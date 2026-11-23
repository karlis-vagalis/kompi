import { PiReadPressure } from "./pi-read-pressure.js";
import { seal, open } from "./conversations/tool-frame.js";
import { randomBytes, randomUUID } from "node:crypto";
import { once } from "node:events";
import { createServer, type Socket } from "node:net";
import { chmodSync, unlinkSync } from "node:fs";
import { ANDROID_TOOL_ACTION_MAX_BYTES, ANDROID_TOOL_TIMEOUT_MS, makeWorkerEvent, parseWorkerRequest, type WorkerEvent } from "../../pi-extension/src/protocol/generated.ts";
import { conversationId } from "./conversations/domain.ts";
import { PiRpcSessionFactory, ModelRequestError, PromptAborted, FollowUpRejected, type ConversationSession, type FollowUpBoundary } from "./conversations/rpc-session-factory.ts";
import { PiTranscriptOwnedError } from "./conversations/pi-rpc-client.ts";

import { discoverModels } from "./conversations/model-selection.ts";

// The operator extension consumes this established environment flag to avoid
// recursively attaching Kompi inside its own Pi CLI subprocess.
process.env.KOMPI_SDK_WORKER = "1";

const MAX_LINE_BYTES = 65_536;
const MAX_QUEUED_OUTPUT_BYTES = MAX_LINE_BYTES * 4;
let failed = false;

type WorkerLifetime = "running" | "closing" | "closed";
let lifetime: WorkerLifetime = "running";
const ownerClosed = new AbortController();
const requestsInFlight = new Set<Promise<void>>();
const sessionDisposals = new WeakMap<ConversationSession, Promise<void>>();
let closing: Promise<void> | undefined;
let stdoutBlocked = false;
let queuedOutputBytes = 0;
const queuedOutput: string[] = [];
const utf8 = new TextDecoder("utf-8", { fatal: true });
const writeFrame = process.stdout.write.bind(process.stdout);

// Keep worker stdout reserved for frames and suppress arbitrary dependency logs.
// Pi extensions run in the CLI child, whose stderr is separately drained.
const stderr = process.stderr;
const stderrWrite = stderr.write.bind(stderr);
const suppressLog = (): void => { stderrWrite("[pi-worker] unrecognized output suppressed\n"); };
type WorkerPhase = "create" | "resume" | "prompt" | "abort" | "dispose" | "dispatch";
type WorkerFailureCategory = "session-create-failed" | "session-resume-failed" | "prompt-failed" | "abort-failed" | "dispose-failed" | "invalid-request" | "invalid-json-frame" | "invalid-worker-request" | "oversized-ipc-frame" | "truncated-ipc-frame" | "stdin-read-failed" | "response-frame-too-large" | "response-backpressure-overflow" | "follow-up-transfer-failed" | "uncaught-exception";
const knownErrorNames = new Set(["Error", "TypeError", "RangeError", "ReferenceError", "SyntaxError", "AggregateError", "AbortError"]);
function safeErrorName(error: unknown): string {
  if (!(error instanceof Error)) return "Error";
  return knownErrorNames.has(error.name) ? error.name : "Error";
}
function safeModuleLocation(error: unknown): string {
  if (!(error instanceof Error) || typeof error.stack !== "string") return "unavailable";
  for (const line of error.stack.split("\n")) {
    const match = line.match(/node_modules\/((?:@[A-Za-z0-9._-]+\/)?[A-Za-z0-9._-]+)\/([^/\s():]+)(?::(\d+))?/);
    if (match) return `${match[1]}/${match[2]}:${match[3] ?? "?"}`;
  }
  return "unavailable";
}
function reportFailure(phase: WorkerPhase, category: WorkerFailureCategory, error: unknown): void {
  // Only emit fixed categories, a restricted Error class and a module basename.
  // Never emit exception messages, prompt data, extension output or credentials.
  stderrWrite(`[pi-worker] failure phase=${phase} category=${category} error=${safeErrorName(error)} module=${safeModuleLocation(error)}\n`);
}
process.on("uncaughtExceptionMonitor", (error) => { reportFailure(currentWorkerPhase(), "uncaught-exception", error); });
for (const method of ["log", "info", "debug", "warn", "error", "dir", "table", "trace", "assert", "count", "countReset", "timeLog", "timeEnd"]) {
  (console as unknown as Record<string, (...args: unknown[]) => void>)[method] = suppressLog;
}
const suppressStreamWrite = ((...args: Parameters<NodeJS.WriteStream["write"]>) => {
  suppressLog();
  const callback = args.find((arg) => typeof arg === "function") as ((error?: Error | null) => void) | undefined;
  callback?.();
  return true;
}) as NodeJS.WriteStream["write"];
(process.stdout as NodeJS.WriteStream).write = suppressStreamWrite;
(process.stderr as NodeJS.WriteStream).write = suppressStreamWrite;

