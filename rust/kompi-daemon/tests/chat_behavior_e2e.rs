//! Real daemon process + native QUIC + durable SQLite + controlled worker subprocess.
//! Only model execution is replaced. No production daemon, home, credentials or Pi session is used.
use anyhow::{Context, Result, bail};
use iroh::{
    Endpoint, EndpointAddr,
    endpoint::{Connection, RecvStream, SendStream, presets},
};
use iroh_tickets::endpoint::EndpointTicket;
use kompi_daemon::{
    sqlite_store::SqliteStore,
    store::{Device, control_socket_path, now_ms},
    wire::{read_frame, write_frame},
};
use kompi_protocol::{control::ControlMessage, *};
use serde_json::{Value, json};
use std::{path::PathBuf, process::Stdio, time::Duration};
use tokio::{
    io::{AsyncBufReadExt, AsyncWriteExt, BufReader},

    process::{Child, Command},
    time::timeout,
};
use uuid::Uuid;
use kompi_daemon::local_ipc::{self, Listener, ServerStream};

fn id(value: &str) -> RequestId {
    RequestId::parse(value.to_owned()).unwrap()
}
fn prompt(text: &str) -> Message {
    // Each isolated harness models one phone draft. An unscoped Prompt starts
    // a new chat, so it cannot exercise ordering within the same conversation.
    Message::ConversationPrompt {
        conversation_id: Uuid::from_u128(0x979fbcd1_b435_4907_bae7_846ca8451da3).into(),
        create_if_missing: Some(true),
        attempt_id: None,
        text: text.into(),
        delivery: Delivery::FollowUp,
        attachments: None,
        configuration: ChatModelConfiguration {
            provider: "fixture".into(),
            model_id: "controlled".into(),
            effort: EffortLevel::High,
        },
    }
}

struct Phone {
    connection: Connection,
    send: SendStream,
    recv: RecvStream,
    inbox: Vec<Envelope<Message>>,
}
impl Phone {
    async fn connect(endpoint: &Endpoint, address: EndpointAddr) -> Result<Self> {
        let connection = endpoint.connect(address, ALPN.as_bytes()).await?;
        let (send, recv) = connection.open_bi().await?;
        let mut phone = Self {
            connection,
            send,
            recv,
            inbox: vec![],
        };
        phone
            .send(
                "hello",
                Message::Hello {
                    capabilities: vec![PHONE_CONVERSATIONS_CAPABILITY.into(), CHAT_STREAMS_CAPABILITY.into()],
                    endpoint: Some(endpoint.id().to_string()),
                    session: None,
                    idle_timeout_seconds: Some(IdleTimeoutSeconds::try_from(1800)?),
                },
            )
            .await?;
        phone
            .wait(|e| e.id.as_str() == "hello" && matches!(e.message, Message::Ack { .. }))
            .await?;
        Ok(phone)
    }
    async fn send(&mut self, request: &str, message: Message) -> Result<()> {
        write_frame(
            &mut self.send,
            &encode_cbor(&Envelope::new(id(request), message))?,
        )
        .await
    }
    async fn wait(
        &mut self,
        predicate: impl Fn(&Envelope<Message>) -> bool,
    ) -> Result<Envelope<Message>> {
        timeout(Duration::from_secs(20), async {
            loop {
                if let Some(index) = self.inbox.iter().position(&predicate) {
                    return Ok(self.inbox.remove(index));
                }
                let bytes = read_frame(&mut self.recv)
                    .await?
                    .context("daemon closed messaging stream")?;
                let event = decode_cbor::<Message>(&bytes).with_context(|| {
                    // Inspect only the envelope tag for diagnostics; never bypass the
                    // production decoder to accept a packet it rejects.
                    let raw: Option<Value> = ciborium::de::from_reader(bytes.as_slice()).ok();
                    let tag = raw
                        .as_ref()
                        .and_then(|v| v.get("message"))
                        .and_then(|v| v.get("type"))
                        .and_then(Value::as_str)
                        .unwrap_or("unknown");
                    format!("daemon {tag} envelope rejected by production protocol decoder")
                })?;
                self.inbox.push(event);
            }
        })
        .await
        .context("timed out awaiting observable daemon outcome")?
    }
    async fn accepted(&mut self, request: &str) -> Result<()> {
        let response = self
            .wait(|e| {
                e.id.as_str() == request
                    && matches!(
                        e.message,
                        Message::Ack { .. }
                            | Message::Error { .. }
                            | Message::PreacceptanceRejected { .. }
                    )
            })
            .await?;
        anyhow::ensure!(
            matches!(response.message, Message::Ack { .. }),
            "request not accepted: {:?}",
            response.message
        );
        Ok(())
    }
    async fn completed(&mut self, request: &str) -> Result<RequestOutcome> {
        let response = self.wait(|e| matches!(&e.message, Message::ResponseComplete { request_id, .. } if request_id.as_str() == request)).await?;
        match response.message {
            Message::ResponseComplete { outcome, .. } => Ok(outcome),
            _ => unreachable!(),
        }
    }
}

