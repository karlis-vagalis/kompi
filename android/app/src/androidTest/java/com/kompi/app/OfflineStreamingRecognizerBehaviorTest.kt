package com.kompi.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kompi.app.audio.DictationDraftMerge
import com.kompi.app.audio.DictationDraftMerger
import com.kompi.app.audio.OfflineStreamingRecognizer
import com.kompi.app.audio.OfflineTranscript
import com.kompi.app.audio.DEFAULT_STREAMING_DECODER_QUEUE_CAPACITY
import com.kompi.app.audio.WHISTLE_STREAMING_FEED_SAMPLES
import com.kompi.app.audio.StreamingSpeechDecoder
import com.kompi.app.audio.live.TranscriptPartial
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled selected-decoder snapshots through energy VAD and the actual PTT streaming adapter. */
@RunWith(AndroidJUnit4::class)
class OfflineStreamingRecognizerBehaviorTest {
    @Test fun suspendedStartTicketIsRetiredByContextLossAndReplacement() {
        val fence = com.kompi.app.audio.DictationStartFence()
        val old = fence.request()
        assertFalse(com.kompi.app.audio.dictationSendAllowed(
            com.kompi.app.audio.OfflineSpeechSessionPhase.Idle, false, fence.hasRequest))
        assertTrue(fence.isCurrent(old))
        fence.invalidate()
        assertFalse(fence.isCurrent(old))
        assertFalse(fence.hasRequest)
        assertTrue(com.kompi.app.audio.dictationSendAllowed(
            com.kompi.app.audio.OfflineSpeechSessionPhase.Idle, false, fence.hasRequest))
        val replacement = fence.request()
        assertFalse(fence.isCurrent(old))
        assertTrue(fence.isCurrent(replacement))
        fence.invalidate()
        assertFalse(fence.isCurrent(replacement))
    }

    @Test fun engineQueueBudgetsUseDecoderFeedsAnd512SampleFrameAlignment() {
        fun alignedSamples(target: Int) = ((target + 511) / 512) * 512
        val whistleQueueSamples = DEFAULT_STREAMING_DECODER_QUEUE_CAPACITY * alignedSamples(WHISTLE_STREAMING_FEED_SAMPLES)
        val parakeetQueueSamples = DEFAULT_STREAMING_DECODER_QUEUE_CAPACITY * alignedSamples(3_200)
        assertEquals(8, DEFAULT_STREAMING_DECODER_QUEUE_CAPACITY)
        assertEquals("Live and offline executors use the decoder-owned default", 8, Decoder().maxQueuedChunks)
        assertEquals(16_000, WHISTLE_STREAMING_FEED_SAMPLES)
        assertEquals(131_072, whistleQueueSamples) // 8 × 32 Silero frames = 8.192 s queued PCM.
        assertEquals(28_672, parakeetQueueSamples) // 8 × 7 frames = 1.792 s queued PCM.
        assertEquals(8_192, whistleQueueSamples * 1_000 / 16_000)
        assertEquals(1_792, parakeetQueueSamples * 1_000 / 16_000)
    }

    @Test fun finalMergeUsesCurrentSelectionAndPreservesDraftAroundIt() {
        val result = DictationDraftMerger.merge("typed review later", 6, 12, "updated words")
        assertEquals(DictationDraftMerge.Applied("typed updated words later", 19), result)
    }

    @Test fun finalMergeRefusesSilentTruncationAtDraftLimit() {
        assertEquals(DictationDraftMerge.DraftLimitExceeded,
            DictationDraftMerger.merge("typed", 5, 5, "x".repeat(16_000)))
    }

    private class Decoder : StreamingSpeechDecoder {
        override val chunkSamples = 3_200
        val pcm = CopyOnWriteArrayList<FloatArray>()
        val entered = CountDownLatch(1)
        var gate: CountDownLatch? = null
        @Volatile var update = TranscriptPartial("Recognized", "first tail")
        var final = TranscriptPartial("Recognized", "second tail")
        val finishCalls = java.util.concurrent.atomic.AtomicInteger()
        override fun transcribe(samples: FloatArray): TranscriptPartial {
            pcm += samples
            entered.countDown()
            check(gate?.await(5, TimeUnit.SECONDS) != false) { "Test did not release decoder barrier" }
            return update
        }
        override fun finish(): TranscriptPartial { finishCalls.incrementAndGet(); return final }
        override fun reset() = Unit
        override fun close() = Unit
    }

    private fun activePcm(amplitude: Float) = FloatArray(320) { if (it % 2 == 0) amplitude else -amplitude }

    @Test fun shortEnergyNoiseNeverReachesDecoderOrPublishesHallucinatedFinalTail() {
        val decoder = Decoder().apply { final = TranscriptPartial("Hallucinated", "words") }
        val partials = CopyOnWriteArrayList<OfflineTranscript>()
        val finals = CopyOnWriteArrayList<OfflineTranscript>()
        val recognizer = OfflineStreamingRecognizer(decoder, { partials += it }, { finals += it }) { fail(it) }
        try {
            repeat(4) { recognizer.onAudio(activePcm(0.1f)) } // 80 ms: under PTT's 150 ms speech gate.
            repeat(35) { recognizer.onAudio(activePcm(0f)) } // Long enough to exercise endpoint discard.
            recognizer.finishUtterance()
            assertTrue(decoder.pcm.isEmpty())
            assertTrue(partials.isEmpty())
            assertTrue(finals.isEmpty())
        } finally { recognizer.closeAndJoin() }
    }

