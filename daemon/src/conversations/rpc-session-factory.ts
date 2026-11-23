import { isAbsolute, sep } from "node:path";
import { homedir } from "node:os";
import { lstat, open } from "node:fs/promises";
import { constants } from "node:fs";
import { createHash } from "node:crypto";
import { z } from "zod";
import type { ImageContent } from "@earendil-works/pi-ai";
import type { ConversationId } from "./domain.ts";
import type { WorkerEvent, WorkerRequest } from "../../../pi-extension/src/protocol/generated.ts";
import type { RpcExtensionUIResponse } from "@earendil-works/pi-coding-agent";
import { PiRpcClient, PiRpcCommandError, type RpcDialog, type RpcEvent, type TranscriptLaunch } from "./pi-rpc-client.ts";
import { applyModel, type ChatConfiguration } from "./model-selection.ts";
import { transcriptLockPath } from "./transcript-lock.ts";

const modelFailureMessages = {
  "model-authentication-failed": "Model authentication failed or expired. Sign in again on the computer, then resend your message.",
  "model-quota-exceeded": "The model provider's quota or billing limit was reached. Check the account on the computer, then resend your message.",
  "model-rate-limited": "The model provider is rate limiting requests. Wait a moment, then resend your message.",
  "model-context-too-large": "This conversation exceeds the model's context limit. Start a new conversation or compact it on the computer.",
  "model-unavailable": "The model provider is unavailable or could not be reached. Try again shortly.",
  "model-request-failed": "The model request failed. Check the Pi session on the computer for details, then resend your message.",
} as const;
export class ModelRequestError extends Error {
  private constructor(readonly code: keyof typeof modelFailureMessages) {
    super(modelFailureMessages[code]);
    this.name = "ModelRequestError";
  }
  static fromProviderMessage(message: string | undefined): ModelRequestError {
    // Provider errors can contain URLs, credentials or request bodies. Classify
    // locally for the delivery error/log. Canonical Pi events separately reach the authorized phone.
    const detail = message?.toLowerCase() ?? "";
    const code = /authentication|unauthorized|invalid.?api.?key|token.*expired|expired.*token|\b401\b|\b403\b/.test(detail) ? "model-authentication-failed"
      : /quota|billing|insufficient.credit/.test(detail) ? "model-quota-exceeded"
      : /rate.limit|too many requests|\b429\b/.test(detail) ? "model-rate-limited"
      : /context.*(limit|length)|too many tokens|maximum context/.test(detail) ? "model-context-too-large"
      : /connection|network|timeout|timed out|unavailable|\b50[0234]\b/.test(detail) ? "model-unavailable"
      : "model-request-failed";
    return new ModelRequestError(code);
  }
}

export class PromptAborted extends Error {}
/** A follow-up definitely did not enter Pi's queue. */
export class FollowUpRejected extends Error {}

export interface PromptAttachment {
  readonly uploadId: string;
  readonly fileName: string;
  readonly mediaType: string;
  readonly length: number;
  readonly sha256: string;
  readonly path: string;
}
export type PromptStats = Extract<WorkerEvent, { type: "final" }>["payload"]["stats"];
export interface ConversationSession {
  readonly sessionId: string;
  readonly sessionFile: string;
  readonly sessionName: string;
  prompt(text: string, configuration: ChatConfiguration, attachments?: readonly PromptAttachment[]): Promise<PromptStats>;
  snapshot(since?: string): ReturnType<PiRpcClient["getEntries"]>;
  readonly epoch: string;
  abort(): Promise<void>;
  dispose(): Promise<void>;
}
export interface SessionFactory {
  create(id: ConversationId, sessionName: string): Promise<ConversationSession>;
  resume(id: ConversationId, sessionId: string | undefined, sessionFile: string): Promise<ConversationSession>;
}
const followUpMode = z.enum(["all", "one-at-a-time"]);
const promptDisposition = z.object({ disposition: z.enum(["started", "queued", "handled"]) });
const followUpDisposition = z.object({ disposition: z.enum(["queued", "handled"]) });
export interface FollowUpBoundary {
  readonly stats: PromptStats;
  enqueue(text: string, configuration: ChatConfiguration, attachments: readonly PromptAttachment[]): Promise<"queued" | "handled">;
  release(continued: boolean): Promise<void>;
}