struct WorkerControl {
    reader: BufReader<tokio::io::ReadHalf<ServerStream>>,
    writer: tokio::io::WriteHalf<ServerStream>,
    started: Vec<String>,
}
impl WorkerControl {
    async fn next(&mut self) -> Result<Value> {
        let mut line = String::new();
        timeout(Duration::from_secs(20), self.reader.read_line(&mut line)).await??;
        let event: Value =
            serde_json::from_str(&line).context("test worker closed control channel")?;
        if event["type"] == "started" {
            self.started.push(
                event["requestId"]
                    .as_str()
                    .context("worker request identity missing")?
                    .into(),
            );
        }
        Ok(event)
    }
    async fn started(&mut self, request: &str) -> Result<()> {
        loop {
            let event = self.next().await?;
            // The daemon may repeat abort for the old request while settling it.
            // It must never abort the next request or dispatch an unexpected one.
            if event["type"] == "aborted" && event["requestId"] != request {
                continue;
            }
            anyhow::ensure!(
                event["type"] == "started" && event["requestId"] == request,
                "unexpected worker invocation: {event}"
            );
            return Ok(());
        }
    }
    async fn command(&mut self, action: &str, request: &str) -> Result<()> {
        self.writer
            .write_all(format!("{}\n", json!({"action": action, "requestId": request})).as_bytes())
            .await?;
        Ok(())
    }
    async fn barrier(&mut self) -> Result<()> {
        self.command("barrier", "").await?;
        let event = self.next().await?;
        anyhow::ensure!(
            event["type"] == "barrier",
            "unexpected extra worker operation: {event}"
        );
        Ok(())
    }
}

