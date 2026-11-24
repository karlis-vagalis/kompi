package com.kompi.app.audio

import android.media.MediaRecorder


/** Compatibility diagnostic: this communication policy does not request unprocessed capability. */
internal enum class UnprocessedCaptureSupport(val code: Int) { NotRequested(-2) }

internal data class CaptureSourceSelection(
    val configuredSource: Int,
    val requestedSource: Int,
    val unprocessedSupport: UnprocessedCaptureSupport,
) {
    companion object {
        fun forConsumer(consumer: Pcm16kConsumer, configuredSource: Int): CaptureSourceSelection {
            return CaptureSourceSelection(configuredSource, configuredSource, UnprocessedCaptureSupport.NotRequested)
        }
    }
}
