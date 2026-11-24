#[path = "attachment.rs"]
mod attachment;
#[path = "artifact.rs"]
mod artifact;
#[path = "pi_history_transport.rs"]
mod pi_history_transport;

use crate::{
    android_tool_route::{ConsumeResult, PendingAndroidTools},
    device_notification::{NotificationReply, PendingDeviceNotifications},
    lifecycle::{Connection, Identified, Ready, Unverified},
    registry::{EndpointId, PeerRegistry},
    store::{ConversationState, Device, HostIdentity, now_ms},
    sqlite_store::SqliteStore,
    session_actor::{PhoneSessionOwner, RuntimeGeneration},
    wire::{read_frame, write_frame},
};
#[cfg(unix)]
use std::os::unix::fs::{FileTypeExt, PermissionsExt};
use anyhow::{Context, Result, bail};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};

use kompi_protocol::{
    ALPN, ATTACHMENTS_CAPABILITY, DEVICE_NOTIFICATIONS_CAPABILITY, UNIFIED_PUSH_CAPABILITY, PendingPiUiRequest, PiUiAnswer, UiInteractionId, ANDROID_TOOL_TIMEOUT_MS, ATTACHMENT_MAX_FILES_PER_MESSAGE, DEVICE_NOTIFICATION_TIMEOUT_MS, DeviceNotificationReceipt, DeviceNotificationStatus, DurableEvent, Envelope, Message, PairingPayload, PushRegistration, PushRegistrationId, PushRegistrationStatus, PushRegistrationErrorCode, RequestId,
    RequestOutcome as WireRequestOutcome, RequestSummary,
    control::{ControlEnvelope, ControlMessage, DeviceSummary},
    worker::{WorkerAttachmentRef, WorkerEvent, WorkerRequest, WorkerRequestEnvelope},
};
use iroh::{
    Endpoint, Watcher,
    endpoint::{Connection as IrohConnection, QuicTransportConfig, RecvStream, SendStream, presets},
};
use iroh_tickets::endpoint::EndpointTicket;
use rand::RngCore;
use sha2::{Digest, Sha256};
use std::{
    collections::{HashMap, HashSet, VecDeque},
    fs,
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU8, AtomicU64, Ordering},
    },
    time::Duration,
};
use tokio::{
    io::AsyncWriteExt,
    sync::{mpsc, oneshot, watch},
    time::timeout,
};

use kompi_protocol::ChatTimingStage as Timing;
use crate::chat_timing::{ChatTiming, Trace};

mod chat_stream;
mod control_listener;

fn timing(trace: &Option<Trace>, stage: Timing) {
    if let Some(trace) = trace { trace.record(stage); }
}

fn audit_ack_write(trace: &Option<Trace>, sent: bool) {
    timing(trace, if sent { Timing::DaemonAckWritten } else { Timing::DaemonAckWriteFailed });
}

const RPC_UI_CAPABILITY: &str = "pi-rpc-ui";
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);
const CONTROL_FRAME_TIMEOUT: Duration = Duration::from_secs(120);
const MAX_CONNECTIONS: usize = 32;
const INTERACTIVE_STREAM_PRIORITY: i32 = 1;
const BULK_STREAM_PRIORITY: i32 = 0;
const MAX_CHAT_STREAMS_PER_CONNECTION: usize = 4;
const MAX_PROMPT_HANDLERS_PER_CHAT: usize = 16;
const PAIR_TTL_MS: i64 = 5 * 60 * 1000;
const CAPABILITIES: &[&str] = &[
    ATTACHMENTS_CAPABILITY,
    kompi_protocol::ARTIFACT_DOWNLOADS_CAPABILITY,
    RPC_UI_CAPABILITY,
    "prompt",
    "cancel",
    "status",
    "session-state",
    "multi-session",
    "concurrent-conversations",
    kompi_protocol::CHAT_STREAMS_CAPABILITY,
    "phone-conversations",
    "pi-session-events",
    kompi_protocol::CHAT_TIMING_CAPABILITY,
    kompi_protocol::PI_ARCHIVE_CAPABILITY,
    kompi_protocol::PI_HOST_HISTORY_CAPABILITY,
    kompi_protocol::CONVERSATION_CONTINUE_CAPABILITY,
    DEVICE_NOTIFICATIONS_CAPABILITY,
    kompi_protocol::RICH_DEVICE_NOTIFICATIONS_CAPABILITY,
    kompi_protocol::REPLY_NOTIFICATIONS_CAPABILITY,
    UNIFIED_PUSH_CAPABILITY,
];

#[derive(Clone)]
struct PairCredential {
    name: String,
    expires_at: i64,
}
type ChatStreamRoutes = Arc<ChatConnectionLimits>;

struct ChatConnectionLimits {
    active: Mutex<HashSet<kompi_protocol::ConversationId>>,
    commands: Arc<tokio::sync::Semaphore>,
    lifecycle: Arc<tokio::sync::Semaphore>,
}
impl Default for ChatConnectionLimits {
    fn default() -> Self {
        Self {
            active: Default::default(),
            commands: Arc::new(tokio::sync::Semaphore::new(MAX_CHAT_STREAMS_PER_CONNECTION * (MAX_PROMPT_HANDLERS_PER_CHAT + 4))),
            lifecycle: Arc::new(tokio::sync::Semaphore::new(MAX_CHAT_STREAMS_PER_CONNECTION * 4)),
        }
    }
}
struct ChatStreamRoute {
    routes: ChatStreamRoutes,
    conversation: kompi_protocol::ConversationId,
}
impl ChatStreamRoute {
    fn claim(routes: ChatStreamRoutes, conversation: kompi_protocol::ConversationId) -> Option<Self> {
        {
            let mut active = routes.active.lock().expect("chat routes poisoned");
            if active.len() >= MAX_CHAT_STREAMS_PER_CONNECTION || !active.insert(conversation) { return None; }
        }
        Some(Self { routes, conversation })
    }
}
impl Drop for ChatStreamRoute {
    fn drop(&mut self) {
        self.routes.active.lock().expect("chat routes poisoned").remove(&self.conversation);
    }
}

struct ReceivedControl {
    frame: Vec<u8>,
    received_at: std::time::Instant,
    wall_ms: i64,
}

#[derive(Clone)]
struct ActivePhoneSender {
    generation: u64,
    sender: crate::lifecycle::ReadySender,
    device_notification_capable: bool,
    rich_notification_capable: bool,
    reply_notification_capable: bool,
}

enum NotificationDispatch {
    Agent(kompi_protocol::AgentNotification),
    Reply(kompi_protocol::ReplyNotification),
}

impl NotificationDispatch {
    fn agent(&self) -> Option<&kompi_protocol::AgentNotification> {
        match self { Self::Agent(value) => Some(value), Self::Reply(_) => None }
    }
}

struct PromptLane {
    owner: PhoneSessionOwner,
    admission: tokio::sync::Mutex<()>,
    execution: tokio::sync::Mutex<()>,
    dispatch: tokio::sync::Mutex<()>,
    pending: Mutex<VecDeque<AcceptedPrompt>>,
}

struct AcceptedPrompt {
    id: RequestId,
    trace: Option<Trace>,
    key: crate::sqlite_store::RequestKey,
    conversation: crate::store::ConversationRecord,
    generation: RuntimeGeneration,
    input: PromptInput,
    started_at: std::time::Instant,
    notify: mpsc::Sender<Envelope<Message>>,
    reply_notification_capable: bool,
}

#[derive(Clone, Copy)]
enum PhoneCompletion { Completed, Cancelled, Uncertain, Failed }

struct PromptInput {
    text: String,
    delivery: kompi_protocol::Delivery,
    configuration: kompi_protocol::ChatModelConfiguration,
    attachments: Option<Vec<WorkerAttachmentRef>>,
}

impl PromptInput {
    fn can_follow_up(&self) -> bool {
        // Pi runs slash commands through prompt(), not through its native queue.
        // Keep them as FIFO barriers rather than reinterpret them as user text.
        !self.text.trim_start().starts_with('/')
    }
}

impl PromptLane {
    fn new() -> Self {
        Self {
            owner: PhoneSessionOwner::spawn(),
            admission: Default::default(),
            execution: Default::default(),
            dispatch: Default::default(),
            pending: Default::default(),
        }
    }

    async fn take_batch(&self, conversation: kompi_protocol::ConversationId, generation: RuntimeGeneration) -> VecDeque<AcceptedPrompt> {
        let _admission = self.admission.lock().await;
        let mut pending = self.pending.lock().expect("prompt lane poisoned");
        let Some(first) = pending.front().filter(|first| first.input.can_follow_up()) else { return VecDeque::new(); };
        let count = pending.iter().take_while(|prompt| {
            prompt.conversation.id == conversation && prompt.generation == generation
                && prompt.input.can_follow_up() && prompt.input.configuration == first.input.configuration
        }).count();
        pending.drain(..count).collect()
    }
}

#[derive(Clone)]
pub struct Daemon {
    sqlite: SqliteStore,
    chat_timing: Option<ChatTiming>,
    endpoint: Arc<Endpoint>,
    registry: PeerRegistry,
    credentials: Arc<Mutex<HashMap<String, PairCredential>>>,
    worker: Arc<WorkerPool>,
    control_path: PathBuf,
    next_connection: Arc<AtomicU64>,
    running: watch::Sender<bool>,
    active_physical: Arc<Mutex<HashMap<String, (u64, IrohConnection)>>>,
    active_senders: Arc<Mutex<HashMap<String, ActivePhoneSender>>>,
    pending_device_notifications: Arc<Mutex<PendingDeviceNotifications>>,
    prompt_lanes: Arc<Mutex<HashMap<(String, kompi_protocol::ConversationId), Arc<PromptLane>>>>,
    peer_dispatch_locks: Arc<Mutex<HashMap<String, Arc<tokio::sync::Mutex<()>>>>>,
    runtime_authorizations: Arc<Mutex<HashMap<String, Arc<tokio::sync::RwLock<()>>>>>,
    permits: Arc<tokio::sync::Semaphore>,
    control_permits: Arc<tokio::sync::Semaphore>,
    secondary_stream_permits: Arc<tokio::sync::Semaphore>,
    attachments: attachment::AttachmentStore,
    push_storage_key: Arc<zeroize::Zeroizing<[u8; 32]>>,
    push_policy_lock: Arc<tokio::sync::Mutex<()>>,
}

impl Daemon {
    async fn new(store: HostIdentity, sqlite: SqliteStore, running: watch::Sender<bool>) -> Result<Self> {
        let seed = store.identity_seed()?;
        let secret = iroh::SecretKey::from_bytes(&seed);
        let mut push_storage_key = [0u8; 32];
        hkdf::Hkdf::<Sha256>::new(Some(b"kompi-push-storage-v1"), &seed)
            .expand(b"registration-secrets", &mut push_storage_key)
            .map_err(|_| anyhow::anyhow!("derive push storage key"))?;
        let endpoint = Endpoint::builder(presets::N0)
            .secret_key(secret)
            .transport_config(QuicTransportConfig::builder().send_fairness(true).build())
            .alpns(vec![ALPN.as_bytes().to_vec()])
            .bind()
            .await
            .context("bind official Iroh endpoint")?;
        let worker = WorkerPool::new();
        let control_path = control_socket_path(store.directory());
        let attachments = attachment::AttachmentStore::new(store.directory(), sqlite.clone())?;
        attachments.recover_and_cleanup_partials()?;
        attachments.cleanup_expired().await?;
        let chat_timing = match ChatTiming::open(store.directory()).await {
            Ok(audit) => Some(audit),
            Err(_) => { eprintln!("Kompi chat timing: audit storage unavailable"); None }
        };
        Ok(Self {
            sqlite,
            chat_timing,
            endpoint: Arc::new(endpoint),
            registry: PeerRegistry::default(),
            credentials: Arc::new(Mutex::new(HashMap::new())),
            worker: Arc::new(worker),
            control_path,
            next_connection: Arc::new(AtomicU64::new(0)),
            running,
            active_physical: Arc::new(Mutex::new(HashMap::new())),
            active_senders: Arc::new(Mutex::new(HashMap::new())),
            pending_device_notifications: Arc::new(Mutex::new(PendingDeviceNotifications::default())),
            prompt_lanes: Arc::new(Mutex::new(HashMap::new())),
            peer_dispatch_locks: Arc::new(Mutex::new(HashMap::new())),
            runtime_authorizations: Arc::new(Mutex::new(HashMap::new())),
            permits: Arc::new(tokio::sync::Semaphore::new(MAX_CONNECTIONS)),
            control_permits: Arc::new(tokio::sync::Semaphore::new(8)),
            // Secondary stream concurrency is bounded by the existing admitted
            // connection ceiling; no stream-specific worker/queue count is introduced.
            secondary_stream_permits: Arc::new(tokio::sync::Semaphore::new(MAX_CONNECTIONS)),
            attachments,
            push_storage_key: Arc::new(zeroize::Zeroizing::new(push_storage_key)),
            push_policy_lock: Arc::new(tokio::sync::Mutex::new(())),
        })
    }

    fn peer_dispatch_lock(&self, endpoint: &str) -> Arc<tokio::sync::Mutex<()>> {
        let mut locks = self.peer_dispatch_locks.lock().expect("peer dispatch locks poisoned");
        locks.entry(endpoint.to_owned()).or_insert_with(|| Arc::new(tokio::sync::Mutex::new(()))).clone()
    }

    // Different conversations share a read lease; revocation takes the write lease.
    fn runtime_authorization(&self, endpoint: &str) -> Arc<tokio::sync::RwLock<()>> {
        self.runtime_authorizations.lock().expect("runtime authorization map poisoned")
            .entry(endpoint.to_owned()).or_default().clone()
    }

    fn is_current_connection(&self, endpoint: &str, generation: u64) -> bool {
        self.active_physical.lock().expect("active connection map poisoned")
            .get(endpoint).is_some_and(|(current, _)| *current == generation)
    }

    fn peer_lanes(&self, endpoint: &str) -> Vec<(kompi_protocol::ConversationId, Arc<PromptLane>)> {
        self.prompt_lanes.lock().expect("prompt lanes poisoned").iter()
            .filter_map(|((peer, id), lane)| (peer == endpoint).then(|| (*id, lane.clone())))
            .collect()
    }

    fn endpoint_hex(&self) -> String {
        hex(&self.endpoint.id().as_bytes()[..])
    }

    async fn run(self) -> Result<()> {
        self.run_with_accept(async |listener| listener.accept().await.map(|(stream, _)| stream)).await
    }

    async fn run_with_accept(self, mut accept: impl AsyncFnMut(&mut crate::local_ipc::Listener) -> std::io::Result<crate::local_ipc::ServerStream>) -> Result<()> {
        let mut listener = self.bind_control().await?;
        let mut background = tokio::task::JoinSet::new();
        let relay_endpoint = self.endpoint.clone();
        background.spawn(async move {
            if timeout(Duration::from_secs(15), relay_endpoint.online()).await.is_ok() {
                eprintln!("Kompi relay connection established");
            } else {
                eprintln!("Kompi relay is offline; check network policy and relay connectivity");
                for relay in relay_endpoint.home_relay_status().get() {
                    if let Some(error) = relay.last_error() {
                        eprintln!("Kompi relay {}: {}", relay.url(), safe_error(&error.to_string()));
                    }
                }
            }
        });
        let endpoint = self.endpoint.clone();
        let daemon = self.clone();
        background.spawn(async move {
            daemon.accept_loop(endpoint).await;
        });
        let push_dispatcher=self.clone();
        background.spawn(async move { push_dispatcher.push_dispatch_loop().await; });
        let sweeper = self.clone();
        background.spawn(async move {
            let mut ticker = tokio::time::interval(Duration::from_secs(5));
            loop {
                ticker.tick().await;
                if !*sweeper.running.borrow() {
                    break;
                }
                sweeper.worker.artifacts.sweep();
                if let Err(e) = sweeper.sweep_idle().await {
                    eprintln!(
                        "Kompi lease cleanup failed: {}",
                        safe_error(&e.to_string())
                    );
                }
            }
        });
        let mut running = self.running.subscribe();
        let result = tokio::select! {
            result = self.serve_listener(async move || accept(&mut listener).await) => result.context("local control listener failed"),
            _ = shutdown_signal() => Ok(()),
            _ = running.wait_for(|running| !*running) => Ok(()),
        };
        self.running.send_replace(false);
        background.shutdown().await;
        self.worker.shutdown().await;
        self.endpoint.close().await;
        #[cfg(unix)]
        let _ = fs::remove_file(&self.control_path);
        result
    }

    async fn bind_control(&self) -> Result<crate::local_ipc::Listener> {
        #[cfg(unix)]
        let listener = {
            if let Some(parent) = self.control_path.parent() {
                fs::create_dir_all(parent)?;
                fs::set_permissions(parent, fs::Permissions::from_mode(0o700))?;
            }
            match fs::symlink_metadata(&self.control_path) {
                Ok(metadata) => {
                    if !metadata.file_type().is_socket() {
                        bail!(
                            "refusing to replace non-socket control path {}",
                            self.control_path.display()
                        );
                    }
                    match crate::local_ipc::connect(&self.control_path).await {
                        Ok(_) => bail!(
                            "another daemon already listens at {}",
                            self.control_path.display()
                        ),
                        Err(_) => fs::remove_file(&self.control_path)?,
                    }
                }
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
                Err(error) => return Err(error.into()),
            }
            let listener = crate::local_ipc::Listener::bind(&self.control_path)?;
            fs::set_permissions(&self.control_path, fs::Permissions::from_mode(0o600))?;
            listener
        };
        #[cfg(windows)]
        let listener = crate::local_ipc::Listener::bind(&self.control_path)?;
        Ok(listener)
    }

    async fn serve_listener(&self, mut accept: impl AsyncFnMut() -> std::io::Result<crate::local_ipc::ServerStream>) -> std::io::Result<()> {
        let control_permits = self.control_permits.clone();
        let mut clients = tokio::task::JoinSet::new();
        loop {
            let stream = control_listener::accept_with_retry(&mut accept).await?;
            while clients.try_join_next().is_some() {}
            let Ok(permit) = control_permits.clone().try_acquire_owned() else {
                drop(stream);
                continue;
            };
            let daemon = self.clone();
            clients.spawn(async move {
                let _permit = permit;
                if let Err(error) = daemon.serve_control(stream).await {
                    eprintln!(
                        "Kompi control connection rejected: {}",
                        safe_error(&error.to_string())
                    );
                }
            });
        }
    }

    async fn serve_control(&self, mut stream: crate::local_ipc::ServerStream) -> Result<()> {
        loop {
            let Some(payload) = read_frame(&mut stream).await? else {
                return Ok(());
            };
            let request: ControlEnvelope = kompi_protocol::decode_cbor(&payload)?;
            let id = request.id;
            let response = self.handle_control(id.clone(), request.message).await;
            let envelope = Envelope::new(id, response);
            let bytes = kompi_protocol::encode_cbor(&envelope)?;
            write_frame(&mut stream, &bytes).await?;
        }
    }

    async fn handle_control(&self, _request_id: RequestId, message: ControlMessage) -> ControlMessage {
        match message {
            ControlMessage::Status {} => {
                let paired_devices = match self.devices().await.and_then(|devices| devices.into_iter().map(device_summary).collect())
                {
                    Ok(v) => v,
                    Err(error) => {
                        return ControlMessage::Error {
                            code: "invalid-device-time".into(),
                            message: safe_error(&error.to_string()),
                        };
                    }
                };
                ControlMessage::StatusResult {
                    running: true,
                    endpoint: Some(self.endpoint_hex()),
                    control_socket: self.control_path.display().to_string(),
                    paired_devices,
                }
            }
            ControlMessage::DevicesList {} => match self.devices().await.and_then(|devices| devices.into_iter().map(device_summary).collect())
            {
                Ok(devices) => ControlMessage::DevicesResult { devices },
                Err(error) => ControlMessage::Error {
                    code: "invalid-device-time".into(),
                    message: safe_error(&error.to_string()),
                },
            },
            ControlMessage::DeviceRevoke { endpoint } => match self.revoke(&endpoint).await {
                Ok(true) => ControlMessage::Ok {
                    operation: "device-revoked".into(),
                },
                Ok(false) => ControlMessage::Error {
                    code: "device-not-found".into(),
                    message: "No paired device has that endpoint ID.".into(),
                },
                Err(error) => ControlMessage::Error {
                    code: "revoke-failed".into(),
                    message: safe_error(&error.to_string()),
                },
            },
            ControlMessage::DeviceNotification { endpoint, request_id: notification_id, title, body } => {
                self.notify_device(notification_id, endpoint, title, body).await
            }
            ControlMessage::DeviceRichNotification { endpoint, request_id, notification } => {
                self.notify_device_payload(request_id, endpoint, notification).await
            }
            ControlMessage::PushAllowOrigin { origin } => {
                let _policy_guard=self.push_policy_lock.lock().await;
                match canonical_push_origin(&origin) {
                Ok(origin) => match self.sqlite.push_allow_origin(&origin).await {
                    Ok(()) => ControlMessage::Ok { operation: "push-origin-allowed".into() },
                    Err(error) => ControlMessage::Error { code: "push-origin-update-failed".into(), message: safe_error(&error.to_string()) },
                },
                Err(_) => ControlMessage::Error { code: "invalid-push-origin".into(), message: "Origin must be a canonical HTTPS origin without path, query, fragment, or credentials.".into() },
                }
            }
            ControlMessage::PushAllowedOriginsList {} => match self.sqlite.push_allowed_origins().await {
                Ok(origins) => ControlMessage::PushAllowedOriginsResult { origins },
                Err(error) => ControlMessage::Error { code: "push-origin-list-failed".into(), message: safe_error(&error.to_string()) },
            },
            ControlMessage::PushAllowedOriginRevoke { origin } => {
                let _policy_guard=self.push_policy_lock.lock().await;
                match canonical_push_origin(&origin) {
                Ok(origin) => match self.sqlite.push_revoke_origin(&origin).await {
                    Ok(_) => ControlMessage::Ok { operation: "push-origin-revoked".into() },
                    Err(error) => ControlMessage::Error { code: "push-origin-revoke-failed".into(), message: safe_error(&error.to_string()) },
                },
                Err(_) => ControlMessage::Error { code: "invalid-push-origin".into(), message: "Origin must be a canonical HTTPS origin without path, query, fragment, or credentials.".into() },
                }
            }
            ControlMessage::PairingCreate { name } => match self.make_pairing(name.as_deref()).await {
                Ok((qr, token, expires_at)) => ControlMessage::PairingResult {
                    qr,
                    token: Some(token),
                    expires_at,
                },
                Err(error) => ControlMessage::Error {
                    code: "pairing-failed".into(),
                    message: safe_error(&error.to_string()),
                },
            },
            other => ControlMessage::Error {
                code: "unsupported-control".into(),
                message: format!("Unsupported control request: {}", control_tag(&other)),
            },
        }
    }

    fn decrypt_push_secrets(&self, peer: &str, registration_id: &str, encrypted: &[u8]) -> Result<zeroize::Zeroizing<Vec<u8>>> {
        anyhow::ensure!(encrypted.len() >= 12 + 16, "encrypted push secret is malformed");
        use chacha20poly1305::aead::{Aead, KeyInit, Payload};
        let cipher = chacha20poly1305::ChaCha20Poly1305::new_from_slice(&self.push_storage_key[..])?;
        let aad = format!("{peer}\0{registration_id}");
        let nonce_bytes: [u8;12]=encrypted[..12].try_into().expect("checked nonce length");
        let nonce:chacha20poly1305::Nonce=nonce_bytes.into();
        let secret = cipher.decrypt(&nonce, Payload { msg: &encrypted[12..], aad: aad.as_bytes() })?;
        anyhow::ensure!(secret.len() >= 50, "decrypted push secret has invalid size");
        let endpoint_len=u16::from_be_bytes([secret[48],secret[49]]) as usize;
        anyhow::ensure!(endpoint_len>0 && endpoint_len<=kompi_protocol::PUSH_ENDPOINT_MAX_BYTES && secret.len()==50+endpoint_len, "decrypted push endpoint has invalid size");
        Ok(zeroize::Zeroizing::new(secret))
    }

    fn notification_fingerprint(&self, peer:&str, request_id:&RequestId, title:&str, body:&str) -> Result<Vec<u8>> {
        use hmac::{KeyInit, Mac};
        anyhow::ensure!(!title.trim().is_empty() && title.chars().count()<=kompi_protocol::DEVICE_NOTIFICATION_TITLE_MAX_CHARS, "notification title is invalid");
        anyhow::ensure!(!body.trim().is_empty() && body.chars().count()<=kompi_protocol::DEVICE_NOTIFICATION_BODY_MAX_CHARS, "notification body is invalid");
        let mut mac=hmac::Hmac::<Sha256>::new_from_slice(&self.push_storage_key[..]).context("initialize notification fingerprint")?;
        mac.update(b"kompi-notification-fingerprint-v1\0");
        for field in [peer.as_bytes(),request_id.as_str().as_bytes(),title.as_bytes(),body.as_bytes()] {
            mac.update(&(field.len() as u64).to_be_bytes());
            mac.update(field);
        }
        Ok(mac.finalize().into_bytes().to_vec())
    }

    fn notification_payload_fingerprint(&self, peer: &str, request_id: &RequestId, notification: &kompi_protocol::AgentNotification) -> Result<Vec<u8>> {
        use hmac::{KeyInit, Mac};
        notification.validate()?;
        if let Some(buttons) = notification.rich.as_ref().and_then(|rich| rich.buttons.as_ref()) {
            for button in buttons {
                if let kompi_protocol::NotificationTarget::Https { url } = &button.target {
                    let parsed = url::Url::parse(url)?;
                    anyhow::ensure!(parsed.scheme()=="https" && parsed.host().is_some() && parsed.username().is_empty() && parsed.password().is_none() && !url.chars().any(|c| c.is_control() || c.is_whitespace()), "unsafe notification URL");
                }
            }
        }
        if notification.rich.is_none() { return self.notification_fingerprint(peer,request_id,&notification.title,&notification.body); }
        let mut mac=hmac::Hmac::<Sha256>::new_from_slice(&self.push_storage_key[..]).context("initialize notification fingerprint")?;
        mac.update(b"kompi-notification-fingerprint-v2\0");
        let content=serde_json::to_vec(notification)?;
        for field in [peer.as_bytes(),request_id.as_str().as_bytes(),&content] {
            mac.update(&(field.len() as u64).to_be_bytes()); mac.update(field);
        }
        Ok(mac.finalize().into_bytes().to_vec())
    }

