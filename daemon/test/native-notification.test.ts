import { afterEach, expect, test } from "bun:test";
import { execFileSync } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile, chmod } from "node:fs/promises";
import { existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { sendNativeNotification, notificationToolResult, phoneNotificationRequestId } from "../src/conversations/native-notification.js";

const directories: string[] = [];
const endpoint = "a".repeat(64);

async function fixture(script: (directory: string) => string): Promise<{ directory: string; binary: string }> {
  const directory = await mkdtemp(join(tmpdir(), "kompi-native-notification-"));
  directories.push(directory);
  const binary = join(directory, "kompi-fixture");
  await writeFile(binary, `#!${process.execPath}\n${script(directory)}\n`);
  await chmod(binary, 0o700);
  return { directory, binary };
}

async function waitFor(predicate: () => boolean, label: string): Promise<void> {
  const deadline = Date.now() + 3_000;
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error(`Timed out waiting for ${label}`);
    await Bun.sleep(5);
  }
}

afterEach(async () => {
  for (const directory of directories.splice(0)) await rm(directory, { recursive: true, force: true });
});

test("importing the notification helper does not take over Pi signal handling", () => {
  const moduleUrl = new URL("../src/conversations/native-notification.ts", import.meta.url).href;
  const script = `
const before = [process.listenerCount("SIGINT"), process.listenerCount("SIGTERM")];
await import(${JSON.stringify(moduleUrl)});
const after = [process.listenerCount("SIGINT"), process.listenerCount("SIGTERM")];
process.stdout.write(JSON.stringify({ before, after }));
`;
  const output = execFileSync(process.execPath, ["-e", script], { encoding: "utf8", maxBuffer: 4096 });
  const result = JSON.parse(output) as { before: number[]; after: number[] };
  expect(result.after).toEqual(result.before);
});

test("phone notification IDs bind peer, conversation and Pi tool call with stable unambiguous hashing", () => {
  const conversation = "11111111-1111-4111-8111-111111111111";
  const stable = phoneNotificationRequestId(endpoint, conversation, "call-1");
  expect(stable).toMatch(/^[a-f0-9]{64}$/);
  expect(phoneNotificationRequestId(endpoint, conversation, "call-1")).toBe(stable);
  expect(phoneNotificationRequestId("b".repeat(64), conversation, "call-1")).not.toBe(stable);
  expect(phoneNotificationRequestId(endpoint, "22222222-2222-4222-8222-222222222222", "call-1")).not.toBe(stable);
  expect(phoneNotificationRequestId(endpoint, conversation, "call-2")).not.toBe(stable);
  expect(phoneNotificationRequestId("model-target", conversation, "call-1")).toBeUndefined();
});

test("CLI typed known outcomes survive ok:false and nonzero exit when the peer matches", async () => {
  const statuses = ["unknown", "offline", "not-posted"] as const;
  const { binary } = await fixture(() => "");
  for (const status of statuses) {
    await writeFile(binary, `#!${process.execPath}\nconst status = ${JSON.stringify(status)}; process.stdout.write(JSON.stringify({ ok: false, endpoint: ${JSON.stringify(endpoint)}, status })); process.exitCode = 9;\n`);
    await chmod(binary, 0o700);
    const result = await sendNativeNotification({ endpoint, binary, requestId: "stable-id", title: "title", body: "body" });
    expect(result).toEqual({ success: true, status });
  }
});

test("typed status for another endpoint is conservatively unknown", async () => {
  const { binary } = await fixture(() => `process.stdout.write(JSON.stringify({ ok: false, endpoint: ${JSON.stringify("b".repeat(64))}, status: "offline" })); process.exitCode = 9;`);
  expect(await sendNativeNotification({ endpoint, binary, requestId: "stable-id", title: "title", body: "body" }))
    .toEqual({ success: true, status: "unknown" });
});

test("phone notification uses only injected endpoint and carries stable CLI request id", async () => {
  const { directory, binary } = await fixture((dir) => `
const { writeFile } = await import("node:fs/promises");
let input = "";
for await (const chunk of process.stdin) input += chunk.toString("utf8");
await writeFile(${JSON.stringify(join(dir, "args.json"))}, JSON.stringify({ args: process.argv.slice(2), input }));
process.stdout.write(JSON.stringify({ ok: true, endpoint: ${JSON.stringify(endpoint)}, status: "queued" }));
`);
  const result = await sendNativeNotification({
    endpoint, binary, requestId: "f".repeat(64), title: "private title fixture", body: "private body fixture",
  });
  expect(result).toEqual({ success: true, status: "queued" });
  const invocation = JSON.parse(await readFile(join(directory, "args.json"), "utf8")) as { args: string[]; input: string };
  expect(invocation.args).toEqual(["devices", "notify", endpoint, "--request-id", "f".repeat(64), "--json", "--payload-stdin"]);
  expect(invocation.args.join(" ")).not.toContain("private title fixture");
  expect(invocation.args.join(" ")).not.toContain("private body fixture");
  expect(JSON.parse(invocation.input)).toEqual({ title: "private title fixture", body: "private body fixture" });
  const toolResult = notificationToolResult(result);
  expect(toolResult.resultJson).toContain("durably queued");
  expect(toolResult.resultJson).not.toContain("private title fixture");
  expect(toolResult.resultJson).not.toContain("private body fixture");
});

