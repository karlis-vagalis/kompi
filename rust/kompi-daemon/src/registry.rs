use anyhow::bail;
use std::{
    collections::HashMap,
    sync::{
        Arc, Mutex,
        atomic::{AtomicU64, Ordering},
    },
};

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct EndpointId(String);

impl TryFrom<String> for EndpointId {
    type Error = anyhow::Error;
    fn try_from(value: String) -> Result<Self, Self::Error> {
        if value.len() != 64
            || !value
                .bytes()
                .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
        {
            bail!("invalid cryptographic endpoint id");
        }
        Ok(Self(value))
    }
}
impl EndpointId {
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Default)]
struct RegistryState {
    held: HashMap<EndpointId, u64>,
}

/// Atomic single-connection ownership keyed only by Iroh's authenticated EndpointId.
/// The monotonic generation prevents a stale lease's Drop from releasing a later reservation.
#[derive(Clone, Default)]
pub struct PeerRegistry {
    state: Arc<Mutex<RegistryState>>,
    next_generation: Arc<AtomicU64>,
}

pub struct PeerLease {
    registry: Arc<Mutex<RegistryState>>,
    endpoint: EndpointId,
    generation: u64,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AlreadyConnected;

impl PeerRegistry {
    pub fn reserve(&self, endpoint: EndpointId) -> Result<PeerLease, AlreadyConnected> {
        let mut state = self.state.lock().expect("peer registry poisoned");
        if state.held.contains_key(&endpoint) {
            return Err(AlreadyConnected);
        }
        let generation = self
            .next_generation
            .fetch_add(1, Ordering::Relaxed)
            .wrapping_add(1);
        state.held.insert(endpoint.clone(), generation);
        Ok(PeerLease {
            registry: self.state.clone(),
            endpoint,
            generation,
        })
    }
    /// Replace ownership after the caller has authenticated the peer identity and
    /// verified its paired-device authorization. The old lease is fenced by generation.
    pub fn replace_authorized(&self, endpoint: EndpointId) -> PeerLease {
        let mut state = self.state.lock().expect("peer registry poisoned");
        let generation = self
            .next_generation
            .fetch_add(1, Ordering::Relaxed)
            .wrapping_add(1);
        state.held.insert(endpoint.clone(), generation);
        PeerLease {
            registry: self.state.clone(),
            endpoint,
            generation,
        }
    }
    pub fn connected_endpoints(&self) -> Vec<String> {
        self.state
            .lock()
            .expect("peer registry poisoned")
            .held
            .keys()
            .map(|key| key.0.clone())
            .collect()
    }
}
impl Drop for PeerLease {
    fn drop(&mut self) {
        let mut state = self.registry.lock().expect("peer registry poisoned");
        if state.held.get(&self.endpoint) == Some(&self.generation) {
            state.held.remove(&self.endpoint);
        }
    }
}
