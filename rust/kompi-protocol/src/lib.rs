//! Rust-authoritative Kompi v4 transport DTOs. Pi owns conversation content.
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};
use std::sync::LazyLock;
use std::{fmt, str::FromStr};
use uuid::Uuid;
mod notifications;
pub use notifications::*;

pub const VERSION: u8 = 4;
pub const PAIRING_VERSION: u8 = 1;
pub const MAX_PAYLOAD: usize = 65_536;
pub const ALPN: &str = "kompi/4";
pub const PAIRING_TOKEN_PREFIX: &str = "kompi-pair-v1:";
// SyncEvents → durable session configuration reaches scalar depth nine. Keep the
// structural limit large enough for every generated wire alternative, not just prompts.
pub const CBOR_MAX_DEPTH: usize = 9;
pub const CBOR_MAX_MAP_ENTRIES: usize = 32;
pub const CBOR_MAX_ARRAY_ENTRIES: usize = 128;
pub const PHONE_CONVERSATIONS_CAPABILITY: &str = "phone-conversations";
pub const PI_ARCHIVE_CAPABILITY: &str = "pi-archive";
pub const CHAT_STREAMS_CAPABILITY: &str = "chat-streams";
pub const CONVERSATION_CONTINUE_CAPABILITY: &str = "conversation-continue";
pub const PI_HOST_HISTORY_CAPABILITY: &str = "pi-host-history";
pub const ATTACHMENTS_CAPABILITY: &str = "attachments";
pub const ARTIFACT_DOWNLOADS_CAPABILITY: &str = "artifact-downloads";
pub const ARTIFACT_MAX_BYTES: u64 = 16 * 1024 * 1024;
pub const ARTIFACT_NAME_PATTERN: &str = r"^[A-Za-z0-9]([A-Za-z0-9 ._()+-]{0,118}[A-Za-z0-9_()+-])?$";
pub fn is_safe_artifact_name(name: &str) -> bool {
    static NAME: LazyLock<regex::Regex> = LazyLock::new(|| regex::Regex::new(ARTIFACT_NAME_PATTERN).expect("artifact name pattern"));
    if !NAME.is_match(name) { return false; }
    let stem = name.split('.').next().unwrap_or_default().trim_end().to_ascii_uppercase();
    !matches!(stem.as_str(), "CON" | "PRN" | "AUX" | "NUL") &&
        !((stem.starts_with("COM") || stem.starts_with("LPT")) && stem.len() == 4 && stem.as_bytes()[3].is_ascii_digit())
}

#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct OfferedArtifact {
    pub artifact_id: ArtifactId,
    #[schemars(regex(pattern = ARTIFACT_NAME_PATTERN))]
    pub file_name: String,
    #[schemars(range(min = 0, max = ARTIFACT_MAX_BYTES))]
    pub length: u64,
    #[schemars(regex(pattern = "^[a-f0-9]{64}$"))]
    pub sha256: String,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash, Serialize, Deserialize)]
#[serde(transparent)]
pub struct ArtifactId(#[serde(with = "uuid::serde::hyphenated")] Uuid);
impl JsonSchema for ArtifactId {
    fn schema_name() -> std::borrow::Cow<'static, str> { "ArtifactId".into() }
    fn json_schema(generator: &mut schemars::SchemaGenerator) -> schemars::Schema {
        ConversationId::json_schema(generator)
    }
}
impl From<Uuid> for ArtifactId {
    fn from(value: Uuid) -> Self { Self(value) }
}
pub const DEVICE_NOTIFICATIONS_CAPABILITY: &str = "device-notifications";
pub const UNIFIED_PUSH_CAPABILITY: &str = "unified-push";
pub const PUSH_ENDPOINT_MAX_BYTES: usize = 2048;
pub const PUSH_MAX_CIPHERTEXT_BYTES: usize = 4096;
pub const PUSH_MAX_TTL_MS: i64 = 24 * 60 * 60 * 1000;
pub const PUSH_MAX_FUTURE_SKEW_MS: i64 = 5 * 60 * 1000;
pub const PUSH_PUBLIC_KEY_PATTERN: &str = "^[A-Za-z0-9_-]{87}$";
pub const PUSH_AUTH_KEY_PATTERN: &str = "^[A-Za-z0-9_-]{22}$";
pub const PUSH_INTEGRITY_KEY_PATTERN: &str = "^[A-Za-z0-9_-]{43}$";
pub const PUSH_MAC_PATTERN: &str = "^[A-Za-z0-9_-]{43}$";
pub const PUSH_PAYLOAD_MAX_BYTES: usize = 32_768;
pub const ATTACHMENT_CHUNK_MAX_BYTES: usize = 49_152;
pub const ATTACHMENT_MAX_FILE_BYTES: u64 = 25 * 1024 * 1024;
pub const ATTACHMENT_MAX_FILES_PER_MESSAGE: usize = 16;
pub const DEFAULT_IDLE_TIMEOUT_SECONDS: u32 = 1_800;
pub const DEFAULT_UPLOAD_RETENTION_SECONDS: u32 = 604_800;
pub const MIN_UPLOAD_RETENTION_SECONDS: u32 = 3_600;
pub const MAX_UPLOAD_RETENTION_SECONDS: u32 = 2_592_000;
pub const MAX_RESPONSE_CHUNK_CHARS: usize = 2048;
pub const MAX_RECOVERY_CHUNKS: usize = 128;
pub const MAX_SYNC_EVENTS: usize = 128;
pub const MAX_SYNC_PAGE: usize = 32;
pub const ANDROID_TOOL_ACTION_MAX_BYTES: usize = 8_192;
pub const ANDROID_TOOL_RESULT_MAX_BYTES: usize = 16_384;
pub const ANDROID_TOOL_TIMEOUT_MS: u32 = 60_000;
pub const DEVICE_NOTIFICATION_TITLE_MAX_CHARS: usize = 120;
pub const DEVICE_NOTIFICATION_BODY_MAX_CHARS: usize = 2_000;
pub const DEVICE_NOTIFICATION_TIMEOUT_MS: u32 = 30_000;
pub const ATTACHMENT_MAX_PEER_BYTES: u64 = 100 * 1024 * 1024;
pub const ATTACHMENT_MAX_PEER_ITEMS: usize = 256;
pub const REQUEST_ID_MIN_LENGTH: usize = 1;
pub const REQUEST_ID_MAX_LENGTH: usize = 128;
pub const REQUEST_ID_PATTERN: &str = "^[A-Za-z0-9._:-]+$";
static REQUEST_ID_REGEX: LazyLock<regex::Regex> =
    LazyLock::new(|| regex::Regex::new(REQUEST_ID_PATTERN).expect("static request id pattern"));

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "lowercase")]
pub enum EffortLevel { Off, Minimal, Low, Medium, High, Xhigh, Max }

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ChatModelConfiguration {
    #[schemars(length(min = 1, max = 128))]
    pub provider: String,
    #[schemars(length(min = 1, max = 256))]
    pub model_id: String,
    pub effort: EffortLevel,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ChatModel {
    #[schemars(length(min = 1, max = 128))]
    pub provider: String,
    #[schemars(length(min = 1, max = 256))]
    pub model_id: String,
    #[schemars(length(min = 1, max = 256))]
    pub name: String,
    #[schemars(length(min = 1, max = 7))]
    pub efforts: Vec<EffortLevel>,
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ModelCatalog {
    #[schemars(length(max = 128))]
    pub models: Vec<ChatModel>,
    pub default_configuration: Option<ChatModelConfiguration>,
}

#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "kebab-case")]
pub enum PiFrameKind { Event, Snapshot, Entries, Settled }

/// Bounded fragments of an unmodified Pi event or get_entries response. The
/// epoch/sequence is an in-memory snapshot barrier, not another transcript cursor.
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PiFrame {
    pub conversation_id: ConversationId,
    #[schemars(length(min = 1, max = 128))]
    pub session_id: String,
    #[schemars(length(min = 1, max = 128))]
    pub epoch: String,
    pub sequence: u64,
    pub part: u32,
    pub last: bool,
    pub kind: PiFrameKind,
    #[schemars(length(max = 8192))]
    pub json: String,
    pub request_id: Option<RequestId>,
}

pub const CHAT_TIMING_CAPABILITY: &str = "chat-timing-v1";
pub const MAX_CHAT_TIMING_PAGE: usize = 32;

#[derive(Clone, Copy, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum ChatTimingStage {
    DaemonReceipt, DaemonCatalogReady, DaemonSweepReady, DaemonClaimCommitted,
    DaemonClaimReplay, DaemonAckWritten, DaemonAckWriteFailed, DaemonRejected,
    DaemonWorkerDispatch, DaemonWorkerWritten, DaemonWorkerFinal, DaemonWorkerFailed,
    DaemonCompletionCommitted, DaemonHandlerFailed,
    DaemonFollowUpStaging, DaemonFollowUpStaged, DaemonFollowUpReleased,
    WorkerAssistantStart, WorkerFirstText, WorkerAssistantLastEnd, WorkerAgentSettled, WorkerFollowUpBoundary,
}

#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ChatTimingStamp {
    pub clock_id: ConversationId,
    pub monotonic_ms: u64,
    pub wall_ms: u64,
    pub stage: ChatTimingStage,
}

