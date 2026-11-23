use std::{path::{Path, PathBuf}, time::Duration};

use anyhow::{Context, Result};
use sqlx::{sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePoolOptions}, Row, SqlitePool};
use tokio::sync::watch;
use uuid::Uuid;
use kompi_protocol::{ConversationId, ConversationMetadata, ConversationState as WireConversationState, DurableEvent, EffectiveSettings, IdleTimeoutSeconds, RequestId, RequestOutcome as WireRequestOutcome, RequestSummary, SyncCursor, SyncEvent, Timestamp, UploadRetentionSeconds, DEFAULT_IDLE_TIMEOUT_SECONDS, DEFAULT_UPLOAD_RETENTION_SECONDS, MAX_SYNC_PAGE};
use crate::store::{ConversationRecord, ConversationState, Device};

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct PeerKey(String);
impl TryFrom<String> for PeerKey {
    type Error = anyhow::Error;
    fn try_from(value: String) -> Result<Self> {
        if value.is_empty() || value.len() > 256 { anyhow::bail!("peer key length out of range"); }
        Ok(Self(value))
    }
}
impl PeerKey { pub fn as_str(&self) -> &str { &self.0 } }

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct RequestKey(String);
impl TryFrom<String> for RequestKey {
    type Error = anyhow::Error;
    fn try_from(value: String) -> Result<Self> {
        if value.is_empty() || value.len() > 128 { anyhow::bail!("request ID length out of range"); }
        Ok(Self(value))
    }
}
impl RequestKey { pub fn as_str(&self) -> &str { &self.0 } }

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct SessionKey(String);
impl TryFrom<String> for SessionKey {
    type Error = anyhow::Error;
    fn try_from(value: String) -> Result<Self> {
        Uuid::parse_str(&value).context("session ID must be a UUID")?;
        Ok(Self(value))
    }
}
impl SessionKey { pub fn as_str(&self) -> &str { &self.0 } }

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum RequestOutcome { Queued, Running, Completed, Failed, Cancelled, Uncertain, Rejected }
impl RequestOutcome {
    fn as_str(&self) -> &'static str { match self { Self::Queued=>"queued",Self::Running=>"running",Self::Completed=>"completed",Self::Failed=>"failed",Self::Cancelled=>"cancelled",Self::Uncertain=>"uncertain",Self::Rejected=>"rejected" } }
}

#[derive(Clone, Debug)]
pub struct RequestClaim {
    pub peer: PeerKey,
    pub request: RequestKey,
    pub session: SessionKey,
    pub session_name: String,
    pub fingerprint: Vec<u8>,
    pub configuration: kompi_protocol::ChatModelConfiguration,
    pub accepted_at_ms: i64,
    pub idle_ttl_seconds: i64,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ClaimStatus { New, Existing, Conflict, AttachmentsUnavailable }
#[derive(Clone, Debug)]
pub struct ClaimedRequest { pub status: ClaimStatus, pub outcome: String, pub session: SessionKey }
#[derive(Clone, Debug)]
pub struct JournalEvent { pub cursor: i64, pub kind: String, pub payload: Vec<u8> }
#[derive(Clone, Debug)]
pub struct PushRegistrationRecord {
    pub peer: String,
    pub registration_id: String,
    pub origin: String,
    pub p256dh: String,
    pub encrypted_secrets: Vec<u8>,
}

#[derive(Clone, Debug)]
pub struct SyncSnapshotData {
    pub cursor: SyncCursor,
    pub settings: EffectiveSettings,
    pub sessions: Vec<ConversationMetadata>,
    pub requests: Vec<RequestSummary>,
    pub has_more_sessions: bool,
    pub has_more_requests: bool,
}

/// App-owned delivery/lifecycle store. Claims, outcomes and peer-scoped metadata
/// notifications are written transactionally; no conversation content or model operation
/// is performed while a database transaction is open.
#[derive(Clone)]
pub struct SqliteStore { pool: SqlitePool, path: PathBuf, revision: watch::Sender<u64> }

pub(crate) struct HistoryDelete {
    pub pi_id: Uuid,
    pub path: PathBuf,
    pub identity: crate::pi_history::FileIdentity,
    pub finished: bool,
}

impl SqliteStore {
    pub fn state_directory(&self) -> &Path {
        self.path.parent().expect("SQLite database path has a parent")
    }