const timingClock = randomUUID();
type TimingStamp = Extract<WorkerEvent, { type: "chat-timing" }>["payload"]["stamp"];
const stamp = (stage: TimingStamp["stage"]): TimingStamp => ({
  stage, clockId: timingClock, monotonicMs: Math.floor(performance.now()), wallMs: Date.now(),
});
type ActivePrompt = {
  requestId: string;
  readonly envelopeId: string;
  settlementScheduled: boolean;
  members: readonly string[];
  userEntries: string[];
  boundary?: { checkpoint: FollowUpBoundary; queued: string[] };
  firstAssistant?: boolean; firstText?: boolean; lastAssistantEnd?: TimingStamp;
};
function reportTiming(active: ActivePrompt, checkpoint: TimingStamp): void {
  // Diagnostics are dispensable; don't fill the critical worker stdout queue.
  if (!stdoutBlocked) send(active.envelopeId, { type: "chat-timing", payload: { requestId: active.requestId, stamp: checkpoint } });
}

type WorkerSession = {
  readonly conversationId: string;
  readonly session: ConversationSession;
  readonly sessionFile: string;
  entryCursor?: string;
  active?: ActivePrompt;
};
// Rust owns one worker process per phone session. Keep that invariant local too:
// a process can never accidentally attach a second conversation to its CLI runtime.
let liveSession: WorkerSession | undefined;
let openingConversationId: string | undefined;
let openingPhase: "create" | "resume" = "create";
let retiredConversationId: string | undefined;
function currentWorkerPhase(): WorkerPhase {
  if (liveSession?.active) return "prompt";
  if (openingConversationId) return openingPhase;
  return "dispatch";
}
const androidToolSocket = process.env.KOMPI_ANDROID_TOOL_SOCKET;
const bridgeKey = androidToolSocket && process.platform === "win32" ? randomBytes(32) : undefined;
const pendingAndroidTools = new Map<string, { conversationId: string; socket: Socket; reply: (line: string) => void; timer: ReturnType<typeof setTimeout> }>();
const androidToolSockets = new Set<Socket>();
const androidToolServer = androidToolSocket ? createServer((socket) => {
  if (lifetime !== "running" || (bridgeKey && androidToolSockets.size >= 32)) { socket.destroy(); return; }
  androidToolSockets.add(socket);
  socket.once("close", () => { androidToolSockets.delete(socket); });
  socket.setNoDelay(true);
  const challenge = bridgeKey ? randomBytes(32).toString("hex") : "";
  const handshake = bridgeKey ? setTimeout(() => socket.destroy(), 5000) : undefined;
  if (handshake) socket.once("close", () => clearTimeout(handshake));
  if (bridgeKey) socket.write(challenge + "\n");
  socket.on("error", () => socket.destroy());
  const reply = (line: string) => socket.end(bridgeKey ? seal(bridgeKey, "reply:" + challenge, line) + "\n" : line);
  let input = Buffer.alloc(0);
  let received = false;
  socket.on("data", (chunk) => {
    if (lifetime !== "running" || received) { socket.destroy(); return; }
    input = Buffer.concat([input, Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)]);
    if (input.length > (bridgeKey ? 12_400 : 9_216)) { socket.destroy(); return; }
    const newline = input.indexOf(0x0a);
    if (newline < 0) return;
    received = true;
    try {
      const line = new TextDecoder("utf-8", { fatal: true }).decode(input.subarray(0, newline));
      const raw = JSON.parse(bridgeKey ? open(bridgeKey, "request:" + challenge, line) : line) as { requestId?: unknown; tool?: unknown; actionJson?: unknown };
      const actionJson = raw.actionJson;
      if (typeof raw.requestId !== "string" || !/^[0-9a-f-]{36}$/i.test(raw.requestId)
        || (raw.tool !== "phone_action" && raw.tool !== "screen_context" && raw.tool !== "send_notification" && raw.tool !== "offer_artifact")
        || (actionJson !== null && typeof actionJson !== "string")
        || (typeof actionJson === "string" && Buffer.byteLength(actionJson, "utf8") > ANDROID_TOOL_ACTION_MAX_BYTES)) throw new Error("invalid tool request");
      if (handshake) clearTimeout(handshake);
      const tool = raw.tool;
      const live = liveSession;
      if (!live || pendingAndroidTools.has(raw.requestId) || pendingAndroidTools.size >= 8) throw new Error("tool unavailable");
      const requestId = raw.requestId;
      const request: WorkerEvent = tool === "offer_artifact" ? (() => {
        if (!live.active || typeof actionJson !== "string") throw new Error("artifact offer unavailable");
        const params = JSON.parse(actionJson) as { path?: unknown; fileName?: unknown };
        if (typeof params.path !== "string" || params.path.length === 0 || params.path.length > 4096
          || (params.fileName !== undefined && (typeof params.fileName !== "string" || params.fileName.length === 0 || params.fileName.length > 120))) throw new Error("invalid artifact offer");
        return { type: "artifact-offer", payload: { conversationId: live.conversationId, requestId, path: params.path, fileName: params.fileName ?? null } };
      })() : { type: "android-tool-request", payload: { conversationId: live.conversationId, requestId, tool, timeoutMs: ANDROID_TOOL_TIMEOUT_MS, actionJson } };
      const timeoutMs = ANDROID_TOOL_TIMEOUT_MS;
      const timer = setTimeout(() => {
        const pending = pendingAndroidTools.get(requestId);
        if (!pending) return;
        pendingAndroidTools.delete(requestId);
        const errorMessage = tool === "offer_artifact" ? "The host did not finish preparing the file offer." : "Android did not respond before the tool request expired. The phone may already have acted; do not retry automatically.";
        reply(`${JSON.stringify({ requestId, response: { success: false, errorCode: "timeout", errorMessage } })}\n`);
        if (tool !== "offer_artifact") send(`android-tool-cancel-${requestId}`, { type: "android-tool-cancel", payload: { conversationId: live.conversationId, requestId } });
      }, timeoutMs);
      pendingAndroidTools.set(requestId, { conversationId: live.conversationId, socket, reply, timer });
      socket.once("close", () => {
        const pending = pendingAndroidTools.get(requestId);
        if (pending?.socket === socket) {
          clearTimeout(pending.timer);
          pendingAndroidTools.delete(requestId);
          if (tool !== "offer_artifact") send(`android-tool-cancel-${requestId}`, { type: "android-tool-cancel", payload: { conversationId: live.conversationId, requestId } });
        }
      });
      send(`android-tool-request-${requestId}`, request);
    } catch {
      reply(`${JSON.stringify({ requestId: "invalid", response: { success: false, errorCode: "invalid-request", errorMessage: "The Android tool request was invalid." } })}\n`);
    }
  });
}) : undefined;
if (bridgeKey && androidToolServer) {
  androidToolServer.listen({host: "127.0.0.1", port: 0, exclusive: true});
  await once(androidToolServer, "listening");
  const address = androidToolServer.address();
  if (!address || typeof address === "string") throw new Error("tool bridge bind failed");
  process.env.KOMPI_TOOL_BRIDGE_PORT = String(address.port);
  process.env.KOMPI_TOOL_BRIDGE_KEY = bridgeKey.toString("hex");
} else if (androidToolSocket && androidToolServer) {
  try { unlinkSync(androidToolSocket); } catch { /* unique per-worker path may not exist */ }
  androidToolServer.listen(androidToolSocket, () => {
    try { chmodSync(androidToolSocket, 0o600); } catch { /* socket path is unique and private to this worker */ }
  });
}

