package com.kompi.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.kompi.app.audio.KokoroPlaybackPhase
import com.kompi.app.audio.KokoroSpeechStatus
import com.kompi.app.live.LiveConversationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Opt-in real audio acceptance. No mocked ASR results or fake TTS/playback phase callbacks. */
class LiveRecordedAudioBehaviorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var h: NativeLiveAudioHarness
    @Before fun mount() { h = NativeLiveAudioHarness(compose); h.mount() }
    @After fun cleanup() { h.close() }

    @Test fun realSpokenReviewAndNegationRoundTripThroughLiveAndNativePlayback() {
        h.start()
        h.play("review", 1)
        val first = h.submissions.single()
        assertEquals(first.text, runBlocking { h.repository.pendingPrompts("native-test-host") }.single().text)
        h.reply("I will review the changes.")
        h.drained(first.requestId)
        compose.onNodeWithText("I will review the changes.").assertIsDisplayed()
        assertTrue(h.phases.filterIsInstance<KokoroPlaybackPhase.Playing>().any { it.requestId == first.requestId && it.pcmRms > 0f })
        h.play("negation", 2)
        val second = h.submissions.last()
        assertNotEquals(first.requestId, second.requestId)
        assertTrue(LiveAudioCorpus.words(second.text).contains("not"))
        h.reply("I will not run the command."); h.drained(second.requestId)
        assertEquals(1, h.inputs.size)
        assertTrue(h.audioFailures.isEmpty())
    }

    @Test fun actualRecognizedBargeInFlushesRealAudioTrackAndPreservesNewPoint() {
        h.start(); h.play("review", 1)
        val first = h.submissions.single().requestId
        h.reply("I am reviewing the changes carefully. There are several details to explain before the review is finished, so this answer keeps speaking while you add another point.")
        h.await("Native output must actually start before barge-in") { h.phases.filterIsInstance<KokoroPlaybackPhase.Playing>().any { it.requestId == first && it.pcmRms > 0f } }
        h.play("interrupt", 2)
        h.await("Original output must be cancelled, not drained or resumed") { h.tts.requestStatuses.value[first]?.state == KokoroSpeechStatus.Cancelled }
        assertEquals(listOf(first), h.cancellations)
        assertEquals(1, h.inputs.size)
        h.reply("I will explain the error instead."); h.drained(h.submissions.last().requestId)
        assertEquals(KokoroSpeechStatus.Cancelled, h.tts.requestStatuses.value[first]?.state)
    }

    @Test fun realLoadedRecognizerDoesNotSubmitSilenceOrSubthresholdNoise() {
        h.start()
        runBlocking(Dispatchers.IO) {
            h.recordedInput.feed(FloatArray(32_000))
            h.recordedInput.feed(FloatArray(16_000) { index -> if (index % 2 == 0) 0.001f else -0.001f })
            h.recordedInput.feed(FloatArray(16_000))
        }
        assertTrue(h.submissions.isEmpty())
        assertTrue(h.cancellations.isEmpty())
        assertEquals(LiveConversationState.Listening, h.snapshot.state)
    }

    @Test fun endingDuringRealRecordedSpeechDiscardsTailAndFreshCaptureRecognizesNextPoint() {
        h.start()
        val input = h.recordedInput
        val clip = LiveAudioCorpus.read16kMono(LiveAudioCorpus.fixture(h.context, "review"))
        runBlocking(Dispatchers.IO) { input.feed(clip.copyOfRange(0, minOf(clip.size, 19_200))) }
        h.main { h.controller.endLive() }
        runBlocking { input.closeAndJoin() }
        h.await("Explicit end must settle") { h.snapshot.state == LiveConversationState.Idle }
        assertTrue(h.submissions.isEmpty())
        h.start(); h.play("continue", 1)
        assertEquals(2, h.inputs.size)
        assertTrue(LiveAudioCorpus.words(h.submissions.single().text).contains("continue"))
    }
}