struct Harness {
    root: PathBuf,
    child: Child,
    endpoint: Endpoint,
    address: EndpointAddr,
    listener: Listener,
    phone: Phone,
}
impl Harness {
    async fn new() -> Result<Self> {
        let root = std::env::temp_dir().join(format!("kcb-{}", Uuid::new_v4()));
        let state = root.join("state");
        let home = root.join("home");
        let worker = root.join("worker");
        std::fs::create_dir_all(&home)?;
        std::fs::create_dir_all(worker.join("node_modules"))?;
        std::fs::create_dir_all(worker.join("dist-worker/daemon/src"))?;
        std::fs::write(
            worker.join("package.json"),
            r#"{"scripts":{"worker":"bun dist-worker/daemon/src/worker.js"}}"#,
        )?;
        std::fs::write(
            worker.join("dist-worker/daemon/src/worker.js"),
            include_str!("fixtures/chat-worker.mjs"),
        )?;
        #[cfg(unix)]
        let control = root.join("worker.sock");
        #[cfg(windows)]
        let control = PathBuf::from(format!(r"\\.\pipe\kompi-worker-test-{}", Uuid::new_v4()));
        let listener = Listener::bind(&control)?;
        let endpoint = Endpoint::builder(presets::N0).bind().await?;
        let store = SqliteStore::open(&state).await?;
        store
            .upsert_device(&Device {
                endpoint: endpoint.id().to_string(),
                name: "Behavior test phone".into(),
                paired_at: now_ms(),
                last_seen: None,
                idle_timeout_seconds: None,
            })
            .await?;
        drop(store);
        let bun = std::env::split_paths(&std::env::var_os("PATH").context("PATH missing")?)
            .map(|path| path.join(format!("bun{}", std::env::consts::EXE_SUFFIX)))
            .find(|path| path.is_file())
            .context("Install Bun to run daemon behavior E2E")?
            .canonicalize()?;
        let mut child = Command::new(env!("CARGO_BIN_EXE_kompi"))
            .args(["--state-dir", state.to_str().unwrap(), "serve"])
            .env("HOME", &home)
            .env("USERPROFILE", &home)
            .env("LOCALAPPDATA", home.join("AppData/Local"))
            .env("APPDATA", home.join("AppData/Roaming"))
            .env("XDG_CONFIG_HOME", home.join(".config"))
            .env("PI_CODING_AGENT_DIR", home.join(".pi"))
            .env("KOMPI_SDK_WORKER_DIR", &worker)
            .env("KOMPI_SDK_RUNTIME", bun)
            .env("KOMPI_TEST_CONTROL_SOCKET", &control)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::piped())
            .kill_on_drop(true)
            .spawn()?;
        let mut logs = BufReader::new(child.stderr.take().unwrap()).lines();
        timeout(Duration::from_secs(20), async {
            while let Some(line) = logs.next_line().await? {
                if line.contains("protocol v4 ready") {
                    return Ok::<_, anyhow::Error>(());
                }
            }
            bail!("isolated daemon exited before readiness")
        })
        .await??;
        // Drain stderr for the child lifetime; fixtures never emit user/private content.
        tokio::spawn(async move { while let Ok(Some(_)) = logs.next_line().await {} });
        let mut socket = timeout(Duration::from_secs(5), async {
            loop {
                match local_ipc::connect(&control_socket_path(&state)).await {
                    Ok(stream) => return stream,
                    Err(_) => tokio::task::yield_now().await,
                }
            }
        })
        .await?;
        write_frame(
            &mut socket,
            &encode_cbor(&Envelope::new(
                id("pairing-ticket"),
                ControlMessage::PairingCreate {
                    name: Some("Behavior fixture".into()),
                },
            ))?,
        )
        .await?;
        let bytes = read_frame(&mut socket).await?.context("control closed")?;
        let response: Envelope<ControlMessage> = decode_cbor(&bytes)?;
        let qr = match response.message {
            ControlMessage::PairingResult { qr, .. } => qr,
            other => bail!("unexpected control response: {other:?}"),
        };
        let pairing = PairingPayload::parse_json(qr.as_bytes())?;
        let ticket: EndpointTicket = pairing.endpoint_ticket.parse()?;
        let address = ticket.endpoint_addr().clone();
        let phone = Phone::connect(&endpoint, address.clone()).await?;
        Ok(Self {
            root,
            child,
            endpoint,
            address,
            listener,
            phone,
        })
    }
    async fn worker(&mut self) -> Result<WorkerControl> {
        let (stream, _) = timeout(Duration::from_secs(20), self.listener.accept()).await??;
        let (reader, writer) = tokio::io::split(stream);
        Ok(WorkerControl {
            reader: BufReader::new(reader),
            writer,
            started: vec![],
        })
    }
    async fn reconnect(&mut self) -> Result<()> {
        self.phone.connection.close(0u32.into(), b"test reconnect");
        self.phone = Phone::connect(&self.endpoint, self.address.clone()).await?;
        self.phone
            .send("reconnect-sync", Message::Sync { after: None })
            .await?;
        self.phone
            .wait(|e| {
                e.id.as_str() == "reconnect-sync"
                    && matches!(e.message, Message::SyncSnapshot { .. })
            })
            .await?;
        Ok(())
    }
    async fn stop(&mut self) -> Result<()> {
        // Signal only this isolated child, never a process group or a workstation service.
        #[cfg(windows)]
        self.child.start_kill()?;
        #[cfg(unix)]
        if let Some(pid) = self.child.id() {
            unsafe {
                libc::kill(pid as i32, libc::SIGTERM);
            }
        }
        timeout(Duration::from_secs(10), self.child.wait()).await??;
        self.endpoint.close().await;
        Ok(())
    }
}
impl Drop for Harness {
    fn drop(&mut self) {
        let _ = self.child.start_kill();
        let _ = std::fs::remove_dir_all(&self.root);
    }
}

