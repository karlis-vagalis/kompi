import { execFile as execFileCallback, spawn } from "node:child_process";
import { access } from "node:fs/promises";
import { delimiter, dirname, join, resolve } from "node:path";
import { promisify } from "node:util";
import { fileURLToPath } from "node:url";
import { z } from "zod";
import { parseControlMessage, PairingPayloadSchema, NOTIFICATION_INPUT_MAX_BYTES } from "./protocol/generated.js";

const execFile = promisify(execFileCallback);
const extensionDirectory = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = resolve(extensionDirectory, "../..");

export class NativeCliError extends Error {
  constructor(message: string, readonly exitCode?: number) { super(message); this.name = "NativeCliError"; }
}

export class NotificationNotDispatchedError extends Error {
  constructor(message: string, readonly code: string = "not-dispatched") { super(message); this.name = "NotificationNotDispatchedError"; }
}

export function serviceUnavailable(error: unknown): NativeCliError {
  const detail = error instanceof Error ? error.message : String(error);
  return new NativeCliError(
    `${detail}. If the service is unavailable, run foreground 'kompi serve' under an external supervisor; the Pi extension never starts or stops it.`,
  );
}

/** Resolve only an explicit override, this checkout's built Rust binary, or PATH. */
export async function kompiBinary(): Promise<string> {
  const override = process.env.KOMPI_BIN;
  if (override) {
    const path = resolve(override);
    await assertExecutable(path, "KOMPI_BIN");
    return path;
  }

  const local = join(repositoryRoot, "target", "release", process.platform === "win32" ? "kompi.exe" : "kompi");
  if (await isExecutable(local)) return local;

  for (const directory of (process.env.PATH ?? "").split(delimiter).filter(Boolean)) {
    const candidate = join(directory, process.platform === "win32" ? "kompi.exe" : "kompi");
    if (await isExecutable(candidate)) return candidate;
  }
  throw new Error(
    "Native kompi CLI was not found. Build with `cargo build --release -p kompi-daemon`, " +
    "install the resulting target/release/kompi on PATH, or set KOMPI_BIN to its path.",
  );
}

async function isExecutable(path: string): Promise<boolean> {
  try { await access(path, process.platform === "win32" ? undefined : 1); return true; } catch { return false; }
}
async function assertExecutable(path: string, source: string): Promise<void> {
  if (!await isExecutable(path)) throw new Error(`${source} does not point to an executable kompi binary: ${path}`);
}

const SERVICE_CLIENT_COMMANDS = new Set(["status", "pair", "devices"]);

/** Always passes client-operation argument vectors directly to execFile; lifecycle is external. */
interface RunOptions { maxBuffer?: number; operationLabel?: string; redactInput?: boolean; }

async function runKompi(args: readonly string[], options: RunOptions = {}): Promise<string> {
  const command = args[0];
  const operationLabel = options.operationLabel ?? `kompi ${command ?? "command"}`;
  if (!command || !SERVICE_CLIENT_COMMANDS.has(command)) {
    throw new NativeCliError("The Pi extension may invoke only status, pair, and devices client operations; run `kompi serve` under an external supervisor.");
  }
  const binary = await kompiBinary();
  try {
    const result = await execFile(binary, [...args], {
      cwd: repositoryRoot,
      env: process.env,
      encoding: "utf8",
      maxBuffer: options.maxBuffer ?? 2 * 1024 * 1024,
      windowsHide: true,
    });
    return result.stdout;
  } catch (error) {
    const cause = error as { stderr?: string; message?: string; code?: number | string };
    const detail = options.redactInput
      ? `${operationLabel} failed; command input omitted for privacy.`
      : cause.stderr?.trim() || cause.message || String(error);
    throw new NativeCliError(detail, typeof cause.code === "number" ? cause.code : undefined);
  }
}

const MAX_NOTIFICATION_STDIN_BYTES = NOTIFICATION_INPUT_MAX_BYTES;
const MAX_NOTIFICATION_OUTPUT_BYTES = 16 * 1024;
const NOTIFICATION_TIMEOUT_MS = 45_000;