async function prepareInput(text: string, attachments: readonly PromptAttachment[]) {
  const images: ImageContent[] = [];
  const files: string[] = [];
  for (const attachment of attachments) {
    if (["image/jpeg", "image/png", "image/gif", "image/webp"].includes(attachment.mediaType.toLowerCase())) {
      images.push({ type: "image", data: (await readVerifiedAttachment(attachment)).toString("base64"), mimeType: attachment.mediaType.toLowerCase() });
    } else {
      const name = attachment.fileName.replace(/[\r\n\u0000-\u001f\u007f]+/g, " ").trim().slice(0, 255) || "attachment";
      files.push(`- ${name} (local attachment path: ${attachment.path})`);
    }
  }
  return { message: files.length ? `Attached files available locally:\n${files.join("\n")}${text ? `\n\n${text}` : ""}` : text, images };
}

function wrap(runtime: Runtime, state: Awaited<ReturnType<PiRpcClient["getState"]>>, abort: () => Promise<void>, dispose: () => Promise<void>, onBoundary?: (boundary: FollowUpBoundary) => void): ConversationSession {
  const { client } = runtime;
  const sessionName = state.sessionName ?? "Kompi";
  return {
    sessionId: state.sessionId,
    sessionFile: state.sessionFile,
    sessionName,
    epoch: client.epoch,
    snapshot: (since) => client.getEntries(since),
    prompt: async (text, configuration, attachments = []) => {
      await applyModel(client, configuration);
      let started = 0;
      let firstTextMs: number | null = null;
      let totalMs = 0;
      let chunks = 0;
      let output = 0;
      let reasoning = 0;
      let usageKnown = true;
      let reasoningKnown = true;
      let assistantMessages = 0;
      const models = new Set<string>();
      const providers = new Set<string>();
      let modelFailure: ModelRequestError | undefined;
      let resolveSettled!: (aborted: boolean) => void;
      const settled = new Promise<boolean>((resolve) => { resolveSettled = resolve; });
      const unsubscribe = client.onEvent((event) => {
        if (event.type === "message_update") {
          const update = event.assistantMessageEvent;
          if (update.type === "text_delta" && update.delta) {
            if (firstTextMs === null) firstTextMs = Math.round(performance.now() - started);
            chunks++;
          }
        } else if (event.type === "message_end" && event.message.kind === "assistant") {
          assistantMessages++;
          if (event.message.usage) output += event.message.usage.output; else usageKnown = false;
          if (event.message.usage?.reasoning !== undefined) reasoning += event.message.usage.reasoning; else reasoningKnown = false;
          if (event.message.model) models.add(event.message.model);
          if (event.message.provider) providers.add(event.message.provider);
          modelFailure = event.message.stopReason === "error"
            ? ModelRequestError.fromProviderMessage(event.message.errorMessage) : undefined;

        } else if (event.type === "agent_settled") {
          totalMs = Math.round(performance.now() - started);
          resolveSettled(event.aborted);
        }
      });
      const stats = (): PromptStats => ({ firstTextMs, totalMs: totalMs || Math.round(performance.now() - started), daemonTotalMs: null,
        outputTokens: usageKnown && assistantMessages > 0 ? output : null,
        reasoningTokens: reasoningKnown && assistantMessages > 0 ? reasoning : null,
        textChunks: chunks, assistantMessages,
        model: models.size === 1 ? [...models][0]! : models.size ? "mixed" : null,
        provider: providers.size === 1 ? [...providers][0]! : providers.size ? "mixed" : null });
      runtime.onBoundary = onBoundary && ((dialog) => {
        let selected: ChatConfiguration | undefined;
        onBoundary({
          stats: stats(),
          enqueue: async (nextText, nextConfiguration, nextAttachments) => {
            const input = await prepareInput(nextText, nextAttachments).catch(() => { throw new FollowUpRejected(); });
            if (!selected) {
              await applyModel(client, nextConfiguration).catch(() => { throw new FollowUpRejected(); });
              selected = nextConfiguration;
            }
            const result = await client.request({ type: "follow_up", ...input }).catch((error) => {
              if (error instanceof PiRpcCommandError) throw new FollowUpRejected();
              throw error;
            });
            return followUpDisposition.parse(result).disposition;
          },
          release: async (continued) => {
            if (runtime.boundaryId !== dialog.id) throw new Error("Pi's follow-up boundary is closed");
            runtime.boundaryId = undefined;
            if (continued) {
              started = performance.now(); firstTextMs = null; totalMs = 0;
              chunks = output = reasoning = assistantMessages = 0;
              usageKnown = reasoningKnown = true; models.clear(); providers.clear(); modelFailure = undefined;
            }
            await client.respond({ type: "extension_ui_response", id: dialog.id, value: "continue" });
          },
        });
      });
      try {
        const input = await prepareInput(text, attachments);
        // The listener is installed before submission, including for a fast reply.
        started = performance.now();
        const result = promptDisposition.parse(await client.request({ type: "prompt", ...input, streamingBehavior: "followUp" }, 120_000));
        if (result.disposition !== "handled") {
          const aborted = await Promise.race([settled, client.closed]);
          if (aborted) throw new PromptAborted("Pi stopped the prompt before it completed");
          if (modelFailure) throw modelFailure;
        }
        return stats();
      } finally { runtime.onBoundary = undefined; runtime.boundaryId = undefined; unsubscribe(); }
    },
    abort,
    dispose,
  };
}

