//! Optional authenticated secondary channel for one conversation's live traffic.
use super::*;
use crate::lifecycle::ReadySender;
use kompi_protocol::{AckState, ConversationId, PiFrameKind};
use tokio::sync::{OwnedSemaphorePermit, Semaphore};

impl Daemon {
    pub(super) async fn serve_chat_stream(
        &self,
        mut send: SendStream,
        mut recv: RecvStream,
        endpoint: &str,
        generation: u64,
        request: RequestId,
        conversation: ConversationId,
        routes: ChatStreamRoutes,
        handshake: OwnedSemaphorePermit,
        attachment_capable: bool,
        reply_notification_capable: bool,
    ) -> Result<()> {
        anyhow::ensure!(
            self.is_current_connection(endpoint, generation),
            "stale chat stream connection"
        );
        let Some(route) = ChatStreamRoute::claim(routes, conversation) else {
            let response = activation_error(
                conversation,
                "chat-stream-unavailable",
                "This conversation already has a stream or the connection has reached its chat stream limit.",
            );
            return timeout(HANDSHAKE_TIMEOUT, async move {
                write_frame(
                    &mut send,
                    &kompi_protocol::encode_cbor(&Envelope::new(request, response))?,
                )
                .await?;
                send.finish()?;
                Ok(())
            })
            .await?;
        };
        send.set_priority(INTERACTIVE_STREAM_PRIORITY)?;
        let (_publisher, mut publications) = self.worker.pi_frames.conversation(endpoint, conversation);
        let sender = ReadySender::new(send);
        sender
            .send(&Envelope::new(
                request,
                Message::Ack {
                    configuration: None,
                    state: AckState::Accepted,
                    session_id: None,
                    conversation_id: Some(conversation),
                    idle_timeout_seconds: None,
                },
            ))
            .await?;
        drop(handshake);

        // Scope before buffering: A cannot advance or overrun B's projection queue.
        // Do not abort a sender that could be halfway through a frame; reset first.
        let barrier = Arc::new(Mutex::new(ProjectionBarrier::NeedsSnapshot));
        let projection_barrier = barrier.clone();
        let projection_daemon = self.clone();
        let peer = endpoint.to_owned();
        let projection_sender = sender.clone();
        let mut projection = tokio::spawn(async move {
            let mut writes = PiWriteWindow::default();
            while let Some(mut published) = publications.recv().await {
                if !projection_daemon.is_current_connection(&peer, generation) {
                    break;
                }
                if !projection_daemon
                    .worker
                    .is_current_origin_for(&peer, conversation, published.origin)
                    .await
                {
                    continue;
                }
                let route = projection_barrier
                    .lock()
                    .expect("chat projection poisoned")
                    .route(&mut published);
                match route {
                    ProjectionRoute::Drop => continue,
                    ProjectionRoute::Reset => break,
                    ProjectionRoute::Send => {
                        if writes
                            .send(&projection_sender, published.frame)
                            .await
                            .is_err()
                        {
                            break;
                        }
                    }
                }
            }
        });
        let prompts = Arc::new(Semaphore::new(MAX_PROMPT_HANDLERS_PER_CHAT));
        let projections = Arc::new(Semaphore::new(4));
        let lifecycle = Arc::new(Semaphore::new(4));
        let result = loop {
            // A stalled upload of a frame never prevents reading another stream.
            let raw = tokio::select! {
                _ = &mut projection => break Ok(()),
                result = read_frame(&mut recv) => match result {
                    Ok(Some(raw)) => raw,
                    Ok(None) => break Ok(()),
                    Err(err) => break Err(err),
                },
            };
            let receipt = std::time::Instant::now();
            let wall_ms = now_ms();
            if !self.is_current_connection(endpoint, generation) {
                break Ok(());
            }
            let envelope: Envelope<Message> = match kompi_protocol::decode_cbor(&raw) {
                Ok(envelope) => envelope,
                Err(err) => break Err(err.into()),
            };
            let request = envelope.id;
            let Some(command) = ChatCommand::parse(envelope.message, conversation) else {
                let response = activation_error(
                    conversation,
                    "chat-stream-scope",
                    "This message is not allowed on this conversation stream.",
                );
                if sender
                    .send(&Envelope::new(request, response))
                    .await
                    .is_err()
                {
                    break Ok(());
                }
                continue;
            };
            let (local, shared) = match command {
                ChatCommand::Prompt { .. } => (&prompts, &route.routes.commands),
                ChatCommand::Snapshot | ChatCommand::Activate => {
                    (&projections, &route.routes.commands)
                }
                ChatCommand::Close | ChatCommand::Cancel(_) => {
                    (&lifecycle, &route.routes.lifecycle)
                }
            };
            let (Ok(permit), Ok(connection_permit)) = (
                local.clone().try_acquire_owned(),
                shared.clone().try_acquire_owned(),
            ) else {
                let response = if matches!(command, ChatCommand::Prompt { .. }) {
                    Message::PreacceptanceRejected {
                        request_id: request.clone(),
                        code: "chat-stream-busy".into(),
                        message: "This conversation stream is busy; retry shortly.".into(),
                        retryable: true,
                    }
                } else {
                    activation_error(
                        conversation,
                        "chat-stream-busy",
                        "This conversation stream is busy; retry shortly.",
                    )
                };
                if sender
                    .send(&Envelope::new(request, response))
                    .await
                    .is_err()
                {
                    break Ok(());
                }
                continue;
            };
            let snapshot_token = if matches!(command, ChatCommand::Snapshot) {
                let token = RequestId::parse(format!("chat-snapshot-{}", uuid::Uuid::new_v4()))
                    .expect("generated snapshot token");
                let requested = barrier
                    .lock()
                    .expect("chat projection poisoned")
                    .request(token.clone(), request.clone());
                if !requested {
                    let response = activation_error(
                        conversation,
                        "chat-snapshot-pending",
                        "Wait for the current snapshot before requesting another one.",
                    );
                    if sender
                        .send(&Envelope::new(request, response))
                        .await
                        .is_err()
                    {
                        break Ok(());
                    }
                    continue;
                }
                Some(token)
            } else {
                None
            };
            let daemon = self.clone();
            let peer = endpoint.to_owned();
            let sender = sender.clone();
            let barrier = barrier.clone();
            // Detach admitted requests from transport lifetime. SQLite and the
            // conversation owner decide their result even if this stream resets.
            tokio::spawn(async move {
                let _permits = (permit, connection_permit);
                let response = match command {
                    ChatCommand::Prompt { attempt, create, text, delivery, configuration, attachments } => {
                        let trace = attempt.and_then(|attempt| daemon.chat_timing.as_ref().map(|audit| {
                            let mut trace = audit.request(&peer, request.clone(), attempt);
                            trace.conversation = Some(conversation);
                            trace.record_at(Timing::DaemonReceipt, receipt, wall_ms);
                            trace
                        }));
                        if attachments.is_some() && !attachment_capable {
                            timing(&trace, Timing::DaemonRejected);
                            activation_error(conversation, "capability-required", "Client did not negotiate attachments.")
                        } else {
                            if let Err(error) = daemon.handle_prompt(sender, &peer, generation, request, text, delivery, configuration, attachments, Some(conversation), create, trace.clone(), reply_notification_capable).await {
                                timing(&trace, Timing::DaemonHandlerFailed);
                                eprintln!("Kompi SDK request failed: {}", safe_error(&error.to_string()));
                            }
                            return;
                        }
                    }
                    ChatCommand::Snapshot => {
                        let token = snapshot_token.expect("snapshot commands own a projection token");
                        if daemon.snapshot_conversation(&peer, generation, conversation, Some(token.clone())).await.is_ok() { return; }
                        if !barrier.lock().expect("chat projection poisoned").fail(&token) { return; }
                        snapshot_error(Some(conversation))
                    }
                    ChatCommand::Activate if request.as_str().starts_with("live-prewarm-") => {
                        drop(_permits);
                        daemon.prewarm_live(&peer, generation, conversation).await;
                        return;
                    }
                    ChatCommand::Activate => daemon.activate_conversation(&peer, generation, conversation).await
                        .unwrap_or_else(|_| activation_error(conversation, "conversation-unavailable", "Could not continue this conversation. It is still available to browse if its transcript can be loaded.")),
                    ChatCommand::Close => daemon.close_conversation(&peer, generation, conversation).await
                        .unwrap_or_else(|_| activation_error(conversation, "conversation-close-failed", "Could not close this conversation.")),
                    ChatCommand::Cancel(target) => daemon.cancel_prompt(&peer, generation, &target, Some(conversation)).await
                        .unwrap_or_else(|_| activation_error(conversation, "cancel-failed", "Could not stop this request.")),
                };
                let _ = sender.send(&Envelope::new(request, response)).await;
            });
        };
        sender.reset().await;
        projection.abort();
        let _ = recv.stop(2u32.into());
        result
    }
}