#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ChatTimingEvent {
    pub request_id: RequestId,
    pub attempt_id: ConversationId,
    pub conversation_id: Option<ConversationId>,
    pub response_request_id: Option<RequestId>,
    pub stamp: ChatTimingStamp,
}

#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ChatTimingCursor {
    pub store_id: ConversationId,
    pub after: SyncCursor,
}

#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ChatTimingRow {
    pub cursor: SyncCursor,
    pub event: ChatTimingEvent,
}

#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GenerationStats {
    pub first_text_ms: Option<u32>,
    pub total_ms: u32,
    pub daemon_total_ms: Option<u32>,
    pub output_tokens: Option<u32>,
    pub reasoning_tokens: Option<u32>,
    pub text_chunks: u32,
    pub assistant_messages: u32,
    #[schemars(length(max = 256))]
    pub model: Option<String>,
    #[schemars(length(max = 128))]
    pub provider: Option<String>,
}

/// Opaque correlation identifier that is validated exactly once on ingress.
#[derive(Clone, Debug, Eq, PartialEq, Hash, Serialize)]
#[serde(transparent)]
pub struct RequestId(String);
impl JsonSchema for RequestId {
    fn schema_name() -> std::borrow::Cow<'static, str> {
        "RequestId".into()
    }
    fn json_schema(_: &mut schemars::SchemaGenerator) -> schemars::Schema {
        schemars::json_schema!({"type":"string","minLength":REQUEST_ID_MIN_LENGTH,"maxLength":REQUEST_ID_MAX_LENGTH,"pattern":REQUEST_ID_PATTERN})
    }
}
impl RequestId {
    pub fn parse(value: impl Into<String>) -> Result<Self, ProtocolError> {
        let value = value.into();
        if value.len() < REQUEST_ID_MIN_LENGTH
            || value.len() > REQUEST_ID_MAX_LENGTH
            || !REQUEST_ID_REGEX.is_match(&value)
        {
            return Err(ProtocolError::Invalid("request id"));
        }
        Ok(Self(value))
    }
    pub fn as_str(&self) -> &str {
        &self.0
    }
}
impl<'de> Deserialize<'de> for RequestId {
    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        let value = String::deserialize(d)?;
        Self::parse(value).map_err(serde::de::Error::custom)
    }
}
impl FromStr for RequestId {
    type Err = ProtocolError;
    fn from_str(s: &str) -> Result<Self, Self::Err> {
        Self::parse(s)
    }
}
impl fmt::Display for RequestId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

/// Bounded chunk body. serde_bytes preserves CBOR major type 2 (byte string), not an integer array.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(transparent)]
pub struct AttachmentChunkData(#[serde(with = "attachment_bytes")] Vec<u8>);
impl AttachmentChunkData {
    pub fn as_bytes(&self) -> &[u8] { &self.0 }
    pub fn from_slice(bytes: &[u8]) -> Result<Self, ProtocolError> {
        if bytes.is_empty() || bytes.len() > ATTACHMENT_CHUNK_MAX_BYTES {
            return Err(ProtocolError::Invalid("attachment chunk length"));
        }
        Ok(Self(bytes.to_vec()))
    }
}
mod attachment_bytes {
    use base64::Engine;
    use serde::{Deserialize, Deserializer, Serialize, Serializer};
    pub fn serialize<S: Serializer>(bytes: &Vec<u8>, serializer: S) -> Result<S::Ok, S::Error> {
        if serializer.is_human_readable() {
            serializer.serialize_str(&base64::engine::general_purpose::STANDARD.encode(bytes))
        } else {
            serde_bytes::Bytes::new(bytes).serialize(serializer)
        }
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Vec<u8>, D::Error> {
        if deserializer.is_human_readable() {
            let value = String::deserialize(deserializer)?;
            base64::engine::general_purpose::STANDARD.decode(value).map_err(serde::de::Error::custom)
        } else {
            Ok(serde_bytes::ByteBuf::deserialize(deserializer)?.into_vec())
        }
    }
}
impl JsonSchema for AttachmentChunkData {
    fn schema_name() -> std::borrow::Cow<'static, str> { "AttachmentChunkData".into() }
    fn json_schema(_: &mut schemars::SchemaGenerator) -> schemars::Schema {
        schemars::json_schema!({"type":"string","format":"byte","contentEncoding":"base64","maxLength":65536})
    }
}

/// Canonical conversation identity; UUID parsing occurs at serde ingress.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash, Serialize, Deserialize)]
#[serde(transparent)]
pub struct ConversationId(#[serde(with = "uuid::serde::hyphenated")] Uuid);
impl ConversationId {
    pub const fn as_uuid(&self) -> &Uuid {
        &self.0
    }
}
impl From<Uuid> for ConversationId {
    fn from(value: Uuid) -> Self {
        Self(value)
    }
}
impl fmt::Display for ConversationId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        self.0.fmt(f)
    }
}
impl JsonSchema for ConversationId {
    fn schema_name() -> std::borrow::Cow<'static, str> {
        "ConversationId".into()
    }
    fn json_schema(_: &mut schemars::SchemaGenerator) -> schemars::Schema {
        schemars::json_schema!({"type":"string","format":"uuid"})
    }
}

