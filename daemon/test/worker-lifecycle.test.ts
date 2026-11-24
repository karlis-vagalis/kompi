import { afterEach, expect, test } from "bun:test";
import { spawn, spawnSync, type ChildProcessWithoutNullStreams } from "node:child_process";
import { once } from "node:events";
import { mkdir, mkdtemp, readFile, writeFile, chmod, rm } from "node:fs/promises";
import { existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { randomUUID } from "node:crypto";
import { makeWorkerRequest, parseWorkerEvent, type WorkerRequest } from "../../pi-extension/src/protocol/generated.ts";

const owned: Array<{ directory: string; child: ChildProcessWithoutNullStreams }> = [];

// An isolated CLI protocol peer, never the installed Pi or its settings/credentials.
const fakePi = `#!${process.execPath}
import { appendFileSync, existsSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
const dir = process.env.FAKE_PI_DIRECTORY;
if (process.platform === "win32") {
  const owner = process.argv.find(arg => /transcript-owner\\.(?:js|ts)$/.test(arg));
  const { default: install } = await import(pathToFileURL(owner).href);
  install({ on(event, listener) { if (event === "session_start") listener({}, { sessionManager: { getSessionFile: () => join(dir, "session.jsonl") } }); } });
}
writeFileSync(dir + "/pid", String(process.pid));
const log = (value) => appendFileSync(dir + "/events", value + "\\n");
const send = (value) => process.stdout.write(JSON.stringify(value) + "\\n");
let firstState = true;
async function respond(command) {
  log(command.type);
  if (command.type === "get_state" && firstState) {
    firstState = false;
    while (process.env.FAKE_DELAY_STATE === "1" && !existsSync(dir + "/release-state")) await new Promise(r => setTimeout(r, 5));
  }
  if (command.type === "get_entries") {
    while (process.env.FAKE_DELAY_SNAPSHOT === "1" && !existsSync(dir + "/release-snapshot")) await new Promise(r => setTimeout(r, 5));
  }
  if (process.platform !== "win32" && process.env.KOMPI_PI_TRANSCRIPT_LAUNCH === "fresh") writeFileSync(dir + "/session.jsonl.kompi-lock", "");
  const state = { followUpMode: "one-at-a-time", sessionId: "ce4026c9-80b4-4f32-b5a8-84490101680d", sessionFile: join(dir, "session.jsonl"), sessionName: "Lifecycle", model: { provider: "fake", id: "model" }, thinkingLevel: "off" };
  const large = command.type === "get_entries" && existsSync(dir + "/large-snapshot");
  if (large) send({ type: "fixture_event", text: "e".repeat(1024 * 1024) });
  const data = command.type === "get_state" ? state
    : command.type === "get_entries" ? { entries: [], leafId: null, ...(large ? { text: "s".repeat(512 * 1024) } : {}) }
    : command.type === "get_available_models" ? { models: [{ provider: "fake", id: "model", name: "Fake" }] }
    : command.type === "get_available_thinking_levels" ? { levels: ["off"] }
    : command.type === "prompt" ? { disposition: "started" } : {};
  const response = { id: command.id, type: "response", command: command.type, success: true, data };
  if (command.type === "get_entries" && existsSync(dir + "/race-snapshot")) {
    process.stdout.write(JSON.stringify(response) + "\\n" + JSON.stringify({type:"fixture_event", text:"after history"}) + "\\n");
  } else send(response);
  if (command.type === "abort") send({ type: "agent_settled", aborted: true });
}
let input = "";
process.stdin.on("data", chunk => {
  input += chunk.toString();
  let index;
  while ((index = input.indexOf("\\n")) >= 0) {
    const line = input.slice(0, index); input = input.slice(index + 1);
    if (line) void respond(JSON.parse(line));
  }
});
process.stdin.on("end", () => { log("stdin-eof"); process.exit(0); });
`;

async function waitFor(predicate: () => boolean | Promise<boolean>, label: string): Promise<void> {
  const deadline = Date.now() + 5_000;
  while (!await predicate()) {
    if (Date.now() >= deadline) throw new Error(`Timed out waiting for ${label}`);
    await Bun.sleep(5);
  }
}

async function fixture(options: { delayState?: boolean; delaySnapshot?: boolean } = {}) {
  const directory = await mkdtemp(join(tmpdir(), "kompi lifecycle & (literal)-"));
  const executable = join(directory, "fake-pi.mjs");
  await writeFile(executable, fakePi);
  await chmod(executable, 0o700);
  let cli = executable;
  if (process.platform === "win32") {
    // A normal global npm layout; the shim itself must never execute.
    const pkg = join(directory, "node_modules/@earendil-works/pi-coding-agent");
    await mkdir(pkg, { recursive: true });
    await writeFile(join(pkg, "package.json"), JSON.stringify({ bin: { pi: "fake-pi.mjs" } }));
    await writeFile(join(pkg, "fake-pi.mjs"), fakePi);
    cli = join(directory, "pi.cmd");
    await writeFile(cli, "@exit /b 99\r\n");
  }
  const child = spawn(process.execPath, [resolve(import.meta.dir, "../src/worker.ts")], {
    cwd: directory,
    env: { KOMPI_NATIVE_BINARY: resolve(import.meta.dir, "../../target/debug/kompi.exe"), ...process.env, HOME: directory, KOMPI_PI_CLI: cli, KOMPI_ANDROID_TOOL_SOCKET: "",
      FAKE_PI_DIRECTORY: directory, FAKE_DELAY_STATE: options.delayState ? "1" : "0",
      FAKE_DELAY_SNAPSHOT: options.delaySnapshot ? "1" : "0" },
    stdio: ["pipe", "pipe", "pipe"],
  });
  owned.push({ directory, child });
  let stdout = "";
  let stderr = "";
  child.stdout.on("data", chunk => { stdout += chunk.toString(); });
  child.stderr.on("data", chunk => { stderr += chunk.toString(); });
  child.stdin.on("error", () => {}); // Fail-closed deliberately destroys its input.
  const exited = once(child, "close");
  const conversationId = randomUUID();
  const send = (message: WorkerRequest | { type: "create"; payload: { conversationId: string; sessionName: string } }) => {
    const request = message.type === "create" ? { ...message, payload: { ...message.payload, createdAt: "2026-11-13T00:00:00Z" } } : message;
    return child.stdin.write(`${JSON.stringify(makeWorkerRequest(randomUUID(), request))}\n`);
  };
  const frames = () => stdout.trim().split("\n").filter(Boolean).map(line => parseWorkerEvent(JSON.parse(line)));
  const events = async () => existsSync(join(directory, "events")) ? (await readFile(join(directory, "events"), "utf8")).trim().split("\n") : [];
  const finish = async (expectedCode: number) => {
    await waitFor(() => child.exitCode !== null || child.signalCode !== null, "worker exit");
    await exited;
    expect(child.signalCode).toBeNull();
    expect(child.exitCode).toBe(expectedCode);
    expect(await events()).toContain("stdin-eof");
    const pid = Number(await readFile(join(directory, "pid"), "utf8"));
    expect(() => process.kill(pid, 0)).toThrow();
  };
  return { directory, child, conversationId, send, frames, events, finish, stderr: () => stderr };
}

afterEach(async () => {
  for (const { directory, child } of owned.splice(0)) {
    // Only failed-test cleanup may signal these exact isolated owned processes.
    if (child.exitCode === null && child.signalCode === null) child.kill("SIGKILL");
    const pidPath = join(directory, "pid");
    if (existsSync(pidPath)) {
      const pid = Number(await readFile(pidPath, "utf8"));
      try {
        const command = await readFile(`/proc/${pid}/cmdline`, "utf8");
        if (command.includes(join(directory, "fake-pi.mjs"))) process.kill(pid, "SIGKILL");
      } catch { /* The successful path has already reaped its child. */ }
    }
    await rm(directory, { recursive: true, force: true });
  }
});

for (const mode of ["create", "resume"] as const) {
  test(`EOF during delayed ${mode} disposes the late session without publication`, async () => {
    const f = await fixture({ delayState: true });
    if (mode === "resume") await writeFile(join(f.directory, "session.jsonl"), JSON.stringify({ type: "session", version: 3, timestamp:"2026-11-13T00:00:00Z", id: "ce4026c9-80b4-4f32-b5a8-84490101680d", cwd: f.directory }) + "\n");
    f.send(mode === "create"
      ? { type: "create", payload: { conversationId: f.conversationId, sessionName: "Lifecycle" } }
      : { type: "resume", payload: { conversationId: f.conversationId, sessionId: "ce4026c9-80b4-4f32-b5a8-84490101680d", sessionFile: join(f.directory, "session.jsonl") } });
    await waitFor(async () => (await f.events()).includes("get_state"), "opening Pi state request");
    if (mode === "resume" && process.platform === "win32") {
      // The helper has already exited. Its lease must still belong to the Pi child.
      const rival = spawnSync(process.env.KOMPI_NATIVE_BINARY ?? resolve(import.meta.dir, "../../target/debug/kompi.exe"),
        ["__pi-lease", String(process.pid), join(f.directory, "session.jsonl"), "resume"]);
      expect(rival.status).toBe(73);
    }
    f.child.stdin.end();
    await Bun.sleep(30);
    await writeFile(join(f.directory, "release-state"), "");
    await f.finish(0);
    expect(f.frames().some(frame => frame.message.type === "created" || frame.message.type === "ready")).toBe(false);
  }, 10_000);
}

test("invalid input during opening fails closed and disposes its late result", async () => {
  const f = await fixture({ delayState: true });
  f.send({ type: "create", payload: { conversationId: f.conversationId, sessionName: "Lifecycle" } });
  await waitFor(async () => (await f.events()).includes("get_state"), "opening Pi state request");
  f.child.stdin.write("not-json\n");
  await waitFor(() => f.stderr().includes("category=invalid-json-frame"), "fail-closed boundary");
  await writeFile(join(f.directory, "release-state"), "");
  await f.finish(1);
  expect(f.frames()).toHaveLength(0);
  expect(await f.events()).not.toContain("prompt");
}, 10_000);

test("EOF aborts an active prompt and disposes only its owned Pi child", async () => {
  const f = await fixture();
  f.send({ type: "create", payload: { conversationId: f.conversationId, sessionName: "Lifecycle" } });
  await waitFor(() => f.frames().some(frame => frame.message.type === "created"), "created");
  f.send({ type: "prompt", payload: { conversationId: f.conversationId, requestId: randomUUID(), text: "isolated lifecycle fixture", delivery: "followUp", configuration: { provider: "fake", modelId: "model", effort: "off" } } });
  await waitFor(async () => (await f.events()).includes("prompt"), "active prompt");
  f.child.stdin.end();
  await f.finish(0);
  expect(await f.events()).toContain("abort");
  expect(f.frames().some(frame => frame.message.type === "final")).toBe(false);
}, 10_000);

test("a large event followed by a large snapshot leaves the reader available for another request", async () => {
  const f = await fixture();
  f.send({ type: "create", payload: { conversationId: f.conversationId, sessionName: "Lifecycle" } });
  await waitFor(() => f.frames().some(frame => frame.message.type === "created"), "created");
  await writeFile(join(f.directory, "large-snapshot"), "");
  const snapshot = () => f.send({ type: "snapshot", payload: { conversationId: f.conversationId } });
  const completed = () => f.frames().filter(frame => frame.message.type === "snapshot-done").length;
  snapshot();
  await waitFor(() => completed() === 1, "large snapshot");
  await rm(join(f.directory, "large-snapshot"));
  snapshot();
  await waitFor(() => completed() === 2, "subsequent snapshot");
  f.child.stdin.end();
  await f.finish(0);
}, 15_000);

test("an event in the same pipe read after a snapshot response follows the snapshot on the wire", async () => {
  const f = await fixture();
  f.send({ type: "create", payload: { conversationId: f.conversationId, sessionName: "Lifecycle" } });
  await waitFor(() => f.frames().some(frame => frame.message.type === "created"), "created");
  await writeFile(join(f.directory, "race-snapshot"), "");
  f.send({ type: "snapshot", payload: { conversationId: f.conversationId } });
  const kinds = () => f.frames().flatMap(({message}) => message.type === "pi-frame" ? [message.payload.frame.kind] : []);
  await waitFor(() => kinds().includes("event") && kinds().includes("snapshot"), "both records");
  expect(kinds().slice(-2)).toEqual(["snapshot", "event"]);
  f.child.stdin.end();
  await f.finish(0);
});

test("EOF during the prompt snapshot cannot dispatch a prompt afterwards", async () => {
  const f = await fixture({ delaySnapshot: true });
  f.send({ type: "create", payload: { conversationId: f.conversationId, sessionName: "Lifecycle" } });
  await waitFor(() => f.frames().some(frame => frame.message.type === "created"), "created");
  f.send({ type: "prompt", payload: { conversationId: f.conversationId, requestId: randomUUID(), text: "must never dispatch", delivery: "followUp", configuration: { provider: "fake", modelId: "model", effort: "off" } } });
  await waitFor(async () => (await f.events()).includes("get_entries"), "pending snapshot");
  f.child.stdin.end();
  await f.finish(0);
  expect(await f.events()).not.toContain("prompt");
}, 10_000);
