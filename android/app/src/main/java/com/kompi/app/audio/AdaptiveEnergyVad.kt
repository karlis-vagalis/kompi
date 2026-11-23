package com.kompi.app.audio

import kotlin.math.sqrt

/** Lightweight local endpoint VAD for push-to-talk; emits bounded speech utterances. */
internal class AdaptiveEnergyVad(
    private val sampleRate: Int = ForegroundMicCapture.SAMPLE_RATE,
    private val preRollMs: Int = 180,
    private val endSilenceMs: Int = 550,
    private val minSpeechMs: Int = 150,
    private val maxUtteranceMs: Int = 30_000,
    private val onSpeechStarted: () -> Unit = {},
    private val onActiveFrame: ((FloatArray) -> Unit)? = null,
    private val onUtteranceEnded: (() -> Unit)? = null,
    private val onUtteranceDiscarded: (() -> Unit)? = null,
) {
    private val preRoll = ArrayDeque<FloatArray>()
    private val voiced = ArrayList<FloatArray>()
    private val trailingSilence = ArrayList<FloatArray>()
    private var preRollSamples = 0
    private var utteranceSamples = 0
    private var trailingSamples = 0
    private var noiseFloorRms = 0.0025
    private var active = false
    private var voicedSamples = 0
    private var onsetSamples = 0
    private var onsetQuietSamples = 0
    private var onsetReported = false

    @Synchronized
    fun accept(frame: FloatArray): List<FloatArray> {
        if (frame.isEmpty()) return emptyList()
        val rms = sqrt(frame.sumOf { it.toDouble() * it } / frame.size)
        val threshold = maxOf(0.008, noiseFloorRms * 3.0)
        val speech = rms >= threshold
        if (!active && !speech) {
            noiseFloorRms = (noiseFloorRms * 0.96 + rms * 0.04).coerceIn(0.0005, 0.05)
            rememberPreRoll(frame)
            return emptyList()
        }

        if (!active) {
            active = true
            for (prior in preRoll) append(voiced, prior)
            utteranceSamples = preRollSamples
            preRoll.clear()
            preRollSamples = 0
        }
        if (speech) {
            voicedSamples += frame.size
            onsetSamples += frame.size
            onsetQuietSamples = 0
            val firstConfirmedFrame = !onsetReported && onsetSamples >= sampleRate * minSpeechMs / 1000
            if (!onsetReported) for (silence in trailingSilence) append(voiced, silence)
            utteranceSamples += trailingSamples
            trailingSilence.clear()
            trailingSamples = 0
            if (onActiveFrame == null || !onsetReported) append(voiced, frame)
            utteranceSamples += frame.size
            if (firstConfirmedFrame) {
                onsetReported = true
                onSpeechStarted()
                // Only release bounded pre-roll after the existing minimum-speech proof. Short
                // energy bursts never reach the decoder or become a draft candidate.
                voiced.forEach { onActiveFrame?.invoke(it) }
                if (onActiveFrame != null) voiced.clear()
            } else if (onsetReported) onActiveFrame?.invoke(frame)
        } else {
            onsetQuietSamples += frame.size
            if (!onsetReported && onsetQuietSamples >= sampleRate * 64 / 1000) onsetSamples = 0
            trailingSilence += frame.copyOf()
            if (onsetReported) onActiveFrame?.invoke(frame)
            trailingSamples += frame.size
            if (trailingSamples >= sampleRate * endSilenceMs / 1000) {
                return finishActive(includeTrailingSilence = false)
            }
        }
        if (utteranceSamples >= sampleRate * maxUtteranceMs / 1000) return finishActive(includeTrailingSilence = false)
        return emptyList()
    }

    @Synchronized
    fun flush(): List<FloatArray> {
        if (!active) return emptyList()
        return finishActive(includeTrailingSilence = true)
    }

    private fun rememberPreRoll(frame: FloatArray) {
        preRoll.addLast(frame.copyOf())
        preRollSamples += frame.size
        val limit = sampleRate * preRollMs / 1000
        while (preRollSamples > limit && preRoll.isNotEmpty()) preRollSamples -= preRoll.removeFirst().size
    }

    private fun finishActive(includeTrailingSilence: Boolean): List<FloatArray> {
        val speechWasConfirmed = onsetReported
        if (includeTrailingSilence) {
            if (onActiveFrame == null) for (silence in trailingSilence) append(voiced, silence)
            utteranceSamples += trailingSamples
        }
        val result = if (onActiveFrame != null) emptyList()
            else if (voicedSamples >= sampleRate * minSpeechMs / 1000) listOf(join(voiced)) else emptyList()
        voiced.clear()
        trailingSilence.clear()
        utteranceSamples = 0
        trailingSamples = 0
        active = false
        voicedSamples = 0
        onsetSamples = 0
        onsetQuietSamples = 0
        onsetReported = false
        preRoll.clear()
        preRollSamples = 0
        if (speechWasConfirmed) onUtteranceEnded?.invoke() else onUtteranceDiscarded?.invoke()
        return result
    }

    private fun append(target: MutableList<FloatArray>, frame: FloatArray) { target += frame.copyOf() }

    private fun join(frames: List<FloatArray>): FloatArray {
        val result = FloatArray(frames.sumOf { it.size })
        var offset = 0
        frames.forEach { frame -> frame.copyInto(result, offset); offset += frame.size }
        return result
    }
}