/// Identity for one current per-device UnifiedPush registration.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash, Serialize, Deserialize)]
#[serde(transparent)]
pub struct PushRegistrationId(#[serde(with = "uuid::serde::hyphenated")] Uuid);
impl PushRegistrationId {
    pub const fn as_uuid(&self) -> &Uuid { &self.0 }
}
impl From<Uuid> for PushRegistrationId {
    fn from(value: Uuid) -> Self { Self(value) }
}
impl fmt::Display for PushRegistrationId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result { self.0.fmt(f) }
}
impl JsonSchema for PushRegistrationId {
    fn schema_name() -> std::borrow::Cow<'static, str> { "PushRegistrationId".into() }
    fn json_schema(_: &mut schemars::SchemaGenerator) -> schemars::Schema {
        schemars::json_schema!({"type":"string","format":"uuid"})
    }
}

/// RFC8291 P-256 uncompressed SEC1 public key encoded as canonical unpadded base64url.
#[derive(Clone, Eq, PartialEq, Serialize)]
#[serde(transparent)]
pub struct PushPublicKey(String);
impl PushPublicKey { pub fn as_str(&self) -> &str { &self.0 } }
impl TryFrom<String> for PushPublicKey {
    type Error = ProtocolError;
    fn try_from(value: String) -> Result<Self, Self::Error> {
        use base64::Engine;
        let decoded = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(&value)
            .map_err(|_| ProtocolError::Invalid("push public key"))?;
        if decoded.len() != 65 || decoded.first() != Some(&4)
            || base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(&decoded) != value {
            return Err(ProtocolError::Invalid("push public key"));
        }
        Ok(Self(value))
    }
}
impl<'de> Deserialize<'de> for PushPublicKey {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        Self::try_from(String::deserialize(deserializer)?).map_err(serde::de::Error::custom)
    }
}
impl JsonSchema for PushPublicKey {
    fn schema_name() -> std::borrow::Cow<'static, str> { "PushPublicKey".into() }
    fn json_schema(_: &mut schemars::SchemaGenerator) -> schemars::Schema {
        schemars::json_schema!({"type":"string","minLength":87,"maxLength":87,"pattern":PUSH_PUBLIC_KEY_PATTERN})
    }
}

/// RFC8291 16-byte authentication secret, canonical unpadded base64url.
#[derive(Clone, Eq, PartialEq, Serialize)]
#[serde(transparent)]
pub struct PushAuthSecret(String);
impl PushAuthSecret { pub fn expose_secret(&self) -> &str { &self.0 } }
impl TryFrom<String> for PushAuthSecret {
    type Error = ProtocolError;
    fn try_from(value: String) -> Result<Self, Self::Error> {
        use base64::Engine;
        let decoded = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(&value)
            .map_err(|_| ProtocolError::Invalid("push auth secret"))?;
        if decoded.len() != 16 || base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(&decoded) != value {
            return Err(ProtocolError::Invalid("push auth secret"));
        }
        Ok(Self(value))
    }
}
impl<'de> Deserialize<'de> for PushAuthSecret {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        Self::try_from(String::deserialize(deserializer)?).map_err(serde::de::Error::custom)
    }
}
impl JsonSchema for PushAuthSecret {
    fn schema_name() -> std::borrow::Cow<'static, str> { "PushAuthSecret".into() }
    fn json_schema(_: &mut schemars::SchemaGenerator) -> schemars::Schema {
        schemars::json_schema!({"type":"string","minLength":22,"maxLength":22,"pattern":PUSH_AUTH_KEY_PATTERN})
    }
}

/// Per-registration HMAC key; intentionally has no Debug implementation.
#[derive(Clone, Eq, PartialEq, Serialize)]
#[serde(transparent)]
pub struct PushIntegrityKey(String);
impl PushIntegrityKey {
    pub fn expose_secret(&self) -> &str { &self.0 }
}
impl TryFrom<String> for PushIntegrityKey {
    type Error = ProtocolError;
    fn try_from(value: String) -> Result<Self, Self::Error> {
        use base64::Engine;
        let decoded = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(&value)
            .map_err(|_| ProtocolError::Invalid("push integrity key"))?;
        if decoded.len() != 32 || base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(&decoded) != value {
            return Err(ProtocolError::Invalid("push integrity key"));
        }
        Ok(Self(value))
    }
}
impl<'de> Deserialize<'de> for PushIntegrityKey {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        Self::try_from(String::deserialize(deserializer)?).map_err(serde::de::Error::custom)
    }
}
impl JsonSchema for PushIntegrityKey {
    fn schema_name() -> std::borrow::Cow<'static, str> { "PushIntegrityKey".into() }
    fn json_schema(_: &mut schemars::SchemaGenerator) -> schemars::Schema {
        schemars::json_schema!({"type":"string","minLength":43,"maxLength":43,"pattern":PUSH_INTEGRITY_KEY_PATTERN})
    }
}

#[derive(Clone, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PushRegistration {
    pub registration_id: PushRegistrationId,
    #[schemars(length(min = 1, max = PUSH_ENDPOINT_MAX_BYTES))]
    pub endpoint: String,
    pub p256dh: PushPublicKey,
    pub auth: PushAuthSecret,
    pub integrity_key: PushIntegrityKey,
}
impl fmt::Debug for PushRegistration {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("PushRegistration")
            .field("registration_id", &self.registration_id)
            .field("endpoint", &"[REDACTED]")
            .field("p256dh", &"[REDACTED]")
            .field("auth", &"[REDACTED]")
            .field("integrity_key", &"[REDACTED]")
            .finish()
    }
}

/// RFC8291 plaintext packet; serialize once, then authenticate those exact bytes before encryption.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PushNotificationPayload {
    #[schemars(range(min = 1, max = 1))]
    pub version: u8,
    #[schemars(regex(pattern = "^[a-f0-9]{64}$"))]
    pub host_endpoint: String,
    pub registration_id: PushRegistrationId,
    pub notification_id: RequestId,
    pub issued_at_ms: i64,
    pub expires_at_ms: i64,
    #[schemars(length(min = 1, max = DEVICE_NOTIFICATION_TITLE_MAX_CHARS))]
    pub title: String,
    #[schemars(length(min = 1, max = DEVICE_NOTIFICATION_BODY_MAX_CHARS))]
    pub body: String,
}

/// Application-level MAC wrapper carried inside RFC8291 ciphertext.
#[derive(Clone, Eq, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PushAuthenticatedPayload {
    #[schemars(range(min = 1, max = 1))]
    pub version: u8,
    #[schemars(length(max = PUSH_PAYLOAD_MAX_BYTES))]
    pub payload: String,
    #[schemars(length(min = 43, max = 43))]
    pub mac: String,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "lowercase")]
pub enum PushRegistrationStatus { Registered, Unregistered, Rejected }

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "kebab-case")]
pub enum PushRegistrationErrorCode {
    OriginNotAllowed,
    InvalidEndpoint,
    InvalidKeys,
    RegistrationLimit,
    RegistrationMismatch,
    PushDisabled,
    ProviderUnavailable,
}

