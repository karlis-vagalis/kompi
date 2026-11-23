package com.kompi.app

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kompi.app.audio.*
import com.kompi.app.audio.live.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual PCM → confirmed gate/pre-roll → serialized decoder worker → partial/final ownership.
 * The decoder and classifier seam are controlled; see LiveSpeechClassifierBehaviorTest for the bundled Silero model.
 * Neither suite establishes acoustic device acceptance or ASR accuracy. */
@RunWith(AndroidJUnit4::class)
class LiveRecognizerBehaviorTest {
    private class Decoder : StreamingSpeechDecoder {
        override val chunkSamples = 3_200
        val received = CopyOnWriteArrayList<FloatArray>()
        val threads = CopyOnWriteArrayList<Thread>()
        val entered = CountDownLatch(1)
        var gate: CountDownLatch? = null
        var failure: Exception? = null
        var partial = TranscriptPartial("Stable words", "provisional tail", null)
        var tail = TranscriptPartial("Stable words final", "", null)
        val finishes = AtomicInteger()
        val resets = AtomicInteger()
        val closes = AtomicInteger()
        override fun transcribe(samples: FloatArray): TranscriptPartial {
            threads.add(Thread.currentThread()); received.add(samples.copyOf()); entered.countDown()
            check(gate?.await(5, TimeUnit.SECONDS) != false) { "Test did not release decoder barrier" }
            failure?.let { throw it }
            return partial
        }
        override fun finish(): TranscriptPartial { threads.add(Thread.currentThread()); finishes.incrementAndGet(); return tail }
        override fun reset() { threads.add(Thread.currentThread()); resets.incrementAndGet() }
        override fun close() { threads.add(Thread.currentThread()); closes.incrementAndGet() }
    }
    private class Observer : LocalLiveListener {
        val onsets = CopyOnWriteArrayList<SpeechUtteranceId>()
        val bargeIns = CopyOnWriteArrayList<SpeechUtteranceId>()
        val partials = CopyOnWriteArrayList<Pair<SpeechUtteranceId, TranscriptPartial>>()
        val finals = CopyOnWriteArrayList<Pair<SpeechUtteranceId, NonBlankTranscript>>()
        val errors = CopyOnWriteArrayList<String>()
        val finalSignal = java.util.concurrent.Semaphore(0)
        val partialSignal = java.util.concurrent.Semaphore(0)
        val errorSignal = CountDownLatch(1)
        override fun onSpeechStarted(utteranceId: SpeechUtteranceId) { onsets.add(utteranceId) }
        override fun onBargeInConfirmed(utteranceId: SpeechUtteranceId) { bargeIns.add(utteranceId) }
        override fun onMeter(meter: CaptureMeter) {}
        override fun onPartial(utteranceId: SpeechUtteranceId, partial: TranscriptPartial) { partials.add(utteranceId to partial); partialSignal.release() }
        override fun onFinal(utteranceId: SpeechUtteranceId, transcript: NonBlankTranscript) { finals.add(utteranceId to transcript); finalSignal.release() }
        override fun onError(message: String) { errors.add(message); errorSignal.countDown() }
        fun awaitFinal() { assertTrue("No finalized utterance; errors=$errors", finalSignal.tryAcquire(5, TimeUnit.SECONDS)) }
        fun awaitPartials(count: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (partials.size < count) {
                val remaining = deadline - System.nanoTime()
                assertTrue("Only ${partials.size} partial snapshots; errors=$errors",
                    remaining > 0 && partialSignal.tryAcquire(remaining, TimeUnit.NANOSECONDS))
            }
        }
    }
    private val decoders = mutableListOf<Decoder>()
    private val recognizers = mutableListOf<LocalLiveRecognizer>()
    private fun pipeline(decoder: Decoder = Decoder(), observer: Observer = Observer()): Triple<LocalLiveRecognizer, Decoder, Observer> {
        decoders.add(decoder)
        // Deterministic test seam for decoder ownership. Actual pinned Silero behavior is tested
        // separately against generated speech and nuisance PCM in LiveSpeechClassifierBehaviorTest.
        val testClassifier = SpeechFrameClassifier { frame ->
            SpeechFrameDecision.categorical(frame.any { kotlin.math.abs(it) >= 0.02f })
        }
        val recognizer = LocalLiveRecognizer(decoder, null, observer, EchoCancellationPolicy.Disabled, null, {}, testClassifier)
        recognizers.add(recognizer)
        return Triple(recognizer, decoder, observer)
    }
    private fun frames(recognizer: LocalLiveRecognizer, count: Int, amplitude: Float) {
        repeat(count) { recognizer.onAudio(FloatArray(320) { sample -> if (sample % 2 == 0) amplitude else -amplitude }) }
    }
    private fun speech(recognizer: LocalLiveRecognizer) { frames(recognizer, 12, 0.1f) }
    private fun endpoint(recognizer: LocalLiveRecognizer) { frames(recognizer, 30, 0f) }
    @After fun cleanUp() {
        decoders.forEach { it.gate?.countDown() }
        recognizers.forEach { it.discardAndJoin() }
    }