async function readVerifiedAttachment(attachment: PromptAttachment): Promise<Buffer> {
  // Do not trust a pathname merely because it came over worker IPC. No symlink
  // following, exact declared length, bounded allocation, and digest verification.
  if (!Number.isSafeInteger(attachment.length) || attachment.length < 0 || attachment.length > 25 * 1024 * 1024) {
    throw new Error("Attachment length is outside the supported range");
  }
  if (!isAbsolute(attachment.path) || attachment.path.includes("\0") || attachment.path.split(sep).includes("..")) {
    throw new Error("Attachment path is unsafe");
  }
  const file = await open(attachment.path, constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0));
  try {
    const stat = await file.stat();
    if (!stat.isFile() || stat.size !== attachment.length) throw new Error("Attachment file does not match its declared length");
    const bytes = await file.readFile();
    const digest = createHash("sha256").update(bytes).digest("hex");
    if (digest !== attachment.sha256) throw new Error("Attachment integrity check failed");
    return bytes;
  } finally {
    await file.close();
  }
}


type UiAnswer = Extract<WorkerRequest, { type: "ui-response" }>["payload"]["answer"];
type UiDialog = Extract<WorkerEvent, { type: "ui-request" }>["payload"]["dialog"];
type PendingDialog = { dialog: RpcDialog; timer: ReturnType<typeof setTimeout> };
type InternalBoundaryDialog = Extract<RpcDialog, { method: "input" }>;
type Runtime = {
  client: PiRpcClient;
  dialogs: Map<string, PendingDialog>;
  boundaryId?: InternalBoundaryDialog["id"];
  onBoundary?(dialog: InternalBoundaryDialog): void;
};
export interface PiRpcUiBridge {
  requested(conversationId: ConversationId, interactionId: string, dialog: UiDialog): void;
  resolved(conversationId: ConversationId, interactionId: string): void;
  boundary?(conversationId: ConversationId, boundary: FollowUpBoundary): void;
  event?(conversationId: ConversationId, sessionId: string, epoch: string, sequence: number, event: Record<string, unknown>, pause: (paused: boolean) => void): void;
  observeTiming?(conversationId: ConversationId, event: RpcEvent): void;
}

