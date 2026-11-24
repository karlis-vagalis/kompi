package com.kompi.app.transport

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import com.kompi.app.diagnostics.ChatTiming
import com.kompi.app.diagnostics.ChatTiming.Origin
import com.kompi.app.diagnostics.ChatTiming.Stage
import com.kompi.app.diagnostics.ResponsePerformance
import com.kompi.app.protocol.generated.OfferedArtifact
import com.kompi.app.protocol.generated.PiFrame
import com.kompi.app.protocol.generated.WireCodec
import com.kompi.app.attachments.MessageAttachment
import com.kompi.app.attachments.userMessagePresentation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A disposable, active-branch view of Pi. Nothing in this class writes a transcript to disk. */
class PiSessionStore(
    private val needSnapshot: () -> Unit,
    private val publicDelta: (requestId: String, sessionId: String, conversationId: String, sequence: Long, text: String) -> Unit,
    private val settled: (requestId: String, sessionId: String, conversationId: String, text: String) -> Unit,
    private val coalesceFrames: Boolean = false,
    private val thinkingTitle: (requestId: String, sessionId: String, conversationId: String, title: String) -> Unit = { _, _, _, _ -> },
    private val usingTools: (requestId: String, sessionId: String, conversationId: String) -> Unit = { _, _, _ -> },
    private val liveActivity: (requestId: String, sessionId: String, conversationId: String, activity: String) -> Unit = { _, _, _, _ -> },
) {
    class Details(private val value: JSONObject) {
        fun pretty(): String = value.toString(2)
        companion object {
            fun toolCalls(first: Details, remaining: List<Details>): Details = Details(
                JSONObject().put("toolCalls", JSONArray().apply {
                    put(first.value)
                    remaining.forEach { put(it.value) }
                }),
            )
        }
    }
    sealed interface Activity {
        data object Thinking : Activity
        data class ToolCall(val source: Details) : Activity
        data object Other : Activity
    }
    data class Line(val key: String, val text: String, val fromUser: Boolean, val activityKind: Activity? = null,
        val streaming: Boolean = false, val details: Details? = null,
        val body: String = text, val attachments: List<MessageAttachment> = emptyList(),
        val artifact: OfferedArtifact? = null) {
        val activity: Boolean get() = activityKind != null
    }
    data class View(val sessionId: String? = null, val conversationId: String? = null, val leafId: String? = null,
        val lines: List<Line> = emptyList(), val loading: Boolean = true, val representedRequests: Set<String> = emptySet(),
        val sessionName: String? = null, val snapshotRevision: Long = 0, val hostId: String? = null)
    private data class Entry(val id: String, val parentId: String?, val raw: JSONObject, val details: Details)
    private data class Record(val frame: PiFrame, val value: JSONObject, val connection: String?, val origin: Origin)
    private data class UpdateWindow(val request: String, val conversation: String, val epoch: String,
        val connection: String?, val origin: Origin, var last: ChatTiming.Time, var sawText: Boolean = false) {
        fun record(stage: Stage, at: ChatTiming.Time = ChatTiming.now()) = ChatTiming.record(stage, request,
            connection = connection, conversation = conversation, origin = origin, time = at)
    }
    private var timingUpdates: UpdateWindow? = null

    @Synchronized internal fun flushTimingUpdates(connection: String? = null) {
        val window = timingUpdates ?: return
        if (connection != null && window.connection != connection) return
        timingUpdates = null
        window.record(Stage.PhoneLastUpdateObserved, window.last)
    }
    private fun observeUpdate(record: Record, publicText: Boolean) {
        val f = record.frame
        val request = f.requestId ?: return
        val now = ChatTiming.now()
        val previous = timingUpdates
        val window = if (previous == null || previous.request != request || previous.epoch != f.epoch ||
            previous.connection != record.connection || previous.origin != record.origin) {
            flushTimingUpdates()
            UpdateWindow(request, f.conversationId, f.epoch, record.connection, record.origin, now).also {
                timingUpdates = it
                it.record(Stage.PhoneFirstUpdate, now)
            }
        } else previous
        window.last = now
        if (publicText && !window.sawText) { window.sawText = true; window.record(Stage.PhoneFirstText, now) }
    }
    private data class Source(val sessionId: String, val epoch: String)
    private data class Partial(val first: PiFrame, val text: StringBuilder = StringBuilder(), var next: Long = 0)
    private sealed interface LiveBlock {
        class Text(val text: StringBuilder = StringBuilder()) : LiveBlock
        class Thinking(val text: StringBuilder = StringBuilder(), val headings: ThinkingTitles = ThinkingTitles()) : LiveBlock {
            private var fallbackOffered = false
            fun offerFallback(): Boolean {
                if (fallbackOffered || headings.titles.isNotEmpty()) return false
                val content = text.toString().trimStart()
                // Give a possible first-line bold heading time to finish, but never wait for
                // an ordinary reasoning paragraph (or the entire thinking block) to end.
                val possibleHeading = content == "*" || (content.startsWith("**") && content.length <= 200 && '\n' !in content)
                if (possibleHeading || content.none { it.isLetterOrDigit() }) return false
                fallbackOffered = true
                return true
            }
        }
        class Tool(val id: String, val name: String, val arguments: StringBuilder = StringBuilder(), var final: JSONObject? = null) : LiveBlock
        class ToolTail(val arguments: StringBuilder = StringBuilder()) : LiveBlock
    }
    private enum class LiveOrigin { Started, ResumedSuffix }
    private class Live(val key: String, val requestId: String?, var raw: JSONObject, val origin: LiveOrigin = LiveOrigin.Started) {
        var complete = false
        val publicTextBlocks = mutableSetOf<Int>()
        val blocks = sortedMapOf<Int, LiveBlock>()
        fun update(event: JSONObject, usage: JSONObject?): List<String> {
            var titles = emptyList<String>()
            if (usage != null) raw.put("usage", usage)
            val index = event.optInt("contentIndex", -1)
            when (event.getString("type")) {
                "text_start" -> blocks[index] = LiveBlock.Text()
                "text_delta" -> ((if (origin == LiveOrigin.ResumedSuffix) blocks.getOrPut(index) { LiveBlock.Text() } else blocks.getValue(index)) as LiveBlock.Text).text.append(event.getString("delta"))
                "thinking_start" -> blocks[index] = LiveBlock.Thinking()
                "thinking_delta" -> {
                    val block = (if (origin == LiveOrigin.ResumedSuffix) blocks.getOrPut(index) { LiveBlock.Thinking() } else blocks.getValue(index)) as LiveBlock.Thinking
                    val delta = event.getString("delta")
                    block.text.append(delta)
                    titles = block.headings.append(delta)
                }
                "thinking_end" -> {
                    val block = (if (origin == LiveOrigin.ResumedSuffix) blocks.getOrPut(index) { LiveBlock.Thinking() } else blocks.getValue(index)) as LiveBlock.Thinking
                    val content = event.getString("content")
                    titles = block.headings.end(content)
                    block.text.clear(); block.text.append(content)
                }
                "toolcall_start" -> blocks[index] = LiveBlock.Tool(event.getString("id"), event.getString("toolName"))
                "toolcall_delta" -> {
                    val block = if (origin == LiveOrigin.ResumedSuffix) blocks.getOrPut(index) { LiveBlock.ToolTail() } else blocks.getValue(index)
                    when (block) { is LiveBlock.Tool -> block.arguments.append(event.getString("delta")); is LiveBlock.ToolTail -> block.arguments.append(event.getString("delta")); else -> error("Pi tool delta changed content kind") }
                }
                "toolcall_end" -> {
                    val call = event.getJSONObject("toolCall")
                    blocks[index] = LiveBlock.Tool(call.getString("id"), call.getString("name"), final = call)
                }
            }
            return titles
        }
        fun titles(index: Int): List<String> = (blocks.values.elementAt(index) as LiveBlock.Thinking).headings.titles
        fun message(): JSONObject {
            if (complete) return raw
            val result = JSONObject()
            raw.keys().forEach { if (it != "content") result.put(it, raw.get(it)) }
            val content = JSONArray()
            blocks.values.forEach { block -> content.put(when (block) {
                is LiveBlock.Text -> JSONObject().put("type", "text").put("text", block.text.toString())
                is LiveBlock.Thinking -> JSONObject().put("type", "thinking").put("thinking", block.text.toString())
                is LiveBlock.ToolTail -> JSONObject().put("type", "toolCallPartial").put("argumentsText", block.arguments.toString())
                is LiveBlock.Tool -> block.final ?: JSONObject().put("type", "toolCall").put("id", block.id).put("name", block.name).put("argumentsText", block.arguments.toString())
            }) }
            return result.put("content", content)
        }
    }
    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view
    private var hostId: String? = null
    @Synchronized internal fun bindHost(id: String) {
        if (id != hostId) {
            hostId = id
            clear()
        }
    }
    private var entries = linkedMapOf<String, Entry>()
    private var sessionName: String? = null
    private fun Entry.piName(): String? = raw.optString("name").trim().takeIf {
        raw.optString("type") == "session_info" && it.isNotEmpty()
    }
    private val renderKeys = mutableMapOf<String, String>()
    private val rowCache = mutableMapOf<String, List<Line>>()
    private var active: Live? = null
    private enum class ToolSpan { None, OneCall, Announced }
    private var toolSpan = ToolSpan.None
    private var toolSpanRequest: String? = null
    private val completedLive = mutableListOf<Live>()
    private val canonicalResponses = mutableMapOf<String, LinkedHashMap<String, String>>()
    private var canonicalSequence = -1L
    private var epoch: String? = null
    private var pendingSource: Source? = null
    private val retiredSources = mutableSetOf<Source>()
    private val settledRequests = mutableSetOf<String>()
    private var leaf: String? = null
    private var conversation: String? = null
    private var session: String? = null
    // Ledger updates never advance this transcript installation barrier.
    private var snapshotRevision = 0L
    private var installedSequence = -1L
    private var sequence = -1L
    private var waiting = true
    private var requested = false
    private val parts = mutableMapOf<String, Partial>()
    private val buffered = mutableListOf<Record>()
    private var bufferedChars = 0
    private val represented = mutableSetOf<String>()
    // Request identity, not text equality, owns the optimistic-to-canonical bubble handoff.
    private val userRequests = mutableMapOf<String, String>()
    private val speechSequences = mutableMapOf<String, Long>()
    private val main = Handler(Looper.getMainLooper())
    private var publicationScheduled = false
    private val maxRecordChars = minOf(32 * 1024 * 1024L, Runtime.getRuntime().maxMemory() / 8).toInt()

    /** Authorization reads the bound owner synchronously, never the deferred UI projection. */
    @Synchronized internal fun currentConversationId(): String? = conversation

    @Synchronized internal fun installedSnapshot(frame: PiFrame): Boolean =
        !waiting && conversation == frame.conversationId && session == frame.sessionId &&
            epoch == frame.epoch && installedSequence == frame.sequence

    /** Requesting the base again on the same connection must not discard a first
     * turn already buffered behind that base. A real reconnect still drops the suffix. */
    @Synchronized fun beginSnapshot() {
        if (!waiting) reconnect()
        else parts.entries.removeAll { it.value.first.kind == "snapshot" }
        requested = true
    }

    @Synchronized fun reconnect() {
        flushTimingUpdates()
        waiting = true; requested = false; pendingSource = null; parts.clear(); buffered.clear(); bufferedChars = 0
        active = null; completedLive.clear()
        toolSpan = ToolSpan.None; toolSpanRequest = null
        _view.value = _view.value.copy(loading = true)
    }
    @Synchronized fun clear() {
        flushTimingUpdates()
        entries.clear(); sessionName = null; rowCache.clear(); renderKeys.clear(); active = null; epoch = null; leaf = null
        conversation = null; session = null; sequence = -1; represented.clear(); userRequests.clear()
        pendingSource = null; retiredSources.clear(); settledRequests.clear()
        toolSpan = ToolSpan.None; toolSpanRequest = null
        parts.clear(); buffered.clear(); bufferedChars = 0; speechSequences.clear(); completedLive.clear(); canonicalResponses.clear(); canonicalSequence = -1L
        waiting = false; requested = false
        publish()
    }
    @Synchronized fun bindConversation(id: String?) {
        if (id == null) { clear(); return }
        if (id != conversation) {
            clear(); conversation = id; waiting = true
        }
        if (waiting && !requested) { requested = true; needSnapshot() }
        publish()
    }
    @Synchronized fun resetRequired() {
        reconnect()
        requested = true
        needSnapshot()
    }
    @Synchronized fun accept(frame: PiFrame, timingConnection: String? = null, allowUnobservedPrefix: Boolean = false) {
        if (conversation != null && frame.conversationId != conversation) return
        val key = "${frame.epoch}:${frame.kind}:${frame.sequence}"
        // Selecting a conversation may join in the middle of an already in-flight record.
        // Its suffix is not a complete record; the new scoped snapshot recovers the gap.
        if (allowUnobservedPrefix && frame.part != 0L && key !in parts) return
        if (conversation == null) { conversation = frame.conversationId; waiting = true }
        val source = Source(frame.sessionId, frame.epoch)
        if (source in retiredSources) return
        val installed = session?.let { id -> epoch?.let { Source(id, it) } }
        val expected = pendingSource ?: installed
        if (expected != null && source != expected) {
            // A snapshot may replace a source only at an explicit synchronization boundary.
            if (frame.kind == "snapshot" && (!waiting || pendingSource != null)) return
            retiredSources.add(expected)
            reconnect()
            pendingSource = source
            if (frame.kind != "snapshot") { requested = true; needSnapshot() }
        } else if (waiting && pendingSource == null) {
            pendingSource = source
        }
        if (frame.kind == "snapshot" && source == installed &&
            (frame.sequence < maxOf(sequence, canonicalSequence) || (!waiting && frame.sequence == maxOf(sequence, canonicalSequence)))) {
            parts.remove(key)
            if (waiting) { requested = true; needSnapshot() }
            return
        }
        val partial = parts.getOrPut(key) {
            require(frame.part == 0L && parts.size < 4) { "Pi transfer is not contiguous" }
            Partial(frame)
        }
        require(frame.part == partial.next && frame.sessionId == partial.first.sessionId && frame.requestId == partial.first.requestId) { "Pi fragment identity changed" }
        require(partial.text.length.toLong() + frame.json.length <= maxRecordChars) { "Pi session record exceeds this phone's display capacity" }
        partial.text.append(frame.json); partial.next++
        if (!frame.last) return
        parts.remove(key)
        val origin = if (waiting) Origin.Catchup else Origin.Live
        val record = Record(frame, JSONObject(partial.text.toString()), timingConnection, origin)
        if (frame.kind == "snapshot") {
            ChatTiming.record(Stage.PhoneSnapshotReceived, conversation = frame.conversationId,
                connection = timingConnection, origin = Origin.History)
            install(record)
            ChatTiming.record(Stage.PhoneSnapshotProjected, conversation = frame.conversationId,
                connection = timingConnection, origin = Origin.History)
            return
        }
        if (record.value.optString("type") == "message_update") {
            val update = record.value.optJSONObject("assistantMessageEvent")
            val text = update?.optString("type") == "text_delta" && update.optString("delta").isNotEmpty()
            observeUpdate(record, text)
            if (text) frame.requestId?.let { ResponsePerformance.delta(it, frame.sequence) }
        }
        if (frame.kind == "settled") {
            flushTimingUpdates(timingConnection)
            ChatTiming.record(Stage.PhoneSettledReceived, request = frame.requestId, conversation = frame.conversationId,
                connection = timingConnection, origin = origin)
        }
        if (waiting) {
            require(bufferedChars.toLong() + partial.text.length <= maxRecordChars) { "Pi resynchronization buffer exceeded capacity" }
            buffered.add(record); bufferedChars += partial.text.length
            if (!requested) { requested = true; needSnapshot() }
        } else apply(record)
    }
    private fun parseEntry(raw: JSONObject): Entry {
        val id = raw.getString("id")
        require(id.isNotBlank()) { "Pi entry has no identity" }
        return Entry(id, if (raw.isNull("parentId")) null else raw.getString("parentId"), raw, Details(raw))
    }
    private fun install(record: Record) {
        val source = record.value.getJSONArray("entries")
        val fresh = linkedMapOf<String, Entry>()
        for (i in 0 until source.length()) {
            val entry = parseEntry(source.getJSONObject(i))
            require(fresh.put(entry.id, entry) == null) { "Duplicate Pi entry identity" }
        }
        val snapshotLeaf = if (record.value.isNull("leafId")) null else record.value.getString("leafId")
        require(snapshotLeaf == null || snapshotLeaf in fresh) { "Pi snapshot has an unknown active leaf" }
        entries = fresh; sessionName = fresh.values.asSequence().mapNotNull { it.piName() }.lastOrNull(); rowCache.clear(); renderKeys.clear(); active = null; completedLive.clear()
        if (session != record.frame.sessionId || epoch != record.frame.epoch) canonicalResponses.clear()
        installedSequence = record.frame.sequence
        snapshotRevision++
        canonicalSequence = record.frame.sequence
        session = record.frame.sessionId; conversation = record.frame.conversationId
        epoch = record.frame.epoch; sequence = record.frame.sequence; leaf = snapshotLeaf
        waiting = false; requested = false; pendingSource = null
        val pending = buffered.toList(); buffered.clear(); bufferedChars = 0
        for (event in pending) {
            if (event.frame.epoch == epoch && event.frame.sessionId == session &&
                (event.frame.kind != "event" || event.frame.sequence > sequence)) apply(event)
            if (waiting) break
        }
        publish()
    }
    private fun apply(record: Record) {
        val f = record.frame
        if (f.epoch != epoch || f.sessionId != session) return
        if (f.kind != "event") { applyEntries(record); return }
        if (f.sequence <= sequence) return
        if (f.sequence != sequence + 1) { resetRequired(); return }
        sequence = f.sequence
        val event = record.value
        when (event.optString("type")) {
            "entry_appended" -> {
                val entry = parseEntry(event.getJSONObject("entry"))
                if (entries.putIfAbsent(entry.id, entry) == null) entry.piName()?.let { sessionName = it }
                if (f.sequence >= canonicalSequence) { leaf = entry.id; canonicalSequence = f.sequence }
                val message = entry.raw.optJSONObject("message")
                if (message?.optString("role") == "assistant") {
                    if (f.sequence >= canonicalSequence && active?.requestId == f.requestId) {
                        active?.let { renderKeys[entry.id] = it.key }
                        active = null
                    }
                    ResponsePerformance.bindEntry(entryKey(entry.id), f.requestId)
                }
                if (message?.optString("role") == "user") f.requestId?.let { id ->
                    if (entry.id !in userRequests && id !in userRequests.values) {
                        represented.add(id); userRequests[entry.id] = id; rowCache.remove(entry.id)
                    }
                }
            }
            "message_start" -> {
                val message = event.optJSONObject("message")
                if (message?.optString("role") == "assistant") {
                    val key = "pi-live:${f.sessionId}:${f.epoch}:${f.sequence}"
                    active = Live(key, f.requestId, message)
                    ResponsePerformance.bindEntry(key, f.requestId)
                    f.requestId?.let { ChatTiming.bindResponse(key, it, f.conversationId, record.connection, record.origin) }
                }
            }
            "message_update" -> {
                val update = event.optJSONObject("assistantMessageEvent")
                if (update != null) {
                    if (active == null) {
                        val key = "pi-live:${f.sessionId}:${f.epoch}:${f.sequence}"
                        active = Live(key, f.requestId, JSONObject().put("role", "assistant"), LiveOrigin.ResumedSuffix)
                        android.util.Log.i("KompiSync", "phase=pi_live_suffix_resumed")
                        ResponsePerformance.bindEntry(key, f.requestId)
                        f.requestId?.let { ChatTiming.bindResponse(key, it, f.conversationId, record.connection, Origin.Catchup) }
                    }
                    val titles = active!!.update(update, event.optJSONObject("usage"))
                    if (update.getString("type") in setOf("thinking_start", "toolcall_start")) f.requestId?.let { id ->
                        com.kompi.app.diagnostics.SpeechAudit.record(id, stage = "pi_activity_observed",
                            detail = "kind=${update.getString("type")} origin=${active!!.origin.name}")
                    }
                    // Only newly observed live headings may speak; snapshots/history stay silent.
                    if (active!!.origin == LiveOrigin.Started) f.requestId?.let { id ->
                        when (update.getString("type")) {
                            "thinking_start" -> liveActivity(id, f.sessionId, f.conversationId, "Thinking")
                            "toolcall_start" -> liveActivity(id, f.sessionId, f.conversationId, "Tool: ${update.getString("toolName").take(256)}")
                        }
                        titles.forEach { thinkingTitle(id, f.sessionId, f.conversationId, it) }
                        if (update.getString("type") == "thinking_delta") {
                            val block = active!!.blocks[update.getInt("contentIndex")] as LiveBlock.Thinking
                            if (block.offerFallback()) thinkingTitle(id, f.sessionId, f.conversationId, "thinking")
                        }
                        if (update.getString("type") == "thinking_end") {
                            val block = active!!.blocks[update.getInt("contentIndex")] as LiveBlock.Thinking
                            if (block.headings.titles.isEmpty()) com.kompi.app.diagnostics.SpeechAudit.record(id,
                                stage = "activity_skipped", detail = if (block.text.isBlank()) "empty_thinking_block" else "completed_thinking_no_new_filler")
                        }
                        if (toolSpanRequest != id) {
                            toolSpanRequest = id
                            toolSpan = ToolSpan.None
                        }
                        when (update.getString("type")) {
                            "text_start" -> toolSpan = ToolSpan.None
                            "toolcall_start" -> toolSpan = when (toolSpan) {
                                ToolSpan.None -> ToolSpan.OneCall
                                ToolSpan.OneCall -> {
                                    usingTools(id, f.sessionId, f.conversationId)
                                    ToolSpan.Announced
                                }
                                ToolSpan.Announced -> ToolSpan.Announced
                            }
                        }
                    }
                }
                if (update?.optString("type") == "text_delta" && update.getString("delta").isNotEmpty()) {
                    val text = update.optString("delta")
                    f.requestId?.let { id ->
                        var next = speechSequences[id] ?: 0L
                        // Match canonical text-block separators, never thinking/tool-only messages.
                        if (active!!.publicTextBlocks.add(update.getInt("contentIndex")) && id in speechSequences) {
                            publicDelta(id, f.sessionId, f.conversationId, next++, "\n")
                        }
                        publicDelta(id, f.sessionId, f.conversationId, next++, text)
                        speechSequences[id] = next
                    }
                }
            }
            "message_end" -> {
                val message = event.optJSONObject("message")
                if (message?.optString("role") == "assistant") active?.let { live ->
                    live.raw = message; live.complete = true
                    completedLive.add(live)
                }
            }
            "session_changed" -> { resetRequired(); return }
            "branch_changed" -> if (f.sequence >= canonicalSequence) {
                leaf = if (event.isNull("leafId")) null else event.getString("leafId")
                canonicalSequence = f.sequence
                active = null; completedLive.clear()
            }
            "agent_settled" -> Unit // Canonical settled entries complete the phone view.
        }
        schedulePublication()
    }
    private fun applyEntries(record: Record) {
        val f = record.frame
        if (f.epoch != epoch || f.sessionId != session) return
        val source = record.value.getJSONArray("entries")
        val submittedUserId = (source.length() - 1 downTo 0).firstNotNullOfOrNull { index ->
            source.getJSONObject(index).takeIf { it.optJSONObject("message")?.optString("role") == "user" }?.getString("id")
        }
        for (i in 0 until source.length()) {
            val entry = parseEntry(source.getJSONObject(i))
            if (entries.putIfAbsent(entry.id, entry) == null) entry.piName()?.let { sessionName = it }
            val message = entry.raw.optJSONObject("message")
            if (message?.optString("role") == "user") f.requestId?.let { id ->
                if (entry.id == submittedUserId && entry.id !in userRequests && id !in userRequests.values) {
                    represented.add(id); userRequests[entry.id] = id; rowCache.remove(entry.id)
                }
            }
            if (message?.optString("role") == "assistant" && f.requestId != null) {
                val live = completedLive.firstOrNull { it.requestId == f.requestId }
                    ?: active?.takeIf { it.requestId == f.requestId }
                if (live != null) {
                    renderKeys[entry.id] = live.key; rowCache.remove(entry.id)
                    completedLive.remove(live)
                    if (active === live) active = null
                }
                ResponsePerformance.bindEntry(entryKey(entry.id), f.requestId)
                val key = renderKeys[entry.id] ?: entryKey(entry.id)
                ChatTiming.bindResponse(key, f.requestId, f.conversationId, record.connection, record.origin)
                val publicText = contentText(message.opt("content"))
                if (publicText.isNotBlank() && f.requestId !in settledRequests) {
                    canonicalResponses.getOrPut(f.requestId) { linkedMapOf() }[entry.id] = publicText
                }
            }
        }
        if (f.sequence >= canonicalSequence) {
            leaf = if (record.value.isNull("leafId")) null else record.value.getString("leafId")
            canonicalSequence = f.sequence
        }
        schedulePublication()
        if (f.kind == "settled") f.requestId?.takeIf(settledRequests::add)?.let { id ->
            ResponsePerformance.finish(id)
            settled(id, f.sessionId, f.conversationId, canonicalResponses.remove(id)?.values?.joinToString("\n").orEmpty())
            speechSequences.remove(id)
        }
    }

    private fun entryKey(id: String) = "pi:$session:$id"
    private fun contentText(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> (0 until content.length()).mapNotNull { index ->
            val block = content.optJSONObject(index) ?: return@mapNotNull null
            when (block.optString("type")) {
                "text" -> block.optString("text")
                "image" -> "[Image: ${block.optString("mimeType", "image")}]"
                else -> null
            }
        }.joinToString("\n")
        else -> ""
    }
    private fun assistantRows(key: String, message: JSONObject, details: Details, firstKey: String, streaming: Boolean,
        liveTitles: ((Int) -> List<String>)? = null, resumedSuffix: Boolean = false): List<Line> = buildList {
        val text = StringBuilder()
        var textPart = 0
        fun flush() {
            if (text.isNotBlank()) {
                val partKey = if (textPart++ == 0) firstKey else "$key:text:$textPart"
                if (partKey == firstKey) ChatTiming.responseProjected(partKey, resumedSuffix)
                ResponsePerformance.bindEntry(partKey, ResponsePerformance.requestFor(key))
                add(Line(partKey, text.toString(), false, streaming = streaming, details = details))
            }
            text.clear()
        }
        when (val content = message.opt("content")) {
            is String -> text.append(content)
            is JSONArray -> for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                when (block.optString("type")) {
                    "text" -> { if (text.isNotEmpty()) text.append("\n"); text.append(block.optString("text")) }
                    "thinking" -> {
                        flush()
                        val titles = liveTitles?.invoke(i) ?: ThinkingTitles.from(block.optString("thinking"))
                        if (titles.isEmpty()) add(Line("$key:thinking:$i", "Thinking", false, activityKind = Activity.Thinking, details = Details(block)))
                        else titles.forEachIndexed { index, title ->
                            add(Line("$key:thinking:$i:$index", title, false, activityKind = Activity.Thinking, details = Details(block)))
                        }
                    }
                    "toolCall", "toolCallPartial" -> {
                        flush()
                        val source = Details(block)
                        val label = when {
                            block.optString("name") == "android_offer_file" -> "Preparing file offer"
                            block.optString("type") == "toolCall" -> "Tool: ${block.optString("name")}"
                            else -> "Tool call · partial"
                        }
                        add(Line("$key:tool:$i", label, false, activityKind = Activity.ToolCall(source), details = source))
                    }
                }
            }
        }
        flush()
        val error = message.optString("errorMessage")
        if (error.isNotBlank()) add(Line("$key:error", error, false, activityKind = Activity.Other, details = details))
    }

    private fun rows(entry: Entry): List<Line> = rowCache.getOrPut(entry.id) {
        val key = entryKey(entry.id)
        val message = entry.raw.optJSONObject("message")
        if (entry.raw.optString("type") != "message" || message == null) {
            emptyList()
        } else when (message.optString("role")) {
            "user" -> {
                val presentation = userMessagePresentation(message.opt("content"))
                listOf(Line(userRequests[entry.id]?.let { "user:$it" } ?: key, contentText(message.opt("content")), true, details = entry.details,
                    body = presentation.body, attachments = presentation.attachments))
            }
            "assistant" -> assistantRows(key, message, entry.details, renderKeys[entry.id] ?: key, false)
            "toolResult" -> if (message.optString("toolName") == "android_offer_file") {
                val metadata = message.optJSONObject("details")?.optJSONObject("kompiArtifactOffer")
                val offer = metadata?.let { runCatching { WireCodec.parseOfferedArtifact(it.toString()) }.getOrNull() }
                when {
                    message.optBoolean("isError") -> listOf(Line(key, "Couldn’t offer the file", false, activityKind = Activity.Other, details = entry.details))
                    offer != null -> listOf(Line(key, offer.fileName, false, artifact = offer))
                    else -> emptyList()
                }
            } else emptyList()
            else -> emptyList()
        }
    }
    /** Presentation-only spans: only consecutive visible tool calls share a group. */
    private fun compactToolCalls(lines: List<Line>): List<Line> {
        class Run(val position: Int, val first: Details, val remaining: MutableList<Details> = mutableListOf())
        val compact = mutableListOf<Line>()
        var run: Run? = null
        fun finishRun() {
            run?.let { current ->
                if (current.remaining.isNotEmpty()) {
                    val label = "${current.remaining.size + 1} tool calls"
                    compact[current.position] = compact[current.position].copy(
                        text = label, body = label, details = Details.toolCalls(current.first, current.remaining),
                    )
                }
            }
            run = null
        }
        for (line in lines) {
            when (val kind = line.activityKind) {
                is Activity.ToolCall -> {
                    val current = run
                    if (current == null) {
                        run = Run(compact.size, kind.source)
                        compact.add(line)
                    } else current.remaining.add(kind.source)
                }
                else -> { finishRun(); compact.add(line) }
            }
        }
        finishRun()
        return compact
    }

    private fun schedulePublication() {
        if (!coalesceFrames) { publish(); return }
        if (publicationScheduled) return
        publicationScheduled = true
        main.post { Choreographer.getInstance().postFrameCallback { synchronized(this) { publicationScheduled = false; publish() } } }
    }
    private fun publish() {
        val chain = mutableListOf<Entry>()
        val seen = mutableSetOf<String>()
        var next = leaf
        while (next != null) {
            require(seen.add(next)) { "Pi active branch contains a cycle" }
            val entry = entries[next] ?: break
            chain.add(entry); next = entry.parentId
        }
        val lines = chain.asReversed().flatMap(::rows).toMutableList()
        active?.let { live ->
            val message = live.message()
            if (live.origin == LiveOrigin.ResumedSuffix) lines.add(Line(
                "${live.key}:partial", "Partial response — earlier content is unavailable", false, activityKind = Activity.Other,
            ))
            lines.addAll(assistantRows(live.key, message, Details(message), live.key, !live.complete,
                if (live.complete) null else live::titles, live.origin == LiveOrigin.ResumedSuffix))
        }
        _view.value = View(session, conversation, leaf, compactToolCalls(lines), waiting, represented.toSet(), sessionName, snapshotRevision, hostId)
    }
}
