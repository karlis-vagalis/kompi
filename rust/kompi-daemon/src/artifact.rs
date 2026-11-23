use crate::wire::write_frame;
use anyhow::{Context, Result, bail, ensure};
use iroh::endpoint::SendStream;
use kompi_protocol::{
    ARTIFACT_MAX_BYTES, ATTACHMENT_CHUNK_MAX_BYTES, ArtifactId, AttachmentChunkData,
    ConversationId, Envelope, Message, OfferedArtifact, RequestId, is_safe_artifact_name,
    worker::{WorkerRequest, WorkerRequestEnvelope},
};
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    fs::OpenOptions,
    io::{ErrorKind, Read},
    os::unix::fs::OpenOptionsExt,
    path::{Path, PathBuf},
    sync::{
        Arc, LazyLock, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};
use tokio::{
    io::AsyncWriteExt,
    process::ChildStdin,
    sync::{OwnedSemaphorePermit, Semaphore},
    time::timeout,
};

const MAX_OFFERS: usize = 8;
const RETENTION: Duration = Duration::from_secs(60 * 60);

#[derive(Clone, Default)]
pub(super) struct ArtifactStore {
    state: Arc<Mutex<State>>,
}

#[derive(Default)]
struct State {
    offers: HashMap<ArtifactId, Arc<Artifact>>,
    // The shared pairing fence remains live while a bound worker or offer exists.
    pairings: HashMap<String, std::sync::Weak<AtomicBool>>,
}

#[derive(Clone)]
pub(super) struct Scope {
    peer: String,
    conversation: ConversationId,
    authorized: Arc<AtomicBool>,
}

pub(super) struct Artifact {
    pub(super) metadata: OfferedArtifact,
    pub(super) bytes: Vec<u8>,
    scope: Scope,
    expires: Instant,
    _slot: OwnedSemaphorePermit,
}

impl ArtifactStore {
    pub(super) fn scope(&self, peer: &str, conversation: ConversationId) -> Scope {
        let mut state = self.state.lock().expect("artifact store poisoned");
        state.pairings.retain(|_, entry| entry.strong_count() != 0);
        let authorized = state
            .pairings
            .get(peer)
            .and_then(std::sync::Weak::upgrade)
            .unwrap_or_else(|| {
                let authorized = Arc::new(AtomicBool::new(true));
                state
                    .pairings
                    .insert(peer.to_owned(), Arc::downgrade(&authorized));
                authorized
            });
        Scope {
            peer: peer.to_owned(),
            conversation,
            authorized,
        }
    }

    pub(super) fn revoke(&self, peer: &str) {
        let mut state = self.state.lock().expect("artifact store poisoned");
        if let Some(fence) = state
            .pairings
            .remove(peer)
            .and_then(|entry| entry.upgrade())
        {
            fence.store(false, Ordering::Release);
        }
        state.offers.retain(|_, offer| offer.scope.peer != peer);
    }

    pub(super) fn sweep(&self) {
        let mut state = self.state.lock().expect("artifact store poisoned");
        state
            .offers
            .retain(|_, offer| Instant::now() < offer.expires);
        state.pairings.retain(|_, entry| entry.strong_count() != 0);
    }

    pub(super) async fn offer(
        &self,
        scope: Scope,
        path: PathBuf,
        name: Option<String>,
    ) -> Result<OfferedArtifact> {
        ensure!(
            scope.authorized.load(Ordering::Acquire),
            "The phone is no longer paired."
        );
        ensure!(
            path.is_absolute(),
            "Provide an absolute path to a regular file."
        );
        let file_name = name
            .as_deref()
            .or_else(|| path.file_name().and_then(|name| name.to_str()))
            .filter(|name| is_safe_artifact_name(name))
            .context("Choose a safe filename (up to 120 ASCII letters, digits, spaces or ._()+-).")?
            .to_owned();
        self.sweep();
        // Hold a slot from before opening the source until all readers release their
        // snapshot, bounding memory even across expiry, revocation and slow readers.
        static SLOTS: LazyLock<Arc<Semaphore>> =
            LazyLock::new(|| Arc::new(Semaphore::new(MAX_OFFERS)));
        let slot = SLOTS
            .clone()
            .try_acquire_owned()
            .context("The host has too many file offers. Wait for an offer to expire.")?;
        let (bytes, sha256, slot) = tokio::task::spawn_blocking(move || {
            let bytes = snapshot(&path)?;
            let sha256 = hex::encode(Sha256::digest(&bytes));
            Ok::<_, anyhow::Error>((bytes, sha256, slot))
        })
        .await
        .context("Could not prepare the file offer.")??;
        let artifact_id = uuid::Uuid::new_v4().into();
        let metadata = OfferedArtifact {
            artifact_id,
            file_name,
            length: bytes.len() as u64,
            sha256,
        };
        let mut state = self.state.lock().expect("artifact store poisoned");
        ensure!(
            scope.authorized.load(Ordering::Acquire),
            "The phone is no longer paired."
        );
        state.offers.insert(
            artifact_id,
            Arc::new(Artifact {
                metadata: metadata.clone(),
                bytes,
                scope,
                expires: Instant::now() + RETENTION,
                _slot: slot,
            }),
        );
        Ok(metadata)
    }