    @Test fun cancellationFencesDecoderResultThatReturnsAfterStop() {
        val decoder = Decoder().apply { gate = CountDownLatch(1) }
        val partials = CopyOnWriteArrayList<OfflineTranscript>()
        val finals = CopyOnWriteArrayList<OfflineTranscript>()
        val recognizer = OfflineStreamingRecognizer(decoder, { partials += it }, { finals += it }) { fail(it) }
        repeat(12) { recognizer.onAudio(activePcm(0.1f)) }
        assertTrue("The decoder should have started", decoder.entered.await(5, TimeUnit.SECONDS))
        recognizer.close()
        decoder.gate!!.countDown()
        recognizer.closeAndJoin()
        assertTrue(partials.isEmpty())
        assertTrue(finals.isEmpty())
    }

    @Test fun actualInterimRevisionsAreVisibleDuringContinuousSpeechAndFinalAppearsOnce() {
        val decoder = Decoder()
        val partials = CopyOnWriteArrayList<OfflineTranscript>()
        val finals = CopyOnWriteArrayList<OfflineTranscript>()
        val firstPartialSignal = CountDownLatch(1)
        val revisionSignal = CountDownLatch(1)
        val finalSignal = CountDownLatch(1)
        val recognizer = OfflineStreamingRecognizer(
            decoder,
            onPartial = {
                partials += it
                if (it.text == "Recognized first tail") firstPartialSignal.countDown()
                if (it.text == "Recognized second tail") revisionSignal.countDown()
            },
            onTranscript = { finals += it; finalSignal.countDown() },
            onError = { fail(it) },
        )
        try {
            repeat(12) { recognizer.onAudio(activePcm(0.1f)) }
            assertTrue("First decoder words should arrive before a VAD pause", firstPartialSignal.await(5, TimeUnit.SECONDS))
            assertTrue(decoder.pcm.isNotEmpty())
            assertTrue(finals.isEmpty())
            decoder.update = TranscriptPartial("Recognized", "second tail")
            repeat(12) { recognizer.onAudio(activePcm(0.1f)) }
            assertTrue("Changed decoder pending tail should be delivered during continuous speech", revisionSignal.await(5, TimeUnit.SECONDS))
            assertTrue(partials.size >= 2)
            assertEquals("Recognized second tail", partials.last().text)
            assertTrue(finals.isEmpty())
            repeat(30) { recognizer.onAudio(activePcm(0f)) }
            assertTrue("Endpoint final should complete", finalSignal.await(5, TimeUnit.SECONDS))
            assertEquals(1, finals.size)
            assertEquals("Recognized second tail", finals.single().text)
        } finally { recognizer.closeAndJoin() }
    }

    @Test fun finishDrainThenImmediateClosePreservesExactlyOneFinal() {
        val decoder = Decoder().apply { update = TranscriptPartial("Flush words", "tail"); final = TranscriptPartial("Flush words", "tail") }
        val partials = CopyOnWriteArrayList<OfflineTranscript>()
        val finals = CopyOnWriteArrayList<OfflineTranscript>()
        val recognizer = OfflineStreamingRecognizer(decoder, { partials += it }, { finals += it }) { fail(it) }
        repeat(12) { recognizer.onAudio(activePcm(0.1f)) }
        // This is OfflineSpeechSession's graceful stop order: flush/drain, then close immediately.
        try {
            recognizer.finishUtteranceAndJoin()
            recognizer.close()
            recognizer.closeAndJoin()
            assertEquals(1, finals.size)
            assertEquals("Flush words tail", finals.single().text)
            assertEquals(1, decoder.finishCalls.get())
        } finally { recognizer.closeAndJoin() }
    }

    @Test fun realEmptyDecoderRevisionRetractsThePriorPartialWithoutSubmittingBlank() {
        val decoder = Decoder().apply { update = TranscriptPartial("Actual", "words"); final = TranscriptPartial("", "") }
        val partials = CopyOnWriteArrayList<OfflineTranscript>()
        val finals = CopyOnWriteArrayList<OfflineTranscript>()
        val first = CountDownLatch(1)
        val retracted = CountDownLatch(1)
        val recognizer = OfflineStreamingRecognizer(decoder, { result ->
            partials += result
            if (result.text == "Actual words") first.countDown()
            if (result.text.isBlank()) retracted.countDown()
        }, { finals += it }) { fail(it) }
        try {
            repeat(12) { recognizer.onAudio(activePcm(0.1f)) }
            assertTrue(first.await(5, TimeUnit.SECONDS))
            decoder.update = TranscriptPartial("", "")
            repeat(12) { recognizer.onAudio(activePcm(0.1f)) }
            assertTrue("A genuine empty snapshot must retract an old provisional display", retracted.await(5, TimeUnit.SECONDS))
            repeat(35) { recognizer.onAudio(activePcm(0f)) }
            recognizer.finishUtteranceAndJoin()
            assertTrue(finals.isEmpty())
            assertTrue(partials.last().text.isBlank())
        } finally { recognizer.closeAndJoin() }
        assertTrue("Blank finalization is not a transcript submission", finals.isEmpty())
    }
}
