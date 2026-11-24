package com.kompi.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kompi.app.audio.EchoCancellationPolicy
import com.kompi.app.audio.SpeechUtteranceId
import com.kompi.app.audio.StreamingSpeechDecoder
import com.kompi.app.audio.live.CaptureMeter
import com.kompi.app.audio.live.LiveSpeechGate
import com.kompi.app.audio.live.LiveSpeechGateEvent
import com.kompi.app.audio.live.LocalLiveListener
import com.kompi.app.audio.live.LocalLiveRecognizer
import com.kompi.app.audio.live.NonBlankTranscript
import com.kompi.app.audio.live.SILERO_SPEECH_THRESHOLD
import com.kompi.app.audio.live.SileroSpeechClassifier
import com.kompi.app.audio.live.TranscriptPartial
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs the bundled, hash-verified Silero ONNX model on Android CPU, not the host-only probe. */
@RunWith(AndroidJUnit4::class)
class LiveSpeechClassifierBehaviorTest {
    // The model belongs to the target app; generated speech fixtures belong to the test APK.
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtureContext: Context = InstrumentationRegistry.getInstrumentation().context

    private class Decoder : StreamingSpeechDecoder {
        override val chunkSamples = 3_200
        val chunks = CopyOnWriteArrayList<FloatArray>()
        override fun transcribe(samples: FloatArray): TranscriptPartial {
            chunks += samples.copyOf()
            return TranscriptPartial("Generated speech", "", null)
        }
        override fun finish() = TranscriptPartial("Generated speech confirmed", "", null)
        override fun reset() = Unit
        override fun close() = Unit
    }

    private class Listener : LocalLiveListener {
        val onsets = CopyOnWriteArrayList<SpeechUtteranceId>()
        val bargeIns = CopyOnWriteArrayList<SpeechUtteranceId>()
        val finals = CopyOnWriteArrayList<NonBlankTranscript>()
        val failure = CopyOnWriteArrayList<String>()
        val finalSignal = CountDownLatch(1)
        override fun onSpeechStarted(utteranceId: SpeechUtteranceId) { onsets += utteranceId }
        override fun onBargeInConfirmed(utteranceId: SpeechUtteranceId) { bargeIns += utteranceId }
        override fun onMeter(meter: CaptureMeter) = Unit
        override fun onPartial(utteranceId: SpeechUtteranceId, partial: TranscriptPartial) = Unit
        override fun onFinal(utteranceId: SpeechUtteranceId, transcript: NonBlankTranscript) { finals += transcript; finalSignal.countDown() }
        override fun onError(message: String) { failure += message }
    }

    @Test fun bundledSileroModelRunsThroughActualLocalRecognizerAndFinalizesItsTurn() {
        val decoder = Decoder()
        val listener = Listener()
        val recognizer = LocalLiveRecognizer(decoder, null, listener, EchoCancellationPolicy.Disabled, null, {}, SileroSpeechClassifier(context))
        try {
            val input = fixture("continue") + FloatArray(16_000)
            feedRecognizer(recognizer, input)
            assertTrue("Bundled Silero should admit generated speech; failures=${listener.failure}", listener.finalSignal.await(10, TimeUnit.SECONDS))
            assertEquals(listOf(SpeechUtteranceId(1)), listener.onsets.toList())
            assertEquals(listOf(SpeechUtteranceId(1)), listener.bargeIns.toList())
            assertTrue("Only confirmed classifier PCM should reach the local decoder", decoder.chunks.isNotEmpty())
            assertEquals("Generated speech confirmed", listener.finals.single().text)
            assertTrue(listener.failure.isEmpty())
        } finally { recognizer.discardAndJoin() }
    }

    @Test fun pinnedSileroScoresGeneratedSpeechIncludingSoftAndShorterPhrase() {
        for (id in listOf("review", "review-soft", "continue")) {
            val pcm = fixture(id)
            val scores = scores(pcm, id)
            assertTrue("Silero should positively classify speech in $id: $scores", scores.positiveFrames >= 5)
            val events = gateEvents(SileroSpeechClassifier(context), pcm)
            assertTrue("Silero gate should confirm generated $id speech", LiveSpeechGateEvent.SpeechConfirmed in events)
            assertTrue("Generated $id should separately pass barge-in hysteresis", LiveSpeechGateEvent.BargeInConfirmed in events)
        }
    }