    fn dispatch_fingerprint(&self, peer: &str, request_id: &RequestId, dispatch: &NotificationDispatch) -> Result<Vec<u8>> {
        match dispatch {
            NotificationDispatch::Agent(notification) => self.notification_payload_fingerprint(peer, request_id, notification),
            NotificationDispatch::Reply(reply) => self.notification_fingerprint(peer, request_id, "kompi-reply-completion-v1", &reply.conversation_id.to_string()),
        }
    }

    fn make_push_ciphertext(&self, registration_id: PushRegistrationId, endpoint: &str, p256dh: &str, auth: &str, integrity: &[u8], request_id: &RequestId, dispatch: &NotificationDispatch) -> Result<(Vec<u8>,i64)> {
        let now = now_ms();
        let host = self.endpoint_hex();
        match dispatch {
            NotificationDispatch::Agent(notification) => {
                let expires = now.checked_add(kompi_protocol::PUSH_MAX_TTL_MS).context("push expiry overflow")?;
                let ciphertext = if notification.rich.is_some() {
                    crate::push_crypto::encrypt_rich_notification_payload(&host,endpoint,registration_id,request_id,now,expires,notification,p256dh,auth,integrity)?
                } else {
                    crate::push_crypto::encrypt_notification_payload(&host,endpoint,registration_id,request_id,now,expires,&notification.title,&notification.body,p256dh,auth,integrity)?
                };
                Ok((ciphertext, expires))
            }
            NotificationDispatch::Reply(reply) => {
                let expires = reply.completed_at_ms.saturating_add(kompi_protocol::REPLY_NOTIFICATION_TTL_MS);
                anyhow::ensure!(expires > now, "reply notification expired");
                let ciphertext = crate::push_crypto::encrypt_reply_notification_payload(&host,endpoint,registration_id,request_id,*reply,expires,p256dh,auth,integrity)?;
                Ok((ciphertext, expires))
            }
        }
    }

    async fn register_push(&self, peer: &str, generation: u64, registration: PushRegistration) -> (PushRegistrationStatus, Option<PushRegistrationErrorCode>) {
        let _policy_guard=self.push_policy_lock.lock().await;
        let rejected = |code| (PushRegistrationStatus::Rejected, Some(code));
        let parsed = match url::Url::parse(&registration.endpoint) { Ok(url) => url, Err(_) => return rejected(PushRegistrationErrorCode::InvalidEndpoint) };
        if parsed.scheme() != "https" || parsed.host().is_none() || !parsed.username().is_empty() || parsed.password().is_some() || parsed.fragment().is_some() || registration.endpoint.len() > kompi_protocol::PUSH_ENDPOINT_MAX_BYTES {
            return rejected(PushRegistrationErrorCode::InvalidEndpoint);
        }
        let origin = parsed.origin().ascii_serialization();
        let allowed = match self.sqlite.push_allowed_origins().await { Ok(origins) => origins, Err(_) => return rejected(PushRegistrationErrorCode::PushDisabled) };
        if !allowed.iter().any(|allowed| allowed == &origin) { return rejected(PushRegistrationErrorCode::OriginNotAllowed); }
        let peer_lock = self.peer_dispatch_lock(peer);
        let _serialized = peer_lock.lock().await;
        if !self.active_physical.lock().expect("active connection map poisoned").get(peer).is_some_and(|(current,_)| *current == generation) {
            return rejected(PushRegistrationErrorCode::RegistrationMismatch);
        }
        let registration_id = registration.registration_id.to_string();
        let auth = match URL_SAFE_NO_PAD.decode(registration.auth.expose_secret()) { Ok(value) if value.len()==16 => zeroize::Zeroizing::new(value), _ => return rejected(PushRegistrationErrorCode::InvalidKeys) };
        let integrity = match URL_SAFE_NO_PAD.decode(registration.integrity_key.expose_secret()) { Ok(value) if value.len()==32 => zeroize::Zeroizing::new(value), _ => return rejected(PushRegistrationErrorCode::InvalidKeys) };
        let mut secret = zeroize::Zeroizing::new(Vec::with_capacity(50+registration.endpoint.len()));
        secret.extend_from_slice(&auth);
        secret.extend_from_slice(&integrity);
        secret.extend_from_slice(&(registration.endpoint.len() as u16).to_be_bytes());
        secret.extend_from_slice(registration.endpoint.as_bytes());
        let aad = format!("{peer}\0{registration_id}");
        let mut iv = [0u8; 12];
        rand::rng().fill_bytes(&mut iv);
        use chacha20poly1305::aead::{Aead, KeyInit, Payload};
        let cipher = match chacha20poly1305::ChaCha20Poly1305::new_from_slice(&self.push_storage_key[..]) { Ok(value) => value, Err(_) => return rejected(PushRegistrationErrorCode::PushDisabled) };
        let nonce:chacha20poly1305::Nonce=iv.into();
        let encrypted = match cipher.encrypt(&nonce, Payload { msg: &secret, aad: aad.as_bytes() }) { Ok(value) => value, Err(_) => return rejected(PushRegistrationErrorCode::PushDisabled) };
        let mut encrypted_secrets = iv.to_vec();
        encrypted_secrets.extend_from_slice(&encrypted);
        let record = crate::sqlite_store::PushRegistrationRecord { peer: peer.to_owned(), registration_id: registration_id.clone(), origin, p256dh: registration.p256dh.as_str().to_owned(), encrypted_secrets };
        let rich = self.active_senders.lock().expect("active sender map poisoned").get(peer)
            .is_some_and(|active| active.generation == generation && active.rich_notification_capable);
        if self.sqlite.replace_push_registration_with_rich(&record,rich).await.is_err() { return rejected(PushRegistrationErrorCode::PushDisabled); }
        if !self.active_physical.lock().expect("active connection map poisoned").get(peer).is_some_and(|(current,_)| *current == generation) {
            let _ = self.sqlite.remove_push_registration(peer, &registration_id).await;
            return rejected(PushRegistrationErrorCode::RegistrationMismatch);
        }
        (PushRegistrationStatus::Registered, None)
    }

    async fn unregister_push(&self, peer: &str, generation: u64, registration_id: PushRegistrationId) -> (PushRegistrationStatus, Option<PushRegistrationErrorCode>) {
        let peer_lock=self.peer_dispatch_lock(peer);
        let _serialized = peer_lock.lock().await;
        if !self.active_physical.lock().expect("active connection map poisoned").get(peer).is_some_and(|(current,_)| *current == generation) {
            return (PushRegistrationStatus::Rejected, Some(PushRegistrationErrorCode::RegistrationMismatch));
        }
        match self.sqlite.remove_push_registration(peer, &registration_id.to_string()).await {
            // A missing specific generation is already unregistered; this is what makes
            // pending stale unregisters converge without ever touching a newer registration.
            Ok(_) => (PushRegistrationStatus::Unregistered, None),
            Err(_) => (PushRegistrationStatus::Rejected, Some(PushRegistrationErrorCode::PushDisabled)),
        }
    }

    async fn queue_push_notification(&self, endpoint: &str, request_id: &RequestId, dispatch: &NotificationDispatch) -> ControlMessage {
        let peer_lock=self.peer_dispatch_lock(endpoint);
        let _dispatch_guard=peer_lock.lock().await;
        if !matches!(self.sqlite.device(endpoint).await,Ok(Some(_))) {
            return ControlMessage::DeviceNotificationResult { endpoint:endpoint.to_owned(),status:DeviceNotificationStatus::NotPosted,code:Some("device-not-found".into()),message:Some("This endpoint is not currently paired; no request was queued.".into()) };
        }
        let fingerprint=match self.dispatch_fingerprint(endpoint,request_id,dispatch) {
            Ok(fingerprint)=>fingerprint,
            Err(_)=>return ControlMessage::DeviceNotificationResult { endpoint:endpoint.to_owned(),status:DeviceNotificationStatus::NotPosted,code:Some("invalid-notification".into()),message:Some("Notification input is invalid or exceeds the supported size.".into()) },
        };
        match self.sqlite.notification_receipt(endpoint,request_id.as_str(),&fingerprint,now_ms()).await {
            Ok(Some((prior_status,code))) => {
                let status=notification_status(&prior_status);
                return ControlMessage::DeviceNotificationResult { endpoint:endpoint.to_owned(),status,code,message:Some(notification_status_message(status).into()) };
            }
            Ok(None) => {}
            Err(_) => return ControlMessage::Error { code:"notification-ledger-unavailable".into(),message:"Could not check prior notification delivery status.".into() },
        }
        let registration = match self.sqlite.push_registration(endpoint).await {
            Ok(Some(registration)) => registration,
            Ok(None) => return ControlMessage::DeviceNotificationResult { endpoint:endpoint.to_owned(), status:DeviceNotificationStatus::Unavailable, code:Some("no-background-route".into()), message:Some("The phone is offline and has no active UnifiedPush registration; no request was queued.".into()) },
            Err(_) => return ControlMessage::Error { code:"push-registration-lookup-failed".into(), message:"Could not check the paired phone's push registration.".into() },
        };
        if let Some(notification) = dispatch.agent().filter(|value| value.rich.is_some()) {
            if !self.sqlite.push_registration_supports_rich(endpoint,&registration.registration_id).await.unwrap_or(false) || notification.has_image() {
            return ControlMessage::DeviceNotificationResult { endpoint:endpoint.to_owned(),status:DeviceNotificationStatus::Unavailable,code:Some("rich-notification-unavailable".into()),message:Some("Rich notifications require a capable phone; pictures require its live connection. Nothing was queued.".into()) };
            }
        }
        let secret=match self.decrypt_push_secrets(endpoint,&registration.registration_id,&registration.encrypted_secrets) {
            Ok(secret)=>secret,
            Err(_)=>return ControlMessage::Error { code:"push-registration-secret-unavailable".into(), message:"The paired phone's push registration secret is unavailable.".into() },
        };
        let auth=URL_SAFE_NO_PAD.encode(&secret[..16]);
        let integrity=&secret[16..48];
        let push_endpoint=match std::str::from_utf8(&secret[50..]) { Ok(value)=>value,Err(_)=>return ControlMessage::Error { code:"push-registration-invalid".into(), message:"The paired phone's push endpoint is invalid.".into() } };
        let registration_id=match uuid::Uuid::parse_str(&registration.registration_id) {
            Ok(id)=>PushRegistrationId::from(id),
            Err(_)=>return ControlMessage::Error { code:"push-registration-invalid".into(), message:"The paired phone's push registration is invalid.".into() },
        };
        let (ciphertext,expires_at_ms)=match self.make_push_ciphertext(registration_id,push_endpoint,&registration.p256dh,&auth,integrity,request_id,dispatch) {
            Ok(payload)=>payload,
            Err(_)=>return ControlMessage::DeviceNotificationResult { endpoint:endpoint.to_owned(), status:DeviceNotificationStatus::Unavailable, code:Some("push-payload-unavailable".into()), message:Some("The notification cannot fit the encrypted push budget. Connect the phone or use smaller content; no push was queued.".into()) },
        };
        let now=now_ms();
        let (status,code,_duplicate)=match self.sqlite.enqueue_push(endpoint,request_id.as_str(),&registration.registration_id,&ciphertext,&fingerprint,expires_at_ms,now).await {
            Ok(result)=>result,
            Err(_)=>return ControlMessage::DeviceNotificationResult { endpoint:endpoint.to_owned(), status:DeviceNotificationStatus::NotPosted, code:Some("push-queue-unavailable".into()), message:Some("The durable push queue is full or unavailable; no push was queued.".into()) },
        };
        let status=notification_status(&status);
        ControlMessage::DeviceNotificationResult { endpoint:endpoint.to_owned(), status, code, message:Some(notification_status_message(status).into()) }
    }

    async fn notify_device(
        &self,
        request_id: RequestId,
        endpoint: String,
        title: String,
        body: String,
    ) -> ControlMessage {
        self.notify_device_payload(request_id,endpoint,kompi_protocol::AgentNotification::plain(title,body)).await
    }

    async fn notify_device_payload(&self, request_id: RequestId, endpoint: String, notification: kompi_protocol::AgentNotification) -> ControlMessage {
        self.dispatch_notification(request_id, endpoint, NotificationDispatch::Agent(notification)).await
    }

    fn notify_response_completed(&self, endpoint: &str, id: &RequestId, conversation_id: kompi_protocol::ConversationId) {
        use sha2::Digest;
        let hash = Sha256::digest(id.as_str().as_bytes());
        let request_id = RequestId::parse(format!("reply-{}", URL_SAFE_NO_PAD.encode(hash)))
            .expect("reply notification ID is a bounded base64url value");
        let reply = kompi_protocol::ReplyNotification { conversation_id, completed_at_ms: now_ms() };
        let daemon = self.clone();
        let endpoint = endpoint.to_owned();
        tokio::spawn(async move {
            let dispatch = NotificationDispatch::Reply(reply);
            // Prefer the registered route: a backgrounded phone can disappear while
            // Iroh still reports a sender. Only a definitely absent route permits live fallback.
            let result = daemon.queue_push_notification(&endpoint, &request_id, &dispatch).await;
            match result {
                ControlMessage::DeviceNotificationResult { code: Some(code), .. } if code == "no-background-route" => {
                    let _ = daemon.dispatch_notification(request_id, endpoint, dispatch).await;
                }
                ControlMessage::Error { code, .. }
                | ControlMessage::DeviceNotificationResult { status: DeviceNotificationStatus::Unavailable | DeviceNotificationStatus::NotPosted, code: Some(code), .. } => {
                    // Routing diagnostics are fixed codes, never endpoint, IDs, or content.
                    eprintln!("Kompi reply notification not queued: {code}");
                }
                _ => {}
            }
        });
    }

    async fn dispatch_notification(&self, request_id: RequestId, endpoint: String, dispatch: NotificationDispatch) -> ControlMessage {
        match self.sqlite.device(&endpoint).await {
            Ok(Some(_)) => {}
            Ok(None) => {
                return ControlMessage::DeviceNotificationResult {
                    endpoint,
                    status: DeviceNotificationStatus::NotPosted,
                    code: Some("device-not-found".into()),
                    message: Some("This endpoint is not a paired device; no request was dispatched.".into()),
                };
            }
            Err(error) => {
                return ControlMessage::Error {
                    code: "device-lookup-failed".into(),
                    message: safe_error(&error.to_string()),
                };
            }
        }
        let Some(active) = self.active_senders
            .lock()
            .expect("active sender map poisoned")
            .get(&endpoint)
            .cloned()
        else {
            return self.queue_push_notification(&endpoint,&request_id,&dispatch).await;
        };
        if matches!(dispatch, NotificationDispatch::Reply(_)) && !active.reply_notification_capable {
            return ControlMessage::DeviceNotificationResult { endpoint, status: DeviceNotificationStatus::Unavailable, code: Some("reply-notifications-unsupported".into()), message: None };
        }
        if dispatch.agent().is_some_and(|value| value.rich.is_some()) && !active.rich_notification_capable {
            return ControlMessage::DeviceNotificationResult { endpoint,status:DeviceNotificationStatus::Unavailable,code:Some("rich-notifications-unsupported".into()),message:Some("The connected phone has not negotiated rich notifications. Nothing was sent.".into()) };
        }
        if !active.device_notification_capable {
            return self.queue_push_notification(&endpoint,&request_id,&dispatch).await;
        }
        let fingerprint=match self.dispatch_fingerprint(&endpoint,&request_id,&dispatch) {
            Ok(fingerprint)=>fingerprint,
            Err(_)=>return ControlMessage::DeviceNotificationResult { endpoint,status:DeviceNotificationStatus::NotPosted,code:Some("invalid-notification".into()),message:Some("Notification input is invalid or exceeds the supported size.".into()) },
        };
        let (reserved,prior_status,prior_code)=match self.sqlite.reserve_direct_notification(&endpoint,request_id.as_str(),&fingerprint,now_ms()).await {
            Ok(claim)=>claim,
            Err(_)=>return ControlMessage::DeviceNotificationResult { endpoint,status:DeviceNotificationStatus::NotPosted,code:Some("notification-ledger-unavailable".into()),message:Some("The notification could not be safely reserved; no request was dispatched.".into()) },
        };
        if !reserved {
            let status=notification_status(&prior_status);
            return ControlMessage::DeviceNotificationResult { endpoint,status,code:prior_code,message:Some(if status==DeviceNotificationStatus::Unknown { "The same notification ID has an in-progress or uncertain outcome; no second delivery was attempted.".into() } else { "This notification ID was already reserved; no second delivery was attempted.".into() }) };
        }
        let generation = active.generation;
        let peer_lock=self.peer_dispatch_lock(&endpoint);
        let _dispatch_guard=peer_lock.lock().await;
        if self.sqlite.device(&endpoint).await.ok().flatten().is_none()
            || !self.active_physical.lock().expect("active connection map poisoned").get(&endpoint).is_some_and(|(current,_)| *current==generation) {
            return ControlMessage::DeviceNotificationResult { endpoint,status:DeviceNotificationStatus::NotPosted,code:Some("device-revoked".into()),message:Some("The phone is no longer paired on this connection; no request was dispatched.".into()) };
        }
        let sender = active.sender;
        let notification_timeout = Duration::from_millis(DEVICE_NOTIFICATION_TIMEOUT_MS as u64);
        let (reply_tx, reply_rx) = oneshot::channel();
        let expires_at = std::time::Instant::now() + notification_timeout;
        if !self.pending_device_notifications.lock().expect("device notification registry poisoned")
            .register(endpoint.clone(), request_id.clone(), generation, expires_at, reply_tx)
        {
            let _=self.sqlite.complete_direct_notification(&endpoint,request_id.as_str(),"unknown",Some("duplicate-request")).await;
            return ControlMessage::DeviceNotificationResult {
                endpoint,
                status: DeviceNotificationStatus::Unknown,
                code: Some("duplicate-request".into()),
                message: Some("A request with this ID is already pending; do not retry automatically.".into()),
            };
        }

        let message = match dispatch {
            NotificationDispatch::Reply(reply) => Message::DeviceReplyNotificationRequest { request_id: request_id.clone(), reply, timeout_ms: DEVICE_NOTIFICATION_TIMEOUT_MS },
            NotificationDispatch::Agent(notification) if notification.rich.is_some() => Message::DeviceRichNotificationRequest { request_id:request_id.clone(),notification,timeout_ms:DEVICE_NOTIFICATION_TIMEOUT_MS },
            NotificationDispatch::Agent(notification) => Message::DeviceNotificationRequest { request_id:request_id.clone(),title:notification.title,body:notification.body,timeout_ms:DEVICE_NOTIFICATION_TIMEOUT_MS },
        };
        let request = Envelope::new(request_id.clone(), message);
        if sender.send(&request).await.is_err() {
            self.pending_device_notifications.lock().expect("device notification registry poisoned")
                .remove(&endpoint, &request_id, generation);
            let _=self.sqlite.complete_direct_notification(&endpoint,request_id.as_str(),"unknown",Some("dispatch-uncertain")).await;
            return ControlMessage::DeviceNotificationResult {
                endpoint,
                status: DeviceNotificationStatus::Unknown,
                code: Some("dispatch-uncertain".into()),
                message: Some("The connection failed during dispatch. The phone may have posted the notification; do not retry automatically.".into()),
            };
        }

        drop(_dispatch_guard);
        let remaining = expires_at.saturating_duration_since(std::time::Instant::now());
        let result=match timeout(remaining, reply_rx).await {
            Ok(Ok(reply)) => ControlMessage::DeviceNotificationResult {
                endpoint: endpoint.clone(),
                status: match reply.status {
                    DeviceNotificationReceipt::Posted => DeviceNotificationStatus::Posted,
                    DeviceNotificationReceipt::NotPosted => DeviceNotificationStatus::NotPosted,
                    DeviceNotificationReceipt::Unknown => DeviceNotificationStatus::Unknown,
                },
                code: reply.code,
                message: reply.message.or_else(|| match reply.status {
                    DeviceNotificationReceipt::Posted => Some("Posted to Android; this does not confirm the user saw or heard it.".into()),
                    DeviceNotificationReceipt::NotPosted => Some("Android rejected the notification before posting it.".into()),
                    DeviceNotificationReceipt::Unknown => Some("Android did not confirm posting. The phone may have acted; do not retry automatically.".into()),
                }),
            },
            Ok(Err(_)) => ControlMessage::DeviceNotificationResult {
                endpoint: endpoint.clone(),
                status: DeviceNotificationStatus::Unknown,
                code: Some("connection-ended".into()),
                message: Some("The phone disconnected before confirming delivery. It may have posted the notification; do not retry automatically.".into()),
            },
            Err(_) => {
                self.pending_device_notifications.lock().expect("device notification registry poisoned")
                    .remove(&endpoint, &request_id, generation);
                ControlMessage::DeviceNotificationResult {
                    endpoint: endpoint.clone(),
                    status: DeviceNotificationStatus::Unknown,
                    code: Some("acknowledgement-timeout".into()),
                    message: Some("The acknowledgement expired. The phone may have posted the notification; do not retry automatically.".into()),
                }
            }
        };
        let (stored_status,code)=match &result {
            ControlMessage::DeviceNotificationResult { status,code,.. } => (match status {
                DeviceNotificationStatus::Posted=>"posted",
                DeviceNotificationStatus::NotPosted=>"not-posted",
                DeviceNotificationStatus::Unknown=>"unknown",
                _=>"unknown",
            },code.as_deref()),
            _=>("unknown",Some("control-result-unavailable")),
        };
        if self.sqlite.complete_direct_notification(&endpoint,request_id.as_str(),stored_status,code).await.is_err() {
            return ControlMessage::DeviceNotificationResult { endpoint,status:DeviceNotificationStatus::Unknown,code:Some("receipt-persist-failed".into()),message:Some("The phone may have posted this notification, but its durable receipt could not be saved; do not retry automatically.".into()) };
        }
        result
    }

    async fn make_pairing(
        &self,
        name: Option<&str>,
    ) -> Result<(String, String, kompi_protocol::Timestamp)> {
        // Do not issue a code that only names an unreachable endpoint. In
        // restricted networks the home relay is the phone's only route here.
        timeout(Duration::from_secs(5), self.endpoint.online())
            .await
            .context("Kompi relay is offline; cannot create a reachable pairing code")?;
        let name = match name.map(str::trim).filter(|name| !name.is_empty()) {
            Some(name) => name.to_owned(),
            None => machine_hostname()?,
        };
        let name = name.chars().take(128).collect::<String>();
        let mut secret = [0u8; 32];
        rand::rng().fill_bytes(&mut secret);
        let secret = URL_SAFE_NO_PAD.encode(secret);
        let endpoint_ticket = EndpointTicket::new(self.endpoint.addr()).to_string();
        let payload = PairingPayload {
            v: 1,
            endpoint_ticket,
            secret: secret.clone(),
            name: name.clone(),
        };
        let qr = serde_json::to_string(&payload)?;
        let token = format!(
            "{}{}",
            kompi_protocol::PAIRING_TOKEN_PREFIX,
            URL_SAFE_NO_PAD.encode(qr.as_bytes())
        );
        let expires_at_ms = now_ms() + PAIR_TTL_MS;
        let expires_at = wire_timestamp(expires_at_ms)?;
        let mut credentials = self.credentials.lock().expect("pairing map poisoned");
        credentials.retain(|_, credential| credential.expires_at >= now_ms());
        if credentials.len() >= 32 {
            bail!("pairing credential capacity reached; wait for an existing code to expire");
        }
        credentials.insert(
            secret,
            PairCredential {
                name,
                expires_at: expires_at_ms,
            },
        );
        drop(credentials);
        Ok((qr, token, expires_at))
    }

    async fn devices(&self) -> Result<Vec<Device>> {
        self.sqlite.devices().await
    }

    async fn revoke(&self, endpoint: &str) -> Result<bool> {
        let _policy_guard=self.push_policy_lock.lock().await;
        // Exclude the final authorization check + provider dispatch while revoking.
        let dispatch_lock = self.peer_dispatch_lock(endpoint);
        let _dispatch_guard = dispatch_lock.lock().await;
        let authorization = self.runtime_authorization(endpoint);
        let _runtime_guard = authorization.write().await;
        let removed = self.sqlite.revoke_device(endpoint).await?;
        if removed {
            self.worker.artifacts.revoke(endpoint);
            if let Some((_, connection)) = self.active_physical.lock().expect("active connection map poisoned").remove(endpoint) {
                connection.close(2u32.into(), b"device revoked");
            }
            self.active_senders.lock().expect("active sender map poisoned").remove(endpoint);
            for (_, lane) in self.peer_lanes(endpoint) {
                lane.owner.revoke().await.context("session owner stopped revoking phone session")?;
            }
            let mut workers = tokio::task::JoinSet::new();
            for conversation in self.worker.conversations_for(endpoint).await {
                let worker = self.worker.clone();
                workers.spawn(async move { worker.dispose(conversation).await });
            }
            while let Some(result) = workers.join_next().await {
                if !matches!(result, Ok(Ok(()))) { eprintln!("Kompi revoked worker did not confirm release"); }
            }
        }
        Ok(removed)
    }

    async fn accept_loop(self, endpoint: Arc<Endpoint>) {
        loop {
            let incoming = match endpoint.accept().await {
                Some(incoming) => incoming,
                None => break,
            };
            let permit = match self.permits.clone().acquire_owned().await {
                Ok(p) => p,
                Err(_) => break,
            };
            let daemon = self.clone();
            tokio::spawn(async move {
                let _permit = permit;
                daemon.handle_incoming(incoming).await;
            });
        }
    }

