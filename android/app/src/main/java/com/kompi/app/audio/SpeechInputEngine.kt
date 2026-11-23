package com.kompi.app.audio

import android.os.Build
import android.content.SharedPreferences

/** Explicit phone-local recognition choice. Both fresh installs and legacy Whistle selections use the packaged Parakeet engine. */
enum class SpeechInputEngine(val preference: String, val label: String) {
    Parakeet("parakeet", "Parakeet TDT 0.6B v3"),
    Whistle("whistle", "Whistle");

    fun supportedOnDevice(): Boolean = when (this) {
        Parakeet -> Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }
        Whistle -> false
    }

    companion object {
        const val PREFERENCE = "speechInputEngine"
        val packaged = listOf(Parakeet)

        fun read(settings: SharedPreferences): SpeechInputEngine {
            val stored = settings.getString(PREFERENCE, null)
            val engine = fromStored(stored)
            if (stored != null && stored != engine.preference) {
                settings.edit().putString(PREFERENCE, engine.preference).apply()
            }
            return engine
        }

        fun fromStored(value: String?): SpeechInputEngine = when (value) {
            null, "parakeet" -> Parakeet
            "whistle" -> Parakeet // Migrate a selection from builds that shipped Needle.
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
