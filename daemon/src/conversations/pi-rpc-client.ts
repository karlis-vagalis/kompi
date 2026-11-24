import { windowsPi } from "./windows-pi.ts";
import { spawn, type ChildProcess, type ChildProcessWithoutNullStreams } from "node:child_process";
import { randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";
import { once } from "node:events";
import { z } from "zod";
import { TRANSCRIPT_LOCK_CONFLICT } from "./transcript-lock.ts";
import type { RpcCommand, RpcExtensionUIResponse } from "@earendil-works/pi-coding-agent";
import { MAX_PI_UI_TEXT_CHARS, MAX_PI_UI_TITLE_CHARS, MAX_PI_UI_OPTIONS, MAX_PI_UI_OPTION_CHARS, MAX_PI_UI_MESSAGE_CHARS, MAX_PI_UI_TIMEOUT_MS } from "../../../pi-extension/src/protocol/generated.ts";

const MAX_RPC_LINE_BYTES = 64 * 1024 * 1024;
const message = z.union([
  z.object({ role: z.literal("assistant"), stopReason: z.enum(["pending", "stop", "length", "toolUse", "error", "aborted", "deferred"]), errorMessage: z.string().optional(), usage: z.object({ output: z.number().int().nonnegative(), reasoning: z.number().int().nonnegative().optional() }).passthrough().optional(), model: z.string().optional(), provider: z.string().optional() })
    .transform(({ stopReason, errorMessage, usage, model, provider }) => ({ kind: "assistant" as const, stopReason, errorMessage, usage, model, provider })),
  z.object({ role: z.string().refine((role) => role !== "assistant") }).transform(() => ({ kind: "other" as const })),
]);
const update = z.discriminatedUnion("type", [
  z.object({ type: z.literal("text_delta"), delta: z.string() }),
  z.object({ type: z.literal("thinking_start"), contentIndex: z.number().int() }),
  z.object({ type: z.literal("thinking_delta"), contentIndex: z.number().int(), delta: z.string() }),
  z.object({ type: z.literal("thinking_end"), contentIndex: z.number().int(), content: z.string() }),
]);
const dialogBase = { id: z.string().min(1).max(128).brand<"RpcDialogId">(), title: z.string().max(MAX_PI_UI_TITLE_CHARS), timeout: z.number().int().positive().max(MAX_PI_UI_TIMEOUT_MS).optional() };
export const rpcDialogSchema = z.discriminatedUnion("method", [
  z.object({ ...dialogBase, method: z.literal("select"), options: z.array(z.string().max(MAX_PI_UI_OPTION_CHARS)).min(1).max(MAX_PI_UI_OPTIONS) }),
  z.object({ ...dialogBase, method: z.literal("confirm"), message: z.string().max(MAX_PI_UI_MESSAGE_CHARS).optional() }),
  z.object({ ...dialogBase, method: z.literal("input"), placeholder: z.string().max(MAX_PI_UI_TITLE_CHARS).optional() }),
  z.object({ ...dialogBase, method: z.literal("editor"), prefill: z.string().max(MAX_PI_UI_TEXT_CHARS).optional() }),
]);
export type RpcDialog = z.infer<typeof rpcDialogSchema>;
const rpcEventSchema = z.discriminatedUnion("type", [
  z.object({ type: z.literal("message_start"), message }),
  z.object({ type: z.literal("message_end"), message }),
  z.object({ type: z.literal("message_update"), assistantMessageEvent: update }),
  z.object({ type: z.literal("tool_execution_start"), toolName: z.string().max(256) }),
  z.object({ type: z.literal("agent_settled"), aborted: z.boolean() }),
]);
export type RpcEvent = z.infer<typeof rpcEventSchema>;
const stateSchema = z.object({
  sessionId: z.string().min(1).max(128), sessionFile: z.string().min(1).max(4096),
  sessionName: z.string().min(1).max(512).optional(),
  model: z.object({ provider: z.string(), id: z.string() }).passthrough().optional(),
}).passthrough();
const responseBase = { type: z.literal("response"), id: z.string(), command: z.string() };
const responseSchema = z.discriminatedUnion("success", [
  z.object({ ...responseBase, success: z.literal(true), data: z.unknown().optional() }),
  z.object({ ...responseBase, success: z.literal(false), error: z.string() }),
]);
const entriesResponse = z.object({ sequence: z.number().int().nonnegative(), data: z.object({
  entries: z.array(z.object({ type: z.string(), id: z.string().min(1), parentId: z.string().nullable() }).passthrough()),
  leafId: z.string().nullable(),
}).passthrough() });
type Entries = z.infer<typeof entriesResponse>;
type Pending = { barrier?: (value: unknown) => unknown; command: string; resolve(value: unknown): void; reject(error: Error): void; timer: ReturnType<typeof setTimeout> };

/** Pi explicitly rejected an RPC command, rather than losing its response. */
export class PiRpcCommandError extends Error {}

/** Small bounded JSONL transport. Pi, not this adapter, owns configuration and runtime setup. */
export class PiTranscriptOwnedError extends Error {}
export type TranscriptLaunch = { readonly path:string; readonly fd:number; readonly lockFd:number } | { readonly path:string; readonly expectedSessionId?:string };

function hasRpcPipes(child: ChildProcess): child is ChildProcessWithoutNullStreams {
  return child.stdin !== null && child.stdout !== null && child.stderr !== null;
}

export class PiRpcClient {
  private child: ChildProcessWithoutNullStreams;
  private buffer = Buffer.alloc(0);
  private pending = new Map<string, Pending>();
  private listeners = new Set<(event: RpcEvent) => void>();
  private rawListeners = new Set<(sequence: number, event: Record<string, unknown>) => void>();
  readonly epoch = randomUUID();
  private eventSequence = 0;
  private failure?: Error;
  private rejectClosed!: (error: Error) => void;
  readonly closed = new Promise<never>((_resolve, reject) => { this.rejectClosed = reject; });
  private stopping?: Promise<void>;
  onDialog?: (dialog: RpcDialog) => void;
  readonly followUpBoundaryTitle = `kompi-follow-up-${randomUUID()}`;

  constructor(cwd: string, args: string[], transcript?: TranscriptLaunch) {
    // Use the same executable, HOME, environment and cwd as a normal CLI launch.
    // No provider/model/tool/settings overrides and no private copy of settings.json.
    // Bundled extensions order user image blocks and bridge only bounded, explicit Android tools for the active phone session.
    const sourceSuffix = import.meta.url.endsWith(".ts") ? ".ts" : ".js";
    const orderingExtension = fileURLToPath(new URL(`./attachment-order${sourceSuffix}`, import.meta.url));
    const androidToolsExtension = fileURLToPath(new URL(`./android-tools${sourceSuffix}`, import.meta.url));
    const boundaryExtension = fileURLToPath(new URL(`./follow-up-boundary${sourceSuffix}`, import.meta.url));
    const ownershipExtension = fileURLToPath(new URL(`./transcript-owner${sourceSuffix}`, import.meta.url));
    const pi = process.env.KOMPI_PI_CLI || "pi";
    const piArgs = ["--mode", "rpc", "--extension", ownershipExtension, "--extension", orderingExtension, "--extension", androidToolsExtension, "--extension", boundaryExtension, ...args];
    const options = { cwd, env: { ...process.env, KOMPI_FOLLOW_UP_BOUNDARY: this.followUpBoundaryTitle,
      KOMPI_PI_TRANSCRIPT_LAUNCH: transcript ? "locked" : "fresh" }, shell: false };
    // Lock the sidecar before the verified, already-open inode. Never use
    // flock's filename form: it can create a new file after concurrent delete.
    // Both descriptors survive exec and follow Pi until it actually exits.
    const windows = process.platform === "win32" ? windowsPi() : undefined;
    const preload = new URL(`./windows-pi-preload${sourceSuffix}`, import.meta.url).href;
    const child = windows
      ? spawn(windows.node, [...(transcript ? ["--import", preload] : []), windows.cli, ...piArgs], { ...options, windowsHide:true, env:{ ...options.env, KOMPI_PI_RESUME_FILE:transcript?.path, KOMPI_PI_RESUME_ID:transcript && "expectedSessionId" in transcript ? transcript.expectedSessionId : undefined }, stdio:["pipe","pipe","pipe"] })
      : transcript && "fd" in transcript
      ? spawn("/bin/sh", ["-c", `flock -x -n -E ${TRANSCRIPT_LOCK_CONFLICT} 4 || exit $?; flock -x -n -E ${TRANSCRIPT_LOCK_CONFLICT} 3 || exit $?; [ "$1" -ef /proc/self/fd/3 ] && [ "$1.kompi-lock" -ef /proc/self/fd/4 ] || exit ${TRANSCRIPT_LOCK_CONFLICT}; shift; exec "$@"`, "kompi-lock", transcript.path, pi, ...piArgs],
        { ...options, stdio: ["pipe", "pipe", "pipe", transcript.fd, transcript.lockFd] })
      : spawn(pi, piArgs, { ...options, stdio: ["pipe", "pipe", "pipe"] });
    if (!hasRpcPipes(child)) throw new Error("Pi CLI requires three RPC pipes");
    this.child = child;
    void this.closed.catch(() => undefined);
    this.child.stderr.on("data", () => { /* Drain, but never expose raw extension/provider diagnostics. */ });
    this.child.stdout.on("data", (chunk: Buffer) => this.read(chunk));
    // An exec/lock failure can close either pipe before the exit event arrives.
    let startingUnderLock = Boolean(transcript);
    this.child.stdout.on("end", () => { if (!startingUnderLock) this.fail(new Error("Pi RPC output closed")); });
    this.child.stdin.on("error", () => { if (!startingUnderLock) this.fail(new Error("Pi RPC input closed")); });
    this.child.once("error", () => this.fail(new Error("Could not start Pi CLI; check KOMPI_PI_CLI, flock and PATH")));
    this.child.once("exit", (code) => this.fail(startingUnderLock && code === TRANSCRIPT_LOCK_CONFLICT
      ? new PiTranscriptOwnedError("Pi transcript lock is held by another process") : new Error("Pi CLI exited")));
    this.child.stdout.once("data", () => { startingUnderLock = false; });
  }

  onEvent(listener: (event: RpcEvent) => void): () => void {
    this.listeners.add(listener);
    return () => { this.listeners.delete(listener); };
  }

  async getState() { return stateSchema.parse(await this.request({ type: "get_state" }, 60_000)); }

  onRawEvent(listener: (sequence: number, event: Record<string, unknown>) => void): () => void {
    this.rawListeners.add(listener);
    return () => { this.rawListeners.delete(listener); };
  }

  setReadBackpressure(paused: boolean): void { if (paused) this.child.stdout.pause(); else this.child.stdout.resume(); }

  async getEntries(since?: string, onSnapshot?: (snapshot: Entries) => Promise<void>): Promise<Entries> {
    let snapshot: Entries | undefined;
    await this.request({ type: "get_entries", ...(since ? { since } : {}) }, 60_000, value => {
      snapshot = entriesResponse.parse(value);
      // Enqueue before parsing any later event in the same stdout chunk.
      return onSnapshot?.(snapshot);
    });
    if (!snapshot) throw new Error("Missing Pi snapshot");
    return snapshot;
  }

  async request(command: RpcCommand, timeoutMs = 30_000, barrier?: Pending["barrier"]): Promise<unknown> {
    if (this.failure) throw this.failure;
    if (this.pending.size >= 32) throw new Error("Too many pending Pi RPC commands");
    const id = randomUUID();
    const result = new Promise<unknown>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error("Pi RPC command timed out"));
        // A late, read-only history result can be discarded by request ID.
        // Recovery must never terminate an unrelated in-flight prompt.
        if (command.type !== "get_entries") this.fail(new Error("Pi RPC command timed out"));
      }, timeoutMs);
      this.pending.set(id, { barrier, command: command.type, resolve, reject, timer });
    });
    // Install the response route before writing, including for immediate replies.
    void this.send({ ...command, id }).catch(() => {
      // On first write, flock may be exiting without ever reading stdin.
      // Its close status is the authoritative reason; the request timer bounds the wait.
      if (command.type !== "get_state") this.fail(new Error("Pi RPC write failed"));
    });
    return result;
  }

  async respond(response: RpcExtensionUIResponse): Promise<void> { await this.send(response); }

  stop(): Promise<void> {
    return this.stopping ??= this.stopProcess();
  }

  private async stopProcess(): Promise<void> {
    if (!this.child.pid || this.child.exitCode !== null || this.child.signalCode !== null) return;
    // EOF is Pi's documented orderly shutdown path; never signal a process group.
    const exited = once(this.child, "close").catch(() => undefined);
    this.child.stdin.end();
    const terminate = setTimeout(() => { this.child.kill("SIGTERM"); }, 5_000);
    const kill = setTimeout(() => { this.child.kill("SIGKILL"); }, 8_000);
    try { await exited; } finally { clearTimeout(terminate); clearTimeout(kill); }
  }

  private async send(record: unknown): Promise<void> {
    if (this.failure) throw this.failure;
    const line = `${JSON.stringify(record)}\n`;
    if (Buffer.byteLength(line) > 160 * 1024 * 1024) throw new Error("Pi RPC input exceeds the attachment limit");
    await new Promise<void>((resolve, reject) => {
      this.child.stdin.write(line, (error) => { if (error) reject(error); else resolve(); });
    });
  }

  private read(chunk: Buffer): void {
    if (this.failure) return;
    // Split strictly on LF, never on Unicode separators inside JSON strings.
    let start = 0;
    while (start < chunk.length) {
      const newline = chunk.indexOf(0x0a, start);
      const end = newline < 0 ? chunk.length : newline;
      const part = chunk.subarray(start, end);
      if (this.buffer.length + part.length > MAX_RPC_LINE_BYTES) {
        this.fail(new Error("Pi RPC output frame is too large"));
        return;
      }
      this.buffer = this.buffer.length ? Buffer.concat([this.buffer, part]) : Buffer.from(part);
      if (newline < 0) return;
      const line = this.buffer;
      this.buffer = Buffer.alloc(0);
      if (line.length) {
        try { this.record(JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(line))); }
        catch { this.fail(new Error("Pi RPC emitted an invalid protocol record")); return; }
      }
      start = newline + 1;
    }
  }

  private record(value: unknown): void {
    const record = z.object({ type: z.string() }).passthrough().parse(value);
    if (record.type === "response") {
      const response = responseSchema.parse(record);
      const pending = this.pending.get(response.id);
      if (!pending) return; // Timed-out or otherwise retired command.
      if (pending.command !== response.command) throw new Error("Pi RPC response command mismatch");
      this.pending.delete(response.id);
      clearTimeout(pending.timer);
      if (response.success) {
        try { pending.resolve(pending.barrier ? pending.barrier({ data: response.data, sequence: this.eventSequence }) : response.data); }
        catch (error) { pending.reject(error instanceof Error ? error : new Error("Invalid Pi response")); }
      }
      else pending.reject(new PiRpcCommandError(`Pi RPC ${pending.command} command failed`));
      return;
    }
    if (record.type === "extension_ui_request" && record.method === "input" && record.title === this.followUpBoundaryTitle) {
      this.onDialog?.(rpcDialogSchema.parse(record));
      return;
    }
    const sequence = ++this.eventSequence;
    for (const listener of this.rawListeners) listener(sequence, record);
    if (record.type === "extension_ui_request") {
      if (["select", "confirm", "input", "editor"].includes(String(record.method))) {
        const dialog = rpcDialogSchema.parse(record);
        if (this.onDialog) this.onDialog(dialog);
        else void this.respond({ type: "extension_ui_response", id: dialog.id, cancelled: true }).catch(() => undefined);
      }
      return;
    }
    if (record.type === "message_update") {
      const kind = z.object({ type: z.string() }).parse(record.assistantMessageEvent).type;
      if (!["text_delta", "thinking_start", "thinking_delta", "thinking_end"].includes(kind)) return;
    }
    if (["message_start", "message_end", "message_update", "tool_execution_start", "agent_settled"].includes(record.type)) {
      const event = rpcEventSchema.parse(record);
      for (const listener of this.listeners) listener(event);
    }
  }

  private fail(error: Error): void {
    if (this.failure) return;
    this.failure = error;
    for (const pending of this.pending.values()) { clearTimeout(pending.timer); pending.reject(error); }
    this.pending.clear();
    this.rejectClosed(error);
    this.child.stdin.end();
    if (this.child.exitCode === null) this.child.kill("SIGTERM");
  }
}