    @Test fun classifierResetAndNewCaptureDoNotReusePriorRecurrentStateOrCloseSharedEnvironment() {
        val speech = fixture("continue")
        val cadence = SileroSpeechClassifier.SILERO_FRAME_SAMPLES
        val firstAudible = speech.indexOfFirst { kotlin.math.abs(it) > 0.02f }
        require(firstAudible >= 0) { "Generated speech fixture must contain non-silent audio" }
        val activeStart = firstAudible / cadence * cadence
        val frame = speech.copyOfRange(activeStart, activeStart + cadence)
        val classifier = SileroSpeechClassifier(context)
        val initialScore: Float
        try {
            initialScore = requireNotNull(classifier.classify(frame).modelScore).value
            repeat(4) { index ->
                val start = activeStart + (index + 1) * frame.size
                classifier.classify(speech.copyOfRange(start, start + frame.size))
            }
            classifier.resetStream()
            assertEquals("Reset must restore the pinned model's initial state/context", initialScore,
                requireNotNull(classifier.classify(frame).modelScore).value, 0.000001f)
        } finally { classifier.close() }
        classifier.close()
        val closedResult = runCatching { classifier.classify(frame) }
        assertTrue("Closed capture must not run inference", closedResult.exceptionOrNull() is IllegalStateException)
        val nextCapture = SileroSpeechClassifier(context)
        try {
            assertEquals("Closing one capture must not close the process-shared ORT environment", initialScore,
                requireNotNull(nextCapture.classify(frame).modelScore).value, 0.000001f)
        } finally { nextCapture.close() }
    }

    @Test fun actualSileroGateRejectsStationaryNoiseChordAndChangingInstrumentalProbe() {
        val random = Random(20261113)
        val noise = FloatArray(16_000 * 3) { (random.nextFloat() * 2f - 1f) * 0.18f }
        assertNoConfirmedSpeech(noise, "stationary broadband noise")
        assertNoConfirmedSpeech(instrumentalChord(3), "stationary music-like chord")
        val music = changingInstrumental(4)
        val summary = scores(music, "changing instrumental");
        val events = gateEvents(SileroSpeechClassifier(context), music)
        println("SILERO_MUSIC_PROBE frames=${summary.frames} positives=${summary.positiveFrames} scoreMin=${summary.minimum} scoreMax=${summary.maximum} cpuMsP95=${summary.p95Ms} cpuMsMax=${summary.maxMs} speechConfirmed=${LiveSpeechGateEvent.SpeechConfirmed in events} bargeInConfirmed=${LiveSpeechGateEvent.BargeInConfirmed in events}")
        // Synthetic melody/percussion is a diagnostic, not a general guarantee for music or vocals.
        assertFalse("This changing instrumental probe should not open a user turn: positives=${summary.positiveFrames} events=$events", LiveSpeechGateEvent.SpeechConfirmed in events)
        assertFalse(LiveSpeechGateEvent.BargeInConfirmed in events)
    }

    @Test fun generatedSpeechRemainsAdmittedOverLowerLevelChangingMusicLikeSignal() {
        val speech = fixture("review-soft")
        val music = changingInstrumental((speech.size + 15_999) / 16_000, intensity = 0.2f)
        val mixture = FloatArray(speech.size) { index -> (speech[index] + music[index]).coerceIn(-1f, 1f) }
        val scores = scores(mixture, "soft speech plus lower-level synthetic instrumental")
        assertTrue("Near-end speech must retain positive classifier evidence: $scores", scores.positiveFrames >= 5)
        val events = gateEvents(SileroSpeechClassifier(context), mixture)
        assertTrue("Generated near-end speech should confirm in this explicitly level-bounded synthetic mixture", LiveSpeechGateEvent.SpeechConfirmed in events)
        assertTrue("Speech should separately pass barge-in hysteresis", LiveSpeechGateEvent.BargeInConfirmed in events)
    }

    private data class ScoreSummary(val frames: Int, val positiveFrames: Int, val minimum: Float, val maximum: Float, val p95Ms: Double, val maxMs: Double)

