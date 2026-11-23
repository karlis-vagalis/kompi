package com.kompi.app.stream

import com.kompi.app.audio.KokoroPlaybackPhase
import com.kompi.app.diagnostics.SpeechAudit

/** Bounded activity audio: real headings outrank unstarted generic filler; replies fence new filler. */
class ActivitySpeech(
    private val speak: (String, String) -> Boolean,
    private val stop: (String) -> Unit,
    private val newId: () -> String = { "activity-${java.util.UUID.randomUUID()}" },
) {
    enum class Gate { Open, Muted, NotForeground, OtherConversation, ReplyAudioPending, OtherAudioPending, VoiceNotReady }
    data class PlaybackOwner(val requestId: String, val title: String)
    private enum class Kind { GenericThinking, Title, Tools }
    private data class Accepted(val owner: PlaybackOwner, val kind: Kind, var playbackStarted: Boolean = false)
    private val accepted = linkedMapOf<String, Accepted>()
    private val requests = LinkedHashMap<String, String?>()
    private val retired = linkedSetOf<String>()
    private val titleCounts = mutableMapOf<String, Int>()
    private val replyTextSeen = linkedSetOf<String>()

    /** Only newly submitted foreground requests are eligible; recovered work stays silent. */
    fun begin(requestId: String) {
        if (requestId in retired || requestId in requests) return
        requests[requestId] = null
        titleCounts[requestId] = 0
        SpeechAudit.record(requestId, stage = "activity_eligible")
        while (requests.size > 64) {
            val first = requests.keys.first()
            requests.remove(first); titleCounts.remove(first)
        }
    }

    fun offer(requestId: String, activity: String, prefix: String, gate: Gate, allowTools: Boolean = false) {
        if (activity == "Thinking") {
            // A start event proves activity, not the absence of a heading. Display it immediately,
            // but classify speech from actual streamed content rather than a speculative timer.
            SpeechAudit.record(requestId, stage = "thinking_started", detail = "awaiting_live_content")
            return
        }
        if (activity == "Using tools" && !allowTools) {
            SpeechAudit.record(requestId, stage = "activity_skipped", detail = "tools_not_live")
            return
        }
        val isTools = allowTools && activity == "Using tools"
        val title = if (isTools) "using tools" else activity.takeIf { it.startsWith("Thinking: ") }
            ?.removePrefix("Thinking: ")?.trim() ?: return
        val kind = when {
            isTools -> Kind.Tools
            title == "thinking" -> Kind.GenericThinking
            else -> Kind.Title
        }
        val ordinal = if (!isTools && requestId in requests)
            titleCounts.getValue(requestId).also { titleCounts[requestId] = it + 1 } else null
        val cue = when (kind) {
            Kind.Tools -> SpeechAudit.Cue.Tools
            Kind.GenericThinking -> SpeechAudit.Cue.Thinking
            Kind.Title -> ordinal?.let { SpeechAudit.Cue.Title(it, SpeechAudit.fingerprint(title)) }
        }
        val source = cue?.let { SpeechAudit.Source.Activity(it, SpeechAudit.fingerprint(prefix.trim().take(39))) }
        fun report(outcome: String) {
            SpeechAudit.record(requestId, stage = "activity_offer", detail = "kind=${kind.name} outcome=$outcome", source = source)
        }
        if (requestId !in requests) { report("ineligible"); return }
        if (title.isEmpty() || title.length > 160 || title.any { it == '\n' || it == '\r' }) return
        if (kind == Kind.Title) discardUnstartedGeneric(requestId, "replaced_by_real_heading")
        if (gate != Gate.Open) { report("skipped_${gate.name}"); return }
        if (kind == Kind.GenericThinking && requestId in replyTextSeen) { report("reply_text_already_received"); return }
        if (!isTools && requests[requestId] == title) { report("duplicate"); return }
        // One tool cue may follow an admitted thinking readout, never a backlog of stale headings.
        if (accepted.isNotEmpty() && (!isTools || accepted.size >= 2 || accepted.values.any { it.kind == Kind.Tools })) {
            report("activity_busy")
            return
        }
        val speechId = newId()
        val spokenSuffix = when (kind) {
            Kind.GenericThinking -> ActivityCueText.Thinking
            Kind.Tools -> ActivityCueText.Tools
            Kind.Title -> title
        }
        val phrase = listOf(prefix.trim().take(39), spokenSuffix).filter { it.isNotEmpty() }.joinToString(" ")
        SpeechAudit.bind(speechId, 0, requestId, source)
        if (phrase.any { it.isLetterOrDigit() } && speak(speechId, phrase)) {
            accepted[speechId] = Accepted(PlaybackOwner(requestId, spokenSuffix), kind)
            if (!isTools) requests[requestId] = title
            report("accepted")
        } else report("engine_unavailable")
    }

    private fun discardUnstartedGeneric(requestId: String, reason: String) {
        val ids = accepted.filterValues {
            it.owner.requestId == requestId && it.kind == Kind.GenericThinking && !it.playbackStarted
        }.keys.toList()
        ids.forEach { speechId ->
            accepted.remove(speechId)
            SpeechAudit.record(speechId, 0, "activity_discarded", reason)
            stop(speechId)
        }
        if (ids.isNotEmpty() && requests[requestId] == "thinking") requests[requestId] = null
    }

    fun onReplyText(requestId: String) {
        replyTextSeen += requestId
        while (replyTextSeen.size > 64) replyTextSeen.remove(replyTextSeen.first())
        discardUnstartedGeneric(requestId, "reply_text_arrived_before_generic_playback")
    }

    /** Explicit interruption cancels all activity audio, including audible headings and tool cues. */
    fun interrupt(requestId: String? = null) {
        val ids = accepted.filterValues { requestId == null || it.owner.requestId == requestId }.keys.toList()
        ids.forEach { speechId -> accepted.remove(speechId); stop(speechId) }
    }

    fun playbackOwner(phase: KokoroPlaybackPhase): PlaybackOwner? {
        val speechId = when (phase) {
            is KokoroPlaybackPhase.Preparing -> phase.requestId
            is KokoroPlaybackPhase.Playing -> phase.requestId
            is KokoroPlaybackPhase.Draining -> phase.requestId
            KokoroPlaybackPhase.Idle -> null
        }
        val current = accepted[speechId] ?: return null
        if (phase is KokoroPlaybackPhase.Playing || phase is KokoroPlaybackPhase.Draining) current.playbackStarted = true
        return current.owner
    }

    fun onPlaybackDrained(speechId: String) { accepted.remove(speechId) }

    /** Unstarted generic filler yields; admitted real headings/tools and already-playing cues finish. */
    fun complete(requestId: String) {
        discardUnstartedGeneric(requestId, "request_completed_before_generic_playback")
        requests.remove(requestId)
        titleCounts.remove(requestId)
        SpeechAudit.record(requestId, stage = "activity_closed",
            detail = "admitted_audio_remaining=${accepted.values.count { it.owner.requestId == requestId }}")
        retired += requestId
        while (retired.size > 64) retired.remove(retired.first())
    }

    fun retire(requestId: String) { interrupt(requestId); complete(requestId) }
    fun invalidate() { interrupt(); requests.keys.toList().forEach(::retire) }
}