function parseDialogAnswer(dialog: RpcDialog, answer: UiAnswer): RpcExtensionUIResponse {
  const base = { type: "extension_ui_response" as const, id: dialog.id };
  switch (answer.type) {
    case "cancelled": return { ...base, cancelled: true };
    case "selected":
      if (dialog.method === "select" && answer.payload.index < dialog.options.length) return { ...base, value: dialog.options[answer.payload.index] };
      break;
    case "confirmed":
      if (dialog.method === "confirm") return { ...base, confirmed: answer.payload.confirmed };
      break;
    case "text":
      if (dialog.method === "input" || dialog.method === "editor") return { ...base, value: answer.payload.text };
      break;
  }
  throw new Error("Pi dialog answer does not match the pending question");
}

function wireDialog(dialog: RpcDialog): UiDialog {
  const common = { title: dialog.title, timeoutMs: dialog.timeout };
  switch (dialog.method) {
    case "select": return { type: "select", payload: { ...common, options: dialog.options } };
    case "confirm": return { type: "confirm", payload: { ...common, message: dialog.message } };
    case "input": return { type: "input", payload: { ...common, placeholder: dialog.placeholder } };
    case "editor": return { type: "editor", payload: { ...common, prefill: dialog.prefill } };
  }
}

const transcriptHeader = z.object({
  type: z.literal("session"), version: z.literal(3), id: z.uuid(),
  cwd: z.string().refine(isAbsolute),
});

/** Normal Pi CLI startup is the sole owner of settings, credentials, tools and extensions. */
export class PiRpcSessionFactory implements SessionFactory {
  private runtimes = new Map<ConversationId, Runtime>();
  constructor(private readonly ui?: PiRpcUiBridge) {}

  create(id: ConversationId, sessionName: string): Promise<ConversationSession> {
    return this.open(id, ["--name", sessionName]);
  }

