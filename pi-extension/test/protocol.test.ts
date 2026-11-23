import { it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { decodeEnvelope, encodeEnvelope } from "../src/protocol/codec.js";
import { FrameDecoder } from "../src/protocol/framing.js";
const vectors = JSON.parse(readFileSync(process.env.KOTLIN_VECTORS ?? new URL("../../protocol/test-vectors/v4.json", import.meta.url), "utf8"));
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
it("rejects malformed CBOR, oversized lengths and partial frames", () => {
  for (const {payloadHex} of vectors.invalid ?? []) assert.throws(()=>decodeEnvelope(Buffer.from(payloadHex,"hex")));
  const decoder = new FrameDecoder();
  assert.throws(()=>decoder.push(Uint8Array.of(0,1,0,1)));
  assert.throws(()=>decoder.finish());
  const partial = new FrameDecoder();
  partial.push(Uint8Array.of(0)); assert.throws(()=>partial.finish());
});
