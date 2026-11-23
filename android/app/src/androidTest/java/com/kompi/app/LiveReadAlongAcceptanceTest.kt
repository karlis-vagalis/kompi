package com.kompi.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.kompi.app.audio.AudioWaveform
import com.kompi.app.audio.KokoroPlaybackPhase
import com.kompi.app.live.LiveConversationState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real controller, encrypted Room outbox and Compose Markdown/scrolling. The recognizer and
 * playback callbacks are controlled here; these checks are not native/acoustic timing evidence. */
class LiveReadAlongAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var h: LiveBehaviorHarness
    @Before fun mount() { h = LiveBehaviorHarness(compose); h.mount() }
    @After fun cleanup() = h.close()

    private fun beginReply(source: String): String {
        h.start(); h.utterance(1, "Review this answer")
        val id = h.attempts.single().requestId
        h.main { h.controller.updateAssistantText(id, source) }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Agent response").fetchSemanticsNodes().isNotEmpty()
        }
        return id
    }

    private fun playing(id: String, sequence: Long, phrase: String) {
        h.main { h.controller.onAssistantPlayback(KokoroPlaybackPhase.Playing(id, sequence, phrase,
            0.2f, AudioWaveform.fromPcm(FloatArray(320) { 0.2f }, 16_000))) }
        h.await("Owned playback must reach the Live view") {
            h.view.readAlong == com.kompi.app.live.LiveReadAlong.Reply(sequence, phrase)
        }
        compose.waitForIdle()
    }

    private fun layout(text: String): TextLayoutResult {
        val node = compose.onNodeWithText(text, useUnmergedTree = true)
        val result = mutableListOf<TextLayoutResult>()
        compose.waitUntil(5_000) { node.fetchSemanticsNode().config.contains(SemanticsActions.GetTextLayoutResult) }
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        return result.single()
    }

    @Test fun l57_repeatedMarkdownPhraseHighlightsOnlyItsSequentialOwnedOccurrenceAndClears() {
        val source = "First **repeat this phrase**. Then **repeat this phrase**."
        val rendered = "First repeat this phrase. Then repeat this phrase."
        val phrase = "repeat this phrase"
        val id = beginReply(source)
        playing(id, 0, phrase)
        fun highlightedStart(): Int {
            val text = layout(rendered).layoutInput.text
            val highlight = text.spanStyles.filter { it.item.background != Color.Unspecified && it.item.background.alpha > 0f }
            assertEquals("Only the actually owned occurrence may be highlighted", 1, highlight.size)
            assertEquals(phrase.length, highlight.single().end - highlight.single().start)
            return highlight.single().start
        }
        assertEquals(rendered.indexOf(phrase), highlightedStart())
        playing(id, 1, phrase)
        val second = rendered.lastIndexOf(phrase)
        assertEquals("The next measured sequence advances across repeated Markdown words", second, highlightedStart())
        playing(id, 1, phrase)
        assertEquals("Repeated PCM for the same sequence must not move the highlight", second, highlightedStart())
        playing(id, 2, "This phrase does not exist in the reply")
        assertTrue("Never fabricate a read-along match", layout(rendered).layoutInput.text.spanStyles.none {
            it.item.background != Color.Unspecified && it.item.background.alpha > 0f
        })
        h.main { h.controller.onAssistantPlaybackDrained(id) }
        h.await("Drain clears ownership without deleting the public answer") { h.view.readAlong == null }
        assertEquals(source, h.view.assistantText)
        assertTrue(layout(rendered).layoutInput.text.spanStyles.none {
            it.item.background != Color.Unspecified && it.item.background.alpha > 0f
        })
    }

    @Test fun l59_userDragWinsForCurrentPhraseAndFollowingResumesOnlyForNextOwnedPhrase() {
        val answer = (1..36).joinToString("\n\n") { "Paragraph $it has enough words to scroll through the Live viewport." }
        val id = beginReply(answer)
        playing(id, 0, "Paragraph 1 has enough words")
        val scroll = compose.onNode(hasScrollAction())
        fun offset() = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val beforeDrag = offset()
        // Keep a real pointer down while another measured callback for the current phrase arrives.
        scroll.performTouchInput { down(center); moveBy(Offset(0f, -150f), delayMillis = 300) }
        val dragging = offset()
        assertTrue("The real drag must move the document", dragging > beforeDrag + 2f)
        playing(id, 0, "Paragraph 1 has enough words")
        assertEquals("Following must not fight a pointer-owned drag", dragging, offset(), 2f)
        scroll.performTouchInput { up() }
        compose.waitForIdle()
        val manual = offset()
        playing(id, 0, "Paragraph 1 has enough words")
        assertEquals("Manual reading owns this phrase even after the pointer is lifted", manual, offset(), 2f)
        playing(id, 1, "Paragraph 35 has enough words")
        compose.waitUntil(5_000) { offset() > manual + 2f }
        h.main { h.controller.onAssistantPlaybackDrained(id) }
        h.await("No follow target remains after drain") { h.view.readAlong == null }
        assertEquals(answer, h.view.assistantText)
    }

    @Test fun l61_failedSpeechIntentStaysReviewableAndRetryUsesSameDurableIdentity() {
        h.sendFailure = IllegalStateException("Outbox temporarily unavailable")
        h.start()
        val words = "Please review the full failed intent including the identifier native_stream_17"
        h.utterance(1, words)
        h.await("Failed delivery must expose reviewable recovery") { h.view.state is LiveConversationState.Failure }
        val failed = h.attempts.single()
        compose.onNodeWithText(words).assertIsDisplayed()
        compose.onNodeWithText("Not sent · Outbox temporarily unavailable").assertIsDisplayed()
        assertTrue(runBlocking { h.repository.pendingPrompts("host-a") }.isEmpty())
        h.sendFailure = null
        compose.onNodeWithText("Retry").assertIsDisplayed().performClick()
        h.await("Explicit recovery durably re-enqueues the same pending intent") {
            h.attempts.size == 2 && h.view.state == LiveConversationState.Waiting(failed.requestId) &&
                runBlocking { h.repository.pendingPrompts("host-a") }.singleOrNull()?.let {
                    it.requestId == failed.requestId && it.text == words
                } == true
        }
        assertEquals("Recovery must not manufacture a new spoken intent", failed, h.attempts.last())
        assertEquals(words, h.view.caption.visibleText)
        val persisted = runBlocking { h.repository.pendingPrompts("host-a") }
        assertEquals(1, persisted.size)
        assertEquals(failed.requestId, persisted.single().requestId)
        assertEquals(words, persisted.single().text)
    }

    @Test fun l61_longFailedIntentOffersCompleteReviewAndRetriesTheSameUntruncatedIntent() {
        val sections = listOf("Failed-intent-begin-4187: Review every instruction before recovering delivery.") +
            (1..40).map { index ->
                "Section ${index.toString().padStart(2, '0')}: Keep symbol review_item_$index, the value ${index * 1009}, and the note Rīga Ω unchanged; do not drop this instruction during review or recovery."
            } + "Failed-intent-tail-7319: Preserve this final instruction: do not execute a second command."
        val words = sections.joinToString("\n\n")
        assertTrue("Fixture must exceed the caption bound without exceeding the accepted intent limit", words.length in 4_001 until 16_000)
        assertTrue(words.indexOf(sections.last()) > 4_000)
        h.sendFailure = IllegalStateException("Long intent delivery is temporarily unavailable")
        h.start(); h.utterance(1, words)
        h.await("The long accepted final must become a recoverable delivery failure") { h.view.state is LiveConversationState.Failure }
        val failed = h.attempts.single()
        assertEquals("The failed immutable intent must retain every word", words, failed.text)
        assertTrue(runBlocking { h.repository.pendingPrompts("host-a") }.isEmpty())
        compose.onNodeWithText("Retry").assertIsDisplayed()

        fun exposesFullReview(): Boolean {
            // Accept one full text field or ordered separate paragraphs, including an explicit
            // review surface. The bounded live caption is NOT required to hold the entire intent.
            val textNodes = compose.onAllNodes(SemanticsMatcher("accessible review text") {
                it.config.contains(SemanticsProperties.Text) || it.config.contains(SemanticsProperties.EditableText)
            }, useUnmergedTree = true).fetchSemanticsNodes()
            val accessible = textNodes.flatMap { node -> buildList {
                if (node.config.contains(SemanticsProperties.Text)) addAll(node.config[SemanticsProperties.Text].map { it.text })
                if (node.config.contains(SemanticsProperties.EditableText)) add(node.config[SemanticsProperties.EditableText].text)
            } }.joinToString("\n")
            var from = 0
            return sections.all { section ->
                val at = accessible.indexOf(section, startIndex = from)
                if (at < 0) false else { from = at + section.length; true }
            }
        }
        var reviewFailure: Throwable? = null
        try {
            if (!exposesFullReview()) {
                val reviewAction = hasClickAction() and SemanticsMatcher("named full-intent review action") { node ->
                    val labels = buildList {
                        if (node.config.contains(SemanticsProperties.Text)) addAll(node.config[SemanticsProperties.Text].map { it.text })
                        if (node.config.contains(SemanticsProperties.ContentDescription)) addAll(node.config[SemanticsProperties.ContentDescription])
                    }
                    labels.any { Regex("(?i)\\b(review|full|view|show|read|open)\\b").containsMatchIn(it) }
                }
                val actions = compose.onAllNodes(reviewAction)
                assertTrue("A shortened failed caption must offer an accessible full-intent review action", actions.fetchSemanticsNodes().isNotEmpty())
                actions.onFirst().performClick()
            }
            compose.waitUntil(5_000) { exposesFullReview() }
            val tailInUi = SemanticsMatcher("distinctive tail in actual review UI") { node ->
                val text = buildList {
                    if (node.config.contains(SemanticsProperties.Text)) addAll(node.config[SemanticsProperties.Text].map { it.text })
                    if (node.config.contains(SemanticsProperties.EditableText)) add(node.config[SemanticsProperties.EditableText].text)
                }
                text.any { it.contains(sections.last()) }
            }
            val scrollableTail = compose.onAllNodes(tailInUi and hasAnyAncestor(hasScrollAction()), useUnmergedTree = true)
            if (scrollableTail.fetchSemanticsNodes().isNotEmpty()) scrollableTail.onFirst().performScrollTo()
            // The sentinel must be exposed by a placed review node, not merely retained in a
            // controller field or test adapter. Full text may live outside the bounded caption.
            compose.onAllNodes(tailInUi, useUnmergedTree = true).onFirst().assertIsDisplayed()
        } catch (error: Throwable) {
            // Still exercise immutable recovery before reporting the missing review, so this red
            // case distinguishes inaccessible text from truncation or duplicate delivery.
            reviewFailure = error
        }
        h.sendFailure = null
        // The short-intent journey above taps the actual Retry control. Invoke its controller
        // action here so a valid separate review dialog need not duplicate/relocate that control.
        h.main { h.controller.retryPendingTurn() }
        h.await("Recovery must enqueue the complete original long intent") {
            h.attempts.size == 2 && h.view.state == LiveConversationState.Waiting(failed.requestId) &&
                runBlocking { h.repository.pendingPrompts("host-a") }.singleOrNull()?.let {
                    it.requestId == failed.requestId && it.text == words
                } == true
        }
        assertEquals(failed, h.attempts.last())
        val persisted = runBlocking { h.repository.pendingPrompts("host-a") }
        assertEquals(1, persisted.size)
        assertEquals(failed.requestId, persisted.single().requestId)
        assertEquals(words, persisted.single().text)
        assertTrue("The distinctive final instruction must survive durable retry", persisted.single().text.endsWith(sections.last()))
        reviewFailure?.let { throw AssertionError("The full failed intent, including its middle and distinctive tail beyond 4000 characters, is not available for accessible review; immutable retry was independently verified", it) }
    }
}
