import { decodeEnvelope, encodeEnvelope } from "./codec.js";
import { MAX_PAYLOAD, type Envelope } from "./types.js";

export function frameEnvelope(envelope: Envelope): Uint8Array {
  const payload = encodeEnvelope(envelope);
  const frame = new Uint8Array(4 + payload.length);
  new DataView(frame.buffer).setUint32(0, payload.length, false);
  frame.set(payload, 4);
  return frame;
}

/** One bounded buffer per frame. No concatenation of unbounded incoming chunks. */
export class FrameDecoder {
  private readonly header = new Uint8Array(4);
  private headerAt = 0;
  private payload: Uint8Array | undefined;
  private payloadAt = 0;
  private failed = false;

  push(chunk: Uint8Array): Envelope[] {
    if (this.failed) throw new Error("Decoder is poisoned; close the stream");
    const messages: Envelope[] = [];
    let at = 0;
    try {
      while (at < chunk.length) {
        if (!this.payload) {
          const n = Math.min(4 - this.headerAt, chunk.length - at);
          this.header.set(chunk.subarray(at, at + n), this.headerAt);
          at += n; this.headerAt += n;
          if (this.headerAt !== 4) continue;
          const length = new DataView(this.header.buffer).getUint32(0, false);
          if (!length || length > MAX_PAYLOAD) throw new Error("Invalid frame length");
          this.payload = new Uint8Array(length); this.payloadAt = 0;
        }
        const n = Math.min(this.payload.length - this.payloadAt, chunk.length - at);
        this.payload.set(chunk.subarray(at, at + n), this.payloadAt);
        at += n; this.payloadAt += n;
        if (this.payloadAt === this.payload.length) {
          messages.push(decodeEnvelope(this.payload));
          this.payload = undefined; this.headerAt = 0;
        }
      }
      return messages;
    } catch (error) { this.failed = true; throw error; }
  }
  finish(): void {
    if (this.failed || this.headerAt || this.payload) throw new Error("Truncated or invalid stream");
  }
}
