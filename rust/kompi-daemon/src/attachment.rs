use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256 as Sha256Hasher};
use kompi_protocol::{ATTACHMENT_CHUNK_MAX_BYTES, ATTACHMENT_MAX_FILE_BYTES, ATTACHMENT_MAX_PEER_BYTES, ATTACHMENT_MAX_PEER_ITEMS};
use std::{
    collections::HashMap,
    fs::{self, OpenOptions},
    path::{Path, PathBuf},
    sync::Arc,
};
use tokio::{io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt}, sync::{Mutex, OwnedMutexGuard}};

const HASH_CHUNK_SIZE: usize = 64 * 1024;

/// UUID validated at the protocol boundary and used only as an opaque upload identity.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(transparent)]
pub struct UploadId(uuid::Uuid);

impl UploadId {
    pub fn new(value: uuid::Uuid) -> Self { Self(value) }
    pub fn value(self) -> uuid::Uuid { self.0 }
}

/// A parsed lowercase SHA-256 digest. Its representation cannot contain malformed hex.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(transparent)]
pub struct ContentHash([u8; 32]);

impl ContentHash {
    pub fn parse(raw: &str) -> Result<Self> {
        if raw.len() != 64 || !raw.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) {
            bail!("sha256 must be 64 lowercase hexadecimal characters");
        }
        let mut bytes = [0u8; 32];
        hex::decode_to_slice(raw, &mut bytes).context("decode sha256")?;
        Ok(Self(bytes))
    }
    pub fn as_hex(&self) -> String { hex::encode(self.0) }
}

/// A safe, display-only filename; disk paths never use this value.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(transparent)]
pub struct FileName(String);

impl FileName {
    pub fn parse(raw: String) -> Result<Self> {
        if raw.is_empty() || raw.len() > 255 || raw == "." || raw == ".."
            || raw.chars().any(|c| c.is_control() || c == '/' || c == '\\')
        {
            bail!("fileName must be a safe basename of at most 255 UTF-8 bytes");
        }
        Ok(Self(raw))
    }
    pub fn as_str(&self) -> &str { &self.0 }
}

/// Advisory media type retained for the recipient, never used to interpret or execute data.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(transparent)]
pub struct MediaType(String);

impl MediaType {
    pub fn parse(raw: String) -> Result<Self> {
        if raw.len() > 127 || raw.chars().any(char::is_control) {
            bail!("mediaType exceeds 127 UTF-8 bytes or contains control characters");
        }
        if raw.is_empty() { return Ok(Self(raw)); }
        let parsed: mime::Mime = raw.parse().context("invalid advisory media type")?;
        let normalized = parsed.to_string();
        if normalized.len() > 127 { bail!("normalized mediaType exceeds 127 UTF-8 bytes"); }
        Ok(Self(normalized))
    }
    pub fn as_str(&self) -> &str { &self.0 }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Offer {
    pub upload_id: UploadId,
    pub file_name: FileName,
    pub media_type: MediaType,
    pub length: u64,
    pub sha256: ContentHash,
}

impl Offer {
    pub fn new(upload_id: UploadId, file_name: String, media_type: String, length: u64, sha256: &str) -> Result<Self> {
        if length > ATTACHMENT_MAX_FILE_BYTES { bail!("attachment exceeds the maximum file size"); }
        Ok(Self {
            upload_id,
            file_name: FileName::parse(file_name)?,
            media_type: MediaType::parse(media_type)?,
            length,
            sha256: ContentHash::parse(sha256)?,
        })
    }
}

#[derive(Clone, Debug)]
pub struct Ready { pub offset: u64, pub chunk_size: u32 }
#[derive(Clone, Debug)]
pub struct Progress { pub received_bytes: u64 }
#[derive(Clone, Debug)]
pub struct Accepted { pub offer: Offer }
#[derive(Clone, Debug)]
pub struct AttachmentRef { pub offer: Offer, pub path: PathBuf }

#[derive(Clone, Serialize, Deserialize)]
struct DiskOffer { offer: Offer }
#[derive(Clone, Serialize, Deserialize)]
struct DiskCompletion { offer: Offer, expires_at_ms: i64 }

/// Peer-scoped attachment storage. Fixed filenames and UUID-derived directories prevent
/// user-supplied paths from participating in filesystem resolution.
#[derive(Clone)]
pub struct AttachmentStore {
    root: PathBuf,
    sqlite: crate::sqlite_store::SqliteStore,
    peer_locks: Arc<Mutex<HashMap<String, Arc<Mutex<()>>>>>,
}

impl AttachmentStore {
    pub fn new(state_dir: &Path, sqlite: crate::sqlite_store::SqliteStore) -> Result<Self> {
        let root = state_dir.join("drop");
        ensure_private_dir(&root)?;
        Ok(Self { root, sqlite, peer_locks: Arc::new(Mutex::new(HashMap::new())) })
    }

