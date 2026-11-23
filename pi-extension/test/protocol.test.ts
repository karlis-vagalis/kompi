import { it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { decodeEnvelope, encodeEnvelope } from "../src/protocol/codec.js";
import { ATTACHMENT_CHUNK_MAX_BYTES, CBOR_MAX_DEPTH } from "../src/protocol/generated.js";
import { FrameDecoder } from "../src/protocol/framing.js";
const vectors = JSON.parse(readFileSync(process.env.KOTLIN_VECTORS ?? new URL("../../protocol/test-vectors/v4.json", import.meta.url), "utf8"));
it("decodes Rust-produced boolean and binary envelopes", () => {
  const rust = JSON.parse(readFileSync(new URL('../../protocol/test-vectors/rust-v4.json', import.meta.url), 'utf8'));
  for (const {message, payloadHex} of rust.valid)
    assert.deepEqual(decodeEnvelope(Buffer.from(payloadHex, 'hex')), message);
});
it("round-trips network envelopes and accepts fragmented frames", () => {
  assert.ok(vectors.valid.length);
  for (const {message, payloadHex, frameHex} of vectors.valid) {
    assert.deepEqual(decodeEnvelope(Buffer.from(payloadHex, "hex")), message);
    assert.deepEqual(decodeEnvelope(encodeEnvelope(message)), message);
    if (!frameHex) continue;
    const frame = Buffer.from(frameHex, "hex");
    for (let i = 0; i <= frame.length; i++) {
      const decoder = new FrameDecoder();
      assert.deepEqual([...decoder.push(frame.subarray(0,i)), ...decoder.push(frame.subarray(i))], [message]);
      decoder.finish();
    }
    assert.deepEqual(new FrameDecoder().push(Buffer.concat([frame, frame])), [message, message]);
  }
});
it("checks binary bounds, canonical base64 and CBOR nesting before decoding", () => {
  const artifact = (data: string) => ({ v: 4 as const, id: 'chunk', message: { type: 'artifact-data' as const, payload: { data } } });
  const full = artifact(Buffer.alloc(ATTACHMENT_CHUNK_MAX_BYTES, 255).toString('base64'));
  assert.deepEqual(decodeEnvelope(encodeEnvelope(full)), full);
  for (const data of ['', '/x==', '!!!!', Buffer.alloc(ATTACHMENT_CHUNK_MAX_BYTES + 1).toString('base64')])
    assert.throws(() => encodeEnvelope(artifact(data)));
  for (const hex of ['a16178f7', 'a16178f814', 'a161785a7fffffff', 'a1617842ff', 'a16178' + '81'.repeat(CBOR_MAX_DEPTH + 1) + 'f4'])
    assert.throws(() => decodeEnvelope(Buffer.from(hex, 'hex')));
});
it("rejects malformed CBOR, oversized lengths and partial frames", () => {
  for (const {payloadHex} of vectors.invalid ?? []) assert.throws(()=>decodeEnvelope(Buffer.from(payloadHex,"hex")));
  const decoder = new FrameDecoder();
  assert.throws(()=>decoder.push(Uint8Array.of(0,1,0,1)));
  assert.throws(()=>decoder.finish());
  const partial = new FrameDecoder();
  partial.push(Uint8Array.of(0)); assert.throws(()=>partial.finish());
});
