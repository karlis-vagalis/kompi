import { Decoder, Encoder } from "cbor-x";
import { CBOR_MAX_ARRAY_ENTRIES, CBOR_MAX_DEPTH, CBOR_MAX_MAP_ENTRIES, MAX_PAYLOAD, parseEnvelope, type Envelope } from "./generated.js";

// Disable cbor-x records: the v2 profile is ordinary RFC 8949 string-keyed maps.
const encoder = new Encoder({ useRecords: false, mapsAsObjects: true, variableMapSize: true });
const decoder = new Decoder({ useRecords: false, mapsAsObjects: true });
const utf8 = new TextDecoder("utf-8", { fatal: true });

function checkCbor(bytes: Uint8Array): void {
  let at=0;
  const read=()=>{if(at>=bytes.length)throw new Error("Truncated CBOR");return bytes[at++]!;};
  const argument=(info:number):number=>{if(info<24)return info;const count=({24:1,25:2,26:4} as Record<number,number>)[info];if(!count)throw new Error("Unsupported CBOR length or indefinite item");let n=0;for(let i=0;i<count;i++)n=n*256+read();return n;};
  const item=(depth:number):void=>{if(depth>CBOR_MAX_DEPTH)throw new Error("CBOR nesting limit");const head=read(),major=head>>5,n=argument(head&31);if(major===0)return;if(n>bytes.length)throw new Error("CBOR item exceeds frame");if(major===3){if(at+n>bytes.length)throw new Error("Truncated CBOR string");utf8.decode(bytes.subarray(at,at+n));at+=n;return;}if(major===4){if(n>CBOR_MAX_ARRAY_ENTRIES)throw new Error("CBOR array limit");for(let i=0;i<n;i++)item(depth+1);return;}if(major===5){if(n>CBOR_MAX_MAP_ENTRIES)throw new Error("CBOR map limit");const keys=new Set<string>();for(let i=0;i<n;i++){const key=itemKey(depth+1);if(keys.has(key)||["__proto__","prototype","constructor"].includes(key))throw new Error("Duplicate or unsafe CBOR key");keys.add(key);item(depth+1);}return;}throw new Error("Unsupported CBOR value");};
  const itemKey=(depth:number):string=>{if(depth>CBOR_MAX_DEPTH)throw new Error("CBOR nesting limit");const head=read(),major=head>>5,n=argument(head&31);if(major!==3||n>bytes.length||at+n>bytes.length)throw new Error("CBOR map keys must be bounded UTF-8 strings");const key=utf8.decode(bytes.subarray(at,at+n));at+=n;return key;};
  if(!bytes.length||bytes[0]!>>5!==5)throw new Error("Expected CBOR map");item(0);if(at!==bytes.length)throw new Error("Trailing CBOR bytes");
}

export function encodeEnvelope(envelope: Envelope): Uint8Array {
  const value=parseEnvelope(envelope);
  const bytes=encoder.encode(value);
  if(!bytes.length||bytes.length>MAX_PAYLOAD)throw new Error(`Payload exceeds ${MAX_PAYLOAD} bytes`);
  return bytes;
}
export function decodeEnvelope(bytes:Uint8Array):Envelope {
  if(!bytes.length||bytes.length>MAX_PAYLOAD)throw new Error("Invalid payload size");
  checkCbor(bytes);
  return parseEnvelope(decoder.decode(bytes));
}
