package com.kompi.app.stream

import android.content.SharedPreferences

/** The thinking lead-in and the entire Live tool announcement are independent preferences. */
internal data class ActivitySpeechText(
    val thinkingPrefix: String = DefaultThinkingPrefix,
    val toolMessage: String = DefaultToolMessage,
) {
    val toolPhrase: String get() = toolMessage.take(MaxToolMessageLength)
        .takeIf { it.any(Char::isLetterOrDigit) } ?: DefaultToolMessage

    fun thinkingPhrase(title: String): String = listOf(thinkingPrefix.trim().take(MaxThinkingPrefixLength), title)
        .filter(String::isNotEmpty).joinToString(" ")

    companion object {
        const val DefaultThinkingPrefix = "I'm"
        const val DefaultToolMessage = "I'm checking a few things"
        const val MaxThinkingPrefixLength = 39
        const val MaxToolMessageLength = 120
        private const val PrefixKey = "thinkingSpeechPrefix"
        private const val ToolKey = "liveToolSpeechMessage"

        fun read(settings: SharedPreferences) = ActivitySpeechText(
            thinkingPrefix = settings.getString(PrefixKey, DefaultThinkingPrefix).orEmpty(),
            toolMessage = settings.getString(ToolKey, DefaultToolMessage) ?: DefaultToolMessage,
        )

        fun saveThinkingPrefix(settings: SharedPreferences, prefix: String) {
            settings.edit().putString(PrefixKey, prefix.take(MaxThinkingPrefixLength)).apply()
        }

        fun saveToolMessage(settings: SharedPreferences, message: String) {
            settings.edit().putString(ToolKey, message.take(MaxToolMessageLength)).apply()
        }
    }
}