    @Test fun l13_silenceEvenWithHallucinatedDecoderTailNeverCreatesUtterance() {
        val (r, d, o) = pipeline()
        d.tail = TranscriptPartial("Hallucinated words without speech", "", null)
        frames(r, 50, 0f)
        val finished = CountDownLatch(1)
        r.finish { finished.countDown() }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        r.awaitInferenceTermination()
        assertTrue(o.onsets.isEmpty()); assertTrue(o.finals.isEmpty()); assertTrue(d.received.isEmpty())
    }

    @Test fun l14_shortEnergyBurstDoesNotReachDecoderOrInterrupt() {
        val (r, d, o) = pipeline()
        frames(r, 20, 0f); frames(r, 4, 0.1f); endpoint(r)
        r.discardAndJoin()
        assertTrue(o.onsets.isEmpty()); assertTrue(o.finals.isEmpty()); assertTrue(d.received.isEmpty())
    }

    @Test fun l15_prerollAndFirstLoudFramesReachDecoderWithoutLosingOnset() {
        val (r, d, o) = pipeline()
        frames(r, 20, 0f); frames(r, 10, 0.004f); speech(r); endpoint(r)
        o.awaitFinal()
        val pcm = d.received.flatMap { it.asList() }
        assertEquals(1, o.onsets.size)
        val quietPrefix = pcm.indexOfFirst { kotlin.math.abs(it) == 0.004f }
        val loudOnset = pcm.indexOfFirst { kotlin.math.abs(it) == 0.1f }
        assertTrue("Bounded pre-roll must precede onset PCM", quietPrefix >= 0 && loudOnset > quietPrefix)
        assertEquals("Stable words final", o.finals.single().second.text)
    }

    @Test fun l74_speechAdmissionPrecedesIndependentBargeInConfirmation() {
        val (r, _, o) = pipeline()
        frames(r, 10, 0.1f) // The sixth 32 ms model frame admits at 192 ms; capture has written 200 ms.
        assertEquals(1, o.onsets.size)
        assertTrue(o.bargeIns.isEmpty())
        frames(r, 4, 0.1f) // Eight full model frames are 256 ms, below the separate 260 ms barge-in target.
        assertTrue(o.bargeIns.isEmpty())
        frames(r, 1, 0.1f) // The ninth model frame crosses barge-in at 288 ms.
        assertEquals(o.onsets.single(), o.bargeIns.single())
    }

    @Test fun l16_actualPartialSnapshotsArriveDuringContinuousPcmBeforeVadEndpoint() {
        val (r, d, o) = pipeline()
        d.partial = TranscriptPartial("First committed", "replaceable")
        speech(r)
        o.awaitPartials(1)
        assertEquals("First committed", o.partials.last().second.committedText)
        assertEquals("replaceable", o.partials.last().second.pendingText)
        assertTrue("No pause/final boundary occurred", o.finals.isEmpty())
        d.partial = TranscriptPartial("First committed", "revised tail")
        frames(r, 12, 0.1f)
        o.awaitPartials(2)
        assertEquals("Committed snapshot must remain cumulative and unchanged", "First committed", o.partials.last().second.committedText)
        assertEquals("Only the pending tail is replaced", "revised tail", o.partials.last().second.pendingText)
        assertTrue("The user remains in one continuous utterance", o.finals.isEmpty())
    }

    @Test fun l24_blankInterimAndEmptyFinalRetractPriorWordsWithoutCreatingIntent() {
        val (r, d, o) = pipeline()
        d.partial = TranscriptPartial("Actual words", "provisional")
        speech(r)
        o.awaitPartials(1)
        assertEquals("Actual words provisional", o.partials.last().second.let { listOf(it.committedText, it.pendingText).joinToString(" ") })
        d.partial = TranscriptPartial("", "")
        frames(r, 12, 0.1f)
        o.awaitPartials(2)
        assertTrue(o.partials.last().second.committedText.isBlank())
        assertTrue(o.partials.last().second.pendingText.isBlank())
        assertTrue("Blank retraction alone cannot submit an intent", o.finals.isEmpty())
        d.tail = TranscriptPartial("", "")
        val finished = CountDownLatch(1)
        r.finish { finished.countDown() }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        r.awaitInferenceTermination()
        assertTrue(o.finals.isEmpty())
        assertTrue(o.errors.isEmpty())
    }

