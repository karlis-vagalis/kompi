//! One owner per conversation. Connection and transcript authority live outside
//! this actor; runtime generations and request membership are local to its lane.
use std::{collections::HashMap, time::Duration};

use kompi_protocol::RequestId;
use tokio::{sync::{mpsc, oneshot}, time::Instant};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct RuntimeGeneration(pub u64);

enum Disposition { Pending, Cancelled }
struct Opening;
struct Ready;
struct Session<S> {
    generation: RuntimeGeneration,
    lease: IdleLease,
    requests: HashMap<RequestId, Disposition>,
    _state: S,
}

impl Session<Opening> {
    fn ready(self) -> Session<Ready> {
        Session { generation: self.generation, lease: self.lease, requests: self.requests, _state: Ready }
    }
}

impl<S> Session<S> {
    fn accept(&mut self, id: RequestId, lease: IdleLease) {
        self.lease = lease;
        self.requests.insert(id, Disposition::Pending);
    }
}

#[derive(Clone, Copy)]
struct IdleLease { touched_at: Instant, ttl: Duration }

impl IdleLease {
    fn new(ttl: Duration) -> Self { Self { touched_at: Instant::now(), ttl } }
    fn due(self, now: Instant) -> bool { now.saturating_duration_since(self.touched_at) >= self.ttl }
}

enum Runtime {
    Dormant,
    Opening(Session<Opening>),
    Ready(Session<Ready>),
}

impl Runtime {
    fn requests(&mut self) -> Option<&mut HashMap<RequestId, Disposition>> {
        match self {
            Self::Opening(session) => Some(&mut session.requests),
            Self::Ready(session) => Some(&mut session.requests),
            Self::Dormant => None,
        }
    }

    fn can_close(&self) -> bool {
        match self {
            Self::Dormant => true,
            Self::Opening(_) => false,
            Self::Ready(session) => session.requests.is_empty(),
        }
    }
}

type Reply<T> = oneshot::Sender<T>;
type OwnerResult<T> = Result<T, oneshot::error::RecvError>;

enum Command {
    Accept { id: RequestId, lease: IdleLease, reply: Reply<RuntimeGeneration> },
    SetIdle { lease: IdleLease, reply: Reply<bool> },
    SetTtl { ttl: Duration, reply: Reply<()> },
    Opened { generation: RuntimeGeneration, reply: Reply<bool> },
    Retire { generation: Option<RuntimeGeneration>, reply: Reply<()> },
    Cancel { id: RequestId, reply: Reply<bool> },
    IsCancelled { id: RequestId, reply: Reply<bool> },
    Current { generation: RuntimeGeneration, reply: Reply<bool> },
    HasWork { id: RequestId, generation: RuntimeGeneration, reply: Reply<bool> },
    Finished { id: RequestId, generation: RuntimeGeneration, reply: Reply<()> },
    Expire { now: Instant, restored_due: bool, reply: Reply<bool> },
    Close { reply: Reply<bool> },
}

pub struct PhoneSessionOwner { commands: mpsc::Sender<Command> }

impl PhoneSessionOwner {
    pub fn spawn() -> Self {
        let (commands, receiver) = mpsc::channel(64);
        tokio::spawn(run_owner(receiver));
        Self { commands }
    }

    async fn ask<T>(&self, build: impl FnOnce(Reply<T>) -> Command) -> OwnerResult<T> {
        let (reply, response) = oneshot::channel();
        let _ = self.commands.send(build(reply)).await;
        response.await
    }

    pub async fn accept(&self, id: RequestId, idle_ttl: Duration) -> OwnerResult<RuntimeGeneration> {
        self.ask(|reply| Command::Accept { id, lease: IdleLease::new(idle_ttl), reply }).await
    }
    pub async fn set_idle(&self, ttl: Duration) -> OwnerResult<bool> {
        self.ask(|reply| Command::SetIdle { lease: IdleLease::new(ttl), reply }).await
    }
    pub async fn set_ttl(&self, ttl: Duration) -> OwnerResult<()> {
        self.ask(|reply| Command::SetTtl { ttl, reply }).await
    }
    pub async fn runtime_opened(&self, generation: RuntimeGeneration) -> OwnerResult<bool> {
        self.ask(|reply| Command::Opened { generation, reply }).await
    }
    pub async fn runtime_failed(&self, generation: RuntimeGeneration) -> OwnerResult<()> {
        self.ask(|reply| Command::Retire { generation: Some(generation), reply }).await
    }
    pub async fn revoke(&self) -> OwnerResult<()> {
        self.ask(|reply| Command::Retire { generation: None, reply }).await
    }
    pub async fn cancel_request(&self, id: RequestId) -> OwnerResult<bool> {
        self.ask(|reply| Command::Cancel { id, reply }).await
    }
    pub async fn is_cancelled(&self, id: RequestId) -> OwnerResult<bool> {
        self.ask(|reply| Command::IsCancelled { id, reply }).await
    }
    pub async fn is_current_runtime(&self, generation: RuntimeGeneration) -> OwnerResult<bool> {
        self.ask(|reply| Command::Current { generation, reply }).await
    }
    pub async fn has_work(&self, id: RequestId, generation: RuntimeGeneration) -> OwnerResult<bool> {
        self.ask(|reply| Command::HasWork { id, generation, reply }).await
    }
    pub async fn work_finished(&self, id: RequestId, generation: RuntimeGeneration) -> OwnerResult<()> {
        self.ask(|reply| Command::Finished { id, generation, reply }).await
    }
    pub async fn expire_if_idle(&self, now: Instant, restored_due: bool) -> OwnerResult<bool> {
        self.ask(|reply| Command::Expire { now, restored_due, reply }).await
    }
    pub async fn close_if_idle(&self) -> OwnerResult<bool> {
        self.ask(|reply| Command::Close { reply }).await
    }
}

