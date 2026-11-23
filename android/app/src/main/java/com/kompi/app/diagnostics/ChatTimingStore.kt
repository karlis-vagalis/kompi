package com.kompi.app.diagnostics

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Process
import android.util.Log
import com.kompi.app.protocol.generated.ChatTimingCursor
import com.kompi.app.protocol.generated.WireMessage as V3Message
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Private, disposable spool; diagnostics never run a transaction on chat/Compose threads. */
internal class ChatTimingStore(context: Context) {
    private var pending = ArrayDeque<ChatTiming.Entry>()
    private var scheduled = false
    private var dropped = 0L
    private var failures = 0L
    private val monitor = Any()
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "kompi-chat-timing")
    }
    private val helper = object : SQLiteOpenHelper(context, File(context.noBackupFilesDir, "chat-timing.sqlite3").path, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE timing (
                event_id INTEGER PRIMARY KEY, stage TEXT NOT NULL, origin TEXT NOT NULL, clock_id TEXT NOT NULL,
                monotonic_ms INTEGER NOT NULL, wall_ms INTEGER NOT NULL, ingested_ms INTEGER NOT NULL,
                request_id TEXT, attempt_id TEXT, connection_id TEXT, conversation_id TEXT,
                response_id TEXT, response_request_id TEXT, outcome TEXT, source_store TEXT, source_sequence INTEGER,
                UNIQUE(source_store,source_sequence))""")
            db.execSQL("CREATE INDEX timing_request ON timing(request_id,event_id)")
            db.execSQL("CREATE INDEX timing_conversation ON timing(conversation_id,event_id)")
            db.execSQL("CREATE INDEX timing_connection ON timing(connection_id,event_id)")
            db.execSQL("CREATE INDEX timing_age ON timing(ingested_ms)")
            db.execSQL("CREATE TABLE host_cursor(peer TEXT PRIMARY KEY, store_id TEXT NOT NULL, after_id TEXT NOT NULL, updated_ms INTEGER NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported chat timing schema")
    }
    init {
        worker.scheduleWithFixedDelay({
            runCatching { prune(helper.writableDatabase) }.onFailure { failure(0) }
        }, 0, 1, TimeUnit.MINUTES)
    }

    fun append(event: ChatTiming.Entry) {
        val start = synchronized(monitor) {
            if (pending.size == 1024) { pending.removeFirst(); dropped++ }
            pending.addLast(event)
            if (scheduled) false else { scheduled = true; true }
        }
        if (start) worker.schedule(::drain, 200, TimeUnit.MILLISECONDS)
    }

    private fun drain() {
        val batch = synchronized(monitor) {
            pending.also { pending = ArrayDeque(); scheduled = false }
        }
        if (batch.isEmpty()) return
        runCatching {
            transaction { db ->
                val now = System.currentTimeMillis()
                batch.forEach { db.insertOrThrow("timing", null, values(it, now)) }
                prune(db, now)
            }
        }.onFailure { failure(batch.size) }
    }

    private fun failure(lost: Int) {
        val count = synchronized(monitor) { dropped += lost; ++failures }
        if (count == 1L || count % 100L == 0L) Log.w("KompiChatTiming", "Audit storage operation failed")
    }

    private fun values(event: ChatTiming.Entry, ingestedMs: Long) = ContentValues().apply {
        put("stage", event.stage); put("origin", event.origin.wire); put("clock_id", event.clockId)
        put("monotonic_ms", event.time.monotonicMs); put("wall_ms", event.time.wallMs); put("ingested_ms", ingestedMs)
        put("request_id", event.request); put("attempt_id", event.attempt); put("connection_id", event.connection)
        put("conversation_id", event.conversation); put("response_id", event.response); put("response_request_id", event.responseRequest); put("outcome", event.outcome)
        put("source_store", event.sourceStore); put("source_sequence", event.sourceSequence)
    }

    private fun transaction(body: (SQLiteDatabase) -> Unit) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try { body(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    private fun prune(db: SQLiteDatabase, now: Long = System.currentTimeMillis()) {
        val floor = (now - RETENTION_MS).toString()
        db.delete("timing", "ingested_ms < ? OR event_id <= (SELECT COALESCE(MAX(event_id),0) FROM timing)-$MAX_ROWS", arrayOf(floor))
        db.delete("host_cursor", "updated_ms < ?", arrayOf(floor))
    }

    private suspend fun <T> work(body: () -> T): T = suspendCancellableCoroutine { continuation ->
        worker.execute {
            if (continuation.isActive) {
                try { continuation.resume(body()) }
                catch (e: Exception) { continuation.resumeWithException(e) }
            }
        }
    }
    suspend fun cursor(peer: String): ChatTimingCursor? = work {
        helper.readableDatabase.query("host_cursor", arrayOf("after_id", "store_id"), "peer = ?",
            arrayOf(peer), null, null, null).use { if (it.moveToFirst()) ChatTimingCursor(after = it.getString(0), storeId = it.getString(1)) else null }
    }
    suspend fun import(peer: String, page: V3Message.ChatTimingPage): Boolean = work {
        val last = page.rows.lastOrNull() ?: return@work true
        runCatching {
            transaction { db ->
                val now = System.currentTimeMillis()
                page.rows.forEach { row ->
                    val event = row.event
                    val stamp = event.stamp
                    db.insertWithOnConflict("timing", null, values(ChatTiming.Entry(stamp.stage, stamp.clockId,
                        ChatTiming.Time(stamp.monotonicMs, stamp.wallMs), ChatTiming.Origin.HostSpool,
                        request = event.requestId, attempt = event.attemptId, conversation = event.conversationId,
                        sourceStore = page.storeId, sourceSequence = row.cursor.toLong(), responseRequest = event.responseRequestId), now),
                        SQLiteDatabase.CONFLICT_IGNORE)
                }
                db.insertWithOnConflict("host_cursor", null, ContentValues().apply {
                    put("peer", peer); put("store_id", page.storeId); put("after_id", last.cursor); put("updated_ms", now)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                prune(db, now)
            }
        }.onFailure { failure(0) }.isSuccess
    }

    sealed interface Filter {
        data object All : Filter
        data object Status : Filter
        data class Request(val id: String) : Filter
        data class Conversation(val id: String) : Filter
        data class Connection(val id: String) : Filter
    }
    fun query(filter: Filter): Cursor = worker.submit<Cursor> {
        drain()
        val db = helper.writableDatabase
        prune(db)
        if (filter == Filter.Status) {
            val rows = android.database.DatabaseUtils.queryNumEntries(db, "timing")
            val counters = synchronized(monitor) { dropped to failures }
            return@submit MatrixCursor(arrayOf("schema", "retention_ms", "max_rows", "rows", "dropped_this_process", "write_failures_this_process")).apply {
                addRow(arrayOf<Any>(1, RETENTION_MS, MAX_ROWS, rows, counters.first, counters.second))
            }
        }
        val (where, args) = when (filter) {
            is Filter.Request -> "request_id = ? OR (request_id IS NULL AND conversation_id IS NULL AND connection_id IN " +
                "(SELECT connection_id FROM timing WHERE request_id = ? AND connection_id IS NOT NULL))" to arrayOf(filter.id, filter.id)
            is Filter.Conversation -> "conversation_id = ?" to arrayOf(filter.id)
            is Filter.Connection -> "connection_id = ?" to arrayOf(filter.id)
            else -> null to null
        }
        val result = MatrixCursor(COLUMNS)
        db.query("timing", COLUMNS, where, args, null, null, "event_id ASC", MAX_ROWS.toString()).use { cursor ->
            while (cursor.moveToNext()) result.addRow(Array<Any?>(COLUMNS.size) { index ->
                when (cursor.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                    else -> cursor.getString(index)
                }
            })
        }
        result
    }.get(10, TimeUnit.SECONDS)

    companion object {
        const val RETENTION_MS = 14 * 24 * 60 * 60 * 1000L
        const val MAX_ROWS = 32_768
        val COLUMNS = arrayOf("event_id", "stage", "origin", "clock_id", "monotonic_ms", "wall_ms", "ingested_ms",
            "request_id", "attempt_id", "connection_id", "conversation_id", "response_id", "response_request_id", "outcome", "source_store", "source_sequence")
    }
}