#[tokio::test]
async fn discovery_is_explicit_and_does_not_reopen_phone_session() -> Result<()> {
    let mut h = Harness::new().await?;
    let stages = h.root.join("home").join("worker-stages");
    let cold = std::time::Instant::now();
    h.phone.send("cold", prompt("Fixture question")).await?;
    h.phone.accepted("cold").await?;
    let mut w = h.worker().await?;
    w.started("cold").await?;
    eprintln!("fixture cold dispatch: {}ms", cold.elapsed().as_millis());
    w.command("release", "cold").await?;
    h.phone.completed("cold").await?;
    h.reconnect().await?;
    let warm = std::time::Instant::now();
    h.phone.send("idle", prompt("Next fixture question")).await?;
    h.phone.accepted("idle").await?;
    w.started("idle").await?;
    eprintln!("fixture idle dispatch: {}ms", warm.elapsed().as_millis());
    w.command("release", "idle").await?;
    h.phone.completed("idle").await?;
    let counts = || std::fs::read_to_string(&stages).unwrap();
    assert_eq!(counts().lines().filter(|s| *s == "models-get").count(), 0);
    assert_eq!(counts().lines().filter(|s| *s == "create" || *s == "resume").count(), 1);
    for id in ["selector", "overlap"] { h.phone.send(id, Message::ModelsGet {}).await?; }
    for id in ["selector", "overlap"] {
        h.phone.wait(|e| e.id.as_str() == id && matches!(e.message, Message::ModelsResult { .. })).await?;
    }
    h.phone.send("refresh", Message::ModelsGet {}).await?;
    h.phone.wait(|e| e.id.as_str() == "refresh" && matches!(e.message, Message::ModelsResult { .. })).await?;
    assert_eq!(counts().lines().filter(|s| *s == "models-get").count(), 2);
    h.stop().await
}

#[tokio::test]
async fn q2_q4_x3_fifo_cancel_middle_and_late_cancel_cannot_stop_next_request() -> Result<()> {
    timeout(Duration::from_secs(60), async {
        let mut h = Harness::new().await?;
        h.phone.send("a", prompt("First instruction")).await?;
        h.phone.accepted("a").await?;
        let mut worker = h.worker().await?;
        worker.started("a").await?;
        h.phone
            .send("b", prompt("Cancelled queued instruction"))
            .await?;
        h.phone.accepted("b").await?;
        h.phone.send("c", prompt("Last instruction")).await?;
        h.phone.accepted("c").await?;
        h.phone
            .send("cancel-b", Message::Cancel { target: id("b") })
            .await?;
        h.phone.wait(|e| e.id.as_str() == "cancel-b").await?;
        worker.command("release", "a").await?;
        assert!(matches!(
            h.phone.completed("a").await?,
            RequestOutcome::Completed
        ));
        assert!(matches!(
            h.phone.completed("b").await?,
            RequestOutcome::Cancelled
        ));
        worker.started("c").await?;
        h.phone
            .send("late-cancel-a", Message::Cancel { target: id("a") })
            .await?;
        h.phone.wait(|e| e.id.as_str() == "late-cancel-a").await?;
        worker.command("release", "c").await?;
        assert!(matches!(
            h.phone.completed("c").await?,
            RequestOutcome::Completed
        ));
        worker.barrier().await?;
        assert_eq!(worker.started, ["a", "c"]);
        h.stop().await
    })
    .await?
}

