package com.kompi.app.live

import com.kompi.app.audio.SpeechInputEngine
import com.kompi.app.audio.SpeechUtteranceId

/** Input progress is independent of reply synthesis/playback and the screen's dominant state. */
sealed interface LiveTranscriptionStatus {
    data object Inactive : LiveTranscriptionStatus
    data class Preparing(val engine: SpeechInputEngine) : LiveTranscriptionStatus
    /** Published only after the first real, route-validated capture frame. */
    data class Listening(val engine: SpeechInputEngine) : LiveTranscriptionStatus
    data class Utterance(
        val engine: SpeechInputEngine,
        val id: SpeechUtteranceId,
        val phase: Phase,
    ) : LiveTranscriptionStatus

    enum class Phase { Receiving, Finalizing }
}
