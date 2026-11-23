package com.kompi.app.audio

import android.content.Context
import com.kompi.app.audio.live.TranscriptPartial
import com.kompi.app.audio.whistle.WhistleVocabulary
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Push-to-talk uses genuine interim/final snapshots from the selected local streaming decoder. */
internal class OfflineStreamingRecognizer internal constructor(
    private val decoder: StreamingSpeechDecoder,
    private val onPartial: (OfflineTranscript) -> Unit,
    private val onTranscript: (OfflineTranscript) -> Unit,
    private val onError: (String) -> Unit,
) : OfflineSpeechRecognizer {
    constructor(
        context: Context,
        model: SpeechRecognitionModel,
        language: String?,
        vocabulary: WhistleVocabulary,
        onPartial: (OfflineTranscript) -> Unit,
        onTranscript: (OfflineTranscript) -> Unit,
        onError: (String) -> Unit,
    ) : this(model.openDecoder(context.applicationContext, language, vocabulary), onPartial, onTranscript, onError)

    private val accepting = AtomicBoolean(true)
    private val closeCompletion = java.util.concurrent.CompletableFuture<Unit>()
    private val pcmLock = Any()
    private val pcm = StreamingPcmBuffer(decoder.chunkSamples)
    private var committed = ""
    private var lastPartial: OfflineTranscript? = null
    private val inference = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(decoder.maxQueuedChunks + 1),
        { task -> Thread(task, "kompi-dictation-inference") }, ThreadPoolExecutor.AbortPolicy())
    private val vad = AdaptiveEnergyVad(
        onActiveFrame = ::appendFrame,
        onUtteranceEnded = ::finishStream,
    )

    override fun onAudio(samples: FloatArray) {
        if (!accepting.get()) return
        try { vad.accept(samples) }
        catch (error: Exception) { onCaptureError(error.message ?: "Local voice detection failed") }
    }

    override fun finishUtterance() {
        if (!accepting.get()) return
        try { vad.flush() }
        catch (error: Exception) { onCaptureError(error.message ?: "Could not finish speech segment") }
    }

    /** Session calls this only after the microphone reader has joined and before [close]. */
    override fun finishUtteranceAndJoin() {
        if (!accepting.get()) return
        finishUtterance()
        inference.shutdown()
        awaitInference()
    }

    private fun appendFrame(frame: FloatArray) = synchronized(pcmLock) {
        if (accepting.get()) pcm.append(frame, ::enqueueChunk)
    }

    private fun finishStream() {
        if (!accepting.get()) return
        val tail = synchronized(pcmLock) { pcm.drain() }
        val endpointAt = System.nanoTime()
        try {
            // Tail and finish share one queue slot. A real endpoint must not require two extra
            // slots behind a full feed queue, and short tails never need synthetic silence.
            inference.execute {
                if (!accepting.get()) return@execute
                try {
                    if (tail.isNotEmpty()) publishPartial(decoder.transcribe(tail))
                    val final = decoder.finish()
                    android.util.Log.i("KompiDictation", "phase=endpoint_finalized elapsed_ms=${(System.nanoTime() - endpointAt) / 1_000_000} tail_samples=${tail.size}")
                    committed = final.committedText.trim()
                    val text = joined(committed, final.pendingText).trim()
                    check(text.length <= 16_000) { "Speech segment exceeds the 16,000-character draft limit" }
                    if (accepting.get() && text.isNotEmpty()) onTranscript(OfflineTranscript(text, final.language))
                    else retractPartial(final.language)
                    decoder.reset()
                    committed = ""
                    lastPartial = null
                } catch (error: Exception) {
                    if (accepting.get()) onError(error.message ?: "Local transcription could not be finalized")
                }
            }
        } catch (_: RejectedExecutionException) { onError("The local decoder is behind; stop and restart dictation") }
    }

    private fun enqueueChunk(samples: FloatArray) {
        if (samples.isEmpty() || !accepting.get()) return
        // Keep one bounded slot available for the real tail + finish command.
        if (inference.queue.size >= decoder.maxQueuedChunks) {
            onError("The local decoder is behind; stop and restart dictation")
            return
        }
        try {
            inference.execute {
                if (!accepting.get()) return@execute
                try {
                    publishPartial(decoder.transcribe(samples))
                } catch (error: Exception) {
                    if (accepting.get()) onError(error.message ?: "Local streaming transcription failed")
                }
            }
        } catch (_: RejectedExecutionException) { onError("The local decoder is behind; stop and restart dictation") }
    }

    private fun publishPartial(partial: TranscriptPartial) {
        committed = partial.committedText.trim()
        val text = joined(committed, partial.pendingText).trim()
        check(text.length <= 16_000) { "Speech segment exceeds the 16,000-character draft limit" }
        val snapshot = OfflineTranscript(text, partial.language)
        if (accepting.get() && snapshot != lastPartial && (text.isNotEmpty() || lastPartial?.text?.isNotEmpty() == true)) {
            lastPartial = snapshot
            onPartial(snapshot)
        }
    }

    private fun retractPartial(language: String?) {
        if (lastPartial?.text?.isNotEmpty() == true && accepting.get()) onPartial(OfflineTranscript("", language))
    }

    override fun onCaptureError(message: String) = onError(message)

    override fun close() {
        if (!accepting.compareAndSet(true, false)) return
        synchronized(pcmLock) { pcm.clear() }
        inference.shutdown()
        Thread({
            try { awaitInference(); decoder.close(); closeCompletion.complete(Unit) }
            catch (error: Throwable) { closeCompletion.completeExceptionally(error) }
        }, "kompi-dictation-close").apply { isDaemon = true; start() }
    }

    override fun closeAndJoin() {
        close()
        closeCompletion.join()
    }

    private fun awaitInference() {
        var interrupted = false
        while (!inference.isTerminated) {
            try { inference.awaitTermination(1, TimeUnit.DAYS) }
            catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun joined(committed: String, pending: String) = listOf(committed.trim(), pending.trim())
        .filter(String::isNotEmpty).joinToString(" ")
}
