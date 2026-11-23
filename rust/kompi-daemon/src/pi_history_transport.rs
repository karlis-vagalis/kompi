use super::{Daemon, activation_error};
use crate::{
    pi_history::{self, LockError, LockedTranscript, Transcript},
    sqlite_store::conversation_metadata,
    store::{ConversationRecord, ConversationState},
};
use anyhow::{Context, Result, ensure};
use kompi_protocol::{
    ConversationId, ConversationMetadata, DEFAULT_IDLE_TIMEOUT_SECONDS, IdleTimeoutSeconds, Message,
};
use std::{
    collections::HashMap,
    fs,
    path::{Path, PathBuf},
    sync::{Mutex, OnceLock},
};
use uuid::Uuid;

fn active(row: &ConversationRecord) -> bool {
    matches!(row.state, ConversationState::Idle | ConversationState::Busy)
}

fn apply_transcript(row: &mut ConversationRecord, transcript: &Transcript) -> Result<()> {
    row.session_name = transcript.title.clone();
    row.project = transcript
        .cwd
        .to_str()
        .context("Pi working directory is not UTF-8")?
        .to_owned();
    row.session_file = transcript
        .path
        .to_str()
        .context("Pi transcript path is not UTF-8")?
        .to_owned();
    Ok(())
}

fn new_record(
    peer: &str,
    transcript: &Transcript,
    ttl: IdleTimeoutSeconds,
) -> Result<ConversationRecord> {
    let mut row = ConversationRecord {
        id: pi_history::conversation_id(peer, transcript.id),
        endpoint: peer.to_owned(),
        project: String::new(),
        project_session_id: String::new(),
        session_id: Some(transcript.id.to_string()),
        session_name: None,
        session_file: String::new(),
        idle_timeout_seconds: Some(ttl),
        state: ConversationState::Closed,
        created_at: transcript.created,
        last_user_prompt_at: transcript.activity,
    };
    apply_transcript(&mut row, transcript)?;
    Ok(row)
}

fn pi_id(row: &ConversationRecord) -> Option<Uuid> {
    row.session_id
        .as_deref()
        .and_then(|id| Uuid::parse_str(id).ok())
}

async fn catalog(home: &str) -> Result<(pi_history::SessionRoots, Vec<Transcript>)> {
    static CACHE: OnceLock<Mutex<pi_history::Catalog>> = OnceLock::new();
    let home = PathBuf::from(home);
    tokio::task::spawn_blocking(move || {
        let root = pi_history::sessions_root(&home)?;
        let mut cache = CACHE
            .get_or_init(Mutex::default)
            .lock()
            .expect("Pi catalog poisoned");
        let transcripts = cache.scan(&root)?;
        Ok((root, transcripts))
    })
    .await?
}

pub(super) struct HistoryBrowse(Vec<ConversationMetadata>);

impl HistoryBrowse {
    pub(super) fn page(
        &self,
        after: Option<ConversationId>,
        limit: u8,
    ) -> (Vec<ConversationMetadata>, Option<ConversationId>) {
        let start = self.0.partition_point(|row| {
            after.is_some_and(|key| row.conversation_id.as_uuid() <= key.as_uuid())
        });
        let end = self.0.len().min(start + usize::from(limit));
        let cursor = (end < self.0.len()).then(|| self.0[end - 1].conversation_id);
        (self.0[start..end].to_vec(), cursor)
    }
}

impl Daemon {
    async fn history_idle_timeout(&self, peer: &str) -> Result<IdleTimeoutSeconds> {
        Ok(self
            .sqlite
            .device(peer)
            .await?
            .context("device not paired")?
            .idle_timeout_seconds
            .unwrap_or(IdleTimeoutSeconds::try_from(DEFAULT_IDLE_TIMEOUT_SECONDS)?))
    }