    async fn record_completion(&self, peer: &str, offer: &Offer, path: &Path, expires_at_ms: i64) -> Result<()> {
        self.sqlite.register_upload(peer, &offer.upload_id.value().to_string(), &path.display().to_string(),
            offer.file_name.as_str(), offer.media_type.as_str(), offer.length, &offer.sha256.as_hex(),
            expires_at_ms, jiff::Timestamp::now().as_millisecond()).await
    }

    pub async fn cleanup_expired(&self) -> Result<usize> {
        let paths = self.sqlite.expire_uploads(jiff::Timestamp::now().as_millisecond()).await?;
        let mut removed = 0;
        for raw_path in paths {
            let path = PathBuf::from(raw_path);
            let Some(dir) = path.parent() else { continue };
            let Some(peer_dir) = dir.parent() else { continue };
            let Some(peer) = peer_dir.file_name().and_then(|name| name.to_str()) else { continue };
            let _guard = self.lock_peer(peer).await?;
            if path == dir.join("payload.blob") && dir.starts_with(&self.root) && path_exists(&path)? {
                ensure_real_dir(peer_dir)?;
                ensure_real_dir(dir)?;
                ensure_regular_file(&path)?;
                fs::remove_dir_all(dir)?;
                removed += 1;
            }
        }
        Ok(removed)
    }

    async fn lock_peer(&self, peer: &str) -> Result<OwnedMutexGuard<()>> {
        if peer.len() != 64 || !peer.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) {
            bail!("invalid authenticated peer endpoint");
        }
        let lock = {
            let mut locks = self.peer_locks.lock().await;
            locks.entry(peer.to_owned()).or_insert_with(|| Arc::new(Mutex::new(()))).clone()
        };
        Ok(lock.lock_owned().await)
    }

    fn peer_dir(&self, peer: &str) -> PathBuf { self.root.join(peer) }
    fn upload_dir(&self, peer: &str, id: UploadId) -> PathBuf { self.peer_dir(peer).join(id.value().to_string()) }