  async resume(id: ConversationId, expectedSessionId: string | undefined, sessionFile: string): Promise<ConversationSession> {
    if (!isAbsolute(sessionFile)) throw new Error("Pi session path must be absolute");
    if (process.platform === "win32") {
      return this.open(id, ["--session", sessionFile], homedir(), expectedSessionId, { path: sessionFile, expectedSessionId });
    }
    const file = await open(sessionFile, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
    try {
      const stat = await file.stat();
      if (!stat.isFile() || stat.nlink !== 1) throw new Error("Pi session must be an existing unaliased regular file");
      const prefix = Buffer.alloc(64 * 1024);
      const { bytesRead } = await file.read(prefix, 0, prefix.length, 0);
      const end = prefix.subarray(0, bytesRead).indexOf(10);
      if (end < 0) throw new Error("Pi transcript has no complete header");
      const header = transcriptHeader.parse(JSON.parse(prefix.toString("utf8", 0, end)));
      if (expectedSessionId !== undefined && header.id !== expectedSessionId) throw new Error("Pi session identity changed before launch");
      const lock = await open(transcriptLockPath(sessionFile), constants.O_CREAT | constants.O_RDWR | constants.O_NOFOLLOW | constants.O_NONBLOCK, 0o600);
      try {
        const lease = await lock.stat();
        if (!lease.isFile() || lease.nlink !== 1) throw new Error("Pi session lease is not a regular file");
        // open() hands the descriptors to the child before its first await.
        return this.open(id, ["--session", sessionFile], header.cwd, header.id, { path: sessionFile, fd: file.fd, lockFd: lock.fd });
      } finally {
        await lock.close();
      }
    } finally {
      await file.close();
    }
  }

  async answerUi(id: ConversationId, interactionId: string, answer: UiAnswer): Promise<void> {
    const runtime = this.runtimes.get(id);
    const pending = runtime?.dialogs.get(interactionId);
    if (!runtime || !pending) throw new Error("Pi question is no longer pending");
    const response = parseDialogAnswer(pending.dialog, answer);
    await runtime.client.respond(response);
    this.resolved(id, runtime, interactionId);
  }

  async abort(id: ConversationId): Promise<void> {
    const runtime = this.runtimes.get(id);
    if (!runtime) return;
    await this.cancelDialogs(id, runtime);
    await runtime.client.request({ type: "clear_queue" });
    const aborted = runtime.client.request({ type: "abort" });
    if (runtime.boundaryId) {
      const id = runtime.boundaryId;
      runtime.boundaryId = undefined;
      await runtime.client.respond({ type: "extension_ui_response", id, value: "stop" });
    }
    await aborted;
  }

  private resolved(id: ConversationId, runtime: Runtime, interactionId: string): void {
    const pending = runtime.dialogs.get(interactionId);
    if (!pending) return;
    clearTimeout(pending.timer);
    runtime.dialogs.delete(interactionId);
    this.ui?.resolved(id, interactionId);
  }

  private async cancelDialogs(id: ConversationId, runtime: Runtime): Promise<void> {
    for (const [interactionId] of runtime.dialogs) {
      await runtime.client.respond({ type: "extension_ui_response", id: interactionId, cancelled: true }).catch(() => undefined);
      this.resolved(id, runtime, interactionId);
    }
  }

  private async open(id: ConversationId, args: string[], cwd = homedir(), expectedSessionId?: string, transcript?: TranscriptLaunch): Promise<ConversationSession> {
    if (this.runtimes.has(id)) throw new Error("A Pi process already owns this phone conversation");
    const client = new PiRpcClient(cwd, args, transcript);
    const runtime: Runtime = { client, dialogs: new Map() };
    let piSessionId: string | undefined;
    const unraw = client.onRawEvent((sequence, event) => {
      if (piSessionId) this.ui?.event?.(id, piSessionId, client.epoch, sequence, event, (paused) => client.setReadBackpressure(paused));
    });
    this.runtimes.set(id, runtime);
    client.onDialog = (dialog) => {
      if (dialog.method === "input" && dialog.title === client.followUpBoundaryTitle) {
        runtime.boundaryId = dialog.id;
        void client.getState().then(async (state) => {
          if (runtime.boundaryId !== dialog.id) return;
          if (followUpMode.parse(state.followUpMode) === "all" && runtime.onBoundary) {
            runtime.onBoundary(dialog);
            return;
          }
          runtime.boundaryId = undefined;
          await client.respond({ type: "extension_ui_response", id: dialog.id, value: "continue" });
        }).catch(() => { void client.stop(); });
        return;
      }
      if (!this.ui || runtime.dialogs.size >= 8) {
        void client.respond({ type: "extension_ui_response", id: dialog.id, cancelled: true }).catch(() => undefined);
        void this.abort(id).catch(() => undefined);
        return;
      }
      const timer = setTimeout(() => {
        void client.respond({ type: "extension_ui_response", id: dialog.id, cancelled: true })
          .catch(() => undefined).finally(() => this.resolved(id, runtime, dialog.id));
      }, dialog.timeout ?? 60_000);
      runtime.dialogs.set(dialog.id, { dialog, timer });
      this.ui.requested(id, dialog.id, wireDialog(dialog));
    };
    const unsubscribe = client.onEvent((event) => {
      this.ui?.observeTiming?.(id, event);
      if (event.type === "agent_settled") void this.cancelDialogs(id, runtime);
    });
    const dispose = async (): Promise<void> => {
      await this.cancelDialogs(id, runtime);
      unsubscribe();
      unraw();
      await client.stop();
      if (this.runtimes.get(id) === runtime) this.runtimes.delete(id);
    };
    void client.closed.catch(() => {
      for (const [interactionId] of runtime.dialogs) this.resolved(id, runtime, interactionId);
      unsubscribe();
      if (this.runtimes.get(id) === runtime) this.runtimes.delete(id);
    });
    try {
      const state = await client.getState();
      piSessionId = state.sessionId;
      if (!transcript && !(await lstat(transcriptLockPath(state.sessionFile))).isFile()) throw new Error("Pi could not reserve a new transcript");
      if ((expectedSessionId !== undefined && state.sessionId !== expectedSessionId) || (transcript !== undefined && state.sessionFile !== transcript.path)) throw new Error("Pi session does not match the daemon's saved transcript");
      const boundary = this.ui?.boundary;
      return wrap(runtime, state, () => this.abort(id), dispose, boundary && ((checkpoint) => boundary(id, checkpoint)));
    } catch (error) {
      await dispose();
      throw error;
    }
  }
}