const factory = new PiRpcSessionFactory({
  requested: (conversationId, interactionId, dialog) => send(`pi-ui-${randomUUID()}`, { type: "ui-request", payload: { conversationId, interactionId, dialog } }),
  resolved: (conversationId, interactionId) => send(`pi-ui-${randomUUID()}`, { type: "ui-resolved", payload: { conversationId, interactionId } }),
  observeTiming: (conversationId, event) => {
    if (lifetime !== "running" || liveSession?.conversationId !== conversationId) return;
    const active = liveSession.active;
    if (!active) return;
    if (event.type === "message_start" && event.message.kind === "assistant" && !active.firstAssistant) {
      active.firstAssistant = true;
      reportTiming(active, stamp("worker_assistant_start"));
    } else if (event.type === "message_update" && event.assistantMessageEvent.type === "text_delta" &&
        event.assistantMessageEvent.delta.length > 0 && !active.firstText) {
      active.firstText = true;
      reportTiming(active, stamp("worker_first_text"));
    } else if (event.type === "message_end" && event.message.kind === "assistant") {
      active.lastAssistantEnd = stamp("worker_assistant_last_end");
    } else if (event.type === "agent_settled") {
      if (active.lastAssistantEnd) reportTiming(active, active.lastAssistantEnd);
      reportTiming(active, stamp("worker_agent_settled"));
    }
  },
  boundary: (conversationId, checkpoint) => {
    const active = liveSession?.conversationId === conversationId ? liveSession.active : undefined;
    if (!active) { void checkpoint.release(false).catch(() => undefined); return; }
    if (active.lastAssistantEnd) {
      reportTiming(active, active.lastAssistantEnd);
      active.lastAssistantEnd = undefined;
    }
    reportTiming(active, stamp("worker_follow_up_boundary"));
    active.boundary = { checkpoint, queued: [] };
    send(active.envelopeId, { type: "follow-up-ready", payload: { stats: checkpoint.stats } });
  },
  event: (conversationId, sessionId, epoch, sequence, event, pause) => {
    if (lifetime !== "running" || (liveSession?.session.epoch === epoch && sessionDisposals.has(liveSession.session))) return;
    const requestId = liveSession?.conversationId === conversationId ? liveSession.active?.requestId : undefined;
    void queuePiRecord(conversationId, sessionId, epoch, sequence, "event", event, requestId ?? null, pause).catch(() => undefined);
    if (liveSession && requestId && (event.type === "message_end" || event.type === "agent_settled")) {
      const settled = event.type === "agent_settled";
      if (settled) liveSession.active!.settlementScheduled = true;
      void syncCanonical(liveSession, requestId, settled).catch(() => undefined);
    }
  },
});

