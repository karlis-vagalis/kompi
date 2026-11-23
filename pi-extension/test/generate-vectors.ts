import { readFileSync, writeFileSync } from "node:fs";
import { Encoder } from "cbor-x";
import { encodeEnvelope } from "../src/protocol/codec.js";
import { frameEnvelope } from "../src/protocol/framing.js";
import { VERSION } from "../src/protocol/generated.js";
const hex = (b: Uint8Array) => Buffer.from(b).toString("hex");
const rust = JSON.parse(readFileSync(new URL('../../protocol/test-vectors/rust-v4.json', import.meta.url), 'utf8'));
const cases = (["ping", "pong"] as const).map(type => ({ name: type, message: { v: VERSION, id: "example", message: { type, payload: {} } } }));
const valid = cases.concat(rust.valid).map(({ name, message }) => {
  return { name, message, payloadHex: hex(encodeEnvelope(message)), frameHex: hex(frameEnvelope(message)) };
});
const invalid = [["empty",""],["indefinite","bfff"],["duplicate-key","a2617604617604"],["invalid-utf8","a161ff01"],["trailing",valid[0]!.payloadHex+"00"]].map(([name,payloadHex])=>({name,payloadHex}));
// Deliberately bypass envelope validation to exercise receiver-side checks.
const raw = new Encoder({ useRecords: false, mapsAsObjects: true, variableMapSize: true, tagUint8Array: false });
const uuid = '12345678-1234-4123-8123-123456789abc';
const tool = { conversationId: uuid, requestId: 'req', success: true };
const blank = { text: ' ', configuration: { provider: 'test', modelId: 'test', effort: 'off' }, delivery: 'followUp' };
for (const [name, type, payload] of [
  ['missing-result', 'android-tool-response', tool],
  ['utf8-result-overflow', 'android-tool-response', { ...tool, resultJson: 'é'.repeat(8193) }],
  ['blank-prompt', 'prompt', blank],
  ['blank-timed-prompt', 'timed-prompt', { ...blank, attemptId: uuid }],
  ['cursor-overflow', 'sync', { after: '9223372036854775808' }],
  ['empty-binary', 'artifact-data', { data: Buffer.alloc(0) }],
  ['text-for-bytes', 'artifact-data', { data: '/w==' }],
  ['array-for-bytes', 'artifact-data', { data: [255] }],
] as const) invalid.push({ name, payloadHex: hex(raw.encode({ v: VERSION, id: 'example', message: { type, payload } })) });
writeFileSync(new URL("../../protocol/test-vectors/v4.json",import.meta.url), JSON.stringify({version:VERSION, valid, invalid},null,2)+"\n");
