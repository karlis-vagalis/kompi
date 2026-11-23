use anyhow::{Result, bail};
use kompi_protocol::{Envelope, Message, RequestId};
use iroh::endpoint::{Connection as IrohConnection, RecvStream, SendStream};
use std::{marker::PhantomData, sync::Arc};
use tokio::sync::Mutex;

use crate::{
    registry::PeerLease,
    wire::{read_frame, write_frame},
};

mod sealed {
    pub trait Sealed {}
}
pub trait ConnectionState: sealed::Sealed + Send + Sync + 'static {}
pub struct Unverified;
pub struct Identified;
pub struct Authorized;
pub struct Ready;
pub struct Closing;
impl sealed::Sealed for Unverified {}
impl sealed::Sealed for Identified {}
impl sealed::Sealed for Authorized {}
impl sealed::Sealed for Ready {}
impl sealed::Sealed for Closing {}
impl ConnectionState for Unverified {}
impl ConnectionState for Identified {}
impl ConnectionState for Authorized {}
impl ConnectionState for Ready {}
impl ConnectionState for Closing {}

pub trait Transition {
    type Next: ConnectionState;
}
impl Transition for Unverified {
    type Next = Identified;
}
impl Transition for Identified {
    type Next = Authorized;
}
impl Transition for Authorized {
    type Next = Ready;
}
impl Transition for Ready {
    type Next = Closing;
}

pub struct Connection<S: ConnectionState> {
    pub(crate) iroh: IrohConnection,
    pub(crate) endpoint_id: Option<String>,
    pub(crate) generation: u64,
    pub(crate) lease: Option<PeerLease>,
    send: Arc<Mutex<SendStream>>,
    recv: Option<RecvStream>,
    state: PhantomData<S>,
}

#[derive(Clone)]
pub struct ReadySender {
    send: Arc<Mutex<SendStream>>,
}
impl ReadySender {
    pub(crate) fn new(send: SendStream) -> Self {
        Self { send: Arc::new(Mutex::new(send)) }
    }
    pub(crate) async fn reset(&self) {
        let _ = self.send.lock().await.reset(2u32.into());
    }
    pub async fn send(&self, envelope: &Envelope<Message>) -> Result<()> {
        let bytes = kompi_protocol::encode_cbor(envelope)?;
        let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(10);
        let mut send = tokio::time::timeout_at(deadline, self.send.lock()).await?;
        match tokio::time::timeout_at(deadline, write_frame(&mut *send, &bytes)).await {
            Ok(result) => result,
            Err(_) => {
                // A timed-out write may have sent part of a frame. The next
                // writer must never append another frame to that prefix.
                let _ = send.reset(2u32.into());
                bail!("peer control write timed out")
            }
        }
    }
}

pub struct ValidPrompt {
    pub id: RequestId,
    pub text: String,
    pub delivery: kompi_protocol::Delivery,
    pub configuration: kompi_protocol::ChatModelConfiguration,
    pub attachments: Option<Vec<kompi_protocol::ConversationId>>,
}

impl Connection<Unverified> {
    pub fn new(
        iroh: IrohConnection,
        generation: u64,
        send: SendStream,
        recv: RecvStream,
        lease: PeerLease,
    ) -> Self {
        Self {
            iroh,
            endpoint_id: None,
            generation,
            lease: Some(lease),
            send: Arc::new(Mutex::new(send)),
            recv: Some(recv),
            state: PhantomData,
        }
    }
    pub fn identify(self, authenticated_endpoint: String) -> Result<Connection<Identified>> {
        if authenticated_endpoint.len() != 64
            || !authenticated_endpoint
                .bytes()
                .all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase())
        {
            bail!("invalid authenticated endpoint id");
        }
        Ok(self.transition(Some(authenticated_endpoint)))
    }
}
impl Connection<Identified> {
    pub fn authorize(self) -> Connection<Authorized> {
        let endpoint_id = self.endpoint_id.clone();
        self.transition(endpoint_id)
    }
    pub async fn receive(&mut self) -> Result<Vec<u8>> {
        read_frame(self.recv.as_mut().expect("only active connection receives"))
            .await?
            .ok_or_else(|| anyhow::anyhow!("peer closed the control stream"))
    }
    pub async fn send(&self, envelope: &Envelope<Message>) -> Result<()> {
        let bytes = kompi_protocol::encode_cbor(envelope)?;
        let mut send = self.send.lock().await;
        write_frame(&mut *send, &bytes).await
    }
    pub async fn send_error(&self, id: &RequestId, code: &str, message: &str) -> Result<()> {
        self.send(&Envelope::new(
            id.clone(),
            Message::Error {
                code: code.into(),
                message: message.into(),
                session_id: None,
                conversation_id: None,
            },
        ))
        .await
    }
}
impl Connection<Authorized> {
    pub fn become_ready(self) -> Connection<Ready> {
        let endpoint_id = self.endpoint_id.clone();
        self.transition(endpoint_id)
    }
}
impl Connection<Ready> {
    pub fn endpoint_id(&self) -> &str {
        self.endpoint_id
            .as_deref()
            .expect("Ready implies Identified")
    }
    pub fn generation(&self) -> u64 {
        self.generation
    }
    pub fn transport(&self) -> &IrohConnection {
        &self.iroh
    }
    pub fn sender(&self) -> ReadySender {
        ReadySender {
            send: self.send.clone(),
        }
    }
    pub(crate) fn take_input(&mut self) -> RecvStream {
        self.recv.take().expect("ready connection input starts only once")
    }
    pub async fn send(&self, envelope: &Envelope<Message>) -> Result<()> {
        self.sender().send(envelope).await
    }
    pub async fn prompt(&self, envelope: Envelope<Message>) -> Result<ValidPrompt> {
        let Envelope { id, message, .. } = envelope;
        match message {
            Message::Prompt { text, delivery, configuration, attachments } => Ok(ValidPrompt { id, text, delivery, configuration, attachments }),
            _ => bail!("message is not a prompt"),
        }
    }
    pub async fn closing(self) -> Connection<Closing> {
        let endpoint_id = self.endpoint_id.clone();
        self.transition(endpoint_id)
    }
}
impl<S: ConnectionState> Connection<S> {
    fn transition<T: ConnectionState>(self, endpoint_id: Option<String>) -> Connection<T> {
        Connection {
            iroh: self.iroh,
            endpoint_id,
            generation: self.generation,
            lease: self.lease,
            send: self.send,
            recv: self.recv,
            state: PhantomData,
        }
    }
}
