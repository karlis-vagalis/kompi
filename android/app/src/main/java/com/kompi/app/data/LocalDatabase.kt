package com.kompi.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.room.Upsert
import com.kompi.app.security.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

@Entity(tableName = "outbox", primaryKeys = ["hostId", "requestId"], indices = [Index(value = ["hostId", "compositionOrder"], unique = true)])
data class OutboxRow(
    val hostId: String,
    val requestId: String,
    val compositionOrder: Long,
    val encryptedBody: String,
    val delivery: String,
    val attachmentIds: String,
    val state: String,
    val createdAtEpochMillis: Long,
)

@Entity(tableName = "sessions", primaryKeys = ["hostId", "conversationId"])
data class SessionRow(
    val hostId: String,
    val conversationId: String,
    val state: String,
    val createdAt: String,
    val expiresAt: String,
    val idleTimeoutSeconds: Long,
)

@Entity(tableName = "request_state", primaryKeys = ["hostId", "requestId"])
data class RequestStateRow(
    val hostId: String,
    val requestId: String,
    val conversationId: String,
    val outcome: String,
    // Retain only an encrypted digest after outbox cleanup, never another transcript.
    val encryptedIntentFingerprint: String? = null,
)

@Entity(tableName = "host_settings")
data class HostSettingsRow(
    @androidx.room.PrimaryKey val hostId: String,
    val effectiveIdleTimeoutSeconds: Long?,
    val effectiveUploadRetentionSeconds: Long?,
    val pendingIdleTimeoutSeconds: Long?,
    val pendingUploadRetentionSeconds: Long?,
)

@Entity(tableName = "sync_cursor")
data class SyncCursorRow(@androidx.room.PrimaryKey val hostId: String, val cursor: String)

@Entity(tableName = "applied_events", primaryKeys = ["hostId", "cursor"])
data class AppliedEventRow(val hostId: String, val cursor: String, val eventId: String)

@Dao
interface LocalDao {
    @Query("SELECT * FROM outbox WHERE hostId = :hostId ORDER BY compositionOrder")
    fun observeOutbox(hostId: String): Flow<List<OutboxRow>>

    @Query("SELECT * FROM sessions WHERE hostId = :hostId ORDER BY createdAt")
    fun observeSessions(hostId: String): Flow<List<SessionRow>>

    @Query("DELETE FROM sessions WHERE hostId = :hostId")
    suspend fun removeSessions(hostId: String)

    @Query("DELETE FROM sessions WHERE hostId = :hostId AND conversationId NOT IN (:conversationIds)")
    suspend fun removeSessionsExcept(hostId: String, conversationIds: List<String>)

    @Query("SELECT * FROM request_state WHERE hostId = :hostId")
    fun observeRequests(hostId: String): Flow<List<RequestStateRow>>

    @Query("SELECT * FROM request_state WHERE hostId = :hostId AND requestId = :requestId")
    suspend fun request(hostId: String, requestId: String): RequestStateRow?

    @Query("SELECT * FROM outbox WHERE hostId = :hostId AND requestId = :requestId")
    suspend fun outboxItem(hostId: String, requestId: String): OutboxRow?

    @Query("SELECT * FROM host_settings WHERE hostId = :hostId")
    fun observeSettings(hostId: String): Flow<HostSettingsRow?>

    @Query("SELECT * FROM host_settings WHERE hostId = :hostId")
    suspend fun settings(hostId: String): HostSettingsRow?

    @Query("SELECT cursor FROM sync_cursor WHERE hostId = :hostId")
    suspend fun cursor(hostId: String): String?

    @Query("SELECT MAX(compositionOrder) FROM outbox WHERE hostId = :hostId")
    suspend fun maxCompositionOrder(hostId: String): Long?

    @Upsert suspend fun putOutbox(row: OutboxRow)
    @Upsert suspend fun putRequest(row: RequestStateRow)
    @Upsert suspend fun putSession(row: SessionRow)
    @Upsert suspend fun putSettings(row: HostSettingsRow)
    @Upsert suspend fun putCursor(row: SyncCursorRow)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun recordEvent(row: AppliedEventRow): Long