#[tokio::test]
async fn r1_r3_reconnect_and_completed_retry_execute_original_request_only_once() -> Result<()> {
    timeout(Duration::from_secs(60), async {
        let mut h = Harness::new().await?;
        h.phone
            .send("same", prompt("Immutable instruction 🎧"))
            .await?;
        h.phone.accepted("same").await?;
        let mut worker = h.worker().await?;
        worker.started("same").await?;
        h.reconnect().await?;
        h.phone
            .send("same", prompt("Immutable instruction 🎧"))
            .await?;
        h.phone.accepted("same").await?;
        worker.command("release", "same").await?;
        // The execution began on the old control stream. Its durable outcome,
        // not a direct response to that retired stream, completes recovery.
        h.phone.wait(|e| match &e.message {
            Message::SyncEvents { events, .. } => events.iter().any(|event| matches!(&event.event,
                DurableEvent::ResponseCompleted { request_id, outcome: RequestOutcome::Completed, .. } if request_id.as_str() == "same")),
            _ => false,
        }).await.context("recover completion through the new connection's journal subscription")?;
        h.phone
            .send("same", prompt("Immutable instruction 🎧"))
            .await?;
        // A settled retry returns its completion directly, not another acceptance.
        assert!(matches!(
            h.phone.completed("same").await?,
            RequestOutcome::Completed
        ));
        worker.barrier().await?;
        assert_eq!(worker.started, ["same"]);
        h.stop().await
    })
    .await?
}

#[tokio::test]
async fn reopen_recovers_reply_on_primary_with_and_without_competing_scoped_stream() -> Result<()> {
    let mut h = Harness::new().await?;
    let prompt = prompt("Synthetic question");
    let Message::ConversationPrompt { conversation_id, .. } = prompt else { unreachable!() };
    h.phone.send("one", prompt).await?;
    h.phone.accepted("one").await?;
    let mut worker = h.worker().await?;
    worker.started("one").await?;
    h.reconnect().await?;
    worker.command("release", "one").await?;
    h.phone.wait(|e| matches!(&e.message, Message::SyncEvents {events, ..} if events.iter().any(|event|
        matches!(event.event, DurableEvent::ResponseCompleted {outcome:RequestOutcome::Completed,..})))).await?;

    let mut chat = None;
    for request in ["primary-alone", "primary-with-scoped", "scoped"] {
        if request == "primary-with-scoped" {
            let (send, recv) = h.phone.connection.open_bi().await?;
            let mut route = Phone { connection:h.phone.connection.clone(), send, recv, inbox: vec![] };
            route.send("open", Message::ConversationStreamOpen { conversation_id }).await?;
            route.accepted("open").await?;
            chat = Some(route);
        }
        let phone = if request == "scoped" { chat.as_mut().unwrap() } else { &mut h.phone };
        phone.send(request, Message::PiConversationSnapshotGet { conversation_id }).await?;
        let response = phone.wait(|e| matches!(&e.message, Message::PiFrame {frame} if frame.request_id.as_ref().is_some_and(|id| id.as_str() == request))).await?;
        let Message::PiFrame {frame} = response.message else { unreachable!() };
        assert_eq!(frame.conversation_id, conversation_id);
        assert!(frame.last);
        let transcript: Value = serde_json::from_str(&frame.json)?;
        assert_eq!(transcript["entries"][0]["message"]["content"][0]["text"], "Fixture complete.");
    }
    worker.barrier().await?;
    assert_eq!(worker.started, ["one"]);
    h.stop().await
}

#[tokio::test]
async fn r1_conflicting_retry_is_rejected_without_executing_replacement_text() -> Result<()> {
    timeout(Duration::from_secs(60), async {
        let mut h = Harness::new().await?;
        h.phone.send("same", prompt("Original instruction")).await?;
        h.phone.accepted("same").await?;
        let mut worker = h.worker().await?;
        worker.started("same").await?;
        h.phone.send("same", prompt("Changed instruction")).await?;
        let conflict = h
            .phone
            .wait(|e| {
                e.id.as_str() == "same"
                    && matches!(
                        e.message,
                        Message::Error { .. } | Message::PreacceptanceRejected { .. }
                    )
            })
            .await?;
        assert!(matches!(
            conflict.message,
            Message::Error { .. } | Message::PreacceptanceRejected { .. }
        ));
        worker.command("release", "same").await?;
        assert!(matches!(
            h.phone.completed("same").await?,
            RequestOutcome::Completed
        ));
        worker.barrier().await?;
        assert_eq!(worker.started, ["same"]);
        h.stop().await
    })
    .await?
}

