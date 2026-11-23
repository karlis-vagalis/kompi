//! Isolated test fixture: encrypt one UnifiedPush notification for the Android cold-receiver harness.
//! Reads a single bounded JSON object from stdin and writes opaque ciphertext to stdout.
use anyhow::{Context, Result};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use kompi_protocol::{PushRegistrationId, RequestId};
use serde::Deserialize;
use std::io::{Read, Write};

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Fixture {
    host_endpoint: String,
    registration_id: PushRegistrationId,
    p256dh: String,
    auth: String,
    integrity_key: String,
    notification_id: RequestId,
    issued_at_ms: i64,
    expires_at_ms: i64,
    title: String,
    body: String,
}

fn main() -> Result<()> {
    const MAX_INPUT_BYTES: usize = 16 * 1024;
    let mut input = Vec::with_capacity(4096);
    std::io::stdin().take((MAX_INPUT_BYTES + 1) as u64).read_to_end(&mut input)
        .context("read bounded push fixture JSON from stdin")?;
    anyhow::ensure!(input.len() <= MAX_INPUT_BYTES, "fixture input exceeds size limit");
    let fixture: Fixture = serde_json::from_slice(&input).context("parse strict push fixture JSON")?;
    let integrity_key = URL_SAFE_NO_PAD.decode(&fixture.integrity_key).context("decode fixture integrity key")?;
    anyhow::ensure!(integrity_key.len() == 32 && URL_SAFE_NO_PAD.encode(&integrity_key) == fixture.integrity_key, "invalid fixture integrity key");
    // The example is test-only. Encryption does not make a network request; the actual
    // dispatcher separately uses the saved provider endpoint after policy validation.
    let ciphertext = kompi_daemon::push_crypto::encrypt_notification_payload(
        &fixture.host_endpoint,
        "https://fixture.invalid/",
        fixture.registration_id,
        &fixture.notification_id,
        fixture.issued_at_ms,
        fixture.expires_at_ms,
        &fixture.title,
        &fixture.body,
        &fixture.p256dh,
        &fixture.auth,
        &integrity_key,
    )?;
    std::io::stdout().write_all(&ciphertext).context("write opaque ciphertext")?;
    Ok(())
}