function closeAndroidToolBridge(): void {
  for (const [requestId, pending] of pendingAndroidTools) {
    clearTimeout(pending.timer);
    pending.reply(`${JSON.stringify({ requestId, response: { success: false, errorCode: "unavailable", errorMessage: "The Android tool bridge closed after dispatch. The phone may already have acted; do not retry automatically." } })}\n`);
  }
  pendingAndroidTools.clear();
  // Include incomplete/idle tool clients, not only requests already registered above.
  for (const socket of androidToolSockets) socket.destroySoon();
  androidToolSockets.clear();
  androidToolServer?.close();
  if (androidToolSocket && !bridgeKey) { try { unlinkSync(androidToolSocket); } catch { /* already removed */ } }
}

function send(id: string, message: WorkerEvent): void {
  if (failed || lifetime !== "running") return;
  const encoded = `${JSON.stringify(makeWorkerEvent(id, message))}\n`;
  const bytes = Buffer.byteLength(encoded, "utf8");
  if (bytes > MAX_LINE_BYTES) {
    failClosed("response-frame-too-large");
    return;
  }
  if (stdoutBlocked) {
    if (queuedOutputBytes + bytes > MAX_QUEUED_OUTPUT_BYTES) {
      failClosed("response-backpressure-overflow");
      return;
    }
    queuedOutput.push(encoded);
    queuedOutputBytes += bytes;
    return;
  }
  stdoutBlocked = !writeFrame(encoded);
}

process.stdout.on("drain", () => {
  if (lifetime !== "running") return;
  stdoutBlocked = false;
  while (!stdoutBlocked && queuedOutput.length) {
    const frame = queuedOutput.shift()!;
    queuedOutputBytes -= Buffer.byteLength(frame, "utf8");
    stdoutBlocked = !writeFrame(frame);
  }
});

let canonicalSync: Promise<void> = Promise.resolve();
function syncCanonical(live: WorkerSession, requestId: string | null, settled = false): Promise<void> {
  const userEntries = requestId === null ? undefined : live.active?.userEntries;
  const next = canonicalSync.then(async () => {
    if (lifetime !== "running" || liveSession !== live) return;
    const snapshot = await live.session.snapshot(live.entryCursor);
    if (lifetime !== "running" || liveSession !== live) return;
    const { entries } = snapshot.data;
    if (entries.length) live.entryCursor = entries.at(-1)!.id;
    const record = (kind: "entries" | "settled", data: unknown, id: string | null) => queuePiRecord(live.conversationId, live.session.sessionId, live.session.epoch, snapshot.sequence, kind, data, id);
    let offset = 0;
    // The native queue emits each accepted input as its own user entry. Bind it
    // by queue position (not text); a get_entries delta may contain several at once.
    if (userEntries?.length) {
      for (const [index, entry] of entries.entries()) {
        if (!userEntries.length) break;
        if (entry.type === "message" && (entry.message as { role?: unknown } | undefined)?.role === "user") {
          await record("entries", { entries: entries.slice(offset, index + 1), leafId: entry.id }, userEntries.shift()!);
          offset = index + 1;
        }
      }
    }
    if (offset < entries.length || settled) await record(settled ? "settled" : "entries", { ...snapshot.data, entries: entries.slice(offset) }, requestId);
  });
  canonicalSync = next.catch(() => { failClosed("invalid-json-frame"); });
  return next;
}

