#![recursion_limit = "256"]
use kompi_protocol::{
    ALPN, ATTACHMENTS_CAPABILITY, ATTACHMENT_CHUNK_MAX_BYTES, ATTACHMENT_MAX_FILE_BYTES,
    ATTACHMENT_MAX_FILES_PER_MESSAGE, ATTACHMENT_MAX_PEER_BYTES, ATTACHMENT_MAX_PEER_ITEMS,
    DEFAULT_IDLE_TIMEOUT_SECONDS, DEFAULT_UPLOAD_RETENTION_SECONDS, MIN_UPLOAD_RETENTION_SECONDS,
    DEVICE_NOTIFICATIONS_CAPABILITY, UNIFIED_PUSH_CAPABILITY, PUSH_ENDPOINT_MAX_BYTES,
    PUSH_MAX_CIPHERTEXT_BYTES, PUSH_MAX_TTL_MS, PUSH_MAX_FUTURE_SKEW_MS,
    MAX_UPLOAD_RETENTION_SECONDS, MAX_RESPONSE_CHUNK_CHARS, MAX_RECOVERY_CHUNKS, MAX_SYNC_EVENTS,
    MAX_SYNC_PAGE, ANDROID_TOOL_ACTION_MAX_BYTES, ANDROID_TOOL_RESULT_MAX_BYTES,
    ANDROID_TOOL_TIMEOUT_MS, DEVICE_NOTIFICATION_TITLE_MAX_CHARS, DEVICE_NOTIFICATION_BODY_MAX_CHARS,
    DEVICE_NOTIFICATION_TIMEOUT_MS,
    CBOR_MAX_ARRAY_ENTRIES, CBOR_MAX_DEPTH, CBOR_MAX_MAP_ENTRIES, IdleTimeoutSeconds,
    MAX_PAYLOAD, Message, PAIRING_TOKEN_PREFIX, PAIRING_VERSION, PHONE_CONVERSATIONS_CAPABILITY,
    PairingPayload, REQUEST_ID_MAX_LENGTH, REQUEST_ID_MIN_LENGTH, REQUEST_ID_PATTERN, VERSION,
    control::{ControlEnvelope, ControlMessage},
    worker::{WorkerEvent, WorkerEventEnvelope, WorkerRequest, WorkerRequestEnvelope},
};
use schemars::{JsonSchema, schema_for};
use std::{fs, path::PathBuf};