    /// A retry deliberately restarts at byte zero. Only exact metadata matches reuse the
    /// directory; mismatched partial state is discarded before the new offer is accepted.
    pub async fn offer(&self, peer: &str, offer: Offer) -> Result<Ready> {
        let _guard = self.lock_peer(peer).await?;
        let peer_dir = self.peer_dir(peer);
        ensure_private_dir(&peer_dir)?;
        let dir = self.upload_dir(peer, offer.upload_id);
        if path_exists(&dir)? {
            ensure_private_dir(&dir)?;
            let complete = dir.join("complete.json");
            if path_exists(&complete)? {
                let completion = read_completion(&complete)?;
                let completed = completion.offer;
                if completed != offer { bail!("completed upload UUID has different metadata"); }
                let blob = dir.join("payload.blob");
                verify_blob(&blob, &completed)?;
                let upload_id = offer.upload_id.value().to_string();
                let record = self.sqlite.upload_record(peer, &upload_id, jiff::Timestamp::now().as_millisecond()).await?;
                let Some((raw_path, file_name, media_type, length, sha256)) = record else {
                    bail!("completed attachment is expired or unavailable in SQLite");
                };
                let stored_offer = Offer::new(completed.upload_id, file_name, media_type, u64::try_from(length)?, &sha256)?;
                if stored_offer != completed || PathBuf::from(raw_path) != blob {
                    bail!("SQLite attachment metadata does not match the completed upload");
                }
                if completion.expires_at_ms <= jiff::Timestamp::now().as_millisecond() {
                    bail!("completed attachment is expired");
                }
                return Ok(Ready { offset: offer.length, chunk_size: ATTACHMENT_CHUNK_MAX_BYTES as u32 });
            }
            let meta = dir.join("offer.json");
            let prior = if path_exists(&meta)? { Some(read_offer(&meta)?) } else { None };
            if prior.as_ref() != Some(&offer) {
                fs::remove_dir_all(&dir).context("remove mismatched partial upload")?;
            } else {
                ensure_peer_quota(&peer_dir, offer.length, Some(&dir))?;
                let partial = dir.join("payload.part");
                if path_exists(&partial)? { ensure_regular_file(&partial)?; fs::remove_file(&partial)?; }
                let file = create_private_file(&partial)?;
                file.sync_all()?;
                return Ok(Ready { offset: 0, chunk_size: ATTACHMENT_CHUNK_MAX_BYTES as u32 });
            }
        }
        ensure_peer_quota(&peer_dir, offer.length, None)?;
        ensure_private_dir(&dir)?;
        let partial = dir.join("payload.part");
        let file = create_private_file(&partial)?;
        file.sync_all()?;
        write_offer(&dir.join("offer.json"), &offer)?;
        Ok(Ready { offset: 0, chunk_size: ATTACHMENT_CHUNK_MAX_BYTES as u32 })
    }

    /// Writes one bounded chunk and applies backpressure by completing the disk write before
    /// returning progress. A caller must not spawn a detached task for this operation.
    pub async fn append(&self, peer: &str, id: UploadId, offset: u64, data: &[u8]) -> Result<Progress> {
        let _guard = self.lock_peer(peer).await?;
        if data.is_empty() || data.len() > ATTACHMENT_CHUNK_MAX_BYTES { bail!("attachment chunk exceeds its protocol bound"); }
        let dir = self.upload_dir(peer, id);
        ensure_existing_private_dir(&dir)?;
        if path_exists(&dir.join("complete.json"))? { bail!("attachment is already complete"); }
        let offer = read_offer(&dir.join("offer.json"))?;
        let path = dir.join("payload.part");
        ensure_regular_file(&path)?;
        let mut file = tokio::fs::File::from_std(crate::private_fs::open_file(&path, OpenOptions::new().read(true).write(true))?);
        let actual = file.metadata().await?.len();
        if actual != offset { bail!("attachment chunk offset is not contiguous"); }
        let end = offset.checked_add(data.len() as u64).context("attachment offset overflow")?;
        if end > offer.length { bail!("attachment chunk exceeds offered length"); }
        file.seek(std::io::SeekFrom::Start(offset)).await?;
        file.write_all(data).await?;
        file.sync_data().await?;
        Ok(Progress { received_bytes: end })
    }

