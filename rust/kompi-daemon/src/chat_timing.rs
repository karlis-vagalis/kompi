//! Best-effort diagnostic spool, separate from request acceptance and transcripts.
use std::{
    path::Path,
    time::{Duration, Instant},
};

use anyhow::Result;
use kompi_protocol::{
    ChatTimingCursor, ChatTimingEvent, ChatTimingRow, ChatTimingStage, ChatTimingStamp,
    ConversationId, MAX_CHAT_TIMING_PAGE, RequestId, SyncCursor,
};
use sqlx::{
    SqlitePool,
    sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePoolOptions},
};
use tokio::sync::mpsc;

const RETAIN_MS: i64 = 14 * 24 * 60 * 60 * 1000;
const KEEP_ROWS: i64 = 32_768;

#[derive(Clone)]
pub(crate) struct ChatTiming {
    pool: SqlitePool,
    tx: mpsc::Sender<(String, ChatTimingEvent)>,
    store_id: ConversationId,
    clock_id: ConversationId,
    start: Instant,
}

impl ChatTiming {
    pub(crate) async fn open(directory: &Path) -> Result<Self> {
        let options = SqliteConnectOptions::new()
            .filename(directory.join("chat-timing.sqlite3"))
            .create_if_missing(true)
            .journal_mode(SqliteJournalMode::Wal)
            .busy_timeout(Duration::from_millis(200));
        let pool = SqlitePoolOptions::new()
            .max_connections(2)
            .connect_with(options)
            .await?;
        sqlx::raw_sql("CREATE TABLE IF NOT EXISTS metadata (singleton INTEGER PRIMARY KEY, store_id TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS timing (id INTEGER PRIMARY KEY AUTOINCREMENT, peer TEXT NOT NULL,
                received_ms INTEGER NOT NULL, event TEXT NOT NULL);
            CREATE INDEX IF NOT EXISTS timing_peer ON timing(peer,id);
            CREATE INDEX IF NOT EXISTS timing_age ON timing(received_ms);")
            .execute(&pool).await?;
        sqlx::query("INSERT OR IGNORE INTO metadata VALUES(1, ?)")
            .bind(uuid::Uuid::new_v4().to_string())
            .execute(&pool)
            .await?;
        let store: String = sqlx::query_scalar("SELECT store_id FROM metadata WHERE singleton=1")
            .fetch_one(&pool)
            .await?;
        let store_id = uuid::Uuid::parse_str(&store)?.into();
        let (tx, rx) = mpsc::channel(1024);
        tokio::spawn(drain(pool.clone(), rx));
        Ok(Self {
            pool,
            tx,
            store_id,
            clock_id: uuid::Uuid::new_v4().into(),
            start: Instant::now(),
        })
    }

    pub(crate) fn request(
        &self,
        peer: &str,
        request_id: RequestId,
        attempt_id: ConversationId,
    ) -> Trace {
        Trace {
            audit: self.clone(),
            peer: peer.into(),
            request_id,
            attempt_id,
            conversation: None,
            response_request_id: None,
        }
    }

    pub(crate) async fn page(
        &self,
        peer: &str,
        cursor: Option<ChatTimingCursor>,
    ) -> Result<kompi_protocol::Message> {
        let after = match cursor.filter(|c| c.store_id == self.store_id) {
            Some(cursor) => cursor.after.as_str().parse::<i64>()?,
            None => 0,
        };
        let records: Vec<(i64, String)> = sqlx::query_as(
            "SELECT id,event FROM timing WHERE peer=? AND id>? AND received_ms>=? ORDER BY id LIMIT ?")
            .bind(peer).bind(after).bind(crate::store::now_ms() - RETAIN_MS).bind((MAX_CHAT_TIMING_PAGE + 1) as i64)
            .fetch_all(&self.pool).await?;
        let has_more = records.len() > MAX_CHAT_TIMING_PAGE;
        let rows = records
            .into_iter()
            .take(MAX_CHAT_TIMING_PAGE)
            .map(|(cursor, event)| {
                Ok(ChatTimingRow {
                    cursor: SyncCursor::parse(cursor.to_string())?,
                    event: serde_json::from_str(&event)?,
                })
            })
            .collect::<Result<Vec<_>>>()?;
        Ok(kompi_protocol::Message::ChatTimingPage {
            store_id: self.store_id,
            rows,
            has_more,
        })
    }
}

#[derive(Clone)]
pub(crate) struct Trace {
    audit: ChatTiming,
    peer: String,
    request_id: RequestId,
    attempt_id: ConversationId,
    pub(crate) conversation: Option<ConversationId>,
    pub(crate) response_request_id: Option<RequestId>,
}
impl Trace {
    pub(crate) fn record(&self, stage: ChatTimingStage) {
        self.record_at(stage, Instant::now(), crate::store::now_ms());
    }
    pub(crate) fn record_at(&self, stage: ChatTimingStage, received_at: Instant, wall_ms: i64) {
        self.record_stamp(ChatTimingStamp {
            stage,
            clock_id: self.audit.clock_id,
            monotonic_ms: received_at.saturating_duration_since(self.audit.start).as_millis() as u64,
            wall_ms: wall_ms as u64,
        });
    }
    pub(crate) fn record_stamp(&self, stamp: ChatTimingStamp) {
        // Missing rows are allowed. No log per drop, blocking, or retries on the request path.
        let _ = self.audit.tx.try_send((
            self.peer.clone(),
            ChatTimingEvent {
                request_id: self.request_id.clone(),
                attempt_id: self.attempt_id,
                conversation_id: self.conversation,
                response_request_id: self.response_request_id.clone(),
                stamp,
            },
        ));
    }
}

async fn drain(pool: SqlitePool, mut input: mpsc::Receiver<(String, ChatTimingEvent)>) {
    let mut batch = Vec::new();
    let mut prune = tokio::time::interval(Duration::from_secs(60));
    loop {
        tokio::select! {
            n = input.recv_many(&mut batch, 256) => {
                if n == 0 { return; }
                // Coalesce normal stage bursts (no work is waiting on this transaction).
                tokio::time::sleep(Duration::from_millis(200)).await;
                while batch.len() < 256 {
                    match input.try_recv() { Ok(row) => batch.push(row), Err(_) => break }
                }
            }
            _ = prune.tick() => {}
        }
        if persist(&pool, &batch).await.is_err() {
            eprintln!("Kompi chat timing: audit storage write failed");
        }
        batch.clear();
    }
}

async fn persist(pool: &SqlitePool, batch: &[(String, ChatTimingEvent)]) -> Result<()> {
    let mut tx = pool.begin().await?;
    let received_ms = crate::store::now_ms();
    for (peer, event) in batch {
        sqlx::query("INSERT INTO timing(peer,received_ms,event) VALUES(?,?,?)")
            .bind(peer)
            .bind(received_ms)
            .bind(serde_json::to_string(event)?)
            .execute(&mut *tx)
            .await?;
    }
    sqlx::query("DELETE FROM timing WHERE received_ms<? OR id <= (SELECT COALESCE(MAX(id),0) FROM timing)-?")
        .bind(received_ms - RETAIN_MS).bind(KEEP_ROWS).execute(&mut *tx).await?;
    tx.commit().await?;
    Ok(())
}
