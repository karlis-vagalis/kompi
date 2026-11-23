package com.kompi.app.audio

/** Local identity of one VAD-bounded utterance within a microphone capture generation. */
@JvmInline
value class SpeechUtteranceId internal constructor(val value: Long) : Comparable<SpeechUtteranceId> {
    override fun compareTo(other: SpeechUtteranceId): Int = value.compareTo(other.value)
}
