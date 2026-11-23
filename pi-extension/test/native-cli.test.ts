import { after, test } from "node:test";
import assert from "node:assert/strict";
import { chmod, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describeDeviceNotificationStatus, NativeBridge, notificationRequestId } from "../src/service.js";

const priorBinary = process.env.KOMPI_BIN;
const priorFixtureArgs = process.env.KOMPI_FIXTURE_ARGS;
const priorFixtureStdin = process.env.KOMPI_FIXTURE_STDIN;
const temporaryDirectories: string[] = [];
after(async () => {
  if (priorBinary === undefined) delete process.env.KOMPI_BIN;
  else process.env.KOMPI_BIN = priorBinary;
  if (priorFixtureArgs === undefined) delete process.env.KOMPI_FIXTURE_ARGS;
  else process.env.KOMPI_FIXTURE_ARGS = priorFixtureArgs;
  if (priorFixtureStdin === undefined) delete process.env.KOMPI_FIXTURE_STDIN;
  else process.env.KOMPI_FIXTURE_STDIN = priorFixtureStdin;
  for (const directory of temporaryDirectories) await rm(directory, { recursive: true, force: true });
});

test("terminal notification honors typed statuses despite CLI ok:false and nonzero exit", async () => {
  const directory = await mkdtemp(join(tmpdir(), "kompi-notify-status-"));
  temporaryDirectories.push(directory);
  const binary = join(directory, "kompi-fixture");
  const capture = join(directory, "invocation.json");
  process.env.KOMPI_BIN = binary;
  const statuses = ["queued", "push-accepted", "posted", "not-posted", "unknown", "offline", "unavailable"] as const;
  for (const status of statuses) {
    await writeFile(binary, `#!${process.execPath}\nconst { writeFile } = await import("node:fs/promises"); let input = ""; for await (const chunk of process.stdin) input += chunk.toString("utf8"); await writeFile(${JSON.stringify(capture)}, JSON.stringify({ args: process.argv.slice(2), input })); process.stdout.write(JSON.stringify({ ok: false, endpoint: ${JSON.stringify("a".repeat(64))}, status: ${JSON.stringify(status)}, message: "private title private body" })); process.exitCode = 9;\n`, { mode: 0o700 });
    await chmod(binary, 0o700);
    const result = await new NativeBridge().sendNotification("a".repeat(64), "private title", "private body", "f".repeat(64));
    assert.equal(result.status, status);
    assert.match(describeDeviceNotificationStatus(result.status), /does not mean|available|offline|did not post|unknown|queued/i);
    assert.equal(JSON.stringify(result).includes("private title"), false);
    assert.equal(JSON.stringify(result).includes("private body"), false);
  }
  const invocation = JSON.parse(await readFile(capture, "utf8")) as { args: string[]; input: string };
  assert.deepEqual(invocation.args, ["devices", "notify", "a".repeat(64), "--request-id", "f".repeat(64), "--json", "--payload-stdin"]);
  assert.equal(invocation.args.join(" ").includes("private title"), false);
  assert.equal(invocation.args.join(" ").includes("private body"), false);
  assert.deepEqual(JSON.parse(invocation.input), { title: "private title", body: "private body" });
  assert.equal(notificationRequestId("a".repeat(64), "call-stable"), notificationRequestId("a".repeat(64), "call-stable"));
  assert.notEqual(notificationRequestId("a".repeat(64), "call-stable"), notificationRequestId("b".repeat(64), "call-stable"));
  assert.match(notificationRequestId("a".repeat(64), "call-stable"), /^[a-f0-9]{64}$/);
});

test("notification private stdin keeps title/body out of argv and failure details", async () => {
  const directory = await mkdtemp(join(tmpdir(), "kompi-notify-cli-"));
  temporaryDirectories.push(directory);
  const binary = join(directory, "kompi-fails");
  const capturedArgs = join(directory, "args.txt");
  const capturedStdin = join(directory, "stdin.json");
  process.env.KOMPI_FIXTURE_ARGS = capturedArgs;
  process.env.KOMPI_FIXTURE_STDIN = capturedStdin;
  await writeFile(binary, "#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$KOMPI_FIXTURE_ARGS\"\ncat > \"$KOMPI_FIXTURE_STDIN\"\nprintf '%s\\n' 'private title private body' >&2\nexit 17\n", { mode: 0o700 });
  await chmod(binary, 0o700);
  process.env.KOMPI_BIN = binary;

  const title = "private-title-1b848f";
  const body = "private-body-92f30a";
  const requestId = notificationRequestId("a".repeat(64), "fixture-tool-call");
  const result = await new NativeBridge().sendNotification("a".repeat(64), title, body, requestId);
  assert.equal(result.status, "unknown");
  assert.equal(JSON.stringify(result).includes(title), false);
  assert.equal(JSON.stringify(result).includes(body), false);
  const argsText = await readFile(capturedArgs, "utf8");
  assert.equal(argsText.includes(title), false);
  assert.equal(argsText.includes(body), false);
  assert.deepEqual(JSON.parse(await readFile(capturedStdin, "utf8")), { title, body });
});