let piOutput: Promise<void> = Promise.resolve();
const piPressure = new PiReadPressure();
function queuePiRecord(conversationId: string, sessionId: string, epoch: string, sequence: number, kind: "event" | "snapshot" | "entries" | "settled", value: unknown, requestId: string | null, pause?: (paused: boolean) => void): Promise<void> {
  if (lifetime !== "running") return Promise.resolve();
  const json = JSON.stringify(value);
  const bytes = Buffer.byteLength(json);
  if (bytes > 64 * 1024 * 1024 || piPressure.bytes + bytes > 128 * 1024 * 1024) {
    failClosed("response-backpressure-overflow");
    return Promise.reject(new Error("Pi record exceeds transfer capacity"));
  }
  piPressure.enqueue(bytes, epoch, pause);
  const next = piOutput.then(async () => {
    let offset = 0;
    let part = 0;
    while (offset < json.length) {
      if (lifetime !== "running") return;
      if (stdoutBlocked) await once(process.stdout, "drain", { signal: ownerClosed.signal });
      if (lifetime !== "running") return;
      const fragment = takeUtf16Prefix(json.slice(offset), 8192);
      offset += fragment.length;
      send(`pi-frame-${randomUUID()}`, { type: "pi-frame", payload: { frame: {
        conversationId, sessionId, epoch, sequence, kind, part: part++, last: offset === json.length,
        json: fragment, requestId,
      } } });
    }
  }).finally(() => piPressure.complete(bytes));
  piOutput = next.catch(() => { failClosed("response-backpressure-overflow"); });
  return next;
}

function takeUtf16Prefix(value: string, limit: number): string {
  let result = "";
  for (let index = 0; index < value.length;) {
    const codePoint = value.codePointAt(index)!;
    const width = codePoint > 0xffff ? 2 : 1;
    const safe = codePoint >= 0xd800 && codePoint <= 0xdfff ? "\uFFFD" : String.fromCodePoint(codePoint);
    if (result.length + safe.length > limit) break;
    result += safe;
    index += width;
  }
  return result;
}

function emitError(id: string, code: string, requestId?: string, conversationIdValue?: string, safeMessage?: string): void {
  send(id, {
    type: "error",
    payload: {
      ...(requestId ? { requestId } : {}),
      ...(conversationIdValue ? { conversationId: conversationIdValue } : {}),
      code,
      message: safeMessage ?? safeErrorMessage(code),
    },
  });
}
function safeErrorMessage(code: string): string {
  switch (code) {
    case "invalid-request": return "Worker request was invalid";
    case "session-not-found": return "Conversation session is unavailable";
    case "session-exists": return "Conversation session already exists";
    case "session-busy": return "Conversation already has an active prompt";
    case "request-mismatch": return "Cancellation request does not match the active prompt";
    case "delivery-unsupported": return "Steering delivery is not supported";
    case "session-create-failed": return "Pi CLI could not create the conversation session";
    case "session-resume-failed": return "Pi CLI could not resume the conversation session";
    case "prompt-failed": return "Pi CLI prompt failed";
    case "response-too-large": return "The reply exceeded the worker frame limit; the prompt was stopped";
    case "abort-failed": return "Pi CLI could not abort the prompt";
    case "dispose-failed": return "Pi CLI could not dispose the session";
    default: return "Pi worker operation failed";
  }
}