    async fn handle_incoming(&self, incoming: iroh::endpoint::Incoming) {
        let connection = match timeout(HANDSHAKE_TIMEOUT, async { incoming.accept()?.await }).await
        {
            Ok(Ok(connection)) => connection,
            _ => return,
        };
        let connection_id = self.next_connection.fetch_add(1, Ordering::Relaxed) + 1;
        let raw_endpoint = hex(&connection.remote_id().as_bytes()[..]);
        let endpoint_id = match EndpointId::try_from(raw_endpoint.clone()) {
            Ok(id) => id,
            Err(_) => return,
        };
        let paired = match self.sqlite.device(&raw_endpoint).await {
            Ok(device) => device.is_some(),
            Err(error) => {
                eprintln!("Kompi pairing lookup failed: {}", safe_error(&error.to_string()));
                connection.close(4u32.into(), b"state unavailable");
                return;
            }
        };
        let lease = if paired {
            let lease = self.registry.replace_authorized(endpoint_id);
            if let Some((_, previous)) = self
                .active_physical
                .lock()
                .expect("active connection map poisoned")
                .remove(&raw_endpoint)
            {
                previous.close(3u32.into(), b"connection replaced");
            }
            lease
        } else {
            match self.registry.reserve(endpoint_id) {
                Ok(lease) => lease,
                Err(_) => {
                    if let Ok(Ok((send, recv))) =
                        timeout(HANDSHAKE_TIMEOUT, connection.accept_bi()).await
                    {
                        let _ = send_error_stream(
                            send,
                            recv,
                            "already-connected",
                            "This device already has a pending or live physical connection.",
                        )
                        .await;
                    }
                    connection.close(1u32.into(), b"already connected");
                    return;
                }
            }
        };
        let (send, recv) = match timeout(HANDSHAKE_TIMEOUT, connection.accept_bi()).await {
            Ok(Ok(streams)) => streams,
            _ => return,
        };
        if let Err(error) = send.set_priority(INTERACTIVE_STREAM_PRIORITY) {
            eprintln!("Kompi control stream priority unavailable: {error}");
            return;
        }
        let unverified =
            Connection::<Unverified>::new(connection.clone(), connection_id, send, recv, lease);
        if let Err(error) = self.serve_connection(unverified, &raw_endpoint).await {
            eprintln!(
                "Kompi peer session ended: {}",
                safe_error(&error.to_string())
            );
        }
        connection.close(0u32.into(), b"control stream ended");
    }

    async fn serve_connection(
        &self,
        connection: Connection<Unverified>,
        remote_endpoint: &str,
    ) -> Result<()> {
        let connection = connection.identify(remote_endpoint.to_owned())?;
        let mut connection = connection;
        let hello = Envelope::new(
            RequestId::parse(format!("hello-{}", uuid::Uuid::new_v4()))?,
            Message::Hello {
                capabilities: CAPABILITIES.iter().filter(|&&cap| cap != kompi_protocol::CHAT_TIMING_CAPABILITY || self.chat_timing.is_some()).map(|x| (*x).to_owned()).collect(),
                endpoint: Some(self.endpoint_hex()),
                session: None,
                idle_timeout_seconds: None,
            },
        );
        connection.send(&hello).await?;
        let first_msg = timeout(HANDSHAKE_TIMEOUT, connection.receive()).await??;
        let incoming: Envelope<Message> = kompi_protocol::decode_cbor(&first_msg)?;
        let hello_id = incoming.id;
        // Validate the legacy Hello shape, but do not let client defaults overwrite
        // the daemon-owned effective policy; changes arrive via SettingsUpdate.
        let _ = incoming.message.client_idle_timeout()?;
        let (attachment_capable, ui_capable, device_notification_capable, rich_notification_capable, reply_notification_capable, unified_push_capable, chat_stream_capable) = match incoming.message {
            Message::Hello { endpoint, capabilities, .. } => {
                if endpoint
                    .as_deref()
                    .is_some_and(|claimed| claimed != remote_endpoint)
                {
                    bail!("hello endpoint does not match authenticated Iroh identity");
                }
                (
                    capabilities.iter().any(|capability| capability == ATTACHMENTS_CAPABILITY),
                    capabilities.iter().any(|capability| capability == RPC_UI_CAPABILITY),
                    capabilities.iter().any(|capability| capability == DEVICE_NOTIFICATIONS_CAPABILITY),
                    capabilities.iter().any(|capability| capability == kompi_protocol::RICH_DEVICE_NOTIFICATIONS_CAPABILITY),
                    capabilities.iter().any(|capability| capability == kompi_protocol::REPLY_NOTIFICATIONS_CAPABILITY),
                    capabilities.iter().any(|capability| capability == UNIFIED_PUSH_CAPABILITY),
                    capabilities.iter().any(|capability| capability == kompi_protocol::CHAT_STREAMS_CAPABILITY),
                )
            }
            _ => bail!("expected hello as first peer message"),
        };
        if self.sqlite.device(remote_endpoint).await?.is_some() {
            let effective = Some(self.sqlite.effective_settings(remote_endpoint).await?.idle_timeout_seconds);
            let authorized = connection.authorize();
            let ready = authorized.become_ready();
            ready
                .send(&Envelope::new(
                    hello_id,
                    Message::Ack { configuration: None,
                        state: kompi_protocol::AckState::Accepted,
                        session_id: None,
                        conversation_id: None,
                        idle_timeout_seconds: effective,
                    },
                ))
                .await?;
            self.sqlite.mark_seen(remote_endpoint, now_ms()).await?;
            self.run_ready(ready, remote_endpoint, attachment_capable, ui_capable, device_notification_capable, rich_notification_capable, reply_notification_capable, unified_push_capable, chat_stream_capable).await
        } else {
            self.serve_pairing(connection, remote_endpoint, hello_id, attachment_capable, ui_capable, device_notification_capable, rich_notification_capable, reply_notification_capable, unified_push_capable, chat_stream_capable)
                .await
        }
    }

    async fn serve_pairing(
        &self,
        mut connection: Connection<Identified>,
        remote_endpoint: &str,
        hello_id: RequestId,
        attachment_capable: bool,
        ui_capable: bool,
        device_notification_capable: bool,
        rich_notification_capable: bool,
        reply_notification_capable: bool,
        unified_push_capable: bool,
        chat_stream_capable: bool,
    ) -> Result<()> {
        loop {
            let payload = timeout(HANDSHAKE_TIMEOUT, connection.receive()).await??;
            let request: Envelope<Message> = kompi_protocol::decode_cbor(&payload)?;
            match request.message {
                Message::Pair { secret, name } => {
                    let authorized = if self.sqlite.device(remote_endpoint).await?.is_some() {
                        // An already-paired identity may replay Pair after losing the Paired acknowledgement.
                        connection.authorize()
                    } else {
                        let credential = self
                            .credentials
                            .lock()
                            .expect("pairing map poisoned")
                            .remove(&secret);
                        let Some(credential) = credential else {
                            connection
                                .send_error(
                                    &request.id,
                                    "invalid-pairing",
                                    "Pairing code is invalid, expired, or already consumed.",
                                )
                                .await?;
                            continue;
                        };
                        if credential.expires_at < now_ms() || credential.name != name {
                            connection
                                .send_error(
                                    &request.id,
                                    "invalid-pairing",
                                    "Pairing code is invalid, expired, or already consumed.",
                                )
                                .await?;
                            continue;
                        }
                        self.sqlite.upsert_device(&Device {
                            endpoint: remote_endpoint.to_owned(),
                            name: credential.name,
                            paired_at: now_ms(),
                            last_seen: Some(now_ms()),
                            idle_timeout_seconds: None,
                        }).await?;
                        connection.authorize()
                    };
                    let effective_idle_timeout = self.sqlite.effective_settings(remote_endpoint).await?.idle_timeout_seconds;
                    let ready = authorized.become_ready();
                    ready
                        .send(&Envelope::new(
                            hello_id,
                            Message::Ack { configuration: None,
                                state: kompi_protocol::AckState::Accepted,
                                session_id: None,
                                conversation_id: None,
                                idle_timeout_seconds: Some(effective_idle_timeout),
                            },
                        ))
                        .await?;
                    ready
                        .send(&Envelope::new(
                            request.id,
                            Message::Paired {
                                endpoint: self.endpoint_hex(),
                            },
                        ))
                        .await?;
                    return self.run_ready(ready, remote_endpoint, attachment_capable, ui_capable, device_notification_capable, rich_notification_capable, reply_notification_capable, unified_push_capable, chat_stream_capable).await;
                }
                _ => {
                    connection
                        .send_error(
                            &request.id,
                            "pairing-required",
                            "Pairing is required before this command.",
                        )
                        .await?;
                    bail!("unexpected pre-authentication event");
                }
            }
        }
    }

    async fn run_ready(&self, mut connection: Connection<Ready>, endpoint: &str, attachment_capable: bool, ui_capable: bool, device_notification_capable: bool, rich_notification_capable: bool, reply_notification_capable: bool, unified_push_capable: bool, chat_stream_capable: bool) -> Result<()> {
        let generation = connection.generation();
        {
            let authorization = self.runtime_authorization(endpoint);
            let _authorization = authorization.write().await;
            anyhow::ensure!(self.sqlite.device(endpoint).await?.is_some(), "device was revoked before connection setup");
            let mut physical = self.active_physical.lock().expect("active connection map poisoned");
            if physical.get(endpoint).is_some_and(|(current, _)| *current > generation) { return Ok(()); }
            if let Some((_, old)) = physical.insert(endpoint.to_owned(), (generation, connection.transport().clone())) {
                old.close(2u32.into(), b"connection replaced");
            }
            self.active_senders.lock().expect("active sender map poisoned")
                .insert(endpoint.to_owned(), ActivePhoneSender {
                    generation, sender: connection.sender(), device_notification_capable,
                    rich_notification_capable, reply_notification_capable,
                });
        }
        // Secondary streams are accepted only after the primary control stream has
        // authenticated and authorized this peer. Each gets an isolated flow-control
        // window and a bounded concurrent handler; control dispatch never waits here.
        let bulk_connection = connection.transport().clone();
        let daemon = self.clone();
        let bulk_peer = endpoint.to_owned();
        let chat_routes: ChatStreamRoutes = Default::default();
        let secondary_routes = chat_routes.clone();
        let handshakes = Arc::new(tokio::sync::Semaphore::new(4));
        tokio::spawn(async move {
            loop {
                let (mut send, mut recv) = match bulk_connection.accept_bi().await {
                    Ok(streams) => streams,
                    Err(_) => break,
                };
                let Ok(handshake) = handshakes.clone().try_acquire_owned() else {
                    let _ = send.reset(2u32.into());
                    let _ = recv.stop(2u32.into());
                    continue;
                };
                let stream_daemon = daemon.clone();
                let stream_peer = bulk_peer.clone();
                let routes = secondary_routes.clone();
                tokio::spawn(async move {
                    let result = async {
                        let first = timeout(HANDSHAKE_TIMEOUT, read_frame(&mut recv)).await??.context("secondary stream closed before role handshake")?;
                        let open: Envelope<Message> = kompi_protocol::decode_cbor(&first)?;
                        if let Message::ConversationStreamOpen { conversation_id } = open.message {
                            anyhow::ensure!(chat_stream_capable, "chat stream capability was not negotiated");
                            stream_daemon.serve_chat_stream(send, recv, &stream_peer, generation, open.id, conversation_id, routes, handshake, attachment_capable, reply_notification_capable).await
                        } else {
                            let Ok(permit) = stream_daemon.secondary_stream_permits.clone().try_acquire_owned() else {
                                // Keep the handshake permit while issuing an overload reply.
                                return timeout(Duration::from_secs(2), refuse_secondary_stream(send, open.id)).await?;
                            };
                            drop(handshake);
                            let _permit = permit;
                            stream_daemon.serve_bulk_stream(send, recv, open, &stream_peer, generation, attachment_capable).await
                        }
                    }.await;
                    if let Err(error) = result {
                        eprintln!("Kompi secondary stream ended: {}", safe_error(&error.to_string()));
                    }
                });
            }
        });
        // UI snapshots have their own writer task. Never cancel a partial
        // length-prefixed control-stream read just to service a polling tick.
        let ui_task = if ui_capable {
            let daemon = self.clone();
            let peer = endpoint.to_owned();
            let sender = connection.sender();
            Some(tokio::spawn(async move {
                let mut tick = tokio::time::interval(Duration::from_millis(500));
                tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
                let mut last = HashMap::new();
                loop {
                    tick.tick().await;
                    if !daemon.is_current_connection(&peer, generation) { break; }
                    let mut pending = HashMap::new();
                    for id in daemon.worker.conversations_for(&peer).await {
                        let requests = daemon.worker.pending_ui(id).await;
                        if !requests.is_empty() { pending.insert(id, requests); }
                    }
                    let changed = pending.keys().chain(last.keys().filter(|id| !pending.contains_key(id)));
                    for &conversation_id in changed {
                        if last.get(&conversation_id) == pending.get(&conversation_id) { continue; }
                        let id = RequestId::parse(format!("ui-state-{}", uuid::Uuid::new_v4())).expect("generated UI state id");
                        let requests = pending.get(&conversation_id).cloned().unwrap_or_default();
                        if sender.send(&Envelope::new(id, Message::PiConversationUiState { conversation_id, requests })).await.is_err() { return; }
                    }
                    last = pending;
                }
            }))
        } else { None };
        let mut pi_frames = self.worker.subscribe_pi();
        let pi_workers = self.worker.clone();
        let sender = connection.sender();
        let daemon = self.clone();
        let peer = endpoint.to_owned();
        let pi_task = tokio::spawn(async move {
            loop {
                let publication = match pi_frames.recv().await {
                    Ok(publication) => publication,
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => {
                        let id = RequestId::parse(format!("pi-reset-{}", uuid::Uuid::new_v4())).expect("generated id");
                        if sender.send(&Envelope::new(id, Message::PiResetRequired {})).await.is_err() { break; }
                        continue;
                    }
                    Err(_) => break,
                };
                let frame = publication.frame;
                if !daemon.is_current_connection(&peer, generation) { break; }
                if !pi_workers.is_current_origin_for(&peer, frame.conversation_id, publication.origin).await { continue; }
                if let Some(owner) = publication.primary_generation {
                    if owner != generation { continue; }
                } else if chat_routes.active.lock().expect("chat routes poisoned").contains(&frame.conversation_id) { continue; }
                if sender.send(&pi_envelope(frame)).await.is_err() { break; }
            }
        });
        // Control messages and journal/tool notifications share dispatch, but
        // notification readiness must never interrupt a partially read frame.
        let mut input = connection.take_input();
        let (input_tx, input_rx) = mpsc::channel(8);
        let input_task = tokio::spawn(async move {
            loop {
                let frame = match crate::wire::read_ready_frame(&mut input, CONTROL_FRAME_TIMEOUT).await {
                    Ok(Some(frame)) => Ok(ReceivedControl { frame, received_at: std::time::Instant::now(), wall_ms: now_ms() }),
                    Ok(None) => Err(anyhow::anyhow!("peer closed the control stream")),
                    Err(error) => Err(error),
                };
                let complete = frame.is_err();
                if input_tx.send(frame).await.is_err() || complete { break; }
            }
        });
        let result = self.serve_ready(&connection, input_rx, endpoint, attachment_capable, ui_capable, reply_notification_capable, unified_push_capable).await;
        connection.transport().close(2u32.into(), b"control stream ended");
        input_task.abort();
        pi_task.abort();
        if let Some(task) = ui_task { task.abort(); }
        {
            let mut physical = self.active_physical.lock().expect("active connection map poisoned");
            if physical.get(endpoint).is_some_and(|(token, _)| *token == generation) {
                physical.remove(endpoint);
                self.active_senders.lock().expect("active sender map poisoned").remove(endpoint);
            }
        }
        let abandoned = self.pending_device_notifications.lock()
            .expect("device notification registry poisoned")
            .disconnect(endpoint, generation);
        for waiter in abandoned {
            let _ = waiter.send(NotificationReply {
                status: DeviceNotificationReceipt::Unknown,
                code: Some("connection-ended".into()),
                message: Some("The phone disconnected before confirming delivery. It may have posted the notification; do not retry automatically.".into()),
            });
        }
        result
    }

    async fn serve_bulk_stream(
        &self,
        mut send: SendStream,
        mut recv: RecvStream,
        open: Envelope<Message>,
        endpoint: &str,
        generation: u64,
        attachment_capable: bool,
    ) -> Result<()> {
        send.set_priority(BULK_STREAM_PRIORITY)?;
        let role = match open.message {
            Message::PiArchiveGet { conversation_id } => {
                return timeout(Duration::from_secs(30), self.serve_pi_archive(send, endpoint, generation, open.id, conversation_id)).await?;
            }
            Message::ArtifactGet { conversation_id, artifact_id } => {
                if !self.is_current_connection(endpoint, generation) || self.sqlite.device(endpoint).await?.is_none() {
                    bail!("bulk stream authorization was revoked or replaced");
                }
                let artifact = self.worker.artifacts.get(endpoint, conversation_id, artifact_id);
                return timeout(Duration::from_secs(120), artifact::serve(send, open.id, artifact)).await?;
            }
            Message::StreamOpen { role: kompi_protocol::StreamRole::AttachmentTransfer } if attachment_capable => kompi_protocol::StreamRole::AttachmentTransfer,
            Message::StreamOpen { role: kompi_protocol::StreamRole::ChatControl } => bail!("chat-control role must use the primary stream"),
            Message::StreamOpen { role: kompi_protocol::StreamRole::AttachmentTransfer } => bail!("attachment role was not negotiated"),
            _ => bail!("invalid initial request on bulk stream"),
        };
        let accepted = Envelope::new(open.id, Message::Ack { configuration: None,
            state: kompi_protocol::AckState::Accepted,
            session_id: None,
            conversation_id: None,
            idle_timeout_seconds: None,
        });
        write_frame(&mut send, &kompi_protocol::encode_cbor(&accepted)?).await?;

        loop {
            let current = self.is_current_connection(endpoint, generation);
            if !current || self.sqlite.device(endpoint).await?.is_none() { bail!("bulk stream authorization was revoked or replaced"); }
            let Some(payload) = read_frame(&mut recv).await? else { return Ok(()) };
            let request: Envelope<Message> = kompi_protocol::decode_cbor(&payload)?;
            let response = match (role, request.message) {
                (kompi_protocol::StreamRole::AttachmentTransfer, Message::AttachmentChunk { upload_id, offset, data }) => {
                    match self.attachments.append(endpoint, attachment::UploadId::new(*upload_id.as_uuid()), offset, data.as_bytes()).await {
                        Ok(progress) => Envelope::new(request.id, Message::AttachmentProgress { upload_id, received_bytes: progress.received_bytes }),
                        Err(error) => Envelope::new(request.id, attachment_error("attachment-chunk-failed", &safe_error(&error.to_string()), upload_id)),
                    }
                }
                (kompi_protocol::StreamRole::AttachmentTransfer, Message::AttachmentFinish { upload_id }) => {
                    match self.attachments.finish(endpoint, attachment::UploadId::new(*upload_id.as_uuid())).await {
                        Ok(accepted) => {
                            let offer = accepted.offer;
                            Envelope::new(request.id, Message::AttachmentAccepted {
                                upload_id, file_name: offer.file_name.as_str().to_owned(),
                                media_type: offer.media_type.as_str().to_owned(), length: offer.length,
                                sha256: offer.sha256.as_hex(),
                            })
                        }
                        Err(error) => Envelope::new(request.id, attachment_error("attachment-finish-failed", &safe_error(&error.to_string()), upload_id)),
                    }
                }
                _ => bail!("message is invalid for the negotiated bulk stream role"),
            };
            write_frame(&mut send, &kompi_protocol::encode_cbor(&response)?).await?;
        }
    }

    async fn serve_pi_archive(
        &self,
        mut send: SendStream,
        endpoint: &str,
        generation: u64,
        request_id: RequestId,
        conversation_id: kompi_protocol::ConversationId,
    ) -> Result<()> {
        let authorized = || async {
            Ok::<bool, anyhow::Error>(self.is_current_connection(endpoint, generation)
                && self.sqlite.device(endpoint).await?.is_some())
        };
        anyhow::ensure!(authorized().await?, "archive stream authorization was revoked or replaced");
        let home = dirs::home_dir().context("daemon account has no home directory")?;
        let row = self.history_conversation(endpoint, conversation_id, &home.display().to_string()).await?;
        let saved = row.filter(|row| !matches!(row.state, ConversationState::Idle | ConversationState::Busy))
            .and_then(|row| row.session_id.map(|id| (id, row.session_file)));
        // Acquire before spawning so sleeping/slow disks cannot accumulate blocking
        // jobs if the async stream times out and releases its own stream permit.
        static ARCHIVE_READS: tokio::sync::Semaphore = tokio::sync::Semaphore::const_new(2);
        let snapshot = if let (Some((session_id, path)), Ok(permit)) = (saved, ARCHIVE_READS.try_acquire()) {
            tokio::task::spawn_blocking(move || {
                let _permit = permit;
                crate::pi_archive::snapshot(std::path::Path::new(&path), &session_id).map(|json| (session_id, json))
            }).await?.ok()
        } else {
            None
        };
        anyhow::ensure!(authorized().await?, "archive stream authorization was revoked or replaced");
        if let Some((session_id, json)) = snapshot {
            let mut remaining = json.as_str();
            let mut part = 0;
            let epoch = uuid::Uuid::new_v4().to_string();
            while !remaining.is_empty() {
                // Split only at scalar boundaries; at most 8,192 bytes / UTF-16 units.
                let mut end = remaining.len().min(8192);
                while !remaining.is_char_boundary(end) { end -= 1; }
                let (fragment, rest) = remaining.split_at(end);
                let frame = kompi_protocol::PiFrame {
                    conversation_id, session_id: session_id.clone(), epoch: epoch.clone(), sequence: 0,
                    part, last: rest.is_empty(), kind: kompi_protocol::PiFrameKind::Snapshot,
                    json: fragment.to_owned(), request_id: None,
                };
                let envelope = Envelope::new(request_id.clone(), Message::PiFrame { frame });
                write_frame(&mut send, &kompi_protocol::encode_cbor(&envelope)?).await?;
                remaining = rest;
                part += 1;
            }
        } else {
            // Do not reveal whether another phone owns an ID or expose a file path.
            let message = Message::Error { code: "pi-archive-unavailable".into(),
                message: "This conversation could not be loaded.".into(), session_id: None, conversation_id: Some(conversation_id) };
            write_frame(&mut send, &kompi_protocol::encode_cbor(&Envelope::new(request_id, message))?).await?;
        }
        send.finish()?;
        Ok(())
    }

    async fn push_dispatch_loop(&self) {
        let mut ticker=tokio::time::interval(Duration::from_secs(2));
        loop {
            ticker.tick().await;
            if !*self.running.borrow() { break; }
            if let Err(error)=self.dispatch_due_pushes().await {
                eprintln!("Kompi push dispatcher unavailable: {}",safe_error(&error.to_string()));
            }
        }
    }

    async fn dispatch_due_pushes(&self) -> Result<()> {
        let now=now_ms();
        self.sqlite.expire_pushes(now).await?;
        for (peer,request_id,registration_id,ciphertext,expires_at_ms,attempts) in self.sqlite.due_pushes(now,8).await? {
            // Serialize final authorization + provider POST with host/origin revocation and registration rotation.
            // A POST already accepted by the provider before revoke acquires these locks cannot be recalled.
            let _policy_guard=self.push_policy_lock.lock().await;
            let peer_lock=self.peer_dispatch_lock(&peer);
            let _dispatch_guard=peer_lock.lock().await;
            // Lock acquisition and earlier providers may consume most of the TTL; use a
            // fresh clock value for the final authorization and provider TTL.
            let dispatch_now=now_ms();
            let registration=match self.sqlite.push_registration(&peer).await? {
                Some(registration) if registration.registration_id==registration_id => registration,
                _ => { self.sqlite.update_push(&peer,&request_id,"not-posted",Some("registration-rotated"),attempts,now,true).await?; continue; }
            };
            if expires_at_ms<=dispatch_now {
                self.sqlite.update_push(&peer,&request_id,"not-posted",Some("expired"),attempts,dispatch_now,true).await?;
                continue;
            }
            let secret=match self.decrypt_push_secrets(&peer,&registration_id,&registration.encrypted_secrets) { Ok(secret)=>secret,Err(_)=> {
                self.sqlite.update_push(&peer,&request_id,"not-posted",Some("registration-secret-invalid"),attempts,now,true).await?;continue;
            }};
            let endpoint_text=match std::str::from_utf8(&secret[50..]) { Ok(endpoint)=>endpoint,Err(_)=> {
                self.sqlite.update_push(&peer,&request_id,"not-posted",Some("registration-endpoint-invalid"),attempts,now,true).await?;continue;
            }};
            let allowed=self.sqlite.push_allowed_origins().await?;
            let endpoint_url=match url::Url::parse(endpoint_text) { Ok(url) if url.scheme()=="https" && url.origin().ascii_serialization()==registration.origin && allowed.iter().any(|origin| origin==&registration.origin) => url, _=> {
                self.sqlite.update_push(&peer,&request_id,"unavailable",Some("origin-revoked"),attempts,now,true).await?;continue;
            }};
            let auth=URL_SAFE_NO_PAD.encode(&secret[..16]);
            let subscription=web_push::SubscriptionInfo::new(endpoint_text.to_owned(),registration.p256dh.clone(),auth);
            // The outbox contains the already RFC8291-encrypted bytes only; the worker never reconstructs plaintext.
            let result=send_web_push(&endpoint_url,&subscription,&ciphertext,(expires_at_ms-dispatch_now).max(1) as u64).await;
            match result {
                Ok(status) if status.is_success() => self.sqlite.update_push(&peer,&request_id,"push-accepted",None,attempts+1,expires_at_ms,true).await?,
                Ok(status) if status.as_u16()==404 || status.as_u16()==410 => {
                    let _=self.sqlite.remove_push_registration(&peer,&registration_id).await?;
                    self.sqlite.update_push(&peer,&request_id,"not-posted",Some("provider-subscription-gone"),attempts+1,expires_at_ms,true).await?;
                }
                Ok(status) if status.is_client_error() && status.as_u16()!=429 => self.sqlite.update_push(&peer,&request_id,"not-posted",Some("provider-rejected"),attempts+1,expires_at_ms,true).await?,
                Err(_) | Ok(_) => {
                    let retry=attempts+1;
                    let backoff=2i64.saturating_pow(retry.min(11) as u32).min(3600)*1000;
                    self.sqlite.update_push(&peer,&request_id,"retry",Some("provider-temporary-failure"),retry,(dispatch_now+backoff).min(expires_at_ms),false).await?;
                }
            }
        }
        Ok(())
    }

