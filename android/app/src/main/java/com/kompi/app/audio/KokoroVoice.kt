package com.kompi.app.audio

import com.google.mediapipe.tasks.audio.texttospeech.VoiceInfo
import java.util.Locale

/** A Kokoro voice ID parsed from model metadata or the saved preference boundary. */
@JvmInline
value class KokoroVoiceId private constructor(val value: String) {
    companion object {
        val DEFAULT = KokoroVoiceId("af_heart")

        fun fromStored(value: String?): KokoroVoiceId? = parse(value)

        internal fun fromModel(value: String): KokoroVoiceId? = parse(value)

        private fun parse(value: String?): KokoroVoiceId? {
            val normalized = value?.trim()?.takeIf { id ->
                id.isNotEmpty() && id.length <= 64 && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }
            } ?: return null
            return KokoroVoiceId(normalized)
        }
    }
}

/** A selectable local voice, resolved against the voices exposed by the installed Kokoro model. */
class KokoroVoice internal constructor(
    val id: KokoroVoiceId,
    val displayName: String,
    val localeTag: String,
    val languageLabel: String,
) {
    companion object {
        private val VOICE_ID = Regex("[abefhijpz][fm]_[a-z0-9_]+")

        internal fun fromModel(info: VoiceInfo, defaultVoiceId: KokoroVoiceId): KokoroVoice? {
            val id = KokoroVoiceId.fromModel(info.voiceId) ?: return null
            // listVoices() enumerates the model bundle. isDownloaded refers to voice-pack state,
            // not whether a voice is embedded in Kokoro's single-file model.
            if (!VOICE_ID.matches(id.value)) return null
            val localeTag = info.locale.trim().ifBlank { "und" }
            val locale = Locale.forLanguageTag(localeTag.replace('_', '-'))
            val languageLabel = locale.getDisplayName(Locale.getDefault())
                .takeIf { locale.language.isNotBlank() && it.isNotBlank() }
                ?: localeTag
            return KokoroVoice(
                id = id,
                displayName = info.displayName.trim().ifBlank { id.value },
                localeTag = localeTag,
                languageLabel = languageLabel,
            )
        }
    }
}
