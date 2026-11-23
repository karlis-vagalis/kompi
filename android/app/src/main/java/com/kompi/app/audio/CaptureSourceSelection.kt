package com.kompi.app.audio

import android.media.MediaRecorder

/** A software echo processor borrowing the retained Android communication-mode/route lease. */
internal interface SoftwareEchoCaptureConsumer : Pcm16kConsumer

/** Compatibility diagnostic: this communication policy does not request unprocessed capability. */
internal enum class UnprocessedCaptureSupport(val code: Int) { NotRequested(-2) }

internal data class CaptureSourceSelection(
    val configuredSource: Int,
    val requestedSource: Int,
    val unprocessedSupport: UnprocessedCaptureSupport,
) {
    companion object {
        fun forConsumer(consumer: Pcm16kConsumer, configuredSource: Int): CaptureSourceSelection {
            // Android 15's active mode-owner callback counts VOICE_COMMUNICATION recording,
            // not recognition/raw recording. Keeping MODE_IN_COMMUNICATION while using a raw
            // source is therefore not a stable lease contract. Do not emulate activity with
            // dummy output, repeatedly set mode, or relax the route guard.
            // WebRTC uses this source plus explicit per-session AEC/NS OFF controls; our
            // SoftwareOwned effect policy additionally controls public AGC when available.
            // Noncommunication consumers (including dictation) retain their configured source.
            val source = if (consumer is SoftwareEchoCaptureConsumer)
                MediaRecorder.AudioSource.VOICE_COMMUNICATION else configuredSource
            return CaptureSourceSelection(configuredSource, source, UnprocessedCaptureSupport.NotRequested)
        }
    }
}
