//! Additive public notification surface. No Intent, executable action or remote input authority.
use crate::*;

pub const RICH_DEVICE_NOTIFICATIONS_CAPABILITY: &str = "rich-device-notifications";
pub const REPLY_NOTIFICATIONS_CAPABILITY: &str = "reply-notifications";
pub const REPLY_NOTIFICATION_TTL_MS: i64 = 5 * 60 * 1000;

/// Host-generated after a successful phone turn, never available to notification tools.
#[derive(Clone, Copy, Debug, Deserialize, Serialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReplyNotification {
    pub conversation_id: crate::ConversationId,
    pub completed_at_ms: i64,
}
pub const NOTIFICATION_INPUT_MAX_BYTES: usize = 32_768;
pub const NOTIFICATION_IMAGE_MAX_BYTES: usize = 12_288;
pub const NOTIFICATION_IMAGE_MAX_PIXELS: usize = 1_048_576;
pub const NOTIFICATION_IMAGE_MAX_DIMENSION: usize = 2_048;
pub const NOTIFICATION_URL_MAX_CHARS: usize = 2_048;
pub const NOTIFICATION_IMAGE_MAX_BASE64_CHARS: usize = NOTIFICATION_IMAGE_MAX_BYTES * 4 / 3;

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AgentNotification {
    #[schemars(length(min = 1, max = DEVICE_NOTIFICATION_TITLE_MAX_CHARS))]
    pub title: String,
    #[schemars(length(min = 1, max = DEVICE_NOTIFICATION_BODY_MAX_CHARS))]
    pub body: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub rich: Option<RichNotificationOptions>,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RichNotificationOptions {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub style: Option<NotificationStyle>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    #[schemars(length(max = 3))]
    pub buttons: Option<Vec<NotificationButton>>,
    /// A new request ID can replace this host's earlier tag. Reusing an ID is never an update.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    #[schemars(length(min = 1, max = 64), regex(pattern = "^[A-Za-z0-9._:-]+$"))]
    pub tag: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    #[schemars(length(min = 1, max = 64), regex(pattern = "^[A-Za-z0-9._:-]+$"))]
    pub group: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    #[schemars(length(min = 1, max = 120))]
    pub subtext: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub silent: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    #[schemars(range(min = 1000, max = 86400000))]
    pub timeout_ms: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub progress: Option<NotificationProgress>,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(tag = "type", content = "payload", rename_all = "kebab-case", rename_all_fields = "camelCase", deny_unknown_fields)]
pub enum NotificationStyle {
    BigText { #[schemars(length(min = 1, max = 8000))] text: String },
    Inbox { #[schemars(length(min = 1, max = 8), inner(length(min = 1, max = 500)))] lines: Vec<String> },
    BigPicture { image: NotificationImage },
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct NotificationImage {
    /// Canonical unpadded base64url, never a URL or a local path. PNG/JPEG only.
    #[schemars(length(min = 1, max = NOTIFICATION_IMAGE_MAX_BASE64_CHARS), regex(pattern = "^[A-Za-z0-9_-]+$"))]
    pub base64: String,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct NotificationButton {
    #[schemars(length(min = 1, max = 40))]
    pub label: String,
    pub target: NotificationTarget,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(tag = "type", content = "payload", rename_all = "kebab-case", rename_all_fields = "camelCase", deny_unknown_fields)]
pub enum NotificationTarget {
    OpenKompi {},
    Https {
        #[schemars(length(min = 9, max = NOTIFICATION_URL_MAX_CHARS), regex(pattern = "^https://[^/@\\s\\x00-\\x20\\x7f]+(?:[/?#][^\\s\\x00-\\x20\\x7f]*)?$"))]
        url: String,
    },
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(tag = "type", content = "payload", rename_all = "kebab-case", rename_all_fields = "camelCase", deny_unknown_fields)]
pub enum NotificationProgress {
    Indeterminate {},
    Determinate { #[schemars(range(min = 0, max = 100))] percent: u32 },
}

/// Version two is sent only to peers that negotiated the rich capability for this registration.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RichPushNotificationPayload {
    #[schemars(range(min = 2, max = 2))]
    pub version: u8,
    #[schemars(regex(pattern = "^[a-f0-9]{64}$"))]
    pub host_endpoint: String,
    pub registration_id: PushRegistrationId,
    pub notification_id: RequestId,
    pub issued_at_ms: i64,
    pub expires_at_ms: i64,
    pub notification: AgentNotification,
}

/// Version three contains no user or assistant content.
#[derive(Clone, Debug, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReplyPushNotificationPayload {
    #[schemars(range(min = 3, max = 3))]
    pub version: u8,
    #[schemars(regex(pattern = "^[a-f0-9]{64}$"))]
    pub host_endpoint: String,
    pub registration_id: PushRegistrationId,
    pub notification_id: RequestId,
    pub issued_at_ms: i64,
    pub expires_at_ms: i64,
    pub conversation_id: crate::ConversationId,
}

impl AgentNotification {
    pub fn plain(title: String, body: String) -> Self { Self { title, body, rich: None } }
    pub fn has_image(&self) -> bool {
        matches!(self.rich.as_ref().and_then(|r| r.style.as_ref()), Some(NotificationStyle::BigPicture { .. }))
    }
    pub fn validate(&self) -> Result<(), ProtocolError> {
        use base64::Engine;
        use std::sync::OnceLock;
        static SCHEMA: OnceLock<jsonschema::Validator> = OnceLock::new();
        let schema = SCHEMA.get_or_init(|| jsonschema::validator_for(&serde_json::to_value(schemars::schema_for!(AgentNotification)).expect("notification schema")).expect("valid notification schema"));
        if serde_json::to_vec(self).map_err(|_| ProtocolError::Invalid("notification"))?.len() > NOTIFICATION_INPUT_MAX_BYTES {
            return Err(ProtocolError::Invalid("notification input budget"));
        }
        let value = serde_json::to_value(self).map_err(|_| ProtocolError::Invalid("notification"))?;
        schema.validate(&value).map_err(|_| ProtocolError::Invalid("notification"))?;
        if self.title.trim().is_empty() || self.body.trim().is_empty() { return Err(ProtocolError::Invalid("blank notification")); }
        if let Some(NotificationStyle::BigPicture { image }) = self.rich.as_ref().and_then(|r| r.style.as_ref()) {
            let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(&image.base64).map_err(|_| ProtocolError::Invalid("notification image"))?;
            if bytes.is_empty() || bytes.len() > NOTIFICATION_IMAGE_MAX_BYTES || base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(&bytes) != image.base64 {
                return Err(ProtocolError::Invalid("notification image"));
            }
        }
        Ok(())
    }
}
