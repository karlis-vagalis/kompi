//! Provider-agnostic UnifiedPush Web Push payload construction.
//!
//! This pure API is shared by the daemon dispatcher and the isolated cold-receiver
//! acceptance fixture. It never performs network I/O and never stores plaintext.

use anyhow::{Context, Result};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use hmac::{KeyInit, Mac};
use kompi_protocol::{PushAuthenticatedPayload, PushNotificationPayload, PushRegistrationId, RequestId};
use sha2::Sha256;

/// Serialize, authenticate, and RFC8291/AES128GCM-encrypt one notification payload.
///
/// `integrity_key` must be the 32-byte per-registration HMAC key exchanged over the
/// authenticated Kompi connection. `p256dh` and `auth` are the canonical unpadded
/// base64url Web Push client keys supplied by the selected UnifiedPush distributor.
/// The returned bytes are the opaque Web Push ciphertext; callers must not log them.
pub fn encrypt_notification_payload(
    host_endpoint: &str,
    endpoint: &str,
    registration_id: PushRegistrationId,
    notification_id: &RequestId,
    issued_at_ms: i64,
    expires_at_ms: i64,
    title: &str,
    body: &str,
    p256dh: &str,
    auth: &str,
    integrity_key: &[u8],
) -> Result<Vec<u8>> {
    anyhow::ensure!(host_endpoint.len() == 64 && host_endpoint.bytes().all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase()), "invalid push host endpoint");
    let parsed_endpoint=url::Url::parse(endpoint).context("parse push endpoint")?;
    anyhow::ensure!(parsed_endpoint.scheme()=="https" && parsed_endpoint.host().is_some() && parsed_endpoint.username().is_empty() && parsed_endpoint.password().is_none() && parsed_endpoint.fragment().is_none(), "invalid push endpoint");
    anyhow::ensure!(integrity_key.len() == 32, "push integrity key must be 32 bytes");
    anyhow::ensure!(issued_at_ms >= 0 && expires_at_ms > issued_at_ms, "invalid notification time window");
    anyhow::ensure!(expires_at_ms - issued_at_ms <= kompi_protocol::PUSH_MAX_TTL_MS as i64, "notification TTL exceeds the push limit");
    anyhow::ensure!(!title.trim().is_empty() && title.chars().count() <= kompi_protocol::DEVICE_NOTIFICATION_TITLE_MAX_CHARS, "notification title is invalid");
    anyhow::ensure!(!body.trim().is_empty() && body.chars().count() <= kompi_protocol::DEVICE_NOTIFICATION_BODY_MAX_CHARS, "notification body is invalid");

    let inner = PushNotificationPayload {
        version: 1,
        host_endpoint: host_endpoint.to_owned(),
        registration_id,
        notification_id: notification_id.clone(),
        issued_at_ms,
        expires_at_ms,
        title: title.to_owned(),
        body: body.to_owned(),
    };
    let inner_bytes = serde_json::to_vec(&inner).context("serialize notification payload")?;
    encrypt_authenticated_bytes(endpoint, &inner_bytes, p256dh, auth, integrity_key)
}

pub fn encrypt_rich_notification_payload(
    host_endpoint: &str, endpoint: &str, registration_id: PushRegistrationId,
    notification_id: &RequestId, issued_at_ms: i64, expires_at_ms: i64,
    notification: &kompi_protocol::AgentNotification, p256dh: &str, auth: &str, integrity_key: &[u8],
) -> Result<Vec<u8>> {
    notification.validate()?;
    anyhow::ensure!(!notification.has_image(), "notification images require a connected phone");
    anyhow::ensure!(integrity_key.len() == 32 && host_endpoint.len() == 64 && host_endpoint.bytes().all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase()), "invalid push binding");
    anyhow::ensure!(issued_at_ms >= 0 && expires_at_ms > issued_at_ms && expires_at_ms - issued_at_ms <= kompi_protocol::PUSH_MAX_TTL_MS, "invalid notification time window");
    let inner = kompi_protocol::RichPushNotificationPayload {
        version: 2, host_endpoint: host_endpoint.to_owned(), registration_id,
        notification_id: notification_id.clone(), issued_at_ms, expires_at_ms, notification: notification.clone(),
    };
    let inner_bytes = serde_json::to_vec(&inner).context("serialize rich notification payload")?;
    encrypt_authenticated_bytes(endpoint, &inner_bytes, p256dh, auth, integrity_key)
}

pub fn encrypt_reply_notification_payload(
    host_endpoint: &str, endpoint: &str, registration_id: PushRegistrationId,
    notification_id: &RequestId, reply: kompi_protocol::ReplyNotification,
    expires_at_ms: i64, p256dh: &str, auth: &str, integrity_key: &[u8],
) -> Result<Vec<u8>> {
    let inner = kompi_protocol::ReplyPushNotificationPayload {
        version: 3, host_endpoint: host_endpoint.to_owned(), registration_id,
        notification_id: notification_id.clone(), issued_at_ms: reply.completed_at_ms,
        expires_at_ms, conversation_id: reply.conversation_id,
    };
    encrypt_authenticated_bytes(endpoint, &serde_json::to_vec(&inner)?, p256dh, auth, integrity_key)
}

fn encrypt_authenticated_bytes(endpoint: &str, inner_bytes: &[u8], p256dh: &str, auth: &str, integrity_key: &[u8]) -> Result<Vec<u8>> {
    let parsed=url::Url::parse(endpoint).context("parse push endpoint")?;
    anyhow::ensure!(parsed.scheme()=="https" && parsed.host().is_some() && parsed.username().is_empty() && parsed.password().is_none() && parsed.fragment().is_none(), "invalid push endpoint");
    anyhow::ensure!(integrity_key.len()==32, "invalid push integrity key");
    anyhow::ensure!(inner_bytes.len() <= kompi_protocol::PUSH_MAX_CIPHERTEXT_BYTES, "notification payload exceeds the push size limit");

    let mut mac = hmac::Hmac::<Sha256>::new_from_slice(integrity_key).context("initialize push payload authenticator")?;
    mac.update(b"kompi-push-v1\0");
    mac.update(&inner_bytes);
    let wrapper = PushAuthenticatedPayload {
        version: 1,
        payload: URL_SAFE_NO_PAD.encode(&inner_bytes),
        mac: URL_SAFE_NO_PAD.encode(mac.finalize().into_bytes()),
    };
    let wrapper_bytes = serde_json::to_vec(&wrapper).context("serialize authenticated push payload")?;
    anyhow::ensure!(wrapper_bytes.len() <= kompi_protocol::PUSH_MAX_CIPHERTEXT_BYTES, "authenticated notification exceeds the push size limit");

    let subscription = web_push::SubscriptionInfo::new(endpoint.to_owned(), p256dh.to_owned(), auth.to_owned());
    let mut builder = web_push::WebPushMessageBuilder::new(&subscription);
    builder.set_payload(web_push::ContentEncoding::Aes128Gcm, &wrapper_bytes);
    builder.set_ttl((kompi_protocol::PUSH_MAX_TTL_MS / 1000) as u32);
    let message = builder.build().context("encrypt UnifiedPush payload")?;
    let ciphertext = message.payload.context("encrypted Web Push payload is missing")?.content;
    anyhow::ensure!(!ciphertext.is_empty() && ciphertext.len() <= kompi_protocol::PUSH_MAX_CIPHERTEXT_BYTES, "encrypted notification exceeds the push size limit");
    Ok(ciphertext)
}