    @Test fun l17_finalCumulativeTranscriptDoesNotDuplicateCommittedWordsOrInterim() {
        val (r, d, o) = pipeline()
        d.partial = TranscriptPartial("Keep these", "old interim", null)
        d.tail = TranscriptPartial("Keep these final words", "", null)
        speech(r); endpoint(r); o.awaitFinal()
        assertEquals("Keep these final words", o.finals.single().second.text)
        assertTrue(o.partials.all { it.second.committedText == "Keep these" })
        assertFalse(o.finals.single().second.text.contains("old interim"))
    }

    @Test fun l18_closeWhileNativeDecodeBlockedSuppressesLatePartialAndFinal() {
        val (r, d, o) = pipeline()
        d.gate = CountDownLatch(1)
        speech(r)
        assertTrue(d.entered.await(5, TimeUnit.SECONDS))
        r.close(); d.gate!!.countDown(); r.discardAndJoin()
        assertTrue(o.partials.isEmpty()); assertTrue(o.finals.isEmpty()); assertEquals(1, d.closes.get())
    }

    @Test fun l21_l94_decoderBacklogFailsExplicitlyAndStopsAccepting() {
        val (r, d, o) = pipeline()
        d.gate = CountDownLatch(1)
        speech(r)
        assertTrue(d.entered.await(5, TimeUnit.SECONDS))
        frames(r, 200, 0.1f)
        assertTrue(o.errorSignal.await(5, TimeUnit.SECONDS))
        d.gate!!.countDown(); r.discardAndJoin()
        assertEquals(1, o.errors.size)
        assertTrue(o.errors.single().contains("behind") || o.errors.single().contains("finalize"))
        assertTrue(o.finals.isEmpty())
    }

    @Test fun l22_briefIntraUtterancePauseDoesNotCommitTwoRequests() {
        val (r, _, o) = pipeline()
        speech(r); frames(r, 8, 0f); speech(r); endpoint(r); o.awaitFinal()
        assertEquals(1, o.onsets.size); assertEquals(1, o.finals.size)
        assertEquals(o.onsets.single(), o.finals.single().first)
    }

    @Test fun l23_unicodeAndReportedLanguageSurviveRecognizerPipeline() {
        val (r, d, o) = pipeline()
        d.partial = TranscriptPartial("Latviešu teksts", "12.50 EUR", "lv")
        d.tail = TranscriptPartial("Latviešu teksts 12.50 EUR 🎧", "", "lv")
        speech(r); endpoint(r); o.awaitFinal()
        assertEquals("Latviešu teksts 12.50 EUR 🎧", o.finals.single().second.text)
        assertEquals("lv", o.finals.single().second.language)
    }

    @Test fun l36_consecutiveVadUtterancesHaveDistinctOrderedOwners() {
        val (r, d, o) = pipeline()
        speech(r); endpoint(r); o.awaitFinal()
        speech(r); endpoint(r); o.awaitFinal()
        r.discardAndJoin()
        assertEquals(listOf(1L, 2L), o.finals.map { it.first.value })
        assertEquals(listOf(1L, 2L), o.onsets.map { it.value })
        assertEquals(2, d.resets.get())
        assertTrue(d.threads.all { it !== Looper.getMainLooper().thread })
    }

    @Test fun l54_unknownLanguageIsNotInventedFromVoiceOrSystemLocale() {
        val (r, _, o) = pipeline()
        speech(r); endpoint(r); o.awaitFinal()
        assertNull(o.finals.single().second.language)
        assertTrue(o.partials.all { it.second.language == null })
    }

    @Test fun l67_decoderFailureIsSingleTerminalOutcomeAndLatePcmCannotReviveIt() {
        val (r, d, o) = pipeline()
        d.failure = IllegalStateException("Controlled decoder failure")
        speech(r)
        assertTrue(o.errorSignal.await(5, TimeUnit.SECONDS))
        frames(r, 50, 0.1f); r.discardAndJoin()
        assertEquals(listOf("Controlled decoder failure"), o.errors)
        assertTrue(o.finals.isEmpty()); assertEquals(1, d.closes.get())
    }

    @Test fun l73_endDiscardsUnfinishedWordsRatherThanFlushingAnUnexpectedTurn() {
        val (r, d, o) = pipeline()
        speech(r)
        assertTrue(d.entered.await(5, TimeUnit.SECONDS))
        r.discardAndJoin()
        assertTrue(o.finals.isEmpty()); assertEquals(1, d.closes.get())
    }
}