/// A validated policy duration, bounded once at the wire boundary.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd, Serialize)]
#[serde(transparent)]
pub struct IdleTimeoutSeconds(u32);
impl IdleTimeoutSeconds {
    pub const MIN_SECONDS: u32 = 60;
    pub const MAX_SECONDS: u32 = 86_400;
    pub const fn get(self) -> u32 {
        self.0
    }
}
impl TryFrom<u32> for IdleTimeoutSeconds {
    type Error = ProtocolError;
    fn try_from(value: u32) -> Result<Self, Self::Error> {
        if (Self::MIN_SECONDS..=Self::MAX_SECONDS).contains(&value) {
            Ok(Self(value))
        } else {
            Err(ProtocolError::Invalid("idle timeout seconds"))
        }
    }
}
impl<'de> Deserialize<'de> for IdleTimeoutSeconds {
    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        Self::try_from(u32::deserialize(d)?).map_err(serde::de::Error::custom)
    }
}
impl JsonSchema for IdleTimeoutSeconds {
    fn schema_name() -> std::borrow::Cow<'static, str> {
        "IdleTimeoutSeconds".into()
    }
    fn json_schema(_: &mut schemars::SchemaGenerator) -> schemars::Schema {
        schemars::json_schema!({"type":"integer","format":"int32","minimum":Self::MIN_SECONDS,"maximum":Self::MAX_SECONDS})
    }
}

/// RFC3339 timestamp parsed through jiff at protocol ingress.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Timestamp(jiff::Timestamp);
impl Timestamp {
    pub fn as_jiff(&self) -> &jiff::Timestamp {
        &self.0
    }
}
impl TryFrom<String> for Timestamp {
    type Error = ProtocolError;
    fn try_from(value: String) -> Result<Self, Self::Error> {
        jiff::Timestamp::from_str(&value)
            .map(Self)
            .map_err(|_| ProtocolError::Invalid("timestamp"))
    }
}
impl Serialize for Timestamp {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(&self.0.to_string())
    }
}
impl<'de> Deserialize<'de> for Timestamp {
    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        let value = String::deserialize(d)?;
        Self::try_from(value).map_err(serde::de::Error::custom)
    }
}
impl JsonSchema for Timestamp {
    fn schema_name() -> std::borrow::Cow<'static, str> {
        "Timestamp".into()
    }
    fn json_schema(_: &mut schemars::SchemaGenerator) -> schemars::Schema {
        schemars::json_schema!({"type":"string","format":"date-time"})
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Hash, Serialize, Deserialize)]
#[serde(transparent)]
pub struct UiInteractionId(RequestId);
impl UiInteractionId {
    pub fn as_str(&self) -> &str { self.0.as_str() }
}
impl JsonSchema for UiInteractionId {
    fn schema_name() -> std::borrow::Cow<'static, str> { "UiInteractionId".into() }
    fn json_schema(generator: &mut schemars::SchemaGenerator) -> schemars::Schema { RequestId::json_schema(generator) }
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(tag = "type", content = "payload", rename_all = "kebab-case", rename_all_fields = "camelCase", deny_unknown_fields)]
pub enum PiUiDialog {
    Select {
        #[schemars(length(max = 2048))] title: String,
        #[schemars(length(min = 1, max = 32), inner(length(max = 1024)))] options: Vec<String>,
        #[schemars(range(min = 1, max = 600000))] timeout_ms: Option<u32>,
    },
    Confirm {
        #[schemars(length(max = 2048))] title: String,
        #[schemars(length(max = 4096))] message: Option<String>,
        #[schemars(range(min = 1, max = 600000))] timeout_ms: Option<u32>,
    },
    Input {
        #[schemars(length(max = 2048))] title: String,
        #[schemars(length(max = 2048))] placeholder: Option<String>,
        #[schemars(range(min = 1, max = 600000))] timeout_ms: Option<u32>,
    },
    Editor {
        #[schemars(length(max = 2048))] title: String,
        #[schemars(length(max = 16000))] prefill: Option<String>,
        #[schemars(range(min = 1, max = 600000))] timeout_ms: Option<u32>,
    },
}

