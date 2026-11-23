package com.kompi.app

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kompi.app.audio.EchoCancellationPolicy
import com.kompi.app.audio.OfflineStreamingRecognizer
import com.kompi.app.audio.ParakeetSpeechDecoder
import com.kompi.app.audio.SpeechRecognitionModel
import com.kompi.app.audio.SpeechUtteranceId
import com.kompi.app.audio.whistle.WhistleVocabulary
import com.kompi.app.audio.live.CaptureMeter
import com.kompi.app.audio.live.LocalLiveListener
import com.kompi.app.audio.live.LocalLiveRecognizer
import com.kompi.app.audio.live.NonBlankTranscript
import com.kompi.app.audio.live.SpeechFrameClassifier
import com.kompi.app.audio.live.SpeechFrameDecision
import com.kompi.app.audio.live.TranscriptPartial
import com.kompi.app.live.LiveCaption
import com.kompi.app.live.LiveConversationSnapshot
import com.kompi.app.live.LiveConversationState
import com.kompi.app.live.LiveTalkScreen
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Cached, hash-verified Parakeet → paced real PCM stream → actual visible partial; no TTS or network. */
@RunWith(AndroidJUnit4::class)
class ParakeetStreamingCaptionAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val snapshot = mutableStateOf(LiveConversationSnapshot(state = LiveConversationState.Listening))
    private var recognizer: LocalLiveRecognizer? = null

    @Before fun mount() {
        compose.setContent {
            KompiTheme(AppTheme.System) { LiveTalkScreen(snapshot.value, {}, {}, {}, {}) }
        }
    }

    @After fun cleanup() { recognizer?.discardAndJoin() }

    @Test fun realParakeetDictationEmitsPartialBeforePauseThenOneFinal() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val model = LiveAudioCorpus.model(context)
        val source = instrumentation.context.assets.open("live-speech-fixtures/review.wav").use { input ->
            LiveAudioCorpus.read16kMono(input.readBytes())
        }
        val speech = source.copyOfRange(0, source.indexOfLast { kotlin.math.abs(it) >= 0.01f } + 1)
        val interim = CopyOnWriteArrayList<String>()
        val finals = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<String>()
        val partialSignal = CountDownLatch(1)
        val finalSignal = CountDownLatch(1)
        val recognizer = OfflineStreamingRecognizer(
            context, SpeechRecognitionModel.Parakeet(model), null, WhistleVocabulary.Empty,
            onPartial = { interim += it.text; partialSignal.countDown() },
            onTranscript = { finals += it.text; finalSignal.countDown() },
            onError = { errors += it },
        )
        try {
            var receivedPartial = false
            for (pass in 0 until 4) {
                for (offset in speech.indices step 320) {
                    val end = minOf(offset + 320, speech.size)
                    recognizer.onAudio(speech.copyOfRange(offset, end))
                    SystemClock.sleep(20) // Pace one captured 20 ms frame; do not flood the bounded queue.
                    if (partialSignal.count == 0L) { receivedPartial = true; break }
                }
                if (receivedPartial) break
            }
            assertTrue("Real Parakeet dictation must return interim text before a pause", partialSignal.await(30, TimeUnit.SECONDS))
            assertTrue(interim.any(String::isNotBlank))
            assertTrue("No VAD endpoint has been fed", finals.isEmpty())
            repeat(35) { recognizer.onAudio(FloatArray(320)); SystemClock.sleep(20) }
            assertTrue("One actual endpoint final should arrive", finalSignal.await(30, TimeUnit.SECONDS))
            assertEquals(1, finals.size)
            assertTrue("Decoder reported a paced-input/backlog failure: $errors", errors.isEmpty())
        } finally { recognizer.closeAndJoin() }
    }

    @Test fun realParakeetWordsBecomeVisibleBeforeContinuousSpeechPausesOrFinalizes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val decoder = ParakeetSpeechDecoder(context, LiveAudioCorpus.model(context))
        val classifier = SpeechFrameClassifier { frame ->
            SpeechFrameDecision.categorical(frame.any { kotlin.math.abs(it) >= 0.01f })
        }
        val partials = CopyOnWriteArrayList<String>()
        val finals = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<String>()
        val partialSignal = CountDownLatch(2)
        val recognizer = LocalLiveRecognizer(decoder, null, object : LocalLiveListener {
            override fun onSpeechStarted(utteranceId: SpeechUtteranceId) {
                compose.activity.runOnUiThread { snapshot.value = snapshot.value.copy(state = LiveConversationState.Recognizing) }
            }
            override fun onBargeInConfirmed(utteranceId: SpeechUtteranceId) = Unit
            override fun onMeter(meter: CaptureMeter) = Unit
            override fun onPartial(utteranceId: SpeechUtteranceId, partial: TranscriptPartial) {
                val actual = listOf(partial.committedText, partial.pendingText).filter(String::isNotBlank).joinToString(" ")
                if (actual.isNotBlank()) {
                    if (partials.addIfAbsent(actual)) partialSignal.countDown()
                    compose.activity.runOnUiThread {
                        snapshot.value = snapshot.value.copy(state = LiveConversationState.Recognizing,
                            caption = LiveCaption.fromWhistle(partial.committedText, partial.pendingText, partial.language))
                    }
                }
            }
            override fun onFinal(utteranceId: SpeechUtteranceId, transcript: NonBlankTranscript) { finals += transcript.text }
            override fun onError(message: String) { errors += message }
        }, EchoCancellationPolicy.Disabled, null, {}, classifier)
        this.recognizer = recognizer

        // Repeated synthetic speech source is continuous PCM. It intentionally contains no
        // inserted silence and the test does not call finish() before checking the visible result.
        val source = instrumentation.context.assets.open("live-speech-fixtures/review.wav").use { input ->
            LiveAudioCorpus.read16kMono(input.readBytes())
        }
        val speech = source.copyOfRange(0, source.indexOfLast { kotlin.math.abs(it) >= 0.01f } + 1)
        // The current native task emits in multi-second batches. Supply enough continuous
        // speech for two genuine updates; this is deliberately not a per-word latency assertion.
        val pcm = FloatArray(128_000) { speech[it % speech.size] }
        for (offset in pcm.indices step 320) {
            recognizer.onAudio(pcm.copyOfRange(offset, offset + 320))
            SystemClock.sleep(20) // Keep the real-model test at captured-frame pace.
            if (partialSignal.count == 0L) break
        }
        assertTrue("Selected Parakeet produced fewer than two distinct actual interim snapshots during continuous PCM", partialSignal.await(30, TimeUnit.SECONDS))
        compose.waitUntil(5_000) { snapshot.value.caption.visibleText.isNotBlank() }
        compose.waitForIdle() // Drain the on-main presentation posts before inspecting the rendered caption.
        val visible = snapshot.value.caption.visibleText
        assertTrue("Live must expose actual recognized text, not a placeholder", visible.isNotBlank())
        compose.onNodeWithText(visible).assertIsDisplayed()
        assertTrue("No pause or decoder finalization occurred", finals.isEmpty())
        assertTrue("Actual selected-model output must be preserved", partials.any { it == visible } && partials.distinct().size >= 2)
        assertTrue("Decoder reported a paced-input/backlog failure: $errors", errors.isEmpty())
    }
}
