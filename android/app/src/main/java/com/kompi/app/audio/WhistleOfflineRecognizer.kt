package com.kompi.app.audio

import com.kompi.app.audio.whistle.WhistleBackend
import com.kompi.app.audio.whistle.WhistleVocabulary
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Silero-free adaptive energy endpointing followed by fully local Whistle inference. */
internal class WhistleOfflineRecognizer(
    model: InstalledWhistleModel,
    private val language: String?,
    private val onTranscript: (OfflineTranscript) -> Unit,
    private val onError: (String) -> Unit,
    private val vocabulary: WhistleVocabulary = WhistleVocabulary.Empty,
) : OfflineSpeechRecognizer {
    private val accepting = AtomicBoolean(true)
    private val vad = AdaptiveEnergyVad()
    private val inference = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4),
        { runnable -> Thread(runnable, "kompi-whistle-inference") },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val backend = WhistleBackend.load(model.file)

    override fun onAudio(samples: FloatArray) {
        if (!accepting.get()) return
        try { vad.accept(samples).forEach(::enqueue) }
        catch (error: Exception) { onCaptureError(error.message ?: "Local voice detection failed") }
    }

    @Synchronized
    override fun finishUtterance() {
        if (!accepting.get()) return
        try { vad.flush().forEach(::enqueue) }
        catch (error: Exception) { onCaptureError(error.message ?: "Could not finish speech segment") }
    }

    private fun enqueue(samples: FloatArray) {
        if (samples.isEmpty()) return
        try {
            inference.execute {
                try {
                    val result = backend.transcribeJson(samples, language, vocabulary)
                    if (result.text.isNotBlank()) onTranscript(OfflineTranscript(result.text.trim(), result.language))
                } catch (error: Exception) {
                    onError(error.message ?: "Whistle transcription failed")
                }
            }
        } catch (_: RejectedExecutionException) {
            onError("Whistle is busy; this speech segment was not queued")
        }
    }

    override fun onCaptureError(message: String) = onError(message)

    override fun close() {
        if (!accepting.compareAndSet(true, false)) return
        // Gracefully drain queued JNI calls. The C API has no cancellation or unload operation.
        inference.shutdown()
    }

    /** Called off the UI thread when a caller must not start another capture until JNI is idle. */
    override fun closeAndJoin() {
        close()
        var interrupted = false
        while (!inference.isTerminated) {
            try { inference.awaitTermination(1, TimeUnit.DAYS) }
            catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