    pub(super) async fn history_browse(&self, peer: &str) -> Result<HistoryBrowse> {
        let home = fs::canonicalize(dirs::home_dir().context("daemon home is unavailable")?)?;
        let home = home.to_str().context("daemon home is not UTF-8")?;
        let ttl = self.history_idle_timeout(peer).await?;
        let (_, transcripts) = catalog(home).await?;
        let excluded = self.sqlite.history_deleted_ids().await?;
        let mut transcripts: HashMap<_, _> = transcripts
            .into_iter()
            .filter(|entry| !excluded.contains(&entry.id))
            .map(|entry| (entry.id, entry))
            .collect();
        let mut owned = self.sqlite.history_rows(peer, home).await?;
        owned.retain_mut(|row| {
            let Some(id) = pi_id(row) else {
                return active(row);
            };
            if excluded.contains(&id) {
                return false;
            }
            if let Some(transcript) = transcripts.remove(&id) {
                return apply_transcript(row, &transcript).is_ok();
            }
            // A just-created live Pi session may not have decided to persist yet.
            // Don't advertise archived rows that no longer have a safe Pi identity.
            active(row)
        });
        let mut rows = owned;
        rows.extend(
            transcripts
                .values()
                .filter_map(|entry| new_record(peer, entry, ttl).ok()),
        );
        // Stable ordering for keyset pagination; clients sort by time for display.
        rows.sort_unstable_by_key(|row| *row.id.as_uuid());
        let sessions = rows
            .iter()
            .map(|row| {
                conversation_metadata(row, None)?.context("history row has no canonical metadata")
            })
            .collect::<Result<Vec<_>>>()?;
        Ok(HistoryBrowse(sessions))
    }

    async fn resolved_history_transcript(
        &self,
        peer: &str,
        id: ConversationId,
        home: &str,
    ) -> Result<
        Option<(
            pi_history::SessionRoots,
            Transcript,
            Option<ConversationRecord>,
        )>,
    > {
        let owned = self.sqlite.conversation_by_id(peer, id, home).await?;
        let owned_pi = owned.as_ref().and_then(pi_id);
        if owned.is_some() && owned_pi.is_none() {
            return Ok(None);
        }
        let (root, transcripts) = catalog(home).await?;
        let found = transcripts.into_iter().find(|entry| match owned_pi {
            Some(pi_id) => entry.id == pi_id,
            None => pi_history::conversation_id(peer, entry.id) == id,
        });
        let Some(entry) = found else {
            return Ok(None);
        };
        if self.sqlite.is_history_deleting(entry.id).await? {
            return Ok(None);
        }
        // Once this phone has an association, only its established conversation
        // key may address the transcript (an unpublished alias is not a row).
        if owned.is_none() && self.sqlite.has_history_association(peer, entry.id).await? {
            return Ok(None);
        }
        Ok(Some((root, entry, owned)))
    }

    pub(super) async fn history_conversation(
        &self,
        peer: &str,
        id: ConversationId,
        home: &str,
    ) -> Result<Option<ConversationRecord>> {
        let Some((_, transcript, owned)) = self.resolved_history_transcript(peer, id, home).await?
        else {
            return Ok(None);
        };
        if let Some(mut row) = owned {
            apply_transcript(&mut row, &transcript)?;
            Ok(Some(row))
        } else {
            Ok(Some(new_record(
                peer,
                &transcript,
                self.history_idle_timeout(peer).await?,
            )?))
        }
    }

    /// Reserve only on a writable action, not on paging or read-only archive
    /// loads. Caller serializes runtime start against this conversation's delete.
    pub(super) async fn reserve_history_conversation(
        &self,
        peer: &str,
        id: ConversationId,
        home: &str,
    ) -> Result<Option<ConversationRecord>> {
        // A live, newly opened session may have no persisted header yet.
        if let Some(row) = self
            .sqlite
            .conversation_by_id(peer, id, home)
            .await?
            .filter(active)
        {
            return Ok(Some(row));
        }
        let Some((_, transcript, owned)) = self.resolved_history_transcript(peer, id, home).await?
        else {
            return Ok(None);
        };
        if let Some(mut row) = owned {
            apply_transcript(&mut row, &transcript)?;
            return Ok(Some(row));
        }
        let row = new_record(peer, &transcript, self.history_idle_timeout(peer).await?)?;
        Ok(self
            .sqlite
            .reserve_history_conversation(&row)
            .await?
            .then_some(row))
    }

