package com.kompi.app.assistant.native

import android.Manifest
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object NativeCommunications {
    private data class Window(val since: Long, val until: Long, val limit: Int, val offset: Int)
    private fun window(a: NativeArguments): Window {
        val since = a.long("since", 0, 32_503_680_000_000L, 0)
        val until = a.long("until", since + 1, 32_503_680_000_001L, System.currentTimeMillis() + 1)
        return Window(since, until, a.long("limit", 1, 50, 20).toInt(), a.long("offset", 0, 5_000, 0).toInt())
    }
    fun parse(name: String, input: JSONObject): NativeOperation? = when (name) {
        "search_contacts" -> {
            val a = NativeArguments(input, "query", "limit", "offset")
            val query = a.text("query", 200, true)!!
            val limit = a.long("limit", 1, 50, 20).toInt()
            val offset = a.long("offset", 0, 5_000, 0).toInt()
            NativeTask(name, "Search contacts for “$query” and share up to $limit phone-number matches (offset $offset).") { context, _ ->
                if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) missingPermission("Contacts")
                else readPage(context, ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf("_id", "display_name", "data1", "data2"),
                    "display_name LIKE ? ESCAPE '\\' OR data1 LIKE ? ESCAPE '\\'", arrayOf(like(query), like(query)),
                    "display_name COLLATE NOCASE ASC, _id ASC", limit, offset) { cursor ->
                    JSONObject().put("name", cursor.text("display_name", 200)).put("number", cursor.text("data1", 80))
                        .put("numberType", cursor.getInt(cursor.getColumnIndexOrThrow("data2")))
                }
            }
        }
        "list_calls" -> {
            val a = NativeArguments(input, "number", "since", "until", "type", "limit", "offset")
            val w = window(a)
            val number = a.text("number", 40)
            val type = a.choice("type", setOf("all", "incoming", "outgoing", "missed", "rejected", "blocked"), "all")
            val typeCode = mapOf("incoming" to 1, "outgoing" to 2, "missed" to 3, "rejected" to 5, "blocked" to 6)[type]
            NativeTask(name, "Read $type calls from ${java.time.Instant.ofEpochMilli(w.since)} until ${java.time.Instant.ofEpochMilli(w.until)}${number?.let { " matching $it" }.orEmpty()}. Share up to ${w.limit} records (offset ${w.offset}), including numbers, times and duration.") { context, _ ->
                restricted(context, Manifest.permission.READ_CALL_LOG)?.let { return@NativeTask it }
                val clauses = mutableListOf("date >= ?", "date < ?")
                val args = mutableListOf(w.since.toString(), w.until.toString())
                number?.let { clauses.add("number = ?"); args.add(it) }
                typeCode?.let { clauses.add("type = ?"); args.add(it.toString()) }
                readPage(context, CallLog.Calls.CONTENT_URI, arrayOf("_id", "number", "date", "duration", "type"),
                    clauses.joinToString(" AND "), args.toTypedArray(), "date DESC, _id DESC", w.limit, w.offset) { cursor ->
                    JSONObject().put("number", cursor.text("number", 80)).put("date", cursor.number("date"))
                        .put("durationSeconds", cursor.number("duration")).put("type", cursor.number("type"))
                }.put("since", w.since).put("until", w.until).put("coverage", "Records exposed by Android Call Log; not complete third-party app call history. Reuse this time window for subsequent pages.")
            }
        }
        "search_sms" -> {
            val a = NativeArguments(input, "query", "number", "since", "until", "limit", "offset")
            val w = window(a)
            val query = a.text("query", 200)
            val number = a.text("number", 40)
            NativeTask(name, "Search SMS${query?.let { " containing “$it”" }.orEmpty()}${number?.let { " from/to $it" }.orEmpty()} from ${java.time.Instant.ofEpochMilli(w.since)} until ${java.time.Instant.ofEpochMilli(w.until)}. Share up to ${w.limit} messages including their text (offset ${w.offset}).") { context, _ ->
                restricted(context, Manifest.permission.READ_SMS)?.let { return@NativeTask it }
                val clauses = mutableListOf("date >= ?", "date < ?")
                val args = mutableListOf(w.since.toString(), w.until.toString())
                query?.let { clauses.add("body LIKE ? ESCAPE '\\'"); args.add(like(it)) }
                number?.let { clauses.add("address = ?"); args.add(it) }
                readPage(context, Telephony.Sms.CONTENT_URI, arrayOf("_id", "address", "body", "date", "type", "read"),
                    clauses.joinToString(" AND "), args.toTypedArray(), "date DESC, _id DESC", w.limit, w.offset) { cursor ->
                    val raw = cursor.getString(cursor.getColumnIndexOrThrow("body")).orEmpty()
                    JSONObject().put("number", cursor.text("address", 80)).put("body", raw.take(2_000))
                        .put("bodyTruncated", raw.length > 2_000).put("date", cursor.number("date"))
                        .put("type", cursor.number("type")).put("read", cursor.number("read") == 1L)
                }.put("since", w.since).put("until", w.until).put("coverage", "SMS provider only; not RCS or private messaging databases. Reuse this time window for subsequent pages.")
            }
        }
        else -> null
    }
    private fun restricted(context: Context, permission: String): JSONObject? = when {
        !selectedAssistant(context) -> nativeError("assistant_required", "Select Kompi as Android's default assistant before accessing call history or SMS.")
        !hasPermission(context, permission) -> missingPermission(if (permission == Manifest.permission.READ_SMS) "SMS" else "Call history")
        else -> null
    }
    private fun like(value: String) = "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
}

internal suspend fun readPage(context: Context, uri: Uri, projection: Array<String>, selection: String?, args: Array<String>?, order: String,
    limit: Int, offset: Int, map: (Cursor) -> JSONObject): JSONObject = withTimeout(8_000) {
    withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                val result = context.contentResolver.query(uri, projection, selection, args, order, signal)?.use { cursor ->
                    val items = JSONArray()
                    if (offset > 0) cursor.moveToPosition(offset - 1)
                    var more = false
                    while (continuation.isActive && cursor.moveToNext()) {
                        if (items.length() >= limit || !appendBounded(items, map(cursor))) { more = true; break }
                    }
                    nativePage(items, if (more) offset + items.length() else null)
                } ?: nativeError("provider_unavailable", "Android did not return a data cursor.")
                continuation.resume(result)
            } catch (error: Exception) { continuation.resumeWithException(error) }
        }
    }
}
internal fun Cursor.text(name: String, max: Int) = getString(getColumnIndexOrThrow(name)).orEmpty().take(max)
internal fun Cursor.number(name: String) = getLong(getColumnIndexOrThrow(name))
