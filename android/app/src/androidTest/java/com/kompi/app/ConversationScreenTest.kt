package com.kompi.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.kompi.app.security.SecureStore.PendingPrompt
import com.kompi.app.transport.Delivery
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Screen integration, not a simulated proof of daemon delivery or cancellation. */
class ConversationScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun input() = compose.onNodeWithTag("prompt-input")
    private fun send() = compose.onNodeWithContentDescription("Send message")

    @Test fun c2_c3_delayedSubmissionCannotClearANewerDraft() {
        val accepted = CompletableDeferred<String?>()
        val submissions = mutableListOf<String>()
        compose.setContent {
            ChatBehaviorSurface(onSend = { text, _ -> submissions.add(text); accepted.await() })
        }
        input().performTextInput("First instruction")
        send().performClick()
        input().performTextReplacement("A different instruction")
        compose.runOnIdle { accepted.complete("request-a") }
        input().assertTextEquals("A different instruction")
        compose.runOnIdle { assertEquals(listOf("First instruction"), submissions) }
    }

    @Test fun c3_editingSubmittedPrefixMustNotDeleteNewDraftText() {
        val accepted = CompletableDeferred<String?>()
        compose.setContent { ChatBehaviorSurface(onSend = { _, _ -> accepted.await() }) }
        input().performTextInput("Deploy")
        send().performClick()
        input().performTextReplacement("Deploy only after review")
        compose.runOnIdle { accepted.complete("request-a") }
        input().assertTextEquals("Deploy only after review")
    }

    @Test fun c3_rejectedLocalSubmissionKeepsDraftAndCanBeRetried() {
        val submissions = mutableListOf<String>()
        compose.setContent {
            ChatBehaviorSurface(onSend = { text, _ -> submissions.add(text); null })
        }
        input().performTextInput("Keep until safely queued")
        send().performClick()
        input().assertTextEquals("Keep until safely queued")
        send().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, submissions.size) }
    }

    @Test fun c3_q5_storageFailureShowsAnErrorAndPreservesDraft() {
        compose.setContent {
            ChatBehaviorSurface(onSend = { _, _ -> throw IllegalStateException("Storage unavailable") })
        }
        input().performTextInput("Do not lose this")
        send().performClick()
        input().assertTextEquals("Do not lose this")
        compose.onNode(hasText("Not queued: Storage unavailable") and hasAnyAncestor(hasTestTag("conversation-composer"))).assertIsDisplayed()
        send().assertIsEnabled()
    }

    @Test fun c4_s3_canSendNextMessageWhileAssistantIsRunning() {
        val submissions = mutableListOf<String>()
        compose.setContent {
            ChatBehaviorSurface(
                transcript = listOf(TranscriptLine("", false, "active:pending", waitingForReply = true)),
                onSend = { text, _ -> submissions.add(text); "next-request" },
            )
        }
        input().performTextInput("Next instruction")
        send().assertIsEnabled().performClick()
        input().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.runOnIdle { assertEquals(listOf("Next instruction"), submissions) }
    }

    @Test fun c1_a5_draftSurvivesSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { ChatBehaviorSurface() }
        input().performTextInput("Draft through recreation\nSecond line 🎧")
        restoration.emulateSavedInstanceStateRestore()
        input().assertTextEquals("Draft through recreation\nSecond line 🎧")
    }

    @Test fun c1_r5_reconnectPhasesDoNotEraseDraftOrDisableOfflineQueueing() {
        var phase by mutableStateOf("Live")
        compose.setContent { ChatBehaviorSurface(connection = phase) }
        input().performTextInput("Keep across reconnect")
        for (next in listOf("Disconnected", "Connecting", "Synchronizing", "Live")) {
            compose.runOnIdle { phase = next }
            input().assertTextEquals("Keep across reconnect")
            send().assertIsEnabled()
        }
    }

    @Test fun c2_duplicateTapCannotSubmitSameRevisionTwice() {
        val accepted = CompletableDeferred<String?>()
        val submissions = mutableListOf<String>()
        compose.setContent {
            ChatBehaviorSurface(onSend = { text, _ -> submissions.add(text); accepted.await() })
        }
        input().performTextInput("One instruction")
        send().performClick()
        send().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf("One instruction"), submissions); accepted.complete("request-a") }
        input().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test fun c5_multilineUnicodeSubmissionPreservesInternalContent() {
        val submissions = mutableListOf<String>()
        compose.setContent { ChatBehaviorSurface(onSend = { text, _ -> submissions.add(text); "request-a" }) }
        input().performTextInput("First line\n\nLatviešu teksts 🎧\nLast line")
        send().performClick()
        compose.runOnIdle { assertEquals(listOf("First line\n\nLatviešu teksts 🎧\nLast line"), submissions) }
    }

    @Test fun q4_discardOneSavedPromptDoesNotClearDraftOrItsNeighbor() {
        var pending by mutableStateOf(listOf(
            PendingPrompt("request-a", "Queued A", Delivery.FollowUp),
            PendingPrompt("request-b", "Queued B", Delivery.FollowUp),
        ))
        val discarded = mutableListOf<String>()
        compose.setContent {
            ChatBehaviorSurface(connection = "Disconnected", unsent = pending, onDiscard = { id ->
                discarded.add(id); pending = pending.filterNot { it.id == id }; true
            })
        }
        input().performTextInput("Unsubmitted draft")
        compose.onAllNodesWithText("Discard")[0].performClick()
        compose.onNodeWithText("Queued A").assertDoesNotExist()
        compose.onNodeWithText("Queued B").assertIsDisplayed()
        input().assertTextEquals("Unsubmitted draft")
        compose.runOnIdle { assertEquals(listOf("request-a"), discarded) }
    }

    @Test fun t1_t2_t3_streamingRespectsHistoryReadingAndJumpReturnsToLatest() {
        var lines by mutableStateOf((0..35).map {
            TranscriptLine("History message $it — enough content to make this a scrollable conversation.", it % 2 == 0, "entry-$it")
        })
        compose.setContent { ChatBehaviorSurface(transcript = lines) }
        val timeline = compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        timeline.performTouchInput { swipeDown() }
        compose.onNodeWithContentDescription("Jump to latest message").assertIsDisplayed()
        val before = timeline.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        compose.runOnIdle { lines = lines + TranscriptLine("New streamed answer", false, "latest", animateArrivals = true) }
        val after = timeline.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals("Incoming output must not move the history reader", before, after, 0.1f)
        compose.onNodeWithContentDescription("Jump to latest message").performClick()
        compose.onNodeWithText("New streamed answer").assertIsDisplayed()
        compose.onNodeWithContentDescription("Jump to latest message").assertDoesNotExist()
    }

    @Test fun c2_noHostCannotSubmitEvenWithDraftText() {
        compose.setContent { ChatBehaviorSurface(peer = null, initialDraft = "Not addressed to any host") }
        send().assertIsNotEnabled()
        input().assertTextEquals("Not addressed to any host")
    }
}
