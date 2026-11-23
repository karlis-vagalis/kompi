package com.kompi.app.audio.live

import android.media.AudioDeviceInfo
import com.kompi.app.audio.AudioWaveform
import com.kompi.app.audio.EchoCancellationPolicy
import com.kompi.app.audio.SpeechUtteranceId
import com.kompi.app.audio.ForegroundMicCapture
import com.kompi.app.audio.Pcm16kConsumer
import com.kompi.app.audio.StreamingSpeechDecoder
import com.kompi.app.audio.StreamingPcmBuffer
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** Current phase of actual microphone/JNI work; not an animated estimate. */
enum class CapturePhase { Listening, Recognizing }

data class CaptureMeter(val rms: Float, val phase: CapturePhase, val waveform: AudioWaveform = AudioWaveform.Silence) {
    init { require(rms.isFinite() && rms in 0f..1f) }
}

data class TranscriptPartial(
    /** All words the selected engine has committed in the current utterance. */
    val committedText: String,
    /** The selected decoder's current uncommitted tail; replace it on each update. */
    val pendingText: String,
    val language: String? = null,
)

class NonBlankTranscript private constructor(val text: String, val language: String?) {
    companion object {
        fun parse(text: String, language: String?): NonBlankTranscript? =
            text.trim().takeIf(String::isNotEmpty)?.let { NonBlankTranscript(it, language) }
    }
}

internal enum class LocalLiveRecognitionFailure(val guidance: String) {
    Backlog("Speech recognition could not keep up. Stop Live and try again."),
    Decoder("Local transcription failed. Check the selected speech model in Voice settings and start Live again."),
    Finalization("Could not finish transcribing that point. Start Live again and repeat it."),
    Classifier("Local speech detection failed. Start Live again."),
}

internal interface LocalLiveListener {
    /** Confirmed local speech onset, before waiting for native transcription. */
    fun onSpeechStarted(utteranceId: SpeechUtteranceId)
    /** Independent hysteresis after speech admission; this alone may cancel existing output. */
    fun onBargeInConfirmed(utteranceId: SpeechUtteranceId) {}
    /** Called with RMS computed from captured PCM or the actual PCM chunk being decoded. */
    fun onMeter(meter: CaptureMeter)
    /** Replace the prior caption with the selected decoder's actual committed/interim state. */
    fun onPartial(utteranceId: SpeechUtteranceId, partial: TranscriptPartial)
    /** Actual VAD boundary, independently of the serialized decoder's remaining tail work. */
    fun onUtteranceEnded(utteranceId: SpeechUtteranceId) {}
    /** A nonblank final is delivered once per completed VAD/end-of-capture utterance. */
    fun onFinal(utteranceId: SpeechUtteranceId, transcript: NonBlankTranscript)
    /** Completes an admitted candidate without inventing text or stranding earlier deferred words. */
    fun onEmptyFinal(utteranceId: SpeechUtteranceId) {}
    fun onRecognitionFailure(failure: LocalLiveRecognitionFailure) { onError(failure.guidance) }
    fun onError(message: String)
}

/**
 * Shared Live VAD, pre-roll and utterance ownership over an engine's real streaming decoder.
 * Each selected engine receives its documented target feed (Whistle ~1 s, Parakeet 200 ms) and reports actual committed/interim snapshots.
 * Decoder calls are serialized off the microphone reader; slower inference can lag this cadence.
 */