enum ProjectionBarrier {
    NeedsSnapshot,
    Pending {
        token: RequestId,
        client_request: RequestId,
    },
    Live(uuid::Uuid),
}
enum ProjectionRoute {
    Send,
    Drop,
    Reset,
}
impl ProjectionBarrier {
    fn request(&mut self, token: RequestId, client_request: RequestId) -> bool {
        if matches!(self, Self::Pending { .. }) {
            return false;
        }
        *self = Self::Pending {
            token,
            client_request,
        };
        true
    }
    fn fail(&mut self, id: &RequestId) -> bool {
        if matches!(self, Self::Pending { token, .. } if token == id) {
            *self = Self::NeedsSnapshot;
            return true;
        }
        false
    }
    fn route(&mut self, publication: &mut PiPublication) -> ProjectionRoute {
        let snapshot = matches!(publication.frame.kind, PiFrameKind::Snapshot);
        match self {
            Self::Pending {
                token,
                client_request,
            } if snapshot && publication.frame.request_id.as_ref() == Some(token) => {
                publication.frame.request_id = Some(client_request.clone());
                if publication.frame.last {
                    *self = Self::Live(publication.origin);
                }
                ProjectionRoute::Send
            }
            Self::Live(_) if snapshot => ProjectionRoute::Drop,
            Self::Live(origin) if *origin == publication.origin => ProjectionRoute::Send,
            Self::Live(_) => ProjectionRoute::Reset,
            _ => ProjectionRoute::Drop,
        }
    }
}