    /// Hashes and checks the completed stream before publishing it atomically without ever
    /// replacing an existing file. Completed data is retained across TTL/session closure.
    pub async fn finish(&self, peer: &str, id: UploadId) -> Result<Accepted> {
        let _guard = self.lock_peer(peer).await?;
        let dir = self.upload_dir(peer, id);
        ensure_existing_private_dir(&dir)?;
        let offer_path = dir.join("offer.json");
        let offer = read_offer(&offer_path)?;
        let complete = dir.join("complete.json");
        if path_exists(&complete)? {
            let completed_offer = read_completion(&complete)?.offer;
            if completed_offer != offer { bail!("completed attachment metadata does not match its offer"); }
            let blob = dir.join("payload.blob");
            verify_blob(&blob, &completed_offer)?;
            let completion = read_completion(&complete)?;
            let expires_at_ms = completion.expires_at_ms;
            let record = self.sqlite.upload_record(peer, &completed_offer.upload_id.value().to_string(),
                jiff::Timestamp::now().as_millisecond()).await?;
            if record.is_none() { bail!("completed attachment is expired or unavailable in SQLite"); }
            self.record_completion(peer, &completed_offer, &blob, expires_at_ms).await?;
            return Ok(Accepted { offer: completed_offer });
        }
        let part = dir.join("payload.part");
        ensure_regular_file(&part)?;
        let mut file = tokio::fs::File::from_std(crate::private_fs::open_file(&part, OpenOptions::new().read(true))?);
        let actual_len = file.metadata().await?.len();
        if actual_len != offer.length { bail!("attachment byte count does not match offer"); }
        let mut hasher = Sha256Hasher::new();
        let mut buf = vec![0u8; HASH_CHUNK_SIZE];
        loop {
            let count = file.read(&mut buf).await?;
            if count == 0 { break; }
            hasher.update(&buf[..count]);
        }
        let digest: [u8; 32] = hasher.finalize().into();
        if digest != offer.sha256.0 { bail!("attachment sha256 does not match offer"); }
        let blob = dir.join("payload.blob");
        // Publish only after the digest and length are verified.
        let completed_at_ms = jiff::Timestamp::now().as_millisecond();
        let expires_at_ms = self.sqlite.upload_expiry(peer, completed_at_ms).await?;
        let intent = dir.join("completion.intent.json");
        write_completion(&intent, &offer, expires_at_ms)?;
        drop(file);
        publish_new(&part, &blob).context("publish verified attachment without overwrite")?;
        write_completion(&dir.join("complete.json"), &offer, expires_at_ms)?;
        fs::remove_file(&intent)?;
        sync_directory(&dir)?;
        self.record_completion(peer, &offer, &blob, expires_at_ms).await?;
        Ok(Accepted { offer })
    }

    /// Resolve an upload only through the durable reference acquired atomically with its
    /// request claim. Expiry cannot remove this file until the request reaches a terminal state.
    pub async fn lookup_pinned(&self, peer: &str, request: &crate::sqlite_store::RequestKey, id: UploadId) -> Result<AttachmentRef> {
        let _guard = self.lock_peer(peer).await?;
        let dir = self.upload_dir(peer, id);
        ensure_existing_private_dir(&dir)?;
        let disk_offer = read_completion(&dir.join("complete.json"))?.offer;
        let id_text = disk_offer.upload_id.value().to_string();
        let Some((raw_path, file_name, media_type, length, sha256)) = self.sqlite
            .pinned_upload_record(&crate::sqlite_store::PeerKey::try_from(peer.to_owned())?, request, &id_text).await? else {
            bail!("attachment is not pinned to this request");
        };
        let offer = Offer::new(disk_offer.upload_id, file_name, media_type, u64::try_from(length)?, &sha256)?;
        if offer != disk_offer { bail!("SQLite attachment metadata does not match the published upload"); }
        let path = PathBuf::from(raw_path);
        if path != dir.join("payload.blob") { bail!("SQLite attachment path does not match its upload identity"); }
        ensure_regular_file(&path)?;
        Ok(AttachmentRef { offer, path })
    }