#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(tag = "type", content = "payload", rename_all = "kebab-case", deny_unknown_fields)]
pub enum PiUiAnswer {
    Cancelled {},
    Selected { #[schemars(range(min = 0, max = 31), extend("format" = "int32"))] index: u8 },
    Confirmed { confirmed: bool },
    Text { #[schemars(length(max = 16000))] text: String },
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PendingPiUiRequest {
    pub conversation_id: ConversationId,
    pub interaction_id: UiInteractionId,
    pub dialog: PiUiDialog,
}

#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct Envelope<T> {
    #[schemars(range(min = VERSION, max = VERSION))]
    pub v: u8,
    pub id: RequestId,
    pub message: T,
}
impl<T> Envelope<T> {
    pub fn new(id: RequestId, message: T) -> Self {
        Self {
            v: VERSION,
            id,
            message,
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(
    tag = "type",
    content = "payload",
    rename_all = "kebab-case",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
pub enum Message {
    ModelsGet {},
    ModelsResult { catalog: ModelCatalog },
    /// Reconcile all active conversations on reconnect.
    PiSnapshotGet {},
    PiConversationSnapshotGet { conversation_id: ConversationId },
    ConversationStreamOpen { conversation_id: ConversationId },
    /// First and only request on a separate authenticated bidirectional stream.
    PiArchiveGet { conversation_id: ConversationId },
    PiFrame { frame: PiFrame },
    PiResetRequired {},
    PiSessionEmpty {},
    GenerationStats { request_id: RequestId, stats: GenerationStats },
    /// Ephemeral CLI dialogs are replayed as a current-state snapshot, never as approvals.
    PiUiState { #[schemars(length(max = 1))] requests: Vec<PendingPiUiRequest> },
    PiConversationUiState { conversation_id: ConversationId, #[schemars(length(max = 1))] requests: Vec<PendingPiUiRequest> },
    PiUiResponse { conversation_id: ConversationId, interaction_id: UiInteractionId, answer: PiUiAnswer },
    AndroidToolRequest { conversation_id: ConversationId, request_id: RequestId, tool: AndroidToolKind, #[schemars(range(min = 1, max = ANDROID_TOOL_TIMEOUT_MS))] timeout_ms: u32, #[schemars(length(max = ANDROID_TOOL_ACTION_MAX_BYTES))] action_json: Option<String> },
    AndroidToolResponse { conversation_id: ConversationId, request_id: RequestId, success: bool, #[schemars(length(max = ANDROID_TOOL_RESULT_MAX_BYTES))] result_json: Option<String>, #[schemars(length(max = 64))] error_code: Option<String>, #[schemars(length(max = 512))] error_message: Option<String> },
    AndroidToolResolved { conversation_id: ConversationId, request_id: RequestId },
    DeviceNotificationRequest { request_id: RequestId, #[schemars(length(min = 1, max = DEVICE_NOTIFICATION_TITLE_MAX_CHARS))] title: String, #[schemars(length(min = 1, max = DEVICE_NOTIFICATION_BODY_MAX_CHARS))] body: String, #[schemars(range(min = 1, max = DEVICE_NOTIFICATION_TIMEOUT_MS))] timeout_ms: u32 },
    DeviceRichNotificationRequest { request_id: RequestId, notification: AgentNotification, #[schemars(range(min = 1, max = DEVICE_NOTIFICATION_TIMEOUT_MS))] timeout_ms: u32 },
    DeviceReplyNotificationRequest { request_id: RequestId, reply: ReplyNotification, #[schemars(range(min = 1, max = DEVICE_NOTIFICATION_TIMEOUT_MS))] timeout_ms: u32 },
    DeviceNotificationResponse { request_id: RequestId, status: DeviceNotificationReceipt, #[schemars(length(max = 64))] code: Option<String>, #[schemars(length(max = 512))] message: Option<String> },
    PushRegister { registration: PushRegistration },
    PushUnregister { registration_id: PushRegistrationId },
    PushRegistrationResult { registration_id: PushRegistrationId, status: PushRegistrationStatus, code: Option<PushRegistrationErrorCode> },
    Hello {
        #[schemars(length(max = 32), inner(length(max = 64)))]
        capabilities: Vec<String>,
        #[schemars(regex(pattern = "^[a-f0-9]{64}$"))]
        endpoint: Option<String>,
        #[schemars(length(max = 1024))]
        session: Option<String>,
        idle_timeout_seconds: Option<IdleTimeoutSeconds>,
    },
    Pair {
        #[schemars(length(min = 32, max = 256))]
        secret: String,
        #[schemars(length(min = 1, max = 128))]
        name: String,
    },
    Paired {
        #[schemars(regex(pattern = "^[a-f0-9]{64}$"))]
        endpoint: String,
    },
    Prompt {
        #[schemars(length(max = 16000), extend("x-kompi-transform" = "trim"))]
        text: String,
        delivery: Delivery,
        configuration: ChatModelConfiguration,
        #[schemars(length(max = ATTACHMENT_MAX_FILES_PER_MESSAGE))]
        attachments: Option<Vec<ConversationId>>,
    },
    /// Same delivery/ledger semantics as Prompt. The attempt UUID is diagnostic only.
    TimedPrompt {
        attempt_id: ConversationId,
        #[schemars(length(max = 16000), extend("x-kompi-transform" = "trim"))]
        text: String,
        delivery: Delivery,
        configuration: ChatModelConfiguration,
        #[schemars(length(max = ATTACHMENT_MAX_FILES_PER_MESSAGE))]
        attachments: Option<Vec<ConversationId>>,
    },
    ChatTimingGet { cursor: Option<ChatTimingCursor> },
    ChatTimingPage {
        store_id: ConversationId,
        #[schemars(length(max = MAX_CHAT_TIMING_PAGE))]
        rows: Vec<ChatTimingRow>,
        has_more: bool,
    },
    /// Starts a role-based stream. Single-request bulk streams use their typed request
    /// instead. Iroh authenticates the connection; this role conveys no authority.
    StreamOpen { role: StreamRole },
    Sync { after: Option<SyncCursor> },
    SyncPage { after: Option<SyncCursor>, #[schemars(range(min = 1, max = MAX_SYNC_PAGE), extend("format" = "int32"))] limit: u8 },
    RequestPage { after: Option<RequestId>, #[schemars(range(min = 1, max = MAX_SYNC_PAGE), extend("format" = "int32"))] limit: u8 },
    RequestPageResult { #[schemars(length(max = MAX_SYNC_PAGE))] requests: Vec<RequestSummary>, next_cursor: Option<RequestId> },
    SessionPage { after: Option<ConversationId>, #[schemars(range(min = 1, max = MAX_SYNC_PAGE), extend("format" = "int32"))] limit: u8 },
    SessionPageResult { #[schemars(length(max = MAX_SYNC_PAGE))] sessions: Vec<ConversationMetadata>, next_cursor: Option<ConversationId> },
    PiHistoryPage { after: Option<ConversationId>, #[schemars(range(min = 1, max = MAX_SYNC_PAGE), extend("format" = "int32"))] limit: u8 },
    PiHistoryPageResult { #[schemars(length(max = MAX_SYNC_PAGE))] sessions: Vec<ConversationMetadata>, next_cursor: Option<ConversationId> },
    SyncSnapshot {
        cursor: SyncCursor,
        state: SyncState,
        settings: EffectiveSettings,
        #[schemars(length(max = MAX_SYNC_PAGE))]
        sessions: Vec<ConversationMetadata>,
        #[schemars(length(max = MAX_SYNC_PAGE))]
        outstanding_requests: Vec<RequestSummary>,
        has_more_sessions: bool,
        has_more_requests: bool,
    },
    SyncEvents { #[schemars(length(max = MAX_SYNC_EVENTS))] events: Vec<SyncEvent>, next_cursor: SyncCursor },
    SyncResetRequired { current_cursor: SyncCursor },
    SettingsGet {},
    SettingsResult { settings: EffectiveSettings },
    SettingsUpdate { update_id: RequestId, settings: EffectiveSettings },
    SettingsConfirmed { update_id: RequestId, settings: EffectiveSettings },
    SettingsRejected { update_id: RequestId, code: String, message: String },
    PreacceptanceRejected { request_id: RequestId, code: String, message: String, retryable: bool },
    ResponseComplete { request_id: RequestId, conversation_id: ConversationId, outcome: RequestOutcome, final_sequence: u32 },
    ConversationClose { conversation_id: ConversationId },
    ConversationDelete { conversation_id: ConversationId },
    ConversationDeleted { conversation_id: ConversationId },
    ConversationActivate {
        conversation_id: ConversationId,
        expected_current: Option<ConversationId>,
    },
    /// This intent never follows a change of visible chat.
    ConversationPrompt {
        conversation_id: ConversationId,
        /// A new phone draft supplies a stable random v4 UUID before its first ACK.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        create_if_missing: Option<bool>,
        /// Present only when chat timing was negotiated; excluded from retry identity.
        attempt_id: Option<ConversationId>,
        #[schemars(length(max = 16000), extend("x-kompi-transform" = "trim"))]
        text: String,
        delivery: Delivery,
        configuration: ChatModelConfiguration,
        #[schemars(length(max = ATTACHMENT_MAX_FILES_PER_MESSAGE))]
        attachments: Option<Vec<ConversationId>>,
    },
    AttachmentExpired { upload_id: ConversationId },
    ArtifactGet { conversation_id: ConversationId, artifact_id: ArtifactId },
    ArtifactReady { artifact: OfferedArtifact },
    ArtifactData { data: AttachmentChunkData },
    ArtifactEnd {},
    AttachmentOffer {
        upload_id: ConversationId,
        #[schemars(length(max = 255))]
        file_name: String,
        #[schemars(length(max = 127))]
        media_type: String,
        #[schemars(range(min = 0, max = ATTACHMENT_MAX_FILE_BYTES))]
        length: u64,
        #[schemars(regex(pattern = "^[a-f0-9]{64}$"))]
        sha256: String,
    },
    AttachmentReady {
        upload_id: ConversationId,
        offset: u64,
        #[schemars(range(min = 1, max = ATTACHMENT_CHUNK_MAX_BYTES), extend("format" = "int32"))]
        chunk_size: u32,
    },
    AttachmentChunk { upload_id: ConversationId, offset: u64, data: AttachmentChunkData },
    AttachmentProgress { upload_id: ConversationId, received_bytes: u64 },
    AttachmentFinish { upload_id: ConversationId },
    AttachmentAccepted {
        upload_id: ConversationId,
        #[schemars(length(max = 255))]
        file_name: String,
        #[schemars(length(max = 127))]
        media_type: String,
        #[schemars(range(min = 0, max = ATTACHMENT_MAX_FILE_BYTES))]
        length: u64,
        #[schemars(regex(pattern = "^[a-f0-9]{64}$"))]
        sha256: String,
    },
    Ack {
        configuration: Option<ChatModelConfiguration>,
        state: AckState,
        #[schemars(length(min = 1, max = 128))]
        session_id: Option<String>,
        conversation_id: Option<ConversationId>,
        idle_timeout_seconds: Option<IdleTimeoutSeconds>,
    },
    Status {
        state: RunningState,
        #[schemars(length(max = 2048))]
        activity: String,
        #[schemars(length(min = 1, max = 128))]
        session_id: Option<String>,
        conversation_id: Option<ConversationId>,
    },
    Cancel { target: RequestId },
    Error {
        #[schemars(length(min = 1, max = 64))]
        code: String,
        #[schemars(length(max = 2048))]
        message: String,
        #[schemars(length(min = 1, max = 128))]
        session_id: Option<String>,
        conversation_id: Option<ConversationId>,
    },
    Conversation {
        configuration: Option<ChatModelConfiguration>,
        #[schemars(length(min = 1, max = 128))]
        session_id: String,
        conversation_id: ConversationId,
        state: ConversationState,
        created_at: Timestamp,
        expires_at: Timestamp,
        idle_timeout_seconds: IdleTimeoutSeconds,
        #[schemars(length(max = 512))]
        session_name: Option<String>,
    },
    Ping {},
    Pong {},
}
impl Message {
    /// Parse the inbound client policy exactly once during the authenticated handshake.
    pub fn client_idle_timeout(&self) -> Result<Option<IdleTimeoutSeconds>, ProtocolError> {
        match self {
            Self::Hello {
                capabilities,
                idle_timeout_seconds,
                ..
            } => {
                let phone = capabilities
                    .iter()
                    .any(|c| c == PHONE_CONVERSATIONS_CAPABILITY);
                match (phone, idle_timeout_seconds) {
                    (true, Some(timeout)) => Ok(Some(*timeout)),
                    (true, None) => {
                        Err(ProtocolError::Invalid("phone idle timeout policy required"))
                    }
                    (false, None) => Ok(None),
                    (false, Some(_)) => Err(ProtocolError::Invalid(
                        "idle timeout without phone capability",
                    )),
                }
            }
            _ => Err(ProtocolError::Invalid("client hello required")),
        }
    }
}
#[derive(Clone, Debug, Eq, PartialEq, Hash, Serialize)]
#[serde(transparent)]
pub struct SyncCursor(String);
impl SyncCursor {
    pub fn parse(value: String) -> Result<Self, ProtocolError> {
        let n = value.parse::<u64>().map_err(|_| ProtocolError::Invalid("sync cursor"))?;
        if value.is_empty() || (value.len() > 1 && value.starts_with('0')) || n.to_string() != value {
            return Err(ProtocolError::Invalid("sync cursor"));
        }
        Ok(Self(value))
    }
    pub fn as_str(&self) -> &str { &self.0 }
}
impl<'de> Deserialize<'de> for SyncCursor {
    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        Self::parse(String::deserialize(d)?).map_err(serde::de::Error::custom)
    }
}
impl JsonSchema for SyncCursor {
    fn schema_name() -> std::borrow::Cow<'static, str> { "SyncCursor".into() }
    fn json_schema(_: &mut schemars::SchemaGenerator) -> schemars::Schema {
        schemars::json_schema!({"type":"string","pattern":"^(0|[1-9][0-9]{0,19})$","maxLength":20})
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EffectiveSettings {
    pub idle_timeout_seconds: IdleTimeoutSeconds,
    pub upload_retention_seconds: UploadRetentionSeconds,
}
#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd, Serialize)]
#[serde(transparent)]
pub struct UploadRetentionSeconds(u32);
impl UploadRetentionSeconds { pub const fn get(self) -> u32 { self.0 } }
impl TryFrom<u32> for UploadRetentionSeconds {
    type Error = ProtocolError;
    fn try_from(v: u32) -> Result<Self, Self::Error> {
        if (MIN_UPLOAD_RETENTION_SECONDS..=MAX_UPLOAD_RETENTION_SECONDS).contains(&v) { Ok(Self(v)) }
        else { Err(ProtocolError::Invalid("upload retention seconds")) }
    }
}
impl<'de> Deserialize<'de> for UploadRetentionSeconds {
    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        Self::try_from(u32::deserialize(d)?).map_err(serde::de::Error::custom)
    }
}
impl JsonSchema for UploadRetentionSeconds {
    fn schema_name() -> std::borrow::Cow<'static, str> { "UploadRetentionSeconds".into() }
    fn json_schema(_: &mut schemars::SchemaGenerator) -> schemars::Schema {
        schemars::json_schema!({"type":"integer","format":"int32","minimum":MIN_UPLOAD_RETENTION_SECONDS,"maximum":MAX_UPLOAD_RETENTION_SECONDS})
    }
}
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RequestSummary { pub request_id: RequestId, pub conversation_id: ConversationId, pub outcome: RequestOutcome, pub next_sequence: u32, pub configuration: Option<ChatModelConfiguration> }
#[derive(Clone, Copy, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub enum RequestOutcome { Accepted, Queued, Running, Completed, Failed, Cancelled, Uncertain, Rejected }
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SyncEvent { pub cursor: SyncCursor, pub event: DurableEvent }
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(tag = "type", content = "payload", rename_all = "kebab-case", rename_all_fields = "camelCase", deny_unknown_fields)]
pub enum DurableEvent {
    SessionChanged { session: ConversationMetadata },
    RequestChanged { request: RequestSummary },
    ResponseCompleted { request_id: RequestId, outcome: RequestOutcome, final_sequence: u32 },
    SettingsChanged { settings: EffectiveSettings },
    AttachmentChanged { upload_id: ConversationId, received_bytes: u64, total_bytes: Option<u64>, complete: bool, expired: bool },
}

#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ConversationMetadata {
    pub configuration: Option<ChatModelConfiguration>,
    #[schemars(length(min = 1, max = 128))]
    pub session_id: String,
    pub conversation_id: ConversationId,
    pub state: ConversationState,
    pub created_at: Timestamp,
    pub expires_at: Timestamp,
    pub idle_timeout_seconds: IdleTimeoutSeconds,
    #[schemars(length(max = 512))]
    pub session_name: Option<String>,
}
#[derive(Clone, Copy, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub enum Delivery {
    FollowUp,
}
#[derive(Clone, Copy, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub enum SyncState { Connecting, Synchronizing, Live, Disconnected }
#[derive(Clone, Copy, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "kebab-case")]
pub enum StreamRole { ChatControl, AttachmentTransfer }
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum AndroidToolKind { PhoneAction, ScreenContext, SendNotification }
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "kebab-case")]
pub enum DeviceNotificationReceipt { Posted, NotPosted, Unknown }
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "kebab-case")]
pub enum DeviceNotificationStatus { Queued, PushAccepted, Posted, NotPosted, Unknown, Offline, Unavailable }
#[derive(Clone, Copy, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub enum AckState {
    Accepted,
    Queued,
}
#[derive(Clone, Copy, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub enum RunningState {
    Running,
}
#[derive(Clone, Copy, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub enum ResultState {
    Completed,
    Failed,
    Cancelled,
}
#[derive(Clone, Copy, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub enum ConversationState {
    Active,
    Expired,
    Closed,
    Unavailable,
}
/// Local CLI/extension control socket DTOs; same v3 envelope and CBOR framing.
pub mod control {
    use super::*;
    #[derive(Clone, Copy, Debug, Serialize, Deserialize, JsonSchema)]
    #[serde(rename_all = "camelCase")]
    pub enum AgentState {
        Idle,
        Running,
    }
    #[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
    #[serde(
        tag = "type",
        content = "payload",
        rename_all = "kebab-case",
        rename_all_fields = "camelCase",
        deny_unknown_fields
    )]
    pub enum ControlMessage {
        Status {},
        DevicesList {},
        DeviceRevoke {
            #[schemars(regex(pattern = "^[a-f0-9]{64}$"))]
            endpoint: String,
        },
        DeviceNotification {
            #[schemars(regex(pattern = "^[a-f0-9]{64}$"))]
            endpoint: String,
            request_id: RequestId,
            #[schemars(length(min = 1, max = DEVICE_NOTIFICATION_TITLE_MAX_CHARS))]
            title: String,
            #[schemars(length(min = 1, max = DEVICE_NOTIFICATION_BODY_MAX_CHARS))]
            body: String,
        },
        DeviceRichNotification {
            #[schemars(regex(pattern = "^[a-f0-9]{64}$"))]
            endpoint: String,
            request_id: RequestId,
            notification: AgentNotification,
        },
        DeviceNotificationResult {
            #[schemars(regex(pattern = "^[a-f0-9]{64}$"))]
            endpoint: String,
            status: DeviceNotificationStatus,
            #[schemars(length(max = 64))]
            code: Option<String>,
            #[schemars(length(max = 512))]
            message: Option<String>,
        },
        PushAllowOrigin { #[schemars(length(min = 1, max = 256))] origin: String },
        PushAllowedOriginsList {},
        PushAllowedOriginRevoke { #[schemars(length(min = 1, max = 256))] origin: String },
        PushAllowedOriginsResult { #[schemars(length(max = 128), inner(length(max = 256)))] origins: Vec<String> },
        PairingCreate {
            #[schemars(length(min = 1, max = 128))]
            name: Option<String>,
        },
        Ok {
            #[schemars(length(min = 1, max = 64))]
            operation: String,
        },
        StatusResult {
            running: bool,
            #[schemars(regex(pattern = "^[a-f0-9]{64}$"))]
            endpoint: Option<String>,
            #[schemars(length(max = 4096))]
            control_socket: String,
            #[schemars(length(max = 128))]
            paired_devices: Vec<DeviceSummary>,
        },
        DevicesResult {
            #[schemars(length(max = 128))]
            devices: Vec<DeviceSummary>,
        },
        PairingResult {
            #[schemars(length(max = 8192))]
            qr: String,
            #[schemars(length(max = 8192))]
            token: Option<String>,
            expires_at: Timestamp,
        },
        Error {
            #[schemars(length(min = 1, max = 64))]
            code: String,
            #[schemars(length(max = 2048))]
            message: String,
        },
    }
    #[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
    #[serde(rename_all = "camelCase", deny_unknown_fields)]
    pub struct DeviceSummary {
        #[schemars(regex(pattern = "^[a-f0-9]{64}$"))]
        pub endpoint: String,
        #[schemars(length(min = 1, max = 128))]
        pub name: String,
        #[schemars(extend("format" = "date-time"))]
        pub paired_at: Timestamp,
        #[schemars(extend("format" = "date-time"))]
        pub last_seen: Option<Timestamp>,
    }
    pub type ControlEnvelope = Envelope<ControlMessage>;
}
/// Supervised Pi SDK worker messages. Worker stdio is bounded JSON Lines; network/control are CBOR BE32.
pub mod worker {
    use super::*;
    #[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
    #[serde(rename_all = "camelCase", deny_unknown_fields)]
    pub struct WorkerAttachmentRef {
        pub upload_id: ConversationId,
        #[schemars(length(max = 255))]
        pub file_name: String,
        #[schemars(length(max = 127))]
        pub media_type: String,
        pub length: u64,
        #[schemars(regex(pattern = "^[a-f0-9]{64}$"))]
        pub sha256: String,
        #[schemars(length(max = 4096))]
        pub path: String,
    }
    #[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
    #[serde(
        tag = "type",
        content = "payload",
        rename_all = "kebab-case",
        rename_all_fields = "camelCase",
        deny_unknown_fields
    )]
    pub enum WorkerRequest {
        ModelsGet {},
        Snapshot { conversation_id: ConversationId, #[serde(default, skip_serializing_if = "Option::is_none")] request_id: Option<RequestId> },
        UiResponse { conversation_id: ConversationId, interaction_id: UiInteractionId, answer: PiUiAnswer },
        AndroidToolResponse { conversation_id: ConversationId, request_id: RequestId, success: bool, #[schemars(length(max = ANDROID_TOOL_RESULT_MAX_BYTES))] result_json: Option<String>, #[schemars(length(max = 64))] error_code: Option<String>, #[schemars(length(max = 512))] error_message: Option<String> },
        Create {
            conversation_id: ConversationId,
            #[schemars(length(min = 1, max = 128))]
            phone_label: Option<String>,
            created_at: Timestamp,
            #[schemars(length(min = 1, max = 512))]
            session_name: String,
        },
        Resume {
            conversation_id: ConversationId,
            #[schemars(length(min = 1, max = 128))]
            session_id: Option<String>,
            #[schemars(length(min = 1, max = 4096))]
            session_file: String,
        },
        Prompt {
            request_id: RequestId,
            conversation_id: ConversationId,
            #[schemars(length(max = 16000), extend("x-kompi-transform" = "trim"))]
            text: String,
            delivery: Delivery,
            configuration: ChatModelConfiguration,
            #[schemars(length(max = ATTACHMENT_MAX_FILES_PER_MESSAGE))]
            attachments: Option<Vec<WorkerAttachmentRef>>,
        },
        FollowUpAdd {
            request_id: RequestId,
            conversation_id: ConversationId,
            #[schemars(length(max = 16000), extend("x-kompi-transform" = "trim"))]
            text: String,
            configuration: ChatModelConfiguration,
            #[schemars(length(max = ATTACHMENT_MAX_FILES_PER_MESSAGE))]
            attachments: Option<Vec<WorkerAttachmentRef>>,
        },
        FollowUpRelease { conversation_id: ConversationId },
        Abort {
            request_id: RequestId,
            conversation_id: ConversationId,
        },
        Dispose {
            conversation_id: ConversationId,
        },
    }
    #[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
    #[serde(
        tag = "type",
        content = "payload",
        rename_all = "kebab-case",
        rename_all_fields = "camelCase",
        deny_unknown_fields
    )]
    pub enum WorkerEvent {
        ModelsResult { catalog: ModelCatalog },
        PiFrame { frame: PiFrame },
        ChatTiming { stamp: ChatTimingStamp, request_id: RequestId },
        SnapshotDone { conversation_id: ConversationId },
        UiRequest { conversation_id: ConversationId, interaction_id: UiInteractionId, dialog: PiUiDialog },
        UiResolved { conversation_id: ConversationId, interaction_id: UiInteractionId },
        AndroidToolRequest { conversation_id: ConversationId, request_id: RequestId, tool: AndroidToolKind, timeout_ms: u32, #[schemars(length(max = ANDROID_TOOL_ACTION_MAX_BYTES))] action_json: Option<String> },
        AndroidToolCancel { conversation_id: ConversationId, request_id: RequestId },
        ArtifactOffer {
            conversation_id: ConversationId,
            request_id: RequestId,
            #[schemars(length(min = 1, max = 4096))]
            path: String,
            #[schemars(length(min = 1, max = 120))]
            file_name: Option<String>,
        },
        Created {
            conversation_id: ConversationId,
            #[schemars(length(min = 1, max = 128))]
            session_id: String,
            #[schemars(length(min = 1, max = 4096))]
            session_file: String,
            #[schemars(length(min = 1, max = 512))]
            session_name: String,
        },
        Ready {
            conversation_id: ConversationId,
            #[schemars(length(min = 1, max = 128))]
            session_id: String,
            #[schemars(length(min = 1, max = 4096))]
            session_file: String,
            #[schemars(length(min = 1, max = 512))]
            session_name: String,
        },
        Aborted {
            request_id: RequestId,
            conversation_id: ConversationId,
        },
        Disposed {
            conversation_id: ConversationId,
        },
        FollowUpReady { stats: GenerationStats },
        FollowUpAdded { queued: bool },
        FollowUpReleased {},
        Final {
            request_id: RequestId,
            conversation_id: ConversationId,
            stats: GenerationStats,
        },
        Error {
            request_id: Option<RequestId>,
            conversation_id: Option<ConversationId>,
            #[schemars(length(min = 1, max = 64))]
            code: String,
            #[schemars(length(max = 2048))]
            message: String,
        },
    }
    pub type WorkerRequestEnvelope = Envelope<WorkerRequest>;
    pub type WorkerEventEnvelope = Envelope<WorkerEvent>;
}
/// Independent pairing credential v1; deliberately not changed with wire major.
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct PairingPayload {
    #[schemars(range(min = PAIRING_VERSION, max = PAIRING_VERSION))]
    pub v: u8,
    #[serde(rename = "endpointTicket")]
    #[schemars(length(min = 1, max = 2048))]
    pub endpoint_ticket: String,
    #[schemars(length(min = 43, max = 256), regex(pattern = "^[A-Za-z0-9_-]+$"))]
    pub secret: String,
    #[schemars(length(min = 1, max = 128))]
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ProtocolError {
    Invalid(&'static str),
    Codec(String),
    Version(u8),
    TooLarge,
}
impl fmt::Display for ProtocolError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Invalid(s) => write!(f, "invalid {s}"),
            Self::Codec(s) => write!(f, "invalid protocol payload: {s}"),
            Self::Version(v) => write!(f, "unsupported protocol version {v}"),
            Self::TooLarge => write!(f, "protocol payload exceeds {} bytes", MAX_PAYLOAD),
        }
    }
}
impl std::error::Error for ProtocolError {}
pub trait ValidateProtocol {
    fn normalize(&mut self) {}
    fn validate(&self) -> Result<(), ProtocolError>;
}
pub fn encode_cbor<T: Serialize + ValidateProtocol>(
    envelope: &Envelope<T>,
) -> Result<Vec<u8>, ProtocolError> {
    if envelope.v != VERSION {
        return Err(ProtocolError::Version(envelope.v));
    }
    envelope.message.validate()?;
    let mut bytes = Vec::new();
    ciborium::ser::into_writer(envelope, &mut bytes)
        .map_err(|e| ProtocolError::Codec(e.to_string()))?;
    if bytes.is_empty() || bytes.len() > MAX_PAYLOAD {
        return Err(ProtocolError::TooLarge);
    }
    Ok(bytes)
}
pub fn decode_cbor<T: for<'de> Deserialize<'de> + ValidateProtocol>(
    bytes: &[u8],
) -> Result<Envelope<T>, ProtocolError> {
    if bytes.is_empty() || bytes.len() > MAX_PAYLOAD {
        return Err(ProtocolError::TooLarge);
    }
    cbor_guard::CborGuard::new(bytes).verify()?;
    let mut e: Envelope<T> =
        ciborium::de::from_reader(bytes).map_err(|e| ProtocolError::Codec(e.to_string()))?;
    if e.v != VERSION {
        return Err(ProtocolError::Version(e.v));
    }
    e.message.normalize();
    e.message.validate()?;
    Ok(e)
}
pub fn encode_worker_json_line<T: Serialize + ValidateProtocol>(
    envelope: &Envelope<T>,
) -> Result<Vec<u8>, ProtocolError> {
    if envelope.v != VERSION {
        return Err(ProtocolError::Version(envelope.v));
    }
    envelope.message.validate()?;
    let mut bytes =
        serde_json::to_vec(envelope).map_err(|e| ProtocolError::Codec(e.to_string()))?;
    if bytes.is_empty() || bytes.len() + 1 > MAX_PAYLOAD {
        return Err(ProtocolError::TooLarge);
    }
    bytes.push(b'\n');
    Ok(bytes)
}
pub fn decode_worker_json_line<T: for<'de> Deserialize<'de> + ValidateProtocol>(
    line: &[u8],
) -> Result<Envelope<T>, ProtocolError> {
    if line.is_empty() || line.len() > MAX_PAYLOAD || line.last() != Some(&b'\n') {
        return Err(ProtocolError::TooLarge);
    }
    let mut envelope: Envelope<T> = serde_json::from_slice(&line[..line.len() - 1])
        .map_err(|e| ProtocolError::Codec(e.to_string()))?;
    if envelope.v != VERSION {
        return Err(ProtocolError::Version(envelope.v));
    }
    envelope.message.normalize();
    envelope.message.validate()?;
    Ok(envelope)
}
mod cbor_guard;
mod validation;
#[cfg(test)]
mod push_tests;
pub fn validate_message(message: &Message) -> Result<(), ProtocolError> {
    message.validate()
}

pub fn frame(payload: &[u8]) -> Result<Vec<u8>, ProtocolError> {
    if payload.is_empty() || payload.len() > MAX_PAYLOAD {
        return Err(ProtocolError::TooLarge);
    }
    let mut out = Vec::with_capacity(payload.len() + 4);
    out.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    out.extend_from_slice(payload);
    Ok(out)
}
