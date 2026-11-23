package com.kompi.app.audio

import android.content.Context
import android.os.Looper
import com.google.mediapipe.tasks.audio.audiotranscriber.AudioTranscriber
import com.google.mediapipe.tasks.audio.audiotranscriber.AudioTranscriberResult
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.kompi.app.audio.live.TranscriptPartial
import com.kompi.app.audio.whistle.WhistleBackend
import com.kompi.app.audio.whistle.WhistleVocabulary
import java.io.Closeable
import java.io.File

/** Calls are serialized off-main. Each engine owns its real committed/interim transcript state. */
internal const val DEFAULT_STREAMING_DECODER_QUEUE_CAPACITY = 8
internal const val WHISTLE_STREAMING_FEED_SAMPLES = 16_000

internal interface StreamingSpeechDecoder : Closeable {
    val chunkSamples: Int
    val maxQueuedChunks: Int get() = DEFAULT_STREAMING_DECODER_QUEUE_CAPACITY
    fun transcribe(samples: FloatArray): TranscriptPartial
    fun finish(): TranscriptPartial
    fun reset()
}

internal fun SpeechRecognitionModel.openDecoder(
    context: Context,
    language: String?,
    vocabulary: WhistleVocabulary,
): StreamingSpeechDecoder = when (this) {
    is SpeechRecognitionModel.Whistle -> WhistleSpeechDecoder(installed, language, vocabulary)
    is SpeechRecognitionModel.Parakeet -> ParakeetSpeechDecoder(context, installed)
}

private class WhistleSpeechDecoder(model: InstalledWhistleModel, private val language: String?, private val vocabulary: WhistleVocabulary) : StreamingSpeechDecoder {
    private val backend = WhistleBackend.load(model.file)
    private var stream = backend.openLiveStream(language, vocabulary)
    // Keep Needle's documented ~1 s normal feed. It re-decodes buffered audio on every call;
    // smaller feeds multiply that work without proving a word-latency benefit. The assembler
    // preserves exact 16,000-sample feeds independently of the microphone/VAD frame boundaries.
    override val chunkSamples = WHISTLE_STREAMING_FEED_SAMPLES
    override fun transcribe(samples: FloatArray): TranscriptPartial {
        val started = System.nanoTime()
        val update = stream.transcribe(samples)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        // Content-free measurement only. Needle re-decodes buffered audio; a feed cadence is not
        // a first-word latency guarantee, and the bounded executor surfaces sustained lag.
        android.util.Log.i("KompiWhistle", "phase=stream_decode samples=${samples.size} elapsed_ms=$elapsedMs native_pass_ms=${update.passMilliseconds?.toLong() ?: -1} buffered_ms=${(update.receivedSeconds * 1000).toLong()}")
        return TranscriptPartial(update.committedText, update.pendingText, update.language)
    }
    override fun finish(): TranscriptPartial = (stream.stop() ?: error("Whistle stream was superseded")).let {
        TranscriptPartial(it.committedText, it.pendingText, it.language)
    }
    override fun reset() { stream = backend.openLiveStream(language, vocabulary) }
    override fun close() { stream.discard() }
}

/** Google's Audio Transcriber task, using the documented CPU, interim-result and prewarm APIs. */
internal class ParakeetSpeechDecoder(context: Context, model: InstalledParakeetModel) : StreamingSpeechDecoder {
    override val chunkSamples = 3_200 // 200 ms; inference never runs on the microphone reader.
    private val task: AudioTranscriber
    private val committed = StringBuilder()
    private var pending = ""
    private var receivedSamples = 0L
    private var streamStartedAt = 0L
    private var firstWordsReported = false

    init {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Parakeet must initialize off the UI thread" }
        val cache = File(context.cacheDir, "parakeet-cache")
        check(cache.isDirectory || cache.mkdirs()) { "Could not create Parakeet cache" }
        val options = AudioTranscriber.AudioTranscriberOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(model.file.absolutePath).setDelegate(Delegate.CPU).build())
            .setSampleRateHz(16_000)
            .setEnableInterimResults(true)
            .setModelArchitecture(AudioTranscriber.ModelArchitecture.OMNI)
            .setCacheDir(cache.absolutePath)
            .build()
        val started = System.nanoTime()
        android.util.Log.i("KompiParakeet", "phase=initialize_started")
        task = AudioTranscriber.createFromOptions(context.applicationContext, options)
        try {
            task.prewarm()
            android.util.Log.i("KompiParakeet", "phase=ready elapsed_ms=${(System.nanoTime() - started) / 1_000_000}")
        }
        catch (error: Throwable) { task.close(); throw error }
    }

    override fun transcribe(samples: FloatArray): TranscriptPartial {
        val started = System.nanoTime()
        if (streamStartedAt == 0L) streamStartedAt = started
        receivedSamples += samples.size
        val result = task.transcribe(samples, false)
        apply(result)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        if (!firstWordsReported && (committed.isNotEmpty() || pending.isNotEmpty())) {
            firstWordsReported = true
            android.util.Log.i("KompiParakeet", "phase=first_words audio_ms=${receivedSamples * 1_000 / 16_000} elapsed_ms=${(System.nanoTime() - streamStartedAt) / 1_000_000}")
        }
        android.util.Log.i("KompiParakeet", "phase=stream_decode samples=${samples.size} audio_ms=${receivedSamples * 1_000 / 16_000} elapsed_ms=$elapsedMs segments=${result.segments().size} committed_characters=${committed.length} pending_characters=${pending.length}")
        return snapshot()
    }

    override fun finish(): TranscriptPartial {
        val started = System.nanoTime()
        apply(task.finishStream())
        // finishStream finalizes the current stream; an old interim must never be submitted twice.
        check(pending.isEmpty()) { "Parakeet did not finalize its interim transcript" }
        android.util.Log.i("KompiParakeet", "phase=utterance_finalized characters=${committed.length} audio_ms=${receivedSamples * 1_000 / 16_000} finish_ms=${(System.nanoTime() - started) / 1_000_000}")
        return snapshot()
    }

    override fun reset() {
        // The official Android sample prewarms after finalization before the next recording.
        val started = System.nanoTime()
        task.prewarm()
        committed.setLength(0)
        pending = ""
        receivedSamples = 0
        streamStartedAt = 0
        firstWordsReported = false
        android.util.Log.i("KompiParakeet", "phase=stream_reset elapsed_ms=${(System.nanoTime() - started) / 1_000_000}")
    }

    override fun close() = task.close()

    private fun apply(result: AudioTranscriberResult) {
        val segments = result.segments()
        if (segments.isEmpty()) return
        val interim = mutableListOf<String>()
        for (segment in segments) {
            val text = segment.transcript().trim()
            if (text.isEmpty()) continue
            if (segment.isFinal()) {
                if (committed.isNotEmpty()) committed.append(' ')
                committed.append(text)
            } else interim += text
        }
        pending = interim.joinToString(" ")
        check(committed.length + pending.length <= 16_000) { "Parakeet speech segment is too long; try a shorter point" }
    }

    // The task has auto-detection but does not expose a recognized-language field. Do not invent one.
    private fun snapshot() = TranscriptPartial(committed.toString(), pending, null)
}