    /// Recover a crash after blob publication, then remove every unfinished partial upload.
    /// Completed blobs are retained, irrespective of conversation TTL.
    pub fn recover_and_cleanup_partials(&self) -> Result<usize> {
        let mut removed = 0;
        for peer in fs::read_dir(&self.root)? {
            let peer = peer?;
            if !peer.file_type()?.is_dir() { continue; }
            let peer_path = peer.path();
            ensure_real_dir(&peer_path)?;
            for upload in fs::read_dir(&peer_path)? {
                let upload = upload?;
                if !upload.file_type()?.is_dir() { continue; }
                let path = upload.path();
                ensure_real_dir(&path)?;
                let complete_path = path.join("complete.json");
                let offer_path = path.join("offer.json");
                let blob = path.join("payload.blob");
                if path_exists(&blob)? && (path_exists(&complete_path)? || path_exists(&offer_path)?) {
                    let offer = if path_exists(&complete_path)? { read_completion(&complete_path)?.offer } else { read_offer(&offer_path)? };
                    verify_blob(&blob, &offer)?;
                    let peer_name = peer_path.file_name().and_then(|name| name.to_str()).context("invalid attachment peer directory")?;
                    let intent_path = path.join("completion.intent.json");
                    let expires_at_ms = if path_exists(&complete_path)? {
                        read_completion(&complete_path)?.expires_at_ms
                    } else if path_exists(&intent_path)? {
                        read_completion(&intent_path)?.expires_at_ms
                    } else {
                        // A published blob with no completion intent is the interrupted
                        // blob-and-offer case; anchor its recovered expiry at stable blob mtime.
                        let at = file_modified_ms(&blob)?;
                        tokio::task::block_in_place(|| tokio::runtime::Handle::current().block_on(self.sqlite.upload_expiry(peer_name, at)))?
                    };
                    if !path_exists(&complete_path)? {
                        write_completion(&complete_path, &offer, expires_at_ms)?;
                        sync_directory(&path)?;
                    }
                    if path_exists(&intent_path)? { fs::remove_file(&intent_path)?; }
                    let now_ms = jiff::Timestamp::now().as_millisecond();
                    if expires_at_ms <= now_ms {
                        let _guard = tokio::task::block_in_place(|| {
                            tokio::runtime::Handle::current().block_on(self.lock_peer(peer_name))
                        })?;
                        let upload_id = offer.upload_id.value().to_string();
                        let pinned = tokio::task::block_in_place(|| {
                            tokio::runtime::Handle::current().block_on(self.sqlite.upload_has_active_references(peer_name, &upload_id))
                        })?;
                        if !pinned {
                            ensure_real_dir(&path)?;
                            fs::remove_dir_all(&path)?;
                            removed += 1;
                            continue;
                        }
                    } else {
                        tokio::task::block_in_place(|| tokio::runtime::Handle::current().block_on(self.record_completion(peer_name, &offer, &blob, expires_at_ms)))?;
                    }
                    if path_exists(&path.join("payload.part"))? { fs::remove_file(path.join("payload.part"))?; }
                } else {
                    fs::remove_dir_all(&path)?;
                    removed += 1;
                }
            }
        }
        Ok(removed)
    }
}

fn ensure_peer_quota(peer_dir: &Path, requested: u64, exclude: Option<&Path>) -> Result<()> {
    let mut used = 0u64;
    let mut items = 0usize;
    for entry in fs::read_dir(peer_dir)? {
        let entry = entry?;
        if !entry.file_type()?.is_dir() { continue; }
        let dir = entry.path();
        ensure_real_dir(&dir)?;
        let Some(name) = dir.file_name().and_then(|name| name.to_str()) else { bail!("invalid upload directory"); };
        let parsed = uuid::Uuid::parse_str(name).context("invalid upload directory name")?;
        if parsed.to_string() != name { bail!("invalid upload directory name"); }
        if exclude.is_some_and(|excluded| excluded == dir.as_path()) { continue; }
        items = items.checked_add(1).context("peer attachment item count overflow")?;
        let blob = dir.join("payload.blob");
        let complete = dir.join("complete.json");
        if path_exists(&complete)? {
            if !path_exists(&blob)? { bail!("completed attachment is missing its blob"); }
            ensure_regular_file(&blob)?;
            used = used.checked_add(fs::metadata(blob)?.len()).context("peer quota overflow")?;
        } else {
            let metadata = dir.join("offer.json");
            let partial = dir.join("payload.part");
            let reserved = if path_exists(&metadata)? { read_offer(&metadata)?.length }
                else if path_exists(&partial)? { ensure_regular_file(&partial)?; fs::metadata(partial)?.len() }
                else { 0 };
            used = used.checked_add(reserved).context("peer quota overflow")?;
        }
    }
    let new_items = usize::from(exclude.is_none());
    if items.checked_add(new_items).context("peer item count overflow")? > ATTACHMENT_MAX_PEER_ITEMS { bail!("peer attachment item limit exceeded"); }
    if used.checked_add(requested).context("peer quota overflow")? > ATTACHMENT_MAX_PEER_BYTES { bail!("peer attachment storage quota exceeded"); }
    Ok(())
}