fn write<T: JsonSchema>(name: &str) {
    let schema = schema_for!(T);
    let path = PathBuf::from("protocol/schema").join(format!("{name}.json"));
    fs::create_dir_all(path.parent().unwrap()).unwrap();
    let value = serde_json::to_value(&schema).unwrap();
    let pretty = serde_json::to_string_pretty(&value).unwrap() + "\n";
    fs::write(path, pretty).unwrap();
}
fn main() {
    write::<Message>("message");
    write::<kompi_protocol::Envelope<Message>>("network-envelope");
    write::<ControlMessage>("control-message");
    write::<ControlEnvelope>("control-envelope");
    write::<WorkerRequest>("worker-request");
    write::<WorkerEvent>("worker-event");
    write::<WorkerRequestEnvelope>("worker-request-envelope");
    write::<WorkerEventEnvelope>("worker-event-envelope");
    write::<PairingPayload>("pairing-payload");
    write::<kompi_protocol::PushNotificationPayload>("push-notification-payload");
    write::<kompi_protocol::PushAuthenticatedPayload>("push-authenticated-payload");
    write::<kompi_protocol::AgentNotification>("agent-notification");
    write::<kompi_protocol::RichPushNotificationPayload>("rich-push-notification-payload");
    write::<kompi_protocol::ReplyPushNotificationPayload>("reply-push-notification-payload");
    let metadata = serde_json::json!({
        "version": VERSION,
        "pairingVersion": PAIRING_VERSION,
        "alpn": ALPN,
        "maxPayload": MAX_PAYLOAD,
        "defaultIdleTimeoutSeconds": DEFAULT_IDLE_TIMEOUT_SECONDS,
        "defaultUploadRetentionSeconds": DEFAULT_UPLOAD_RETENTION_SECONDS,
        "minUploadRetentionSeconds": MIN_UPLOAD_RETENTION_SECONDS,
        "maxUploadRetentionSeconds": MAX_UPLOAD_RETENTION_SECONDS,
        "maxResponseChunkChars": MAX_RESPONSE_CHUNK_CHARS,
        "maxRecoveryChunks": MAX_RECOVERY_CHUNKS,
        "maxSyncEvents": MAX_SYNC_EVENTS,
        "maxSyncPage": MAX_SYNC_PAGE,
        "androidToolActionMaxBytes": ANDROID_TOOL_ACTION_MAX_BYTES,
        "androidToolResultMaxBytes": ANDROID_TOOL_RESULT_MAX_BYTES,
        "androidToolTimeoutMs": ANDROID_TOOL_TIMEOUT_MS,
        "deviceNotificationTitleMaxChars": DEVICE_NOTIFICATION_TITLE_MAX_CHARS,
        "deviceNotificationBodyMaxChars": DEVICE_NOTIFICATION_BODY_MAX_CHARS,
        "deviceNotificationTimeoutMs": DEVICE_NOTIFICATION_TIMEOUT_MS,
        "pairingTokenPrefix": PAIRING_TOKEN_PREFIX,
        "phoneConversationsCapability": PHONE_CONVERSATIONS_CAPABILITY,
        "attachmentsCapability": ATTACHMENTS_CAPABILITY,
        "artifactDownloadsCapability": kompi_protocol::ARTIFACT_DOWNLOADS_CAPABILITY,
        "deviceNotificationsCapability": DEVICE_NOTIFICATIONS_CAPABILITY,
        "richDeviceNotificationsCapability": kompi_protocol::RICH_DEVICE_NOTIFICATIONS_CAPABILITY,
        "replyNotificationsCapability": kompi_protocol::REPLY_NOTIFICATIONS_CAPABILITY,
        "replyNotificationTtlMs": kompi_protocol::REPLY_NOTIFICATION_TTL_MS,
        "notificationInputMaxBytes": kompi_protocol::NOTIFICATION_INPUT_MAX_BYTES,
        "notificationImageMaxBytes": kompi_protocol::NOTIFICATION_IMAGE_MAX_BYTES,
        "notificationImageMaxPixels": kompi_protocol::NOTIFICATION_IMAGE_MAX_PIXELS,
        "notificationImageMaxDimension": kompi_protocol::NOTIFICATION_IMAGE_MAX_DIMENSION,
        "notificationUrlMaxChars": kompi_protocol::NOTIFICATION_URL_MAX_CHARS,
        "unifiedPushCapability": UNIFIED_PUSH_CAPABILITY,
        "pushEndpointMaxBytes": PUSH_ENDPOINT_MAX_BYTES,
        "pushMaxCiphertextBytes": PUSH_MAX_CIPHERTEXT_BYTES,
        "pushMaxTtlMs": PUSH_MAX_TTL_MS,
        "pushMaxFutureSkewMs": PUSH_MAX_FUTURE_SKEW_MS,
        "attachmentChunkMaxBytes": ATTACHMENT_CHUNK_MAX_BYTES,
        "attachmentMaxFileBytes": ATTACHMENT_MAX_FILE_BYTES,
        "attachmentMaxFilesPerMessage": ATTACHMENT_MAX_FILES_PER_MESSAGE,
        "attachmentMaxPeerBytes": ATTACHMENT_MAX_PEER_BYTES,
        "attachmentMaxPeerItems": ATTACHMENT_MAX_PEER_ITEMS,
        "requestId": {
            "minLength": REQUEST_ID_MIN_LENGTH,
            "maxLength": REQUEST_ID_MAX_LENGTH,
            "pattern": REQUEST_ID_PATTERN
        },
        "idleTimeoutSeconds": {
            "minimum": IdleTimeoutSeconds::MIN_SECONDS,
            "maximum": IdleTimeoutSeconds::MAX_SECONDS
        },
        "cbor": {
            "maxDepth": CBOR_MAX_DEPTH,
            "maxMapEntries": CBOR_MAX_MAP_ENTRIES,
            "maxArrayEntries": CBOR_MAX_ARRAY_ENTRIES
        }
    });
    fs::write(
        "protocol/schema/protocol-metadata.json",
        serde_json::to_string_pretty(&metadata).unwrap() + "\n",
    )
    .unwrap();
}
