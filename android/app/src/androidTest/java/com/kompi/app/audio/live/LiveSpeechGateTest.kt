package com.kompi.app.audio.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSpeechGateTest {
    private class ScriptedClassifier(
        decisions: List<Boolean>,
        override val frameSamples: Int = SileroSpeechClassifier.SILERO_FRAME_SAMPLES,
    ) : SpeechFrameClassifier {
        private val frames = ArrayDeque(decisions)
        var classifiedFrames = 0
        var closed = false
        override fun classify(frame: FloatArray): SpeechFrameDecision {
            assertEquals(frameSamples, frame.size)
            classifiedFrames++
            return SpeechFrameDecision.categorical(frames.removeFirstOrNull() ?: false)
        }
        override fun close() { closed = true }
    }

    private fun pcm(frames: Int, samplesPerWrite: Int = SileroSpeechClassifier.SILERO_FRAME_SAMPLES): FloatArray =
        FloatArray(frames * samplesPerWrite)

    @Test fun onsetGapIsCountedOnTheModelCadenceAndShortCandidatesDoNotTrigger() {
        val hysteresisClassifier = ScriptedClassifier(List(5) { true } + listOf(false, true))
        val hysteresisGate = LiveSpeechGate(hysteresisClassifier)
        assertEquals(listOf(LiveSpeechGateEvent.SpeechConfirmed), hysteresisGate.accept(pcm(7)))
        assertTrue(hysteresisGate.flush()) // A classifier-confirmed candidate is flushable.
        hysteresisGate.close()

        val shortClassifier = ScriptedClassifier(List(4) { true } + listOf(false, true) + List(20) { false })
        val shortGate = LiveSpeechGate(shortClassifier)
        assertTrue(shortGate.accept(pcm(6)).isEmpty()) // 128 ms speech + one 32 ms gap + 32 ms = 160 ms.
        assertTrue(shortGate.accept(pcm(20)).isEmpty()) // The shorter candidate expires without admission.
        assertFalse(shortGate.flush())
        shortGate.close()
        assertTrue(shortClassifier.closed)
    }

    @Test fun sileroCadenceRoundsAdmissionBargeInAndEndpointOnElapsedSamples() {
        val classifier = ScriptedClassifier(List(9) { true } + List(18) { false })
        val gate = LiveSpeechGate(classifier)
        assertEquals(listOf(LiveSpeechGateEvent.SpeechConfirmed), gate.accept(pcm(6))) // 192 ms; target is 180 ms.
        assertTrue(gate.accept(pcm(2)).isEmpty()) // 256 ms; barge-in waits for another complete frame.
        assertEquals(listOf(LiveSpeechGateEvent.BargeInConfirmed), gate.accept(pcm(1))) // 288 ms; target is 260 ms.
        assertTrue(gate.accept(pcm(17)).isEmpty()) // 544 ms negative; target endpoint is 550 ms.
        assertEquals(listOf(LiveSpeechGateEvent.UtteranceEnded), gate.accept(pcm(1))) // 576 ms.
        assertFalse(gate.flush())
        gate.close()
    }

    @Test fun bargeEvidenceResetsAfterAtLeastOneHundredMillisecondsWithoutEndingTurn() {
        val classifier = ScriptedClassifier(List(6) { true } + List(4) { false } + List(9) { true })
        val gate = LiveSpeechGate(classifier)
        assertEquals(listOf(LiveSpeechGateEvent.SpeechConfirmed), gate.accept(pcm(6)))
        assertTrue(gate.accept(pcm(4)).isEmpty()) // 128 ms clears barge evidence, not the admitted utterance.
        assertTrue(gate.accept(pcm(8)).isEmpty()) // 256 ms new evidence is below the 288 ms cadence boundary.
        assertEquals(listOf(LiveSpeechGateEvent.BargeInConfirmed), gate.accept(pcm(1)))
        gate.close()
    }

    @Test fun flushOnlyAuthorizesAConfirmedTurn() {
        val gate = LiveSpeechGate(ScriptedClassifier(List(6) { true }))
        assertEquals(listOf(LiveSpeechGateEvent.SpeechConfirmed), gate.accept(pcm(6)))
        assertTrue(gate.flush())
        assertFalse(gate.flush())
        gate.close()
    }

    @Test fun partialModelFrameWaitsAndIrregularCaptureWritesKeepEachSampleExactlyOnce() {
        val decisions = List(6) { true } + List(18) { false }
        val classifierA = ScriptedClassifier(decisions)
        val classifierB = ScriptedClassifier(decisions)
        val gateA = LiveSpeechGate(classifierA)
        val gateB = LiveSpeechGate(classifierB)
        val samples = FloatArray(512 * 24) { ((it % 23) - 11) / 16f }
        assertTrue(gateA.accept(samples.copyOfRange(0, 511)).isEmpty())
        val expected = ArrayList<LiveSpeechGateEvent>()
        expected += gateA.accept(samples.copyOfRange(511, samples.size))
        val actual = ArrayList<LiveSpeechGateEvent>()
        var offset = 0
        val blockSizes = intArrayOf(113, 901, 7, 512, 37, 2048, 73)
        var nextBlock = 0
        while (offset < samples.size) {
            val end = minOf(samples.size, offset + blockSizes[nextBlock++ % blockSizes.size])
            actual += gateB.accept(samples.copyOfRange(offset, end))
            offset = end
        }
        assertEquals(expected, actual)
        assertEquals(24, classifierA.classifiedFrames)
        assertEquals(24, classifierB.classifiedFrames)
        gateA.close(); gateB.close()
    }
}
