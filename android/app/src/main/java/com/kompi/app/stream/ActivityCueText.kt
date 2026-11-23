package com.kompi.app.stream

/** Shared fixed-cue suffixes for speech, Live activity text and playback highlighting. */
internal object ActivityCueText {
    const val Thinking = "thinking it through"
    const val Tools = "checking a few things"

    fun thinkingTitle(title: String): String = if (title == "thinking") Thinking else title
    fun activity(label: String): String = when (label) {
        "Thinking" -> Thinking
        "Using tools" -> Tools
        else -> label
    }
}
