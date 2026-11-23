package com.kompi.app.audio.live

import java.io.Closeable

/** Gate transitions are events, not a confidence score or claim of speaker identity. */
internal enum class LiveSpeechGateEvent { SpeechConfirmed, BargeInConfirmed, UtteranceEnded }

/**
 * Classifies continuous capture at the selected local model's fixed cadence, then applies separate
 * hysteresis/endpoint timing using elapsed 16 kHz samples. The classifier never receives persisted
 * or transmitted audio.
 *
 * Target hysteresis is 180 ms onset, 60 ms onset-gap, 260 ms separate barge-in evidence with its
 * own 100 ms gap reset, and 550 ms endpoint silence. Decisions can only happen on a complete model
 * frame, so Silero's 512-sample/32 ms cadence rounds those thresholds up to 192/64/288/128/576 ms;
 * all accounting is from samples, not assumed AudioRecord callback/frame counts. During an
 * admitted turn's quiet tail, an isolated positive frame does not restart the endpoint timer.
 * AudioRecord blocks can be split or coalesced without changing model framing, and the bounded
 * assembler retains each sample exactly once.
 */
internal class LiveSpeechGate(
    private val classifier: SpeechFrameClassifier,
    private val frameSamples: Int = classifier.frameSamples,
    private val onsetSpeechMs: Int = 180,
    private val onsetGapResetMs: Int = 60,
    private val bargeInSpeechMs: Int = 260,
    private val bargeInGapResetMs: Int = 100,
    private val endSilenceMs: Int = 550,
    private val maxUtteranceMs: Int = 30_000,
) : Closeable {
    private val vadFrame = FloatArray(frameSamples)
    private var vadFrameSamples = 0
    private var candidate = false
    private var confirmed = false
    private var positiveSamples = 0
    private var candidateSilenceSamples = 0
    private var bargeInEvidenceSamples = 0
    private var bargeInConfirmed = false
    private var bargeInSilenceSamples = 0
    private var endpointQuietSamples = 0
    private var possibleSpeechResume = false
    private var utteranceSamples = 0
    private var closed = false

    init {
        require(frameSamples == classifier.frameSamples && frameSamples > 0) { "Gate cadence must match the classifier's verified frame size" }
        require(onsetSpeechMs > 0 && onsetGapResetMs >= 0 && bargeInSpeechMs >= onsetSpeechMs &&
            bargeInGapResetMs >= 0 && endSilenceMs > 0 && maxUtteranceMs >= onsetSpeechMs)
    }

    /** Returns every transition caused by this PCM block; partial VAD frames wait for more audio. */
    @Synchronized
    fun accept(samples: FloatArray): List<LiveSpeechGateEvent> {
        check(!closed) { "Live speech gate is closed" }
        if (samples.isEmpty()) return emptyList()
        val events = ArrayList<LiveSpeechGateEvent>(2)
        for (sample in samples) {
            vadFrame[vadFrameSamples++] = sample
            if (vadFrameSamples == vadFrame.size) {
                consumeFrame(classifier.classify(vadFrame).speech, events)
                vadFrameSamples = 0
            }
        }
        return events
    }

    /** Flush only an already confirmed user turn; unconfirmed noise/candidates are discarded. */
    @Synchronized
    fun flush(): Boolean {
        if (closed || !confirmed) {
            resetTurn()
            return false
        }
        resetTurn()
        return true
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        vadFrameSamples = 0
        resetTurn()
        classifier.close()
    }

    private fun consumeFrame(speech: Boolean, events: MutableList<LiveSpeechGateEvent>) {
        if (speech) {
            candidate = true
            positiveSamples += frameSamples
            candidateSilenceSamples = 0
            bargeInSilenceSamples = 0
            if (!confirmed && positiveSamples >= samplesFor(onsetSpeechMs)) {
                confirmed = true
                // Count the bounded onset only after confirmation; a long-lived, never-confirmed
                // candidate must not accumulate as an ever-growing utterance duration.
                utteranceSamples = positiveSamples
                bargeInEvidenceSamples = positiveSamples
                events += LiveSpeechGateEvent.SpeechConfirmed
            } else if (confirmed) {
                utteranceSamples += frameSamples
                bargeInEvidenceSamples += frameSamples
            }
            if (confirmed && !bargeInConfirmed && bargeInEvidenceSamples >= samplesFor(bargeInSpeechMs)) {
                bargeInConfirmed = true
                events += LiveSpeechGateEvent.BargeInConfirmed
            }
        } else if (!candidate) {
            return
        } else {
            candidateSilenceSamples += frameSamples
            if (!confirmed && candidateSilenceSamples >= samplesFor(onsetGapResetMs)) {
                positiveSamples = 0
            }
            if (confirmed) {
                bargeInSilenceSamples += frameSamples
                utteranceSamples += frameSamples
                if (bargeInSilenceSamples >= samplesFor(bargeInGapResetMs)) bargeInEvidenceSamples = 0
            }
        }

        if (confirmed && (advanceEndpoint(speech) || utteranceSamples >= samplesFor(maxUtteranceMs))) {
            events += LiveSpeechGateEvent.UtteranceEnded
            resetTurn()
        } else if (!confirmed && candidateSilenceSamples >= samplesFor(endSilenceMs)) {
            // Discard a speech-like transient that never passed onset confirmation.
            resetTurn()
        }
    }

    /** Endpoint debounce is independent of speech admission and barge-in evidence. */
    private fun advanceEndpoint(speech: Boolean): Boolean {
        if (speech && (endpointQuietSamples == 0 || possibleSpeechResume)) {
            endpointQuietSamples = 0
            possibleSpeechResume = false
            return false
        }
        endpointQuietSamples += frameSamples
        possibleSpeechResume = speech
        // If a positive lands exactly at the deadline, give the next frame a chance to
        // confirm resumed speech. One isolated hit can delay closure by at most one frame.
        return !speech && endpointQuietSamples >= samplesFor(endSilenceMs)
    }

    private fun resetTurn() {
        candidate = false
        confirmed = false
        positiveSamples = 0
        candidateSilenceSamples = 0
        bargeInEvidenceSamples = 0
        bargeInConfirmed = false
        bargeInSilenceSamples = 0
        endpointQuietSamples = 0
        possibleSpeechResume = false
        utteranceSamples = 0
    }

    private fun samplesFor(milliseconds: Int): Int = SAMPLE_RATE * milliseconds / 1_000

    private companion object { const val SAMPLE_RATE = 16_000 }
}