    pub async fn open(state_directory: impl AsRef<Path>) -> Result<Self> {
        let state_directory = state_directory.as_ref();
        tokio::fs::create_dir_all(state_directory).await
            .with_context(|| format!("create SQLite state directory {}", state_directory.display()))?;
        #[cfg(unix)] {
            use std::os::unix::fs::PermissionsExt;
            tokio::fs::set_permissions(state_directory, std::fs::Permissions::from_mode(0o700)).await?;
        }
        let path = state_directory.join("kompi.sqlite3");
        let options = SqliteConnectOptions::new().filename(&path).create_if_missing(true)
            .journal_mode(SqliteJournalMode::Wal).foreign_keys(true).busy_timeout(Duration::from_secs(5));
        let pool = SqlitePoolOptions::new().max_connections(8).connect_with(options).await
            .with_context(|| format!("open SQLite store {}", path.display()))?;
        #[cfg(unix)] {
            use std::os::unix::fs::PermissionsExt;
            tokio::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600)).await?;
        }
        sqlx::query("PRAGMA synchronous = FULL").execute(&pool).await?;
        let (revision, _) = watch::channel(0);
        let store = Self { pool, path, revision };
        store.migrate().await?;
        Ok(store)
    }

    pub fn path(&self) -> &Path { &self.path }

    /// Coalescing wake-up only; SQLite's per-peer cursor remains authoritative.
    /// Register before taking a snapshot to close the snapshot/subscription race.
    pub fn subscribe_events(&self) -> watch::Receiver<u64> { self.revision.subscribe() }

    fn notify_committed(&self) { self.revision.send_modify(|revision| *revision = revision.wrapping_add(1)); }

    pub async fn notification_receipt(&self, peer:&str, request_id:&str, fingerprint:&[u8], now_ms:i64) -> Result<Option<(String,Option<String>)>> {
        self.expire_pushes(now_ms).await?;
        let row=sqlx::query("SELECT status,code,fingerprint,expires_at_ms FROM push_outbox_history WHERE peer=? AND request_id=?")
            .bind(peer).bind(request_id).fetch_optional(&self.pool).await?;
        let Some(row)=row else { return Ok(None); };
        let status:String=row.try_get("status")?;
        let code:Option<String>=row.try_get("code")?;
        let prior:Vec<u8>=row.try_get("fingerprint")?;
        let expires_at_ms:i64=row.try_get("expires_at_ms")?;
        if expires_at_ms<=now_ms { return Ok(None); }
        if prior!=fingerprint { return Ok(Some(("not-posted".into(),Some("request-id-conflict".into())))); }
        Ok(Some((status,code)))
    }

    pub async fn reserve_direct_notification(&self, peer:&str, request_id:&str, fingerprint:&[u8], now_ms:i64) -> Result<(bool,String,Option<String>)> {
        let mut tx=self.pool.begin().await?;
        sqlx::query("DELETE FROM push_outbox_history WHERE expires_at_ms<=?").bind(now_ms).execute(&mut *tx).await?;
        if let Some(row)=sqlx::query("SELECT status,code,fingerprint FROM push_outbox_history WHERE peer=? AND request_id=?").bind(peer).bind(request_id).fetch_optional(&mut *tx).await? {
            let status:String=row.try_get("status")?;let code:Option<String>=row.try_get("code")?;let prior:Vec<u8>=row.try_get("fingerprint")?;
            tx.commit().await?;
            if prior!=fingerprint { return Ok((false,"not-posted".into(),Some("request-id-conflict".into()))); }
            return Ok((false,status,code));
        }
        let receipt_count:i64=sqlx::query_scalar("SELECT COUNT(*) FROM push_outbox_history WHERE peer=?").bind(peer).fetch_one(&mut *tx).await?;
        anyhow::ensure!(receipt_count<4096,"notification receipt ledger is full");
        let expires=now_ms.saturating_add(kompi_protocol::PUSH_MAX_TTL_MS as i64*2);
        sqlx::query("INSERT INTO push_outbox_history(peer,request_id,status,code,fingerprint,expires_at_ms,updated_at_ms) VALUES(?,?,'direct-dispatching',NULL,?,?,?)")
            .bind(peer).bind(request_id).bind(fingerprint).bind(expires).bind(now_ms).execute(&mut *tx).await?;
        tx.commit().await?;
        Ok((true,"direct-dispatching".into(),None))
    }

    pub async fn complete_direct_notification(&self, peer:&str, request_id:&str, status:&str, code:Option<&str>) -> Result<()> {
        sqlx::query("UPDATE push_outbox_history SET status=?,code=?,updated_at_ms=? WHERE peer=? AND request_id=? AND status='direct-dispatching'")
            .bind(status).bind(code).bind(crate::store::now_ms()).bind(peer).bind(request_id).execute(&self.pool).await?;
        Ok(())
    }

    pub async fn recover_notification_receipts(&self, now_ms:i64) -> Result<()> {
        sqlx::query("UPDATE push_outbox_history SET status='unknown',code='daemon-restarted-after-dispatch',updated_at_ms=? WHERE status='direct-dispatching' AND expires_at_ms>?")
            .bind(now_ms).bind(now_ms).execute(&self.pool).await?;
        Ok(())
    }

    pub async fn enqueue_push(&self, peer: &str, request_id: &str, registration_id: &str, ciphertext: &[u8], fingerprint: &[u8], expires_at_ms: i64, now_ms: i64) -> Result<(String, Option<String>, bool)> {
        // The authorization read and enqueue must share one write transaction. A
        // deferred transaction can fail immediately upgrading a stale WAL snapshot.
        let mut tx=self.pool.begin_with("BEGIN IMMEDIATE").await?;
        let authorized:bool=sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM paired_devices p JOIN push_registrations r ON r.peer=p.peer WHERE p.peer=? AND r.registration_id=?)")
            .bind(peer).bind(registration_id).fetch_one(&mut *tx).await?;
        if !authorized { tx.rollback().await?; return Ok(("unavailable".into(),Some("no-background-route".into()),false)); }
        sqlx::query("UPDATE push_outbox_history SET status='not-posted',code='expired',updated_at_ms=? WHERE (peer,request_id) IN (SELECT peer,request_id FROM push_outbox WHERE expires_at_ms<=? AND status IN ('queued','retry'))")
            .bind(now_ms).bind(now_ms).execute(&mut *tx).await?;
        sqlx::query("UPDATE push_outbox SET status='not-posted',last_code='expired',ciphertext=X'' WHERE expires_at_ms<=? AND status IN ('queued','retry')")
            .bind(now_ms).execute(&mut *tx).await?;
        sqlx::query("DELETE FROM push_outbox WHERE expires_at_ms<=?").bind(now_ms).execute(&mut *tx).await?;
        sqlx::query("DELETE FROM push_outbox_history WHERE expires_at_ms<=?").bind(now_ms).execute(&mut *tx).await?;
        let exists=sqlx::query("SELECT status,code AS last_code,fingerprint FROM push_outbox_history WHERE peer=? AND request_id=?").bind(peer).bind(request_id).fetch_optional(&mut *tx).await?;
        if let Some(row)=exists {
            let status:String=row.try_get("status")?;let code:Option<String>=row.try_get("last_code")?;let prior:Vec<u8>=row.try_get("fingerprint")?;
            tx.commit().await?;
            if prior!=fingerprint { return Ok(("not-posted".into(),Some("request-id-conflict".into()),false)); }
            return Ok((status,code,true));
        }
        let total:i64=sqlx::query_scalar("SELECT COUNT(*) FROM push_outbox WHERE peer=?").bind(peer).fetch_one(&mut *tx).await?;
        let history_total:i64=sqlx::query_scalar("SELECT COUNT(*) FROM push_outbox_history WHERE peer=?").bind(peer).fetch_one(&mut *tx).await?;
        let count:i64=sqlx::query_scalar("SELECT COUNT(*) FROM push_outbox WHERE peer=? AND status IN ('queued','retry')").bind(peer).fetch_one(&mut *tx).await?;
        anyhow::ensure!(total < 1024 && history_total < 4096, "push outbox history is full");
        anyhow::ensure!(count < 128, "push outbox is full");
        sqlx::query("INSERT INTO push_outbox(peer,request_id,registration_id,ciphertext,fingerprint,expires_at_ms,status,attempts,next_attempt_at_ms,created_at_ms) VALUES(?,?,?,?,?,?,'queued',0,?,?)")
            .bind(peer).bind(request_id).bind(registration_id).bind(ciphertext).bind(fingerprint).bind(expires_at_ms).bind(now_ms).bind(now_ms).execute(&mut *tx).await?;
        sqlx::query("INSERT INTO push_outbox_history(peer,request_id,status,code,fingerprint,expires_at_ms,updated_at_ms) VALUES(?,?,'queued',NULL,?,?,?)")
            .bind(peer).bind(request_id).bind(fingerprint).bind(expires_at_ms.saturating_add(kompi_protocol::PUSH_MAX_TTL_MS as i64)).bind(now_ms).execute(&mut *tx).await?;
        tx.commit().await?;
        Ok(("queued".into(),None,false))
    }

    pub async fn expire_pushes(&self, now_ms:i64) -> Result<()> {
        let mut tx=self.pool.begin().await?;
        sqlx::query("UPDATE push_outbox_history SET status='not-posted',code='expired',updated_at_ms=? WHERE (peer,request_id) IN (SELECT peer,request_id FROM push_outbox WHERE expires_at_ms<=? AND status IN ('queued','retry'))")
            .bind(now_ms).bind(now_ms).execute(&mut *tx).await?;
        sqlx::query("UPDATE push_outbox SET status='not-posted',last_code='expired',ciphertext=X'' WHERE expires_at_ms<=? AND status IN ('queued','retry')")
            .bind(now_ms).execute(&mut *tx).await?;
        tx.commit().await?;
        Ok(())
    }

    pub async fn due_pushes(&self, now_ms: i64, limit: i64) -> Result<Vec<(String,String,String,Vec<u8>,i64,i64)>> {
        Ok(sqlx::query_as("SELECT peer,request_id,registration_id,ciphertext,expires_at_ms,attempts FROM push_outbox WHERE status IN ('queued','retry') AND next_attempt_at_ms<=? AND expires_at_ms>? ORDER BY created_at_ms LIMIT ?")
            .bind(now_ms).bind(now_ms).bind(limit).fetch_all(&self.pool).await?)
    }

    pub async fn update_push(&self, peer:&str, request_id:&str, status:&str, code:Option<&str>, attempts:i64, next_attempt_at_ms:i64, clear_ciphertext:bool) -> Result<()> {
        let mut tx=self.pool.begin().await?;
        let changed=sqlx::query("UPDATE push_outbox SET status=?,last_code=?,attempts=?,next_attempt_at_ms=?,ciphertext=CASE WHEN ? THEN X'' ELSE ciphertext END WHERE peer=? AND request_id=?")
            .bind(status).bind(code).bind(attempts).bind(next_attempt_at_ms).bind(clear_ciphertext).bind(peer).bind(request_id).execute(&mut *tx).await?.rows_affected();
        if changed>0 {
            sqlx::query("UPDATE push_outbox_history SET status=?,code=?,updated_at_ms=? WHERE peer=? AND request_id=?")
                .bind(status).bind(code).bind(crate::store::now_ms()).bind(peer).bind(request_id).execute(&mut *tx).await?;
        }
        tx.commit().await?;
        Ok(())
    }

    pub async fn push_registration(&self, peer: &str) -> Result<Option<PushRegistrationRecord>> {
        let row = sqlx::query_as::<_, (String,String,String,String,Vec<u8>)>(
            "SELECT peer,registration_id,origin,p256dh,encrypted_secrets FROM push_registrations WHERE peer=?")
            .bind(peer).fetch_optional(&self.pool).await?;
        Ok(row.map(|(peer,registration_id,origin,p256dh,encrypted_secrets)| PushRegistrationRecord { peer,registration_id,origin,p256dh,encrypted_secrets }))
    }

    pub async fn replace_push_registration(&self, registration: &PushRegistrationRecord) -> Result<()> {
        self.replace_push_registration_with_rich(registration, false).await
    }

    pub async fn push_registration_supports_rich(&self, peer: &str, registration_id: &str) -> Result<bool> {
        Ok(sqlx::query_scalar::<_, bool>("SELECT EXISTS(SELECT 1 FROM push_registration_capabilities WHERE peer=? AND registration_id=? AND rich=1)")
            .bind(peer).bind(registration_id).fetch_one(&self.pool).await?)
    }

    pub async fn replace_push_registration_with_rich(&self, registration: &PushRegistrationRecord, rich: bool) -> Result<()> {
        let mut tx = self.pool.begin().await?;
        sqlx::query("UPDATE push_outbox_history SET status='not-posted',code='registration-rotated',updated_at_ms=? WHERE (peer,request_id) IN (SELECT peer,request_id FROM push_outbox WHERE peer=? AND registration_id<>?)")
            .bind(crate::store::now_ms()).bind(&registration.peer).bind(&registration.registration_id).execute(&mut *tx).await?;
        sqlx::query("INSERT INTO push_registrations(peer,registration_id,origin,p256dh,encrypted_secrets,updated_at_ms) VALUES(?,?,?,?,?,?) ON CONFLICT(peer) DO UPDATE SET registration_id=excluded.registration_id,origin=excluded.origin,p256dh=excluded.p256dh,encrypted_secrets=excluded.encrypted_secrets,updated_at_ms=excluded.updated_at_ms")
            .bind(&registration.peer).bind(&registration.registration_id).bind(&registration.origin).bind(&registration.p256dh).bind(&registration.encrypted_secrets).bind(crate::store::now_ms()).execute(&mut *tx).await?;
        sqlx::query("INSERT INTO push_registration_capabilities(peer,registration_id,rich) VALUES(?,?,?) ON CONFLICT(peer) DO UPDATE SET registration_id=excluded.registration_id,rich=excluded.rich")
            .bind(&registration.peer).bind(&registration.registration_id).bind(rich).execute(&mut *tx).await?;
        sqlx::query("DELETE FROM push_outbox WHERE peer=? AND registration_id<>?").bind(&registration.peer).bind(&registration.registration_id).execute(&mut *tx).await?;
        tx.commit().await?;
        Ok(())
    }

    pub async fn remove_push_registration(&self, peer: &str, registration_id: &str) -> Result<bool> {
        let mut tx = self.pool.begin().await?;
        let result=sqlx::query("DELETE FROM push_registrations WHERE peer=? AND registration_id=?").bind(peer).bind(registration_id).execute(&mut *tx).await?;
        if result.rows_affected()>0 {
            sqlx::query("UPDATE push_outbox_history SET status='not-posted',code='registration-removed',updated_at_ms=? WHERE (peer,request_id) IN (SELECT peer,request_id FROM push_outbox WHERE peer=? AND registration_id=?)")
                .bind(crate::store::now_ms()).bind(peer).bind(registration_id).execute(&mut *tx).await?;
            sqlx::query("DELETE FROM push_outbox WHERE peer=? AND registration_id=?").bind(peer).bind(registration_id).execute(&mut *tx).await?;
        }
        tx.commit().await?;
        Ok(result.rows_affected()>0)
    }

    pub async fn push_allow_origin(&self, origin: &str) -> Result<()> {
        sqlx::query("INSERT INTO push_allowed_origins(origin,created_at_ms) VALUES(?,?) ON CONFLICT(origin) DO NOTHING")
            .bind(origin).bind(crate::store::now_ms()).execute(&self.pool).await?;
        Ok(())
    }

    pub async fn push_revoke_origin(&self, origin: &str) -> Result<bool> {
        let mut tx = self.pool.begin().await?;
        sqlx::query("UPDATE push_outbox_history SET status='unavailable',code='origin-revoked',updated_at_ms=? WHERE (peer,request_id) IN (SELECT o.peer,o.request_id FROM push_outbox o JOIN push_registrations r ON r.peer=o.peer WHERE r.origin=?)")
            .bind(crate::store::now_ms()).bind(origin).execute(&mut *tx).await?;
        sqlx::query("DELETE FROM push_outbox WHERE peer IN (SELECT peer FROM push_registrations WHERE origin=?)").bind(origin).execute(&mut *tx).await?;
        sqlx::query("DELETE FROM push_registrations WHERE origin=?").bind(origin).execute(&mut *tx).await?;
        let result = sqlx::query("DELETE FROM push_allowed_origins WHERE origin=?").bind(origin).execute(&mut *tx).await?;
        tx.commit().await?;
        Ok(result.rows_affected() > 0)
    }

    pub async fn push_allowed_origins(&self) -> Result<Vec<String>> {
        Ok(sqlx::query_scalar("SELECT origin FROM push_allowed_origins ORDER BY origin").fetch_all(&self.pool).await?)
    }

    pub async fn devices(&self) -> Result<Vec<Device>> {
        let rows = sqlx::query_as::<_, (String, String, i64, Option<i64>, Option<i64>)>(
            "SELECT peer,name,paired_at_ms,last_seen_ms,idle_ttl_seconds FROM paired_devices ORDER BY paired_at_ms",
        ).fetch_all(&self.pool).await?;
        rows.into_iter().map(device_from_row).collect()
    }

    pub async fn upload_expiry(&self, peer: &str, completed_at_ms: i64) -> Result<i64> {
        let settings = self.effective_settings(peer).await?;
        completed_at_ms.checked_add(i64::from(settings.upload_retention_seconds.get()) * 1000)
            .context("upload expiry timestamp overflow")
    }

    /// Register the immutable expiry captured in the upload's durable completion intent.
    /// Exact retries are idempotent only while the existing record remains live; they
    /// can neither extend retention nor clear an expired marker.
    pub async fn register_upload(&self, peer: &str, upload_id: &str, path: &str, file_name: &str, media_type: &str, length: u64, sha256: &str, expires_at_ms: i64, now_ms: i64) -> Result<()> {
        anyhow::ensure!(expires_at_ms > now_ms, "completed upload is already expired");
        let length = i64::try_from(length).context("upload length exceeds SQLite range")?;
        let mut tx = self.pool.begin().await?;
        sqlx::query("INSERT OR IGNORE INTO uploads(upload_id,peer,path,file_name,media_type,length,sha256,expires_at_ms,active_references,expired) VALUES(?,?,?,?,?,?,?,?,0,0)")
            .bind(upload_id).bind(peer).bind(path).bind(file_name).bind(media_type)
            .bind(length).bind(sha256).bind(expires_at_ms).execute(&mut *tx).await?;
        let existing = sqlx::query_as::<_, (String,String,String,i64,String,i64,i64)>("SELECT path,file_name,media_type,length,sha256,expires_at_ms,expired FROM uploads WHERE upload_id=? AND peer=?")
            .bind(upload_id).bind(peer).fetch_optional(&mut *tx).await?;
        let Some((stored_path, stored_name, stored_type, stored_length, stored_hash, stored_expiry, expired)) = existing else {
            anyhow::bail!("upload identity is already owned by another peer");
        };
        anyhow::ensure!(stored_path == path && stored_name == file_name && stored_type == media_type && stored_length == length && stored_hash == sha256,
            "upload identity is already registered with different metadata");
        anyhow::ensure!(stored_expiry == expires_at_ms, "upload expiry differs from its original completion");
        anyhow::ensure!(expired == 0 && stored_expiry > now_ms, "completed upload is expired or unavailable");
        tx.commit().await?;
        Ok(())
    }

    pub async fn upload_record(&self, peer: &str, upload_id: &str, now_ms: i64) -> Result<Option<(String, String, String, i64, String)>> {
        Ok(sqlx::query_as("SELECT path,file_name,media_type,length,sha256 FROM uploads WHERE peer=? AND upload_id=? AND expired=0 AND expires_at_ms>? ")
            .bind(peer).bind(upload_id).bind(now_ms).fetch_optional(&self.pool).await?)
    }

    /// Expired files remain on disk while any accepted request still pins them.
    pub async fn upload_has_active_references(&self, peer: &str, upload_id: &str) -> Result<bool> {
        Ok(sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM uploads WHERE peer=? AND upload_id=? AND active_references>0)")
            .bind(peer).bind(upload_id).fetch_one(&self.pool).await?)
    }

    pub async fn pinned_upload_record(&self, peer: &PeerKey, request: &RequestKey, upload_id: &str) -> Result<Option<(String, String, String, i64, String)>> {
        Ok(sqlx::query_as("SELECT u.path,u.file_name,u.media_type,u.length,u.sha256 FROM uploads u JOIN upload_request_refs r ON r.upload_id=u.upload_id AND r.peer=u.peer WHERE r.peer=? AND r.request_id=? AND u.upload_id=?")
            .bind(peer.as_str()).bind(request.as_str()).bind(upload_id).fetch_optional(&self.pool).await?)
    }

    /// Mark due uploads expired in SQLite before returning paths for filesystem cleanup.
    /// Active references are retained and reconsidered when released by their consumer.
    pub async fn expire_uploads(&self, now_ms: i64) -> Result<Vec<String>> {
        let mut tx = self.pool.begin_with("BEGIN IMMEDIATE").await?;
        let paths = sqlx::query_scalar::<_, String>("SELECT path FROM uploads WHERE (expired=1 OR expires_at_ms<=?) AND active_references=0")
            .bind(now_ms).fetch_all(&mut *tx).await?;
        sqlx::query("UPDATE uploads SET expired=1 WHERE expired=0 AND expires_at_ms<=? AND active_references=0")
            .bind(now_ms).execute(&mut *tx).await?;
        tx.commit().await?;
        Ok(paths)
    }

    pub async fn effective_settings(&self, endpoint: &str) -> Result<EffectiveSettings> {
        let row = sqlx::query_as::<_, (Option<i64>, Option<i64>)>(
            "SELECT idle_ttl_seconds,upload_retention_seconds FROM paired_devices WHERE peer=?",
        ).bind(endpoint).fetch_optional(&self.pool).await?
            .context("trusted phone is not paired")?;
        let idle = row.0
            .map(u32::try_from)
            .transpose()
            .context("stored idle timeout is invalid")?
            .map(IdleTimeoutSeconds::try_from)
            .transpose()?
            .unwrap_or(IdleTimeoutSeconds::try_from(DEFAULT_IDLE_TIMEOUT_SECONDS)?);
        let retention = row.1
            .map(u32::try_from)
            .transpose()
            .context("stored upload retention is invalid")?
            .map(UploadRetentionSeconds::try_from)
            .transpose()?
            .unwrap_or(UploadRetentionSeconds::try_from(DEFAULT_UPLOAD_RETENTION_SECONDS)?);
        Ok(EffectiveSettings { idle_timeout_seconds: idle, upload_retention_seconds: retention })
    }

    pub async fn update_settings(&self, endpoint: &str, settings: &EffectiveSettings, now_ms: i64) -> Result<()> {
        let mut tx = self.pool.begin().await?;
        let updated = sqlx::query("UPDATE paired_devices SET idle_ttl_seconds=?,upload_retention_seconds=? WHERE peer=?")
            .bind(i64::from(settings.idle_timeout_seconds.get()))
            .bind(i64::from(settings.upload_retention_seconds.get()))
            .bind(endpoint)
            .execute(&mut *tx).await?.rows_affected();
        if updated == 0 { anyhow::bail!("trusted phone is not paired"); }
        sqlx::query("UPDATE sessions SET idle_ttl_seconds=? WHERE peer=? AND state IN ('idle','busy')")
            .bind(i64::from(settings.idle_timeout_seconds.get())).bind(endpoint)
            .execute(&mut *tx).await?;
        let event = DurableEvent::SettingsChanged { settings: settings.clone() };
        let mut payload = Vec::new();
        ciborium::ser::into_writer(&event, &mut payload).context("encode settings event")?;
        let peer = PeerKey::try_from(endpoint.to_owned())?;
        Self::append_event_tx(&mut tx, &peer, "settings", &payload, now_ms).await?;
        tx.commit().await?;
        self.notify_committed();
        Ok(())
    }

    pub async fn device(&self, endpoint: &str) -> Result<Option<Device>> {
        let row = sqlx::query_as::<_, (String, String, i64, Option<i64>, Option<i64>)>(
            "SELECT peer,name,paired_at_ms,last_seen_ms,idle_ttl_seconds FROM paired_devices WHERE peer=?",
        ).bind(endpoint).fetch_optional(&self.pool).await?;
        row.map(device_from_row).transpose()
    }

    pub async fn upsert_device(&self, device: &Device) -> Result<()> {
        crate::store::validate_endpoint(&device.endpoint)?;
        let mut tx = self.pool.begin().await?;
        sqlx::query("INSERT INTO paired_devices(peer,name,paired_at_ms,last_seen_ms,idle_ttl_seconds,upload_retention_seconds) VALUES(?,?,?,?,?,?) ON CONFLICT(peer) DO UPDATE SET name=excluded.name,last_seen_ms=excluded.last_seen_ms,idle_ttl_seconds=COALESCE(excluded.idle_ttl_seconds,paired_devices.idle_ttl_seconds)")
            .bind(&device.endpoint).bind(&device.name).bind(device.paired_at).bind(device.last_seen)
            .bind(device.idle_timeout_seconds.map(|value| i64::from(value.get())))
            .bind(i64::from(DEFAULT_UPLOAD_RETENTION_SECONDS))
            .execute(&mut *tx).await?;
        sqlx::query("INSERT OR IGNORE INTO peer_cursors(peer,cursor) VALUES(?,0)")
            .bind(&device.endpoint).execute(&mut *tx).await?;
        tx.commit().await?;
        Ok(())
    }

    pub async fn update_idle_timeout(&self, endpoint: &str, timeout: IdleTimeoutSeconds) -> Result<()> {
        let mut tx = self.pool.begin().await?;
        let updated = sqlx::query("UPDATE paired_devices SET idle_ttl_seconds=? WHERE peer=?")
            .bind(i64::from(timeout.get())).bind(endpoint).execute(&mut *tx).await?.rows_affected();
        if updated == 0 { anyhow::bail!("trusted phone is not paired"); }
        sqlx::query("UPDATE sessions SET idle_ttl_seconds=? WHERE peer=? AND state IN ('idle','busy')")
            .bind(i64::from(timeout.get())).bind(endpoint).execute(&mut *tx).await?;
        tx.commit().await?;
        Ok(())
    }

    pub async fn mark_seen(&self, endpoint: &str, when: i64) -> Result<()> {
        sqlx::query("UPDATE paired_devices SET last_seen_ms=? WHERE peer=?")
            .bind(when).bind(endpoint).execute(&self.pool).await?;
        Ok(())
    }

    pub async fn revoke_device(&self, endpoint: &str) -> Result<bool> {
        crate::store::validate_endpoint(endpoint)?;
        let mut tx = self.pool.begin().await?;
        sqlx::query("DELETE FROM push_outbox WHERE peer=?").bind(endpoint).execute(&mut *tx).await?;
        sqlx::query("DELETE FROM push_outbox_history WHERE peer=?").bind(endpoint).execute(&mut *tx).await?;
        sqlx::query("DELETE FROM push_registrations WHERE peer=?").bind(endpoint).execute(&mut *tx).await?;
        let removed = sqlx::query("DELETE FROM paired_devices WHERE peer=?")
            .bind(endpoint).execute(&mut *tx).await?.rows_affected() > 0;
        tx.commit().await?;
        Ok(removed)
    }
    pub async fn active_conversations(&self, endpoint: &str, home: &str) -> Result<Vec<ConversationRecord>> {
        let rows = sqlx::query_as::<_, (String,String,Option<String>,Option<String>,Option<String>,String,i64,i64,i64)>(
            "SELECT session_id,peer,pi_session_id,transcript_path,session_name,state,created_at_ms,last_user_message_at_ms,idle_ttl_seconds FROM sessions WHERE peer=? AND state IN ('idle','busy') ORDER BY last_user_message_at_ms DESC",
        ).bind(endpoint).fetch_all(&self.pool).await?;
        rows.into_iter().map(|row| conversation_from_row(row, home)).collect()
    }

    pub async fn conversation_by_id(&self, endpoint: &str, id: ConversationId, home: &str) -> Result<Option<ConversationRecord>> {
        let row = sqlx::query_as::<_, (String,String,Option<String>,Option<String>,Option<String>,String,i64,i64,i64)>(
            "SELECT session_id,peer,pi_session_id,transcript_path,session_name,state,created_at_ms,last_user_message_at_ms,idle_ttl_seconds FROM sessions WHERE peer=? AND session_id=?",
        ).bind(endpoint).bind(id.to_string()).fetch_optional(&self.pool).await?;
        row.map(|row| conversation_from_row(row, home)).transpose()
    }

    pub async fn upsert_conversation(&self, row: &ConversationRecord) -> Result<()> {
        let mut tx = self.pool.begin().await?;
        sqlx::query("INSERT INTO sessions(session_id,peer,pi_session_id,transcript_path,session_name,state,created_at_ms,last_user_message_at_ms,idle_ttl_seconds) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(session_id) DO UPDATE SET pi_session_id=excluded.pi_session_id,transcript_path=excluded.transcript_path,session_name=excluded.session_name,state=excluded.state,last_user_message_at_ms=excluded.last_user_message_at_ms,idle_ttl_seconds=excluded.idle_ttl_seconds WHERE sessions.peer=excluded.peer")
            .bind(row.id.to_string()).bind(&row.endpoint).bind(&row.session_id).bind(&row.session_file)
            .bind(&row.session_name).bind(conversation_state_key(&row.state)).bind(row.created_at)
            .bind(row.last_user_prompt_at).bind(row.idle_timeout_seconds.map(|value| i64::from(value.get())))
            .execute(&mut *tx).await?;
        if let (Some(session_id), Some(idle_timeout_seconds)) = (&row.session_id, row.idle_timeout_seconds) {
            let metadata = ConversationMetadata {
                configuration: session_configuration(&mut tx, &row.endpoint, row.id).await?,
                session_id: session_id.clone(),
                conversation_id: row.id,
                state: match &row.state {
                    ConversationState::Idle | ConversationState::Busy => WireConversationState::Active,
                    ConversationState::Closed => WireConversationState::Closed,
                    ConversationState::Expired => WireConversationState::Expired,
                    ConversationState::Unavailable => WireConversationState::Unavailable,
                },
                created_at: timestamp(row.created_at)?,
                expires_at: timestamp(row.last_user_prompt_at.checked_add(i64::from(idle_timeout_seconds.get()) * 1000).context("session expiry timestamp overflow")?)?,
                idle_timeout_seconds,
                session_name: row.session_name.clone(),
            };
            let event = DurableEvent::SessionChanged { session: metadata };
            let mut payload = Vec::new();
            ciborium::ser::into_writer(&event, &mut payload).context("encode session event")?;
            let peer = PeerKey::try_from(row.endpoint.clone())?;
            Self::append_event_tx(&mut tx, &peer, "session-state", &payload, jiff::Timestamp::now().as_millisecond()).await?;
        }
        tx.commit().await?;
        self.notify_committed();
        Ok(())
    }

    pub async fn transcript_shared(&self, row: &ConversationRecord) -> Result<bool> {
        Ok(sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM sessions WHERE session_id<>? AND state IN ('idle','busy') AND (pi_session_id=? OR transcript_path=?))")
            .bind(row.id.to_string()).bind(&row.session_id).bind(&row.session_file).fetch_one(&self.pool).await?)
    }

    pub(crate) async fn history_rows(&self, peer: &str, home: &str) -> Result<Vec<ConversationRecord>> {
        let rows = sqlx::query_as::<_, (String,String,Option<String>,Option<String>,Option<String>,String,i64,i64,i64)>(
            "SELECT session_id,peer,pi_session_id,transcript_path,session_name,state,created_at_ms,last_user_message_at_ms,idle_ttl_seconds FROM sessions WHERE peer=? AND pi_session_id IS NOT NULL",
        ).bind(peer).fetch_all(&self.pool).await?;
        rows.into_iter().map(|row| conversation_from_row(row, home)).collect()
    }

    pub(crate) async fn history_deleted_ids(&self) -> Result<std::collections::HashSet<Uuid>> {
        let ids: Vec<String> = sqlx::query_scalar("SELECT pi_session_id FROM pi_history_deletes WHERE finished=1").fetch_all(&self.pool).await?;
        ids.into_iter().map(|id| Ok(Uuid::parse_str(&id)?)).collect()
    }

    pub(crate) async fn is_history_deleting(&self, pi_id: Uuid) -> Result<bool> {
        Ok(sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM pi_history_deletes WHERE pi_session_id=?)")
            .bind(pi_id.to_string()).fetch_one(&self.pool).await?)
    }

    pub(crate) async fn has_history_association(&self, peer: &str, pi_id: Uuid) -> Result<bool> {
        Ok(sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM sessions WHERE peer=? AND pi_session_id=?)")
            .bind(peer).bind(pi_id.to_string()).fetch_one(&self.pool).await?)
    }

    pub(crate) async fn history_delete(&self, peer: &str, id: ConversationId) -> Result<Option<HistoryDelete>> {
        let row = sqlx::query_as::<_, (String, String, String, String, bool)>(
            "SELECT pi_session_id,transcript_path,device,inode,finished FROM pi_history_deletes WHERE peer=? AND conversation_id=?",
        ).bind(peer).bind(id.to_string()).fetch_optional(&self.pool).await?;
        row.map(|(pi_id, path, dev, ino, finished)| Ok(HistoryDelete {
            pi_id: Uuid::parse_str(&pi_id)?, path: path.into(),
            identity: crate::pi_history::FileIdentity { device: dev.parse()?, inode: ino.parse()? }, finished,
        })).transpose()
    }

    pub(crate) async fn reserve_history_conversation(&self, row: &ConversationRecord) -> Result<bool> {
        let pi_id = row.session_id.as_deref().context("missing Pi identity for history association")?;
        let inserted = sqlx::query("INSERT INTO sessions(session_id,peer,pi_session_id,transcript_path,session_name,state,created_at_ms,last_user_message_at_ms,idle_ttl_seconds) SELECT ?,?,?,?,?,'closed',?,?,? WHERE EXISTS(SELECT 1 FROM paired_devices WHERE peer=?) AND NOT EXISTS(SELECT 1 FROM pi_history_deletes WHERE pi_session_id=?) ON CONFLICT DO NOTHING")
            .bind(row.id.to_string()).bind(&row.endpoint).bind(pi_id).bind(&row.session_file).bind(&row.session_name)
            .bind(row.created_at).bind(row.last_user_prompt_at).bind(row.idle_timeout_seconds.map(|value| i64::from(value.get())))
            .bind(&row.endpoint).bind(pi_id).execute(&self.pool).await?.rows_affected();
        Ok(inserted == 1)
    }

    /// Durable intent before unlink. Foreign-key request/deduplication records remain intact.
    /// An active association or accepted request on any paired phone disallows deletion.
    pub(crate) async fn begin_history_delete(&self, peer: &str, id: ConversationId, pi_id: Uuid, path: &str, identity: crate::pi_history::FileIdentity) -> Result<bool> {
        let pi_id = pi_id.to_string();
        let inserted = sqlx::query("INSERT INTO pi_history_deletes(pi_session_id,peer,conversation_id,transcript_path,device,inode,finished) SELECT ?,?,?,?,?,?,0 WHERE EXISTS(SELECT 1 FROM paired_devices WHERE peer=?) AND NOT EXISTS(SELECT 1 FROM sessions s WHERE (s.pi_session_id=? OR s.transcript_path=?) AND (s.state IN ('idle','busy') OR EXISTS(SELECT 1 FROM requests r WHERE r.session_id=s.session_id AND r.final_sequence IS NULL))) ON CONFLICT DO NOTHING")
            .bind(&pi_id).bind(peer).bind(id.to_string()).bind(path).bind(identity.device.to_string()).bind(identity.inode.to_string())
            .bind(peer).bind(&pi_id).bind(path).execute(&self.pool).await?.rows_affected();
        Ok(inserted == 1)
    }

    pub(crate) async fn finish_history_delete(&self, peer: &str, id: ConversationId, pi_id: Uuid) -> Result<()> {
        let mut tx = self.pool.begin().await?;
        sqlx::query("UPDATE sessions SET pi_session_id=NULL, transcript_path=NULL, session_name=NULL, state='unavailable' WHERE pi_session_id=?")
            .bind(pi_id.to_string()).execute(&mut *tx).await?;
        let changed = sqlx::query("UPDATE pi_history_deletes SET finished=1, transcript_path='', device='0', inode='0' WHERE peer=? AND conversation_id=? AND pi_session_id=?")
            .bind(peer).bind(id.to_string()).bind(pi_id.to_string()).execute(&mut *tx).await?.rows_affected();
        anyhow::ensure!(changed == 1, "missing Pi history deletion intent");
        tx.commit().await?;
        self.notify_committed();
        Ok(())
    }

    pub async fn expired_idle_candidates(&self, now_ms: i64, home: &str) -> Result<Vec<ConversationRecord>> {
        let rows = sqlx::query_as::<_, (String,String,Option<String>,Option<String>,Option<String>,String,i64,i64,i64)>(
            "SELECT session_id,peer,pi_session_id,transcript_path,session_name,state,created_at_ms,last_user_message_at_ms,idle_ttl_seconds FROM sessions WHERE state='idle' AND last_user_message_at_ms + idle_ttl_seconds * 1000 <= ?",
        ).bind(now_ms).fetch_all(&self.pool).await?;
        rows.into_iter().map(|row| conversation_from_row(row, home)).collect()
    }

    /// Persist one expiry only after the session actor approved the lifecycle transition.
    /// State and its durable event commit together; a concurrent accepted prompt changes
    /// the row out of idle and makes this command a no-op.
    pub async fn persist_session_expiry(&self, row: &ConversationRecord, now_ms: i64) -> Result<bool> {
        let mut tx = self.pool.begin().await?;
        let changed = sqlx::query("UPDATE sessions SET state='expired' WHERE session_id=? AND peer=? AND state='idle'")
            .bind(row.id.to_string()).bind(&row.endpoint).execute(&mut *tx).await?.rows_affected() == 1;
        if changed {
            let mut expired = row.clone();
            expired.state = ConversationState::Expired;
            let configuration = session_configuration(&mut tx, &expired.endpoint, expired.id).await?;
            if let Some(metadata) = conversation_metadata(&expired, configuration)? {
                let mut payload = Vec::new();
                ciborium::ser::into_writer(&DurableEvent::SessionChanged { session: metadata }, &mut payload)
                    .context("encode session expiry event")?;
                let peer = PeerKey::try_from(row.endpoint.clone())?;
                Self::append_event_tx(&mut tx, &peer, "session-state", &payload, now_ms).await?;
            }
        }
        tx.commit().await?;
        if changed { self.notify_committed(); }
        Ok(changed)
    }


    async fn migrate(&self) -> Result<()> {
        // Development-only schema: fail on malformed/newer schemas rather than adding
        // compatibility migration behavior or importing the retired JSON store.
        let retired_cache: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name='response_chunks')")
            .fetch_one(&self.pool).await?;
        anyhow::ensure!(!retired_cache, "Protocol 4 requires coordinated fresh development storage; the existing v3 database is preserved and will not be imported or modified");
        for statement in [
            "CREATE TABLE IF NOT EXISTS paired_devices (peer TEXT PRIMARY KEY, name TEXT NOT NULL, paired_at_ms INTEGER NOT NULL, last_seen_ms INTEGER, idle_ttl_seconds INTEGER, upload_retention_seconds INTEGER)",
            "CREATE TABLE IF NOT EXISTS sessions (session_id TEXT PRIMARY KEY, peer TEXT NOT NULL, pi_session_id TEXT, transcript_path TEXT, session_name TEXT, state TEXT NOT NULL, created_at_ms INTEGER NOT NULL, last_user_message_at_ms INTEGER NOT NULL, idle_ttl_seconds INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS pi_history_deletes (pi_session_id TEXT PRIMARY KEY, peer TEXT NOT NULL, conversation_id TEXT NOT NULL, transcript_path TEXT NOT NULL, device TEXT NOT NULL, inode TEXT NOT NULL, finished INTEGER NOT NULL, UNIQUE(peer,conversation_id))",
            "CREATE INDEX IF NOT EXISTS sessions_peer_created ON sessions(peer, created_at_ms DESC)",
            "CREATE TABLE IF NOT EXISTS requests (peer TEXT NOT NULL, request_id TEXT NOT NULL, session_id TEXT NOT NULL REFERENCES sessions(session_id), fingerprint BLOB NOT NULL, outcome TEXT NOT NULL, accepted_at_ms INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL, final_sequence INTEGER, PRIMARY KEY(peer, request_id))",
            "CREATE TABLE IF NOT EXISTS request_models (peer TEXT NOT NULL, request_id TEXT NOT NULL, configuration TEXT NOT NULL, PRIMARY KEY(peer,request_id), FOREIGN KEY(peer,request_id) REFERENCES requests(peer,request_id))",
            "CREATE TABLE IF NOT EXISTS peer_cursors (peer TEXT PRIMARY KEY, cursor INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS sync_floor (peer TEXT PRIMARY KEY, cursor INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS sync_events (peer TEXT NOT NULL, cursor INTEGER NOT NULL, kind TEXT NOT NULL, payload BLOB NOT NULL, created_at_ms INTEGER NOT NULL, PRIMARY KEY(peer,cursor))",
            "CREATE INDEX IF NOT EXISTS sync_events_created ON sync_events(peer, created_at_ms)",
            "CREATE TABLE IF NOT EXISTS uploads (upload_id TEXT PRIMARY KEY, peer TEXT NOT NULL, path TEXT NOT NULL, file_name TEXT NOT NULL, media_type TEXT NOT NULL, length INTEGER NOT NULL, sha256 TEXT NOT NULL, expires_at_ms INTEGER NOT NULL, active_references INTEGER NOT NULL DEFAULT 0, expired INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS upload_request_refs (peer TEXT NOT NULL, request_id TEXT NOT NULL, upload_id TEXT NOT NULL, PRIMARY KEY(peer,request_id,upload_id), FOREIGN KEY(peer,request_id) REFERENCES requests(peer,request_id), FOREIGN KEY(upload_id) REFERENCES uploads(upload_id))",
            "CREATE TABLE IF NOT EXISTS push_allowed_origins (origin TEXT PRIMARY KEY, created_at_ms INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS push_registrations (peer TEXT PRIMARY KEY REFERENCES paired_devices(peer) ON DELETE CASCADE, registration_id TEXT NOT NULL UNIQUE, origin TEXT NOT NULL, p256dh TEXT NOT NULL, encrypted_secrets BLOB NOT NULL, updated_at_ms INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS push_registration_capabilities (peer TEXT PRIMARY KEY REFERENCES push_registrations(peer) ON DELETE CASCADE, registration_id TEXT NOT NULL, rich INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS push_outbox (peer TEXT NOT NULL, request_id TEXT NOT NULL, registration_id TEXT NOT NULL, ciphertext BLOB NOT NULL, fingerprint BLOB NOT NULL, expires_at_ms INTEGER NOT NULL, status TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at_ms INTEGER NOT NULL, created_at_ms INTEGER NOT NULL, last_code TEXT, PRIMARY KEY(peer,request_id))",
            "CREATE INDEX IF NOT EXISTS push_outbox_due ON push_outbox(status,next_attempt_at_ms,expires_at_ms)",
            "CREATE TABLE IF NOT EXISTS push_outbox_history (peer TEXT NOT NULL, request_id TEXT NOT NULL, status TEXT NOT NULL, code TEXT, fingerprint BLOB NOT NULL, expires_at_ms INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL, PRIMARY KEY(peer,request_id))",
        ] { sqlx::query(statement).execute(&self.pool).await?; }
        Ok(())
    }

    pub async fn recover_interrupted_requests(&self, now_ms: i64) -> Result<()> {
        let mut tx = self.pool.begin().await?;
        let rows = sqlx::query_as::<_, (String, String, String, String)>(
            "SELECT peer, request_id, session_id, outcome FROM requests WHERE outcome IN ('queued','running') ORDER BY accepted_at_ms",
        )
        .fetch_all(&mut *tx)
        .await?;
        for (peer_raw, request_raw, session_raw, previous) in rows {
            let peer = PeerKey::try_from(peer_raw)?;
            let request = RequestKey::try_from(request_raw.clone())?;
            let session = SessionKey::try_from(session_raw.clone())?;
            let conversation_id = ConversationId::from(Uuid::parse_str(session.as_str())?);
            let outcome = if previous == "queued" { RequestOutcome::Failed } else { RequestOutcome::Uncertain };
            let wire_outcome = if previous == "queued" { WireRequestOutcome::Failed } else { WireRequestOutcome::Uncertain };
            let final_sequence = 0u32;
            sqlx::query("UPDATE requests SET outcome=?, final_sequence=?, updated_at_ms=? WHERE peer=? AND request_id=?")
                .bind(outcome.as_str()).bind(i64::from(final_sequence)).bind(now_ms)
                .bind(peer.as_str()).bind(request.as_str()).execute(&mut *tx).await?;
            Self::release_upload_pins_tx(&mut tx, &peer, &request).await?;
            let event = DurableEvent::ResponseCompleted {
                request_id: RequestId::parse(request.as_str().to_owned())?,
                outcome: wire_outcome,
                final_sequence,
            };
            let mut payload = Vec::new();
            ciborium::ser::into_writer(&event, &mut payload).context("encode recovery completion event")?;
            Self::append_event_tx(&mut tx, &peer, "response-complete", &payload, now_ms).await?;
            let summary = DurableEvent::RequestChanged {
                request: RequestSummary {
                    request_id: RequestId::parse(request.as_str().to_owned())?,
                    conversation_id,
                    outcome: wire_outcome,
                    next_sequence: final_sequence,
                    configuration: self.request_configuration(&peer, &request).await?,
                },
            };
            let mut payload = Vec::new();
            ciborium::ser::into_writer(&summary, &mut payload).context("encode recovery request event")?;
            Self::append_event_tx(&mut tx, &peer, "request-state", &payload, now_ms).await?;
        }
        tx.commit().await?;
        self.notify_committed();
        Ok(())
    }

    pub async fn ensure_peer(&self, peer: &PeerKey, name: &str, now_ms: i64) -> Result<()> {
        let mut tx = self.pool.begin().await?;
        sqlx::query("INSERT INTO paired_devices(peer,name,paired_at_ms,last_seen_ms) VALUES(?,?,?,?) ON CONFLICT(peer) DO UPDATE SET name=excluded.name,last_seen_ms=excluded.last_seen_ms")
            .bind(peer.as_str()).bind(name).bind(now_ms).bind(now_ms).execute(&mut *tx).await?;
        sqlx::query("INSERT OR IGNORE INTO peer_cursors(peer,cursor) VALUES(?,0)").bind(peer.as_str()).execute(&mut *tx).await?;
        tx.commit().await?;
        self.notify_committed();
        Ok(())
    }

    /// A uniqueness conflict is reported with the prior fingerprint/outcome and never
    /// re-dispatched. The session association and acceptance event commit with the claim.
    pub async fn claim_request(&self, claim: &RequestClaim, upload_ids: &[String], now_ms: i64, encoded_event: &[u8]) -> Result<ClaimedRequest> {
        // Concurrent lanes must not read the same WAL snapshot and then race to
        // upgrade it to a write transaction. Take SQLite's writer lease first.
        let mut tx = self.pool.begin_with("BEGIN IMMEDIATE").await?;
        let prior = sqlx::query_as::<_, (String, String, Vec<u8>)>("SELECT outcome,session_id,fingerprint FROM requests WHERE peer=? AND request_id=?")
            .bind(claim.peer.as_str()).bind(claim.request.as_str()).fetch_optional(&mut *tx).await?;
        if let Some((outcome, session_id, fingerprint)) = prior {
            let status = if fingerprint == claim.fingerprint { ClaimStatus::Existing } else { ClaimStatus::Conflict };
            let session = SessionKey::try_from(session_id)?;
            tx.rollback().await?;
            return Ok(ClaimedRequest { status, outcome, session });
        }
        // A claim is authorized only while the peer still exists in the same
        // SQLite write transaction that records the acceptance.
        sqlx::query("INSERT OR IGNORE INTO sessions(session_id,peer,state,created_at_ms,last_user_message_at_ms,idle_ttl_seconds) VALUES(?,?,'busy',?,?,?)")
            .bind(claim.session.as_str()).bind(claim.peer.as_str()).bind(claim.accepted_at_ms).bind(claim.accepted_at_ms).bind(claim.idle_ttl_seconds).execute(&mut *tx).await?;
        let owned = sqlx::query("UPDATE sessions SET state='busy',last_user_message_at_ms=?,idle_ttl_seconds=? WHERE session_id=? AND peer=?")
            .bind(claim.accepted_at_ms).bind(claim.idle_ttl_seconds).bind(claim.session.as_str()).bind(claim.peer.as_str()).execute(&mut *tx).await?.rows_affected();
        anyhow::ensure!(owned == 1, "conversation ID belongs to another paired device");
        let paired: bool = sqlx::query_scalar("SELECT EXISTS(SELECT 1 FROM paired_devices WHERE peer=?)")
            .bind(claim.peer.as_str()).fetch_one(&mut *tx).await?;
        if !paired {
            tx.rollback().await?;
            anyhow::bail!("trusted phone is not paired");
        }
        let inserted = sqlx::query("INSERT OR IGNORE INTO requests(peer,request_id,session_id,fingerprint,outcome,accepted_at_ms,updated_at_ms) VALUES(?,?,?,?,?,?,?)")
            .bind(claim.peer.as_str()).bind(claim.request.as_str()).bind(claim.session.as_str()).bind(&claim.fingerprint)
            .bind(RequestOutcome::Queued.as_str()).bind(claim.accepted_at_ms).bind(claim.accepted_at_ms).execute(&mut *tx).await?.rows_affected() == 1;
        let existing = sqlx::query_as::<_, (String, String, Vec<u8>)>("SELECT outcome, session_id, fingerprint FROM requests WHERE peer=? AND request_id=?")
            .bind(claim.peer.as_str()).bind(claim.request.as_str()).fetch_one(&mut *tx).await?;
        if !inserted && existing.2 != claim.fingerprint {
            tx.rollback().await?;
            return Ok(ClaimedRequest { status: ClaimStatus::Conflict, outcome: existing.0, session: SessionKey::try_from(existing.1)? });
        }
        let session = SessionKey::try_from(existing.1)?;
        if inserted {
            sqlx::query("INSERT INTO request_models(peer,request_id,configuration) VALUES(?,?,?)")
                .bind(claim.peer.as_str()).bind(claim.request.as_str()).bind(serde_json::to_string(&claim.configuration)?).execute(&mut *tx).await?;
            for upload_id in upload_ids {
                let pinned = sqlx::query("UPDATE uploads SET active_references=active_references+1 WHERE peer=? AND upload_id=? AND expired=0 AND expires_at_ms>?")
                    .bind(claim.peer.as_str()).bind(upload_id).bind(now_ms).execute(&mut *tx).await?.rows_affected();
                if pinned != 1 {
                    tx.rollback().await?;
                    return Ok(ClaimedRequest { status: ClaimStatus::AttachmentsUnavailable, outcome: String::new(), session: claim.session.clone() });
                }
                sqlx::query("INSERT INTO upload_request_refs(peer,request_id,upload_id) VALUES(?,?,?)")
                    .bind(claim.peer.as_str()).bind(claim.request.as_str()).bind(upload_id).execute(&mut *tx).await?;
            }
            Self::append_event_tx(&mut tx, &claim.peer, "request-accepted", encoded_event, claim.accepted_at_ms).await?;
        }
        tx.commit().await?;
        if inserted { self.notify_committed(); }
        Ok(ClaimedRequest { status: if inserted { ClaimStatus::New } else { ClaimStatus::Existing }, outcome: existing.0, session })
    }

    pub async fn conversation_configuration(&self, peer: &str, conversation_id: ConversationId) -> Result<Option<kompi_protocol::ChatModelConfiguration>> {
        let mut tx = self.pool.begin().await?;
        let configuration = session_configuration(&mut tx, peer, conversation_id).await?;
        tx.commit().await?;
        Ok(configuration)
    }

    pub async fn request_configuration(&self, peer: &PeerKey, request: &RequestKey) -> Result<Option<kompi_protocol::ChatModelConfiguration>> {
        let configuration: Option<String> = sqlx::query_scalar("SELECT configuration FROM request_models WHERE peer=? AND request_id=?")
            .bind(peer.as_str()).bind(request.as_str()).fetch_optional(&self.pool).await?;
        configuration.map(|json| serde_json::from_str(&json).context("invalid stored model configuration")).transpose()
    }

    pub async fn transition_request(&self, peer: &PeerKey, request: &RequestKey, outcome: RequestOutcome, now_ms: i64, event: &[u8]) -> Result<i64> {
        let mut tx = self.pool.begin().await?;
        sqlx::query("UPDATE requests SET outcome=?,updated_at_ms=? WHERE peer=? AND request_id=?")
            .bind(outcome.as_str()).bind(now_ms).bind(peer.as_str()).bind(request.as_str()).execute(&mut *tx).await?;
        let cursor = Self::append_event_tx(&mut tx, peer, "request-state", event, now_ms).await?;
        tx.commit().await?;
        self.notify_committed();
        Ok(cursor)
    }

    pub async fn complete_response(&self, peer: &PeerKey, request: &RequestKey, outcome: RequestOutcome, final_sequence: i64, now_ms: i64, event: &[u8]) -> Result<i64> {
        let mut tx = self.pool.begin().await?;
        sqlx::query("UPDATE requests SET outcome=?,final_sequence=?,updated_at_ms=? WHERE peer=? AND request_id=?")
            .bind(outcome.as_str()).bind(final_sequence).bind(now_ms).bind(peer.as_str()).bind(request.as_str()).execute(&mut *tx).await?;
        Self::release_upload_pins_tx(&mut tx, peer, request).await?;
        let cursor = Self::append_event_tx(&mut tx, peer, "response-complete", event, now_ms).await?;
        tx.commit().await?;
        self.notify_committed();
        Ok(cursor)
    }

    /// Inspect a retry identity before resolving attachment files. A matching accepted
    /// retry must replay its ledger state even if retention has since expired its uploads.
    pub async fn request_identity(&self, peer: &PeerKey, request: &RequestKey) -> Result<Option<(Vec<u8>, String, SessionKey)>> {
        let row = sqlx::query_as::<_, (Vec<u8>, String, String)>(
            "SELECT fingerprint, outcome, session_id FROM requests WHERE peer=? AND request_id=?",
        )
        .bind(peer.as_str())
        .bind(request.as_str())
        .fetch_optional(&self.pool)
        .await?;
        row.map(|(fingerprint, outcome, session)| {
            Ok((fingerprint, outcome, SessionKey::try_from(session)?))
        }).transpose()
    }

    pub async fn request_completion(&self, peer: &PeerKey, request: &RequestKey) -> Result<Option<(String, Option<u32>)>> {
        let row = sqlx::query_as::<_, (String, Option<i64>)>(
            "SELECT outcome, final_sequence FROM requests WHERE peer=? AND request_id=?",
        )
        .bind(peer.as_str())
        .bind(request.as_str())
        .fetch_optional(&self.pool)
        .await?;
        row.map(|(outcome, final_sequence)| {
            let sequence = final_sequence
                .map(u32::try_from)
                .transpose()
                .context("stored response sequence is invalid")?;
            Ok((outcome, sequence))
        })
        .transpose()
    }

    pub async fn sync_snapshot(&self, peer: &PeerKey, home: &str) -> Result<SyncSnapshotData> {
        let mut tx = self.pool.begin().await?;
        let cursor: i64 = sqlx::query_scalar("SELECT cursor FROM peer_cursors WHERE peer=?")
            .bind(peer.as_str()).fetch_optional(&mut *tx).await?.unwrap_or(0);
        let cursor = SyncCursor::parse(cursor.to_string())?;
        let settings_row = sqlx::query_as::<_, (Option<i64>, Option<i64>)>(
            "SELECT idle_ttl_seconds,upload_retention_seconds FROM paired_devices WHERE peer=?",
        ).bind(peer.as_str()).fetch_optional(&mut *tx).await?
            .context("trusted phone is not paired")?;
        let settings = settings_from_row(settings_row)?;
        let session_rows = sqlx::query_as::<_, (String,String,Option<String>,Option<String>,Option<String>,String,i64,i64,i64)>(
            "SELECT session_id,peer,pi_session_id,transcript_path,session_name,state,created_at_ms,last_user_message_at_ms,idle_ttl_seconds FROM sessions WHERE peer=? AND pi_session_id IS NOT NULL ORDER BY last_user_message_at_ms DESC LIMIT ?",
        ).bind(peer.as_str()).bind((MAX_SYNC_PAGE + 1) as i64).fetch_all(&mut *tx).await?;
        let has_more_sessions = session_rows.len() > MAX_SYNC_PAGE;
        let mut sessions = Vec::new();
        for row in session_rows.into_iter().take(MAX_SYNC_PAGE) {
            let row = conversation_from_row(row, home)?;
            let configuration = session_configuration(&mut tx, peer.as_str(), row.id).await?;
            if let Some(metadata) = conversation_metadata(&row, configuration)? {
                sessions.push(metadata);
            }
        }
        let request_rows = sqlx::query_as::<_, (String,String,String,i64)>(
            "SELECT request_id,session_id,outcome,COALESCE(final_sequence,0) FROM requests q WHERE peer=? ORDER BY accepted_at_ms DESC LIMIT ?",
        ).bind(peer.as_str()).bind((MAX_SYNC_PAGE + 1) as i64).fetch_all(&mut *tx).await?;
        let has_more_requests = request_rows.len() > MAX_SYNC_PAGE;
        let mut requests = request_rows.into_iter().take(MAX_SYNC_PAGE).map(|(request_id,session_id,outcome,next)| {
            let request_id = RequestId::parse(request_id)?;
            let conversation_id = ConversationId::from(Uuid::parse_str(&session_id)?);
            let outcome = wire_request_outcome(&outcome)?;
            let next_sequence = u32::try_from(next).context("stored response sequence is invalid")?;
            Ok(RequestSummary { request_id, conversation_id, outcome, next_sequence, configuration: None })
        }).collect::<Result<Vec<_>>>()?;
        tx.commit().await?;
        for request in &mut requests {
            request.configuration = self.request_configuration(peer, &RequestKey::try_from(request.request_id.to_string())?).await?;
        }
        Ok(SyncSnapshotData { cursor, settings, sessions, requests, has_more_sessions, has_more_requests })
    }

    pub async fn request_page(&self, peer: &PeerKey, after: Option<&RequestId>, limit: u8) -> Result<(Vec<RequestSummary>, Option<RequestId>)> {
        let rows = if let Some(after) = after {
            sqlx::query_as::<_, (String,String,String,i64)>(
                "SELECT request_id,session_id,outcome,COALESCE(final_sequence,0) FROM requests q WHERE peer=? AND request_id>? ORDER BY request_id LIMIT ?",
            ).bind(peer.as_str()).bind(after.as_str()).bind(i64::from(limit) + 1).fetch_all(&self.pool).await?
        } else {
            sqlx::query_as::<_, (String,String,String,i64)>(
                "SELECT request_id,session_id,outcome,COALESCE(final_sequence,0) FROM requests q WHERE peer=? ORDER BY request_id LIMIT ?",
            ).bind(peer.as_str()).bind(i64::from(limit) + 1).fetch_all(&self.pool).await?
        };
        let has_more = rows.len() > usize::from(limit);
        let mut requests = rows.into_iter().take(usize::from(limit)).map(|(request_id, session_id, outcome, next_sequence)| {
            Ok(RequestSummary {
                request_id: RequestId::parse(request_id)?,
                conversation_id: ConversationId::from(Uuid::parse_str(&session_id)?),
                outcome: wire_request_outcome(&outcome)?,
                next_sequence: u32::try_from(next_sequence).context("stored response sequence is invalid")?,
                configuration: None,
            })
        }).collect::<Result<Vec<_>>>()?;
        for request in &mut requests {
            request.configuration = self.request_configuration(peer, &RequestKey::try_from(request.request_id.to_string())?).await?;
        }
        let next_cursor = if has_more { requests.last().map(|request| request.request_id.clone()) } else { None };
        Ok((std::mem::take(&mut requests), next_cursor))
    }

    pub async fn session_page(&self, peer: &PeerKey, after: Option<ConversationId>, limit: u8, home: &str) -> Result<(Vec<ConversationMetadata>, Option<ConversationId>)> {
        let rows = if let Some(after) = after {
            sqlx::query_as::<_, (String,String,Option<String>,Option<String>,Option<String>,String,i64,i64,i64)>(
                "SELECT session_id,peer,pi_session_id,transcript_path,session_name,state,created_at_ms,last_user_message_at_ms,idle_ttl_seconds FROM sessions WHERE peer=? AND pi_session_id IS NOT NULL AND session_id>? ORDER BY session_id LIMIT ?",
            ).bind(peer.as_str()).bind(after.to_string()).bind(i64::from(limit) + 1).fetch_all(&self.pool).await?
        } else {
            sqlx::query_as::<_, (String,String,Option<String>,Option<String>,Option<String>,String,i64,i64,i64)>(
                "SELECT session_id,peer,pi_session_id,transcript_path,session_name,state,created_at_ms,last_user_message_at_ms,idle_ttl_seconds FROM sessions WHERE peer=? AND pi_session_id IS NOT NULL ORDER BY session_id LIMIT ?",
            ).bind(peer.as_str()).bind(i64::from(limit) + 1).fetch_all(&self.pool).await?
        };
        let has_more = rows.len() > usize::from(limit);
        let mut sessions = Vec::new();
        for row in rows.into_iter().take(usize::from(limit)) {
            let record = conversation_from_row(row, home)?;
            let configuration = self.conversation_configuration(peer.as_str(), record.id).await?;
            sessions.push(conversation_metadata(&record, configuration)?.context("persisted session has no Pi session metadata")?);
        }
        let next_cursor = if has_more { sessions.last().map(|session| session.conversation_id) } else { None };
        Ok((sessions, next_cursor))
    }

    pub async fn sync_events_after(&self, peer: &PeerKey, after: &SyncCursor, limit: u8) -> Result<(Vec<SyncEvent>, SyncCursor)> {
        let after_value = after.as_str().parse::<i64>().context("sync cursor exceeds SQLite range")?;
        // Read the compaction floor and event page from one SQLite snapshot. Otherwise
        // compaction could advance the floor and delete rows between these reads, making
        // a stale cursor appear valid while returning an incomplete page.
        let mut tx = self.pool.begin().await?;
        let floor: i64 = sqlx::query_scalar("SELECT cursor FROM sync_floor WHERE peer=?")
            .bind(peer.as_str()).fetch_optional(&mut *tx).await?.unwrap_or(0);
        anyhow::ensure!(after_value >= floor, "sync cursor was compacted; fresh snapshot required");
        let rows = sqlx::query_as::<_, (i64, String, Vec<u8>)>("SELECT cursor,kind,payload FROM sync_events WHERE peer=? AND cursor>? ORDER BY cursor LIMIT ?")
            .bind(peer.as_str()).bind(after_value).bind(i64::from(limit)).fetch_all(&mut *tx).await?;
        tx.commit().await?;
        let rows = rows.into_iter().map(|(cursor,kind,payload)| JournalEvent { cursor,kind,payload }).collect::<Vec<_>>();
        let mut events = Vec::with_capacity(rows.len());
        for row in &rows {
            let cursor = SyncCursor::parse(row.cursor.to_string())?;
            let event = ciborium::de::from_reader::<DurableEvent, _>(row.payload.as_slice())
                .context("decode durable event journal payload")?;
            events.push(SyncEvent { cursor, event });
        }
        let next = rows.last().map(|row| SyncCursor::parse(row.cursor.to_string())).transpose()?.unwrap_or_else(|| after.clone());
        Ok((events, next))
    }

    /// Delete only metadata-notification rows, never permanent request identities.
    /// Conversation recovery belongs to Pi, not this journal.
    pub async fn compact_journal(&self, peer: &PeerKey, keep_events: u32) -> Result<u64> {
        let keep_events = i64::from(keep_events.max(1));
        let mut tx = self.pool.begin_with("BEGIN IMMEDIATE").await?;
        let current: i64 = sqlx::query_scalar("SELECT cursor FROM peer_cursors WHERE peer=?")
            .bind(peer.as_str()).fetch_optional(&mut *tx).await?.unwrap_or(0);
        let floor = current.saturating_sub(keep_events);
        sqlx::query("INSERT INTO sync_floor(peer,cursor) VALUES(?,?) ON CONFLICT(peer) DO UPDATE SET cursor=MAX(sync_floor.cursor,excluded.cursor)")
            .bind(peer.as_str()).bind(floor).execute(&mut *tx).await?;
        let deleted = sqlx::query("DELETE FROM sync_events WHERE peer=? AND cursor<=?")
            .bind(peer.as_str()).bind(floor).execute(&mut *tx).await?.rows_affected();
        tx.commit().await?;
        Ok(deleted)
    }

    pub async fn journal_bounds(&self, peer: &PeerKey) -> Result<(i64, i64)> {
        let floor: i64 = sqlx::query_scalar("SELECT cursor FROM sync_floor WHERE peer=?")
            .bind(peer.as_str()).fetch_optional(&self.pool).await?.unwrap_or(0);
        let current: i64 = sqlx::query_scalar("SELECT cursor FROM peer_cursors WHERE peer=?")
            .bind(peer.as_str()).fetch_optional(&self.pool).await?.unwrap_or(0);
        Ok((floor, current))
    }

    pub async fn events_after(&self, peer: &PeerKey, after: i64, limit: i64) -> Result<Vec<JournalEvent>> {
        let rows = sqlx::query_as::<_, (i64, String, Vec<u8>)>("SELECT cursor,kind,payload FROM sync_events WHERE peer=? AND cursor>? ORDER BY cursor LIMIT ?")
            .bind(peer.as_str()).bind(after).bind(limit).fetch_all(&self.pool).await?;
        Ok(rows.into_iter().map(|(cursor,kind,payload)| JournalEvent { cursor,kind,payload }).collect())
    }

    async fn release_upload_pins_tx(tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>, peer: &PeerKey, request: &RequestKey) -> Result<()> {
        sqlx::query("UPDATE uploads SET active_references=active_references-1 WHERE upload_id IN (SELECT upload_id FROM upload_request_refs WHERE peer=? AND request_id=?) AND peer=? AND active_references>0")
            .bind(peer.as_str()).bind(request.as_str()).bind(peer.as_str()).execute(&mut **tx).await?;
        sqlx::query("DELETE FROM upload_request_refs WHERE peer=? AND request_id=?")
            .bind(peer.as_str()).bind(request.as_str()).execute(&mut **tx).await?;
        Ok(())
    }

    async fn append_event_tx(tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>, peer: &PeerKey, kind: &str, payload: &[u8], now_ms: i64) -> Result<i64> {
        sqlx::query("INSERT OR IGNORE INTO peer_cursors(peer,cursor) VALUES(?,0)").bind(peer.as_str()).execute(&mut **tx).await?;
        sqlx::query("UPDATE peer_cursors SET cursor=cursor+1 WHERE peer=?").bind(peer.as_str()).execute(&mut **tx).await?;
        let cursor: i64 = sqlx::query_scalar("SELECT cursor FROM peer_cursors WHERE peer=?").bind(peer.as_str()).fetch_one(&mut **tx).await?;
        sqlx::query("INSERT INTO sync_events(peer,cursor,kind,payload,created_at_ms) VALUES(?,?,?,?,?)")
            .bind(peer.as_str()).bind(cursor).bind(kind).bind(payload).bind(now_ms).execute(&mut **tx).await?;
        Ok(cursor)
    }
}