internal class LocalLiveRecognizer(
    private val decoder: StreamingSpeechDecoder,
    private val language: String?,
    private val listener: LocalLiveListener,
    override val echoCancellation: EchoCancellationPolicy,
    override val preferredInputDevice: AudioDeviceInfo?,
    private val checkAudioRoute: () -> Unit,
    speechFrameClassifier: SpeechFrameClassifier,
) : Pcm16kConsumer, Closeable {
    private val accepting = AtomicBoolean(true)
    private val cancelled = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val terminalFinished = AtomicBoolean(false)
    private val stateLock = Any()
    private var utteranceSequence = 0L
    private var capturingUtterance: SpeechUtteranceId? = null
    private val preRoll = ArrayDeque<FloatArray>()
    private var preRollSamples = 0
    private val vad = LiveSpeechGate(speechFrameClassifier)
    private val inference = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(decoder.maxQueuedChunks + 1),
        { task -> Thread(task, "kompi-local-live") },
        ThreadPoolExecutor.AbortPolicy(),
    )

    private var currentLanguage: String? = language
    private var committed = ""
    private var lastPartial: TranscriptPartial? = null
    /** Inference thread only; normal stop after a queued endpoint cannot finish a closed task. */
    private var decoderClosed = false
    private val pcm = StreamingPcmBuffer(decoder.chunkSamples)

    override fun onAudio(samples: FloatArray) {
        if (!accepting.get() || samples.isEmpty()) return
        checkAudioRoute()
        val rms = rms(samples)
        val waveform = AudioWaveform.fromPcm(samples, 16_000)
        notifySafely { listener.onMeter(CaptureMeter(rms, CapturePhase.Listening, waveform)) }
        synchronized(stateLock) {
            if (!accepting.get()) return
            if (capturingUtterance == null) {
                preRoll.addLast(samples.copyOf())
                preRollSamples += samples.size
                while (preRollSamples > PRE_ROLL_SAMPLES && preRoll.isNotEmpty()) preRollSamples -= preRoll.removeFirst().size
            }
            val wasCapturing = capturingUtterance != null
            val transitions = try { vad.accept(samples) } catch (error: Exception) {
                fail(LocalLiveRecognitionFailure.Classifier)
                return
            }
            val speechStarted = LiveSpeechGateEvent.SpeechConfirmed in transitions
            val bargeInConfirmed = LiveSpeechGateEvent.BargeInConfirmed in transitions
            val endpoint = LiveSpeechGateEvent.UtteranceEnded in transitions
            if (speechStarted) {
                val utteranceId = SpeechUtteranceId(++utteranceSequence)
                capturingUtterance = utteranceId
                // Publish onset before the first pre-roll feed can return a native partial.
                notifySafely { listener.onSpeechStarted(utteranceId) }
                for (frame in preRoll) appendChunk(utteranceId, frame)
                preRoll.clear()
                preRollSamples = 0
            } else if (wasCapturing) capturingUtterance?.let { appendChunk(it, samples) }
            if (bargeInConfirmed) capturingUtterance?.let { id -> notifySafely { listener.onBargeInConfirmed(id) } }
            // Only real PCM belonging to an admitted utterance reaches ASR, including its real
            // endpoint silence. Never pad a model window to manufacture earlier recognition.
            val utteranceId = capturingUtterance
            if (endpoint && utteranceId != null) {
                notifySafely { listener.onUtteranceEnded(utteranceId) }
                submitBoundary(utteranceId, pcm.drain())
                capturingUtterance = null
                preRoll.clear()
                preRollSamples = 0
            }
        }
    }

    /** Stop capture first, then flush the final real PCM chunk and upstream decoder tail. */
    fun finish(onComplete: (() -> Unit)? = null) {
        synchronized(stateLock) {
            if (!accepting.compareAndSet(true, false)) { onComplete?.invoke(); return }
            terminalFinished.set(true)
            val hadSpeech = vad.flush()
            vad.close()
            val utteranceId = capturingUtterance
            if (utteranceId != null && hadSpeech) notifySafely { listener.onUtteranceEnded(utteranceId) }
            submitTerminalBoundary(utteranceId.takeIf { hadSpeech }, pcm.drain(), onComplete = onComplete)
        }
    }

    /** Called for capture failure or live-mode teardown: discard results and reset native stream. */
    override fun close() = cancelNativeStream()

    /** Discards the current stream, then waits off the UI thread until JNI cleanup has completed. */
    fun discardAndJoin() {
        close()
        awaitInferenceTermination()
    }

    /** Wait after [finish] so a new process-global Needle stream cannot overlap native finalization. */
    fun awaitInferenceTermination() {
        var interrupted = false
        while (!inference.isTerminated) {
            try { inference.awaitTermination(1, TimeUnit.DAYS) }
            catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun cancelNativeStream(force: Boolean = false) {
        synchronized(stateLock) {
            if ((!force && terminalFinished.get()) || !cancelled.compareAndSet(false, true)) return
            accepting.set(false)
            pcm.clear()
            preRoll.clear()
            preRollSamples = 0
            capturingUtterance = null
            vad.close()
            inference.queue.clear()
            try {
                inference.execute { runCatching { closeDecoder() } }
                inference.shutdown()
            } catch (_: RejectedExecutionException) {
                inference.shutdownNow()
            }
        }
    }

    override fun onCaptureError(message: String) = fail(message)

    private fun appendChunk(utteranceId: SpeechUtteranceId, samples: FloatArray) {
        pcm.append(samples) { submitChunk(AudioChunk(it, utteranceId)) }
    }

    private fun submitChunk(chunk: AudioChunk) {
        if (chunk.pcm.isEmpty() || cancelled.get() || failed.get()) return
        // Reserve one bounded command slot for endpoint tail+finish. Feed pressure must never
        // occupy that slot, and no PCM is dropped or replaced when the budget is exhausted.
        if (inference.queue.size >= decoder.maxQueuedChunks) {
            fail(LocalLiveRecognitionFailure.Backlog)
            return
        }
        val queuedAt = System.nanoTime()
        try {
            inference.execute {
                if (cancelled.get() || failed.get()) return@execute
                val started = System.nanoTime()
                try {
                    val update = decoder.transcribe(chunk.pcm)
                    if (!cancelled.get() && !failed.get()) applyPartial(chunk.utteranceId, update)
                    android.util.Log.i("KompiLiveAsr", "phase=feed utterance=${chunk.utteranceId.value} samples=${chunk.pcm.size} queue_ms=${(started - queuedAt) / 1_000_000} decode_ms=${(System.nanoTime() - started) / 1_000_000}")
                } catch (error: Exception) {
                    fail(LocalLiveRecognitionFailure.Decoder)
                }
            }
        } catch (_: RejectedExecutionException) {
            fail(LocalLiveRecognitionFailure.Backlog)
        }
    }

    private fun submitBoundary(utteranceId: SpeechUtteranceId, tail: FloatArray) {
        val endpointAt = System.nanoTime()
        try {
            inference.execute { finishStream(utteranceId, tail, reopen = true, endpointAt = endpointAt) }
        } catch (_: RejectedExecutionException) {
            fail(LocalLiveRecognitionFailure.Backlog)
        }
    }

    private fun submitTerminalBoundary(utteranceId: SpeechUtteranceId?, tail: FloatArray, onComplete: (() -> Unit)?) {
        val endpointAt = System.nanoTime()
        try {
            inference.execute {
                try { finishStream(utteranceId, tail, reopen = false, endpointAt = endpointAt) }
                finally {
                    runCatching { onComplete?.invoke() }
                    inference.shutdown()
                }
            }
        } catch (_: RejectedExecutionException) {
            fail(LocalLiveRecognitionFailure.Backlog)
            runCatching { onComplete?.invoke() }
        }
    }

    private fun finishStream(utteranceId: SpeechUtteranceId?, pcmTail: FloatArray, reopen: Boolean, endpointAt: Long) {
        if (decoderClosed) return
        if (cancelled.get() || failed.get()) {
            runCatching { closeDecoder() }
            return
        }
        // Empty terminal cleanup is disposal, not another inference on a closed/fresh stream.
        if (utteranceId == null && pcmTail.isEmpty()) {
            closeDecoder()
            return
        }
        try {
            if (pcmTail.isNotEmpty()) {
                val update = decoder.transcribe(pcmTail)
                if (utteranceId != null && !cancelled.get() && !failed.get()) applyPartial(utteranceId, update)
            }
            val tail = decoder.finish()
            currentLanguage = tail.language ?: currentLanguage
            committed = tail.committedText.trim()
            val complete = appendWords(committed, tail.pendingText)
            if (utteranceId != null) {
                val final = NonBlankTranscript.parse(complete, currentLanguage)
                if (final != null) notifySafely { listener.onFinal(utteranceId, final) }
                else {
                    retractPartial(utteranceId, currentLanguage)
                    notifySafely { listener.onEmptyFinal(utteranceId) }
                }
                android.util.Log.i("KompiLiveAsr", "phase=endpoint_finalized utterance=${utteranceId.value} elapsed_ms=${(System.nanoTime() - endpointAt) / 1_000_000} tail_samples=${pcmTail.size} characters=${complete.length}")
            }
            committed = ""
            lastPartial = null
            if (reopen && accepting.get() && !cancelled.get()) {
                currentLanguage = language
                decoder.reset()
            } else closeDecoder()
        } catch (error: Exception) {
            fail(LocalLiveRecognitionFailure.Finalization)
        }
    }

    private fun applyPartial(utteranceId: SpeechUtteranceId, update: TranscriptPartial) {
        currentLanguage = update.language ?: currentLanguage
        committed = update.committedText.trim()
        val partial = TranscriptPartial(committed, update.pendingText.trim(), currentLanguage)
        if (partial != lastPartial && (partial.committedText.isNotBlank() || partial.pendingText.isNotBlank() ||
                lastPartial?.let { it.committedText.isNotBlank() || it.pendingText.isNotBlank() } == true)) {
            lastPartial = partial
            notifySafely { listener.onPartial(utteranceId, partial) }
        }
    }

    private fun retractPartial(utteranceId: SpeechUtteranceId, language: String?) {
        if (lastPartial?.let { it.committedText.isNotBlank() || it.pendingText.isNotBlank() } == true) {
            val empty = TranscriptPartial("", "", language)
            lastPartial = empty
            notifySafely { listener.onPartial(utteranceId, empty) }
        }
    }

    private fun closeDecoder() {
        if (decoderClosed) return
        decoderClosed = true
        decoder.close()
    }

    private fun fail(failure: LocalLiveRecognitionFailure) {
        if (!failed.compareAndSet(false, true)) return
        notifySafely { listener.onRecognitionFailure(failure) }
        cancelNativeStream(force = true)
    }

    private fun fail(message: String) {
        if (!failed.compareAndSet(false, true)) return
        notifySafely { listener.onError(message) }
        cancelNativeStream(force = true)
    }

    private fun notifySafely(callback: () -> Unit) {
        if (cancelled.get()) return
        try { callback() } catch (_: Exception) { /* Observer failures must not break mic/JNI cleanup. */ }
    }

    private fun appendWords(prefix: String, suffix: String): String = listOf(prefix.trim(), suffix.trim())
        .filter(String::isNotEmpty)
        .joinToString(" ")

    private fun rms(samples: FloatArray): Float {
        val squareSum = samples.sumOf { it.toDouble() * it }
        return sqrt(squareSum / samples.size).toFloat().coerceIn(0f, 1f)
    }

    private data class AudioChunk(val pcm: FloatArray, val utteranceId: SpeechUtteranceId)

    private companion object {
        const val PRE_ROLL_SAMPLES = ForegroundMicCapture.SAMPLE_RATE * 400 / 1_000
    }
}
