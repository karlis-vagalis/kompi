import { spawnSync } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const here=path.dirname(fileURLToPath(import.meta.url)), root=path.resolve(here,'..');
const schemas=path.join(here,'schema'), tsDir=path.join(root,'pi-extension/src/protocol/generated');
const metadata=JSON.parse(await readFile(path.join(schemas,'protocol-metadata.json'),'utf8'));
await mkdir(tsDir,{recursive:true});
function run(args){const r=spawnSync(args[0],args.slice(1),{cwd:here,encoding:'utf8'});if(r.status!==0)throw new Error(`${args.join(' ')} failed: ${r.stderr||r.stdout}`);}
function schemaToZod(schema, defs, seen=new Set()) {
  if(schema.$ref){const key=schema.$ref.split('/').at(-1);if(seen.has(key))return 'z.lazy(() => '+key+'Schema)';return schemaToZod(defs[key],defs,new Set([...seen,key]));}
  if(schema.oneOf||schema.anyOf){const options=schema.oneOf||schema.anyOf;return `z.union([${options.map(x=>schemaToZod(x,defs,seen)).join(', ')}])`;}
  if(schema.allOf)return schema.allOf.map(x=>schemaToZod(x,defs,seen)).join('.and(')+')'.repeat(schema.allOf.length-1);
  if(schema.enum){const vals=schema.enum;return vals.every(x=>typeof x==='string')?`z.enum(${JSON.stringify(vals)})`:`z.union([${vals.map(x=>`z.literal(${JSON.stringify(x)})`).join(',')}])`;}
  const types=Array.isArray(schema.type)?schema.type.filter(x=>x!=='null'):null;
  const t=types?(types.length===1?types[0]:types):schema.type;
  if(Array.isArray(schema.type)&&schema.type.includes('null'))return `z.union([${schemaToZod({...schema,type:t},defs,seen)}, z.null()])`;
  let out;
  if(t==='object'||schema.properties){const props=Object.entries(schema.properties||{}).map(([k,v])=>`${JSON.stringify(k)}: ${schemaToZod(v,defs,seen)}${(schema.required||[]).includes(k)?'':'.optional()'}`).join(', ');out=`z.object({${props}})${schema.additionalProperties===false?'.strict()':''}`;if(schema.properties?.text&&schema.properties?.attachments)out+='.refine(v => v.text.trim().length > 0 || (v.attachments?.length ?? 0) > 0, { message: "prompt requires text or attachments" })';}
  else if(t==='array')out=`z.array(${schemaToZod(schema.items||{},defs,seen)})${schema.maxItems!==undefined?`.max(${schema.maxItems})`:''}${schema.minItems!==undefined?`.min(${schema.minItems})`:''}`;
  else if(t==='string'){out='z.string()';if(schema.format==='byte')out+=' .regex(/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/)';if(schema['x-kompi-transform']==='trim')out+='.trim()';if(schema.minLength!==undefined)out+=`.min(${schema.minLength})`;if(schema.maxLength!==undefined)out+=`.max(${schema.maxLength})`;if(schema.pattern)out+=`.regex(new RegExp(${JSON.stringify(schema.pattern)}))`;if(schema.format==='uuid')out+='.uuid()';if(schema.format==='date-time')out+='.datetime({offset:true})';}
  else if(t==='integer'||t==='number'){if(schema.minimum!==undefined&&schema.minimum===schema.maximum)out=`z.literal(${schema.minimum})`;else{out='z.number()'+(t==='integer'?'.int()':'');if(schema.minimum!==undefined)out+=`.gte(${schema.minimum})`;if(schema.maximum!==undefined)out+=`.lte(${schema.maximum})`;}}
  else if(t==='boolean')out='z.boolean()';else if(t==='null')out='z.null()';else out='z.unknown()';
  if(schema.const!==undefined)out=`z.literal(${JSON.stringify(schema.const)})`;
  return out;
}
const configs=[['message','MessageSchema','Message'],['network-envelope','NetworkEnvelopeSchema','Envelope'],['control-message','ControlMessageSchema','ControlMessage'],['control-envelope','ControlEnvelopeSchema','ControlEnvelope'],['worker-request','WorkerRequestSchema','WorkerRequest'],['worker-event','WorkerEventSchema','WorkerEvent'],['worker-request-envelope','WorkerRequestEnvelopeSchema','WorkerRequestEnvelope'],['worker-event-envelope','WorkerEventEnvelopeSchema','WorkerEventEnvelope'],['pairing-payload','PairingPayloadSchema','PairingPayload'],['push-notification-payload','PushNotificationPayloadSchema','PushNotificationPayload'],['push-authenticated-payload','PushAuthenticatedPayloadSchema','PushAuthenticatedPayload'],['agent-notification','AgentNotificationSchema','AgentNotification'],['rich-push-notification-payload','RichPushNotificationPayloadSchema','RichPushNotificationPayload'],['reply-push-notification-payload','ReplyPushNotificationPayloadSchema','ReplyPushNotificationPayload']];
const exports=[],hashes=[];
for(const [stem,name,type] of configs){const file=path.join(schemas,`${stem}.json`),raw=await readFile(file,'utf8'),doc=JSON.parse(raw),hash=createHash('sha256').update(raw).digest('hex');const defs=doc.$defs||doc.definitions||{};const expr=schemaToZod(doc,defs);const ts=`// GENERATED from Rust schemars schema (${stem}); sha256 ${hash}. DO NOT EDIT.\nimport { z } from "zod";\nexport const ${name} = ${expr};\nexport type ${type} = z.infer<typeof ${name}>;\n`;await writeFile(path.join(tsDir,`${stem}.ts`),ts);hashes.push(`${stem}: ${hash}`);exports.push(`export { ${name}, type ${type} } from "./generated/${stem}.js";`);if(stem==='agent-notification'){await writeFile(path.join(tsDir,'agent-notification-json-schema.ts'),`// GENERATED from Rust. DO NOT EDIT.\nexport const AgentNotificationJsonSchema = ${JSON.stringify(doc)} as const;\n`);exports.push('export { AgentNotificationJsonSchema } from "./generated/agent-notification-json-schema.js";');}
}
const uiDefs=JSON.parse(await readFile(path.join(schemas,'network-envelope.json'),'utf8')).$defs;
const uiVariant=(name,type)=>uiDefs[name].oneOf.find(v=>v.properties.type.const===type).properties.payload.properties;
const uiLimits={
  MAX_PI_UI_TEXT_CHARS:uiVariant('PiUiAnswer','text').text.maxLength,
  MAX_PI_UI_TITLE_CHARS:uiVariant('PiUiDialog','select').title.maxLength,
  MAX_PI_UI_OPTIONS:uiVariant('PiUiDialog','select').options.maxItems,
  MAX_PI_UI_OPTION_CHARS:uiVariant('PiUiDialog','select').options.items.maxLength,
  MAX_PI_UI_MESSAGE_CHARS:uiVariant('PiUiDialog','confirm').message.anyOf?.find(v=>v.type==='string')?.maxLength ?? uiVariant('PiUiDialog','confirm').message.maxLength,
  MAX_PI_UI_TIMEOUT_MS:uiVariant('PiUiDialog','select').timeoutMs.anyOf?.find(v=>v.type==='integer')?.maximum ?? uiVariant('PiUiDialog','select').timeoutMs.maximum,
};
if(Object.values(uiLimits).some(v=>!Number.isInteger(v)))throw new Error('Rust UI schema is missing a required bound');
const metadataHash=createHash('sha256').update(JSON.stringify(metadata)).digest('hex');
hashes.push(`protocol-metadata: ${metadataHash}`);
const index=`// GENERATED by protocol/generate-clients.mjs from Rust schemars output. metadata sha256 ${metadataHash}. DO NOT EDIT.\nimport { NetworkEnvelopeSchema, type Envelope } from "./generated/network-envelope.js";\nimport { MessageSchema, type Message } from "./generated/message.js";\nimport { ControlMessageSchema, type ControlMessage } from "./generated/control-message.js";\nimport { ControlEnvelopeSchema, type ControlEnvelope } from "./generated/control-envelope.js";\nimport { WorkerRequestSchema, type WorkerRequest } from "./generated/worker-request.js";\nimport { WorkerEventSchema, type WorkerEvent } from "./generated/worker-event.js";\nimport { WorkerRequestEnvelopeSchema, type WorkerRequestEnvelope } from "./generated/worker-request-envelope.js";\nimport { WorkerEventEnvelopeSchema, type WorkerEventEnvelope } from "./generated/worker-event-envelope.js";\n${exports.join('\n')}\nexport const parseEnvelope = (value: unknown): Envelope => NetworkEnvelopeSchema.parse(value);\nexport const parseMessage = (value: unknown): Message => MessageSchema.parse(value);\nexport const parseControlMessage = (value: unknown): ControlMessage => ControlMessageSchema.parse(value);\nexport const parseControlEnvelope = (value: unknown): ControlEnvelope => ControlEnvelopeSchema.parse(value);\nexport const parseWorkerRequest = (value: unknown): WorkerRequestEnvelope => WorkerRequestEnvelopeSchema.parse(value);\nexport const parseWorkerEvent = (value: unknown): WorkerEventEnvelope => WorkerEventEnvelopeSchema.parse(value);\nexport const makeWorkerRequest = (id:string,message:WorkerRequest):WorkerRequestEnvelope=>parseWorkerRequest({v:VERSION,id,message});\nexport const makeWorkerEvent = (id:string,message:WorkerEvent):WorkerEventEnvelope=>parseWorkerEvent({v:VERSION,id,message});\nexport const isWorkerRequest=(v:unknown):v is WorkerRequestEnvelope=>WorkerRequestEnvelopeSchema.safeParse(v).success;\nexport const isWorkerEvent=(v:unknown):v is WorkerEventEnvelope=>WorkerEventEnvelopeSchema.safeParse(v).success;\n${Object.entries(uiLimits).map(([key,value])=>`export const ${key}=${value} as const;`).join('\n')}
export const VERSION=${metadata.version} as const;\nexport const PAIRING_VERSION=${metadata.pairingVersion} as const;\nexport const MAX_PAYLOAD=${metadata.maxPayload} as const;\nexport const ALPN=${JSON.stringify(metadata.alpn)} as const;\nexport const PAIRING_TOKEN_PREFIX=${JSON.stringify(metadata.pairingTokenPrefix)} as const;\nexport const PHONE_CONVERSATIONS_CAPABILITY=${JSON.stringify(metadata.phoneConversationsCapability)} as const;
export const ATTACHMENTS_CAPABILITY=${JSON.stringify(metadata.attachmentsCapability)} as const;
export const DEVICE_NOTIFICATIONS_CAPABILITY=${JSON.stringify(metadata.deviceNotificationsCapability)} as const;
export const RICH_DEVICE_NOTIFICATIONS_CAPABILITY=${JSON.stringify(metadata.richDeviceNotificationsCapability)} as const;
export const NOTIFICATION_INPUT_MAX_BYTES=${metadata.notificationInputMaxBytes} as const;
export const UNIFIED_PUSH_CAPABILITY=${JSON.stringify(metadata.unifiedPushCapability)} as const;
export const PUSH_ENDPOINT_MAX_BYTES=${metadata.pushEndpointMaxBytes} as const;
export const PUSH_MAX_CIPHERTEXT_BYTES=${metadata.pushMaxCiphertextBytes} as const;
export const PUSH_MAX_TTL_MS=${metadata.pushMaxTtlMs} as const;
export const PUSH_MAX_FUTURE_SKEW_MS=${metadata.pushMaxFutureSkewMs} as const;
export const ATTACHMENT_CHUNK_MAX_BYTES=${metadata.attachmentChunkMaxBytes} as const;
export const ATTACHMENT_MAX_FILE_BYTES=${metadata.attachmentMaxFileBytes} as const;
export const ATTACHMENT_MAX_FILES_PER_MESSAGE=${metadata.attachmentMaxFilesPerMessage} as const;
export const ATTACHMENT_MAX_PEER_BYTES=${metadata.attachmentMaxPeerBytes} as const;\nexport const ATTACHMENT_MAX_PEER_ITEMS=${metadata.attachmentMaxPeerItems} as const;\nexport const MIN_IDLE_TIMEOUT_SECONDS=${metadata.idleTimeoutSeconds.minimum} as const;\nexport const MAX_IDLE_TIMEOUT_SECONDS=${metadata.idleTimeoutSeconds.maximum} as const;
export const DEFAULT_IDLE_TIMEOUT_SECONDS=${metadata.defaultIdleTimeoutSeconds} as const;
export const DEFAULT_UPLOAD_RETENTION_SECONDS=${metadata.defaultUploadRetentionSeconds} as const;
export const MIN_UPLOAD_RETENTION_SECONDS=${metadata.minUploadRetentionSeconds} as const;
export const MAX_UPLOAD_RETENTION_SECONDS=${metadata.maxUploadRetentionSeconds} as const;
export const MAX_RESPONSE_CHUNK_CHARS=${metadata.maxResponseChunkChars} as const;
export const MAX_RECOVERY_CHUNKS=${metadata.maxRecoveryChunks} as const;
export const MAX_SYNC_EVENTS=${metadata.maxSyncEvents} as const;
export const MAX_SYNC_PAGE=${metadata.maxSyncPage} as const;\nexport const ANDROID_TOOL_ACTION_MAX_BYTES=${metadata.androidToolActionMaxBytes} as const;\nexport const ANDROID_TOOL_RESULT_MAX_BYTES=${metadata.androidToolResultMaxBytes} as const;\nexport const ANDROID_TOOL_TIMEOUT_MS=${metadata.androidToolTimeoutMs} as const;\nexport const DEVICE_NOTIFICATION_TITLE_MAX_CHARS=${metadata.deviceNotificationTitleMaxChars} as const;\nexport const DEVICE_NOTIFICATION_BODY_MAX_CHARS=${metadata.deviceNotificationBodyMaxChars} as const;\nexport const DEVICE_NOTIFICATION_TIMEOUT_MS=${metadata.deviceNotificationTimeoutMs} as const;\nexport const CBOR_MAX_DEPTH=${metadata.cbor.maxDepth} as const;\nexport const CBOR_MAX_MAP_ENTRIES=${metadata.cbor.maxMapEntries} as const;\nexport const CBOR_MAX_ARRAY_ENTRIES=${metadata.cbor.maxArrayEntries} as const;\n`;
await writeFile(path.join(root,'pi-extension/src/protocol/generated.ts'),index);
await writeFile(path.join(here,'schema/SOURCES.sha256'),hashes.join('\n')+'\n');
run(['node',path.join(here,'generate-kotlin-wire.mjs')]);
console.log(`Generated TS/Zod clients from Rust schemars:\n${hashes.join('\n')}`);
