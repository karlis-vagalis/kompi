package com.kompi.app.audio

import android.os.Build
import com.kompi.app.audio.whistle.WhistleBackend

/** Explicit phone-local recognition choice. Missing preference means Parakeet, never fallback. */
enum class SpeechInputEngine(val preference: String, val label: String) {
    Parakeet("parakeet", "Parakeet TDT 0.6B v3"),
    Whistle("whistle", "Whistle");

    fun supportedOnDevice(): Boolean = when (this) {
        Parakeet -> Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }
        Whistle -> WhistleBackend.isSupportedOnThisDevice()
    }

    companion object {
        const val PREFERENCE = "speechInputEngine"
        fun fromStored(value: String?): SpeechInputEngine = when (value) {
            null, "parakeet" -> Parakeet
            "whistle" -> Whistle
            else -> error("Unknown speech input engine preference")
        }
    }
}

/** A selected, installed model carries its engine; a filename is not an engine discriminator. */
sealed interface SpeechRecognitionModel {
    val engine: SpeechInputEngine
    data class Parakeet(val installed: InstalledParakeetModel) : SpeechRecognitionModel {
        override val engine = SpeechInputEngine.Parakeet
    }
    data class Whistle(val installed: InstalledWhistleModel) : SpeechRecognitionModel {
        override val engine = SpeechInputEngine.Whistle
    }
}
