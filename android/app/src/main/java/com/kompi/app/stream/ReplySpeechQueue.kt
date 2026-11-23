package com.kompi.app.stream

import com.kompi.app.audio.SpeechOutputAdmission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keeps model streaming independent of the bounded PCM queue, without dropping reply clauses. */
class ReplySpeechQueue(
    private val scope: CoroutineScope,
    private val enqueue: suspend (String, Long, String) -> Boolean,
    private val finish: (String) -> Unit,
    private val stop: (String?) -> Unit,
    private val failed: (String) -> Unit,
) {
    private data class Phrase(val sequence: Long, val text: String, val admission: SpeechOutputAdmission)
    private class Pending {
        val phrases = Channel<Phrase>(Channel.UNLIMITED, onUndeliveredElement = { it.admission.closePending() })
        var characters = 0
        var offeredPhrases = 0L
        var admittedPhrases = 0L
        lateinit var job: Job
    }
    private val pending = mutableMapOf<String, Pending>()
    private val blocked = linkedSetOf<String>()

    @Synchronized fun offer(requestId: String, sequence: Long, text: String): Boolean {
        if (requestId in blocked) return false
        val turn = pending.getOrPut(requestId) {
            Pending().also { next ->
                next.job = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        for (phrase in next.phrases) {
                            val accepted = try {
                                withContext(phrase.admission) { enqueue(requestId, phrase.sequence, phrase.text) }
                            } finally {
                                // Kokoro atomically takes the capability when its bounded queue accepts.
                                // A cancellation at the suspend-return boundary cannot release its writer.
                                phrase.admission.closePending()
                            }
                            if (!accepted) {
                                failAdmission(requestId, next)
                                return@launch
                            }
                            synchronized(this@ReplySpeechQueue) {
                                next.characters -= phrase.text.length
                                next.admittedPhrases++
                            }
                        }
                        // Only seal Kokoro after every accepted clause has reached its queue.
                        finish(requestId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Admission can throw after an earlier phrase has already reached real PCM.
                        // Seal that valid prefix; never turn a failed suffix into a playback stop.
                        failAdmission(requestId, next)
                    } finally {
                        synchronized(this@ReplySpeechQueue) {
                            if (pending[requestId] === next) pending.remove(requestId)
                        }
                        next.phrases.cancel()
                    }
                }
            }
        }
        if (turn.characters + text.length > MAX_PENDING_CHARACTERS) {
            failAdmission(requestId, turn)
            return false
        }
        val admission = try { SpeechOutputAdmission.capture(requestId, sequence) }
        catch (_: IllegalStateException) { failAdmission(requestId, turn); return false }
        if (!turn.phrases.trySend(Phrase(sequence, text, admission)).isSuccess) {
            admission.closePending()
            return false
        }
        turn.characters += text.length
        turn.offeredPhrases++
        turn.job.start()
        return true
    }

    @Synchronized fun hasPendingSpeech(requestId: String? = null): Boolean =
        if (requestId == null) pending.values.any { it.characters > 0 }
        else (pending[requestId]?.characters ?: 0) > 0

    @Synchronized fun complete(requestId: String) {
        if (requestId in blocked) return
        val turn = pending[requestId]
        android.util.Log.i("KompiReplySpeech", "phase=sealed request=$requestId phrases=${turn?.offeredPhrases ?: 0}")
        if (turn == null) finish(requestId) else turn.phrases.close()
    }

    @Synchronized fun cancel(requestId: String? = null) {
        if (requestId == null) {
            val turns = pending.values.toList()
            pending.clear()
            blocked.clear()
            turns.forEach { it.job.cancel(); it.phrases.cancel() }
        } else {
            blocked += requestId
            while (blocked.size > 64) blocked.remove(blocked.first())
            pending.remove(requestId)?.let { it.job.cancel(); it.phrases.cancel() }
        }
        stop(requestId)
    }

    private fun failAdmission(requestId: String, turn: Pending) {
        val report = synchronized(this) {
            if (pending[requestId] !== turn || requestId in blocked) false
            else {
                pending.remove(requestId)
                blocked += requestId
                while (blocked.size > 64) blocked.remove(blocked.first())
                turn.phrases.cancel()
                true
            }
        }
        if (!report) return
        turn.job.cancel() // A still-suspended admission must not append a suffix after failure.
        if (synchronized(this) { turn.admittedPhrases > 0L }) {
            // Seal a real accepted prefix without flushing its already queued/playing PCM (L49).
            runCatching { finish(requestId) }
        } else {
            // Nothing was admitted: retire this request only, never signal a false completion.
            runCatching { stop(requestId) }
        }
        runCatching { failed(requestId) }
    }

    private companion object { const val MAX_PENDING_CHARACTERS = 48_000 }
}
