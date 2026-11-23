package com.kompi.app.assistant.push

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Durable write-ahead replay gate. Unexpired entries are never evicted to make room. */
internal class UnifiedPushReplayLedger(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE,
    null,
    VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE receipts (
                host_id TEXT NOT NULL,
                registration_id TEXT NOT NULL,
                notification_id TEXT NOT NULL,
                expires_at_ms INTEGER NOT NULL,
                PRIMARY KEY(host_id, registration_id, notification_id)
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX receipts_expiry ON receipts(expires_at_ms)")
        db.execSQL(
            """CREATE TABLE notification_claims (
                host_id TEXT NOT NULL,
                notification_id TEXT NOT NULL,
                expires_at_ms INTEGER NOT NULL,
                outcome TEXT NOT NULL,
                fingerprint TEXT,
                PRIMARY KEY(host_id, notification_id)
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX notification_claims_expiry ON notification_claims(expires_at_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion in 2..3) {
            db.execSQL(
                """CREATE TABLE notification_claims (
                    host_id TEXT NOT NULL,
                    notification_id TEXT NOT NULL,
                    expires_at_ms INTEGER NOT NULL,
                    outcome TEXT NOT NULL,
                    PRIMARY KEY(host_id, notification_id)
                )""".trimIndent(),
            )
            db.execSQL("CREATE INDEX notification_claims_expiry ON notification_claims(expires_at_ms)")
        } else if (oldVersion != 2 || newVersion != 3) error("No UnifiedPush replay-ledger migration is defined")
        if (oldVersion < 3 && newVersion >= 3) {
            db.execSQL("ALTER TABLE notification_claims ADD COLUMN fingerprint TEXT")
        }
    }

    @Synchronized
    fun claim(hostId: String, registrationId: String, notificationId: String, expiresAtMs: Long, nowMs: Long): ReplayClaim {
        if (expiresAtMs <= nowMs) return ReplayClaim.Expired
        val db = runCatching { writableDatabase }.getOrElse { return ReplayClaim.StorageFailure }
        return runCatching {
            db.beginTransaction()
            try {
                // Entries are retained for their entire signed TTL; only already-expired IDs may leave.
                db.delete(TABLE, "expires_at_ms <= ?", arrayOf(nowMs.toString()))
                val duplicate = db.query(
                    TABLE,
                    arrayOf("notification_id"),
                    "host_id = ? AND registration_id = ? AND notification_id = ?",
                    arrayOf(hostId, registrationId, notificationId),
                    null,
                    null,
                    null,
                    "1",
                ).use { it.moveToFirst() }
                if (duplicate) {
                    db.setTransactionSuccessful()
                    return@runCatching ReplayClaim.Duplicate
                }
                val count = db.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { cursor ->
                    if (!cursor.moveToFirst()) return@use MAX_RECEIPTS.toLong()
                    cursor.getLong(0)
                }
                if (count >= MAX_RECEIPTS) {
                    db.setTransactionSuccessful()
                    return@runCatching ReplayClaim.Full
                }
                val row = ContentValues().apply {
                    put("host_id", hostId)
                    put("registration_id", registrationId)
                    put("notification_id", notificationId)
                    put("expires_at_ms", expiresAtMs)
                }
                if (db.insertWithOnConflict(TABLE, null, row, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
                    db.setTransactionSuccessful()
                    return@runCatching ReplayClaim.Duplicate
                }
                db.setTransactionSuccessful()
                ReplayClaim.Claimed
            } finally {
                db.endTransaction()
            }
        }.getOrElse { ReplayClaim.StorageFailure }
    }

    /** Shared host-scoped claim for both live Iroh and encrypted-push deliveries. */
    @Synchronized
    fun claimNotification(hostId: String, notificationId: String, expiresAtMs: Long, nowMs: Long): ReplayClaim =
        claimNotificationContent(hostId, notificationId, expiresAtMs, nowMs, null)

    @Synchronized
    fun claimNotificationContent(hostId: String, notificationId: String, expiresAtMs: Long, nowMs: Long, fingerprint: String?): ReplayClaim {
        if (hostId.isBlank() || notificationId.isBlank()) return ReplayClaim.StorageFailure
        if (expiresAtMs <= nowMs) return ReplayClaim.Expired
        val db = runCatching { writableDatabase }.getOrElse { return ReplayClaim.StorageFailure }
        return runCatching {
            db.beginTransaction()
            try {
                db.delete(NOTIFICATIONS, "expires_at_ms <= ?", arrayOf(nowMs.toString()))
                val duplicate = db.query(
                    NOTIFICATIONS,
                    arrayOf("fingerprint"),
                    "host_id = ? AND notification_id = ?",
                    arrayOf(hostId, notificationId), null, null, null, "1",
                ).use { cursor ->
                    if (!cursor.moveToFirst()) null else {
                        val prior = cursor.getString(0)
                        // Older records carry no content proof. Keep them fail-closed.
                        if (fingerprint != null && prior != fingerprint) ReplayClaim.Conflict else ReplayClaim.Duplicate
                    }
                }
                if (duplicate != null) {
                    db.setTransactionSuccessful()
                    return@runCatching duplicate
                }
                val count = db.rawQuery("SELECT COUNT(*) FROM $NOTIFICATIONS", null).use { cursor ->
                    if (!cursor.moveToFirst()) MAX_RECEIPTS else cursor.getLong(0)
                }
                if (count >= MAX_RECEIPTS) {
                    db.setTransactionSuccessful()
                    return@runCatching ReplayClaim.Full
                }
                val inserted = db.insertOrThrow(NOTIFICATIONS, null, ContentValues().apply {
                    put("host_id", hostId)
                    put("notification_id", notificationId)
                    put("expires_at_ms", expiresAtMs)
                    put("outcome", NotificationReceiptOutcome.Posting.name)
                    put("fingerprint", fingerprint)
                })
                check(inserted != -1L)
                db.setTransactionSuccessful()
                ReplayClaim.Claimed
            } finally {
                db.endTransaction()
            }
        }.getOrElse { ReplayClaim.StorageFailure }
    }

    @Synchronized
    fun notificationOutcome(hostId: String, notificationId: String): NotificationReceiptOutcome? = runCatching {
        readableDatabase.query(
            NOTIFICATIONS,
            arrayOf("outcome"),
            "host_id = ? AND notification_id = ?",
            arrayOf(hostId, notificationId), null, null, null, "1",
        ).use { cursor -> if (cursor.moveToFirst()) NotificationReceiptOutcome.valueOf(cursor.getString(0)) else null }
    }.getOrNull()

    @Synchronized
    fun completeNotification(hostId: String, notificationId: String, outcome: NotificationReceiptOutcome): Boolean = runCatching {
        writableDatabase.update(
            NOTIFICATIONS,
            ContentValues().apply { put("outcome", outcome.name) },
            "host_id = ? AND notification_id = ?",
            arrayOf(hostId, notificationId),
        ) == 1
    }.getOrDefault(false)

    companion object {
        private const val DATABASE = "unified_push_replay.sqlite3"
        private const val VERSION = 3
        private const val NOTIFICATIONS = "notification_claims"
        private const val TABLE = "receipts"
        private const val MAX_RECEIPTS = 8192L
    }
}

internal enum class ReplayClaim { Claimed, Duplicate, Conflict, Expired, Full, StorageFailure }

internal enum class NotificationReceiptOutcome { Posting, Posted, NotPosted, Unknown }