    async fn sweep_idle(&self) -> Result<()> {
        self.attachments.cleanup_expired().await?;
        // Compact lifecycle/settings notifications, never permanent request identities.
        // Conversation replay belongs exclusively to Pi.
        for device in self.sqlite.devices().await? {
            let peer = crate::sqlite_store::PeerKey::try_from(device.endpoint)?;
            self.sqlite.compact_journal(&peer, 10_000).await?;
        }
        // The actor owns live monotonic expiry. Persist actor-approved live expirations
        // before considering wall-clock candidates restored after a daemon restart.
        let home = dirs::home_dir().context("daemon account has no home directory")?;
        let home = fs::canonicalize(home).context("daemon account home directory is unavailable")?;
        let home = home.display().to_string();
        let now = now_ms();
        for device in self.sqlite.devices().await? {
            for (id, lane) in self.peer_lanes(&device.endpoint) {
                let Ok(_guard) = lane.admission.try_lock() else { continue; };
                if lane.owner.expire_if_idle(tokio::time::Instant::now(), false).await? {
                    if let Some(row) = self.sqlite.conversation_by_id(&device.endpoint, id, &home).await? {
                        self.expire_conversation(row, &lane, now).await?;
                    }
                }
            }
        }
        let candidates = self.sqlite.expired_idle_candidates(now, &home).await?;
        for row in candidates {
            let lane = self.prompt_lane(&row.endpoint, row.id);
            let Ok(_guard) = lane.admission.try_lock() else { continue; };
            if !self.sqlite.conversation_by_id(&row.endpoint, row.id, &home).await?.is_some_and(|current|
                current.state == ConversationState::Idle && current.idle_timeout_seconds.is_some_and(|ttl| current.last_user_prompt_at.saturating_add(i64::from(ttl.get()) * 1000) <= now)
            ) { continue; }
            if lane.owner.expire_if_idle(tokio::time::Instant::now(), true).await? {
                self.expire_conversation(row, &lane, now).await?;
            }
        }
        let workers = self.worker.runtime_ids().await;
        self.prompt_lanes.lock().expect("prompt lanes poisoned")
            .retain(|(_, id), lane| workers.contains(id) || Arc::strong_count(lane) > 1);
        Ok(())
    }

    async fn expire_conversation(&self, row: crate::store::ConversationRecord, lane: &PromptLane, now: i64) -> Result<()> {
        if row.state == ConversationState::Idle {
            self.worker.dispose(row.id).await?;
            self.sqlite.persist_session_expiry(&row, now).await?;
            lane.owner.close_if_idle().await?;
        }
        Ok(())
    }

    async fn serve_ready(&self, connection: &Connection<Ready>, mut input: mpsc::Receiver<Result<ReceivedControl>>, endpoint: &str, attachment_capable: bool, ui_capable: bool, reply_notification_capable: bool, unified_push_capable: bool) -> Result<()> {
        // A subscription stores only its durable cursor. Notifications are bounded by
        // the journal page size; if the client or socket falls behind, the next page
        // is read from SQLite rather than accumulated in an in-memory queue.
        let mut sync_cursor: Option<kompi_protocol::SyncCursor> = None;
        let timing_pages = Arc::new(tokio::sync::Semaphore::new(1));
        let snapshots = Arc::new(tokio::sync::Semaphore::new(8));
        let mut revisions = self.sqlite.subscribe_events();
        let mut catch_up_pending = false;
        let mut android_tools = self.worker.android_tools.subscribe();
        let mut pending_android_tools = PendingAndroidTools::default();
        let mut history_browse: Option<pi_history_transport::HistoryBrowse> = None;
        loop {
            let generation = connection.generation();
            if !self.is_current_connection(endpoint, generation) {
                return Ok(());
            }
            if self.sqlite.device(endpoint).await?.is_none() {
                connection
                    .send(&Envelope::new(
                        RequestId::parse("revoked")?,
                        Message::Error {
                            code: "peer-revoked".into(),
                            message: "This device has been revoked.".into(),
                            session_id: None,
                            conversation_id: None,
                        },
                    ))
                    .await?;
                return Ok(());
            }
            let raw = tokio::select! {
                incoming = input.recv() => incoming.context("control stream reader stopped")??,
                tool = android_tools.recv() => {
                    let tool = match tool {
                        Ok(tool) => tool,
                        Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                        Err(tokio::sync::broadcast::error::RecvError::Closed) => continue,
                    };
                    match tool.event {
                        WorkerEvent::AndroidToolRequest { conversation_id, request_id, tool: kind, timeout_ms, action_json } => {
                            if !self.worker.is_current_origin_for(endpoint, conversation_id, tool.origin).await || !self.is_current_connection(endpoint, generation) { continue; }
                            let now = std::time::Instant::now();
                            for (request_id, conversation_id) in pending_android_tools.prune(now) {
                                let id = RequestId::parse(format!("android-tool-resolved-{}", uuid::Uuid::new_v4()))?;
                                connection.send(&Envelope::new(id, Message::AndroidToolResolved { conversation_id, request_id })).await?;
                            }
                            if pending_android_tools.count_for(conversation_id) >= 8 || pending_android_tools.len() >= 128 { continue; }
                            let deadline = now + Duration::from_millis(timeout_ms as u64);
                            if !pending_android_tools.register(request_id.clone(), conversation_id, deadline) { continue; }
                            let wire_id = RequestId::parse(format!("android-tool-{}", uuid::Uuid::new_v4()))?;
                            connection.send(&Envelope::new(wire_id, Message::AndroidToolRequest {
                                conversation_id, request_id, tool: kind, timeout_ms: timeout_ms.min(ANDROID_TOOL_TIMEOUT_MS), action_json,
                            })).await?;
                        }
                        WorkerEvent::AndroidToolCancel { conversation_id, request_id } => {
                            if pending_android_tools.retire(&request_id, conversation_id) {
                                let wire_id = RequestId::parse(format!("android-tool-resolved-{}", uuid::Uuid::new_v4()))?;
                                connection.send(&Envelope::new(wire_id, Message::AndroidToolResolved { conversation_id, request_id })).await?;
                            }
                        }
                        _ => {}
                    }
                    continue;
                }
                notified = async {
                    if catch_up_pending { Ok(()) } else { revisions.changed().await }
                }, if catch_up_pending || sync_cursor.is_some() => {
                    if notified.is_err() { bail!("sync journal notifier closed"); }
                    let _ = revisions.borrow_and_update();
                    let Some(cursor) = sync_cursor.as_ref() else { continue };
                    let peer = crate::sqlite_store::PeerKey::try_from(endpoint.to_owned())?;
                    let after = cursor.as_i64();
                    let (floor, current) = self.sqlite.journal_bounds(&peer).await?;
                    if after < floor {
                        connection.send(&Envelope::new(
                            RequestId::parse(format!("sync-reset-{}", uuid::Uuid::new_v4()))?,
                            Message::SyncResetRequired { current_cursor: kompi_protocol::SyncCursor::parse(current.to_string())? },
                        )).await?;
                        sync_cursor = None;
                        catch_up_pending = false;
                        continue;
                    }
                    let (events, next_cursor) = match self.sqlite.sync_events_after(&peer, cursor, kompi_protocol::MAX_SYNC_EVENTS as u8).await {
                        Ok(page) => page,
                        Err(error) if error.to_string().contains("fresh snapshot required") => {
                            let (_, current) = self.sqlite.journal_bounds(&peer).await?;
                            connection.send(&Envelope::new(
                                RequestId::parse(format!("sync-reset-{}", uuid::Uuid::new_v4()))?,
                                Message::SyncResetRequired { current_cursor: kompi_protocol::SyncCursor::parse(current.to_string())? },
                            )).await?;
                            sync_cursor = None;
                            catch_up_pending = false;
                            continue;
                        }
                        Err(error) => return Err(error),
                    };
                    let still_current = self.active_physical.lock().expect("active connection map poisoned")
                        .get(endpoint).is_some_and(|(active, _)| *active == generation);
                    if !still_current { return Ok(()); }
                    let full_page = events.len() == kompi_protocol::MAX_SYNC_EVENTS;
                    if !events.is_empty() {
                        connection.send(&Envelope::new(
                            RequestId::parse(format!("sync-push-{}", uuid::Uuid::new_v4()))?,
                            Message::SyncEvents { events, next_cursor: next_cursor.clone() },
                        )).await?;
                        sync_cursor = Some(next_cursor);
                    }
                    catch_up_pending = full_page;
                    continue;
                }
            };
            let still_current = self
                .active_physical
                .lock()
                .expect("active connection map poisoned")
                .get(endpoint)
                .is_some_and(|(current, _)| *current == generation);
            if !still_current {
                return Ok(());
            }
            let request: Envelope<Message> = kompi_protocol::decode_cbor(&raw.frame)?;
            let (message, trace) = match request.message {
                message @ Message::ConversationPrompt { attempt_id: Some(attempt), conversation_id, .. } => {
                    let trace = self.chat_timing.as_ref().map(|audit| {
                        let mut trace = audit.request(endpoint, request.id.clone(), attempt);
                        trace.conversation = Some(conversation_id);
                        trace
                    });
                    if let Some(trace) = &trace { trace.record_at(Timing::DaemonReceipt, raw.received_at, raw.wall_ms); }
                    (message, trace)
                }
                Message::TimedPrompt { attempt_id, text, delivery, configuration, attachments } => {
                    let trace = self.chat_timing.as_ref().map(|audit| audit.request(endpoint, request.id.clone(), attempt_id));
                    if let Some(trace) = &trace { trace.record_at(Timing::DaemonReceipt, raw.received_at, raw.wall_ms); }
                    (Message::Prompt { text, delivery, configuration, attachments }, trace)
                }
                message => (message, None),
            };
            match message {
                Message::ChatTimingGet { cursor } => {
                    if let (Some(audit), Ok(permit)) = (&self.chat_timing, timing_pages.clone().try_acquire_owned()) {
                        let audit = audit.clone();
                        let phone = endpoint.to_owned();
                        let sender = connection.sender();
                        let active = self.active_physical.clone();
                        tokio::spawn(async move {
                            let _permit = permit;
                            if let Ok(page) = audit.page(&phone, cursor).await {
                                let current = active.lock().expect("active connection map poisoned")
                                    .get(&phone).is_some_and(|(active, _)| *active == generation);
                                if current { let _ = sender.send(&Envelope::new(request.id, page)).await; }
                            }
                        });
                    }
                }
                Message::PushRegister { registration } => {
                    let registration_id = registration.registration_id;
                    let (status, code) = if unified_push_capable {
                        self.register_push(endpoint, generation, registration).await
                    } else {
                        (PushRegistrationStatus::Rejected, Some(PushRegistrationErrorCode::PushDisabled))
                    };
                    connection.send(&Envelope::new(request.id, Message::PushRegistrationResult {
                        registration_id,
                        status,
                        code,
                    })).await?;
                }
                Message::PushUnregister { registration_id } => {
                    let (status, code) = self.unregister_push(endpoint, generation, registration_id).await;
                    connection.send(&Envelope::new(request.id, Message::PushRegistrationResult { registration_id, status, code })).await?;
                }
                Message::DeviceNotificationResponse { request_id: notification_id, status, code, message }
                    if request.id == notification_id => {
                    if let Some(reply) = self.pending_device_notifications.lock()
                        .expect("device notification registry poisoned")
                        .resolve(endpoint, &notification_id, generation, std::time::Instant::now())
                    {
                        let _ = reply.send(NotificationReply { status, code, message });
                    }
                }
                Message::AndroidToolResponse { conversation_id, request_id: tool_request_id, success, result_json, error_code, error_message } => {
                    match pending_android_tools.consume(&tool_request_id, conversation_id, std::time::Instant::now()) {
                        ConsumeResult::Expired => {
                            let resolved_id = RequestId::parse(format!("android-tool-resolved-{}", uuid::Uuid::new_v4()))?;
                            connection.send(&Envelope::new(resolved_id, Message::AndroidToolResolved { conversation_id, request_id: tool_request_id })).await?;
                            continue;
                        }
                        ConsumeResult::Missing | ConsumeResult::WrongConversation => continue,
                        ConsumeResult::Accepted => {}
                    }
                    let daemon = self.clone();
                    let sender = connection.sender();
                    let peer = endpoint.to_owned();
                    tokio::spawn(async move {
                        if !daemon.is_current_connection(&peer, generation) { return; }
                        if daemon.worker.belongs_to(&peer, conversation_id).await {
                            let result = daemon.worker.reply_android_tool(conversation_id, tool_request_id.clone(), success, result_json, error_code, error_message).await;
                            if result.is_err() {
                                eprintln!("Kompi Android tool response could not reach its current Pi worker; request was not replayed");
                            }
                        }
                        let id = RequestId::parse(format!("android-tool-resolved-{}", uuid::Uuid::new_v4())).expect("generated response ID");
                        let _ = sender.send(&Envelope::new(id, Message::AndroidToolResolved { conversation_id, request_id: tool_request_id })).await;
                    });
                }
                Message::PiUiResponse { conversation_id, interaction_id, answer } => {
                    let daemon = self.clone();
                    let sender = connection.sender();
                    let peer = endpoint.to_owned();
                    tokio::spawn(async move {
                        let accepted = ui_capable && daemon.worker.belongs_to(&peer, conversation_id).await
                            && daemon.is_current_connection(&peer, generation)
                            && daemon.worker.reply_ui(conversation_id, interaction_id, answer).await.is_ok();
                        let message = if accepted {
                            Message::Ack { configuration: None, state: kompi_protocol::AckState::Accepted, session_id: None, conversation_id: Some(conversation_id), idle_timeout_seconds: None }
                        } else {
                            activation_error(conversation_id, "pi-ui-response-rejected", "This question is no longer pending or the answer does not match it.")
                        };
                        let _ = sender.send(&Envelope::new(request.id, message)).await;
                    });
                }
                Message::AttachmentOffer { upload_id, file_name, media_type, length, sha256 } => {
                    if !attachment_capable {
                        connection.send(&Envelope::new(request.id, attachment_error("capability-required", "Client did not negotiate attachments.", upload_id))).await?;
                        continue;
                    }
                    let result = attachment::Offer::new(
                        attachment::UploadId::new(*upload_id.as_uuid()), file_name, media_type, length, &sha256,
                    );
                    self.attachments.cleanup_expired().await?;
                    match result {
                        Ok(offer) => match self.attachments.offer(endpoint, offer).await {
                            Ok(ready) => connection.send(&Envelope::new(request.id, Message::AttachmentReady {
                                upload_id, offset: ready.offset, chunk_size: ready.chunk_size,
                            })).await?,
                            Err(error) => connection.send(&Envelope::new(request.id, attachment_error("attachment-offer-failed", &safe_error(&error.to_string()), upload_id))).await?,
                        },
                        Err(error) => connection.send(&Envelope::new(request.id, attachment_error("attachment-offer-failed", &safe_error(&error.to_string()), upload_id))).await?,
                    }
                }
                Message::AttachmentChunk { upload_id, offset, data } => {
                    if !attachment_capable {
                        connection.send(&Envelope::new(request.id, attachment_error("capability-required", "Client did not negotiate attachments.", upload_id))).await?;
                        continue;
                    }
                    match self.attachments.append(endpoint, attachment::UploadId::new(*upload_id.as_uuid()), offset, data.as_bytes()).await {
                        Ok(progress) => connection.send(&Envelope::new(request.id, Message::AttachmentProgress { upload_id, received_bytes: progress.received_bytes })).await?,
                        Err(error) => connection.send(&Envelope::new(request.id, attachment_error("attachment-chunk-failed", &safe_error(&error.to_string()), upload_id))).await?,
                    }
                }
                Message::AttachmentFinish { upload_id } => {
                    if !attachment_capable {
                        connection.send(&Envelope::new(request.id, attachment_error("capability-required", "Client did not negotiate attachments.", upload_id))).await?;
                        continue;
                    }
                    match self.attachments.finish(endpoint, attachment::UploadId::new(*upload_id.as_uuid())).await {
                        Ok(accepted) => {
                            let offer = accepted.offer;
                            connection.send(&Envelope::new(request.id, Message::AttachmentAccepted {
                                upload_id, file_name: offer.file_name.as_str().to_owned(),
                                media_type: offer.media_type.as_str().to_owned(), length: offer.length,
                                sha256: offer.sha256.as_hex(),
                            })).await?;
                        }
                        Err(error) => connection.send(&Envelope::new(request.id, attachment_error("attachment-finish-failed", &safe_error(&error.to_string()), upload_id))).await?,
                    }
                }
                Message::Ping {} => {
                    connection
                        .send(&Envelope::new(request.id, Message::Pong {}))
                        .await?
                }
                Message::Pair { .. } => {
                    connection
                        .send(&Envelope::new(
                            request.id,
                            Message::Paired {
                                endpoint: self.endpoint_hex(),
                            },
                        ))
                        .await?
                }
                message @ (Message::Prompt { .. } | Message::ConversationPrompt { .. }) => {
                    let (target, create_if_missing, text, delivery, configuration, attachments) = match message {
                        Message::Prompt { text, delivery, configuration, attachments } => (None, false, text, delivery, configuration, attachments),
                        Message::ConversationPrompt { conversation_id, create_if_missing, text, delivery, configuration, attachments, .. } => (Some(conversation_id), create_if_missing.unwrap_or(false), text, delivery, configuration, attachments),
                        _ => unreachable!(),
                    };
                    if attachments.is_some() && !attachment_capable {
                        timing(&trace, Timing::DaemonRejected);
                        connection.send(&Envelope::new(request.id, Message::Error {
                            code: "capability-required".into(), message: "Client did not negotiate attachments.".into(),
                            session_id: None, conversation_id: None,
                        })).await?;
                        continue;
                    }
                    let sender = connection.sender();
                    let daemon = self.clone();
                    let peer = endpoint.to_owned();
                    let request_id = request.id;
                    tokio::spawn(async move {
                        if let Err(error) = daemon
                            .handle_prompt(sender, &peer, generation, request_id, text, delivery, configuration, attachments, target, create_if_missing, trace.clone(), reply_notification_capable)
                            .await
                        {
                            timing(&trace, Timing::DaemonHandlerFailed);
                            eprintln!(
                                "Kompi SDK request failed: {}",
                                safe_error(&error.to_string())
                            );
                        }
                    });
                }
                Message::PiSnapshotGet {} | Message::PiConversationSnapshotGet { .. } => {
                    let target = match message {
                        Message::PiConversationSnapshotGet { conversation_id } => Some(conversation_id),
                        _ => None,
                    };
                    let Ok(permit) = snapshots.clone().try_acquire_owned() else {
                        connection.send(&Envelope::new(request.id, snapshot_error(target))).await?;
                        continue;
                    };
                    let daemon = self.clone();
                    let sender = connection.sender();
                    let peer = endpoint.to_owned();
                    tokio::spawn(async move {
                        let _permit = permit;
                        if daemon.reconcile_pi(&peer, generation, target, &sender, &request.id).await.is_err() {
                            let _ = sender.send(&Envelope::new(request.id, snapshot_error(target))).await;
                        }
                    });
                }
                Message::ModelsGet {} => {
                    let Ok(permit) = self.worker.catalog_requests.clone().try_acquire_owned() else {
                        connection.send(&Envelope::new(request.id, Message::Error { code: "models-busy".into(), message: "Model discovery is already running. Retry shortly.".into(), session_id: None, conversation_id: None })).await?;
                        continue;
                    };
                    let worker = self.worker.clone();
                    let sender = connection.sender();
                    tokio::spawn(async move {
                        let _permit = permit;
                        let message = match worker.models().await {
                            Ok(catalog) => Message::ModelsResult { catalog },
                            Err(_) => Message::Error { code: "models-unavailable".into(), message: "Could not load configured Pi models. Check Pi on the computer and retry.".into(), session_id: None, conversation_id: None },
                        };
                        let _ = sender.send(&Envelope::new(request.id, message)).await;
                    });
                }
                Message::SettingsGet {} => {
                    let settings = self.sqlite.effective_settings(endpoint).await?;
                    connection.send(&Envelope::new(request.id, Message::SettingsResult { settings })).await?;
                }
                Message::SettingsUpdate { update_id, settings } => {
                    let updated = async {
                        let auth = self.runtime_authorization(endpoint);
                        let _guard = auth.write().await;
                        self.sqlite.update_settings(endpoint, &settings, now_ms()).await?;
                        let ttl = Duration::from_secs(u64::from(settings.idle_timeout_seconds.get()));
                        for (_, lane) in self.peer_lanes(endpoint) {
                            lane.owner.set_ttl(ttl).await?;
                        }
                        Ok::<_, anyhow::Error>(())
                    }.await;
                    if updated.is_ok() {
                        let daemon = self.clone();
                        tokio::spawn(async move { let _ = daemon.sweep_idle().await; });
                    }
                    match updated {
                        Ok(()) => connection.send(&Envelope::new(request.id, Message::SettingsConfirmed {
                            update_id,
                            settings,
                        })).await?,
                        Err(error) => connection.send(&Envelope::new(request.id, Message::SettingsRejected {
                            update_id,
                            code: "settings-rejected".into(),
                            message: safe_error(&error.to_string()),
                        })).await?,
                    }
                }
                Message::Sync { .. } => {
                    let peer = crate::sqlite_store::PeerKey::try_from(endpoint.to_owned())?;
                    let home = dirs::home_dir().context("daemon account has no home directory")?;
                    let home = fs::canonicalize(home).context("daemon account home directory is unavailable")?;
                    let snapshot = self.sqlite.sync_snapshot(&peer, &home.display().to_string()).await?;
                    if !self.active_physical.lock().expect("active connection map poisoned")
                        .get(endpoint).is_some_and(|(active, _)| *active == generation) { return Ok(()); }
                    sync_cursor = Some(snapshot.cursor.clone());
                    connection.send(&Envelope::new(request.id, Message::SyncSnapshot {
                        cursor: snapshot.cursor,
                        state: kompi_protocol::SyncState::Synchronizing,
                        settings: snapshot.settings,
                        sessions: snapshot.sessions,
                        outstanding_requests: snapshot.requests,
                        has_more_sessions: snapshot.has_more_sessions,
                        has_more_requests: snapshot.has_more_requests,
                    })).await?;
                }
                Message::SyncPage { after, limit } => {
                    let peer = crate::sqlite_store::PeerKey::try_from(endpoint.to_owned())?;
                    let after = after.unwrap_or(kompi_protocol::SyncCursor::parse("0".to_owned())?);
                    match self.sqlite.sync_events_after(&peer, &after, limit).await {
                        Ok((events, next_cursor)) => {
                            if !self.active_physical.lock().expect("active connection map poisoned")
                                .get(endpoint).is_some_and(|(active, _)| *active == generation) { return Ok(()); }
                            sync_cursor = Some(next_cursor.clone());
                            connection.send(&Envelope::new(request.id, Message::SyncEvents { events, next_cursor })).await?;
                        }
                        Err(error) if error.to_string().contains("fresh snapshot required") => {
                            let (_, current) = self.sqlite.journal_bounds(&peer).await?;
                            sync_cursor = None;
                            connection.send(&Envelope::new(request.id, Message::SyncResetRequired {
                                current_cursor: kompi_protocol::SyncCursor::parse(current.to_string())?,
                            })).await?;
                        }
                        Err(error) => return Err(error),
                    }
                }
                Message::RequestPage { after, limit } => {
                    let peer = crate::sqlite_store::PeerKey::try_from(endpoint.to_owned())?;
                    let (requests, next_cursor) = self.sqlite.request_page(&peer, after.as_ref(), limit).await?;
                    connection.send(&Envelope::new(request.id, Message::RequestPageResult { requests, next_cursor })).await?;
                }
                Message::SessionPage { after, limit } => {
                    let peer = crate::sqlite_store::PeerKey::try_from(endpoint.to_owned())?;
                    let home = dirs::home_dir().context("daemon account has no home directory")?;
                    let home = fs::canonicalize(home).context("daemon account home directory is unavailable")?;
                    let (sessions, next_cursor) = self.sqlite.session_page(&peer, after, limit, &home.display().to_string()).await?;
                    connection.send(&Envelope::new(request.id, Message::SessionPageResult { sessions, next_cursor })).await?;
                }
                Message::PiHistoryPage { after, limit } => {
                    if after.is_none() || history_browse.is_none() {
                        history_browse = self.history_browse(endpoint).await.ok();
                    }
                    let message = match &history_browse {
                        Some(browse) => {
                            let (sessions, next_cursor) = browse.page(after, limit);
                            if next_cursor.is_none() { history_browse = None; }
                            Message::PiHistoryPageResult { sessions, next_cursor }
                        }
                        None => Message::Error { code: "pi-history-unavailable".into(), message: "Could not load Pi history from this computer.".into(), session_id: None, conversation_id: None },
                    };
                    connection.send(&Envelope::new(request.id, message)).await?;
                }
                Message::ConversationDelete { conversation_id } => {
                    history_browse = None;
                    let message = match self.delete_history_conversation(endpoint, connection.generation(), conversation_id).await {
                        Ok(message) => message,
                        Err(_) => activation_error(conversation_id, "conversation-delete-failed", "Could not delete this conversation. Refresh history or retry the same delete."),
                    };
                    connection.send(&Envelope::new(request.id, message)).await?;
                }
                Message::Cancel { target } => {
                    let daemon = self.clone();
                    let sender = connection.sender();
                    let peer = endpoint.to_owned();
                    tokio::spawn(async move {
                        let result = daemon.cancel_prompt(&peer, generation, &target, None).await;
                        let message = result.unwrap_or_else(|_| Message::Error {
                            code: "cancel-failed".into(), message: "Could not stop this request.".into(),
                            session_id: None, conversation_id: None,
                        });
                        let _ = sender.send(&Envelope::new(request.id, message)).await;
                    });
                }
                Message::ConversationActivate { conversation_id, expected_current: _ } => {
                    let daemon = self.clone();
                    let peer = endpoint.to_owned();
                    let generation = connection.generation();
                    let sender = connection.sender();
                    tokio::spawn(async move {
                        let message = match daemon.activate_conversation(&peer, generation, conversation_id).await {
                            Ok(message) => message,
                            Err(error) => {
                                eprintln!("Kompi could not activate saved conversation: {}", safe_error(&error.to_string()));
                                activation_error(conversation_id, "conversation-unavailable", "Could not continue this conversation. It is still available to browse if its transcript can be loaded.")
                            }
                        };
                        let _ = sender.send(&Envelope::new(request.id, message)).await;
                    });
                }
                Message::ConversationClose { conversation_id } => {
                    let daemon = self.clone();
                    let sender = connection.sender();
                    let peer = endpoint.to_owned();
                    tokio::spawn(async move {
                        let message = daemon.close_conversation(&peer, generation, conversation_id).await
                            .unwrap_or_else(|_| activation_error(conversation_id, "conversation-close-failed", "Could not close this conversation."));
                        let _ = sender.send(&Envelope::new(request.id, message)).await;
                    });
                }
                _ => {
                    connection
                        .send(&Envelope::new(
                            request.id,
                            Message::Error {
                                code: "unexpected-message".into(),
                                message: "Message is not valid in the ready state.".into(),
                                session_id: None,
                                conversation_id: None,
                            },
                        ))
                        .await?;
                    bail!("invalid client event at ready-state boundary");
                }
            }
        }
    }