test("push acceptance is distinct from Android posting and user receipt", async () => {
  const { binary } = await fixture(() => `process.stdout.write(JSON.stringify({ ok: true, endpoint: ${JSON.stringify(endpoint)}, status: "push-accepted" }));`);
  const result = await sendNativeNotification({ endpoint, binary, requestId: "stable-id", title: "title", body: "body" });
  expect(result).toEqual({ success: true, status: "push-accepted" });
  const rendered = notificationToolResult(result).resultJson ?? "";
  expect(rendered).toContain("provider accepted");
  expect(rendered).not.toContain("Android posted");
  expect(rendered).not.toContain("user saw or read");
});

test("missing optional UnifiedPush route remains an ordinary unavailable outcome", async () => {
  const { binary } = await fixture(() => `process.stdout.write(JSON.stringify({ ok: true, endpoint: ${JSON.stringify(endpoint)}, status: "unavailable" }));`);
  const result = await sendNativeNotification({ endpoint, binary, requestId: "stable-id", title: "title", body: "body" });
  expect(result).toEqual({ success: true, status: "unavailable" });
  const rendered = notificationToolResult(result).resultJson ?? "";
  expect(rendered).toContain("No UnifiedPush provider or connected delivery route is available");
  expect(rendered).not.toContain("fatal");
});

test("native CLI failure output is discarded and never echoes notification content", async () => {
  const title = "private-title-sentinel";
  const body = "private-body-sentinel";
  const { binary } = await fixture(() => `process.stderr.write(process.argv.join(" ")); process.exit(9);`);
  const result = await sendNativeNotification({ endpoint, binary, requestId: "stable-id", title, body });
  expect(result).toEqual({ success: true, status: "unknown" });
  const output = notificationToolResult(result);
  expect(output.resultJson).not.toContain(title);
  expect(output.resultJson).not.toContain(body);
  expect(JSON.stringify(output)).not.toContain(title);
  expect(JSON.stringify(output)).not.toContain(body);
});

test("worker-owner cancellation terminates only the owned native CLI and returns unknown after spawn", async () => {
  const { directory, binary } = await fixture((dir) => `
const { rename, writeFile } = await import("node:fs/promises");
process.on("SIGTERM", async () => { await writeFile(${JSON.stringify(join(dir, "exited"))}, "stopped"); process.exit(0); });
// Publish readiness only once the signal handler and complete PID are available.
await writeFile(${JSON.stringify(join(dir, "pid.tmp"))}, String(process.pid));
await rename(${JSON.stringify(join(dir, "pid.tmp"))}, ${JSON.stringify(join(dir, "pid"))});
await new Promise(() => {});
`);
  const owner = new AbortController();
  const pending = sendNativeNotification({ endpoint, binary, requestId: "stable-id", title: "title", body: "body" }, owner.signal);
  await waitFor(() => existsSync(join(directory, "pid")), "native CLI readiness");
  const pid = Number(await readFile(join(directory, "pid"), "utf8"));
  owner.abort();
  expect(await pending).toEqual({ success: true, status: "unknown" });
  await waitFor(() => existsSync(join(directory, "exited")), "owned CLI termination");
  await waitFor(() => {
    try { process.kill(pid, 0); return false; } catch { return true; }
  }, "owned CLI exit");
});

test("invalid trusted-route inputs fail closed without spawning a command", async () => {
  const { binary } = await fixture(() => `throw new Error("must not run");`);
  expect(await sendNativeNotification({ endpoint: "model-chosen", binary, requestId: "id", title: "title", body: "body" })).toEqual({ success: false, errorCode: "unavailable" });
  expect(await sendNativeNotification({ endpoint, binary: "relative/kompi", requestId: "id", title: "title", body: "body" })).toEqual({ success: false, errorCode: "unavailable" });
  expect(await sendNativeNotification({ endpoint, binary, requestId: "bad id", title: "title", body: "body" })).toEqual({ success: false, errorCode: "unavailable" });
});