fn settings_from_row(row: (Option<i64>, Option<i64>)) -> Result<EffectiveSettings> {
    let idle = row.0
        .map(u32::try_from)
        .transpose()
        .context("stored idle timeout is invalid")?
        .map(IdleTimeoutSeconds::try_from)
        .transpose()?
        .unwrap_or(IdleTimeoutSeconds::try_from(DEFAULT_IDLE_TIMEOUT_SECONDS)?);
    let retention = row.1
        .map(u32::try_from)
        .transpose()
        .context("stored upload retention is invalid")?
        .map(UploadRetentionSeconds::try_from)
        .transpose()?
        .unwrap_or(UploadRetentionSeconds::try_from(DEFAULT_UPLOAD_RETENTION_SECONDS)?);
    Ok(EffectiveSettings { idle_timeout_seconds: idle, upload_retention_seconds: retention })
}

fn wire_request_outcome(raw: &str) -> Result<WireRequestOutcome> {
    match raw {
        "queued" => Ok(WireRequestOutcome::Queued),
        "running" => Ok(WireRequestOutcome::Running),
        "completed" => Ok(WireRequestOutcome::Completed),
        "failed" => Ok(WireRequestOutcome::Failed),
        "cancelled" => Ok(WireRequestOutcome::Cancelled),
        "uncertain" => Ok(WireRequestOutcome::Uncertain),
        "rejected" => Ok(WireRequestOutcome::Rejected),
        _ => anyhow::bail!("stored request outcome is invalid"),
    }
}

