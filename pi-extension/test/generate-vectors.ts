import { writeFileSync } from "node:fs";
import { encodeEnvelope } from "../src/protocol/codec.js";
import { frameEnvelope } from "../src/protocol/framing.js";
import { VERSION } from "../src/protocol/generated.js";
const hex = (b: Uint8Array) => Buffer.from(b).toString("hex");
const valid = (["ping", "pong"] as const).map(type => {
  const message = { v: VERSION, id: "example", message: { type, payload: {} } };
  return { name: type, message, payloadHex: hex(encodeEnvelope(message)), frameHex: hex(frameEnvelope(message)) };
});
const invalid = [["empty",""],["indefinite","bfff"],["duplicate-key","a2617604617604"],["invalid-utf8","a161ff01"],["trailing",valid[0]!.payloadHex+"00"]].map(([name,payloadHex])=>({name,payloadHex}));
writeFileSync(new URL("../../protocol/test-vectors/v4.json",import.meta.url), JSON.stringify({version:VERSION, valid, invalid},null,2)+"\n");