fn read_metadata(path: &Path) -> Result<Vec<u8>> {
    use std::io::Read;
    ensure_regular_file(path)?;
    let file = crate::private_fs::open_file(path, OpenOptions::new().read(true))?;
    let mut bytes = Vec::new();
    file.take(2049).read_to_end(&mut bytes)?;
    if bytes.len() > 2048 { bail!("invalid attachment metadata size"); }
    Ok(bytes)
}

fn read_offer(path: &Path) -> Result<Offer> {
    let bytes = read_metadata(path)?;
    let disk = serde_json::from_slice::<DiskOffer>(&bytes)?;
    let offer = Offer::new(disk.offer.upload_id, disk.offer.file_name.0, disk.offer.media_type.0,
        disk.offer.length, &disk.offer.sha256.as_hex())?;
    let expected_id = offer.upload_id.value().to_string();
    if path.parent().and_then(Path::file_name).and_then(|name| name.to_str())
        != Some(expected_id.as_str())
    {
        bail!("attachment metadata upload ID does not match its directory");
    }
    Ok(offer)
}

fn verify_blob(path: &Path, offer: &Offer) -> Result<()> {
    ensure_regular_file(path)?;
    let mut file = crate::private_fs::open_file(path, OpenOptions::new().read(true))?;
    if file.metadata()?.len() != offer.length { bail!("published attachment length does not match offer"); }
    let mut hasher = Sha256Hasher::new();
    let mut buf = vec![0u8; HASH_CHUNK_SIZE];
    loop {
        use std::io::Read;
        let count = file.read(&mut buf)?;
        if count == 0 { break; }
        hasher.update(&buf[..count]);
    }
    let digest: [u8; 32] = hasher.finalize().into();
    if digest != offer.sha256.0 { bail!("published attachment hash does not match offer"); }
    Ok(())
}

fn read_completion(path: &Path) -> Result<DiskCompletion> {
    let bytes = read_metadata(path)?;
    let completion = serde_json::from_slice::<DiskCompletion>(&bytes)
        .context("completed attachment metadata must include its fixed expiry")?;
    let parsed = Offer::new(completion.offer.upload_id, completion.offer.file_name.0.clone(),
        completion.offer.media_type.0.clone(), completion.offer.length, &completion.offer.sha256.as_hex())?;
    if parsed != completion.offer { bail!("invalid completed attachment metadata"); }
    Ok(completion)
}

fn file_modified_ms(path: &Path) -> Result<i64> {
    let millis = fs::metadata(path)?.modified()?.duration_since(std::time::UNIX_EPOCH)
        .context("attachment completion time predates Unix epoch")?.as_millis();
    i64::try_from(millis).context("attachment completion time exceeds SQLite range")
}

fn write_completion(path: &Path, offer: &Offer, expires_at_ms: i64) -> Result<()> {
    let parent = path.parent().context("metadata path has no parent")?;
    let tmp = parent.join(format!(".metadata-{}.tmp", uuid::Uuid::new_v4()));
    let mut file = create_private_file(&tmp)?;
    serde_json::to_writer(&mut file, &DiskCompletion { offer: offer.clone(), expires_at_ms })?;
    file.sync_all()?;
    drop(file);
    publish_new(&tmp, path).context("publish attachment completion without overwrite")?;
    sync_directory(parent)?;
    Ok(())
}

