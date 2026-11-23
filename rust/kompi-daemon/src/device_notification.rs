use kompi_protocol::{
    DeviceNotificationReceipt, DeviceNotificationStatus, RequestId, control::ControlMessage,
};
use std::{collections::HashMap, time::Instant};
use tokio::sync::oneshot;

pub fn offline_result(endpoint: String) -> ControlMessage {
    ControlMessage::DeviceNotificationResult {
        endpoint,
        status: DeviceNotificationStatus::Offline,
        code: None,
        message: Some("The paired phone is offline; no request was dispatched.".into()),
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct NotificationReply {
    pub status: DeviceNotificationReceipt,
    pub code: Option<String>,
    pub message: Option<String>,
}

struct Pending {
    generation: u64,
    expires_at: Instant,
    reply: oneshot::Sender<NotificationReply>,
}

/// One-shot receipts for conversation-free messages sent over authenticated phone connections.
/// The registry is memory-only: a reconnect never replays an expired or unanswered notification.
#[derive(Default)]
pub struct PendingDeviceNotifications {
    entries: HashMap<(String, RequestId), Pending>,
}

impl PendingDeviceNotifications {
    pub fn register(
        &mut self,
        endpoint: String,
        request_id: RequestId,
        generation: u64,
        expires_at: Instant,
        reply: oneshot::Sender<NotificationReply>,
    ) -> bool {
        let key = (endpoint, request_id);
        if self.entries.contains_key(&key) {
            return false;
        }
        self.entries.insert(
            key,
            Pending {
                generation,
                expires_at,
                reply,
            },
        );
        true
    }

    pub fn resolve(
        &mut self,
        endpoint: &str,
        request_id: &RequestId,
        generation: u64,
        now: Instant,
    ) -> Option<oneshot::Sender<NotificationReply>> {
        let key = (endpoint.to_owned(), request_id.clone());
        let expired = self
            .entries
            .get(&key)
            .is_some_and(|pending| now >= pending.expires_at);
        if expired {
            self.entries.remove(&key);
            return None;
        }
        if self
            .entries
            .get(&key)
            .is_some_and(|pending| pending.generation == generation)
        {
            return self.entries.remove(&key).map(|pending| pending.reply);
        }
        None
    }

    pub fn remove(&mut self, endpoint: &str, request_id: &RequestId, generation: u64) {
        let key = (endpoint.to_owned(), request_id.clone());
        if self
            .entries
            .get(&key)
            .is_some_and(|pending| pending.generation == generation)
        {
            self.entries.remove(&key);
        }
    }

    pub fn disconnect(
        &mut self,
        endpoint: &str,
        generation: u64,
    ) -> Vec<oneshot::Sender<NotificationReply>> {
        let keys = self
            .entries
            .iter()
            .filter(|((peer, _), pending)| peer == endpoint && pending.generation == generation)
            .map(|(key, _)| key.clone())
            .collect::<Vec<_>>();
        keys.into_iter()
            .filter_map(|key| self.entries.remove(&key).map(|pending| pending.reply))
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    fn id(value: &str) -> RequestId {
        RequestId::parse(value).unwrap()
    }
    const PEER: &str = "a1";

    #[tokio::test]
    async fn matches_only_endpoint_generation_and_one_shot_request_before_expiry() {
        let now = Instant::now();
        let (tx, rx) = oneshot::channel();
        let mut pending = PendingDeviceNotifications::default();
        assert!(pending.register(
            PEER.into(),
            id("req-1"),
            4,
            now + Duration::from_secs(1),
            tx
        ));
        assert!(pending.resolve("b2", &id("req-1"), 4, now).is_none());
        assert!(pending.resolve(PEER, &id("req-1"), 5, now).is_none());
        let reply = pending.resolve(PEER, &id("req-1"), 4, now).unwrap();
        assert!(pending.resolve(PEER, &id("req-1"), 4, now).is_none());
        reply
            .send(NotificationReply {
                status: DeviceNotificationReceipt::Posted,
                code: None,
                message: None,
            })
            .unwrap();
        assert_eq!(rx.await.unwrap().status, DeviceNotificationReceipt::Posted);
    }

    #[tokio::test]
    async fn duplicate_and_expired_receipts_are_not_dispatched_or_replayed() {
        let now = Instant::now();
        let (tx, _rx) = oneshot::channel();
        let (duplicate, _duplicate_rx) = oneshot::channel();
        let mut pending = PendingDeviceNotifications::default();
        assert!(pending.register(
            PEER.into(),
            id("req-2"),
            7,
            now + Duration::from_millis(1),
            tx
        ));
        assert!(!pending.register(
            PEER.into(),
            id("req-2"),
            7,
            now + Duration::from_secs(1),
            duplicate
        ));
        assert!(
            pending
                .resolve(PEER, &id("req-2"), 7, now + Duration::from_millis(1))
                .is_none()
        );
        assert!(pending.disconnect(PEER, 7).is_empty());
    }

    #[test]
    fn offline_is_only_reported_before_dispatch() {
        let ControlMessage::DeviceNotificationResult {
            status, message, ..
        } = offline_result("a".repeat(64))
        else {
            panic!("offline result constructor must return device notification status");
        };
        assert_eq!(status, DeviceNotificationStatus::Offline);
        assert!(
            message
                .as_deref()
                .is_some_and(|text| text.contains("no request was dispatched"))
        );
    }

    #[tokio::test]
    async fn disconnect_releases_pending_waiters_as_ambiguous() {
        let now = Instant::now();
        let (tx, rx) = oneshot::channel();
        let mut pending = PendingDeviceNotifications::default();
        pending.register(
            PEER.into(),
            id("req-3"),
            8,
            now + Duration::from_secs(1),
            tx,
        );
        let waiters = pending.disconnect(PEER, 8);
        assert_eq!(waiters.len(), 1);
        waiters
            .into_iter()
            .next()
            .unwrap()
            .send(NotificationReply {
                status: DeviceNotificationReceipt::Unknown,
                code: None,
                message: Some("The phone may have acted before disconnect.".into()),
            })
            .unwrap();
        assert_eq!(rx.await.unwrap().status, DeviceNotificationReceipt::Unknown);
    }
}
