import { Decoder, Encoder } from "cbor-x";
import { ATTACHMENT_CHUNK_MAX_BYTES, CBOR_MAX_ARRAY_ENTRIES, CBOR_MAX_DEPTH, CBOR_MAX_MAP_ENTRIES, MAX_PAYLOAD, parseEnvelope, type Envelope } from "./generated.js";

// Ordinary string-keyed maps and untagged byte strings, not cbor-x records.
const encoder = new Encoder({ useRecords: false, mapsAsObjects: true, variableMapSize: true, tagUint8Array: false });
const decoder = new Decoder({ useRecords: false, mapsAsObjects: true });
const utf8 = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true });

// Preflight before cbor-x allocates containers. Only two message variants allow
// binary data, and each has exactly one byte string, checked after dispatch.
function checkCbor(bytes: Uint8Array): { hasBinary: boolean } {
  let at = 0;
  let hasBinary = false;
  function read(): number {
    if (at === bytes.length) throw new Error("Truncated CBOR");
    return bytes[at++]!;
  }
  function argument(info: number): number {
    if (info < 24) return info;
    if (info > 26) throw new Error("Unsupported CBOR argument or indefinite item");
    let n = 0;
    for (let i = 0; i < (1 << (info - 24)); i++) n = n * 256 + read();
    return n;
  }
  function take(n: number): Uint8Array {
    if (n > bytes.length - at) throw new Error("CBOR item exceeds frame");
    at += n;
    return bytes.subarray(at - n, at);
  }
  function item(depth: number): void {
    if (depth > CBOR_MAX_DEPTH) throw new Error("CBOR nesting limit");
    const head = read();
    if (head === 0xf4 || head === 0xf5 || head === 0xf6) return; // false, true, null
    const n = argument(head & 31);
    switch (head >> 5) {
      case 0: case 1: return;
      case 2:
        if (hasBinary || !n || n > ATTACHMENT_CHUNK_MAX_BYTES) throw new Error("Invalid CBOR byte string");
        take(n);
        hasBinary = true;
        return;
      case 3: utf8.decode(take(n)); return;
      case 4:
        if (n > CBOR_MAX_ARRAY_ENTRIES) throw new Error("CBOR array limit");
        for (let i = 0; i < n; i++) item(depth + 1);
        return;
      case 5: {
        if (n > CBOR_MAX_MAP_ENTRIES) throw new Error("CBOR map limit");
        const keys = new Set<string>();
        for (let i = 0; i < n; i++) {
          const keyHead = read();
          if (keyHead >> 5 !== 3) throw new Error("Expected CBOR text key");
          const key = utf8.decode(take(argument(keyHead & 31)));
          if (keys.has(key) || ["__proto__", "prototype", "constructor"].includes(key)) throw new Error("Duplicate or unsafe CBOR key");
          keys.add(key);
          item(depth + 1);
        }
        return;
      }
      default: throw new Error("Unsupported CBOR value");
    }
  }
  if (!bytes.length || bytes[0]! >> 5 !== 5) throw new Error("Expected CBOR map");
  item(0);
  if (at !== bytes.length) throw new Error("Trailing CBOR bytes");
  return { hasBinary };
}

export function encodeEnvelope(envelope: Envelope): Uint8Array {
  const value = parseEnvelope(envelope);
  const { message } = value;
  let wire: unknown = value;
  if (message.type === "artifact-data" || message.type === "attachment-chunk") {
    const data = Buffer.from(message.payload.data, "base64");
    if (!data.length || data.length > ATTACHMENT_CHUNK_MAX_BYTES || data.toString("base64") !== message.payload.data)
      throw new Error("Invalid base64 chunk");
    // Emit the discriminant before payload so serde can dispatch without buffering bytes.
    wire = { ...value, message: { type: message.type, payload: { ...message.payload, data } } };
  }
  const bytes = encoder.encode(wire);
  if (!bytes.length || bytes.length > MAX_PAYLOAD) throw new Error(`Payload exceeds ${MAX_PAYLOAD} bytes`);
  return bytes;
}

export function decodeEnvelope(bytes: Uint8Array): Envelope {
  if (!bytes.length || bytes.length > MAX_PAYLOAD) throw new Error("Invalid payload size");
  const { hasBinary } = checkCbor(bytes);
  const value = decoder.decode(bytes);
  const message = value?.message;
  if (message?.type === "artifact-data" || message?.type === "attachment-chunk") {
    const data = message.payload?.data;
    if (!(data instanceof Uint8Array)) throw new Error("Expected CBOR byte string");
    message.payload.data = Buffer.from(data).toString("base64");
  } else if (hasBinary) throw new Error("Unexpected CBOR byte string");
  return parseEnvelope(value);
}
