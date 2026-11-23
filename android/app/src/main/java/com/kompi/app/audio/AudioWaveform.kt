package com.kompi.app.audio

import kotlin.math.abs

/** A bounded, immutable 20 ms view of real PCM. Audio is never retained beyond this display frame. */
class AudioWaveform private constructor(private val samples: FloatArray, private val peak: Float) {
    val size: Int get() = samples.size
    fun normalizedSample(index: Int): Float = samples[index] / peak.coerceAtLeast(0.001f)

    companion object {
        private const val POINTS = 96
        val Silence = AudioWaveform(FloatArray(POINTS), 0f)

        fun fromPcm(pcm: FloatArray, sampleRateHz: Int, start: Int = 0, count: Int = pcm.size - start): AudioWaveform {
            if (count == 0) return Silence
            val window = minOf(count, sampleRateHz / 50)
            val offset = start + (count - window) / 2
            val points = FloatArray(POINTS) { point ->
                val start = point * window / POINTS
                val end = maxOf(start + 1, (point + 1) * window / POINTS).coerceAtMost(window)
                var sum = 0f
                for (index in start until end) {
                    val sample = pcm[offset + index]
                    sum += if (sample.isFinite()) sample.coerceIn(-1f, 1f) else 0f
                }
                sum / (end - start)
            }
            return AudioWaveform(points, points.maxOf(::abs))
        }
    }
}