    fn prompt_lane(&self, endpoint: &str, conversation: kompi_protocol::ConversationId) -> Arc<PromptLane> {
        self.prompt_lanes.lock().expect("prompt lanes poisoned")
            .entry((endpoint.to_owned(), conversation)).or_insert_with(|| Arc::new(PromptLane::new())).clone()
    }

    async fn cancel_prompt(&self, endpoint: &str, generation: u64, target: &RequestId, scope: Option<kompi_protocol::ConversationId>) -> Result<Message> {
        let peer = crate::sqlite_store::PeerKey::try_from(endpoint.to_owned())?;
        let key = crate::sqlite_store::RequestKey::try_from(target.to_string())?;
        let Some((_, _, session)) = self.sqlite.request_identity(&peer, &key).await? else {
            return Ok(Message::Error {
                code: "not-cancellable".into(), message: "Request is no longer running.".into(),
                session_id: None, conversation_id: None,
            });
        };
        let id = uuid::Uuid::parse_str(session.as_str())?.into();
        if let Some(scope) = scope.filter(|scope| *scope != id) {
            return Ok(activation_error(scope, "not-cancellable", "Request is no longer running in this conversation."));
        }
        let lane = self.prompt_lane(endpoint, id);
        let dispatch = lane.dispatch.lock().await;
        let authorization = self.runtime_authorization(endpoint);
        {
            let _authorized = authorization.read().await;
            anyhow::ensure!(self.is_current_connection(endpoint, generation), "stale connection");
            if !lane.owner.cancel_request(target.clone()).await? {
                return Ok(activation_error(id, "not-cancellable", "Request is no longer running."));
            }
        }
        let state = self.sqlite.request_completion(&peer, &key).await?;
        drop(dispatch);
        if state.is_some_and(|(outcome, final_sequence)| outcome != "queued" && final_sequence.is_none()) {
            self.worker.abort(target.clone(), id).await?;
        }
        Ok(Message::Ack {
            configuration: None, state: kompi_protocol::AckState::Accepted,
            session_id: None, conversation_id: Some(id), idle_timeout_seconds: None,
        })
    }

    async fn close_conversation(&self, endpoint: &str, generation: u64, target: kompi_protocol::ConversationId) -> Result<Message> {
        let lane = self.prompt_lane(endpoint, target);
        let _admission = lane.admission.lock().await;
        anyhow::ensure!(self.is_current_connection(endpoint, generation), "stale connection");
        let home = dirs::home_dir().context("daemon home is unavailable")?;
        let Some(mut row) = self.sqlite.conversation_by_id(endpoint, target, &home.to_string_lossy()).await? else {
            return Ok(activation_error(target, "conversation-not-found", "Conversation does not belong to this paired phone."));
        };
        let authorization = self.runtime_authorization(endpoint);
        let release = {
            let _authorized = authorization.read().await;
            anyhow::ensure!(self.is_current_connection(endpoint, generation), "stale connection");
            match row.state {
                ConversationState::Closed => false,
                ConversationState::Idle if lane.owner.close_if_idle().await? => true,
                ConversationState::Idle | ConversationState::Busy => {
                    return Ok(activation_error(target, "conversation-busy", "Cannot close a conversation while a prompt is queued or active."));
                }
                _ => return Ok(activation_error(target, "conversation-not-active", "Only an idle conversation can be closed.")),
            }
        };
        if release {
            // The lane remains held across disposal so a new connection cannot
            // replace the worker between the close claim and the release.
            self.worker.dispose(target).await?;
            row.state = ConversationState::Closed;
            let _authorized = authorization.read().await;
            if self.sqlite.device(endpoint).await?.is_some() { self.sqlite.upsert_conversation(&row).await?; }
        }
        conversation_message(&row, self.sqlite.conversation_configuration(endpoint, target).await?)
    }

    async fn activate_conversation(
        &self, endpoint: &str, generation: u64, target: kompi_protocol::ConversationId,
    ) -> Result<Message> {
        let reject = |code, message| activation_error(target, code, message);
        let home = fs::canonicalize(dirs::home_dir().context("daemon home is unavailable")?)?;
        let lane = self.prompt_lane(endpoint, target);
        let _admission = lane.admission.lock().await;
        anyhow::ensure!(self.is_current_connection(endpoint, generation), "stale connection");
        let Some(mut saved) = self.reserve_history_conversation(endpoint, target, &home.to_string_lossy()).await? else {
            return Ok(reject("conversation-unavailable", "This conversation is unavailable on the computer."));
        };
        let configuration = self.sqlite.conversation_configuration(endpoint, target).await?;
        if saved.state == ConversationState::Busy {
            return conversation_message(&saved, configuration);
        }
        if saved.session_id.is_none() || saved.session_file.is_empty() {
            return Ok(reject("conversation-unavailable", "There is no saved Pi transcript to continue."));
        }
        if self.sqlite.transcript_shared(&saved).await? {
            return Ok(reject("conversation-owned", "Another session owns this transcript. It can’t be continued here."));
        }
        // Only this target is opened. Holding its admission gate keeps an old
        // close/delete or a first turn from racing this transcript acquisition.
        if let Err(error) = self.prepare_worker(&saved, endpoint).await {
            self.worker.dispose(target).await?;
            if error.is::<TranscriptOwned>() {
                return Ok(reject("conversation-owned", "This transcript is open on the computer. Close it there before continuing."));
            }
            return Err(error);
        }
        let authorization = self.runtime_authorization(endpoint);
        let _authorization = authorization.read().await;
        let result = async {
            anyhow::ensure!(self.is_current_connection(endpoint, generation) && self.sqlite.device(endpoint).await?.is_some(), "stale connection");
            let timeout = self.sqlite.effective_settings(endpoint).await?.idle_timeout_seconds;
            saved.state = ConversationState::Idle;
            saved.idle_timeout_seconds = Some(timeout);
            saved.last_user_prompt_at = now_ms();
            let reply = conversation_message(&saved, configuration)?;
            self.sqlite.upsert_conversation(&saved).await?;
            anyhow::ensure!(lane.owner.set_idle(Duration::from_secs(u64::from(timeout.get()))).await?, "conversation gained outstanding work during activation");
            Ok(reply)
        }.await;
        if result.is_err() { self.worker.dispose(target).await?; }
        result
    }

    async fn prepare_worker(&self, row: &crate::store::ConversationRecord, phone_label: &str) -> Result<WorkerSession> {
        let started = std::time::Instant::now();
        let authorization = self.runtime_authorization(&row.endpoint);
        let worker = {
            let _authorization = authorization.read().await;
            anyhow::ensure!(self.sqlite.device(&row.endpoint).await?.is_some(), "phone is no longer paired");
            self.worker.process_for(row.id, &row.endpoint).await?
        };
        preparation_timing("authorized-worker-acquired", started);
        worker.prepare(row.id, row.session_id.as_deref(), &row.session_file, phone_label, wire_timestamp(row.created_at)?).await
    }

    async fn reconcile_pi(
        &self, endpoint: &str, generation: u64, target: Option<kompi_protocol::ConversationId>,
        sender: &crate::lifecycle::ReadySender, request: &RequestId,
    ) -> Result<()> {
        let home = dirs::home_dir().context("daemon home is unavailable")?;
        let home = home.to_string_lossy();
        let rows = match target {
            Some(id) => self.sqlite.conversation_by_id(endpoint, id, &home).await?.into_iter().collect(),
            None => self.sqlite.active_conversations(endpoint, &home).await?,
        };
        if rows.is_empty() {
            return if target.is_some() { sender.send(&Envelope::new(request.clone(), snapshot_error(target))).await }
            else { sender.send(&Envelope::new(request.clone(), Message::PiSessionEmpty {})).await };
        }
        let mut jobs = tokio::task::JoinSet::new();
        for record in rows {
            let daemon = self.clone();
            let sender = sender.clone();
            let request = request.clone();
            jobs.spawn(async move {
                let id = record.id;
                // A specific primary route can also be selected after a secondary
                // stream reset. Fence its snapshot against frames already queued.
                let token = target.map(|_| RequestId::parse(format!("primary-{}", uuid::Uuid::new_v4())).expect("generated token"));
                let publishers = &daemon.worker.pi_frames;
                if let Some(token) = &token {
                    publishers.primary.lock().expect("Pi routes poisoned").insert((id, token.clone()), (generation, request.clone()));
                }
                let result = daemon.snapshot_conversation(&record.endpoint, generation, id, token.clone()).await;
                if let Some(token) = token {
                    publishers.primary.lock().expect("Pi routes poisoned").remove(&(id, token));
                }
                if result.is_err() {
                    let _ = sender.send(&Envelope::new(request, snapshot_error(Some(id)))).await;
                }
            });
        }
        while jobs.join_next().await.is_some() {}
        Ok(())
    }

    async fn snapshot_conversation(&self, endpoint: &str, generation: u64, id: kompi_protocol::ConversationId, request_id: Option<RequestId>) -> Result<()> {
        let home = dirs::home_dir().context("daemon home is unavailable")?;
        let lane = self.prompt_lane(endpoint, id);
        let _admission = lane.admission.lock().await;
        anyhow::ensure!(self.is_current_connection(endpoint, generation), "stale connection");
        let row = self.sqlite.conversation_by_id(endpoint, id, &home.to_string_lossy()).await?
            .filter(|row| matches!(row.state, ConversationState::Idle | ConversationState::Busy))
            .context("conversation is not active")?;
        if self.worker.belongs_to(endpoint, id).await {
            return self.worker.snapshot(id, request_id).await;
        }
        // Restart restoration does not touch the lease or create a transcript.
        anyhow::ensure!(row.state == ConversationState::Idle && !row.session_file.is_empty(), "conversation worker is not ready");
        let deadline = row.last_user_prompt_at + i64::from(row.idle_timeout_seconds.context("missing conversation TTL")?.get()) * 1000;
        anyhow::ensure!(deadline > now_ms(), "conversation has expired");
        if let Err(error) = self.prepare_worker(&row, endpoint).await {
            self.worker.dispose(id).await?;
            return Err(error);
        }
        self.worker.snapshot(id, request_id).await
    }

    /// Ledger identity, not current selection, determines a retry's destination.
    async fn replay_prompt(
        &self,
        connection: &crate::lifecycle::ReadySender,
        peer: &crate::sqlite_store::PeerKey,
        request: &crate::sqlite_store::RequestKey,
        id: &RequestId,
        fingerprint: &[u8],
        configuration: &kompi_protocol::ChatModelConfiguration,
        mut trace: Option<Trace>,
    ) -> Result<bool> {
        let Some((prior_fingerprint, _, prior_session)) = self.sqlite.request_identity(peer, request).await? else {
            return Ok(false);
        };
        if prior_fingerprint != fingerprint {
            timing(&trace, Timing::DaemonRejected);
            connection.send(&Envelope::new(id.clone(), Message::PreacceptanceRejected {
                request_id: id.clone(),
                code: "request-id-conflict".into(),
                message: "Request ID is already bound to different content.".into(),
                retryable: false,
            })).await?;
            return Ok(true);
        }
        let conversation_id = uuid::Uuid::parse_str(prior_session.as_str())?.into();
        if let Some(trace) = &mut trace { trace.conversation = Some(conversation_id); }
        timing(&trace, Timing::DaemonClaimReplay);
        let (outcome, final_sequence) = self.sqlite.request_completion(peer, request).await?
            .context("claimed request disappeared from SQLite")?;
        if final_sequence.is_some() {
            self.send_request_completion(connection, id.clone(), peer.as_str(), conversation_id).await?;
        } else {
            let record = self.sqlite.conversation_by_id(peer.as_str(), conversation_id, "").await?;
            let result = connection.send(&Envelope::new(id.clone(), Message::Ack {
                configuration: Some(configuration.clone()),
                state: if outcome == "queued" { kompi_protocol::AckState::Queued } else { kompi_protocol::AckState::Accepted },
                session_id: record.and_then(|row| row.session_id),
                conversation_id: Some(conversation_id),
                idle_timeout_seconds: None,
            })).await;
            audit_ack_write(&trace, result.is_ok());
            result?;
        }
        Ok(true)
    }

    async fn handle_prompt(
        &self,
        connection: crate::lifecycle::ReadySender,
        endpoint: &str,
        connection_generation: u64,
        id: RequestId,
        text: String,
        delivery: kompi_protocol::Delivery,
        configuration: kompi_protocol::ChatModelConfiguration,
        attachment_ids: Option<Vec<kompi_protocol::ConversationId>>,
        target: Option<kompi_protocol::ConversationId>,
        create_if_missing: bool,
        mut trace: Option<Trace>,
        reply_notification_capable: bool,
    ) -> Result<()> {
        let dispatch_started = std::time::Instant::now();
        let attachment_ids = attachment_ids.unwrap_or_default();
        if text.len() > 16_000 || (text.trim().is_empty() && attachment_ids.is_empty()) || attachment_ids.len() > ATTACHMENT_MAX_FILES_PER_MESSAGE {
            timing(&trace, Timing::DaemonRejected);
            connection
                .send(&Envelope::new(
                    id,
                    Message::Error {
                        code: "invalid-prompt".into(),
                        message: "Prompt must contain text or up to sixteen attachments; text is limited to 16,000 characters.".into(),
                        session_id: None,
                        conversation_id: None,
                    },
                ))
                .await?;
            return Ok(());
        }
        // Retry identity is resolved from SQLite before touching upload files: accepted
        // retries replay their durable state even when attachment retention has elapsed.
        let ledger_text = if attachment_ids.is_empty() {
            text.clone()
        } else {
            attachment_prompt_fingerprint(&text, &attachment_ids)
        };
        let peer_key = crate::sqlite_store::PeerKey::try_from(endpoint.to_owned())?;
        let request_key = crate::sqlite_store::RequestKey::try_from(id.to_string())?;
        let intent = match target {
            Some(target) if create_if_missing => serde_json::to_vec(&(delivery_key(delivery), ledger_text, &configuration, target, true))?,
            Some(target) => serde_json::to_vec(&(delivery_key(delivery), ledger_text, &configuration, target))?,
            None => serde_json::to_vec(&(delivery_key(delivery), ledger_text, &configuration))?,
        };
        let fingerprint = Sha256::digest(intent).to_vec();
        if self.replay_prompt(&connection, &peer_key, &request_key, &id, &fingerprint, &configuration, trace.clone()).await? {
            return Ok(());
        }
        // The live Pi session applies the persisted selection; discovery is selector-only.
        match crate::resource_admission::check(self.sqlite.state_directory()) {
            Ok(()) => {}
            Err(crate::resource_admission::AdmissionError::Pressure) => {
                timing(&trace, Timing::DaemonRejected);
                connection.send(&Envelope::new(id.clone(), Message::PreacceptanceRejected {
                    request_id: id,
                    code: "resource-pressure".into(),
                    message: "Host resources are temporarily unavailable; retry this request.".into(),
                    retryable: true,
                })).await?;
                return Ok(());
            }
            Err(crate::resource_admission::AdmissionError::Unavailable(reason)) => {
                timing(&trace, Timing::DaemonRejected);
                connection.send(&Envelope::new(id.clone(), Message::PreacceptanceRejected {
                    request_id: id,
                    code: "resource-check-unavailable".into(),
                    message: safe_error(&reason),
                    retryable: true,
                })).await?;
                return Ok(());
            }
        }
        let mut seen_attachments = HashSet::new();
        for upload_id in &attachment_ids {
            if !seen_attachments.insert(*upload_id) {
                timing(&trace, Timing::DaemonRejected);
                connection.send(&Envelope::new(id, Message::Error {
                    code: "duplicate-attachment".into(), message: "A prompt cannot reference the same attachment more than once.".into(),
                    session_id: None, conversation_id: None,
                })).await?;
                return Ok(());
            }
        }
        let home = dirs::home_dir().context("daemon account has no home directory")?;
        let project_path = fs::canonicalize(&home)
            .context("daemon account home directory is unavailable")?
            .display()
            .to_string();
        let conversation_id = target.unwrap_or_else(|| uuid::Uuid::new_v4().into());
        let lane = self.prompt_lane(endpoint, conversation_id);
        // A conversation owns its own admission and FIFO. A peer read lease
        // fences revocation without serializing unrelated chats.
        let admission = lane.admission.lock().await;
        anyhow::ensure!(self.is_current_connection(endpoint, connection_generation), "stale connection");
        let mut conversation = None;
        let mut needs_saved = !create_if_missing;
        if let Some(target_id) = target {
            if create_if_missing {
                conversation = self.sqlite.conversation_by_id(endpoint, target_id, &project_path).await?;
                needs_saved = conversation.as_ref().is_some_and(|row|
                    row.session_id.is_some() && !matches!(row.state, ConversationState::Idle | ConversationState::Busy));
            }
            if needs_saved {
                conversation = self.reserve_history_conversation(endpoint, target_id, &project_path).await?;
            }
        }
        let authorization = self.runtime_authorization(endpoint);
        let authorized = authorization.read().await;
        anyhow::ensure!(self.is_current_connection(endpoint, connection_generation), "stale connection");
        let now = now_ms();
        let unavailable = conversation.as_ref().is_some_and(|row| row.session_id.is_none()
            && !matches!(row.state, ConversationState::Busy));
        let missing = conversation.is_none() && target.is_some()
            && (needs_saved || !create_if_missing || conversation_id.as_uuid().get_version_num() != 4);
        if unavailable || missing {
            drop(authorized);
            drop(admission);
            if self.replay_prompt(&connection, &peer_key, &request_key, &id, &fingerprint, &configuration, trace.clone()).await? {
                return Ok(());
            }
            timing(&trace, Timing::DaemonRejected);
            connection.send(&Envelope::new(id.clone(), Message::PreacceptanceRejected {
                request_id: id, code: "conversation-unavailable".into(),
                message: "This message’s conversation is no longer available on the computer.".into(), retryable: false,
            })).await?;
            return Ok(());
        }
        let immediate_guard = lane.execution.try_lock().ok();
        let queued = immediate_guard.is_none();
        let session_id = conversation.as_ref().and_then(|row| row.session_id.clone())
            .unwrap_or_else(|| "pending-pi-session".to_owned());
        let Some(peer_policy) = self.sqlite.device(endpoint).await? else {
            bail!("authenticated peer disappeared from trusted registry");
        };
        let idle_timeout_seconds = self.sqlite.effective_settings(endpoint).await?.idle_timeout_seconds;
        let session_file = conversation
            .as_ref()
            .map(|r| r.session_file.clone())
            .unwrap_or_default();
        let sdk_session_id = conversation.as_ref().and_then(|r| r.session_id.clone());
        let session_name = conversation.as_ref().and_then(|r| r.session_name.clone());
        let conversation_record = crate::store::ConversationRecord {
            id: conversation_id,
            endpoint: endpoint.to_owned(),
            project: project_path.clone(),
            project_session_id: session_id.clone(),
            session_id: sdk_session_id.clone(),
            session_file: session_file.clone(),
            session_name,
            idle_timeout_seconds: Some(idle_timeout_seconds),
            created_at: conversation.as_ref().map(|r| r.created_at).unwrap_or(now),
            last_user_prompt_at: now,
            state: ConversationState::Busy,
        };
        let session_key = crate::sqlite_store::SessionKey::try_from(conversation_id.to_string())?;
        let claim_event = DurableEvent::RequestChanged {
            request: RequestSummary {
                request_id: id.clone(),
                conversation_id,
                outcome: if queued { WireRequestOutcome::Queued } else { WireRequestOutcome::Accepted },
                next_sequence: 0,
                configuration: Some(configuration.clone()),
            },
        };
        let encoded_claim_event = encode_durable_event(&claim_event)?;
        let sqlite_claim = match self.sqlite.claim_request(
            &crate::sqlite_store::RequestClaim {
                peer: peer_key.clone(),
                request: request_key.clone(),
                session: session_key,
                session_name: peer_policy.name.clone(),
                fingerprint: fingerprint.clone(),
                configuration: configuration.clone(),
                accepted_at_ms: now,
                idle_ttl_seconds: i64::from(idle_timeout_seconds.get()),
            },
            &attachment_ids.iter().map(ToString::to_string).collect::<Vec<_>>(),
            now,
            &encoded_claim_event,
        ).await {
            Ok(claim) => claim,
            Err(error) => {
                timing(&trace, Timing::DaemonRejected);
                connection.send(&Envelope::new(id.clone(), Message::PreacceptanceRejected {
                    request_id: id,
                    code: "state-store-unavailable".into(),
                    message: safe_error(&error.to_string()),
                    retryable: true,
                })).await?;
                return Ok(());
            }
        };
        if sqlite_claim.status == crate::sqlite_store::ClaimStatus::AttachmentsUnavailable {
            timing(&trace, Timing::DaemonRejected);
            connection.send(&Envelope::new(id.clone(), Message::PreacceptanceRejected {
                request_id: id,
                code: "attachment-unavailable".into(),
                message: "One or more attachments are expired or unavailable.".into(),
                retryable: false,
            })).await?;
            return Ok(());
        }
        if sqlite_claim.status == crate::sqlite_store::ClaimStatus::Conflict {
            timing(&trace, Timing::DaemonRejected);
            connection.send(&Envelope::new(id.clone(), Message::PreacceptanceRejected {
                request_id: id,
                code: "request-id-conflict".into(),
                message: "Request ID is already bound to different content.".into(),
                retryable: false,
            })).await?;
            return Ok(());
        }
        if sqlite_claim.status == crate::sqlite_store::ClaimStatus::Existing {
            drop(immediate_guard);
            drop(admission);
            anyhow::ensure!(self.replay_prompt(&connection, &peer_key, &request_key, &id, &fingerprint, &configuration, trace.clone()).await?, "claimed request disappeared from SQLite");
            return Ok(());
        }
        if let Some(trace) = &mut trace { trace.conversation = Some(conversation_id); }
        timing(&trace, Timing::DaemonClaimCommitted);
        let mut worker_attachments = Vec::with_capacity(attachment_ids.len());
        for upload_id in &attachment_ids {
            let reference = match self.attachments.lookup_pinned(endpoint, &request_key, attachment::UploadId::new(*upload_id.as_uuid())).await {
                Ok(reference) => reference,
                Err(error) => {
                    let completion = DurableEvent::ResponseCompleted { request_id: id.clone(), outcome: WireRequestOutcome::Failed, final_sequence: 0 };
                    self.sqlite.complete_response(&peer_key, &request_key, crate::sqlite_store::RequestOutcome::Failed, 0, now_ms(), &encode_durable_event(&completion)?).await?;
                    timing(&trace, Timing::DaemonCompletionCommitted);
                    connection.send(&Envelope::new(id.clone(), Message::ResponseComplete { request_id: id, conversation_id, outcome: WireRequestOutcome::Failed, final_sequence: 0 })).await?;
                    eprintln!("Kompi accepted attachment could not be opened: {}", safe_error(&error.to_string()));
                    return Ok(());
                }
            };
            worker_attachments.push(kompi_protocol::worker::WorkerAttachmentRef {
                upload_id: *upload_id,
                file_name: reference.offer.file_name.as_str().to_owned(),
                media_type: reference.offer.media_type.as_str().to_owned(),
                length: reference.offer.length,
                sha256: reference.offer.sha256.as_hex(),
                path: reference.path.display().to_string(),
            });
        }
        let worker_attachments = if worker_attachments.is_empty() { None } else { Some(worker_attachments) };
        let owner = &lane.owner;
        let runtime_generation = owner.accept(id.clone(), Duration::from_secs(u64::from(idle_timeout_seconds.get()))).await
            .context("phone session actor stopped before accepting request")?;
        drop(authorized);
        // Durable work must not depend on a live or writable control stream. A
        // bounded per-request notification queue preserves ordering while allowing
        // the worker and SQLite persistence to continue if the peer stops reading.
        let (notification_tx, mut notification_rx) = mpsc::channel(16);
        let notification_connection = connection.clone();
        let ack_trace = trace.clone();
        tokio::spawn(async move {
            while let Some(envelope) = notification_rx.recv().await {
                let sent = notification_connection.send(&envelope).await.is_ok();
                if matches!(envelope.message, Message::Ack { .. }) {
                    audit_ack_write(&ack_trace, sent);
                }
                if !sent { break; }
            }
        });
        let _ = notification_tx.try_send(Envelope::new(
            id.clone(),
            Message::Ack { configuration: Some(configuration.clone()),
                state: if queued {
                    kompi_protocol::AckState::Queued
                } else {
                    kompi_protocol::AckState::Accepted
                },
                session_id: Some(session_id.clone()),
                conversation_id: Some(conversation_id),
                idle_timeout_seconds: None,
            },
        ));
        let prompt = AcceptedPrompt {
            id, trace, key: request_key, conversation: conversation_record, generation: runtime_generation,
            input: PromptInput { text, delivery, configuration, attachments: worker_attachments },
            started_at: dispatch_started, notify: notification_tx, reply_notification_capable,
        };
        let Some(execution) = immediate_guard else {
            lane.pending.lock().expect("prompt lane poisoned").push_back(prompt);
            return Ok(());
        };
        drop(admission);
        self.run_phone_queue(&peer_key, owner, &lane, execution, prompt).await
    }

