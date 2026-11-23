package com.kompi.app.diagnostics

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Disposable diagnostics only: isolated from Room, pairing, outbox and Pi transcript ownership. */
internal class SpeechAuditStore(context: Context) {
    private val appContext = context.applicationContext
    private val bootCount = Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT)
    private val pending = ArrayDeque<SpeechAudit.Entry>()
    private var drainScheduled = false
    private val dropped = AtomicLong()
    private val writeFailures = AtomicLong()
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "kompi-speech-audit")
    }
    private val helper by lazy {
        object : SQLiteOpenHelper(appContext, File(appContext.noBackupFilesDir, "speech-audit.sqlite3").path, null, 1) {
            override fun onConfigure(db: SQLiteDatabase) {
                db.rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() }
            }
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("""CREATE TABLE events (
                    event_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    boot_count INTEGER NOT NULL, elapsed_ms INTEGER NOT NULL, epoch_ms INTEGER NOT NULL,
                    request_id TEXT NOT NULL, speech_id TEXT NOT NULL, phrase_sequence INTEGER,
                    stage TEXT NOT NULL, detail TEXT NOT NULL, source_ref TEXT
                )""")
                db.execSQL("CREATE INDEX events_request ON events(request_id, event_id)")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                error("Unsupported speech audit schema")
            }
        }
    }

    init {
        // Expire records without requiring an open screen, another turn or an ADB query.
        worker.scheduleWithFixedDelay({
            drain()
            try { prune(helper.writableDatabase) }
            catch (error: Exception) { reportFailure(error, 0) }
        }, 0, 15, TimeUnit.SECONDS)
    }

    fun append(entry: SpeechAudit.Entry) {
        val schedule = synchronized(pending) {
            if (pending.size >= MAX_EVENTS) { pending.removeFirst(); dropped.incrementAndGet() }
            pending.addLast(entry)
            if (drainScheduled) false else { drainScheduled = true; true }
        }
        if (schedule) worker.execute(::drain)
    }

    private fun drain() {
        val batch = synchronized(pending) {
            pending.toList().also { pending.clear(); drainScheduled = false }
        }
        if (batch.isEmpty()) return
        try {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                batch.forEach { entry ->
                    val values = ContentValues().apply {
                        put("boot_count", bootCount)
                        put("elapsed_ms", entry.elapsedMs)
                        put("epoch_ms", entry.epochMs)
                        put("request_id", entry.requestId)
                        put("speech_id", entry.speechId)
                        if (entry.sequence == null) putNull("phrase_sequence") else put("phrase_sequence", entry.sequence)
                        put("stage", entry.stage)
                        put("detail", entry.detail)
                        if (entry.source == null) putNull("source_ref") else put("source_ref", SpeechAudit.sourceJson(entry.source))
                    }
                    db.insertOrThrow("events", null, values)
                }
                prune(db)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        } catch (error: Exception) { reportFailure(error, batch.size) }
    }

    private fun prune(db: SQLiteDatabase) {
        db.delete("events", "boot_count != ? OR elapsed_ms < ?",
            arrayOf(bootCount.toString(), (SystemClock.elapsedRealtime() - RETENTION_MS).toString()))
        val evicted = db.delete("events", "event_id NOT IN (SELECT event_id FROM events ORDER BY event_id DESC LIMIT $MAX_EVENTS)", null)
        dropped.addAndGet(evicted.toLong())
    }

    private fun reportFailure(error: Exception, lost: Int) {
        writeFailures.incrementAndGet()
        dropped.addAndGet(lost.toLong())
        Log.e("KompiSpeechAudit", "store_failure type=${error.javaClass.simpleName} dropped=$lost")
    }

    /** Called only through the DUMP-protected provider; flush and materialize on the database worker. */
    fun query(requestId: String?): Cursor = worker.submit<Cursor> {
        drain()
        val db = helper.writableDatabase
        prune(db)
        val result = MatrixCursor(COLUMNS)
        db.query("events", COLUMNS, if (requestId == null) null else "request_id = ?",
            requestId?.let { arrayOf(it) }, null, null, "event_id ASC", MAX_EVENTS.toString()).use { cursor ->
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

    fun status(): Cursor = worker.submit<Cursor> {
        drain()
        val db = helper.writableDatabase
        prune(db)
        val count = db.rawQuery("SELECT COUNT(*) FROM events", null).use { it.moveToFirst(); it.getLong(0) }
        MatrixCursor(arrayOf("retention_ms", "max_events", "events", "dropped_events", "write_failures")).apply {
            addRow(arrayOf<Any>(RETENTION_MS, MAX_EVENTS, count, dropped.get(), writeFailures.get()))
        }
    }.get(10, TimeUnit.SECONDS)

    companion object {
        private const val RETENTION_MS = 5 * 60 * 1_000L
        private const val MAX_EVENTS = 4096
        private val COLUMNS = arrayOf("event_id", "boot_count", "elapsed_ms", "epoch_ms", "request_id", "speech_id",
            "phrase_sequence", "stage", "detail", "source_ref")
    }
}