    pub(super) fn get(
        &self,
        peer: &str,
        conversation: ConversationId,
        id: ArtifactId,
    ) -> Option<Arc<Artifact>> {
        self.state
            .lock()
            .expect("artifact store poisoned")
            .offers
            .get(&id)
            .filter(|offer| {
                offer.scope.peer == peer
                    && offer.scope.conversation == conversation
                    && Instant::now() < offer.expires
            })
            .cloned()
    }
}

impl Artifact {
    pub(super) fn authorized(&self) -> bool {
        self.scope.authorized.load(Ordering::Acquire)
    }
}

pub(super) async fn serve(
    mut send: SendStream,
    id: RequestId,
    artifact: Option<Arc<Artifact>>,
) -> Result<()> {
    async fn frame(send: &mut SendStream, id: &RequestId, message: Message) -> Result<()> {
        let payload = kompi_protocol::encode_cbor(&Envelope::new(id.clone(), message))?;
        write_frame(send, &payload).await
    }
    if let Some(artifact) = artifact.filter(|offer| offer.authorized()) {
        frame(
            &mut send,
            &id,
            Message::ArtifactReady {
                artifact: artifact.metadata.clone(),
            },
        )
        .await?;
        for bytes in artifact.bytes.chunks(ATTACHMENT_CHUNK_MAX_BYTES) {
            ensure!(artifact.authorized(), "file transfer authorization revoked");
            frame(
                &mut send,
                &id,
                Message::ArtifactData {
                    data: AttachmentChunkData::from_slice(bytes)?,
                },
            )
            .await?;
        }
        ensure!(artifact.authorized(), "file transfer authorization revoked");
        frame(&mut send, &id, Message::ArtifactEnd {}).await?;
    } else {
        frame(
            &mut send,
            &id,
            Message::Error {
                code: "artifact-unavailable".into(),
                message: "This file offer is no longer available. Ask for a new offer.".into(),
                session_id: None,
                conversation_id: None,
            },
        )
        .await?;
    }
    send.finish()?;
    Ok(())
}

pub(super) async fn reply_to_worker(
    store: ArtifactStore,
    scope: Scope,
    request_id: RequestId,
    path: PathBuf,
    name: Option<String>,
    writer: std::sync::Weak<tokio::sync::Mutex<Option<ChildStdin>>>,
) {
    let conversation_id = scope.conversation;
    let outcome = timeout(Duration::from_secs(20), store.offer(scope, path, name))
        .await
        .unwrap_or_else(|_| {
            Err(anyhow::anyhow!(
                "The host timed out while reading the file."
            ))
        })
        .and_then(|artifact| {
            serde_json::to_string(&artifact).context("Could not describe the file offer.")
        });
    let (success, result_json, error_code, error_message) = match outcome {
        Ok(json) => (true, Some(json), None, None),
        Err(err) => (
            false,
            None,
            Some("artifact-offer-failed".into()),
            Some(err.to_string()),
        ),
    };
    let Ok(id) = RequestId::parse(format!("artifact-reply-{}", uuid::Uuid::new_v4())) else {
        return;
    };
    let envelope: WorkerRequestEnvelope = Envelope::new(
        id,
        WorkerRequest::AndroidToolResponse {
            conversation_id,
            request_id,
            success,
            result_json,
            error_code,
            error_message,
        },
    );
    if let (Some(writer), Ok(bytes)) = (
        writer.upgrade(),
        kompi_protocol::encode_worker_json_line(&envelope),
    ) {
        // Serialize with other worker writes; do not cancel a partially written frame.
        if let Some(stdin) = writer.lock().await.as_mut() {
            let _ = tokio::time::timeout(std::time::Duration::from_secs(5), stdin.write_all(&bytes)).await;
        }
    }
}

fn snapshot(path: &Path) -> Result<Vec<u8>> {
    // Refuse a final symlink, and do not block in open() on a FIFO.
    let mut file = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK)
        .open(path)
        .context("Could not open the file. Choose a readable regular file, not a symbolic link.")?;
    let before = file
        .metadata()
        .context("Could not read file information.")?;
    ensure!(before.is_file(), "Only regular files can be offered.");
    ensure!(
        before.len() <= ARTIFACT_MAX_BYTES,
        "The file is larger than the 16 MiB limit."
    );
    let mut bytes = vec![0; before.len() as usize];
    file.read_exact(&mut bytes)
        .context("Could not read the complete file. Finish writing it before offering it.")?;
    loop {
        match file.read(&mut [0]) {
            Ok(0) => break,
            Ok(_) => {
                bail!("The file changed while being read. Finish writing it before offering it.")
            }
            Err(error) if error.kind() == ErrorKind::Interrupted => continue,
            Err(_) => bail!("Could not read the complete file."),
        }
    }
    let after = file
        .metadata()
        .context("Could not read file information.")?;
    use std::os::unix::fs::MetadataExt;
    ensure!(
        (
            before.len(),
            before.mtime(),
            before.mtime_nsec(),
            before.ctime(),
            before.ctime_nsec()
        ) == (
            after.len(),
            after.mtime(),
            after.mtime_nsec(),
            after.ctime(),
            after.ctime_nsec()
        ),
        "The file changed while being read. Finish writing it before offering it."
    );
    Ok(bytes)
}