    async fn run_phone_queue(
        &self,
        peer: &crate::sqlite_store::PeerKey,
        owner: &PhoneSessionOwner,
        lane: &PromptLane,
        execution: tokio::sync::MutexGuard<'_, ()>,
        mut prompt: AcceptedPrompt,
    ) -> Result<()> {
        let endpoint = prompt.conversation.endpoint.clone();
        let project = prompt.conversation.project.clone();
        loop {
            let conversation = prompt.conversation.id;
            if let Err(error) = self.run_phone_turn(peer, owner, lane, prompt).await {
                eprintln!("Kompi could not finalize phone work: {}", safe_error(&error.to_string()));
            }
            let _admission = lane.admission.lock().await;
            let next = lane.pending.lock().expect("prompt lane poisoned").pop_front();
            if let Some(next) = next {
                prompt = next;
                continue;
            }
            let authorization = self.runtime_authorization(&endpoint);
            let _authorized = authorization.read().await;
            if let Some(mut row) = self.sqlite.conversation_by_id(&endpoint, conversation, &project).await? {
                if row.state == ConversationState::Busy {
                    row.state = ConversationState::Idle;
                    self.sqlite.upsert_conversation(&row).await?;
                }
                if owner.expire_if_idle(tokio::time::Instant::now(), false).await? {
                    drop(_authorized);
                    self.expire_conversation(row, lane, now_ms()).await?;
                }
            }
            drop(execution);
            return Ok(());
        }
    }

    async fn run_phone_turn(
        &self,
        peer: &crate::sqlite_store::PeerKey,
        owner: &PhoneSessionOwner,
        lane: &PromptLane,
        prompt: AcceptedPrompt,
    ) -> Result<()> {
        let conversation = prompt.conversation.id;
        let generation = prompt.generation;
        let endpoint = prompt.conversation.endpoint.clone();
        let project = prompt.conversation.project.clone();
        let route = prompt.id.clone();
        let authorization = self.runtime_authorization(&endpoint);
        let mut group = vec![prompt];
        let mut unstarted = VecDeque::new();
        let mut staged = Vec::new();
        let mut checkpoint = None;
        let mut dispatched = false;
        let mut failure = None;
        let outcome: Result<Option<kompi_protocol::GenerationStats>> = async {
            if owner.is_cancelled(route.clone()).await? {
                return Ok(None);
            }
            anyhow::ensure!(owner.is_current_runtime(generation).await?, "session runtime was revoked or replaced before worker setup");
            let record = &group[0].conversation;
            let phone_label = self.sqlite.device(&endpoint).await?.context("phone is no longer paired")?.name;
            let opened = self.prepare_worker(record, &phone_label).await?;
            if !owner.is_current_runtime(generation).await? {
                self.worker.dispose(conversation).await?;
                bail!("session runtime was revoked or replaced during worker setup");
            }
            anyhow::ensure!(owner.runtime_opened(generation).await?, "session runtime was replaced during worker setup");
            {
                let _admission = lane.admission.lock().await;
                let _authorized = authorization.read().await;
                anyhow::ensure!(owner.is_current_runtime(generation).await?, "session runtime was revoked before persistence");
                if let Some(mut row) = self.sqlite.conversation_by_id(&endpoint, conversation, &project).await? {
                    row.session_id = Some(opened.session_id);
                    row.session_file = opened.session_file;
                    row.session_name = Some(opened.session_name);
                    self.sqlite.upsert_conversation(&row).await?;
                }
            }
            let (mut events, mut overflow) = {
                // Serialize the start with cancel and revoke, not with phone I/O.
                let _dispatch = lane.dispatch.lock().await;
                let _authorized = authorization.read().await;
                if owner.is_cancelled(route.clone()).await? { return Ok(None); }
                anyhow::ensure!(self.sqlite.device(&endpoint).await?.is_some(), "phone was revoked before dispatch");
                self.start_phone_prompt(peer, owner, &group[0]).await?;
                let lead = &mut group[0];
                let input = &mut lead.input;
                let text = std::mem::take(&mut input.text);
                let attachments = input.attachments.take();
                dispatched = true;
                timing(&lead.trace, Timing::DaemonWorkerDispatch);
                let route = self.worker.begin_prompt(route.clone(), conversation, text, input.delivery, input.configuration.clone(), attachments).await?;
                timing(&lead.trace, Timing::DaemonWorkerWritten);
                route
            };
            loop {
                let event = timeout(Duration::from_secs(300), async { tokio::select! {
                    biased;
                    _ = &mut overflow => Err(anyhow::anyhow!("Pi worker response route lost; request will not be replayed")),
                    event = events.recv() => event.context("Pi worker exited during prompt"),
                }}).await.context("Pi worker response timed out")??;
                let event = match event {
                    WorkerEvent::ChatTiming { stamp, request_id } => {
                        if let Some(trace) = group.first().filter(|lead| lead.id == request_id).and_then(|lead| lead.trace.as_ref()) {
                            trace.record_stamp(stamp);
                        }
                        continue;
                    }
                    event => event,
                };
                anyhow::ensure!(owner.has_work(group[0].id.clone(), generation).await?, "late Pi event belongs to a replaced runtime");
                match event {
                    WorkerEvent::Final { stats, .. } => {
                        timing(&group[0].trace, Timing::DaemonWorkerFinal);
                        return Ok(Some(stats));
                    }
                    WorkerEvent::Error { code, .. } => {
                        failure = Some(code);
                        bail!("Pi rejected or did not finish the phone turn");
                    }
                    WorkerEvent::FollowUpReady { stats } => {
                        let _dispatch = lane.dispatch.lock().await;
                        // An in-flight Stop belongs to the old turn. Don't hand off
                        // the queue while that abort is resolving at the Pi boundary.
                        let mut stopped = false;
                        for member in &group {
                            stopped |= owner.is_cancelled(member.id.clone()).await?;
                        }
                        if stopped {
                            let _ = self.worker.release_follow_up(conversation).await;
                            continue;
                        }
                        checkpoint = Some(stats);
                        unstarted = lane.take_batch(conversation, generation).await;
                        let _authorized = authorization.read().await;
                        anyhow::ensure!(owner.is_current_runtime(generation).await?, "session runtime was revoked before follow-up");
                        // Pi is paused. Wait for acceptance of every input before release.
                        // Keep staged and unstarted work distinct on any transfer failure.
                        while let Some(candidate) = unstarted.front() {
                            if owner.is_cancelled(candidate.id.clone()).await? {
                                self.complete_phone_prompt(peer, owner, unstarted.pop_front().unwrap(), PhoneCompletion::Cancelled, None).await?;
                                continue;
                            }
                            self.start_phone_prompt(peer, owner, candidate).await?;
                            staged.push(unstarted.pop_front().unwrap());
                            let candidate = staged.last_mut().unwrap();
                            let request = WorkerRequest::FollowUpAdd {
                                request_id: candidate.id.clone(), conversation_id: conversation,
                                text: std::mem::take(&mut candidate.input.text),
                                configuration: candidate.input.configuration.clone(),
                                attachments: candidate.input.attachments.take(),
                            };
                            timing(&candidate.trace, Timing::DaemonFollowUpStaging);
                            match self.worker.add_follow_up(conversation, request).await? {
                                WorkerEvent::FollowUpAdded { queued: true } => timing(&candidate.trace, Timing::DaemonFollowUpStaged),
                                WorkerEvent::FollowUpAdded { queued: false } => {
                                    self.complete_phone_prompt(peer, owner, staged.pop().unwrap(), PhoneCompletion::Completed, None).await?;
                                }
                                WorkerEvent::Error { code, .. } => {
                                    timing(&candidate.trace, Timing::DaemonWorkerFailed);
                                    self.complete_phone_prompt(peer, owner, staged.pop().unwrap(), PhoneCompletion::Failed, Some(public_prompt_failure(&code))).await?;
                                }
                                _ => bail!("unexpected Pi follow-up response"),
                            }
                        }
                        let continued = !staged.is_empty();
                        if continued {
                            let previous = std::mem::replace(&mut group, std::mem::take(&mut staged));
                            self.finish_phone_group(peer, owner, previous, checkpoint.take().unwrap()).await?;
                        }
                        self.worker.release_follow_up(conversation).await?;
                        if continued {
                            let response_owner = group[0].id.clone();
                            for member in &mut group {
                                if let Some(trace) = &mut member.trace {
                                    trace.response_request_id = Some(response_owner.clone());
                                    trace.record(Timing::DaemonFollowUpReleased);
                                }
                            }
                        }
                        checkpoint = None;
                    }
                    _ => {}
                }
            }
        }.await;
        self.worker.remove_pending(conversation, route.as_str()).await;
        match outcome {
            Ok(Some(stats)) => {
                self.finish_phone_group(peer, owner, group, stats).await
            }
            Ok(None) => {
                self.complete_phone_prompt(peer, owner, group.remove(0), PhoneCompletion::Cancelled, None).await?;
                Ok(())
            }
            Err(error) => {
                for member in group.iter().chain(staged.iter()) {
                    timing(&member.trace, if dispatched { Timing::DaemonWorkerFailed } else { Timing::DaemonHandlerFailed });
                }
                // A clean Pi response failure leaves the runtime usable. Lost or ambiguous
                // worker IPC cannot be retried: dispose it including any half-filled queue.
                let current = owner.is_current_runtime(generation).await?;
                let worker_lost = current && (failure.is_none() || !self.worker.is_healthy(conversation).await);
                if worker_lost {
                    let _admission = lane.admission.lock().await;
                    let released = self.worker.dispose(conversation).await.is_ok();
                    let _authorized = authorization.read().await;
                    if owner.is_current_runtime(generation).await? {
                        owner.runtime_failed(generation).await?;
                        if released {
                            if let Some(mut row) = self.sqlite.conversation_by_id(&endpoint, conversation, &project).await? {
                                row.state = ConversationState::Unavailable;
                                self.sqlite.upsert_conversation(&row).await?;
                            }
                        }
                    }
                }
                if let Some(stats) = checkpoint {
                    self.finish_phone_group(peer, owner, std::mem::take(&mut group), stats).await?;
                }
                let result = if failure.as_deref() == Some("prompt-aborted") { PhoneCompletion::Cancelled }
                    else if worker_lost && dispatched { PhoneCompletion::Uncertain }
                    else { PhoneCompletion::Failed };
                let message = match result {
                    PhoneCompletion::Cancelled => None,
                    PhoneCompletion::Uncertain => Some("The Pi worker stopped before the response completed. Check Pi on the computer before retrying; the request will not be rerun automatically."),
                    _ if error.is::<TranscriptOwned>() => Some("This Pi transcript is in use. Release it on the computer before sending again."),
                    _ => Some(public_prompt_failure(failure.as_deref().unwrap_or("prompt-failed"))),
                };
                for member in group.into_iter().chain(staged) {
                    self.complete_phone_prompt(peer, owner, member, result, message).await?;
                }
                for member in unstarted {
                    self.complete_phone_prompt(peer, owner, member, PhoneCompletion::Failed, Some(public_prompt_failure("prompt-failed"))).await?;
                }
                Ok(())
            }
        }
    }

    async fn start_phone_prompt(&self, peer: &crate::sqlite_store::PeerKey, owner: &PhoneSessionOwner, prompt: &AcceptedPrompt) -> Result<()> {
        self.persist_request_running(peer, &prompt.key, prompt.conversation.id).await?;
        anyhow::ensure!(owner.has_work(prompt.id.clone(), prompt.generation).await?, "session runtime was replaced before work started");
        Ok(())
    }

    async fn finish_phone_group(
        &self, peer: &crate::sqlite_store::PeerKey, owner: &PhoneSessionOwner,
        group: Vec<AcceptedPrompt>, mut stats: kompi_protocol::GenerationStats,
    ) -> Result<()> {
        // One assistant response (and at most one notice) belongs to the group's first request.
        let mut notice = None;
        if let Some(lead) = group.first() {
            if lead.reply_notification_capable && stats.assistant_messages > 0 && !owner.is_cancelled(lead.id.clone()).await? {
                notice = Some((lead.id.clone(), lead.conversation.id));
            }
            stats.daemon_total_ms = Some(u32::try_from(lead.started_at.elapsed().as_millis()).unwrap_or(u32::MAX));
            let _ = lead.notify.try_send(Envelope::new(lead.id.clone(), Message::GenerationStats { request_id: lead.id.clone(), stats }));
        }
        for member in group {
            self.complete_phone_prompt(peer, owner, member, PhoneCompletion::Completed, None).await?;
        }
        if let Some((lead, conversation)) = notice {
            self.notify_response_completed(peer.as_str(), &lead, conversation);
        }
        Ok(())
    }

    async fn complete_phone_prompt(
        &self, peer: &crate::sqlite_store::PeerKey, owner: &PhoneSessionOwner,
        prompt: AcceptedPrompt, result: PhoneCompletion, failure: Option<&str>,
    ) -> Result<()> {
        use crate::sqlite_store::RequestOutcome;
        let (outcome, stored) = match result {
            PhoneCompletion::Completed => (WireRequestOutcome::Completed, RequestOutcome::Completed),
            PhoneCompletion::Cancelled => (WireRequestOutcome::Cancelled, RequestOutcome::Cancelled),
            PhoneCompletion::Uncertain => (WireRequestOutcome::Uncertain, RequestOutcome::Uncertain),
            PhoneCompletion::Failed => (WireRequestOutcome::Failed, RequestOutcome::Failed),
        };
        let conversation_id = prompt.conversation.id;
        let final_sequence = 0;
        let event = DurableEvent::ResponseCompleted { request_id: prompt.id.clone(), outcome, final_sequence };
        self.sqlite.complete_response(peer, &prompt.key, stored, 0, now_ms(), &encode_durable_event(&event)?).await?;
        timing(&prompt.trace, Timing::DaemonCompletionCommitted);
        owner.work_finished(prompt.id.clone(), prompt.generation).await?;
        if let Some(message) = failure {
            let _ = prompt.notify.try_send(Envelope::new(prompt.id.clone(), Message::Error { code: "pi-request-failed".into(), message: message.into(), session_id: None, conversation_id: Some(conversation_id) }));
        }
        let _ = prompt.notify.try_send(Envelope::new(prompt.id.clone(), Message::ResponseComplete { request_id: prompt.id, conversation_id, outcome, final_sequence }));
        Ok(())
    }

    async fn persist_request_running(
        &self,
        peer: &crate::sqlite_store::PeerKey,
        request: &crate::sqlite_store::RequestKey,
        conversation_id: kompi_protocol::ConversationId,
    ) -> Result<()> {
        let event = DurableEvent::RequestChanged {
            request: RequestSummary {
                request_id: RequestId::parse(request.as_str().to_owned())?,
                conversation_id,
                outcome: WireRequestOutcome::Running,
                next_sequence: 0,
                configuration: self.sqlite.request_configuration(peer, request).await?,
            },
        };
        self.sqlite.transition_request(
            peer,
            request,
            crate::sqlite_store::RequestOutcome::Running,
            now_ms(),
            &encode_durable_event(&event)?,
        ).await?;
        Ok(())
    }

    async fn send_request_completion(
        &self,
        connection: &crate::lifecycle::ReadySender,
        envelope_id: RequestId,
        endpoint: &str,
        conversation_id: kompi_protocol::ConversationId,
    ) -> Result<()> {
        let peer = crate::sqlite_store::PeerKey::try_from(endpoint.to_owned())?;
        let request = crate::sqlite_store::RequestKey::try_from(envelope_id.to_string())?;
        let (stored_outcome, final_sequence) = self.sqlite
            .request_completion(&peer, &request)
            .await?
            .context("completed request is absent from SQLite outcome ledger")?;
        let final_sequence = final_sequence.context("completed request has no final response position")?;
        let outcome = match stored_outcome.as_str() {
            "completed" => WireRequestOutcome::Completed,
            "failed" => WireRequestOutcome::Failed,
            "cancelled" => WireRequestOutcome::Cancelled,
            "uncertain" => WireRequestOutcome::Uncertain,
            _ => anyhow::bail!("request outcome is not terminal"),
        };
        connection.send(&Envelope::new(envelope_id.clone(), Message::ResponseComplete {
            request_id: envelope_id,
            conversation_id,
            outcome,
            final_sequence,
        })).await?;
        Ok(())
    }


}

fn pi_envelope(frame: kompi_protocol::PiFrame) -> Envelope<Message> {
    let id = RequestId::parse(format!("pi-{}-{}-{}", frame.conversation_id, frame.sequence, frame.part)).expect("generated Pi frame id");
    Envelope::new(id, Message::PiFrame { frame })
}

async fn refuse_secondary_stream(mut send: SendStream, request_id: RequestId) -> Result<()> {
    let refusal = Envelope::new(request_id, Message::Error {
        code: "stream-overloaded".into(),
        message: "The host cannot serve another bulk stream right now; retry the transfer.".into(),
        session_id: None,
        conversation_id: None,
    });
    write_frame(&mut send, &kompi_protocol::encode_cbor(&refusal)?).await?;
    send.finish()?;
    Ok(())
}

async fn shutdown_signal() {
    #[cfg(unix)]
    {
        let mut terminate =
            tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
                .expect("install SIGTERM handler");
        tokio::select! {_=tokio::signal::ctrl_c()=>{},_=terminate.recv()=>{}}
    }
    #[cfg(windows)]
    {
        use tokio::signal::windows::{ctrl_break, ctrl_close, ctrl_shutdown};
        let mut interrupt = ctrl_break().expect("install Ctrl-Break handler");
        let mut close = ctrl_close().expect("install console close handler");
        let mut shutdown = ctrl_shutdown().expect("install system shutdown handler");
        tokio::select! { _ = tokio::signal::ctrl_c() => {}, _ = interrupt.recv() => {}, _ = close.recv() => {}, _ = shutdown.recv() => {} }
    }
}

pub async fn run_daemon(state_directory: PathBuf) -> Result<()> {
    #[cfg(windows)]
    crate::local_ipc::contain_daemon_tree().context("contain daemon worker processes")?;
    let (store, _instance_lock) = HostIdentity::open(&state_directory)?;
    let sqlite = SqliteStore::open(&state_directory).await?;
    sqlite.recover_interrupted_requests(now_ms()).await?;
    sqlite.recover_notification_receipts(now_ms()).await?;
    let (running, _) = watch::channel(true);
    let daemon = Daemon::new(store, sqlite, running.clone()).await?;
    eprintln!("Kompi protocol v4 ready at endpoint {}", daemon.endpoint_hex());
    daemon.run().await
}

#[derive(Clone, Copy)]
#[repr(u8)]
enum WorkerPhase {
    Starting = 0,
    Idle = 1,
    Create = 2,
    Resume = 3,
    Prompt = 4,
    Abort = 5,
    Dispose = 6,
}
impl WorkerPhase {
    fn from_atomic(value: u8) -> Self {
        match value {
            1 => Self::Idle,
            2 => Self::Create,
            3 => Self::Resume,
            4 => Self::Prompt,
            5 => Self::Abort,
            6 => Self::Dispose,
            _ => Self::Starting,
        }
    }
    fn as_str(self) -> &'static str {
        match self { Self::Starting => "starting", Self::Idle => "idle", Self::Create => "create", Self::Resume => "resume", Self::Prompt => "prompt", Self::Abort => "abort", Self::Dispose => "dispose" }
    }
    fn from_name(name: &str) -> Option<Self> {
        Some(match name { "starting" => Self::Starting, "idle" => Self::Idle, "create" => Self::Create, "resume" => Self::Resume, "prompt" => Self::Prompt, "abort" => Self::Abort, "dispose" => Self::Dispose, _ => return None })
    }
}
#[derive(Clone, Copy)]
enum WorkerFailureCategory {
    StdoutClosed,
    InvalidWorkerEventFrame,
    OversizedOrUnreadableWorkerEventFrame,
    ProcessExited,
    ProcessWaitFailed,
    StdinWriteFailed,
    StdinFlushFailed,
    StdinReadFailed,
    InvalidJsonFrame,
    InvalidWorkerRequest,
    OversizedIpcFrame,
    TruncatedIpcFrame,
    ResponseFrameTooLarge,
    ResponseBackpressureOverflow,
    SessionCreateFailed,
    SessionResumeFailed,
    PromptFailed,
    AbortFailed,
    DisposeFailed,
    InvalidRequest,
    DeliveryUnsupported,
    SessionNotFound,
    SessionExists,
    SessionBusy,
    RequestMismatch,
    OtherWorkerError,
    UncaughtException,
}
impl WorkerFailureCategory {
    fn as_str(self) -> &'static str {
        match self {
            Self::StdoutClosed => "worker-stdout-closed",
            Self::InvalidWorkerEventFrame => "invalid-worker-event-frame",
            Self::OversizedOrUnreadableWorkerEventFrame => "oversized-or-unreadable-worker-event-frame",
            Self::ProcessExited => "worker-process-exited",
            Self::ProcessWaitFailed => "worker-process-wait-failed",
            Self::StdinWriteFailed => "worker-stdin-write-failed",
            Self::StdinFlushFailed => "worker-stdin-flush-failed",
            Self::StdinReadFailed => "worker-stdin-read-failed",
            Self::InvalidJsonFrame => "invalid-json-frame",
            Self::InvalidWorkerRequest => "invalid-worker-request",
            Self::OversizedIpcFrame => "oversized-ipc-frame",
            Self::TruncatedIpcFrame => "truncated-ipc-frame",
            Self::ResponseFrameTooLarge => "response-frame-too-large",
            Self::ResponseBackpressureOverflow => "response-backpressure-overflow",
            Self::SessionCreateFailed => "session-create-failed",
            Self::SessionResumeFailed => "session-resume-failed",
            Self::PromptFailed => "prompt-failed",
            Self::AbortFailed => "abort-failed",
            Self::DisposeFailed => "dispose-failed",
            Self::InvalidRequest => "invalid-request",
            Self::DeliveryUnsupported => "delivery-unsupported",
            Self::SessionNotFound => "session-not-found",
            Self::SessionExists => "session-exists",
            Self::SessionBusy => "session-busy",
            Self::RequestMismatch => "request-mismatch",
            Self::OtherWorkerError => "worker-reported-error",
            Self::UncaughtException => "uncaught-exception",
        }
    }
    fn from_diagnostic_name(name: &str) -> Option<Self> {
        Some(match name {
            "session-create-failed" => Self::SessionCreateFailed,
            "session-resume-failed" => Self::SessionResumeFailed,
            "prompt-failed" => Self::PromptFailed,
            "abort-failed" => Self::AbortFailed,
            "dispose-failed" => Self::DisposeFailed,
            "invalid-request" => Self::InvalidRequest,
            "invalid-json-frame" => Self::InvalidJsonFrame,
            "invalid-worker-request" => Self::InvalidWorkerRequest,
            "oversized-ipc-frame" => Self::OversizedIpcFrame,
            "truncated-ipc-frame" => Self::TruncatedIpcFrame,
            "stdin-read-failed" => Self::StdinReadFailed,
            "response-frame-too-large" => Self::ResponseFrameTooLarge,
            "response-backpressure-overflow" => Self::ResponseBackpressureOverflow,
            "uncaught-exception" => Self::UncaughtException,
            _ => return None,
        })
    }
    fn from_worker_code(code: &str) -> Self {
        match code {
            "session-create-failed" => Self::SessionCreateFailed,
            "session-resume-failed" => Self::SessionResumeFailed,
            "prompt-failed" => Self::PromptFailed,
            "abort-failed" => Self::AbortFailed,
            "dispose-failed" => Self::DisposeFailed,
            "invalid-request" => Self::InvalidRequest,
            "delivery-unsupported" => Self::DeliveryUnsupported,
            "session-not-found" => Self::SessionNotFound,
            "session-exists" => Self::SessionExists,
            "session-busy" => Self::SessionBusy,
            "request-mismatch" => Self::RequestMismatch,
            _ => Self::OtherWorkerError,
        }
    }
}
#[derive(Clone, Copy)]
struct WorkerFailure { phase: WorkerPhase, category: WorkerFailureCategory }

#[derive(Clone)]
struct WorkerHost {
    origin: uuid::Uuid,
    notification_endpoint: Option<String>,
    writer: Arc<tokio::sync::Mutex<Option<tokio::process::ChildStdin>>>,
    exited: watch::Receiver<bool>,
    pending: Arc<tokio::sync::Mutex<HashMap<String, PendingWorkerRoute>>>,
    pending_ui: Arc<tokio::sync::Mutex<HashMap<UiInteractionId, PendingPiUiRequest>>>,
    healthy: Arc<AtomicBool>,
    phase: Arc<AtomicU8>,
    failure: watch::Receiver<Option<WorkerFailure>>,
    failure_tx: watch::Sender<Option<WorkerFailure>>,
    open_gate: Arc<tokio::sync::Mutex<()>>,
    known_sessions:
        Arc<tokio::sync::Mutex<HashMap<kompi_protocol::ConversationId, WorkerSession>>>,
}
#[derive(Debug)]
struct TranscriptOwned;
impl std::fmt::Display for TranscriptOwned {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result { f.write_str("Another process owns this Pi transcript") }
}
impl std::error::Error for TranscriptOwned {}