/** Send the notification payload through a bounded private stdin pipe, never argv. */
export async function runNotificationJsonOutput(
  args: readonly string[],
  payload: string,
  options: RunOptions & { signal?: AbortSignal; timeoutMs?: number } = {},
): Promise<string> {
  if (args[0] !== "devices" || args[1] !== "notify" || !args.includes("--payload-stdin") || args.includes("--title") || args.includes("--body")) {
    throw new NativeCliError("Only stdin-payload `kompi devices notify` may capture a failure receipt.");
  }
  const input = Buffer.from(payload, "utf8");
  if (input.byteLength > MAX_NOTIFICATION_STDIN_BYTES) throw new NotificationNotDispatchedError("Notification payload exceeds the private input limit.");
  if (options.signal?.aborted) throw new NotificationNotDispatchedError("Notification request was cancelled before dispatch.", "cancelled");

  const binary = await kompiBinary();
  if (options.signal?.aborted) throw new NotificationNotDispatchedError("Notification request was cancelled before dispatch.", "cancelled");
  return new Promise((resolve, reject) => {
    const child = spawn(binary, [...args], {
      cwd: repositoryRoot,
      env: process.env,
      windowsHide: true,
      shell: false,
      stdio: ["pipe", "pipe", "pipe"],
    });
    const stdout: Buffer[] = [];
    let stdoutBytes = 0;
    let stderrBytes = 0;
    let dispatched = false;
    let settled = false;
    let timeout: ReturnType<typeof setTimeout> | undefined;
    let terminateTimeout: ReturnType<typeof setTimeout> | undefined;

    const cleanup = () => {
      if (timeout) clearTimeout(timeout);
      options.signal?.removeEventListener("abort", onAbort);
    };
    const finish = (error?: Error, output = "") => {
      if (settled) return;
      settled = true;
      cleanup();
      if (error) reject(error);
      else resolve(output);
    };
    const terminate = () => {
      if (child.exitCode !== null || child.signalCode !== null) return;
      try { child.kill("SIGTERM"); } catch { /* exact child may already be gone */ }
      terminateTimeout = setTimeout(() => {
        if (child.exitCode === null && child.signalCode === null) {
          try { child.kill("SIGKILL"); } catch { /* exact child may already be gone */ }
        }
      }, 1_000);
      terminateTimeout.unref?.();
    };
    const failAfterSpawn = (message: string) => {
      terminate();
      finish(new NativeCliError(message));
    };
    const onAbort = () => {
      if (dispatched) failAfterSpawn("Notification delivery outcome is unknown after cancellation.");
      else { terminate(); finish(new NotificationNotDispatchedError("Notification request was cancelled before dispatch.", "cancelled")); }
    };

    child.once("error", (error) => {
      if (dispatched) finish(new NativeCliError("Notification delivery outcome is unknown after CLI failure."));
      else finish(error);
    });
    child.stdout?.on("data", (chunk: Buffer | string) => {
      const data = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
      stdoutBytes += data.byteLength;
      if (stdoutBytes > MAX_NOTIFICATION_OUTPUT_BYTES) {
        failAfterSpawn("Notification CLI output exceeded its private limit.");
        return;
      }
      stdout.push(data);
    });
    child.stderr?.on("data", (chunk: Buffer | string) => {
      stderrBytes += Buffer.isBuffer(chunk) ? chunk.byteLength : Buffer.byteLength(chunk);
      if (stderrBytes > MAX_NOTIFICATION_OUTPUT_BYTES) failAfterSpawn("Notification CLI diagnostic output exceeded its private limit.");
    });
    // Wait for close so a typed failure receipt can outrank a broken-pipe error.
    child.stdin?.on("error", () => {});
    child.once("spawn", () => {
      if (settled) { terminate(); child.stdin?.end(); return; }
      dispatched = true;
      child.stdin?.end(input);
    });
    child.once("close", (code) => {
      if (terminateTimeout) clearTimeout(terminateTimeout);
      if (settled) return;
      const output = Buffer.concat(stdout).toString("utf8");
      if (output) finish(undefined, output);
      else if (code === 0) finish(undefined, output);
      else finish(new NativeCliError("Native notification CLI returned no delivery receipt."));
    });

    options.signal?.addEventListener("abort", onAbort, { once: true });
    if (options.signal?.aborted) onAbort();
    if (!settled) {
      timeout = setTimeout(() => {
        if (dispatched) failAfterSpawn("Notification delivery outcome is unknown after CLI timeout.");
        else { terminate(); finish(new NotificationNotDispatchedError("Native notification CLI timed out before dispatch.", "timeout")); }
      }, options.timeoutMs ?? NOTIFICATION_TIMEOUT_MS);
      timeout.unref?.();
    }
  });
}

async function runJson(args: readonly string[], options: RunOptions = {}) {
  const stdout = await runKompi(args, options);
  const operationLabel = options.operationLabel ?? `kompi ${args[0] ?? "command"}`;
  let result: unknown;
  try { result = JSON.parse(stdout); }
  catch {
    const message = `${operationLabel} did not return valid JSON${options.redactInput ? "; command input omitted for privacy" : ""}`;
    throw options.redactInput ? new NativeCliError(message) : new Error(message);
  }
  const envelope = z.discriminatedUnion("ok", [
    z.object({ ok: z.literal(true) }).passthrough(),
    z.object({ ok: z.literal(false), error: z.object({ code: z.string(), message: z.string() }) }),
  ]).safeParse(result);
  if (!envelope.success) {
    const message = `${operationLabel} returned an invalid JSON result${options.redactInput ? "; command input omitted for privacy" : ""}`;
    throw options.redactInput ? new NativeCliError(message) : new Error(message);
  }
  if (!envelope.data.ok) throw new NativeCliError(envelope.data.error.message);
  const { ok, ...payload } = envelope.data;
  return payload;
}

export async function runStatusJson() {
  const result = parseControlMessage({ type: "status-result", payload: await runJson(["status", "--json"]) });
  if (result.type !== "status-result") throw new NativeCliError("Invalid status result");
  return result.payload;
}

export async function runDevicesJson() {
  const result = parseControlMessage({ type: "devices-result", payload: await runJson(["devices", "list", "--json"]) });
  if (result.type !== "devices-result") throw new NativeCliError("Invalid devices list");
  return result.payload;
}

export async function runRevokeJson(endpoint: string) {
  z.object({ operation: z.literal("device-revoked") }).parse(await runJson(["devices", "revoke", endpoint, "--json"]));
}

export async function runPairJson(args: readonly string[]) {
  const result = z.object({ pairing: z.object({ qr: z.string().max(8192), terminalQr: z.string().min(1),
    pngBase64: z.string().min(1).base64(), name: z.string().min(1).max(128), expiresAt: z.string().datetime({ offset: true }), token: z.string().min(1).max(8192).optional(),
  }) }).parse(await runJson(args));
  try { PairingPayloadSchema.parse(JSON.parse(result.pairing.qr)); }
  catch { throw new NativeCliError("Invalid pairing QR payload"); }
  return result;
}