#[tokio::test]
async fn x3_cancel_active_then_submit_new_work_does_not_leak_stop_to_next_request() -> Result<()> {
    timeout(Duration::from_secs(60), async {
        let mut h = Harness::new().await?;
        h.phone
            .send("a", prompt("Interrupt this instruction"))
            .await?;
        h.phone.accepted("a").await?;
        let mut worker = h.worker().await?;
        worker.started("a").await?;
        h.phone
            .send("cancel-a", Message::Cancel { target: id("a") })
            .await?;
        h.phone.wait(|e| e.id.as_str() == "cancel-a").await?;
        let aborted = worker.next().await?;
        anyhow::ensure!(
            aborted["type"] == "aborted" && aborted["requestId"] == "a",
            "wrong abort target: {aborted}"
        );
        assert!(matches!(
            h.phone.completed("a").await?,
            RequestOutcome::Cancelled
        ));
        h.phone
            .send("b", prompt("New instruction after Stop"))
            .await?;
        h.phone.accepted("b").await?;
        worker.started("b").await?;
        worker.command("release", "b").await?;
        assert!(matches!(
            h.phone.completed("b").await?,
            RequestOutcome::Completed
        ));
        worker.barrier().await?;
        assert_eq!(worker.started, ["a", "b"]);
        h.stop().await
    })
    .await?
}

#[tokio::test]
async fn q6_worker_crash_marks_active_uncertain_and_fails_unstarted_queue() -> Result<()> {
    timeout(Duration::from_secs(60), async {
        let mut h = Harness::new().await?;
        h.phone
            .send("a", prompt("Work interrupted by crash"))
            .await?;
        h.phone.accepted("a").await?;
        let mut worker = h.worker().await?;
        worker.started("a").await?;
        h.phone
            .send("b", prompt("Must not move to fresh context"))
            .await?;
        h.phone.accepted("b").await?;
        worker.command("crash", "").await?;
        let active_outcome = h.phone.completed("a").await?;
        let queued_outcome = h.phone.completed("b").await?;
        assert!(
            matches!(active_outcome, RequestOutcome::Uncertain),
            "In-flight crash must remain uncertain, got {active_outcome:?}"
        );
        assert!(
            matches!(queued_outcome, RequestOutcome::Failed),
            "Unstarted work must fail explicitly, got {queued_outcome:?}"
        );
        assert_eq!(worker.started, ["a"]);
        h.stop().await
    })
    .await?
}

#[tokio::test]
async fn r1_r4_completed_retry_replays_ledger_without_second_worker_invocation() -> Result<()> {
    timeout(Duration::from_secs(60), async {
        let mut h = Harness::new().await?;
        h.phone.send("same", prompt("Execute exactly once")).await?;
        h.phone.accepted("same").await?;
        let mut worker = h.worker().await?;
        worker.started("same").await?;
        worker.command("release", "same").await?;
        assert!(matches!(
            h.phone.completed("same").await?,
            RequestOutcome::Completed
        ));
        h.phone.send("same", prompt("Execute exactly once")).await?;
        assert!(matches!(
            h.phone.completed("same").await?,
            RequestOutcome::Completed
        ));
        // Same-stream Pong proves the daemon has handled the replay request.
        h.phone.send("after-replay", Message::Ping {}).await?;
        h.phone
            .wait(|e| e.id.as_str() == "after-replay" && matches!(e.message, Message::Pong {}))
            .await?;
        worker.barrier().await?;
        assert_eq!(worker.started, ["same"]);
        h.stop().await
    })
    .await?
}