#[cfg(test)]
#[path = "push_storage_tests.rs"]
mod push_storage_tests;

fn device_from_row(row: (String, String, i64, Option<i64>, Option<i64>)) -> Result<Device> {
    let (endpoint, name, paired_at, last_seen, idle_ttl_seconds) = row;
    let idle_timeout_seconds = idle_ttl_seconds
        .map(u32::try_from)
        .transpose()
        .context("stored idle timeout is invalid")?
        .map(IdleTimeoutSeconds::try_from)
        .transpose()?;
    Ok(Device { endpoint, name, paired_at, last_seen, idle_timeout_seconds })
}

fn conversation_from_row(
    row: (String, String, Option<String>, Option<String>, Option<String>, String, i64, i64, i64),
    home: &str,
) -> Result<ConversationRecord> {
    let (session_id, endpoint, pi_session_id, transcript_path, session_name, state, created_at, last_user_prompt_at, idle_ttl_seconds) = row;
    let id = ConversationId::from(Uuid::parse_str(&session_id).context("stored conversation ID is invalid")?);
    let idle_timeout_seconds = IdleTimeoutSeconds::try_from(u32::try_from(idle_ttl_seconds).context("stored idle timeout is invalid")?)?;
    let state = match state.as_str() {
        "idle" => ConversationState::Idle,
        "busy" => ConversationState::Busy,
        "closed" => ConversationState::Closed,
        "expired" => ConversationState::Expired,
        "unavailable" => ConversationState::Unavailable,
        _ => anyhow::bail!("stored session state is invalid"),
    };
    Ok(ConversationRecord {
        id,
        endpoint,
        project: home.to_owned(),
        project_session_id: String::new(),
        session_id: pi_session_id,
        session_file: transcript_path.unwrap_or_default(),
        session_name,
        idle_timeout_seconds: Some(idle_timeout_seconds),
        created_at,
        last_user_prompt_at,
        state,
    })
}

