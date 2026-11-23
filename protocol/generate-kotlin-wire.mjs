import { alternatives } from './payload-alternatives.mjs';
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const here=path.dirname(fileURLToPath(import.meta.url)),root=path.resolve(here,'..');
const raw=await readFile(path.join(here,'schema/network-envelope.json'),'utf8'),doc=JSON.parse(raw),defs=doc.$defs||{},variants=defs.Message.oneOf;
const metadata=JSON.parse(await readFile(path.join(here,'schema/protocol-metadata.json'),'utf8'));
const pushPayloadDocs=await Promise.all(['push-notification-payload','push-authenticated-payload','rich-push-notification-payload','reply-push-notification-payload'].map(async name=>JSON.parse(await readFile(path.join(here,`schema/${name}.json`),'utf8'))));
const kotlinType=s=>{if(s.$ref){const key=s.$ref.split('/').at(-1),d=defs[key];return d?.format==='byte'?'ByteArray':d?.enum||d?.type==='string'?'String':d?.type==='object'||d?.oneOf?`com.kompi.app.protocol.generated.${key}`:d?.type==='integer'?(d.format==='int32'?'Int':'Long'):d?.type==='number'?'Double':d?.type==='boolean'?'Boolean':'JsonNode';}if(s.anyOf){const value=s.anyOf.find(x=>x.type!=='null');return `${kotlinType(value||{})}?`;}if(Array.isArray(s.type)){const t=s.type.find(x=>x!=='null');return `${kotlinType({...s,type:t})}?`;}if(s.type==='string')return s.format==='byte'?'ByteArray':'String';if(s.type==='integer')return s.format==='int32'?'Int':'Long';if(s.type==='number')return 'Double';if(s.type==='boolean')return 'Boolean';if(s.type==='array')return `List<${kotlinType(s.items||{})}>`;return 'JsonNode';};
const dtoClasses=Object.entries(defs).filter(([,s])=>s.type==='object'&&s.properties).map(([name,s])=>{const required=new Set(s.required||[]),fields=Object.entries(s.properties).map(([k,v])=>`    val ${k}: ${kotlinType(v)}${required.has(k)?'':' = null'}`).join(',\n');return `data class ${name}(\n${fields}\n)`;});
const pushPayloadClasses=pushPayloadDocs.map(s=>{const fields=Object.entries(s.properties||{}).map(([k,v])=>`    val ${k}: ${kotlinType(v)}`).join(',\n');return `data class ${s.title}(\n${fields}\n)`;});
const eventVariants=(defs.DurableEvent?.oneOf||[]).map(option=>{const tag=option.properties.type.const, payload=option.properties.payload.properties||{}, name=tag.split('-').map(x=>x[0].toUpperCase()+x.slice(1)).join(''), payloadName=`${name}Payload`, required=new Set(option.properties.payload.required||[]), fields=Object.entries(payload).map(([k,v])=>`    val ${k}: ${kotlinType(v)}${required.has(k)?'':' = null'}`).join(',\n');return {tag,name,payloadName,payloadClass:`data class ${payloadName}(\n${fields}\n)`,variant:`    data class ${name}(val payload:${payloadName}):DurableEvent`};});
const eventTypes=`${eventVariants.map(x=>x.payloadClass).join('\n')}\n@com.fasterxml.jackson.annotation.JsonTypeInfo(use=com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME,include=com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY,property="type")\n@com.fasterxml.jackson.annotation.JsonSubTypes(value=[${eventVariants.map(x=>`com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=DurableEvent.${x.name}::class,name=${JSON.stringify(x.tag)})`).join(',')}])\nsealed interface DurableEvent {\n${eventVariants.map(x=>x.variant).join('\n')}\n}`;
// Additional adjacent-tagged domain unions used by the RPC dialog bridge.
const uiTypes=['PiUiDialog','PiUiAnswer','NotificationStyle','NotificationTarget','NotificationProgress'].map(unionName=>{
  const entries=(defs[unionName]?.oneOf||[]).map(option=>{
    const tag=option.properties.type.const,name=tag.split('-').map(x=>x[0].toUpperCase()+x.slice(1)).join('');
    const payload=option.properties.payload,fields=payload.properties||{},required=new Set(payload.required||[]),payloadName=`${unionName}${name}Payload`;
    const props=Object.entries(fields).map(([k,v])=>`    val ${k}: ${kotlinType(v)}${required.has(k)?'':' = null'}`).join(',\n');
    const dto=props?`data class ${payloadName}(\n${props}\n)`:`class ${payloadName} { @com.fasterxml.jackson.annotation.JsonValue fun value():Map<String,String> = emptyMap() }`;
    return {name,tag,payloadName,dto};
  });
  return `${entries.map(x=>x.dto).join('\n')}\n@com.fasterxml.jackson.annotation.JsonTypeInfo(use=com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME,include=com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY,property="type")\n@com.fasterxml.jackson.annotation.JsonSubTypes(value=[${entries.map(x=>`com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=${unionName}.${x.name}::class,name=${JSON.stringify(x.tag)})`).join(',')}])\nsealed interface ${unionName} {\n${entries.map(x=>`    data class ${x.name}(val payload:${x.payloadName}):${unionName}`).join('\n')}\n}`;
}).join('\n');
const classes=[],shapeMap=[];
for(const option of variants){const {type,payload}=option.properties,tag=type.const,name=tag.split('-').map(x=>x[0].toUpperCase()+x.slice(1)).join(''),fields=payload.properties||{},required=new Set(payload.required||[]);const props=Object.entries(fields).map(([k,s])=>`        val ${k}: ${kotlinType(s)}${required.has(k)?'':' = null'}`).join(',\n');classes.push(Object.keys(fields).length ? `    data class ${name}(\n${props}\n    ):WireMessage { override val type:String get()=${JSON.stringify(tag)} }` : `    data object ${name}:WireMessage { override val type:String get()=${JSON.stringify(tag)} }`);shapeMap.push(`        ${JSON.stringify(tag)} to Shape(setOf(${Object.keys(fields).map(JSON.stringify).join(',')}), setOf(${[...required].map(JSON.stringify).join(',')}))`);}
const resolved=s=>s.$ref?defs[s.$ref.split('/').at(-1)]:s.anyOf?resolved(s.anyOf.find(x=>x.type!=='null')||{}):s;
// Generate complete recursive parsers for RPC dialog payloads. Nested bounds and
// tagged alternatives stay Rust-owned; consumers receive already-parsed DTOs.
function nestedChecks(schema, node, depth=0) {
  // Schema references/nullable wrappers add depth without adding CBOR nesting.
  if(depth>24)throw new Error('Shared schema nesting exceeds generator limit');
  if(schema.$ref)return nestedChecks(defs[schema.$ref.split('/').at(-1)],node,depth+1);
  if(schema.anyOf&&!schema.properties){const value=schema.anyOf.find(x=>x.type!=='null');return `if(!${node}.isNull) { ${nestedChecks(value,node,depth+1)} }`;}
  if(Array.isArray(schema.type)){const type=schema.type.find(x=>x!=='null');return `if(!${node}.isNull) { ${nestedChecks({...schema,type},node,depth+1)} }`;}
  if(schema.oneOf){
    const variants=schema.oneOf;
    if(!variants.every(x=>x.properties?.type?.const))throw new Error('Unsupported UI schema union');
    return `if(!${node}.isObject) fail("dialog must be a map"); when(${node}.path("type").asText()) { ${variants.map(v=>`${JSON.stringify(v.properties.type.const)} -> { ${nestedChecks(v,node,depth+1)} }`).join('; ')}; else -> fail("unknown dialog alternative") }`;
  }
  const lines=[];
  if(schema.type==='object'){
    if(schema.anyOf)lines.push(`if(!(${alternatives(schema.anyOf,'kotlin',node)})) fail("invalid payload alternative")`);
    const keys=Object.keys(schema.properties||{}),required=schema.required||[];
    lines.push(`if(!${node}.isObject) fail("dialog field must be a map")`);
    lines.push(`if(!${node}.fieldNames().asSequence().toSet().containsAll(setOf<String>(${required.map(JSON.stringify).join(',')}))) fail("missing dialog field")`);
    if(schema.additionalProperties===false)lines.push(`if(${node}.fieldNames().asSequence().any{it !in setOf<String>(${keys.map(JSON.stringify).join(',')})}) fail("unknown dialog field")`);
    for(const [key,value] of Object.entries(schema.properties||{}))lines.push(`${node}.get(${JSON.stringify(key)})?.let { n${depth} -> ${nestedChecks(value,`n${depth}`,depth+1)} }`);
  }else if(schema.type==='array'){
    lines.push(`if(!${node}.isArray) fail("dialog field must be an array")`);
    if(schema.minItems!==undefined)lines.push(`if(${node}.size()<${schema.minItems}) fail("not enough dialog choices")`);
    if(schema.maxItems!==undefined)lines.push(`if(${node}.size()>${schema.maxItems}) fail("too many dialog entries")`);
    lines.push(`${node}.forEach { n${depth} -> ${nestedChecks(schema.items||{},`n${depth}`,depth+1)} }`);
  }else if(schema.type==='string'){
    lines.push(`if(!${node}.isTextual) fail("dialog field must be text")`);
    if(schema.format==='android-tool-result')lines.push(`if(${node}.asText().encodeToByteArray().size>${metadata.androidToolResultMaxBytes}) fail("Android tool result too large")`);
    if(schema.enum)lines.push(`if(${node}.asText() !in setOf(${schema.enum.map(JSON.stringify).join(',')})) fail("invalid field alternative")`);
    if(schema.minLength!==undefined)lines.push(`if(${node}.asText().length<${schema.minLength}) fail("dialog text too short")`);
    if(schema.maxLength!==undefined)lines.push(`if(${schema['x-kompi-transform']==='trim'?`${node}.asText().codePointCount(0,${node}.asText().length)`:`${node}.asText().length`}>${schema.maxLength}) fail("dialog text too long")`);
    if(schema.pattern)lines.push(`if(!Regex(${JSON.stringify(schema.pattern)}).matches(${node}.asText())) fail("invalid dialog identity")`);
    if(schema.format==='uuid')lines.push(`if(runCatching{java.util.UUID.fromString(${node}.asText()).toString().equals(${node}.asText(),true)}.getOrDefault(false)!=true) fail("invalid dialog conversation")`);
  }else if(schema.type==='integer'){
    lines.push(`if(!${node}.isIntegralNumber||!${node}.canConvertToLong()) fail("dialog field must be an integer")`);
    if(schema.minimum!==undefined)lines.push(`if(${node}.asLong()<${schema.minimum}L) fail("dialog number too small")`);
    if(schema.maximum!==undefined)lines.push(`if(${node}.asLong()>${schema.maximum}L) fail("dialog number too large")`);
  }else if(schema.type==='boolean')lines.push(`if(!${node}.isBoolean) fail("dialog field must be a boolean")`);
  if(schema.const!==undefined)lines.push(`if(${node}.asText()!=${JSON.stringify(String(schema.const))}) fail("invalid dialog tag")`);
  return lines.join('; ');
}
const fieldChecks=variants.map(o=>{const tag=o.properties.type.const,props=o.properties.payload.properties||{};if(o.properties.payload.anyOf||tag.startsWith('pi-ui-')||tag.startsWith('chat-timing-')||tag==='device-rich-notification-request'||tag==='pi-frame'||tag==='generation-stats'||tag==='models-result'||tag==='prompt'||tag==='ack'||tag==='conversation'||tag==='sync-snapshot'||tag==='sync-events'||tag==='session-page-result'||tag==='request-page-result'||tag==='artifact-get'||tag==='artifact-ready')return `            ${JSON.stringify(tag)} -> { ${nestedChecks(o.properties.payload,'payload')} }`;const checks=Object.entries(props).map(([k,rawField])=>{const s=resolved(rawField),nullable=rawField.anyOf?.some(x=>x.type==='null')||Array.isArray(rawField.type)&&rawField.type.includes('null');let lines=[];if(s.format==='byte'){lines.push(`if(!n.isBinary) fail(${JSON.stringify(k+' must be CBOR bytes')})`);lines.push(`if(n.binaryValue().size !in 1..${metadata.attachmentChunkMaxBytes}) fail(${JSON.stringify(k+' too large')})`);}else if(s.type==='string'||s.enum){lines.push(`if(!n.isTextual) fail(${JSON.stringify(k+' must be text')})`);if(s.minLength!==undefined)lines.push(`if(n.asText().length<${s.minLength}) fail(${JSON.stringify(k+' too short')})`);if(s.maxLength!==undefined)lines.push(`if(n.asText().length>${s.maxLength}) fail(${JSON.stringify(k+' too long')})`);if(s.pattern)lines.push(`if(!Regex(${JSON.stringify(s.pattern)}).matches(n.asText())) fail(${JSON.stringify('invalid '+k)})`);if(s.format==='date-time')lines.push(`if(runCatching{java.time.OffsetDateTime.parse(n.asText())}.isFailure) fail(${JSON.stringify('invalid '+k)})`);if(s.format==='uuid')lines.push(`if(runCatching{java.util.UUID.fromString(n.asText())}.isFailure) fail(${JSON.stringify('invalid '+k)})`);if(s.enum)lines.push(`if(n.asText() !in setOf(${s.enum.map(JSON.stringify).join(',')})) fail(${JSON.stringify('invalid '+k)})`);}else if(s.type==='integer'){lines.push(`if(!n.isIntegralNumber) fail(${JSON.stringify(k+' must be integer')})`);if(s.minimum!==undefined)lines.push(`if(n.asLong()<${s.minimum}L) fail(${JSON.stringify(k+' too small')})`);if(s.maximum!==undefined)lines.push(`if(n.asLong()>${s.maximum}L) fail(${JSON.stringify(k+' too large')})`);}else if(s.type==='array'){lines.push(`if(!n.isArray) fail(${JSON.stringify(k+' must be array')})`);if(s.maxItems!==undefined)lines.push(`if(n.size()>${s.maxItems}) fail(${JSON.stringify(k+' too many')})`);if(s.items?.$ref){const item=defs[s.items.$ref.split('/').at(-1)];if(item?.type==='object'){const allowed=Object.keys(item.properties||{}),required=item.required||[];lines.push(`n.forEach{ item -> if(!item.isObject) fail(${JSON.stringify('invalid '+k+' entry')}); val itemKeys=item.fieldNames().asSequence().toSet(); if(itemKeys.any{it !in setOf(${allowed.map(JSON.stringify).join(',')})}||!itemKeys.containsAll(setOf(${required.map(JSON.stringify).join(',')}))) fail(${JSON.stringify('invalid '+k+' entry')}) }`);}}}else if(s.type==='object')lines.push(`if(!n.isObject) fail(${JSON.stringify(k+' must be object')})`);return `            payload.get(${JSON.stringify(k)})?.let { n -> if(!n.isNull) { ${lines.join('; ')} } }`;}).join('\n');return `            ${JSON.stringify(tag)} -> {\n${checks}\n            }`;});
// Keep generated validators below the JVM's per-method bytecode limit as the
 // protocol grows. Keep each message's checks and normalization unchanged.