async function handleRequest(envelope: ReturnType<typeof parseWorkerRequest>): Promise<void> {
  if (lifetime !== "running") return;
  const { id, message } = envelope;
  switch (message.type) {
    case "models-get": {
      try { send(id, { type: "models-result", payload: { catalog: await discoverModels() } }); }
      catch { emitError(id, "models-unavailable", undefined, undefined, "Could not load configured Pi models. Check Pi on the computer."); }
      return;
    }
    case "android-tool-response": {
      const payload = message.payload;
      const pending = pendingAndroidTools.get(payload.requestId);
      if (!pending || pending.conversationId !== payload.conversationId) return;
      clearTimeout(pending.timer);
      pendingAndroidTools.delete(payload.requestId);
      pending.reply(`${JSON.stringify({ requestId: payload.requestId, response: {
        success: payload.success,
        ...(payload.resultJson != null ? { resultJson: payload.resultJson } : {}),
        ...(!payload.success ? { errorCode: payload.errorCode, errorMessage: payload.errorMessage } : {}),
      } })}\n`);
      return;
    }
    case "ui-response": {
      const payload = message.payload;
      await factory.answerUi(conversationId(payload.conversationId), payload.interactionId, payload.answer);
      return;
    }
    case "create": {
      const payload = message.payload;
      if (liveSession || openingConversationId || retiredConversationId) {
        emitError(id, "session-exists", undefined, payload.conversationId);
        return;
      }
      openingConversationId = payload.conversationId;
      openingPhase = "create";
      try {
        const session = await factory.create(conversationId(payload.conversationId), payload.sessionName);
        if (ownerClosed.signal.aborted) { await disposeAfterOwnerClosed(session); return; }
        liveSession = { conversationId: payload.conversationId, session, sessionFile: session.sessionFile };
        send(id, { type: "created", payload: { conversationId: payload.conversationId, sessionId: session.sessionId, sessionFile: session.sessionFile, sessionName: session.sessionName } });
      } catch (error) {
        reportFailure("create", "session-create-failed", error);
        emitError(id, "session-create-failed", undefined, payload.conversationId);
      } finally {
        openingConversationId = undefined;
      }
      return;
    }
    case "resume": {
      const payload = message.payload;
      if (liveSession || openingConversationId || retiredConversationId) {
        emitError(id, "session-exists", undefined, payload.conversationId);
        return;
      }
      openingConversationId = payload.conversationId;
      openingPhase = "resume";
      try {
        const session = await factory.resume(conversationId(payload.conversationId), payload.sessionId ?? undefined, payload.sessionFile);
        if (ownerClosed.signal.aborted) { await disposeAfterOwnerClosed(session); return; }
        liveSession = { conversationId: payload.conversationId, session, sessionFile: session.sessionFile };
        send(id, { type: "ready", payload: { conversationId: payload.conversationId, sessionId: session.sessionId, sessionFile: session.sessionFile, sessionName: session.sessionName } });
      } catch (error) {
        if (error instanceof PiTranscriptOwnedError) {
          emitError(id, "session-transcript-owned", undefined, payload.conversationId, "Another process owns this Pi transcript");
        } else {
          reportFailure("resume", "session-resume-failed", error);
          emitError(id, "session-resume-failed", undefined, payload.conversationId);
        }
      } finally {
        openingConversationId = undefined;
      }
      return;
    }
    case "snapshot": {
      const live = liveSession?.conversationId === message.payload.conversationId ? liveSession : undefined;
      if (!live) { emitError(id, "session-not-found", undefined, message.payload.conversationId); return; }
      try {
        const snapshot = await live.session.snapshot();
        await queuePiRecord(live.conversationId, live.session.sessionId, live.session.epoch, snapshot.sequence, "snapshot", snapshot.data, message.payload.requestId ?? null);
        send(id, { type: "snapshot-done", payload: { conversationId: live.conversationId } });
      } catch { emitError(id, "snapshot-failed", undefined, live.conversationId); }
      return;
    }
    case "prompt": {
      const payload = message.payload;
      if (payload.delivery !== "followUp") {
        emitError(id, "delivery-unsupported", payload.requestId, payload.conversationId);
        return;
      }
      const live = liveSession?.conversationId === payload.conversationId ? liveSession : undefined;
      if (!live) {
        emitError(id, "session-not-found", payload.requestId, payload.conversationId);
        return;
      }
      if (live.active) {
        emitError(id, "session-busy", payload.requestId, payload.conversationId);
        return;
      }
      live.active = { requestId: payload.requestId, envelopeId: id, settlementScheduled: false, members: [payload.requestId], userEntries: [] };
      try {
        await syncCanonical(live, null);
        if (ownerClosed.signal.aborted || liveSession !== live) return;
        const stats = await live.session.prompt(
          payload.text,
          payload.configuration,
          payload.attachments ?? [],
        );
        if (ownerClosed.signal.aborted || liveSession !== live) return;
        if (!live.active.settlementScheduled) await syncCanonical(live, live.active.requestId, true);
        await canonicalSync;
        await piOutput;
        // An extension can prevent Pi from continuing at the boundary. If it did,
        // do not report delivery of absent entries or leave native work to run later.
        if (live.active.userEntries.length) { failClosed("follow-up-transfer-failed"); return; }
        send(id, { type: "final", payload: {
          stats,
          requestId: live.active.requestId,
          conversationId: payload.conversationId,
        } });
      } catch (error) {
        if (error instanceof ModelRequestError) {
          reportFailure("prompt", "prompt-failed", error);
          emitError(id, error.code, live.active.requestId, payload.conversationId, error.message);
        } else if (error instanceof PromptAborted) {
          emitError(id, "prompt-aborted", live.active.requestId, payload.conversationId, "Pi stopped the prompt before it completed");
        } else {
          reportFailure("prompt", "prompt-failed", error);
          emitError(id, "prompt-failed", live.active.requestId, payload.conversationId);
        }
      } finally {
        live.active = undefined;
      }
      return;
    }
    case "follow-up-add": {
      const payload = message.payload;
      const boundary = liveSession?.conversationId === payload.conversationId ? liveSession.active?.boundary : undefined;
      if (!boundary) { emitError(id, "prompt-failed", payload.requestId, payload.conversationId); return; }
      try {
        const queued = await boundary.checkpoint.enqueue(payload.text, payload.configuration, payload.attachments ?? []) === "queued";
        if (queued) boundary.queued.push(payload.requestId);
        send(id, { type: "follow-up-added", payload: { queued } });
      } catch (error) {
        if (error instanceof FollowUpRejected) emitError(id, "prompt-failed", payload.requestId, payload.conversationId);
        else failClosed("follow-up-transfer-failed");
      }
      return;
    }
    case "follow-up-release": {
      const live = liveSession;
      const active = live?.active;
      const boundary = active?.boundary;
      if (!live || live.conversationId !== message.payload.conversationId || !active || !boundary) {
        emitError(id, "prompt-failed", undefined, message.payload.conversationId);
        return;
      }
      active.boundary = undefined;
      try {
        if (boundary.queued.length) {
          await syncCanonical(live, active.requestId, true);
          active.requestId = boundary.queued[0]!;
          active.firstAssistant = undefined;
          active.firstText = undefined;
          active.lastAssistantEnd = undefined;
          active.members = boundary.queued;
          active.userEntries = [...boundary.queued];
        }
        await boundary.checkpoint.release(boundary.queued.length > 0);
        send(id, { type: "follow-up-released", payload: {} });
      } catch {
        emitError(id, "prompt-failed", undefined, message.payload.conversationId);
      }
      return;
    }
    case "abort": {
      const payload = message.payload;
      const live = liveSession?.conversationId === payload.conversationId ? liveSession : undefined;
      if (!live && openingConversationId === payload.conversationId) {
        await factory.abort(conversationId(payload.conversationId));
        send(id, { type: "aborted", payload: { requestId: payload.requestId, conversationId: payload.conversationId } });
        return;
      }
      if (!live) {
        emitError(id, "session-not-found", payload.requestId, payload.conversationId);
        return;
      }
      if (!live.active?.members.includes(payload.requestId)) {
        emitError(id, "request-mismatch", payload.requestId, payload.conversationId);
        return;
      }
      try {
        await live.session.abort();
        send(id, { type: "aborted", payload: { requestId: payload.requestId, conversationId: payload.conversationId } });
      } catch (error) {
        reportFailure("abort", "abort-failed", error);
        emitError(id, "abort-failed", payload.requestId, payload.conversationId);
      }
      return;
    }
    case "dispose": {
      const payload = message.payload;
      const live = liveSession?.conversationId === payload.conversationId ? liveSession : undefined;
      if (!live) {
        if (openingConversationId === payload.conversationId) {
          emitError(id, "session-busy", undefined, payload.conversationId);
          return;
        }
        send(id, { type: "disposed", payload: { conversationId: payload.conversationId } });
        return;
      }
      if (live.active) {
        emitError(id, "session-busy", live.active.requestId, payload.conversationId);
        return;
      }
      try {
        await disposeSession(live.session);
        if (liveSession === live) liveSession = undefined;
        retiredConversationId = payload.conversationId;
        send(id, { type: "disposed", payload: { conversationId: payload.conversationId } });
      } catch (error) {
        reportFailure("dispose", "dispose-failed", error);
        emitError(id, "dispose-failed", undefined, payload.conversationId);
      }
    }
  }
}