async fn run_owner(mut receiver: mpsc::Receiver<Command>) {
    let mut runtime = Runtime::Dormant;
    let mut generation = RuntimeGeneration(0);
    while let Some(command) = receiver.recv().await {
        match command {
            Command::Accept { id, lease, reply } => {
                match &mut runtime {
                    Runtime::Opening(session) => session.accept(id, lease),
                    Runtime::Ready(session) => session.accept(id, lease),
                    Runtime::Dormant => {
                        generation.0 = generation.0.wrapping_add(1);
                        runtime = Runtime::Opening(Session {
                            generation, lease, requests: HashMap::from([(id, Disposition::Pending)]), _state: Opening,
                        });
                    }
                }
                let _ = reply.send(generation);
            }
            Command::SetIdle { lease, reply } => {
                let allowed = runtime.can_close();
                if allowed {
                    generation.0 = generation.0.wrapping_add(1);
                    runtime = Runtime::Ready(Session { generation, lease, requests: HashMap::new(), _state: Ready });
                }
                let _ = reply.send(allowed);
            }
            Command::SetTtl { ttl, reply } => {
                match &mut runtime {
                    Runtime::Opening(session) => session.lease.ttl = ttl,
                    Runtime::Ready(session) => session.lease.ttl = ttl,
                    Runtime::Dormant => {},
                }
                let _ = reply.send(());
            }
            Command::Opened { generation: expected, reply } => {
                let current = generation == expected && !matches!(runtime, Runtime::Dormant);
                if current {
                    runtime = match runtime {
                        Runtime::Opening(session) => Runtime::Ready(session.ready()),
                        other => other,
                    };
                }
                let _ = reply.send(current);
            }
            Command::Retire { generation: expected, reply } => {
                if expected.is_none_or(|value| value == generation) {
                    generation.0 = generation.0.wrapping_add(1);
                    runtime = Runtime::Dormant;
                }
                let _ = reply.send(());
            }
            Command::Cancel { id, reply } => {
                let entry = runtime.requests().and_then(|book| book.get_mut(&id));
                let accepted = match entry {
                    Some(status @ Disposition::Pending) => { *status = Disposition::Cancelled; true }
                    _ => false,
                };
                let _ = reply.send(accepted);
            }
            Command::IsCancelled { id, reply } => {
                let _ = reply.send(matches!(runtime.requests().and_then(|book| book.get(&id)), Some(Disposition::Cancelled)));
            }
            Command::Current { generation: expected, reply } => {
                let _ = reply.send(expected == generation && !matches!(runtime, Runtime::Dormant));
            }
            Command::HasWork { id, generation: expected, reply } => {
                // An abort still owns its final worker event; cancellation does not
                // make that event stale or turn it into a worker-loss outcome.
                let current = matches!(&runtime, Runtime::Ready(session) if session.generation == expected && session.requests.contains_key(&id));
                let _ = reply.send(current);
            }
            Command::Finished { id, generation: expected, reply } => {
                if expected == generation {
                    if let Some(book) = runtime.requests() { book.remove(&id); }
                    if matches!(&runtime, Runtime::Opening(session) if session.requests.is_empty()) { runtime = Runtime::Dormant; }
                }
                let _ = reply.send(());
            }
            Command::Expire { now, restored_due, reply } => {
                let expired = match &runtime {
                    Runtime::Ready(session) => session.requests.is_empty() && session.lease.due(now),
                    Runtime::Dormant => restored_due,
                    Runtime::Opening(_) => false,
                };
                // Retire only after persistence/disposal succeed (see Close).
                let _ = reply.send(expired);
            }
            Command::Close { reply } => {
                let allowed = runtime.can_close();
                if allowed { runtime = Runtime::Dormant; }
                let _ = reply.send(allowed);
            }
        }
    }
}