fn write_offer(path: &Path, offer: &Offer) -> Result<()> {
    let parent = path.parent().context("metadata path has no parent")?;
    let tmp = parent.join(format!(".metadata-{}.tmp", uuid::Uuid::new_v4()));
    let mut file = create_private_file(&tmp)?;
    serde_json::to_writer(&mut file, &DiskOffer { offer: offer.clone() })?;
    file.sync_all()?;
    // Never replace an unexpected file (or symlink) at the destination.
    drop(file);
    publish_new(&tmp, path).context("publish attachment metadata without overwrite")?;
    sync_directory(parent)?;
    Ok(())
}

#[cfg(test)]
mod publish_tests {
    use super::*;

    #[test]
    fn publishing_never_replaces_an_existing_blob() {
        let dir = std::env::temp_dir().join(uuid::Uuid::new_v4().to_string());
        fs::create_dir(&dir).unwrap();
        let part = dir.join("payload.part");
        let blob = dir.join("payload.blob");
        fs::write(&part, b"original").unwrap();
        publish_new(&part, &blob).unwrap();
        assert!(!part.exists());
        fs::write(&part, b"retry").unwrap();
        assert!(publish_new(&part, &blob).is_err());
        assert_eq!(fs::read(&blob).unwrap(), b"original");
        fs::remove_dir_all(dir).unwrap();
    }
}

#[cfg(unix)]
fn publish_new(from: &Path, to: &Path) -> std::io::Result<()> {
    fs::hard_link(from, to)?;
    fs::remove_file(from)
}

#[cfg(windows)]
fn publish_new(from: &Path, to: &Path) -> std::io::Result<()> {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::Storage::FileSystem::{MoveFileExW, MOVEFILE_WRITE_THROUGH};
    // Both names are generated inside the same private upload directory; omitting
    // REPLACE_EXISTING and COPY_ALLOWED requires a same-volume no-clobber rename.
    let wide = |p: &Path| p.as_os_str().encode_wide().chain(Some(0)).collect::<Vec<_>>();
    if unsafe { MoveFileExW(wide(from).as_ptr(), wide(to).as_ptr(), MOVEFILE_WRITE_THROUGH) } == 0 {
        return Err(std::io::Error::last_os_error());
    }
    Ok(())
}

fn create_private_file(path: &Path) -> Result<fs::File> {
    Ok(crate::private_fs::open_file(path, OpenOptions::new().write(true).create_new(true))?)
}

fn ensure_private_dir(path: &Path) -> Result<()> {
    match fs::symlink_metadata(path) {
        Ok(meta) if meta.file_type().is_dir() => { crate::private_fs::private_dir(path)?; Ok(()) }
        Ok(_) => bail!("attachment storage path is not a real directory"),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            crate::private_fs::private_dir(path)?;
            Ok(())
        }
        Err(e) => Err(e.into()),
    }
}

fn ensure_existing_private_dir(path: &Path) -> Result<()> {
    ensure_real_dir(path)?;
    crate::private_fs::private_dir(path)?;
    Ok(())
}
fn ensure_real_dir(path: &Path) -> Result<()> {
    #[cfg(windows)]
    let _pinned = crate::pi_history::windows::pin_directory(&std::path::absolute(path)?)?;
    let meta = fs::symlink_metadata(path)?;
    if !meta.file_type().is_dir() { bail!("attachment path is not a real directory"); }
    Ok(())
}
fn ensure_regular_file(path: &Path) -> Result<()> {
    let meta = fs::symlink_metadata(path)?;
    if !meta.file_type().is_file() { bail!("attachment path is not a regular file"); }
    crate::private_fs::private_file(path)?;
    Ok(())
}
fn path_exists(path: &Path) -> Result<bool> {
    match fs::symlink_metadata(path) {
        Ok(_) => Ok(true),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(false),
        Err(e) => Err(e.into()),
    }
}
// Windows exposes no unprivileged directory fsync; payload and metadata files are flushed before publication.
#[cfg(windows)]
fn sync_directory(_: &Path) -> Result<()> { Ok(()) }
#[cfg(unix)]
fn sync_directory(path: &Path) -> Result<()> { fs::File::open(path)?.sync_all()?; Ok(()) }
