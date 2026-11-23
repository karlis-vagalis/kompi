package com.kompi.app.audio

import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveEnergyVadTest {
    @Test fun silenceNeverCreatesAnUtterance() {
        val vad = AdaptiveEnergyVad()
        repeat(40) { assertTrue(vad.accept(FloatArray(512)).isEmpty()) }
        assertTrue(vad.flush().isEmpty())
    }

    @Test fun preRollAndSpeechAreEmittedAfterEndpointSilence() {
        val vad = AdaptiveEnergyVad()
        repeat(8) { vad.accept(FloatArray(512)) }
        repeat(12) { vad.accept(FloatArray(512) { 0.12f }) }
        var utterance = emptyList<FloatArray>()
        repeat(20) { utterance = utterance + vad.accept(FloatArray(512)) }
        assertTrue("endpoint should emit one speech segment", utterance.size == 1)
        assertTrue("segment should include the voiced audio", utterance.single().size >= 12 * 512)
        assertTrue("segment must stay within Whistle's 30s VAD window", utterance.single().size <= 30 * 16_000)
    }

    @Test fun pushToTalkFlushReturnsTheLastUtterance() {
        val vad = AdaptiveEnergyVad()
        repeat(10) { vad.accept(FloatArray(512) { 0.08f }) }
        val finished = vad.flush()
        assertTrue(finished.size == 1)
        assertTrue(finished.single().isNotEmpty())
        assertTrue(vad.flush().isEmpty())
    }
}