    private fun scores(samples: FloatArray, label: String): ScoreSummary {
        val classifier = SileroSpeechClassifier(context)
        try {
            var frames = 0; var positives = 0
            var minimum = Float.POSITIVE_INFINITY; var maximum = Float.NEGATIVE_INFINITY
            val latenciesMs = ArrayList<Double>()
            var offset = 0
            while (offset + classifier.frameSamples <= samples.size) {
                val started = System.nanoTime()
                val decision = classifier.classify(samples.copyOfRange(offset, offset + classifier.frameSamples))
                latenciesMs += (System.nanoTime() - started) / 1_000_000.0
                val score = requireNotNull(decision.modelScore).value
                frames++
                if (decision.speech) positives++
                minimum = minOf(minimum, score); maximum = maxOf(maximum, score)
                offset += classifier.frameSamples
            }
            val orderedLatencies = latenciesMs.sorted()
            val summary = ScoreSummary(frames, positives, minimum, maximum,
                orderedLatencies[((orderedLatencies.size * 95 + 99) / 100 - 1).coerceAtLeast(0)],
                orderedLatencies.last())
            println("SILERO_SCORE signal=$label frames=$frames positives=$positives/${frames} scoreMin=$minimum scoreMax=$maximum threshold=${SILERO_SPEECH_THRESHOLD} cpuMsP95=${summary.p95Ms} cpuMsMax=${summary.maxMs}")
            return summary
        } finally { classifier.close() }
    }

    private fun assertNoConfirmedSpeech(samples: FloatArray, label: String) {
        val summary = scores(samples, label)
        val gate = LiveSpeechGate(SileroSpeechClassifier(context))
        try {
            val events = ArrayList<LiveSpeechGateEvent>()
            var offset = 0
            while (offset < samples.size) {
                val end = minOf(samples.size, offset + 512)
                events += gate.accept(samples.copyOfRange(offset, end))
                offset = end
            }
            println("SILERO_NUISANCE_GATE signal=$label frames=${summary.frames} positives=${summary.positiveFrames} events=$events")
            assertFalse("$label must not confirm a speech onset; score-positive frames=${summary.positiveFrames} events=$events", LiveSpeechGateEvent.SpeechConfirmed in events)
            assertFalse("$label must not be flushable as a user turn", gate.flush())
        } finally { gate.close() }
    }


    private fun fixture(id: String): FloatArray = fixtureContext.assets.open("live-speech-fixtures/$id.wav").use {
        LiveAudioCorpus.read16kMono(it.readBytes())
    }

    private fun changingInstrumental(seconds: Int, intensity: Float = 1f): FloatArray {
        val melody = doubleArrayOf(220.0, 261.63, 293.66, 329.63, 392.0, 329.63, 293.66, 261.63)
        val random = Random(731)
        return FloatArray(16_000 * seconds) { index ->
            val time = index / 16_000.0
            val notePosition = time % 0.24
            val frequency = melody[(time / 0.24).toInt() % melody.size]
            val envelope = (notePosition / 0.015).coerceIn(0.0, 1.0) * if (notePosition < 0.19) 1.0 else ((0.24 - notePosition) / 0.05).coerceIn(0.0, 1.0)
            val notes = (sin(2.0 * PI * frequency * time) +
                0.45 * sin(2.0 * PI * frequency * 2.0 * time) +
                0.22 * sin(2.0 * PI * frequency * 3.0 * time)) * envelope * 0.16
            val beatPosition = time % 0.48
            val percussionEnvelope = if (beatPosition < 0.035) kotlin.math.exp(-beatPosition * 95.0) else 0.0
            val percussion = (random.nextFloat() * 2.0 - 1.0) * percussionEnvelope * 0.12
            ((notes + percussion) * intensity).toFloat().coerceIn(-1f, 1f)
        }
    }

    private fun instrumentalChord(seconds: Int, amplitude: Float = 0.18f): FloatArray =
        FloatArray(16_000 * seconds) { index ->
            val t = index / 16_000.0
            (sin(2.0 * PI * 220.0 * t) + sin(2.0 * PI * 329.63 * t) + sin(2.0 * PI * 440.0 * t))
                .toFloat() * amplitude / 3f
        }

    private fun gateEvents(classifier: SileroSpeechClassifier, samples: FloatArray): List<LiveSpeechGateEvent> {
        val gate = LiveSpeechGate(classifier)
        try {
            val events = ArrayList<LiveSpeechGateEvent>(2)
            var offset = 0
            while (offset < samples.size) {
                val end = minOf(samples.size, offset + 512)
                events += gate.accept(samples.copyOfRange(offset, end))
                offset = end
            }
            return events
        } finally { gate.close() }
    }

    private fun feedRecognizer(recognizer: LocalLiveRecognizer, samples: FloatArray) {
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(samples.size, offset + 512)
            recognizer.onAudio(samples.copyOfRange(offset, end))
            offset = end
        }
    }
}
