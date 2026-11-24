package com.kompi.app.audio

/** Acquisition-time estimate for the first sample, in Android monotonic time. */
data class CaptureFrameTiming(
    val firstSampleNanos: Long,
    val nanosPerSample: Double = 1_000_000_000.0 / ForegroundMicCapture.SAMPLE_RATE,
    val uncertaintyNanos: Long,
    val hardwareClock: Boolean,
)