async fn session_configuration(tx: &mut sqlx::Transaction<'_, sqlx::Sqlite>, peer: &str, conversation_id: ConversationId) -> Result<Option<kompi_protocol::ChatModelConfiguration>> {
    let json: Option<String> = sqlx::query_scalar("SELECT m.configuration FROM requests r JOIN request_models m ON m.peer=r.peer AND m.request_id=r.request_id WHERE r.peer=? AND r.session_id=? ORDER BY r.accepted_at_ms DESC,r.rowid DESC LIMIT 1")
        .bind(peer).bind(conversation_id.to_string()).fetch_optional(&mut **tx).await?;
    json.map(|json| serde_json::from_str(&json).context("invalid stored session model configuration")).transpose()
}

pub(crate) fn conversation_metadata(row: &ConversationRecord, configuration: Option<kompi_protocol::ChatModelConfiguration>) -> Result<Option<ConversationMetadata>> {
    let Some(session_id) = row.session_id.clone() else { return Ok(None) };
    let Some(idle_timeout_seconds) = row.idle_timeout_seconds else { return Ok(None) };
    Ok(Some(ConversationMetadata {
        configuration,
        session_id,
        conversation_id: row.id,
        state: match &row.state {
            ConversationState::Idle | ConversationState::Busy => WireConversationState::Active,
            ConversationState::Closed => WireConversationState::Closed,
            ConversationState::Expired => WireConversationState::Expired,
            ConversationState::Unavailable => WireConversationState::Unavailable,
        },
        created_at: timestamp(row.created_at)?,
        expires_at: timestamp(row.last_user_prompt_at.checked_add(i64::from(idle_timeout_seconds.get()) * 1000).context("session expiry timestamp overflow")?)?,
        idle_timeout_seconds,
        session_name: row.session_name.clone(),
    }))
}

fn timestamp(milliseconds: i64) -> Result<Timestamp> {
    let timestamp = jiff::Timestamp::from_millisecond(milliseconds)?;
    Ok(Timestamp::try_from(timestamp.to_string())?)
}

fn conversation_state_key(state: &ConversationState) -> &'static str {
    match state {
        ConversationState::Idle => "idle",
        ConversationState::Busy => "busy",
        ConversationState::Closed => "closed",
        ConversationState::Expired => "expired",
        ConversationState::Unavailable => "unavailable",
    }
}