    @Query("UPDATE outbox SET state = :state WHERE hostId = :hostId AND requestId = :requestId")
    suspend fun setRequestState(hostId: String, requestId: String, state: String)

    @Query("DELETE FROM outbox WHERE hostId = :hostId AND requestId = :requestId")
    suspend fun removeOutbox(hostId: String, requestId: String)

}

@Database(
    entities = [OutboxRow::class, SessionRow::class, RequestStateRow::class, HostSettingsRow::class, SyncCursorRow::class, AppliedEventRow::class],
    version = 2,
    exportSchema = false,
)
abstract class LocalDatabase : RoomDatabase() {
    abstract fun localDao(): LocalDao

    companion object {
        @Volatile private var instance: LocalDatabase? = null

        /** Activity and assistant service share one Room invalidation/transaction owner. */
        fun open(context: Context): LocalDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LocalDatabase::class.java, "kompi-delivery-v4.db").build().also { instance = it }
        }
    }
}

data class StoredPrompt(
    val hostId: String,
    val requestId: String,
    val compositionOrder: Long,
    val text: String,
    val delivery: String,
    val state: String,
    val attachmentIds: List<String>,
    val createdAtEpochMillis: Long,
    val configuration: com.kompi.app.protocol.generated.ChatModelConfiguration?,
    val targetConversationId: String?,
    val createIfMissing: Boolean,
)

data class LocalConversationState(
    val outbox: List<StoredPrompt>,
    val sessions: List<SessionRow>,
    val activeConversationId: String?,
    val requests: List<RequestStateRow>,
    val settings: HostSettingsRow?,
)