#[cfg(test)]
mod recovery_tests {
    use super::*;

    #[tokio::test]
    async fn targeted_snapshot_uses_requesting_route_and_large_scoped_snapshot_survives_backpressure() {
        let buses = PiPublishers::new();
        let mut primary = buses.control.subscribe();
        let conversation = ConversationId::from(uuid::Uuid::new_v4());
        let token = RequestId::parse("internal".to_owned()).unwrap();
        let request = RequestId::parse("client".to_owned()).unwrap();
        let mut publication = PiPublication {
            origin: uuid::Uuid::new_v4(), primary_generation: None,
            frame: kompi_protocol::PiFrame { conversation_id: conversation, session_id: "fixture".into(),
                epoch: "epoch".into(), sequence: 0, kind: PiFrameKind::Snapshot,
                part: 0, last: true, json: "{}".into(), request_id: Some(token.clone()) },
        };
        buses.primary.lock().unwrap().insert((conversation, token), (7, request.clone()));
        // A primary request works before a chat attaches and while it is attached.
        let assert_primary = |reply: PiPublication| {
            assert_eq!(reply.primary_generation, Some(7));
            assert_eq!(reply.frame.request_id.as_ref(), Some(&request));
        };
        buses.publish("peer", publication.clone()).await;
        assert_primary(primary.try_recv().unwrap());
        let (_lease, mut scoped) = buses.conversation("peer", conversation);
        let (_other, mut other) = buses.conversation("another-peer", conversation);
        buses.publish("peer", publication.clone()).await;
        assert_primary(primary.try_recv().unwrap());
        assert!(scoped.try_recv().is_err());

        publication.frame.request_id = Some(RequestId::parse("scoped".to_owned()).unwrap());
        let producer = tokio::spawn(async move {
            for part in 0..300 {
                publication.frame.part = part;
                publication.frame.last = part == 299;
                buses.publish("peer", publication.clone()).await;
            }
        });
        tokio::task::yield_now().await;
        for part in 0..300 {
            assert_eq!(scoped.recv().await.unwrap().frame.part, part);
        }
        producer.await.unwrap();
        assert!(other.try_recv().is_err());
        assert!(primary.try_recv().is_err());
    }
}

enum ChatCommand {
    Prompt {
        attempt: Option<ConversationId>,
        create: bool,
        text: String,
        delivery: kompi_protocol::Delivery,
        configuration: kompi_protocol::ChatModelConfiguration,
        attachments: Option<Vec<ConversationId>>,
    },
    Snapshot,
    Activate,
    Close,
    Cancel(RequestId),
}
impl ChatCommand {
    fn parse(message: Message, scope: ConversationId) -> Option<Self> {
        match message {
            Message::ConversationPrompt {
                conversation_id,
                attempt_id,
                create_if_missing,
                text,
                delivery,
                configuration,
                attachments,
            } if conversation_id == scope => Some(Self::Prompt {
                attempt: attempt_id,
                create: create_if_missing.unwrap_or(false),
                text,
                delivery,
                configuration,
                attachments,
            }),
            Message::PiConversationSnapshotGet { conversation_id } if conversation_id == scope => {
                Some(Self::Snapshot)
            }
            Message::ConversationActivate {
                conversation_id, ..
            } if conversation_id == scope => Some(Self::Activate),
            Message::ConversationClose { conversation_id } if conversation_id == scope => {
                Some(Self::Close)
            }
            Message::Cancel { target } => Some(Self::Cancel(target)),
            _ => None,
        }
    }
}