struct PendingWorkerRoute {
    events: mpsc::Sender<WorkerEvent>,
    overflow: Option<tokio::sync::oneshot::Sender<()>>,
}
#[derive(Clone)]
struct WorkerSession {
    session_id: String,
    session_file: String,
    session_name: String,
}
fn executable(path: &Path, metadata: &fs::Metadata) -> bool {
    #[cfg(unix)] { let _ = path; metadata.is_file() && metadata.permissions().mode() & 0o111 != 0 }
    #[cfg(windows)] { metadata.is_file() && path.extension().is_some_and(|ext| ext.eq_ignore_ascii_case("exe")) }
}
fn resolve_sdk_runtime() -> Result<PathBuf> {
    let configured = std::env::var_os("KOMPI_SDK_RUNTIME").or_else(|| std::env::var_os("BUN_BIN"));
    if let Some(raw) = configured {
        let path = PathBuf::from(raw);
        if !path.is_absolute() {
            bail!("KOMPI_SDK_RUNTIME/BUN_BIN must be an absolute executable path");
        }
        let metadata = fs::metadata(&path).with_context(|| {
            format!(
                "SDK runtime {} does not exist; install Bun or set KOMPI_SDK_RUNTIME",
                path.display()
            )
        })?;
        if !executable(&path, &metadata) {
            bail!("SDK runtime {} is not an executable file", path.display());
        }
        return fs::canonicalize(&path)
            .with_context(|| format!("resolve SDK runtime {}", path.display()));
    }
    if let Some(path_var) = std::env::var_os("PATH") {
        for directory in std::env::split_paths(&path_var) {
            if !directory.is_absolute() { continue; }
            let candidate = directory.join(format!("bun{}", std::env::consts::EXE_SUFFIX));
            if let Ok(metadata) = fs::metadata(&candidate) {
                if executable(&candidate, &metadata) {
                    return fs::canonicalize(&candidate)
                        .with_context(|| format!("resolve Bun runtime {}", candidate.display()));
                }
            }
        }
    }
    bail!(
        "Bun runtime was not found on the service PATH; set KOMPI_SDK_RUNTIME to the absolute Bun executable path"
    )
}

impl WorkerHost {
    fn spawn(conversation_id: kompi_protocol::ConversationId, notification_endpoint: Option<&str>, pi_frames: PiPublishers, android_tools: tokio::sync::broadcast::Sender<WorkerToolPublication>, artifacts: artifact::ArtifactStore) -> Result<Self> {
        let origin = uuid::Uuid::new_v4();
        use tokio::process::Command;
        let worker_dir = match std::env::var_os("KOMPI_SDK_WORKER_DIR") {
            Some(raw) => {
                let path = PathBuf::from(raw);
                if !path.is_absolute() {
                    bail!("KOMPI_SDK_WORKER_DIR must be an absolute path");
                }
                path
            }
            None => crate::package_worker::default_dir()?,
        };
        let worker_dir=fs::canonicalize(&worker_dir).with_context(||format!("resolve Pi SDK worker package {}; set KOMPI_SDK_WORKER_DIR to its absolute package directory",worker_dir.display()))?;
        let package_path = worker_dir.join("package.json");
        let package: serde_json::Value = serde_json::from_slice(
            &fs::read(&package_path).context("read Pi SDK worker package manifest")?,
        )
        .context("parse Pi SDK worker package manifest")?;
        let worker_script = package
            .get("scripts")
            .and_then(|scripts| scripts.get("worker"))
            .and_then(serde_json::Value::as_str);
        if worker_script != Some("bun dist-worker/daemon/src/worker.js")
            || !worker_dir.join("dist-worker/daemon/src/worker.js").is_file()
        {
            bail!(
                "Pi SDK worker package must provide its compiled worker entry, dependencies, and matching worker script"
            );
        }
        let runtime = resolve_sdk_runtime()?;
        let android_tool_socket = std::env::temp_dir().join(format!("kompi-android-{}.sock", uuid::Uuid::new_v4()));
        let mut command = Command::new(&runtime);
        command.kill_on_drop(true);
        command.env("KOMPI_NATIVE_BINARY", std::env::current_exe()?);
        command.env_remove("KOMPI_NOTIFICATION_DEVICE_ENDPOINT").env_remove("KOMPI_NOTIFICATION_CONVERSATION_ID").env_remove("KOMPI_NATIVE_CLI");
        if let Some(endpoint) = notification_endpoint {
            anyhow::ensure!(!endpoint.is_empty() && endpoint.len() <= kompi_protocol::PUSH_ENDPOINT_MAX_BYTES, "invalid paired notification endpoint");
            let native_cli = std::fs::canonicalize(std::env::current_exe().context("resolve Kompi native CLI executable")?)
                .context("canonicalize Kompi native CLI executable")?;
            anyhow::ensure!(native_cli.is_absolute(), "Kompi native CLI path must be absolute");
            command.env("KOMPI_NOTIFICATION_DEVICE_ENDPOINT", endpoint).env("KOMPI_NOTIFICATION_CONVERSATION_ID", conversation_id.to_string()).env("KOMPI_NATIVE_CLI", native_cli);
        }
        let mut child = command
            .env("KOMPI_ANDROID_TOOL_SOCKET", &android_tool_socket)
            .arg("--no-install")
            .arg("dist-worker/daemon/src/worker.js")
            .current_dir(&worker_dir)
            .stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped())
            .spawn()
            .map_err(|_| {
                eprintln!("Kompi SDK worker failure phase=starting category=worker-process-spawn-failed package={}", worker_dir.display());
                anyhow::anyhow!("start Pi SDK worker failed (process-spawn-failed)")
            })?;
        let stdin = child.stdin.take().context("SDK worker stdin unavailable")?;
        let stdout = child
            .stdout
            .take()
            .context("SDK worker stdout unavailable")?;
        let stderr = child
            .stderr
            .take()
            .context("SDK worker stderr unavailable")?;
        eprintln!("Kompi SDK worker starting package={}", worker_dir.display());
        let healthy = Arc::new(AtomicBool::new(true));
        let phase = Arc::new(AtomicU8::new(WorkerPhase::Starting as u8));
        let (failure_tx, failure) = watch::channel(None);
        let writer = Arc::new(tokio::sync::Mutex::new(Some(stdin)));
        let (exited_tx, exited) = watch::channel(false);
        let pending = Arc::new(tokio::sync::Mutex::new(
            HashMap::<String, PendingWorkerRoute>::new(),
        ));
        let pending_ui = Arc::new(tokio::sync::Mutex::new(HashMap::<UiInteractionId, PendingPiUiRequest>::new()));
        let android_tools_reader = android_tools;
        let publication_peer = notification_endpoint.unwrap_or_default().to_owned();
        let artifact_scope = notification_endpoint.map(|peer| artifacts.scope(peer, conversation_id));
        let artifact_writer = Arc::downgrade(&writer);
        let ui_reader = pending_ui.clone();
        let pending_reader = pending.clone();
        let healthy_reader = healthy.clone();
        let phase_reader = phase.clone();
        let failure_reader = failure_tx.clone();
        tokio::spawn(async move {
            use tokio::io::{AsyncBufReadExt, AsyncReadExt};
            let mut reader = tokio::io::BufReader::new(stdout);
            loop {
                let mut line = Vec::new();
                let read = (&mut reader)
                    .take((kompi_protocol::MAX_PAYLOAD + 1) as u64)
                    .read_until(b'\n', &mut line)
                    .await;
                match read {
                    Ok(0) => {
                        mark_worker_failed(
                            &healthy_reader,
                            &phase_reader,
                            &failure_reader,
                            WorkerFailureCategory::StdoutClosed,
                        );
                        break;
                    }
                    Ok(n) if n <= kompi_protocol::MAX_PAYLOAD => {
                        let Ok(envelope) =
                            kompi_protocol::decode_worker_json_line::<WorkerEvent>(&line)
                        else {
                            mark_worker_failed(
                                &healthy_reader,
                                &phase_reader,
                                &failure_reader,
                                WorkerFailureCategory::InvalidWorkerEventFrame,
                            );
                            break;
                        };
                        match &envelope.message {
                            WorkerEvent::PiFrame { frame } => {
                                if frame.conversation_id != conversation_id {
                                    mark_worker_failed(&healthy_reader, &phase_reader, &failure_reader, WorkerFailureCategory::InvalidWorkerEventFrame);
                                    break;
                                }
                                pi_frames.publish(&publication_peer, PiPublication { frame: frame.clone(), origin, primary_generation: None }).await;
                                continue;
                            }
                            WorkerEvent::UiRequest { conversation_id: owner_id, interaction_id, dialog } => {
                                let mut dialogs = ui_reader.lock().await;
                                if *owner_id != conversation_id || dialogs.len() >= 8 {
                                    mark_worker_failed(&healthy_reader, &phase_reader, &failure_reader, WorkerFailureCategory::InvalidWorkerEventFrame);
                                    break;
                                }
                                dialogs.insert(interaction_id.clone(), PendingPiUiRequest { conversation_id, interaction_id: interaction_id.clone(), dialog: dialog.clone() });
                                continue;
                            }
                            WorkerEvent::UiResolved { conversation_id: owner_id, interaction_id } if *owner_id == conversation_id => {
                                ui_reader.lock().await.remove(interaction_id);
                                continue;
                            }
                            WorkerEvent::ArtifactOffer { conversation_id: owner_id, request_id, path, file_name } if *owner_id == conversation_id => {
                                if let Some(scope) = artifact_scope.clone() {
                                    tokio::spawn(artifact::reply_to_worker(artifacts.clone(), scope, request_id.clone(), PathBuf::from(path), file_name.clone(), artifact_writer.clone()));
                                }
                                continue;
                            }
                            WorkerEvent::AndroidToolRequest { conversation_id: owner_id, .. }
                            | WorkerEvent::AndroidToolCancel { conversation_id: owner_id, .. } => {
                                if *owner_id != conversation_id {
                                    mark_worker_failed(&healthy_reader, &phase_reader, &failure_reader, WorkerFailureCategory::InvalidWorkerEventFrame);
                                    break;
                                }
                                let _ = android_tools_reader.send(WorkerToolPublication { origin, event: envelope.message.clone() });
                                continue;
                            }
                            _ => {}
                        }
                        let operation_phase = WorkerPhase::from_atomic(phase_reader.load(Ordering::Acquire));
                        if let WorkerEvent::Error { code, .. } = &envelope.message {
                            eprintln!("Kompi SDK worker operation failed phase={} category={}", operation_phase.as_str(), WorkerFailureCategory::from_worker_code(code).as_str());
                        }
                        let terminal = matches!(&envelope.message, WorkerEvent::SnapshotDone { .. } | WorkerEvent::Created { .. } | WorkerEvent::Ready { .. } | WorkerEvent::Final { .. } | WorkerEvent::FollowUpAdded { .. } | WorkerEvent::FollowUpReleased { .. } | WorkerEvent::Error { .. } | WorkerEvent::Aborted { .. } | WorkerEvent::Disposed { .. });
                        let mut routes = pending_reader.lock().await;
                        if let Some(route) = routes.get_mut(envelope.id.as_str()) {
                            // Best effort and leave room for actual worker control events.
                            if matches!(&envelope.message, WorkerEvent::ChatTiming { .. }) {
                                if route.events.capacity() > 8 { let _ = route.events.try_send(envelope.message); }
                                continue;
                            }
                            if terminal { phase_reader.store(WorkerPhase::Idle as u8, Ordering::Release); }
                            match route.events.try_send(envelope.message) {
                                Ok(()) => {}
                                Err(mpsc::error::TrySendError::Full(_)) => {
                                    if let Some(overload) = route.overflow.take() {
                                        let _ = overload.send(());
                                    }
                                    routes.remove(envelope.id.as_str());
                                }
                                Err(mpsc::error::TrySendError::Closed(_)) => {
                                    routes.remove(envelope.id.as_str());
                                }
                            }
                        }
                    }
                    _ => {
                        mark_worker_failed(
                            &healthy_reader,
                            &phase_reader,
                            &failure_reader,
                            WorkerFailureCategory::OversizedOrUnreadableWorkerEventFrame,
                        );
                        break;
                    }
                }
            }
            pending_reader.lock().await.clear();
            ui_reader.lock().await.clear();
        });
        tokio::spawn(async move {
            use tokio::io::AsyncReadExt;
            let mut reader = stderr;
            let mut buffer = [0u8; 1024];
            let mut line = Vec::with_capacity(192);
            let mut oversized_line = false;
            let mut reported = 0usize;
            let mut suppressed = 0u64;
            loop {
                match reader.read(&mut buffer).await {
                    Ok(0) | Err(_) => break,
                    Ok(n) => {
                        if reported < 16 {
                            eprintln!("Kompi SDK worker emitted stderr; unrecognized content suppressed ({n} bytes)");
                            reported += 1;
                        } else {
                            suppressed = suppressed.saturating_add(n as u64);
                        }
                        for byte in &buffer[..n] {
                            if *byte == b'\n' {
                                if !oversized_line {
                                    if let Ok(text) = std::str::from_utf8(&line) {
                                        if let Some((phase, category, error_name, module)) = parse_worker_diagnostic(text) {
                                            eprintln!("Kompi worker diagnostic phase={} category={} error={} module={}", phase.as_str(), category.as_str(), error_name, module);
                                        }
                                    }
                                }
                                line.clear();
                                oversized_line = false;
                            } else if !oversized_line {
                                if line.len() < 512 { line.push(*byte); }
                                else { line.clear(); oversized_line = true; }
                            }
                        }
                    }
                }
            }
            if suppressed > 0 {
                eprintln!("Kompi SDK worker additional unrecognized stderr suppressed ({suppressed} bytes)");
            }
        });
        let healthy_wait = healthy.clone();
        let phase_wait = phase.clone();
        let failure_wait = failure_tx.clone();
        let pending_wait = pending.clone();
        tokio::spawn(async move {
            match child.wait().await {
                Ok(status) => {
                    let phase = WorkerPhase::from_atomic(phase_wait.load(Ordering::Acquire));
                    eprintln!("Kompi SDK worker process exited phase={} status={}", phase.as_str(), status);
                    mark_worker_failed(
                        &healthy_wait,
                        &phase_wait,
                        &failure_wait,
                        WorkerFailureCategory::ProcessExited,
                    );
                }
                Err(_) => mark_worker_failed(
                    &healthy_wait,
                    &phase_wait,
                    &failure_wait,
                    WorkerFailureCategory::ProcessWaitFailed,
                ),
            }
            pending_wait.lock().await.clear();
            exited_tx.send_replace(true);
        });
        Ok(Self {
            origin,
            notification_endpoint: notification_endpoint.map(str::to_owned),
            writer,
            exited,
            pending,
            pending_ui,
            healthy,
            phase,
            failure,
            failure_tx,
            open_gate: Arc::new(tokio::sync::Mutex::new(())),
            known_sessions: Arc::new(tokio::sync::Mutex::new(HashMap::new())),
        })
    }

    fn is_healthy(&self) -> bool {
        self.healthy.load(Ordering::Acquire)
    }

    fn failure_summary(&self) -> Option<String> {
        self.failure.borrow().as_ref().map(|failure| format!("phase={} category={}", failure.phase.as_str(), failure.category.as_str()))
    }

    async fn send(&self, envelope: WorkerRequestEnvelope) -> Result<()> {
        if !self.is_healthy() {
            bail!("Pi SDK worker is unavailable ({})", self.failure_summary().unwrap_or_else(|| "failure category pending".to_owned()));
        }
        let bytes = kompi_protocol::encode_worker_json_line(&envelope)?;
        let mut writer = self.writer.lock().await;
        let writer = writer.as_mut().context("Pi worker stdin is closed")?;
        if !self.is_healthy() {
            bail!("Pi SDK worker is unavailable ({})", self.failure_summary().unwrap_or_else(|| "failure category pending".to_owned()));
        }
        if let Err(error) = timeout(Duration::from_secs(5), writer.write_all(&bytes)).await.context("write to Pi worker timed out")? {
            mark_worker_failed(
                &self.healthy,
                &self.phase,
                &self.failure_tx,
                WorkerFailureCategory::StdinWriteFailed,
            );
            return Err(error).context("write to Pi SDK worker failed");
        }
        if let Err(error) = writer.flush().await {
            mark_worker_failed(
                &self.healthy,
                &self.phase,
                &self.failure_tx,
                WorkerFailureCategory::StdinFlushFailed,
            );
            return Err(error).context("flush to Pi SDK worker failed");
        }
        Ok(())
    }

    async fn reply_android_tool(
        &self,
        conversation_id: kompi_protocol::ConversationId,
        request_id: RequestId,
        success: bool,
        result_json: Option<String>,
        error_code: Option<String>,
        error_message: Option<String>,
    ) -> Result<()> {
        let id = RequestId::parse(format!("android-tool-reply-{}", uuid::Uuid::new_v4()))?;
        self.send(Envelope::new(id, WorkerRequest::AndroidToolResponse {
            conversation_id, request_id, success, result_json, error_code, error_message,
        })).await
    }

    async fn prepare(
        &self,
        conversation_id: kompi_protocol::ConversationId,
        session_id: Option<&str>,
        session_file: &str,
        phone_label: &str,
        created_at: kompi_protocol::Timestamp,
    ) -> Result<WorkerSession> {
        let waiting = std::time::Instant::now();
        let _opening = self.open_gate.lock().await;
        preparation_timing("open-gate", waiting);
        if let Some(session) = self
            .known_sessions
            .lock()
            .await
            .get(&conversation_id)
            .cloned()
        {
            return Ok(session);
        }
        let opening = std::time::Instant::now();
        let operation_id = RequestId::parse(format!("worker-open-{}", uuid::Uuid::new_v4()))?;
        let (tx, mut rx) = mpsc::channel(8);
        self.pending.lock().await.insert(
            operation_id.to_string(),
            PendingWorkerRoute {
                events: tx,
                overflow: None,
            },
        );
        let request = if session_file.is_empty() {
            self.phase.store(WorkerPhase::Create as u8, Ordering::Release);
            let created = created_at.as_jiff().to_zoned(jiff::tz::TimeZone::system());
            let named_phone = phone_label.chars().filter(|c| !c.is_control()).take(96).collect::<String>();
            let session_name = format!("Kompi · {} · {}", if named_phone.is_empty() { "Phone" } else { &named_phone }, created.strftime("%Y-%m-%d %H:%M:%S %:z"));
            WorkerRequest::Create {
                conversation_id,
                phone_label: Some(phone_label.to_owned()),
                created_at,
                session_name,
            }
        } else {
            self.phase.store(WorkerPhase::Resume as u8, Ordering::Release);
            WorkerRequest::Resume {
                conversation_id,
                session_id: session_id.map(str::to_owned),
                session_file: session_file.to_owned(),
            }
        };
        if let Err(error) = self.send(Envelope::new(operation_id.clone(), request)).await {
            self.pending.lock().await.remove(operation_id.as_str());
            return Err(error);
        }
        let result = timeout(Duration::from_secs(90), async {
            loop {
                match rx
                    .recv()
                    .await
                    .context("SDK worker exited opening session")?
                {
                    WorkerEvent::Created {
                        session_id,
                        session_file,
                        session_name,
                        ..
                    }
                    | WorkerEvent::Ready {
                        session_id,
                        session_file,
                        session_name,
                        ..
                    } => {
                        break Ok(WorkerSession {
                            session_id,
                            session_file,
                            session_name,
                        });
                    }
                    WorkerEvent::Error { code, .. } if code == "session-transcript-owned" => break Err(TranscriptOwned.into()),
                    WorkerEvent::Error { code, message, .. } => {
                        break Err(anyhow::anyhow!("SDK worker {code}: {message}"));
                    }
                    _ => continue,
                }
            }
        })
        .await;
        self.pending.lock().await.remove(operation_id.as_str());
        preparation_timing(if session_file.is_empty() { "create-rpc" } else { "resume-rpc" }, opening);
        let opened = result.context("SDK worker session open timed out")??;
        self.known_sessions
            .lock()
            .await
            .insert(conversation_id, opened.clone());
        Ok(opened)
    }

    async fn models(&self) -> Result<kompi_protocol::ModelCatalog> {
        let id = RequestId::parse(format!("models-{}", uuid::Uuid::new_v4()))?;
        let (events, mut rx) = mpsc::channel(2);
        self.pending.lock().await.insert(id.to_string(), PendingWorkerRoute { events, overflow: None });
        let result = async {
            self.send(Envelope::new(id.clone(), WorkerRequest::ModelsGet {})).await?;
            match timeout(Duration::from_secs(120), rx.recv()).await.context("model catalog timed out")?.context("model catalog worker closed")? {
                WorkerEvent::ModelsResult { catalog } => Ok(catalog),
                _ => bail!("Pi model catalog is unavailable"),
            }
        }.await;
        self.pending.lock().await.remove(id.as_str());
        result
    }

    async fn snapshot(&self, conversation_id: kompi_protocol::ConversationId, request_id: Option<RequestId>) -> Result<()> {
        let _opening = self.open_gate.lock().await;
        let id = RequestId::parse(format!("snapshot-{}", uuid::Uuid::new_v4()))?;
        let (events, mut rx) = mpsc::channel(2);
        self.pending.lock().await.insert(id.to_string(), PendingWorkerRoute { events, overflow: None });
        let result = async {
            self.send(Envelope::new(id.clone(), WorkerRequest::Snapshot { conversation_id, request_id })).await?;
            match timeout(Duration::from_secs(60), rx.recv()).await.context("snapshot worker timed out")?.context("snapshot worker closed")? {
                WorkerEvent::SnapshotDone { .. } => Ok(()),
                _ => bail!("Pi snapshot is unavailable"),
            }
        }.await;
        self.pending.lock().await.remove(id.as_str());
        result
    }

    async fn follow_up_command(&self, request: WorkerRequest) -> Result<WorkerEvent> {
        let id = RequestId::parse(format!("worker-follow-up-{}", uuid::Uuid::new_v4()))?;
        let (events, mut rx) = mpsc::channel(1);
        self.pending.lock().await.insert(id.to_string(), PendingWorkerRoute { events, overflow: None });
        let result = async {
            self.send(Envelope::new(id.clone(), request)).await?;
            timeout(Duration::from_secs(30), rx.recv()).await.context("Pi follow-up command timed out")?.context("Pi follow-up response route closed")
        }.await;
        self.pending.lock().await.remove(id.as_str());
        result
    }

    async fn begin_prompt(
        &self,
        request_id: RequestId,
        conversation_id: kompi_protocol::ConversationId,
        text: String,
        delivery: kompi_protocol::Delivery,
        configuration: kompi_protocol::ChatModelConfiguration,
        attachments: Option<Vec<kompi_protocol::worker::WorkerAttachmentRef>>,
    ) -> Result<(
        mpsc::Receiver<WorkerEvent>,
        tokio::sync::oneshot::Receiver<()>,
    )> {
        let (tx, rx) = mpsc::channel(128);
        let (overflow_tx, overflow_rx) = tokio::sync::oneshot::channel();
        self.phase.store(WorkerPhase::Prompt as u8, Ordering::Release);
        self.pending.lock().await.insert(
            request_id.to_string(),
            PendingWorkerRoute {
                events: tx,
                overflow: Some(overflow_tx),
            },
        );
        let request = Envelope::new(
            request_id.clone(),
            WorkerRequest::Prompt {
                request_id: request_id.clone(),
                conversation_id,
                text,
                delivery,
                configuration,
                attachments,
            },
        );
        if let Err(error) = self.send(request).await {
            self.pending.lock().await.remove(request_id.as_str());
            return Err(error);
        }
        Ok((rx, overflow_rx))
    }

    async fn abort(
        &self,
        request_id: RequestId,
        conversation_id: kompi_protocol::ConversationId,
    ) -> Result<()> {
        self.phase.store(WorkerPhase::Abort as u8, Ordering::Release);
        let operation_id = RequestId::parse(format!("worker-abort-{}", uuid::Uuid::new_v4()))?;
        let (tx, mut rx) = mpsc::channel(4);
        self.pending.lock().await.insert(
            operation_id.to_string(),
            PendingWorkerRoute {
                events: tx,
                overflow: None,
            },
        );
        if let Err(error) = self
            .send(Envelope::new(
                operation_id.clone(),
                WorkerRequest::Abort {
                    request_id,
                    conversation_id,
                },
            ))
            .await
        {
            self.pending.lock().await.remove(operation_id.as_str());
            return Err(error);
        }
        let result = timeout(Duration::from_secs(15), async {
            loop {
                match rx.recv().await.context("worker exited aborting prompt")? {
                    WorkerEvent::Aborted { .. } => break Ok(()),
                    WorkerEvent::Error { code, message, .. } => {
                        break Err(anyhow::anyhow!("SDK worker {code}: {message}"));
                    }
                    _ => continue,
                }
            }
        })
        .await;
        self.pending.lock().await.remove(operation_id.as_str());
        result.context("SDK worker abort timed out")??;
        Ok(())
    }

    async fn shutdown(&self) -> Result<()> {
        self.healthy.store(false, Ordering::Release);
        // Explicit EOF is independent of how many routing tasks hold a WorkerHost.
        self.writer.lock().await.take();
        self.pending.lock().await.clear();
        let mut exited = self.exited.clone();
        timeout(Duration::from_secs(75), exited.wait_for(|closed| *closed))
            .await.context("Pi worker did not release ownership after EOF")??;
        Ok(())
    }
}

#[derive(Clone)]
struct PiPublication { frame: kompi_protocol::PiFrame, origin: uuid::Uuid, primary_generation: Option<u64> }

