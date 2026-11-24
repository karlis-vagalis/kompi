package com.kompi.app.stream

/** Default activity labels for Live display and playback highlighting. */
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
