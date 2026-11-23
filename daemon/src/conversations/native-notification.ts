import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { isAbsolute } from "node:path";
import { z } from "zod";
import { AgentNotificationSchema, NOTIFICATION_INPUT_MAX_BYTES, type AgentNotification } from "../../../pi-extension/src/protocol/generated.ts";

const ENDPOINT_PATTERN = /^[a-f0-9]{64}$/;
const REQUEST_ID_PATTERN = /^[A-Za-z0-9._:-]{1,128}$/;
const MAX_NOTIFICATION_STDIN_BYTES = NOTIFICATION_INPUT_MAX_BYTES;
const MAX_STDOUT_BYTES = 16 * 1024;
const MAX_STDERR_BYTES = 16 * 1024;
const CLI_TIMEOUT_MS = 55_000;
const TERMINATE_GRACE_MS = 1_000;

const resultSchema = z.object({
  ok: z.boolean().optional(),
  endpoint: z.string().regex(ENDPOINT_PATTERN),
  status: z.enum(["queued", "push-accepted", "posted", "not-posted", "offline", "unavailable", "unknown"]),
  code: z.string().max(64).optional(),
}).passthrough();

export type NativeNotificationResult =
  | { success: true; status: "queued" | "push-accepted" | "posted" | "not-posted" | "offline" | "unavailable" | "unknown" }
  | { success: false; errorCode: "unavailable" | "timeout" | "cancelled" | "native-error" };

export function phoneNotificationRequestId(endpoint: string, conversationId: string, toolCallId: string): string | undefined {
  if (!ENDPOINT_PATTERN.test(endpoint) || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(conversationId) || !toolCallId) return undefined;
  // NUL separators make each trusted owner/call component unambiguous.
  return createHash("sha256")
    .update("kompi-phone-notification\0")
    .update(endpoint).update("\0")
    .update(conversationId).update("\0")
    .update(toolCallId)
    .digest("hex");
}

export interface NativeNotificationInput extends AgentNotification {
  /** Injected by the trusted daemon session owner, never supplied by tool arguments. */
  readonly endpoint: string;
  /** Absolute path to the matched daemon executable, injected by Rust. */
  readonly binary: string;
  readonly requestId: string;
}

type SafeFailure = Extract<NativeNotificationResult, { success: false }>;
type NativeNotificationStatus = Extract<NativeNotificationResult, { success: true }>["status"];
const ownedChildren = new Set<ReturnType<typeof spawn>>();
function terminateOwnedChildren(): void {
  for (const child of ownedChildren) {
    if (child.exitCode === null && child.signalCode === null) {
      try { child.kill("SIGTERM"); } catch { /* Exact child may already have exited. */ }
    }
  }
}
// Best-effort cleanup only; Pi owns the worker's signal and shutdown lifecycle.
process.once("exit", terminateOwnedChildren);