const validationGroups=[];
for(let start=0;start<variants.length;start+=12){
  const tags=variants.slice(start,start+12).map(v=>JSON.stringify(v.properties.type.const)).join(', ');
  validationGroups.push({
    dispatch:`            ${tags} -> validateGroup${start}(tag,payload)`,
    method:`    private fun validateGroup${start}(tag:String,payload:ObjectNode) {\n        when(tag) {\n${fieldChecks.slice(start,start+12).join('\n')}\n            else -> fail("unknown message type")\n        }\n    }`,
  });
}
const normalizations=variants.map(o=>{const tag=o.properties.type.const,fields=o.properties.payload.properties||{};const code=Object.entries(fields).filter(([,s])=>s['x-kompi-transform']==='trim').map(([k])=>`payload.get(${JSON.stringify(k)})?.takeIf{it.isTextual}?.let{payload.put(${JSON.stringify(k)},it.asText().trim{c -> c.code in 9..13 || c.code==0x85 || Character.isSpaceChar(c)})}`).join('; ');return code?`            ${JSON.stringify(tag)} -> { ${code} }`:null;}).filter(Boolean).join('\n');
const dispatch=variants.map(o=>{const tag=o.properties.type.const,n=tag.split('-').map(x=>x[0].toUpperCase()+x.slice(1)).join('');return `            ${JSON.stringify(tag)} -> mapper.treeToValue(payload, WireMessage.${n}::class.java)`;}).join('\n');
const encode=variants.map(o=>{const tag=o.properties.type.const,n=tag.split('-').map(x=>x[0].toUpperCase()+x.slice(1)).join('');return `            is WireMessage.${n} -> message.type to mapper.valueToTree<ObjectNode>(message)`;}).join('\n');
// Container depth follows the CBOR guard: root is zero, references/unions add no level.
function wireDepth(schema, refs = []) {
  if (schema.$ref) {
    if (refs.includes(schema.$ref)) throw new Error('Recursive wire schema needs an explicit depth policy');
    return wireDepth(defs[schema.$ref.split('/').at(-1)], [...refs, schema.$ref]);
  }
  const alternatives = ['oneOf', 'anyOf', 'allOf'].flatMap(key => schema[key] || []);
  const children = [...Object.values(schema.properties || {}), ...(schema.items ? [schema.items] : []),
    ...(typeof schema.additionalProperties === 'object' ? [schema.additionalProperties] : [])];
  return Math.max(0, ...alternatives.map(value => wireDepth(value, refs)), ...children.map(value => 1 + wireDepth(value, refs)));
}
if (wireDepth(doc) > metadata.cbor.maxDepth) throw new Error('CBOR guard rejects a legal wire-schema alternative');
const metadataHash=createHash('sha256').update(JSON.stringify(metadata)).digest('hex');
const hash=createHash('sha256').update(raw+metadataHash).digest('hex');
const idleTimeoutSchema=metadata.idleTimeoutSeconds;
if(!Number.isInteger(idleTimeoutSchema?.minimum)||!Number.isInteger(idleTimeoutSchema?.maximum))throw new Error('Rust metadata missing IdleTimeoutSeconds bounds');
const piUiTextMax=defs.PiUiAnswer.oneOf.find(v=>v.properties.type.const==='text').properties.payload.properties.text.maxLength;
if(!Number.isInteger(piUiTextMax))throw new Error('Rust UI answer schema is missing its text bound');
const src=`// GENERATED from Rust schemars network envelope; sha256 ${hash}. DO NOT EDIT.
package com.kompi.app.protocol.generated

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.cbor.CBORFactory
import com.fasterxml.jackson.dataformat.cbor.CBORGenerator
import com.fasterxml.jackson.core.io.SerializedString
import java.io.ByteArrayOutputStream
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val MAX_PI_UI_TEXT_CHARS:Int=${piUiTextMax}
const val VERSION:Int=${metadata.version}
const val PAIRING_VERSION:Int=${metadata.pairingVersion}
const val MAX_PAYLOAD:Int=${metadata.maxPayload}
const val ALPN:String=${JSON.stringify(metadata.alpn)}
const val PAIRING_TOKEN_PREFIX:String=${JSON.stringify(metadata.pairingTokenPrefix)}
const val PHONE_CONVERSATIONS_CAPABILITY:String=${JSON.stringify(metadata.phoneConversationsCapability)}
const val ATTACHMENTS_CAPABILITY:String=${JSON.stringify(metadata.attachmentsCapability)}
const val ARTIFACT_DOWNLOADS_CAPABILITY:String=${JSON.stringify(metadata.artifactDownloadsCapability)}
const val DEVICE_NOTIFICATIONS_CAPABILITY:String=${JSON.stringify(metadata.deviceNotificationsCapability)}
const val RICH_DEVICE_NOTIFICATIONS_CAPABILITY:String=${JSON.stringify(metadata.richDeviceNotificationsCapability)}
const val REPLY_NOTIFICATIONS_CAPABILITY:String=${JSON.stringify(metadata.replyNotificationsCapability)}
const val REPLY_NOTIFICATION_TTL_MS:Long=${metadata.replyNotificationTtlMs}L
const val NOTIFICATION_INPUT_MAX_BYTES:Int=${metadata.notificationInputMaxBytes}
const val NOTIFICATION_IMAGE_MAX_BYTES:Int=${metadata.notificationImageMaxBytes}
const val NOTIFICATION_IMAGE_MAX_PIXELS:Int=${metadata.notificationImageMaxPixels}
const val NOTIFICATION_IMAGE_MAX_DIMENSION:Int=${metadata.notificationImageMaxDimension}
const val NOTIFICATION_URL_MAX_CHARS:Int=${metadata.notificationUrlMaxChars}
const val UNIFIED_PUSH_CAPABILITY:String=${JSON.stringify(metadata.unifiedPushCapability)}
const val PUSH_ENDPOINT_MAX_BYTES:Int=${metadata.pushEndpointMaxBytes}
const val PUSH_MAX_CIPHERTEXT_BYTES:Int=${metadata.pushMaxCiphertextBytes}
const val PUSH_MAX_TTL_MS:Long=${metadata.pushMaxTtlMs}L
const val PUSH_MAX_FUTURE_SKEW_MS:Long=${metadata.pushMaxFutureSkewMs}L
const val ATTACHMENT_CHUNK_MAX_BYTES:Int=${metadata.attachmentChunkMaxBytes}
const val ATTACHMENT_MAX_FILE_BYTES:Long=${metadata.attachmentMaxFileBytes}L
const val ATTACHMENT_MAX_FILES_PER_MESSAGE:Int=${metadata.attachmentMaxFilesPerMessage}
const val ATTACHMENT_MAX_PEER_BYTES:Long=${metadata.attachmentMaxPeerBytes}L
const val ATTACHMENT_MAX_PEER_ITEMS:Int=${metadata.attachmentMaxPeerItems}
const val REQUEST_ID_MIN_LENGTH:Int=${metadata.requestId.minLength}
const val REQUEST_ID_MAX_LENGTH:Int=${metadata.requestId.maxLength}
const val REQUEST_ID_PATTERN:String=${JSON.stringify(metadata.requestId.pattern)}
const val MIN_IDLE_TIMEOUT_SECONDS:Int=${idleTimeoutSchema.minimum}
const val MAX_IDLE_TIMEOUT_SECONDS:Int=${idleTimeoutSchema.maximum}
const val DEFAULT_IDLE_TIMEOUT_SECONDS:Int=${metadata.defaultIdleTimeoutSeconds}
const val DEFAULT_UPLOAD_RETENTION_SECONDS:Int=${metadata.defaultUploadRetentionSeconds}
const val MIN_UPLOAD_RETENTION_SECONDS:Int=${metadata.minUploadRetentionSeconds}
const val MAX_UPLOAD_RETENTION_SECONDS:Int=${metadata.maxUploadRetentionSeconds}
const val MAX_RESPONSE_CHUNK_CHARS:Int=${metadata.maxResponseChunkChars}
const val MAX_RECOVERY_CHUNKS:Int=${metadata.maxRecoveryChunks}
const val MAX_SYNC_EVENTS:Int=${metadata.maxSyncEvents}
const val MAX_SYNC_PAGE:Int=${metadata.maxSyncPage}
const val CBOR_MAX_DEPTH:Int=${metadata.cbor.maxDepth}
const val CBOR_MAX_MAP_ENTRIES:Int=${metadata.cbor.maxMapEntries}
const val CBOR_MAX_ARRAY_ENTRIES:Int=${metadata.cbor.maxArrayEntries}
data class EnvelopeV3(val v:Int=VERSION,val id:String,val message:WireMessage)
${dtoClasses.join('\n')}
${pushPayloadClasses.join('\n')}
${eventTypes}
${uiTypes}
sealed interface WireMessage {
    @get:JsonIgnore val type:String
${classes.join('\n')}
}
class WireFormatException(message:String):IllegalArgumentException(message)
private class CborGuard(private val data:ByteArray) {
    private var p=0
    fun verify(){val (major,n)=head();if(major!=5||n !in 1..CBOR_MAX_MAP_ENTRIES.toLong())bad("root map");val keys=mutableSetOf<String>();repeat(n.toInt()){val key=text();if(!keys.add(key))bad("duplicate key");item(1)};if(p!=data.size)bad("trailing CBOR")}
    private fun byte():Int{if(p>=data.size)bad("truncated CBOR");return data[p++].toInt() and 255}
    private fun read(count:Int):Long{if(data.size-p<count)bad("truncated CBOR");var n=0L;repeat(count){n=(n shl 8) or byte().toLong()};return n}
    private fun head():Pair<Int,Long>{val first=byte();val ai=first and 31;val n=when{ai<24->ai.toLong();ai==24->read(1);ai==25->read(2);ai==26->read(4);ai==27->read(8);else->bad("indefinite CBOR")};if(n<0)bad("CBOR integer overflow");return (first ushr 5) to n}
    private fun text():String{val (m,n)=head();if(m!=3||n>MAX_PAYLOAD.toLong()||n>(data.size-p).toLong())bad("invalid CBOR text");val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);val value=decoder.decode(ByteBuffer.wrap(data,p,n.toInt())).toString();p+=n.toInt();return value}
    private fun item(depth:Int){if(depth>CBOR_MAX_DEPTH)bad("CBOR depth");val first=data.getOrNull(p)?.toInt()?.and(255)?:bad("truncated CBOR");if(first ushr 5==7){p++;if(first !in setOf(0xf4,0xf5,0xf6))bad("unsupported CBOR simple value");return};val (m,n)=head();when(m){0,1->Unit;2->{if(n>ATTACHMENT_CHUNK_MAX_BYTES.toLong()||n>(data.size-p).toLong())bad("CBOR byte string length");p+=n.toInt()};3->{if(n>(data.size-p).toLong())bad("CBOR text length");val d=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);d.decode(ByteBuffer.wrap(data,p,n.toInt()));p+=n.toInt()};4->{if(n>CBOR_MAX_ARRAY_ENTRIES)bad("CBOR array limit");repeat(n.toInt()){item(depth+1)}};5->{if(n>CBOR_MAX_MAP_ENTRIES)bad("CBOR map limit");val keys=mutableSetOf<String>();repeat(n.toInt()){if(!keys.add(text()))bad("duplicate key");item(depth+1)}};else->bad("unsupported CBOR type")}}
    private fun bad(reason:String):Nothing=throw WireFormatException(reason)
}
object WireCodec {
    private data class Shape(val fields:Set<String>,val required:Set<String>)
    private val shapes=mapOf(
${shapeMap.join(',\n')}
    )
    private val mapper=ObjectMapper(CBORFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).registerKotlinModule()
    private val idPattern=Regex(REQUEST_ID_PATTERN)
    fun decode(bytes:ByteArray):EnvelopeV3 {
        if(bytes.isEmpty()||bytes.size>MAX_PAYLOAD)fail("payload size")
        CborGuard(bytes).verify()
        try {
            val root=mapper.readTree(bytes) ?: fail("empty CBOR")
            if(!root.isObject||root.fieldNames().asSequence().toSet()!=setOf("v","id","message"))fail("invalid envelope keys")
            val v=root.path("v");if(!v.isIntegralNumber||v.intValue()!=VERSION)fail("unsupported version")
            val id=root.path("id").takeIf{it.isTextual}?.asText()?:fail("id must be text");if(id.length !in REQUEST_ID_MIN_LENGTH..REQUEST_ID_MAX_LENGTH||!idPattern.matches(id))fail("invalid id")
            val outer=root.path("message");if(!outer.isObject||outer.fieldNames().asSequence().toSet()!=setOf("type","payload"))fail("invalid message envelope")
            val tag=outer.path("type").takeIf{it.isTextual}?.asText()?:fail("message type must be text")
            val payloadNode=outer.path("payload");if(!payloadNode.isObject)fail("payload must be map")
            val payload=payloadNode as ObjectNode
            validatePayload(tag,payload)
            val message=when(tag){
${dispatch}
                else -> fail("unknown message type")
            }
            return EnvelopeV3(VERSION,id,message)
        }catch(e:WireFormatException){throw e}catch(e:Exception){throw WireFormatException("invalid CBOR: "+e.message)}
    }
    fun encode(envelope:EnvelopeV3):ByteArray {
        if(envelope.v!=VERSION||envelope.id.length !in REQUEST_ID_MIN_LENGTH..REQUEST_ID_MAX_LENGTH||!idPattern.matches(envelope.id))fail("invalid envelope")
        val (tag,payload)=when(val message=envelope.message){
${encode}
        }
        validatePayload(tag,payload)
        val root=mapper.createObjectNode().put("v",VERSION).put("id",envelope.id)
        val m=mapper.createObjectNode().put("type",tag);m.set<ObjectNode>("payload",payload);root.set<ObjectNode>("message",m)
        val output=ByteArrayOutputStream()
        (mapper.factory.createGenerator(output) as CBORGenerator).use { writeDefinite(it,root) }
        val bytes=output.toByteArray();if(bytes.isEmpty()||bytes.size>MAX_PAYLOAD)fail("payload size");return bytes
    }
    // Jackson's ordinary long-String serializer emits indefinite chunks. Our wire grammar
    // deliberately permits definite containers/text only, including large Unicode prompts.
    private fun writeDefinite(writer:CBORGenerator,node:JsonNode) {
        when {
            node.isObject -> {
                writer.writeStartObject(node.size())
                node.fields().forEachRemaining { (key,value) -> writer.writeFieldName(SerializedString(key)); writeDefinite(writer,value) }
                writer.writeEndObject()
            }
            node.isArray -> { writer.writeStartArray(node.size()); node.forEach { writeDefinite(writer,it) }; writer.writeEndArray() }
            node.isTextual -> { val utf8=node.textValue().encodeToByteArray(throwOnInvalidSequence=true); writer.writeUTF8String(utf8,0,utf8.size) }
            node.isBinary -> writer.writeBinary(node.binaryValue())
            node.isIntegralNumber && node.canConvertToLong() -> writer.writeNumber(node.longValue())
            node.isBoolean -> writer.writeBoolean(node.booleanValue())
            node.isNull -> writer.writeNull()
            else -> fail("unsupported CBOR value")
        }
    }
    private fun validatePayload(tag:String,payload:ObjectNode) {
        val shape=shapes[tag]?:fail("unknown message type")
        val keys=payload.fieldNames().asSequence().toSet();if(keys.any{it !in shape.fields}||!keys.containsAll(shape.required))fail("unknown or missing payload field")
        when(tag) {\n${normalizations}\n            else -> Unit\n        }\n        when(tag) {\n${validationGroups.map(g=>g.dispatch).join('\n')}\n            else -> fail("unknown message type")\n        }
    }
${validationGroups.map(g=>g.method).join('\n')}
    private val artifactJsonMapper by lazy {
        ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxNestingDepth(2).maxStringLength(512).maxNumberLength(20).build()).build())
            .registerKotlinModule()
    }
    fun parseOfferedArtifact(json:String):OfferedArtifact {
        if(json.length>512) fail("artifact metadata too large")
        val node=artifactJsonMapper.readTree(json)?:fail("empty artifact metadata")
        ${nestedChecks(defs.OfferedArtifact,'node')}
        return artifactJsonMapper.treeToValue(node,OfferedArtifact::class.java)
    }
    private val notificationJsonMapper by lazy { ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxNestingDepth(CBOR_MAX_DEPTH).maxStringLength(NOTIFICATION_INPUT_MAX_BYTES).maxNumberLength(20).build()).build()).registerKotlinModule() }
    fun parseAgentNotification(json:String):AgentNotification {
        if(json.toByteArray(Charsets.UTF_8).size>NOTIFICATION_INPUT_MAX_BYTES) fail("notification size")
        val node=notificationJsonMapper.readTree(json)?:fail("empty notification")
        ${nestedChecks(defs.AgentNotification,'node')}
        return notificationJsonMapper.treeToValue(node,AgentNotification::class.java)
    }
    fun parseReplyPushNotificationPayload(json:String):ReplyPushNotificationPayload {
        if(json.toByteArray(Charsets.UTF_8).size>PUSH_MAX_CIPHERTEXT_BYTES) fail("push notification size")
        val node=notificationJsonMapper.readTree(json)?:fail("empty notification")
        ${nestedChecks(pushPayloadDocs.find(s=>s.title==='ReplyPushNotificationPayload'),'node')}
        return notificationJsonMapper.treeToValue(node,ReplyPushNotificationPayload::class.java)
    }
    fun parseRichPushNotificationPayload(json:String):RichPushNotificationPayload {
        if(json.toByteArray(Charsets.UTF_8).size>PUSH_MAX_CIPHERTEXT_BYTES) fail("push notification size")
        val node=notificationJsonMapper.readTree(json)?:fail("empty notification")
        ${nestedChecks(pushPayloadDocs.find(s=>s.title==='RichPushNotificationPayload'),'node')}
        return notificationJsonMapper.treeToValue(node,RichPushNotificationPayload::class.java)
    }
    private fun fail(reason:String):Nothing=throw WireFormatException(reason)
}
object FrameCodec {
    fun frame(payload:ByteArray):ByteArray { if(payload.isEmpty()||payload.size>MAX_PAYLOAD)throw WireFormatException("payload size");return ByteBuffer.allocate(payload.size+4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size).put(payload).array() }
    fun payload(frame:ByteArray):ByteArray { if(frame.size<5)throw WireFormatException("truncated frame");val n=ByteBuffer.wrap(frame,0,4).order(ByteOrder.BIG_ENDIAN).int;if(n !in 1..MAX_PAYLOAD||frame.size!=n+4)throw WireFormatException("invalid frame length");return frame.copyOfRange(4,frame.size) }
}
`;
await writeFile(path.join(root,'android/app/src/main/java/com/kompi/app/protocol/generated/WireV3.kt'),src);