type PiSender = tokio::sync::broadcast::Sender<PiPublication>;
#[derive(Clone)]
struct PiPublishers {
    control: PiSender,
    scoped: Arc<Mutex<HashMap<(String, kompi_protocol::ConversationId), mpsc::WeakSender<PiPublication>>>>,
    primary: Arc<Mutex<HashMap<(kompi_protocol::ConversationId, RequestId), (u64, RequestId)>>>,
}
impl PiPublishers {
    fn new() -> Self {
        Self { control: tokio::sync::broadcast::channel(8192).0, scoped: Default::default(), primary: Default::default() }
    }
    fn conversation(&self, peer: &str, id: kompi_protocol::ConversationId) -> (mpsc::Sender<PiPublication>, mpsc::Receiver<PiPublication>) {
        let mut scoped = self.scoped.lock().expect("Pi publishers poisoned");
        scoped.retain(|_, sender| sender.strong_count() != 0);
        let (sender, receiver) = mpsc::channel(128);
        scoped.insert((peer.to_owned(), id), sender.downgrade());
        (sender, receiver)
    }
    async fn publish(&self, peer: &str, mut publication: PiPublication) {
        let sender = self.scoped.lock().expect("Pi publishers poisoned")
            .get(&(peer.to_owned(), publication.frame.conversation_id)).and_then(mpsc::WeakSender::upgrade);
        if matches!(publication.frame.kind, kompi_protocol::PiFrameKind::Snapshot) && publication.frame.request_id.is_some() {
            // Internally generated tokens distinguish requests across streams and
            // reconnects, even if two clients choose the same envelope ID.
            let route = self.primary.lock().expect("Pi routes poisoned")
                .get(&(publication.frame.conversation_id, publication.frame.request_id.clone().expect("checked token"))).cloned();
            if let Some((generation, request)) = route {
                publication.primary_generation = Some(generation);
                publication.frame.request_id = Some(request);
                let _ = self.control.send(publication);
            } else if let Some(sender) = sender { let _ = sender.send(publication).await; }
            return;
        }
        if let Some(sender) = sender { let _ = sender.send(publication.clone()).await; }
        let _ = self.control.send(publication);
    }
}

#[derive(Clone)]
struct WorkerToolPublication { origin: uuid::Uuid, event: WorkerEvent }

struct CatalogSnapshot {
    observed_at: std::time::Instant,
    catalog: kompi_protocol::ModelCatalog,
}

enum CatalogWorker {
    Available(Option<WorkerHost>),
    Closed,
}

#[derive(Clone)]
struct WorkerPool {
    catalog_requests: Arc<tokio::sync::Semaphore>,
    catalog: Arc<Mutex<Option<CatalogSnapshot>>>,
    catalog_worker: Arc<Mutex<CatalogWorker>>,
    catalog_refresh: Arc<tokio::sync::Mutex<Option<std::time::Instant>>>,
    artifacts: artifact::ArtifactStore,
    pi_frames: PiPublishers,
    android_tools: tokio::sync::broadcast::Sender<WorkerToolPublication>,
    processes: Arc<tokio::sync::Mutex<HashMap<kompi_protocol::ConversationId, WorkerHost>>>,
}
impl WorkerPool {
    fn new() -> Self {
        let pi_frames = PiPublishers::new();
        let (android_tools, _) = tokio::sync::broadcast::channel(32);
        Self { pi_frames, android_tools, artifacts: artifact::ArtifactStore::default(), processes: Arc::new(tokio::sync::Mutex::new(HashMap::new())), catalog_requests: Arc::new(tokio::sync::Semaphore::new(32)), catalog: Arc::new(Mutex::new(None)), catalog_worker: Arc::new(Mutex::new(CatalogWorker::Available(None))), catalog_refresh: Arc::new(tokio::sync::Mutex::new(None)) }
    }

    async fn models(&self) -> Result<kompi_protocol::ModelCatalog> {
        let requested = std::time::Instant::now();
        let mut failure = self.catalog_refresh.lock().await;
        if failure.is_some_and(|at| at >= requested) { bail!("Pi model catalog refresh failed"); }
        if let Some(snapshot) = self.catalog.lock().expect("model catalog poisoned").as_ref().filter(|s| s.observed_at >= requested) {
            return Ok(snapshot.catalog.clone());
        }
        self.discover_catalog(&mut failure).await
    }

    async fn discover_catalog(&self, last_failure: &mut Option<std::time::Instant>) -> Result<kompi_protocol::ModelCatalog> {
        let result = async {
            let host = {
                let mut worker = self.catalog_worker.lock().expect("catalog worker poisoned");
                let CatalogWorker::Available(host) = &mut *worker else { bail!("catalog worker is closed"); };
                if host.as_ref().is_none_or(|host| !host.is_healthy()) {
                    *host = Some(WorkerHost::spawn(uuid::Uuid::new_v4().into(), None, self.pi_frames.clone(), self.android_tools.clone(), self.artifacts.clone())?);
                }
                host.as_ref().context("catalog worker is unavailable")?.clone()
            };
            host.models().await
        }.await;
        let observed_at = std::time::Instant::now();
        match result {
            Ok(catalog) => {
                *last_failure = None;
                *self.catalog.lock().expect("model catalog poisoned") = Some(CatalogSnapshot {
                    observed_at,
                    catalog: catalog.clone(),
                });
                Ok(catalog)
            }
            Err(error) => {
                *last_failure = Some(observed_at);
                Err(error)
            }
        }
    }

    fn subscribe_pi(&self) -> tokio::sync::broadcast::Receiver<PiPublication> { self.pi_frames.control.subscribe() }

    async fn is_current_origin_for(&self, peer: &str, id: kompi_protocol::ConversationId, origin: uuid::Uuid) -> bool {
        self.processes.lock().await.get(&id).is_some_and(|worker|
            worker.notification_endpoint.as_deref() == Some(peer) && worker.origin == origin && worker.is_healthy())
    }

    async fn belongs_to(&self, peer: &str, id: kompi_protocol::ConversationId) -> bool {
        self.processes.lock().await.get(&id).is_some_and(|worker| worker.notification_endpoint.as_deref() == Some(peer))
    }

    async fn runtime_ids(&self) -> HashSet<kompi_protocol::ConversationId> {
        self.processes.lock().await.keys().copied().collect()
    }

    async fn conversations_for(&self, peer: &str) -> Vec<kompi_protocol::ConversationId> {
        self.processes.lock().await.iter().filter_map(|(id, worker)|
            (worker.notification_endpoint.as_deref() == Some(peer)).then_some(*id)).collect()
    }

    async fn snapshot(&self, conversation_id: kompi_protocol::ConversationId, request_id: Option<RequestId>) -> Result<()> {
        self.process(conversation_id).await?.snapshot(conversation_id, request_id).await
    }

    async fn process(&self, conversation_id: kompi_protocol::ConversationId) -> Result<WorkerHost> {
        self.processes.lock().await.get(&conversation_id).cloned().context("session worker is unavailable")
    }

    async fn process_for(&self, conversation_id: kompi_protocol::ConversationId, peer: &str) -> Result<WorkerHost> {
        let mut processes = self.processes.lock().await;
        if let Some(process) = processes.get(&conversation_id) {
            anyhow::ensure!(process.notification_endpoint.as_deref() == Some(peer), "conversation worker is not bound to this paired device");
            return Ok(process.clone());
        }
        let process = WorkerHost::spawn(conversation_id, Some(peer), self.pi_frames.clone(), self.android_tools.clone(), self.artifacts.clone())?;
        processes.insert(conversation_id, process.clone());
        Ok(process)
    }

    async fn begin_prompt(&self, request_id: RequestId, conversation_id: kompi_protocol::ConversationId, text: String, delivery: kompi_protocol::Delivery, configuration: kompi_protocol::ChatModelConfiguration, attachments: Option<Vec<kompi_protocol::worker::WorkerAttachmentRef>>) -> Result<(mpsc::Receiver<WorkerEvent>, tokio::sync::oneshot::Receiver<()>)> {
        self.process(conversation_id).await?.begin_prompt(request_id, conversation_id, text, delivery, configuration, attachments).await
    }

    async fn add_follow_up(&self, conversation_id: kompi_protocol::ConversationId, request: WorkerRequest) -> Result<WorkerEvent> {
        let process = self.processes.lock().await.get(&conversation_id).cloned().context("session worker is unavailable")?;
        process.follow_up_command(request).await
    }

    async fn release_follow_up(&self, conversation_id: kompi_protocol::ConversationId) -> Result<()> {
        let event = self.add_follow_up(conversation_id, WorkerRequest::FollowUpRelease { conversation_id }).await?;
        anyhow::ensure!(matches!(event, WorkerEvent::FollowUpReleased {}), "Pi follow-up boundary could not be released");
        Ok(())
    }

    async fn abort(&self, request_id: RequestId, conversation_id: kompi_protocol::ConversationId) -> Result<()> {
        let process = self.processes.lock().await.get(&conversation_id).cloned().context("session worker is unavailable")?;
        process.abort(request_id, conversation_id).await
    }

    async fn dispose(&self, conversation_id: kompi_protocol::ConversationId) -> Result<()> {
        let process = self.processes.lock().await.get(&conversation_id).cloned();
        if let Some(process) = process {
            process.shutdown().await?;
            let mut processes = self.processes.lock().await;
            if processes.get(&conversation_id).is_some_and(|current| current.origin == process.origin) {
                processes.remove(&conversation_id);
            }
        }
        Ok(())
    }

    async fn pending_ui(&self, conversation_id: kompi_protocol::ConversationId) -> Vec<PendingPiUiRequest> {
        let process = self.processes.lock().await.get(&conversation_id).cloned();
        let Some(process) = process.filter(WorkerHost::is_healthy) else { return Vec::new() };
        let mut pending = process.pending_ui.lock().await.values().cloned().collect::<Vec<_>>();
        pending.sort_by(|a, b| a.interaction_id.as_str().cmp(b.interaction_id.as_str()));
        pending.truncate(1); // Keep the bounded control frame and the phone UI sequential.
        pending
    }

    async fn reply_android_tool(
        &self,
        conversation_id: kompi_protocol::ConversationId,
        request_id: RequestId,
        success: bool,
        result_json: Option<String>,
        error_code: Option<String>,
        error_message: Option<String>,
    ) -> Result<()> {
        let process = self.processes.lock().await.get(&conversation_id).cloned().context("Android tool worker is unavailable")?;
        process.reply_android_tool(conversation_id, request_id, success, result_json, error_code, error_message).await
    }

    async fn reply_ui(&self, conversation_id: kompi_protocol::ConversationId, interaction_id: UiInteractionId, answer: PiUiAnswer) -> Result<()> {
        let process = self.processes.lock().await.get(&conversation_id).cloned().context("CLI worker is unavailable")?;
        let request = {
            let mut dialogs = process.pending_ui.lock().await;
            let pending = dialogs.get(&interaction_id).cloned().context("CLI dialog is no longer pending")?;
            let request = parse_ui_answer(pending, answer)?;
            dialogs.remove(&interaction_id);
            request
        };
        // Once written or ambiguously failed, never forward a second answer.
        process.send(Envelope::new(RequestId::parse(format!("ui-answer-{}", uuid::Uuid::new_v4()))?, request)).await
    }

    async fn remove_pending(&self, conversation_id: kompi_protocol::ConversationId, id: &str) {
        if let Some(process) = self.processes.lock().await.get(&conversation_id).cloned() {
            process.pending.lock().await.remove(id);
        }
    }

    async fn is_healthy(&self, conversation_id: kompi_protocol::ConversationId) -> bool {
        self.processes.lock().await.get(&conversation_id).is_some_and(WorkerHost::is_healthy)
    }

    async fn shutdown(&self) {
        let catalog_worker = std::mem::replace(&mut *self.catalog_worker.lock().expect("catalog worker poisoned"), CatalogWorker::Closed);
        let mut workers = tokio::task::JoinSet::new();
        if let CatalogWorker::Available(Some(worker)) = catalog_worker { workers.spawn(async move { worker.shutdown().await }); }
        let processes = std::mem::take(&mut *self.processes.lock().await);
        for (_, process) in processes { workers.spawn(async move { process.shutdown().await }); }
        while let Some(result) = workers.join_next().await {
            if !matches!(result, Ok(Ok(()))) { eprintln!("Kompi worker did not close cleanly"); }
        }
    }
}

/// Parse a wire answer against the pending question once, before it can reach Pi.
fn parse_ui_answer(pending: PendingPiUiRequest, answer: PiUiAnswer) -> Result<WorkerRequest> {
    let parsed = match (&pending.dialog, answer) {
        (_, answer @ PiUiAnswer::Cancelled {}) => answer,
        (kompi_protocol::PiUiDialog::Select { options, .. }, PiUiAnswer::Selected { index }) if usize::from(index) < options.len() => PiUiAnswer::Selected { index },
        (kompi_protocol::PiUiDialog::Confirm { .. }, answer @ PiUiAnswer::Confirmed { .. }) => answer,
        (kompi_protocol::PiUiDialog::Input { .. } | kompi_protocol::PiUiDialog::Editor { .. }, answer @ PiUiAnswer::Text { .. }) => answer,
        _ => bail!("CLI answer does not match the pending question"),
    };
    Ok(WorkerRequest::UiResponse { conversation_id: pending.conversation_id, interaction_id: pending.interaction_id, answer: parsed })
}

async fn send_error_stream(
    mut send: SendStream,
    _recv: RecvStream,
    code: &str,
    message: &str,
) -> Result<()> {
    let envelope = Envelope::new(
        RequestId::parse("already-connected")?,
        Message::Error {
            code: code.into(),
            message: message.into(),
            session_id: None,
            conversation_id: None,
        },
    );
    let bytes = kompi_protocol::encode_cbor(&envelope)?;
    write_frame(&mut send, &bytes).await?;
    send.finish()?;
    Ok(())
}

fn device_summary(p: Device) -> Result<DeviceSummary> {
    Ok(DeviceSummary {
        endpoint: p.endpoint,
        name: p.name,
        paired_at: wire_timestamp(p.paired_at)?,
        last_seen: p.last_seen.map(wire_timestamp).transpose()?,
    })
}
fn conversation_wire(s: ConversationState) -> kompi_protocol::ConversationState {
    match s {
        ConversationState::Idle | ConversationState::Busy => {
            kompi_protocol::ConversationState::Active
        }
        ConversationState::Closed => kompi_protocol::ConversationState::Closed,
        ConversationState::Expired => kompi_protocol::ConversationState::Expired,
        ConversationState::Unavailable => kompi_protocol::ConversationState::Unavailable,
    }
}
fn delivery_key(delivery: kompi_protocol::Delivery) -> &'static str {
    match delivery {
        kompi_protocol::Delivery::FollowUp => "followUp",
    }
}

fn mark_worker_failed(
    healthy: &AtomicBool,
    phase: &AtomicU8,
    failure_tx: &watch::Sender<Option<WorkerFailure>>,
    category: WorkerFailureCategory,
) {
    if !healthy.swap(false, Ordering::AcqRel) { return; }
    let failure = WorkerFailure { phase: WorkerPhase::from_atomic(phase.load(Ordering::Acquire)), category };
    if failure_tx_first(failure, failure_tx) {
        eprintln!("Kompi SDK worker failure phase={} category={}", failure.phase.as_str(), failure.category.as_str());
    }
}
fn failure_tx_first(value: WorkerFailure, sender: &watch::Sender<Option<WorkerFailure>>) -> bool {
    sender.send_if_modified(|current| {
        if current.is_some() { false } else { *current = Some(value); true }
    })
}

fn parse_worker_diagnostic(line: &str) -> Option<(WorkerPhase, WorkerFailureCategory, &'static str, String)> {
    if line.len() > 512 { return None; }
    let mut fields = line.split_ascii_whitespace();
    if fields.next()? != "[pi-worker]" || fields.next()? != "failure" { return None; }
    let phase = fields.next()?.strip_prefix("phase=").and_then(WorkerPhase::from_name)?;
    let category = fields.next()?.strip_prefix("category=").and_then(WorkerFailureCategory::from_diagnostic_name)?;
    let error_name = match fields.next()?.strip_prefix("error=")? {
        "Error" => "Error", "TypeError" => "TypeError", "RangeError" => "RangeError",
        "ReferenceError" => "ReferenceError", "SyntaxError" => "SyntaxError",
        "AggregateError" => "AggregateError", "AbortError" => "AbortError", _ => return None,
    };
    let module = fields.next()?.strip_prefix("module=")?;
    if fields.next().is_some() || module.len() > 160 || !module.bytes().all(|byte| byte.is_ascii_alphanumeric() || b"@._/-:?".contains(&byte)) {
        return None;
    }
    Some((phase, category, error_name, module.to_owned()))
}

fn encode_durable_event(event: &DurableEvent) -> Result<Vec<u8>> {
    let mut bytes = Vec::new();
    ciborium::ser::into_writer(event, &mut bytes)
        .context("encode durable event for SQLite journal")?;
    Ok(bytes)
}

fn wire_timestamp(milliseconds: i64) -> Result<kompi_protocol::Timestamp> {
    let timestamp = jiff::Timestamp::from_millisecond(milliseconds)?;
    Ok(kompi_protocol::Timestamp::try_from(timestamp.to_string())?)
}
fn conversation_message(row: &crate::store::ConversationRecord, configuration: Option<kompi_protocol::ChatModelConfiguration>) -> Result<Message> {
    let session_id = row
        .session_id
        .clone()
        .context("Pi SDK session identity not yet persisted")?;
    let idle_timeout_seconds = row
        .idle_timeout_seconds
        .context("conversation idle policy is unavailable")?;
    Ok(Message::Conversation {
        configuration,
        session_id,
        conversation_id: row.id,
        state: conversation_wire(row.state.clone()),
        created_at: wire_timestamp(row.created_at)?,
        expires_at: wire_timestamp(
            row.last_user_prompt_at + i64::from(idle_timeout_seconds.get()) * 1000,
        )?,
        idle_timeout_seconds,
        session_name: row.session_name.clone(),
    })
}
fn attachment_prompt_fingerprint(
    text: &str,
    attachment_ids: &[kompi_protocol::ConversationId],
) -> String {
    let mut digest = Sha256::new();
    digest.update(b"kompi-attachment-prompt-v1\0");
    digest.update((text.len() as u64).to_le_bytes());
    digest.update(text.as_bytes());
    digest.update((attachment_ids.len() as u64).to_le_bytes());
    for upload_id in attachment_ids {
        digest.update(upload_id.as_uuid().as_bytes());
    }
    format!("[attachment-prompt:{}]", hex(&digest.finalize()))
}

fn attachment_error(code: &str, message: &str, upload_id: kompi_protocol::ConversationId) -> Message {
    Message::Error {
        code: code.to_owned(),
        message: message.to_owned(),
        session_id: None,
        conversation_id: Some(upload_id),
    }
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}
fn snapshot_error(conversation_id: Option<kompi_protocol::ConversationId>) -> Message {
    Message::Error { code: "pi-snapshot-failed".into(), message: "This conversation’s live Pi snapshot is unavailable.".into(), session_id: None, conversation_id }
}

fn activation_error(conversation_id: kompi_protocol::ConversationId, code: &str, message: &str) -> Message {
    Message::Error { code: code.into(), message: message.into(), session_id: None, conversation_id: Some(conversation_id) }
}

fn preparation_timing(stage: &'static str, started: std::time::Instant) {
    if std::env::var_os("KOMPI_PREPARE_TIMING").is_some() {
        eprintln!("Kompi session preparation: stage={stage} elapsed_ms={}", started.elapsed().as_millis());
    }
}

fn public_prompt_failure(code: &str) -> &'static str {
    // Never relay arbitrary worker/provider exception messages or credentials.
    match code {
        "model-configuration-unavailable" => "The selected model or effort is unavailable. Open the model selector, choose a configured model, and resend.",
        "model-authentication-failed" => "Model authentication failed or expired. Sign in again on the computer, then resend your message.",
        "model-quota-exceeded" => "The model provider's quota or billing limit was reached. Check the account on the computer, then resend your message.",
        "model-rate-limited" => "The model provider is rate limiting requests. Wait a moment, then resend your message.",
        "model-context-too-large" => "This conversation exceeds the model's context limit. Start a new conversation or compact it on the computer.",
        "model-unavailable" => "The model provider is unavailable or could not be reached. Try again shortly.",
        _ => "The model request failed. Check the Pi session on the computer for details, then resend your message.",
    }
}

#[cfg(windows)]
fn machine_hostname() -> Result<String> {
    use windows_sys::Win32::System::SystemInformation::{ComputerNameDnsHostname, GetComputerNameExW};
    let mut buffer = [0u16; 256]; let mut len = buffer.len() as u32;
    if unsafe { GetComputerNameExW(ComputerNameDnsHostname, buffer.as_mut_ptr(), &mut len) } == 0 { return Err(std::io::Error::last_os_error().into()); }
    let name = String::from_utf16(&buffer[..len as usize])?;
    anyhow::ensure!(!name.trim().is_empty(), "machine hostname is empty");
    Ok(name)
}
#[cfg(unix)]
fn machine_hostname() -> Result<String> {
    let mut buffer = [0u8; 256];
    // SAFETY: buffer is writable for the full length passed to gethostname.
    if unsafe { libc::gethostname(buffer.as_mut_ptr().cast(), buffer.len()) } != 0 {
        return Err(std::io::Error::last_os_error()).context("read machine hostname");
    }
    let hostname = std::ffi::CStr::from_bytes_until_nul(&buffer)
        .context("machine hostname is too long")?
        .to_str()
        .context("machine hostname is not valid UTF-8")?
        .trim();
    if hostname.is_empty() {
        bail!("machine hostname is empty; provide an explicit pairing name");
    }
    Ok(hostname.to_owned())
}

async fn send_web_push(endpoint: &url::Url, subscription: &web_push::SubscriptionInfo, ciphertext: &[u8], ttl_ms: u64) -> Result<reqwest::StatusCode> {
    anyhow::ensure!(endpoint.scheme()=="https" && subscription.endpoint==endpoint.as_str(), "push endpoint changed after validation");
    let client=reqwest::Client::builder().redirect(reqwest::redirect::Policy::none()).connect_timeout(Duration::from_secs(5)).timeout(Duration::from_secs(12)).build()?;
    let max_ttl_seconds=((kompi_protocol::PUSH_MAX_TTL_MS as u64)+999)/1000;
    let ttl_seconds=((ttl_ms.saturating_add(999)/1000).max(1).min(max_ttl_seconds)) as u32;
    let response=client.post(endpoint.clone())
        .header(reqwest::header::CONTENT_TYPE,"application/octet-stream")
        .header(reqwest::header::CONTENT_ENCODING,"aes128gcm")
        .header("TTL",ttl_seconds.to_string())
        .body(ciphertext.to_vec())
        .send().await?;
    Ok(response.status())
}

fn notification_status_message(status:DeviceNotificationStatus)->&'static str {
    match status {
        DeviceNotificationStatus::Queued=>"Queued durably for UnifiedPush delivery; Android posting is not confirmed.",
        DeviceNotificationStatus::PushAccepted=>"The push provider accepted the encrypted message; Android posting is not confirmed.",
        DeviceNotificationStatus::Posted=>"Android confirmed the notification was posted; this does not confirm that the user saw or heard it.",
        DeviceNotificationStatus::NotPosted=>"This notification request was rejected or its ID conflicts with an earlier request.",
        DeviceNotificationStatus::Unknown=>"Delivery status is unknown; do not retry automatically.",
        DeviceNotificationStatus::Offline=>"The phone is offline and no request was dispatched.",
        DeviceNotificationStatus::Unavailable=>"No background push route is available; no notification was queued.",
    }
}

fn notification_status(status:&str) -> DeviceNotificationStatus {
    match status {
        "queued"|"retry"=>DeviceNotificationStatus::Queued,
        "push-accepted"=>DeviceNotificationStatus::PushAccepted,
        "posted"=>DeviceNotificationStatus::Posted,
        "not-posted"=>DeviceNotificationStatus::NotPosted,
        "unknown"|"direct-dispatching"=>DeviceNotificationStatus::Unknown,
        "offline"=>DeviceNotificationStatus::Offline,
        "unavailable"=>DeviceNotificationStatus::Unavailable,
        // A new or corrupt durable status is ambiguous, never claim that no route exists.
        _=>DeviceNotificationStatus::Unknown,
    }
}

#[cfg(test)]
mod push_status_tests {
    use super::*;

    #[test]
    fn durable_notification_statuses_map_without_false_route_claims() {
        assert_eq!(notification_status("queued"), DeviceNotificationStatus::Queued);
        assert_eq!(notification_status("retry"), DeviceNotificationStatus::Queued);
        assert_eq!(notification_status("push-accepted"), DeviceNotificationStatus::PushAccepted);
        assert_eq!(notification_status("posted"), DeviceNotificationStatus::Posted);
        assert_eq!(notification_status("not-posted"), DeviceNotificationStatus::NotPosted);
        assert_eq!(notification_status("unknown"), DeviceNotificationStatus::Unknown);
        assert_eq!(notification_status("direct-dispatching"), DeviceNotificationStatus::Unknown);
        assert_eq!(notification_status("offline"), DeviceNotificationStatus::Offline);
        assert_eq!(notification_status("unavailable"), DeviceNotificationStatus::Unavailable);
        assert_eq!(notification_status("future-status"), DeviceNotificationStatus::Unknown);
    }
}

fn canonical_push_origin(origin: &str) -> Result<String> {
    let parsed = url::Url::parse(origin).context("parse push origin")?;
    anyhow::ensure!(parsed.scheme() == "https" && parsed.host().is_some(), "push origin must use HTTPS");
    anyhow::ensure!(parsed.username().is_empty() && parsed.password().is_none(), "push origin must not include credentials");
    anyhow::ensure!(parsed.path() == "/" && parsed.query().is_none() && parsed.fragment().is_none(), "push origin must not include a path, query, or fragment");
    let canonical = parsed.origin().ascii_serialization();
    anyhow::ensure!(origin == canonical, "push origin must be canonical");
    Ok(canonical)
}

fn safe_error(raw: &str) -> String {
    raw.chars()
        .filter(|c| !c.is_control())
        .take(2048)
        .collect::<String>()
        .split_whitespace()
        .map(|token| {
            if token.len() > 40 {
                "[redacted]"
            } else {
                token
            }
        })
        .collect::<Vec<_>>()
        .join(" ")
}
fn control_tag(message: &ControlMessage) -> &'static str {
    match message {
        ControlMessage::Status {} => "status",
        ControlMessage::DevicesList {} => "devices-list",
        ControlMessage::DeviceRevoke { .. } => "device-revoke",
        ControlMessage::DeviceNotification { .. } => "device-notification",
        ControlMessage::DeviceRichNotification { .. } => "device-rich-notification",
        ControlMessage::DeviceNotificationResult { .. } => "device-notification-result",
        ControlMessage::PairingCreate { .. } => "pairing-create",
        _ => "response-only message",
    }
}
fn control_socket_path(state_dir: &Path) -> PathBuf {
    crate::store::control_socket_path(state_dir)
}