/** Call only with endpoint/binary env inherited from the authenticated worker owner. */
export function sendNativeNotification(input: NativeNotificationInput, signal?: AbortSignal): Promise<NativeNotificationResult> {
  if (!ENDPOINT_PATTERN.test(input.endpoint) || !isAbsolute(input.binary) || !REQUEST_ID_PATTERN.test(input.requestId)) {
    return Promise.resolve({ success: false, errorCode: "unavailable" });
  }
  if (signal?.aborted) return Promise.resolve({ success: false, errorCode: "cancelled" });
  const parsed = AgentNotificationSchema.safeParse({ title: input.title, body: input.body, ...(input.rich === undefined ? {} : { rich: input.rich }) });
  if (!parsed.success) return Promise.resolve({ success: false, errorCode: "native-error" });
  const payload = Buffer.from(JSON.stringify(parsed.data), "utf8");
  if (payload.byteLength > MAX_NOTIFICATION_STDIN_BYTES) {
    return Promise.resolve({ success: false, errorCode: "native-error" });
  }

  return new Promise((resolve) => {
    let child: ReturnType<typeof spawn>;
    try {
      child = spawn(input.binary, [
        "devices", "notify", input.endpoint,
        "--request-id", input.requestId,
        "--json", "--payload-stdin",
      ], { shell: false, windowsHide: true, stdio: ["pipe", "pipe", "pipe"] });
    } catch {
      resolve({ success: false, errorCode: "unavailable" });
      return;
    }

    ownedChildren.add(child);
    let settled = false;
    let dispatched = false;
    let stdout = Buffer.alloc(0);
    let stdoutBytes = 0;
    let stderrBytes = 0;
    let overLimit = false;
    let terminationTimer: ReturnType<typeof setTimeout> | undefined;

    const clearTimers = (): void => {
      clearTimeout(timeout);
      signal?.removeEventListener("abort", onAbort);
    };
    const finish = (result: NativeNotificationResult): void => {
      if (settled) return;
      settled = true;
      clearTimers();
      resolve(result);
    };
    const terminateOwnedChild = (): void => {
      if (child.exitCode !== null || child.signalCode !== null) return;
      try { child.kill("SIGTERM"); } catch { /* The exact child may already have exited. */ }
      terminationTimer = setTimeout(() => {
        if (child.exitCode === null && child.signalCode === null) {
          try { child.kill("SIGKILL"); } catch { /* The exact child may already have exited. */ }
        }
      }, TERMINATE_GRACE_MS);
      terminationTimer.unref();
    };
    const abortResult = (errorCode: SafeFailure["errorCode"]): void => {
      terminateOwnedChild();
      finish(dispatched
        ? { success: true, status: "unknown" }
        : { success: false, errorCode });
    };
    const onAbort = (): void => abortResult("cancelled");
    const timeout = setTimeout(() => {
      terminateOwnedChild();
      finish(dispatched
        ? { success: true, status: "unknown" }
        : { success: false, errorCode: "timeout" });
    }, CLI_TIMEOUT_MS);
    timeout.unref();

    signal?.addEventListener("abort", onAbort, { once: true });
    child.once("spawn", () => {
      if (settled) {
        terminateOwnedChild();
        child.stdin?.end();
        return;
      }
      dispatched = true;
      child.stdin?.end(payload);
    });
    // A closed pipe may accompany a useful typed CLI receipt; the close handler
    // parses that receipt first and otherwise resolves conservatively to unknown.
    child.stdin?.on("error", () => {});
    child.stdout?.on("data", (chunk: Buffer) => {
      stdoutBytes += chunk.length;
      if (stdoutBytes > MAX_STDOUT_BYTES) {
        overLimit = true;
        terminateOwnedChild();
        return;
      }
      stdout = Buffer.concat([stdout, chunk]);
    });
    child.stderr?.on("data", (chunk: Buffer) => {
      stderrBytes += chunk.length;
      if (stderrBytes > MAX_STDERR_BYTES) {
        overLimit = true;
        terminateOwnedChild();
      }
    });
    child.once("error", () => {
      ownedChildren.delete(child);
      finish(dispatched ? { success: true, status: "unknown" } : { success: false, errorCode: "unavailable" });
    });
    child.once("close", () => {
      ownedChildren.delete(child);
      if (terminationTimer) clearTimeout(terminationTimer);
      if (settled) return;
      if (overLimit) {
        finish({ success: true, status: "unknown" });
        return;
      }
      let parsed: z.infer<typeof resultSchema>;
      try { parsed = resultSchema.parse(JSON.parse(stdout.toString("utf8"))); }
      catch {
        finish(dispatched ? { success: true, status: "unknown" } : { success: false, errorCode: "native-error" });
        return;
      }
      if (parsed.endpoint !== input.endpoint) {
        finish({ success: true, status: "unknown" });
        return;
      }
      // A typed result for this exact target is authoritative even when the CLI
      // reports a control failure or exits non-zero; no message/code is echoed.
      finish({ success: true, status: parsed.status });
    });
  });
}

export function notificationToolResult(result: NativeNotificationResult): { success: boolean; resultJson?: string; errorCode?: string; errorMessage?: string } {
  if (!result.success) {
    const messages: Record<SafeFailure["errorCode"], string> = {
      unavailable: "The native notification service is unavailable before delivery.",
      timeout: "The notification request timed out before delivery.",
      cancelled: "The notification request was cancelled before delivery.",
      "native-error": "The native notification service could not accept the request.",
    };
    return { success: false, errorCode: result.errorCode, errorMessage: messages[result.errorCode] };
  }

  const messages: Record<NativeNotificationStatus, string> = {
    queued: "Notification durably queued for phone delivery; this does not mean Android posted it.",
    "push-accepted": "Push provider accepted delivery; this does not mean Android received or posted it.",
    posted: "Android confirmed posting; this does not mean the user saw or read it.",
    "not-posted": "Android did not post the notification.",
    offline: "The daemon reports the phone offline; no durable acceptance was reported.",
    unavailable: "No UnifiedPush provider or connected delivery route is available.",
    unknown: "Delivery outcome is unknown; the phone may have acted. Do not retry automatically.",
  };
  return {
    success: true,
    resultJson: JSON.stringify({ status: result.status, meaning: messages[result.status] }),
  };
}
