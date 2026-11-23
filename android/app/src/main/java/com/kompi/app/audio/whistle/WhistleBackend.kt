package com.kompi.app.audio.whistle

import android.os.Build
import android.os.Looper
import com.kompi.app.audio.WhistleModelInstaller
import java.io.File
import org.json.JSONException
import org.json.JSONObject

/**
 * Thin, offline-only adapter for Cactus Whistle's speech-only C API.
 *
 * All native calls are synchronous. Call [load] and [transcribeJson] from a
 * worker thread; inference cannot be interrupted by the current C API.
 */
class WhistleBackend private constructor() {
    companion object {
        private var nativeLoaded = false
        private var loadedModelPath: String? = null
        private val streamLock = Any()
        private var activeStreamOwner: Any? = null

        fun isSupportedOnThisDevice(): Boolean = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

        /** Loads one `.cact` model into Needle's process-global speech slot. */
        fun load(modelFile: File): WhistleBackend {
            checkWorkerThread()
            require(isSupportedOnThisDevice()) { "Whistle is available only on arm64-v8a devices" }
            require(modelFile.isFile && modelFile.canRead()) { "Whistle model is not a readable file" }
            val modelPath = modelFile.absolutePath
            synchronized(WhistleBackend::class.java) {
                if (nativeLoaded) {
                    check(loadedModelPath == modelPath) {
                        "Needle's C API has no unload/reset-model operation; Whistle is already loaded from a different file"
                    }
                } else {
                    require(WhistleModelInstaller.isVerifiedModel(modelFile)) { "Whistle model checksum verification failed" }
                    System.loadLibrary("kompi_whistle")
                    nativeLoad(modelPath)
                    loadedModelPath = modelPath
                    nativeLoaded = true
                }
            }
            return WhistleBackend()
        }

        private fun checkWorkerThread() {
            check(Looper.myLooper() != Looper.getMainLooper()) {
                "Whistle model loading and inference must run off the main thread"
            }
        }

        @JvmStatic private external fun nativeLoad(modelPath: String)
        @JvmStatic private external fun nativeTranscribe(pcm16kMono: FloatArray, language: String?, keywords: String?): String
        @JvmStatic private external fun nativeStreamTranscribe(pcm16kMono: FloatArray, language: String?, keywords: String?): String
        @JvmStatic private external fun nativeStreamStop(): String
        private val SUPPORTED_LANGUAGES = setOf("en", "de", "fr", "es", "it", "nl", "pl")
    }

    /**
     * Transcribes 16 kHz mono float PCM in [-1, 1]. Returns the C API JSON
     * (`text`, `language`, `ttft_ms`, `decode_tps`, and optionally `words`).
     * Pass null for automatic language detection; supported codes are en/de/fr/es/it/nl/pl.
     */
    fun transcribeJson(pcm16kMono: FloatArray, language: String? = null, vocabulary: WhistleVocabulary = WhistleVocabulary.Empty): WhistleTranscript {
        checkWorkerThread()
        require(pcm16kMono.isNotEmpty()) { "PCM must not be empty" }
        require(language == null || language in SUPPORTED_LANGUAGES) { "Unsupported Whistle language: $language" }
        val response = synchronized(streamLock) {
            check(activeStreamOwner == null) { "One-shot Whistle transcription cannot overlap a live stream" }
            nativeTranscribe(pcm16kMono, language, vocabulary.nativeKeywords)
        }
        val json = try { JSONObject(response) } catch (error: JSONException) {
            throw IllegalStateException("Whistle returned invalid JSON", error)
        }
        val text = json.optString("text", "")
        val languageCode = json.optString("language", "").takeIf { it.isNotBlank() }
        require(languageCode == null || languageCode in SUPPORTED_LANGUAGES) { "Whistle returned an unsupported language code" }
        return WhistleTranscript(
            text = text,
            language = languageCode,
            timeToFirstTokenMs = json.optDouble("ttft_ms", 0.0),
            decodeTokensPerSecond = json.optDouble("decode_tps", 0.0),
        )
    }

    /** Starts an exclusive process-global stream; old cancelled generations are reset before reuse. */
    fun openLiveStream(language: String? = null, vocabulary: WhistleVocabulary = WhistleVocabulary.Empty): WhistleLiveStream {
        checkWorkerThread()
        require(language == null || language in SUPPORTED_LANGUAGES) { "Unsupported language" }
        synchronized(streamLock) {
            activeStreamOwner?.let { nativeStreamStop() }
            val owner = Any()
            activeStreamOwner = owner
            return WhistleLiveStream(this, owner, language, vocabulary)
        }
    }

    internal fun streamChunk(owner: Any, pcm16kMono: FloatArray, language: String?, vocabulary: WhistleVocabulary): WhistleStreamUpdate {
        checkWorkerThread()
        require(pcm16kMono.isNotEmpty() && pcm16kMono.size <= 32_000) { "Live PCM chunk must contain 1..32000 samples" }
        require(language == null || language in SUPPORTED_LANGUAGES) { "Unsupported language" }
        synchronized(streamLock) {
            check(activeStreamOwner === owner) { "This Whistle stream was superseded or stopped" }
            return parseStreamUpdate(nativeStreamTranscribe(pcm16kMono, language, vocabulary.nativeKeywords))
        }
    }

    internal fun stopStream(owner: Any): WhistleStreamUpdate? {
        checkWorkerThread()
        synchronized(streamLock) {
            if (activeStreamOwner !== owner) return null
            val result = parseStreamUpdate(nativeStreamStop())
            activeStreamOwner = null
            return result
        }
    }

    internal fun discardStream(owner: Any) {
        checkWorkerThread()
        synchronized(streamLock) {
            if (activeStreamOwner !== owner) return
            nativeStreamStop()
            activeStreamOwner = null
        }
    }

    private fun parseStreamUpdate(response: String): WhistleStreamUpdate {
        val json = try { JSONObject(response) } catch (error: JSONException) {
            throw IllegalStateException("Whistle returned invalid live JSON", error)
        }
        val languageCode = json.optString("language", "").takeIf { it.isNotBlank() }
        require(languageCode == null || languageCode in SUPPORTED_LANGUAGES) { "Whistle returned an unsupported language code" }
        return WhistleStreamUpdate(
            committedText = json.optString("text", ""),
            pendingText = json.optString("pending", ""),
            language = languageCode,
            receivedSeconds = json.optDouble("received", 0.0).coerceAtLeast(0.0),
            passMilliseconds = json.optDouble("pass_ms", Double.NaN).takeIf { it.isFinite() },
        )
    }

    private fun checkWorkerThread() {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Whistle inference must run off the main thread"
        }
    }
}

class WhistleLiveStream internal constructor(
    private val backend: WhistleBackend,
    private val owner: Any,
    private val language: String?,
    private val vocabulary: WhistleVocabulary,
) {
    fun transcribe(pcm16kMono: FloatArray): WhistleStreamUpdate = backend.streamChunk(owner, pcm16kMono, language, vocabulary)
    fun stop(): WhistleStreamUpdate? = backend.stopStream(owner)
    fun discard() = backend.discardStream(owner)
}

data class WhistleStreamUpdate(
    val committedText: String,
    val pendingText: String,
    val language: String?,
    val receivedSeconds: Double,
    /** Native per-feed inference duration when Needle reports it; not recognition latency/confidence. */
    val passMilliseconds: Double? = null,
)

data class WhistleTranscript(
    val text: String,
    val language: String?,
    val timeToFirstTokenMs: Double,
    val decodeTokensPerSecond: Double,
)
