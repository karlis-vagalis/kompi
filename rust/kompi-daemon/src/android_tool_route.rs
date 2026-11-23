use kompi_protocol::{ConversationId, RequestId};
use std::{collections::HashMap, time::Instant};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ConsumeResult {
    Accepted,
    Expired,
    Missing,
    WrongConversation,
}

#[derive(Default)]
pub struct PendingAndroidTools(HashMap<RequestId, PendingTool>);

struct PendingTool {
    conversation_id: ConversationId,
    deadline: Instant,
}

impl PendingAndroidTools {
    pub fn len(&self) -> usize { self.0.len() }

    pub fn count_for(&self, conversation_id: ConversationId) -> usize {
        self.0.values().filter(|pending| pending.conversation_id == conversation_id).count()
    }

    pub fn prune(&mut self, now: Instant) -> Vec<(RequestId, ConversationId)> {
        let mut expired = Vec::new();
        self.0.retain(|id, request| {
            let pending = request.deadline > now;
            if !pending { expired.push((id.clone(), request.conversation_id)); }
            pending
        });
        expired
    }

    pub fn register(
        &mut self,
        request_id: RequestId,
        conversation_id: ConversationId,
        deadline: Instant,
    ) -> bool {
        if self.0.contains_key(&request_id) { return false; }
        self.0.insert(request_id, PendingTool { conversation_id, deadline });
        true
    }

    /// A response can consume only its own live conversation-bound request.
    /// A wrong-conversation attempt never consumes the valid pending request.
    pub fn consume(
        &mut self,
        request_id: &RequestId,
        conversation_id: ConversationId,
        now: Instant,
    ) -> ConsumeResult {
        match self.0.get(request_id) {
            None => ConsumeResult::Missing,
            Some(pending) if pending.conversation_id != conversation_id => ConsumeResult::WrongConversation,
            Some(pending) if now >= pending.deadline => {
                self.0.remove(request_id);
                ConsumeResult::Expired
            }
            Some(_) => {
                self.0.remove(request_id);
                ConsumeResult::Accepted
            }
        }
    }

    /// Retire the request even after its deadline; this is used to clear phone UI
    /// when the Pi caller cancels or its timeout fires.
    pub fn retire(&mut self, request_id: &RequestId, conversation_id: ConversationId) -> bool {
        if self.0.get(request_id).is_some_and(|pending| pending.conversation_id == conversation_id) {
            self.0.remove(request_id);
            true
        } else {
            false
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use uuid::Uuid;

    fn new_conversation() -> ConversationId { Uuid::new_v4().into() }
    fn request(value: &str) -> RequestId { RequestId::parse(value).unwrap() }

    #[test]
    fn request_is_conversation_scoped_and_consumed_at_most_once() {
        let mut pending = PendingAndroidTools::default();
        let conversation = new_conversation();
        let id = request("android-tool-1");
        let now = Instant::now();
        assert!(pending.register(id.clone(), conversation, now + std::time::Duration::from_secs(60)));
        assert_eq!(pending.consume(&id, new_conversation(), now), ConsumeResult::WrongConversation);
        assert_eq!(pending.len(), 1, "wrong owner must not consume the real phone's pending request");
        assert_eq!(pending.consume(&id, conversation, now), ConsumeResult::Accepted);
        assert_eq!(pending.consume(&id, conversation, now), ConsumeResult::Missing);
    }

    #[test]
    fn duplicate_ids_and_expired_responses_are_rejected() {
        let mut pending = PendingAndroidTools::default();
        let conversation = new_conversation();
        let id = request("android-tool-2");
        let now = Instant::now();
        let deadline = now + std::time::Duration::from_millis(50);
        assert!(pending.register(id.clone(), conversation, deadline));
        assert!(!pending.register(id.clone(), conversation, deadline));
        assert_eq!(pending.consume(&id, conversation, deadline), ConsumeResult::Expired);
        assert_eq!(pending.len(), 0);
    }

    #[test]
    fn cancellation_retires_only_the_matching_request() {
        let mut pending = PendingAndroidTools::default();
        let conversation = new_conversation();
        let id = request("android-tool-3");
        assert!(pending.register(id.clone(), conversation, Instant::now()));
        assert!(!pending.retire(&id, new_conversation()));
        assert!(pending.retire(&id, conversation));
        assert!(!pending.retire(&id, conversation));
    }
}
