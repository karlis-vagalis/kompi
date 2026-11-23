package com.kompi.app.audio

import java.io.Closeable

data class OfflineTranscript(val text: String, val language: String?)

/** Local recognizer interface at the foreground 16 kHz mono PCM boundary. */
interface OfflineSpeechRecognizer : Pcm16kConsumer, Closeable {
    fun finishUtterance()

    /** Flush and drain the selected decoder off the capture/main threads before [close]. */
    fun finishUtteranceAndJoin() { finishUtterance() }

    fun closeAndJoin()
}
