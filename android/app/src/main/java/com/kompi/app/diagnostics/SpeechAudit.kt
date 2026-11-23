package com.kompi.app.diagnostics

import android.content.Context
import android.database.Cursor
import android.os.SystemClock
import org.json.JSONObject
import java.security.MessageDigest

/** Hidden five-minute diagnostic metadata. Speech text remains solely in the Pi transcript. */
object SpeechAudit {
    sealed interface Cue {
        data object Thinking : Cue
        data object Tools : Cue
        data class Title(val ordinal: Int, val fingerprint: String) : Cue
    }
    sealed interface Source {
        data class Reply(val start: Int, val end: Int, val fingerprint: String) : Source
        data class Activity(val cue: Cue, val prefixFingerprint: String) : Source
    }
    data class Entry(val elapsedMs: Long, val epochMs: Long, val requestId: String, val speechId: String,
        val sequence: Long?, val stage: String, val detail: String, val source: Source?)
    private data class Binding(val requestId: String, val source: Source?, val at: Long)
    @Volatile private lateinit var storage: SpeechAuditStore
    private val sessions = linkedMapOf<String, Pair<String, String>>()
    private val bindings = linkedMapOf<Pair<String, Long>, Binding>()
    private const val RETENTION_MS = 5 * 60 * 1_000L
    private const val MAX_ENTRIES = 4096

    @Synchronized fun initialize(context: Context) {
        if (!::storage.isInitialized) {
            storage = SpeechAuditStore(context)
            record("audit", stage = "audit_started", detail = "retention_ms=300000 max_events=$MAX_ENTRIES")
        }
    }

    fun query(requestId: String?): Cursor = storage.query(requestId)
    fun status(): Cursor = storage.status()

    @Synchronized fun bindSession(requestId: String, sessionId: String, conversationId: String) {
        val reference = sessionId to conversationId
        if (sessions[requestId] == reference) return
        sessions[requestId] = reference
        while (sessions.size > 128) sessions.remove(sessions.keys.first())
        record(requestId, stage = "session_bound", detail = "session=$sessionId conversation=$conversationId")
    }

    internal fun sourceJson(source: Source): String = when (source) {
        is Source.Reply -> JSONObject().put("kind", "reply").put("start", source.start)
            .put("end", source.end).put("fingerprint", source.fingerprint)
        is Source.Activity -> JSONObject().put("kind", "activity").put("prefixFingerprint", source.prefixFingerprint).apply {
            when (val cue = source.cue) {
                Cue.Thinking -> { put("cue", "thinking"); put("wordingVersion", 1) }
                Cue.Tools -> { put("cue", "using_tools"); put("wordingVersion", 1) }
                is Cue.Title -> { put("cue", "title"); put("ordinal", cue.ordinal); put("titleFingerprint", cue.fingerprint) }
            }
        }
    }.toString()

    fun fingerprint(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun normalize(text: String): Pair<String, List<Int>> {
        val words = StringBuilder()
        val offsets = mutableListOf<Int>()
        var separator = -1
        text.forEachIndexed { index, character ->
            if (character.isLetterOrDigit()) {
                if (separator >= 0 && words.isNotEmpty()) { words.append(' '); offsets.add(separator) }
                separator = -1
                words.append(character.lowercaseChar()); offsets.add(index)
            } else if (separator < 0) separator = index
        }
        return words.toString() to offsets
    }

    /** Match actual admitted words back to the existing transcript, without retaining another text copy. */
    fun replySource(text: String, phrase: String, from: Int): Source.Reply? {
        val (normalized, offsets) = normalize(text)
        val wanted = normalize(phrase).first
        if (wanted.isEmpty()) return null
        val start = offsets.indexOfFirst { it >= from }.takeIf { it >= 0 } ?: return null
        val found = normalized.indexOf(wanted, start)
        if (found < 0) return null
        return Source.Reply(offsets[found], offsets[found + wanted.length - 1] + 1, fingerprint(wanted))
    }

    @Synchronized fun bind(speechId: String, sequence: Long, requestId: String, source: Source?) {
        val now = SystemClock.elapsedRealtime()
        prune(now)
        bindings[speechId to sequence] = Binding(requestId, source, now)
        while (bindings.size > MAX_ENTRIES) bindings.remove(bindings.keys.first())
    }

    @Synchronized fun record(speechId: String, sequence: Long? = null, stage: String, detail: String = "", source: Source? = null) {
        val now = SystemClock.elapsedRealtime()
        prune(now)
        val binding = sequence?.let { bindings[speechId to it] }
        storage.append(Entry(now, System.currentTimeMillis(), binding?.requestId ?: speechId, speechId,
            sequence, stage, detail, source ?: binding?.source))
        android.util.Log.i("KompiSpeechAudit", "t=$now request=${binding?.requestId ?: speechId} speech=$speechId seq=$sequence stage=$stage detail=$detail")
    }

    private fun prune(now: Long) {
        bindings.entries.removeAll { now - it.value.at > RETENTION_MS }
    }
}