function disposeSession(session: ConversationSession): Promise<void> {
  piPressure.resume(session.epoch);
  let disposal = sessionDisposals.get(session);
  if (!disposal) {
    disposal = Promise.resolve().then(() => session.dispose());
    sessionDisposals.set(session, disposal);
  }
  return disposal;
}

async function disposeAfterOwnerClosed(session: ConversationSession): Promise<void> {
  try { await disposeSession(session); }
  catch (error) {
    process.exitCode = 1;
    reportFailure("dispose", "dispose-failed", error);
  }
}

/** EOF/error is terminal even while create/resume is awaiting Pi's initial state. */
function closeOwner(): Promise<void> {
  if (closing) return closing;
  lifetime = "closing";
  piPressure.resumeAll();
  ownerClosed.abort();
  queuedOutput.length = 0;
  queuedOutputBytes = 0;
  closeAndroidToolBridge();
  const live = liveSession;
  liveSession = undefined;
  const pending = [...requestsInFlight];
  closing = (async () => {
    const disposal = (async () => {
      if (!live) return;
      if (live.active) {
        // Attempt RPC abort, but never let an unresponsive command prevent disposal.
        // PiRpcClient.stop() then owns EOF and bounded signals to this exact child.
        let timer: ReturnType<typeof setTimeout> | undefined;
        try {
          await Promise.race([
            live.session.abort().catch(() => undefined),
            new Promise<void>((resolve) => { timer = setTimeout(resolve, 1_000); }),
          ]);
        } finally { if (timer) clearTimeout(timer); }
      }
      await disposeAfterOwnerClosed(live.session);
    })();
    // Opening get_state already has a 60s RPC deadline and failed opens dispose
    // their child. A successful late open is disposed above without publication.
    // Join handlers rather than exiting the worker and orphaning that late child.
    await Promise.allSettled([...pending, disposal]);
    lifetime = "closed";
  })();
  return closing;
}