    pub(super) async fn delete_history_conversation(
        &self,
        peer: &str,
        generation: u64,
        id: ConversationId,
    ) -> Result<Message> {
        let unavailable = || {
            activation_error(
                id,
                "conversation-unavailable",
                "This saved conversation could not be located safely.",
            )
        };
        let busy = || {
            activation_error(
                id,
                "conversation-busy",
                "Close the running conversation before deleting it.",
            )
        };
        let deleted = || Message::ConversationDeleted {
            conversation_id: id,
        };
        let home = fs::canonicalize(dirs::home_dir().context("daemon home is unavailable")?)?;
        let home = home.to_str().context("daemon home is not UTF-8")?;
        let lane = self.prompt_lane(peer, id);
        let Ok(_guard) = lane.admission.try_lock() else {
            return Ok(busy());
        };
        let authorized = || async {
            Ok::<bool, anyhow::Error>(
                self.is_current_connection(peer, generation)
                    && self.sqlite.device(peer).await?.is_some(),
            )
        };
        ensure!(authorized().await?, "stale connection");
        let intent = self.sqlite.history_delete(peer, id).await?;
        if intent.as_ref().is_some_and(|intent| intent.finished) {
            return Ok(deleted());
        }
        if self
            .sqlite
            .conversation_by_id(peer, id, home)
            .await?
            .as_ref()
            .is_some_and(active)
        {
            return Ok(busy());
        }
        let (pi_id, path, locked) = if let Some(intent) = &intent {
            let root = pi_history::sessions_root(Path::new(home))?;
            let path = intent.path.clone();
            let identity = intent.identity;
            let pi_id = intent.pi_id;
            let locked = tokio::task::spawn_blocking(move || {
                LockedTranscript::open(&root, &path, pi_id, identity)
            })
            .await?;
            (pi_id, intent.path.clone(), locked)
        } else {
            let Some((root, transcript, _)) =
                self.resolved_history_transcript(peer, id, home).await?
            else {
                return Ok(unavailable());
            };
            let pi_id = transcript.id;
            let path = transcript.path.clone();
            let locked =
                tokio::task::spawn_blocking(move || LockedTranscript::discover(&root, &transcript))
                    .await?;
            (pi_id, path, locked)
        };
        // Revocation and mutation linearize under the peer authorization lease;
        // a potentially expensive filesystem scan never holds that lease.
        let authorization = self.runtime_authorization(peer);
        let _authorized = authorization.read().await;
        ensure!(authorized().await?, "stale connection");
        match locked {
            Ok(locked) => {
                if intent.is_none()
                    && !self
                        .sqlite
                        .begin_history_delete(
                            peer,
                            id,
                            pi_id,
                            path.to_str().context("Pi transcript path is not UTF-8")?,
                            locked.identity,
                        )
                        .await?
                {
                    return Ok(busy());
                }
                tokio::task::spawn_blocking(move || locked.unlink()).await??;
            }
            Err(LockError::Missing(parent)) if intent.is_some() => {
                // Interrupted after unlink. Make that absence durable before
                // recording success; no replacement has been opened or removed.
                #[cfg(unix)]
                tokio::task::spawn_blocking(move || parent.sync_all()).await??;
                #[cfg(windows)]
                drop(parent); // Windows completes disposition on handle close.
            }
            Err(LockError::InUse) => {
                return Ok(activation_error(
                    id,
                    "conversation-owned",
                    "Close the session on the computer before deleting it.",
                ));
            }
            Err(LockError::Missing(_) | LockError::IdentityChanged) => return Ok(unavailable()),
            Err(LockError::Other(error)) => return Err(error),
        }
        self.sqlite.finish_history_delete(peer, id, pi_id).await?;
        Ok(deleted())
    }
}
