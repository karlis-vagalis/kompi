package com.kompi.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kompi.app.audio.DICTATION_TRANSCRIPT_TEST_TAG
import com.kompi.app.audio.DICTATION_TRANSCRIPT_TEXT_TEST_TAG
import com.kompi.app.audio.DictationTranscript
import com.kompi.app.audio.OfflineSpeechRecognizer
import com.kompi.app.audio.OfflineSpeechSession
import com.kompi.app.audio.OfflineSpeechSessionPhase
import com.kompi.app.audio.OfflineStreamingRecognizer
import com.kompi.app.audio.InstalledParakeetModel
import com.kompi.app.audio.OfflineTranscript
import com.kompi.app.audio.SpeechRecognitionModel
import com.kompi.app.audio.StreamingSpeechDecoder
import com.kompi.app.audio.dictationSendAllowed
import com.kompi.app.audio.live.TranscriptPartial
import com.kompi.app.ui.KompiButton
import com.kompi.app.ui.KompiText
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DictationTranscriptComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class Decoder : StreamingSpeechDecoder {
        override val chunkSamples = 3_200
        @Volatile var update = TranscriptPartial("Actual words from selected decoder\n".repeat(24), "first tail", null)
        var final = TranscriptPartial("", "", null)
        val calls = AtomicInteger()
        override fun transcribe(samples: FloatArray): TranscriptPartial { calls.incrementAndGet(); return update }
        override fun finish(): TranscriptPartial = final
        override fun reset() = Unit
        override fun close() = Unit
    }

    @Test fun actualStreamingPartialsTailFollowThenRetractWithoutDraftMutationOrBlankSend() {
        val draft = mutableStateOf("Durable draft stays here")
        val preview = mutableStateOf<OfflineTranscript?>(null)
        val phase = mutableStateOf(OfflineSpeechSessionPhase.Capturing)
        val overflow = mutableStateOf(false)
        val copied = mutableStateOf(false)
        val partials = CopyOnWriteArrayList<OfflineTranscript>()
        val errors = CopyOnWriteArrayList<String>()
        val first = CountDownLatch(1)
        val revision = CountDownLatch(1)
        val retraction = CountDownLatch(1)
        compose.setContent {
            KompiTheme(AppTheme.System) {
                Column {
                    KompiText(draft.value, Modifier.testTag("dictation-durable-draft"))
                    preview.value?.let {
                        DictationTranscript(
                            partial = it,
                            copyAvailable = overflow.value,
                            onCopy = { copied.value = true },
                            dismissEnabled = phase.value == OfflineSpeechSessionPhase.Idle,
                            onDismiss = { preview.value = null; overflow.value = false },
                        )
                    }
                    KompiButton(
                        onClick = {},
                        enabled = dictationSendAllowed(phase.value, preview.value != null),
                        modifier = Modifier.testTag("dictation-send"),
                    ) { KompiText("Send") }
                }
            }
        }
        val decoder = Decoder()
        val recognizer = OfflineStreamingRecognizer(
            decoder,
            onPartial = { result ->
                partials += result
                preview.value = result.takeIf { it.text.isNotBlank() }
                if (result.text.contains("first tail")) first.countDown()
                if (result.text.contains("second tail")) revision.countDown()
                if (result.text.isBlank()) retraction.countDown()
            },
            onTranscript = { result -> draft.value = result.text },
            onError = { errors += it },
        )
        try {
            compose.onNodeWithTag("dictation-send").assertIsNotEnabled()
            repeat(12) { recognizer.onAudio(FloatArray(512) { if (it % 2 == 0) 0.1f else -0.1f }) }
            assertTrue("Actual first partial never arrived", first.await(5, TimeUnit.SECONDS))
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag(DICTATION_TRANSCRIPT_TEST_TAG).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(DICTATION_TRANSCRIPT_TEXT_TEST_TAG).assertTextContains("first tail", substring = true)
            val firstRange = compose.onNodeWithTag(DICTATION_TRANSCRIPT_TEST_TAG).fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
            compose.waitUntil(5_000) { firstRange.value() >= firstRange.maxValue() - 1f }
            compose.onNodeWithTag("dictation-durable-draft").assertTextEquals("Durable draft stays here")
            compose.onNodeWithTag("dictation-send").assertIsNotEnabled()

            // The user deliberately scrolls away from the newest words; later partials respect it.
            compose.onNodeWithTag(DICTATION_TRANSCRIPT_TEST_TAG).performTouchInput { swipeDown() }
            compose.waitForIdle()
            decoder.update = TranscriptPartial("Actual words from selected decoder\n".repeat(24), "second tail", null)
            repeat(12) { recognizer.onAudio(FloatArray(512) { if (it % 2 == 0) 0.1f else -0.1f }) }
            assertTrue("Revised actual decoder tail did not arrive", revision.await(5, TimeUnit.SECONDS))
            compose.waitForIdle()
            compose.onNodeWithTag(DICTATION_TRANSCRIPT_TEXT_TEST_TAG).assertTextContains("second tail", substring = true)
            val revisedRange = compose.onNodeWithTag(DICTATION_TRANSCRIPT_TEST_TAG).fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue("Manual scroll should not be pulled back to the newest tail", revisedRange.value() < revisedRange.maxValue() - 1f)

            // A genuine empty streaming snapshot retracts the provisional words; final silence
            // emits no transcript and therefore cannot mutate the draft.
            decoder.update = TranscriptPartial("", "", null)
            repeat(12) { recognizer.onAudio(FloatArray(512) { if (it % 2 == 0) 0.1f else -0.1f }) }
            assertTrue("Empty selected-decoder snapshot did not retract the visible partial", retraction.await(5, TimeUnit.SECONDS))
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag(DICTATION_TRANSCRIPT_TEST_TAG).fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag("dictation-durable-draft").assertTextEquals("Durable draft stays here")
            compose.runOnIdle { phase.value = OfflineSpeechSessionPhase.Finalizing }
            compose.onNodeWithTag("dictation-send").assertIsNotEnabled()
            recognizer.finishUtteranceAndJoin()
            recognizer.close()
            recognizer.closeAndJoin()
            compose.runOnIdle { phase.value = OfflineSpeechSessionPhase.Idle }
            compose.onNodeWithTag("dictation-durable-draft").assertTextEquals("Durable draft stays here")
            compose.onNodeWithTag("dictation-send").assertIsEnabled()
            assertTrue("Only nonblank actual partials and one blank retraction should be visible", partials.any { it.text.isBlank() })
            assertTrue("Controlled decoder unexpectedly errored: $errors", errors.isEmpty())
        } finally { recognizer.closeAndJoin() }
    }

    @Test fun startupCancellationFencesLateMicAndSilentStopReturnsIdle() {
        val phase = mutableStateOf(OfflineSpeechSessionPhase.Idle)
        val recordingChanges = CopyOnWriteArrayList<Boolean>()
        val partials = CopyOnWriteArrayList<OfflineTranscript>()
        val finals = CopyOnWriteArrayList<OfflineTranscript>()
        lateinit var emitPartial: (OfflineTranscript) -> Unit
        val factoryEntered = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val fake = object : OfflineSpeechRecognizer {
            var closeCount = 0
            override fun onAudio(samples: FloatArray) = Unit
            override fun onCaptureError(message: String) = Unit
            override fun finishUtterance() = Unit
            override fun close() { closeCount++ }
            override fun closeAndJoin() = close()
        }
        lateinit var session: OfflineSpeechSession
        compose.runOnUiThread { session = OfflineSpeechSession(
            context = compose.activity,
            owner = compose.activity,
            model = SpeechRecognitionModel.Parakeet(InstalledParakeetModel(File("unused-model"))),
            language = null,
            onPartial = { partials += it },
            onTranscript = { finals += it },
            onMessage = {},
            onRecordingChanged = { recordingChanges += it },
            onPhaseChanged = { phase.value = it },
        ) }
        session.recognizerFactory = { _, _, _, _, partial, _, _ ->
            emitPartial = partial
            factoryEntered.countDown()
            check(releaseFactory.await(10, TimeUnit.SECONDS)) { "Test did not release model initialization" }
            fake
        }
        compose.setContent {
            KompiTheme(AppTheme.System) {
                KompiButton(
                    onClick = {},
                    enabled = dictationSendAllowed(phase.value, false),
                    modifier = Modifier.testTag("dictation-send"),
                ) { KompiText("Send") }
            }
        }
        val stopResult = CompletableFuture<Unit>()
        try {
            compose.activity.runOnUiThread { session.start() }
            assertTrue("Controlled model startup did not begin", factoryEntered.await(5, TimeUnit.SECONDS))
            compose.waitUntil(5_000) { phase.value == OfflineSpeechSessionPhase.Preparing }
            compose.onNodeWithTag("dictation-send").assertIsNotEnabled()
            Thread {
                try { runBlocking { session.stopAndJoin(flush = false) }; stopResult.complete(Unit) }
                catch (error: Throwable) { stopResult.completeExceptionally(error) }
            }.apply { isDaemon = true; start() }
            compose.waitUntil(5_000) { phase.value == OfflineSpeechSessionPhase.Finalizing }
            compose.onNodeWithTag("dictation-send").assertIsNotEnabled()
            releaseFactory.countDown()
            stopResult.get(30, TimeUnit.SECONDS)
            compose.waitUntil(5_000) { phase.value == OfflineSpeechSessionPhase.Idle }
            compose.onNodeWithTag("dictation-send").assertIsEnabled()
            emitPartial(OfflineTranscript("Stale words", null))
            compose.waitForIdle()
            assertTrue("A late callback from cancelled preparation must be fenced", partials.isEmpty())
            assertTrue("A cancelled preparation must never start microphone capture", recordingChanges.none { it })
            assertEquals(1, fake.closeCount)
            assertTrue("Silent stop must not invent a final transcript", finals.isEmpty())
        } finally {
            releaseFactory.countDown()
            compose.runOnUiThread { session.close() }
        }
    }

    @Test fun overflowPreviewOffersCopyAndDismissRecovery() {
        val preview = mutableStateOf<OfflineTranscript?>(OfflineTranscript("Final transcript retained for review", null))
        val phase = mutableStateOf(OfflineSpeechSessionPhase.Idle)
        val copied = mutableStateOf(false)
        compose.setContent {
            KompiTheme(AppTheme.System) {
                Column {
                    preview.value?.let {
                        DictationTranscript(
                            partial = it,
                            copyAvailable = true,
                            onCopy = { copied.value = true },
                            dismissEnabled = true,
                            onDismiss = { preview.value = null },
                        )
                    }
                    KompiButton(onClick = {}, enabled = dictationSendAllowed(phase.value, preview.value != null),
                        modifier = Modifier.testTag("dictation-overflow-send")) {
                        KompiText("Send")
                    }
                }
            }
        }
        compose.onNodeWithTag("dictation-overflow-send").assertIsNotEnabled()
        compose.onNodeWithTag(DICTATION_TRANSCRIPT_TEXT_TEST_TAG).assertTextContains("Final transcript retained", substring = true)
        compose.onNodeWithText("Copy transcript").performClick()
        compose.runOnIdle { assertTrue(copied.value) }
        compose.onNodeWithText("Dismiss transcript").performClick()
        compose.onNodeWithTag(DICTATION_TRANSCRIPT_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithTag("dictation-overflow-send").assertIsEnabled()
    }
}