let input: Uint8Array = new Uint8Array(0);
function failClosed(category: Extract<WorkerFailureCategory, "invalid-json-frame" | "invalid-worker-request" | "oversized-ipc-frame" | "truncated-ipc-frame" | "stdin-read-failed" | "response-frame-too-large" | "response-backpressure-overflow" | "follow-up-transfer-failed">): void {
  if (failed || lifetime !== "running") return;
  failed = true;
  reportFailure(currentWorkerPhase(), category, undefined);
  process.exitCode = 1;
  void closeOwner();
  process.stdin.destroy();
}
function dispatchLine(line: Uint8Array): void {
  if (!line.length || lifetime !== "running") return;
  let value: unknown;
  try {
    value = JSON.parse(utf8.decode(line));
  } catch (error) {
    reportFailure(currentWorkerPhase(), "invalid-json-frame", error);
    failClosed("invalid-json-frame");
    return;
  }
  let request: ReturnType<typeof parseWorkerRequest>;
  try {
    request = parseWorkerRequest(value);
  } catch (error) {
    reportFailure(currentWorkerPhase(), "invalid-worker-request", error);
    failClosed("invalid-worker-request");
    return;
  }
  const task = handleRequest(request).catch((error) => {
    reportFailure(currentWorkerPhase(), "invalid-request", error);
    emitError(request.id, "invalid-request");
  }).finally(() => { requestsInFlight.delete(task); });
  requestsInFlight.add(task);
}

process.stdin.on("data", (chunk: Buffer) => {
  if (lifetime !== "running") return;
  if (chunk.length > MAX_LINE_BYTES * 2 && !chunk.includes(0x0a)) { failClosed("oversized-ipc-frame"); return; }
  input = input.length ? Buffer.concat([input, chunk]) : chunk;
  let newline = input.indexOf(0x0a);
  while (newline >= 0) {
    if (newline >= MAX_LINE_BYTES) { failClosed("oversized-ipc-frame"); return; }
    dispatchLine(input.subarray(0, newline));
    if (ownerClosed.signal.aborted) return;
    input = input.subarray(newline + 1);
    newline = input.indexOf(0x0a);
  }
  if (input.length >= MAX_LINE_BYTES) failClosed("oversized-ipc-frame");
});
process.stdin.on("end", () => {
  if (input.length) failClosed("truncated-ipc-frame");
  void closeOwner();
});
process.stdin.on("error", (error) => {
  reportFailure(currentWorkerPhase(), "stdin-read-failed", error);
  failClosed("stdin-read-failed");
});