/** Delivery outbox, lifecycle and settings only. Pi conversation content is never stored here. */
class LocalRepository(
    private val database: LocalDatabase,
    private val secureStore: SecureStore,
) {
    private val dao = database.localDao()

    private companion object {
        val TERMINAL_OUTCOMES = setOf("completed", "failed", "cancelled", "host-cancelled", "uncertain", "rejected", "discarded", "local-failure")
    }

    private data class PromptIntent(
        val body: String,
        val delivery: String,
        val attachmentIds: List<String>,
        val configuration: com.kompi.app.protocol.generated.ChatModelConfiguration,
        val targetConversationId: String?,
        val createIfMissing: Boolean,
    ) {
        fun fingerprint(): String {
            val encoded = org.json.JSONArray().put(delivery)
                .put(ChatModelStorage.encodePrompt(body, configuration, attachmentIds, targetConversationId, createIfMissing)).toString()
            return android.util.Base64.encodeToString(
                java.security.MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8)),
                android.util.Base64.NO_WRAP,
            )
        }
    }

    /** A parsed, unsigned durable ledger position; comparisons must not use decimal strings. */
    private data class LedgerCursor private constructor(val value: java.math.BigInteger) {
        companion object {
            fun parse(raw: String): LedgerCursor {
                require(raw.matches(Regex("^(0|[1-9][0-9]{0,19})$"))) { "Invalid event cursor" }
                val value = java.math.BigInteger(raw)
                require(value <= java.math.BigInteger("18446744073709551615")) { "Event cursor exceeds its range" }
                return LedgerCursor(value)
            }
        }
    }

    fun observe(hostId: String): Flow<LocalConversationState> = combine(
        dao.observeOutbox(hostId).map { rows -> rows.map(::storedPrompt) }.flowOn(Dispatchers.IO), dao.observeSessions(hostId), dao.observeRequests(hostId), dao.observeSettings(hostId),
    ) { outbox, sessions, requests, settings ->
        LocalConversationState(outbox, sessions, sessions.lastOrNull { it.state == "active" }?.conversationId, requests, settings)
    }

    suspend fun enqueue(hostId: String, requestId: String, body: String, delivery: String, attachmentIds: List<String>, configuration: com.kompi.app.protocol.generated.ChatModelConfiguration, kind: String = "queued", createdAtEpochMillis: Long = System.currentTimeMillis(), targetConversationId: String? = null, createIfMissing: Boolean = false) {
        require(!createIfMissing || targetConversationId != null)
        val intent = PromptIntent(body, delivery, attachmentIds.toList(), configuration, targetConversationId, createIfMissing)
        val fingerprint = intent.fingerprint()
        database.withTransaction {
            val known = dao.request(hostId, requestId)
            if (known != null) {
                check(known.encryptedIntentFingerprint?.let(secureStore::reveal) == fingerprint) {
                    "Request ID is already bound to a different or remotely retained intent"
                }
                return@withTransaction // Identical retry retains order, configuration and terminal state.
            }
            val order = (dao.maxCompositionOrder(hostId) ?: 0L) + 1L
            val encrypted = secureStore.protect(ChatModelStorage.encodePrompt(intent.body, intent.configuration, intent.attachmentIds, intent.targetConversationId, intent.createIfMissing))
            dao.putOutbox(OutboxRow(hostId, requestId, order, encrypted, intent.delivery, org.json.JSONArray(intent.attachmentIds).toString(), kind, createdAtEpochMillis))
            dao.putRequest(RequestStateRow(hostId, requestId, targetConversationId.orEmpty(), kind, secureStore.protect(fingerprint)))
        }
    }

    suspend fun pendingPrompts(hostId: String): List<StoredPrompt> = dao.observeOutbox(hostId).first().map(::storedPrompt)

    private fun storedPrompt(row: OutboxRow): StoredPrompt {
        val content = ChatModelStorage.decodePrompt(secureStore.reveal(row.encryptedBody))
        // Modern encrypted intents are authoritative, even if the redundant index is corrupt.
        // Legacy text-only records lack a model and already require discard/resend. Keep them
        // visible and discardable even when their old index cannot supply an attachment preview.
        val attachments = content.attachmentIds ?: try {
            val ids = org.json.JSONArray(row.attachmentIds)
            (0 until ids.length()).map { ids.get(it) as? String ?: throw org.json.JSONException("Not a string") }
        } catch (_: org.json.JSONException) {
            android.util.Log.w("LocalRepository", "Ignoring corrupt attachment index on legacy prompt")
            emptyList()
        }
        return StoredPrompt(row.hostId, row.requestId, row.compositionOrder, content.text, row.delivery, row.state, attachments, row.createdAtEpochMillis, content.configuration, content.targetConversationId, content.createIfMissing)
    }

    suspend fun holdPromptForConversation(hostId: String, requestId: String) = database.withTransaction {
        val item = dao.outboxItem(hostId, requestId) ?: return@withTransaction
        // Loss of route capability is not a negative receipt for a previous write.
        if (item.state == "queued") dao.putOutbox(item.copy(state = "waiting-for-conversation"))
    }

    /** A crash or lost reply must not turn a possibly accepted prompt into a local-only discard. */
    suspend fun beginPromptDispatch(hostId: String, requestId: String): Boolean = database.withTransaction {
        val item = dao.outboxItem(hostId, requestId) ?: return@withTransaction false
        if (item.state == "accepted") return@withTransaction false
        dao.setRequestState(hostId, requestId, "dispatching")
        true
    }

    suspend fun isPromptDispatchUncertain(hostId: String, requestId: String): Boolean =
        dao.outboxItem(hostId, requestId)?.state == "dispatching"

    enum class RejectionDisposition { Retry, Hold, Remove }

    suspend fun recordPromptRejection(hostId: String, requestId: String, disposition: RejectionDisposition): Boolean = database.withTransaction {
        val item = dao.outboxItem(hostId, requestId) ?: return@withTransaction false
        if (item.state != "dispatching") return@withTransaction false // Never downgrade durable acceptance.
        when (disposition) {
            RejectionDisposition.Retry -> dao.setRequestState(hostId, requestId, "queued")
            RejectionDisposition.Hold -> dao.setRequestState(hostId, requestId, "waiting-for-conversation")
            RejectionDisposition.Remove -> {
                val request = checkNotNull(dao.request(hostId, requestId))
                dao.putRequest(request.copy(outcome = "rejected"))
                dao.removeOutbox(hostId, requestId)
            }
        }
        true
    }

    suspend fun markPromptAccepted(hostId: String, requestId: String) {
        database.withTransaction {
            dao.request(hostId, requestId)?.let { current ->
                if (current.outcome in TERMINAL_OUTCOMES || current.outcome == "running") return@withTransaction
                dao.setRequestState(hostId, requestId, "accepted")
                dao.putRequest(current.copy(outcome = "accepted"))
            }
        }
    }

    suspend fun bindPromptToConversation(hostId: String, requestId: String, conversationId: String) {
        require(conversationId.isNotBlank())
        database.withTransaction {
            dao.request(hostId, requestId)?.let { current ->
                check(current.conversationId.isEmpty() || current.conversationId == conversationId) { "A request cannot move to another conversation" }
                dao.putRequest(current.copy(conversationId = conversationId))
            }
        }
    }

    suspend fun failBeforeDispatch(hostId: String, requestId: String) = database.withTransaction {
        // A previous ambiguous write must never become a definitive local-only failure.
        if (dao.outboxItem(hostId, requestId)?.state == "queued") {
            dao.request(hostId, requestId)?.let { dao.putRequest(it.copy(outcome = "local-failure")) }
            dao.removeOutbox(hostId, requestId)
        }
    }

    suspend fun discardUnacceptedPrompt(hostId: String, requestId: String): Boolean = database.withTransaction {
        val item = dao.outboxItem(hostId, requestId) ?: return@withTransaction false
        check(item.state != "accepted" && item.state != "dispatching") {
            "A message the computer may already be executing cannot be discarded"
        }
        dao.removeOutbox(hostId, requestId)
        dao.request(hostId, requestId)?.let { dao.putRequest(it.copy(outcome = "discarded")) }
        true
    }

    fun observeHostSettings(hostId: String): Flow<HostSettingsRow?> = dao.observeSettings(hostId)

    suspend fun updatePendingSettings(hostId: String, idleTimeoutSeconds: Long?, uploadRetentionSeconds: Long?) {
        database.withTransaction {
            val current = dao.settings(hostId) ?: HostSettingsRow(hostId, null, null, null, null)
            dao.putSettings(current.copy(
                pendingIdleTimeoutSeconds = idleTimeoutSeconds ?: current.pendingIdleTimeoutSeconds,
                pendingUploadRetentionSeconds = uploadRetentionSeconds ?: current.pendingUploadRetentionSeconds,
            ))
        }
    }

    suspend fun observeEffectiveSettings(hostId: String, idleTimeoutSeconds: Long, uploadRetentionSeconds: Long) {
        database.withTransaction {
            val current = dao.settings(hostId) ?: HostSettingsRow(hostId, null, null, null, null)
            dao.putSettings(current.copy(effectiveIdleTimeoutSeconds = idleTimeoutSeconds, effectiveUploadRetentionSeconds = uploadRetentionSeconds))
        }
    }

    suspend fun observeEffectiveIdleTimeout(hostId: String, idleTimeoutSeconds: Long) {
        database.withTransaction {
            val current = dao.settings(hostId) ?: HostSettingsRow(hostId, null, null, null, null)
            dao.putSettings(current.copy(effectiveIdleTimeoutSeconds = idleTimeoutSeconds))
        }
    }

    suspend fun confirmSettings(hostId: String, idleTimeoutSeconds: Long, uploadRetentionSeconds: Long) {
        database.withTransaction {
            val current = dao.settings(hostId) ?: HostSettingsRow(hostId, null, null, null, null)
            dao.putSettings(current.copy(
                effectiveIdleTimeoutSeconds = idleTimeoutSeconds,
                effectiveUploadRetentionSeconds = uploadRetentionSeconds,
                pendingIdleTimeoutSeconds = current.pendingIdleTimeoutSeconds?.takeUnless { it == idleTimeoutSeconds },
                pendingUploadRetentionSeconds = current.pendingUploadRetentionSeconds?.takeUnless { it == uploadRetentionSeconds },
            ))
        }
    }

    suspend fun rejectPendingSettings(hostId: String, rejected: com.kompi.app.protocol.generated.EffectiveSettings?) {
        database.withTransaction {
            dao.settings(hostId)?.let { current ->
                dao.putSettings(current.copy(
                    pendingIdleTimeoutSeconds = current.pendingIdleTimeoutSeconds?.takeUnless { rejected != null && it == rejected.idleTimeoutSeconds.toLong() },
                    pendingUploadRetentionSeconds = current.pendingUploadRetentionSeconds?.takeUnless { rejected != null && it == rejected.uploadRetentionSeconds.toLong() },
                ))
            }
        }
    }

    suspend fun commitCursor(hostId: String, cursor: String) {
        val next = LedgerCursor.parse(cursor)
        database.withTransaction {
            val previous = LedgerCursor.parse(dao.cursor(hostId) ?: "0")
            require(next.value >= previous.value) { "A cursor cannot move backwards" }
            dao.putCursor(SyncCursorRow(hostId, cursor))
        }
    }

    /** Atomically installs the authoritative snapshot projection and its resume position. */
    suspend fun applySnapshot(hostId: String, snapshot: com.kompi.app.protocol.generated.WireMessage.SyncSnapshot) {
        val barrier = LedgerCursor.parse(snapshot.cursor)
        database.withTransaction {
            val previous = LedgerCursor.parse(dao.cursor(hostId) ?: "0")
            // Reject before changing any projection, settings, request or resume position.
            require(barrier.value >= previous.value) { "Stale synchronization snapshot" }
            snapshot.sessions.forEach { session ->
                dao.putSession(SessionRow(hostId, session.conversationId, session.state, session.createdAt, session.expiresAt, session.idleTimeoutSeconds.toLong()))
            }
            snapshot.outstandingRequests.forEach { reconcileRequest(hostId, it) }
            val current = dao.settings(hostId) ?: HostSettingsRow(hostId, null, null, null, null)
            dao.putSettings(reconcileSettings(current, snapshot.settings.idleTimeoutSeconds.toLong(), snapshot.settings.uploadRetentionSeconds.toLong()))
            dao.putCursor(SyncCursorRow(hostId, snapshot.cursor))
        }
    }

    suspend fun activeConversationId(hostId: String): String? = dao.observeSessions(hostId).first()
        .singleOrNull { it.state == "active" }?.conversationId

    suspend fun currentCursor(hostId: String): String = dao.cursor(hostId) ?: "0"

    suspend fun hostSettings(hostId: String): HostSettingsRow? = dao.settings(hostId)

    suspend fun upsertConversation(hostId: String, conversation: com.kompi.app.transport.PhoneConversation) {
        dao.putSession(SessionRow(hostId, conversation.conversationId, conversation.state.wire, conversation.createdAt, conversation.expiresAt, conversation.idleTimeoutSeconds.toLong()))
    }

    suspend fun reconcileSessions(hostId: String, conversationIds: List<String>) {
        database.withTransaction {
            if (conversationIds.isEmpty()) dao.removeSessions(hostId) else dao.removeSessionsExcept(hostId, conversationIds)
        }
    }

    suspend fun requestState(hostId: String, requestId: String): RequestStateRow? = dao.request(hostId, requestId)

    suspend fun hasQueuedPrompt(hostId: String, requestId: String): Boolean = dao.outboxItem(hostId, requestId) != null

    /** Reconciles delivery outcomes, never response text or a transcript cursor. */
    suspend fun reconcileOutstandingRequests(hostId: String, summaries: List<com.kompi.app.protocol.generated.RequestSummary>) {
        database.withTransaction { summaries.forEach { reconcileRequest(hostId, it) } }
    }

    private suspend fun reconcileRequest(hostId: String, summary: com.kompi.app.protocol.generated.RequestSummary) {
        val current = dao.request(hostId, summary.requestId)
        check(current == null || current.conversationId.isEmpty() || current.conversationId == summary.conversationId) {
            "A request cannot move to another conversation"
        }
        val outcome = when {
            summary.outcome == "cancelled" && (current == null || current.outcome == "cancelled" || current.outcome !in TERMINAL_OUTCOMES) -> "host-cancelled"
            current != null && current.outcome in TERMINAL_OUTCOMES -> current.outcome
            current?.outcome == "running" && summary.outcome in setOf("queued", "accepted") -> current.outcome
            else -> summary.outcome
        }
        dao.putRequest(RequestStateRow(hostId, summary.requestId, summary.conversationId, outcome, current?.encryptedIntentFingerprint))
        if (outcome in TERMINAL_OUTCOMES) dao.removeOutbox(hostId, summary.requestId)
        else dao.setRequestState(hostId, summary.requestId, "accepted")
    }

    suspend fun applyEvent(hostId: String, nextCursor: String, eventId: String, mutation: suspend LocalDao.() -> Unit) {
        val next = LedgerCursor.parse(nextCursor)
        database.withTransaction {
            val previous = LedgerCursor.parse(dao.cursor(hostId) ?: "0")
            if (next.value <= previous.value) return@withTransaction
            require(next.value == previous.value + java.math.BigInteger.ONE) { "Event cursor gap must be reconciled before committing" }
            dao.mutation()
            dao.recordEvent(AppliedEventRow(hostId, nextCursor, eventId))
            dao.putCursor(SyncCursorRow(hostId, nextCursor))
        }
    }

    private fun reconcileSettings(current: HostSettingsRow, idle: Long, retention: Long): HostSettingsRow = current.copy(
        effectiveIdleTimeoutSeconds = idle,
        effectiveUploadRetentionSeconds = retention,
        pendingIdleTimeoutSeconds = current.pendingIdleTimeoutSeconds?.takeUnless { it == idle },
        pendingUploadRetentionSeconds = current.pendingUploadRetentionSeconds?.takeUnless { it == retention },
    )

    suspend fun applyDurableEvent(hostId: String, cursor: String, event: com.kompi.app.protocol.generated.DurableEvent) {
        applyEvent(hostId, cursor, cursor, mutation = {
            when (event) {
                is com.kompi.app.protocol.generated.DurableEvent.SessionChanged -> {
                    val session = event.payload.session
                    putSession(SessionRow(hostId, session.conversationId, session.state, session.createdAt, session.expiresAt, session.idleTimeoutSeconds.toLong()))
                }
                is com.kompi.app.protocol.generated.DurableEvent.RequestChanged -> reconcileRequest(hostId, event.payload.request)
                is com.kompi.app.protocol.generated.DurableEvent.ResponseCompleted -> {
                    val completed = event.payload
                    val outcome = if (completed.outcome == "cancelled") "host-cancelled" else completed.outcome
                    val current = request(hostId, completed.requestId)
                    if (current == null) putRequest(RequestStateRow(hostId, completed.requestId, "", outcome))
                    else if (current.outcome !in TERMINAL_OUTCOMES) putRequest(current.copy(outcome = outcome))
                    removeOutbox(hostId, completed.requestId)
                }
                is com.kompi.app.protocol.generated.DurableEvent.SettingsChanged -> {
                    val remoteSettings = event.payload.settings
                    val current = settings(hostId) ?: HostSettingsRow(hostId, null, null, null, null)
                    putSettings(reconcileSettings(current, remoteSettings.idleTimeoutSeconds.toLong(), remoteSettings.uploadRetentionSeconds.toLong()))
                }
                is com.kompi.app.protocol.generated.DurableEvent.AttachmentChanged -> Unit
            }
        })
    }
}
