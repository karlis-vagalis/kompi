package com.kompi.app.stream

import java.util.Locale

/** Content-free reasons for a speech lifecycle transition. */
enum class SpeechStreamSignal { SequenceGap, CanonicalReplacement, EnqueueRejected, UnspeakableToken, Cancelled, Invalidated, Finished }

/**
 * Correlates public assistant deltas into one canonical reply and small, ordered speech phrases.
 * Wire sequence gaps stop that turn's queued audio; the final server text still repairs its bubble.
 */
class AssistantSpeechStream(
    private val speak: (String, Long, String) -> Boolean,
    private val stop: (String?) -> Unit,
    private val onTextUpdate: (String, String, String) -> Unit = { _, _, _ -> },
    private val onDiagnostic: (String, SpeechStreamSignal) -> Unit = { _, _ -> },
    private val speechLocale: () -> Locale = { Locale.getDefault() },
    private val hasQueuedAudio: (String) -> Boolean = { false },
) {
    private data class Turn(
        val requestId: String,
        val conversationId: String,
        val planner: SpeechChunkPlanner,
        var bufferStartedAtNanos: Long = 0,
        var nextSequence: Long = 0,
        val text: StringBuilder = StringBuilder(),
        val phraseBuffer: StringBuilder = StringBuilder(),
        var speechSequence: Long = 0,
        var auditSourceEnd: Int = 0,
        var inCodeFence: Boolean = false,
        var pendingFence: String = "",
        var terminal: Boolean = false,
        var gapDetected: Boolean = false,
        var speechSuppressed: Boolean = false,
    )

    private val turns = LinkedHashMap<String, Turn>()
    // Tombstones are never evicted into permission to speak. At the bounded ownership budget,
    // fail closed for new speech rather than allowing a retired request to become fresh again.
    private val retiredRequests = linkedSetOf<String>()
    private var ownershipBudgetExhausted = false
    private var outputMuted = false
    private var generation = 0L

    @Synchronized
    fun accept(requestId: String, conversationId: String, sequence: Long, text: String, token: Long = generation): Boolean {
        if (token != generation || requestId in retiredRequests || requestId.isBlank() || conversationId.isBlank() || sequence < 0 || text.isEmpty()) return false
        if (requestId !in turns && ownershipBudgetExhausted) {
            onDiagnostic(requestId, SpeechStreamSignal.EnqueueRejected)
            return false
        }
        val turn = turns.getOrPut(requestId) { Turn(requestId, conversationId, SpeechChunkPlanner(speechLocale()), speechSuppressed = outputMuted) }
        if (turn.terminal || turn.conversationId != conversationId || sequence < turn.nextSequence) return false
        if (sequence != turn.nextSequence) {
            turn.terminal = true
            turn.gapDetected = true
            onDiagnostic(requestId, SpeechStreamSignal.SequenceGap)
            stop(requestId)
            return false
        }
        if (turn.nextSequence == 0L) com.kompi.app.diagnostics.SpeechAudit.record(requestId, stage = "first_text_received")
        turn.nextSequence++
        turn.text.append(text)
        if (turn.text.length > MAX_REPLY_CHARS) turn.text.setLength(MAX_REPLY_CHARS)
        onTextUpdate(requestId, conversationId, turn.text.toString())
        if (!turn.speechSuppressed) {
            appendSpeakable(turn, text)
            emitCompletePhrases(turn, flush = false)
        }
        return true
    }

    /** The final result's canonical public text is the single source of truth for the bubble. */
    @Synchronized
    fun finish(requestId: String, conversationId: String, canonicalText: String, token: Long = generation) {
        if (token != generation || requestId in retiredRequests) return
        com.kompi.app.diagnostics.SpeechAudit.record(requestId, stage = "reply_completed")
        val canonical = canonicalText.take(MAX_REPLY_CHARS)
        val turn = turns[requestId]
        if (turn == null) {
            if (ownershipBudgetExhausted) { onDiagnostic(requestId, SpeechStreamSignal.EnqueueRejected); return }
            val fresh = Turn(requestId, conversationId, SpeechChunkPlanner(speechLocale()), speechSuppressed = outputMuted)
            turns[requestId] = fresh
            fresh.text.append(canonical)
            onTextUpdate(requestId, conversationId, canonical)
            if (!fresh.speechSuppressed) {
                appendSpeakable(fresh, canonical)
                emitCompletePhrases(fresh, flush = true)
            }
            fresh.terminal = true
            onDiagnostic(requestId, SpeechStreamSignal.Finished)
            trimTurns()
            return
        }
        if (turn.conversationId != conversationId) return
        if (turn.speechSuppressed) {
            onTextUpdate(requestId, conversationId, canonical)
            turn.terminal = true
            onDiagnostic(requestId, SpeechStreamSignal.Finished)
            trimTurns()
            return
        }
        if (turn.gapDetected) {
            onTextUpdate(requestId, conversationId, canonical)
            // We cannot know exactly what was heard across a missing delta. Prefer a correct bubble
            // to replaying already-audible paragraphs from the canonical response.
            turn.terminal = true
            trimTurns()
            return
        }
        if (turn.terminal) {
            onTextUpdate(requestId, conversationId, canonical)
            return
        }
        onTextUpdate(requestId, conversationId, canonical)
        val streamed = turn.text.toString()
        if (canonical.startsWith(streamed)) {
            turn.text.append(canonical.substring(streamed.length))
            appendSpeakable(turn, canonical.substring(streamed.length))
            emitCompletePhrases(turn, flush = true)
        } else if (streamed.isEmpty()) {
            appendSpeakable(turn, canonical)
            emitCompletePhrases(turn, flush = true)
        } else if (canonical.replace(Regex("\\s+"), " ").trim() == streamed.replace(Regex("\\s+"), " ").trim()) {
            // Block/paragraph separators may differ without changing the spoken words.
            emitCompletePhrases(turn, flush = true)
        } else {
            // A final display replacement is not a playback cancellation. Drain every received
            // word, including the buffered tail, without replaying the replacement over heard audio.
            onDiagnostic(requestId, SpeechStreamSignal.CanonicalReplacement)
            emitCompletePhrases(turn, flush = true)
        }
        turn.terminal = true
        onDiagnostic(requestId, SpeechStreamSignal.Finished)
        trimTurns()
    }

    @Synchronized
    fun cancel(requestId: String? = null) {
        if (requestId == null) turns.values.forEach { it.terminal = true; retire(it.requestId) }
        else {
            onDiagnostic(requestId, SpeechStreamSignal.Cancelled)
            turns[requestId]?.terminal = true
            retire(requestId)
        }
        com.kompi.app.diagnostics.SpeechAudit.record(requestId ?: "all", stage = "stream_cancelled")
        stop(requestId)
    }

    /** Independent local output control. Text keeps flowing; old speech can never resume. */
    @Synchronized
    fun setOutputMuted(muted: Boolean) {
        if (outputMuted == muted) return
        outputMuted = muted
        if (muted) {
            turns.values.forEach {
                it.speechSuppressed = true
                it.phraseBuffer.clear()
                it.pendingFence = ""
                it.bufferStartedAtNanos = 0
            }
            stop(null)
        }
    }

    /** Call before changing project/context, recording, or starting a fresh conversation. */
    @Synchronized
    fun invalidate() {
        generation++
        turns.values.filterNot { it.terminal }.forEach { onDiagnostic(it.requestId, SpeechStreamSignal.Invalidated) }
        turns.values.forEach { it.terminal = true; retire(it.requestId) }
        turns.clear()
        stop(null)
    }

    @Synchronized fun generationToken(): Long = generation
    @Synchronized fun isOutputMuted(): Boolean = outputMuted

    /** Runtime-owned tick: meet safe-clause deadlines even when no new delta arrives. */
    @Synchronized fun drainBufferedSpeech() {
        turns.values.filterNot { it.terminal || it.speechSuppressed }.forEach { emitCompletePhrases(it, flush = false) }
    }

    private fun appendSpeakable(turn: Turn, input: String) {
        val combined = turn.pendingFence + input
        turn.pendingFence = ""
        val lines = combined.split('\n')
        for (index in lines.indices) {
            val line = lines[index]
            val trimmed = line.trimStart()
            val marker = when {
                trimmed.startsWith("```") -> "```"
                trimmed.startsWith("~~~") -> "~~~"
                else -> null
            }
            if (marker != null) {
                if (!turn.inCodeFence) appendLineText(turn, line.substringBefore(marker))
                turn.inCodeFence = !turn.inCodeFence
                if (!turn.inCodeFence) appendLineText(turn, line.substringAfter(marker, ""))
            } else if (!turn.inCodeFence) {
                if (index == lines.lastIndex && !input.endsWith('\n')) {
                    val partial = line.trimStart()
                    val possibleFence = partial.isEmpty() || "```".startsWith(partial) || "~~~".startsWith(partial)
                    if (possibleFence) turn.pendingFence = line
                    else turn.phraseBuffer.append(line) // No newline needed before sentence-level speech.
                } else appendLineText(turn, line)
            }
        }
        if (turn.pendingFence.length > 2_048) turn.pendingFence = turn.pendingFence.takeLast(2_048)
    }

    private fun appendLineText(turn: Turn, line: String) {
        if (Regex("^\\s*(?:<thinking>|<tool(?:_call)?\\b|tool\\s*:|planning\\s*:)", RegexOption.IGNORE_CASE).containsMatchIn(line)) return
        turn.phraseBuffer.append(line).append(' ')
    }

    private fun emitCompletePhrases(turn: Turn, flush: Boolean) {
        if (flush && turn.pendingFence.isNotEmpty()) {
            val candidate = turn.pendingFence.trimStart()
            if (!turn.inCodeFence && candidate.isNotEmpty() && !candidate.startsWith("```") && !candidate.startsWith("~~~")) turn.phraseBuffer.append(turn.pendingFence)
            turn.pendingFence = ""
        }
        val buffer = turn.phraseBuffer
        if (buffer.isNotEmpty() && turn.bufferStartedAtNanos == 0L) turn.bufferStartedAtNanos = System.nanoTime()
        while (!turn.terminal && buffer.isNotBlank()) {
            val pressure = if (hasQueuedAudio(turn.requestId)) SpeechBufferPressure.HasAudio else SpeechBufferPressure.NeedsAudio
            when (val next = turn.planner.next(buffer.toString(), flush, turn.speechSequence == 0L, pressure,
                (System.nanoTime() - turn.bufferStartedAtNanos) / 1_000_000)) {
                is SpeechChunkDecision.Emit -> {
                    val phrase = buffer.substring(0, next.end).trim()
                    buffer.delete(0, next.end)
                    while (buffer.isNotEmpty() && buffer.first().isWhitespace()) buffer.deleteCharAt(0)
                    turn.bufferStartedAtNanos = if (buffer.isEmpty()) 0 else System.nanoTime()
                    emit(turn, phrase)
                }
                SpeechChunkDecision.OversizedToken -> { rejectOversizedToken(turn); break }
                SpeechChunkDecision.AwaitText -> break
            }
        }
        if (buffer.isBlank()) { buffer.clear(); turn.bufferStartedAtNanos = 0 }
        if (buffer.length > MAX_PHRASE_BUFFER) rejectOversizedToken(turn)
    }

    private fun rejectOversizedToken(turn: Turn) {
        turn.terminal = true
        turn.phraseBuffer.clear()
        onDiagnostic(turn.requestId, SpeechStreamSignal.UnspeakableToken)
        stop(turn.requestId)
    }

    private fun emit(turn: Turn, phrase: String) {
        val clean = phrase.replace(Regex("[`*_#>|]+"), " ").replace(Regex("\\s+"), " ").trim()
        // The planner owns all boundaries, including the engine budget; never re-split a token here.
        if (clean.any { it.isLetterOrDigit() }) {
            val sequence = turn.speechSequence++
            val source = com.kompi.app.diagnostics.SpeechAudit.replySource(turn.text.toString(), clean, turn.auditSourceEnd)
            if (source != null) turn.auditSourceEnd = source.end
            com.kompi.app.diagnostics.SpeechAudit.bind(turn.requestId, sequence, turn.requestId, source)
            com.kompi.app.diagnostics.SpeechAudit.record(turn.requestId, sequence, "phrase_offered", "characters=${clean.length}")
            if (!speak(turn.requestId, sequence, clean)) {
                turn.terminal = true
                com.kompi.app.diagnostics.SpeechAudit.record(turn.requestId, sequence, "phrase_rejected")
                onDiagnostic(turn.requestId, SpeechStreamSignal.EnqueueRejected)
                stop(turn.requestId)
            }
        }
    }

    private fun trimTurns() {
        while (turns.size > 32) {
            val oldest = turns.keys.first()
            retire(oldest)
            turns.remove(oldest)
        }
    }

    private fun retire(requestId: String) {
        if (retiredRequests.size < MAX_RETIRED_REQUESTS) retiredRequests.add(requestId)
        else ownershipBudgetExhausted = true
    }

    private companion object {
        const val MAX_REPLY_CHARS = 48_000
        const val MAX_PHRASE_BUFFER = 2_400
        const val MAX_RETIRED_REQUESTS = 4_096
    }
}
