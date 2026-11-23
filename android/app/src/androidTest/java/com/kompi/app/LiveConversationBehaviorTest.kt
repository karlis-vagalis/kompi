package com.kompi.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import com.kompi.app.audio.AudioWaveform
import com.kompi.app.live.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Broad ASR-boundary → real controller → encrypted Room → real Live UI journeys. Not acoustic E2E. */
class LiveConversationBehaviorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var h: LiveBehaviorHarness
    @Before fun mount() { h = LiveBehaviorHarness(compose); h.mount() }
    @After fun cleanup() { h.close() }

    @Test fun l01_enteringLiveNeverStartsCaptureOrSubmits() {
        compose.onNodeWithText("Start Live").assertIsDisplayed()
        h.main { assertTrue(h.inputs.isEmpty()); assertTrue(h.attempts.isEmpty()) }
    }

    @Test fun l02_startIsIdempotentAndListeningRequiresMeasuredInput() {
        h.main { h.controller.startLive(); h.controller.startLive() }
        h.await("capture created once") { h.inputs.size == 1 }
        h.main { assertEquals(LiveConversationState.Preparing, h.view.state); h.input.ready() }
        h.await("measured input is ready") { h.view.state == LiveConversationState.Listening }
        h.main { h.controller.startLive() }
        h.main { assertEquals(1, h.inputs.size); assertEquals(1, h.input.starts) }
    }

    @Test fun l04_missingEngineFailsWithoutCreatingCaptureOrSending() {
        h.factoryFailure = IllegalStateException("Selected local model is missing")
        compose.onNodeWithText("Start Live").performClick()
        h.await("missing model visible") { h.view.state is LiveConversationState.Failure }
        compose.onNodeWithText("Selected local model is missing").assertIsDisplayed()
        h.main { assertTrue(h.inputs.isEmpty()); assertTrue(h.attempts.isEmpty()) }
    }

    @Test fun l05_endBeforeFirstFrameFencesDelayedReadinessAndFinal() {
        h.main { h.controller.startLive() }
        h.await("input initialized") { h.inputs.isNotEmpty() }
        val old = h.input
        h.main { h.controller.endLive() }
        h.await("ended") { h.view.state == LiveConversationState.Idle }
        h.main { old.ready(); old.onset(1); old.final(1, "Must never be submitted") }
        h.main { assertEquals(LiveConversationState.Idle, h.view.state); assertTrue(h.attempts.isEmpty()); assertEquals(1, old.stops) }
    }

    @Test fun l13_l24_silenceMetersDoNotInventSpeechOrRequests() {
        h.start()
        h.main { repeat(100) { h.input.ready() } }
        h.main { assertTrue(h.attempts.isEmpty()); assertEquals("", h.view.caption.visibleText); assertTrue(h.cancelled.isEmpty()) }
    }

    @Test fun l16_cumulativeCommittedWordsAndReplaceableInterimAreShownOnce() {
        h.start()
        h.main { h.input.onset(1); h.input.partial(1, "Keep these", "old tail") }
        compose.onNodeWithText("Keep these old tail").assertIsDisplayed()
        h.main { h.input.partial(1, "Keep these", "new tail"); h.input.partial(1, "Keep these words", "") }
        compose.onNodeWithText("Keep these words").assertIsDisplayed()
        h.main { assertEquals("Keep these words", h.view.caption.visibleText); assertTrue(h.attempts.isEmpty()) }
    }

    @Test fun l17_finalizedIntentReachesEncryptedOutboxOnceDespiteDuplicateFinal() {
        h.start(); h.utterance(1, "Review this change 🎧")
        h.main { h.input.final(1, "Review this change 🎧"); h.input.final(1, "Review this change 🎧") }
        val rows = runBlocking { h.repository.pendingPrompts("host-a") }
        h.main {
            assertEquals(1, h.attempts.size)
            assertEquals(h.attempts.single().requestId, rows.single().requestId)
            assertEquals("Review this change 🎧", rows.single().text)
            assertEquals("controlled-model", rows.single().configuration!!.modelId)
        }
    }

    @Test fun l18_oldInputCallbacksCannotAffectExplicitlyRestartedCapture() {
        h.start(); val old = h.input
        h.main { h.controller.endLive() }
        h.await("idle") { h.view.state == LiveConversationState.Idle }
        h.start()
        h.main { old.onset(1); old.partial(1, "Old words", ""); old.final(1, "Old words"); old.listener.onLocalStop(LiveLocalStopReason.EngineFailure("Stale decoder failure")) }
        h.main { assertEquals(LiveConversationState.Listening, h.view.state); assertTrue(h.attempts.isEmpty()); assertEquals("", h.view.caption.visibleText) }
    }

    @Test fun l19_previousFinalIsRetainedWithNewerPointWithoutObsoleteSubmission() {
        h.start()
        h.main { h.input.onset(1); h.input.partial(1, "First point", ""); h.input.onset(2); h.input.final(1, "First point") }
        h.main { assertTrue(h.attempts.isEmpty()) }
        h.main { h.input.partial(2, "Second point", ""); h.input.final(2, "Second point") }
        h.await("combined point queued") { h.attempts.size == 1 }
        h.main { assertEquals("First point\nSecond point", h.attempts.single().text) }
    }

    @Test fun l20_identicalWordsInDeliberateSeparateUtterancesAreDistinctIntents() {
        h.start(); h.utterance(1, "Continue")
        h.main { h.controller.onAssistantPlaybackDrained(h.attempts.first().requestId) }
        h.utterance(2, "Continue")
        h.main { assertNotEquals(h.attempts[0].requestId, h.attempts[1].requestId); assertEquals(listOf("Continue", "Continue"), h.attempts.map { it.text }) }
    }

    @Test fun l21_overlongSpeechFailsExplicitlyWithoutTruncatedSubmission() {
        h.start()
        h.main { h.input.onset(1); h.input.final(1, "x".repeat(16_001)) }
        h.await("limit must be explicit") { h.view.state is LiveConversationState.Failure }
        h.main { assertTrue(h.attempts.isEmpty()); assertEquals(1, h.input.stops) }
    }

    @Test fun l23_unicodeNumbersAndLongAcceptedPointKeepExactSubmission() {
        val text = "Latviešu teksts: 12.50 EUR, src/file_name.kt\n" + "word ".repeat(1500).trim()
        h.start(); h.utterance(1, text)
        h.main { assertEquals(text, h.attempts.single().text) }
        val saved = runBlocking { h.repository.pendingPrompts("host-a") }.single()
        assertEquals(text, saved.text)
    }

    @Test fun l25_localStopPrecedesSlowRemoteCancelAndKeepsMicrophoneOpen() {
        h.start(); h.utterance(1, "First point")
        val first = h.attempts.single().requestId
        h.playing(first)
        h.cancelGate = h.gate()
        h.main { h.input.onset(2) }
        h.await("targeted remote cancellation dispatched") { first in h.cancelled }
        h.main { assertTrue(first in h.stoppedAudio); assertEquals(0, h.input.stops); assertEquals(1, h.inputs.size); assertEquals(LiveConversationState.Listening, h.view.state) }
    }

    @Test fun l74_candidateKeepsReplyUntilActualPartialThenShowsWordsWithoutPrematureCancellation() {
        h.start(); h.utterance(1, "First point")
        val first = h.attempts.single().requestId
        h.playing(first, "The previous reply remains visible")
        val stoppedBeforeCandidate = h.stoppedAudio.toList() // Initial privacy/setup flush is not barge-in.
        h.main { h.input.speechCandidate(2) }
        compose.onNodeWithText("The previous reply remains visible").assertIsDisplayed()
        h.main { h.input.partial(2, "Second", "point") }
        compose.onNodeWithText("Second point").assertIsDisplayed()
        compose.onNodeWithText("The previous reply remains visible").assertDoesNotExist()
        h.await("real partial replaces reply without authorizing cancellation") {
            h.view.state == LiveConversationState.Speaking(first) && h.view.inputCaptionActive &&
                first !in h.cancelled && first !in h.stoppedAudio
        }
        h.main { h.input.bargeIn(2) }
        h.await("confirmed barge-in cancels only the current reply") {
            first in h.cancelled && h.view.state == LiveConversationState.Recognizing && h.view.caption.visibleText == "Second point"
        }
        h.main { assertEquals(listOf(first), h.cancelled); assertEquals(stoppedBeforeCandidate + first, h.stoppedAudio); assertEquals(1, h.inputs.size) }
    }

    @Test fun l75_blankPartialRetractsCandidateCaptionWithoutTurnOrCancellation() {
        h.start(); h.utterance(1, "First point")
        val first = h.attempts.single().requestId
        h.playing(first, "The previous reply remains visible")
        h.main { h.input.speechCandidate(2); h.input.partial(2, "Actual", "interim words") }
        h.main { h.input.partial(2, "", "") }
        h.await("empty selected-decoder snapshot retracts candidate words") {
            !h.view.inputCaptionActive && h.view.caption.visibleText.isBlank() && h.view.state == LiveConversationState.Speaking(first)
        }
        compose.onNodeWithText("The previous reply remains visible").assertIsDisplayed()
        h.main { assertEquals(1, h.attempts.size); assertTrue(h.cancelled.isEmpty()) }
    }

    @Test fun l76_replyDrainKeepsActiveInputCaptionAndRecognizingState() {
        h.start(); h.utterance(1, "First point")
        val first = h.attempts.single().requestId
        h.playing(first, "The previous reply remains visible")
        h.main { h.input.speechCandidate(2); h.input.partial(2, "Second", "point") }
        compose.onNodeWithText("Second point").assertIsDisplayed()
        h.main { h.controller.onAssistantPlaybackDrained(first) }
        h.await("drain preserves active selected-decoder caption") {
            h.view.state == LiveConversationState.Recognizing && h.view.inputCaptionActive &&
                h.view.caption.visibleText == "Second point" && h.view.playingPhrase.isEmpty()
        }
        compose.onNodeWithText("Second point").assertIsDisplayed()
        h.main { assertEquals(1, h.attempts.size); assertTrue(h.cancelled.isEmpty()) }
    }

    @Test fun l26_l27_manualAndVoiceInterruptionCancelOriginalOnlyOnce() {
        h.start(); h.utterance(1, "First point")
        val first = h.attempts.single().requestId
        h.playing(first)
        compose.onNodeWithText("Interrupt").performClick()
        h.main { h.input.onset(2); h.input.partial(2, "New", "point"); h.input.partial(2, "New point", "") }
        h.await("original cancellation sent") { h.cancelled.isNotEmpty() }
        h.main { assertEquals(listOf(first), h.cancelled); assertEquals(1, h.inputs.size); assertEquals(0, h.input.stops) }
    }

    @Test fun l28_oldTextPcmTitleAndDrainCannotReplaceNewTurn() {
        h.start(); h.utterance(1, "Old point")
        val old = h.attempts.single().requestId
        h.playing(old)
        h.utterance(2, "New point")
        val current = h.attempts.last().requestId
        h.main {
            h.controller.updateAssistantText(old, "Stale answer")
            h.controller.updateThinkingTitle(old, "Stale heading")
            h.controller.onAssistantPlaying(old, "Stale answer", 1f, AudioWaveform.Silence)
            h.controller.onAssistantPlaybackDrained(old)
        }
        h.main { assertEquals(current, h.view.replyRequestId); assertEquals("New point", h.view.caption.visibleText); assertEquals("", h.view.assistantText); assertTrue(h.view.thinkingTitles.isEmpty()) }
    }

    @Test fun l30_interruptNeverResumesOldAnswerAfterDrainOrSilence() {
        h.start(); h.utterance(1, "Old point"); val old = h.attempts.single().requestId
        h.playing(old)
        h.main { h.controller.interruptAndListen(); h.controller.onAssistantPlaybackDrained(old); h.input.ready() }
        h.main { assertEquals(LiveConversationState.Listening, h.view.state); assertEquals("", h.view.playingPhrase); assertEquals(0f, h.view.playbackLevel.value) }
    }

    @Test fun l31_failedRemoteCancellationFailsClosedAndStopsCapture() {
        h.start(); h.utterance(1, "Old point")
        h.cancellationFailure = IllegalStateException("Connection lost during cancellation")
        h.main { h.controller.interruptAndListen() }
        h.await("cancellation uncertainty surfaced") { h.view.state is LiveConversationState.Failure }
        h.main { assertEquals(1, h.input.stops); assertEquals("", h.view.playingPhrase) }
    }

    @Test fun l34_lateSendCompletionCannotRegressActuallyPlayingReplyToWaiting() {
        h.sendGate = h.gate()
        h.start(); h.utterance(1, "A point")
        val request = h.attempts.single().requestId
        h.playing(request)
        h.main { h.sendGate!!.complete(Unit) }
        h.main { assertEquals(LiveConversationState.Speaking(request), h.view.state) }
    }

    @Test fun l51_finalCaptionRemainsUntilPublicTextNotPreparation() {
        h.start(); h.utterance(1, "User's complete point")
        val request = h.attempts.single().requestId
        h.main { h.controller.onAssistantPreparing(request, "Not audible yet") }
        compose.onNodeWithText("User's complete point").assertIsDisplayed()
        h.main { h.controller.updateAssistantText(request, "Public answer") }
        compose.onNodeWithText("Public answer").assertIsDisplayed()
    }

    @Test fun l52_answerSurvivesDrainAndNewOnsetUntilNewWordsArrive() {
        h.start(); h.utterance(1, "Old point")
        val request = h.attempts.single().requestId
        h.playing(request, "The full public answer")
        h.main { h.controller.onAssistantPlaybackDrained(request) }
        compose.onNodeWithText("The full public answer").assertIsDisplayed()
        h.main { h.input.onset(2) }
        compose.onNodeWithText("The full public answer").assertIsDisplayed()
        h.main { h.input.partial(2, "My new", "point") }
        compose.onNodeWithText("My new point").assertIsDisplayed()
        compose.onNodeWithText("The full public answer").assertDoesNotExist()
    }

    @Test fun l53_assistantTextIsNotCutToRecognizersCaptionBound() {
        val full = "Complete visible answer. ".repeat(260)
        h.start(); h.utterance(1, "Explain")
        h.main { h.controller.updateAssistantText(h.attempts.single().requestId, full) }
        h.main { assertEquals(full, h.view.assistantText); assertTrue(h.view.assistantText.length > 4_000) }
    }

    @Test fun l60_playbackDrainClearsHighlightButKeepsAnswer() {
        h.start(); h.utterance(1, "A point"); val request = h.attempts.single().requestId
        h.playing(request, "Retain this answer")
        h.main { h.controller.onAssistantPlaybackDrained(request) }
        h.main { assertNull(h.view.readAlong); assertEquals("Retain this answer", h.view.assistantText); assertEquals(0f, h.view.playbackLevel.value) }
    }

    @Test fun l63_connectionLossActuallyStopsMicrophoneAndOutput() {
        h.start()
        h.main { h.controller.onConnectionLost() }
        h.main { assertEquals("A paused flag is not hardware teardown", 1, h.input.stops); assertEquals(LiveConversationState.Paused, h.view.state) }
    }

    @Test fun l65_genuineDeliveryRetryReusesExactRequestIdAndText() {
        h.sendFailure = IllegalStateException("Could not durably queue")
        h.start(); h.utterance(1, "Recover this point")
        h.await("submission failure visible") { h.view.state is LiveConversationState.Failure }
        val first = h.attempts.single()
        h.sendFailure = null
        h.main { h.controller.retryPendingTurn() }
        h.await("same intent retried") { h.attempts.size == 2 }
        h.main { assertEquals(first, h.attempts.last()) }
        assertEquals(first.requestId, runBlocking { h.repository.pendingPrompts("host-a") }.single().requestId)
    }

    @Test fun l66_endInvalidatesFailedTurnsRetryAction() {
        h.sendFailure = IllegalStateException("Queue unavailable")
        h.start(); h.utterance(1, "Do not resurrect")
        h.await("submission failed") { h.view.state is LiveConversationState.Failure }
        h.main { h.controller.endLive() }
        h.await("ended") { h.view.state == LiveConversationState.Idle }
        h.main { h.controller.retryPendingTurn() }
        h.main { assertEquals(1, h.attempts.size); assertEquals(1, h.inputs.size); assertEquals(LiveConversationState.Idle, h.view.state) }
    }

    @Test fun l66_contextReplacementInvalidatesFailedTurnsRetryAction() {
        h.sendFailure = IllegalStateException("Queue unavailable")
        h.start(); h.utterance(1, "Host A only")
        h.await("submission failed") { h.view.state is LiveConversationState.Failure }
        h.main { h.controller.onContextChanged("host-b:conversation-b") }
        h.await("old context interrupted") { h.view.state == LiveConversationState.Interrupted }
        h.main { h.controller.retryPendingTurn() }
        h.main { assertEquals(1, h.attempts.size); assertEquals(1, h.inputs.size); assertEquals(LiveConversationState.Interrupted, h.view.state) }
    }

    @Test fun l67_submissionFailureCannotBecomeSpeakingFromLateOutput() {
        h.sendFailure = IllegalStateException("Queue unavailable")
        h.start(); h.utterance(1, "Failed point")
        h.await("submission failed") { h.view.state is LiveConversationState.Failure }
        val request = h.attempts.single().requestId
        h.main { h.controller.updateAssistantText(request, "Zombie answer"); h.controller.onAssistantPlaying(request, "Zombie answer", 1f, AudioWaveform.Silence) }
        h.main { assertTrue("Failed ownership must be fenced", h.view.state is LiveConversationState.Failure); assertEquals("", h.view.assistantText) }
    }

    @Test fun l68_passiveBackgroundStopsLocalAudioWithoutCancellingAcceptedWork() {
        h.start(); h.utterance(1, "Accepted work")
        h.main { h.owner.registry.currentState = Lifecycle.State.CREATED }
        h.await("local capture stopped on background") { h.input.stops == 1 }
        h.main { assertTrue("Passive background is not an explicit cancel command", h.cancelled.isEmpty()) }
    }

    @Test fun l69_foregroundReturnDoesNotRestartEndedMicrophone() {
        h.start()
        h.main { h.owner.registry.currentState = Lifecycle.State.CREATED }
        h.await("local stop") { h.input.stops == 1 }
        h.main { h.owner.registry.currentState = Lifecycle.State.RESUMED }
        h.main { assertEquals(1, h.inputs.size); assertEquals(LiveConversationState.Idle, h.view.state) }
    }

    @Test fun l70_hostChangeStopsInputAndRejectsLateOldCallbacks() {
        h.start(); val old = h.input
        h.main { h.controller.onContextChanged("host-b:conversation-b") }
        h.await("context fenced") { h.view.state == LiveConversationState.Interrupted }
        h.main { old.onset(1); old.final(1, "Old host speech"); old.ready() }
        h.main { assertEquals(1, old.stops); assertTrue(h.attempts.isEmpty()); assertEquals("", h.view.caption.visibleText) }
    }

    @Test fun l72_teardownFailureIsVisibleAndStillFencesOldFinal() {
        h.start(); val old = h.input
        old.stopFailure = IllegalStateException("Hardware stop not confirmed")
        h.main { h.controller.endLive() }
        h.await("teardown failure visible") { h.view.state is LiveConversationState.Failure }
        h.main { old.onset(1); old.final(1, "Cannot trust this callback") }
        h.main { assertTrue(h.attempts.isEmpty()); assertTrue((h.view.state as LiveConversationState.Failure).message.contains("teardown", true)) }
    }

    /** Controller/Room/UI plus a controlled hardware boundary, not physical microphone evidence.
     * Hold the actor inside an independent output-control callback: privacy-stop entry points must
     * revoke capture on the calling thread, before queued teardown or UI progress can run. */
    @Test fun l63_l68_l73_publicPrivacyStopFencesCaptureBeforeBlockedActorCleanupAndFreshStartKeepsOwnership() {
        val explicitCancellations = mutableListOf<String>()
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        for (step in 1..3) {
            h.start(); h.utterance(step.toLong(), "Durable point $step before a blocked local stop")
            val request = h.attempts.last().requestId
            val oldInput = h.input
            h.playing(request, "Owned reply $step remains visible until local teardown")
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            h.main {
                h.outputMuteObserver = { muted ->
                    if (muted) {
                        entered.countDown()
                        check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Blocked-actor fixture was not released" }
                    }
                }
            }
            mainHandler.post { h.controller.toggleOutputMute() }
            try {
                assertTrue("The production actor must reach the controlled busy boundary", entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue("The controlled capture boundary is still open before the stop", oldInput.recording)
                when (step) {
                    1 -> h.controller.onConnectionLost()
                    2 -> h.controller.pauseLive()
                    3 -> { explicitCancellations += request; h.controller.endLive() }
                }
                // No compose.runOnIdle/await here: Main is deliberately blocked. These assertions
                // distinguish synchronous privacy revocation from an eventually-correct label.
                assertFalse("Capture must be fenced before the actor is allowed to perform cleanup", oldInput.recording)
                assertEquals("The actual controlled capture owner stops once", 1, oldInput.stops)
                assertEquals("The actor has not advanced the UI state yet", LiveConversationState.Speaking(request), h.view.state)
                assertTrue("A privacy fence itself cannot dispatch cancellation", h.cancelled.none { it == request })
                oldInput.onset(99); oldInput.final(99, "Retired words must never become a durable command")
                h.controller.onAssistantPlaying(request, "Retired PCM", 1f, AudioWaveform.Silence)
            } finally { release.countDown() }
            val terminal = if (step == 1) LiveConversationState.Paused else LiveConversationState.Idle
            h.await("Queued cleanup must finish without restarting the stopped input") { h.view.state == terminal }
            h.await("Only explicit End may cancel its original request") { h.cancelled == explicitCancellations }
            h.main {
                h.outputMuteObserver = null
                oldInput.ready(); oldInput.final(100, "Late finalized words after teardown")
                assertEquals(step, h.attempts.size)
                assertEquals(step, h.inputs.size)
                assertEquals(1, oldInput.stops)
            }
            compose.onNodeWithText("Start Live").assertIsDisplayed()
        }
        val rows = runBlocking { h.repository.pendingPrompts("host-a") }
        assertEquals("Every deliberately recognized point remains independently durable", 3, rows.size)
        assertEquals(h.attempts.map { it.requestId }.toSet(), rows.map { it.requestId }.toSet())
        assertEquals(1, explicitCancellations.size)
    }

    @Test fun l73_repeatedEndDiscardsUnfinishedSpeechAndDoesNotStartAnotherTurn() {
        h.start(); val old = h.input
        h.main { old.onset(1); old.partial(1, "Unfinished", "point"); h.controller.endLive(); h.controller.endLive() }
        h.await("ended") { h.view.state == LiveConversationState.Idle }
        h.main { old.final(1, "Unfinished point"); assertTrue(h.attempts.isEmpty()); assertEquals(1, old.stops); assertEquals("", h.view.caption.visibleText) }
    }
}
