package com.kompi.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Detailed state of the Android system-default TTS engine. */
sealed interface LocalTtsStatus {
    data object Checking : LocalTtsStatus
    data object Disabled : LocalTtsStatus
    data class Ready(val engineName: String, val voiceName: String) : LocalTtsStatus
    data class Unavailable(val message: String) : LocalTtsStatus
    data object Closed : LocalTtsStatus
}

/**
 * Uses Android's configured system-default TextToSpeech engine (no fixed package/engine ID).
 * Network-required voices are never selected. The user's current voice is preserved when local;
 * if it requires network, an offline voice of the same language is preferred. This class does not
 * install third-party engines or modify any Android system defaults.
 */
class LocalTts(context: Context, initiallyEnabled: Boolean = true) : AutoCloseable {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val enabled = MutableStateFlow(initiallyEnabled)
    val isEnabled: StateFlow<Boolean> = enabled.asStateFlow()
    private val mutableDetails = MutableStateFlow<LocalTtsStatus>(if (initiallyEnabled) LocalTtsStatus.Checking else LocalTtsStatus.Disabled)
    val details: StateFlow<LocalTtsStatus> = mutableDetails.asStateFlow()
    private val mutableStatus = MutableStateFlow(if (initiallyEnabled) "Checking Android system TTS" else "Speech disabled")
    /** Human-readable actual engine/voice and readiness for the Settings UI. */
    val status: StateFlow<String> = mutableStatus.asStateFlow()

    @Volatile private var engine: TextToSpeech? = null
    @Volatile private var ready = false

    init {
        // The two-argument constructor deliberately lets Android use its current system default.
        engine = TextToSpeech(appContext) { result ->
            val tts = engine
            if (closed.get()) return@TextToSpeech
            if (result != TextToSpeech.SUCCESS || tts == null) {
                setUnavailable("Android system text-to-speech engine is unavailable")
                return@TextToSpeech
            }
            configure(tts)
        }
    }

    private fun configure(tts: TextToSpeech) {
        try {
            tts.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            val current = tts.voice
            val voice = if (current != null && !current.isNetworkConnectionRequired) current else {
                val language = current?.locale?.language?.takeIf(String::isNotBlank) ?: Locale.getDefault().language
                tts.voices.orEmpty()
                    .filter { !it.isNetworkConnectionRequired && it.locale.language == language }
                    .maxByOrNull { it.quality }
            }
            if (voice == null) {
                ready = false
                setUnavailable("No offline voice installed for this language. Install an offline voice or engine in Android Settings.")
                return
            }
            // Respect the currently selected offline voice; only replace a network-required voice.
            if (voice != current) tts.voice = voice
            ready = true
            val status = LocalTtsStatus.Ready(engineLabel(), voice.name)
            mutableDetails.value = status
            mutableStatus.value = if (enabled.value) "Ready · " + status.engineName + " · " + status.voiceName else "Speech disabled · " + status.engineName
        } catch (error: Exception) {
            ready = false
            setUnavailable(error.message ?: "Could not configure an offline system voice")
        }
    }

    private fun engineLabel(): String {
        val packageName = runCatching { engine?.defaultEngine }.getOrNull()
        if (packageName.isNullOrBlank()) return "Android system default TTS"
        return runCatching {
            val info = appContext.packageManager.getApplicationInfo(packageName, 0)
            info.loadLabel(appContext.packageManager).toString().ifBlank { packageName }
        }.getOrDefault(packageName)
    }

    private fun setUnavailable(message: String) {
        mutableDetails.value = LocalTtsStatus.Unavailable(message)
        mutableStatus.value = if (enabled.value) "Unavailable · $message" else "Speech disabled · $message"
    }

    fun setEnabled(value: Boolean) {
        if (closed.get()) return
        enabled.value = value
        if (!value) {
            stop()
            mutableDetails.value = LocalTtsStatus.Disabled
            mutableStatus.value = "Speech disabled"
        } else if (ready) {
            val details = LocalTtsStatus.Ready(engineLabel(), engine?.voice?.name ?: "Offline voice")
            mutableDetails.value = details
            mutableStatus.value = "Ready · ${details.engineName} · ${details.voiceName}"
        } else if (mutableDetails.value is LocalTtsStatus.Unavailable) {
            val message = (mutableDetails.value as LocalTtsStatus.Unavailable).message
            mutableStatus.value = "Unavailable · $message"
        } else {
            mutableDetails.value = LocalTtsStatus.Checking
            mutableStatus.value = "Checking Android system TTS"
        }
    }

    /** Returns false unless explicitly enabled and an offline voice is ready. */
    fun speak(text: String): Boolean {
        val bounded = TtsText.bounded(text)
        if (bounded.isBlank() || !enabled.value || !ready || closed.get()) return false
        return engine?.speak(bounded, TextToSpeech.QUEUE_FLUSH, null, "kompi-${System.nanoTime()}") == TextToSpeech.SUCCESS
    }

    fun stop() { engine?.stop() }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ready = false
        enabled.value = false
        mutableDetails.value = LocalTtsStatus.Closed
        mutableStatus.value = "Speech engine closed"
        engine?.stop()
        engine?.shutdown()
        engine = null
    }

}

/** Pure boundary policy for speech input; bounded to keep long Pi output from monopolizing audio. */
object TtsText {
    const val MAX_CHARS = 4_000
    fun bounded(text: String): String = text.trim().take(MAX_CHARS)
}
