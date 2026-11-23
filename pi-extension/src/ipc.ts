import { randomUUID } from "node:crypto";
import { createConnection, type Socket } from "node:net";
import { Decoder, Encoder } from "cbor-x";
import {
  ControlEnvelopeSchema,
  ControlMessageSchema,
  MAX_PAYLOAD,
  VERSION,
  parseControlEnvelope,
  parseControlMessage,
  type ControlEnvelope,
  type ControlMessage,
} from "./protocol/generated.js";

export const IPC_MAX = MAX_PAYLOAD;
const encoder = new Encoder({ useRecords: false, mapsAsObjects: true, variableMapSize: true });
const decoder = new Decoder({ useRecords: false, mapsAsObjects: true });

function encodeFrame(value: ControlEnvelope): Buffer {
  const envelope = ControlEnvelopeSchema.parse(value) as ControlEnvelope;
  const body = Buffer.from(encoder.encode(envelope));
  if (!body.length || body.length > IPC_MAX) throw new Error("Control IPC frame exceeds 64 KiB");
  const frame = Buffer.allocUnsafe(body.length + 4);
  frame.writeUInt32BE(body.length, 0);
  body.copy(frame, 4);
  return frame;
}

class FrameDecoder {
  private header = Buffer.alloc(4);
  private headerUsed = 0;
  private body?: Buffer;
  private bodyUsed = 0;
  private failed = false;

  push(chunk: Buffer): ControlEnvelope[] {
    if (this.failed) throw new Error("Control IPC decoder is poisoned");
    const frames: ControlEnvelope[] = [];
    let at = 0;
    try {
      while (at < chunk.length) {
        if (!this.body) {
          const count = Math.min(4 - this.headerUsed, chunk.length - at);
          chunk.copy(this.header, this.headerUsed, at, at + count);
          at += count; this.headerUsed += count;
          if (this.headerUsed !== 4) continue;
          const length = this.header.readUInt32BE(0);
          if (length === 0 || length > IPC_MAX) throw new Error("Invalid control IPC frame length");
          this.body = Buffer.allocUnsafe(length);
          this.bodyUsed = 0;
        }
        const count = Math.min(this.body.length - this.bodyUsed, chunk.length - at);
        chunk.copy(this.body, this.bodyUsed, at, at + count);
        at += count; this.bodyUsed += count;
        if (this.bodyUsed === this.body.length) {
          const decoded: unknown = decoder.decode(this.body);
          const envelope = parseControlEnvelope(decoded);
          frames.push({ ...envelope, message: parseControlMessage(envelope.message) });
          this.body = undefined;
          this.headerUsed = 0;
        }
      }
      return frames;
    } catch (error) {
      this.failed = true;
      throw error;
    }
  }
}

export type ControlReply = ControlEnvelope["message"];

export class ControlClient {
  private readonly frames = new FrameDecoder();
  private readonly pending = new Map<string, { resolve: (reply: ControlReply) => void; reject: (error: Error) => void }>();
  private closed = false;

  private constructor(private readonly socket: Socket) {
    socket.on("data", (chunk: Buffer) => {
      try {
        for (const envelope of this.frames.push(chunk)) {
          const waiter = this.pending.get(envelope.id);
          if (!waiter) continue;
          this.pending.delete(envelope.id);
          const reply = envelope.message as ControlReply;
          if (reply.type === "error") waiter.reject(Object.assign(new Error(reply.payload.message), { code: reply.payload.code }));
          else waiter.resolve(reply);
        }
      } catch (error) { socket.destroy(error as Error); }
    });
    socket.on("close", () => this.failPending(new Error("Native kompi control socket closed")));
    socket.on("error", (error) => this.failPending(error));
  }

  static connect(path: string): Promise<ControlClient> {
    return new Promise((resolve, reject) => {
      const socket = createConnection(path);
      const onError = (error: Error) => { socket.destroy(); reject(error); };
      socket.once("error", onError);
      socket.once("connect", () => {
        socket.removeListener("error", onError);
        resolve(new ControlClient(socket));
      });
    });
  }

  async request(message: ControlMessage): Promise<ControlReply> {
    if (this.closed || this.socket.destroyed) throw new Error("Native kompi control socket is not connected");
    const parsedMessage = ControlMessageSchema.parse(message) as ControlMessage;
    const id = randomUUID();
    const envelope = { v: VERSION, id, message: parsedMessage } as ControlEnvelope;
    const frame = encodeFrame(envelope);
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.socket.write(frame, (error) => {
        if (!error) return;
        this.pending.delete(id);
        reject(error);
      });
    });
  }

  isClosed(): boolean { return this.closed || this.socket.destroyed; }
  close(): void { this.closed = true; this.socket.end(); this.failPending(new Error("Native kompi control client closed")); }

  private failPending(error: Error): void {
    if (this.closed && this.pending.size === 0) return;
    this.closed = true;
    for (const pending of this.pending.values()) pending.reject(error);
    this.pending.clear();
  }
}
