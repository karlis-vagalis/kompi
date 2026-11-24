package com.kompi.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.kompi.app.protocol.generated.PiFrame
import com.kompi.app.transport.ConversationTarget
import com.kompi.app.transport.PiSessionStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Real projection, pane lifecycle and conversation UI. Only the peer's frame delivery is controlled. */
class ReplyRecoveryScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun firstSendRequiresExplicitModelSelectionAndPreservesDraft() {
        val default = com.kompi.app.protocol.generated.ChatModelConfiguration("high", "fixture-model", "fixture")
        val choices = com.kompi.app.protocol.generated.ModelCatalog(default, listOf(
            com.kompi.app.protocol.generated.ChatModel(listOf("high"), default.modelId, "Fixture model", default.provider)))
        var selected by mutableStateOf<com.kompi.app.protocol.generated.ChatModelConfiguration?>(null)
        var catalog by mutableStateOf<com.kompi.app.protocol.generated.ModelCatalog?>(null)
        var reads = 0
        val sent = mutableListOf<String>()
        compose.setContent {
            ChatBehaviorSurface(configuration = selected, catalog = catalog,
                onRefreshModels = { reads++; catalog = choices }, onSelectModel = { selected = it },
                onSend = { text, _ -> sent += text; "fixture-request" })
        }
        compose.onNodeWithTag("prompt-input").performTextInput("First draft")
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, reads) }
        compose.onNodeWithText("Choose a model to send").assertIsDisplayed().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        compose.onNodeWithText("First draft").assertIsDisplayed()
        compose.onNodeWithText("Choose a model to send").performClick()
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle { assertEquals(default, selected); assertEquals(1, reads); assertEquals(emptyList<String>(), sent) }
        compose.onNodeWithContentDescription("Send message").assertIsEnabled().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(listOf("First draft"), sent); assertEquals(1, reads) }
    }

    @Test fun disconnectedCompletionReopensWithReadableReplyAndBoundedRetry() {
        val target = ConversationTarget.saved("host-a", "00000000-0000-4000-8000-000000000001")
        val store = PiSessionStore(needSnapshot = {}, publicDelta = { _, _, _, _, _ -> }, settled = { _, _, _, _ -> })
        var phase by mutableStateOf("Live")
        var completed by mutableStateOf(emptySet<String>())
        var selected by mutableStateOf<ConversationTarget?>(target)
        var requests = 0
        var sends = 0
        var modelReads = 0
        fun snapshot(chat: String, sequence: Long, answer: String) = PiFrame(conversationId = chat, epoch = "epoch-a",
            sessionId = "session-a", part = 0, last = true, kind = "snapshot", requestId = "read-$sequence", sequence = sequence,
            json = """{"entries":[{"id":"line","type":"message","message":{"role":"assistant","content":[{"type":"text","text":"$answer"}]}}],"leafId":"line"}""")
        compose.runOnUiThread { store.bindHost(target.hostId); store.bindConversation(target.id) }
        compose.setContent {
            val view by store.view.collectAsState()
            val pane = rememberChatHistory(selected, view, store.view, "active", phase, true, true, completed,
                { requests++; store.reconnect() }, { error("Unexpected archive read") }, timeoutMs = 2_000)
            ChatBehaviorSurface(history = pane, connection = phase, transcript = pane.view.lines.map {
                TranscriptLine(it.text, it.fromUser, it.key, body = it.body)
            }, onSend = { _, _ -> sends++; "unexpected" }, onRefreshModels = { modelReads++ })
        }
        compose.onNodeWithText("Loading conversation…").assertIsDisplayed()
        compose.waitUntil(8_000) { compose.onAllNodesWithText("Couldn’t load this conversation.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Loading conversation…").assertDoesNotExist()
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(2, requests); store.accept(snapshot(target.id, 1, "Earlier answer")) }
        compose.onNodeWithText("Earlier answer").assertIsDisplayed()
        compose.runOnIdle { phase = "Disconnected"; store.reconnect(); completed = setOf("accepted-once") }
        compose.onNodeWithText("Earlier answer").assertIsDisplayed()
        compose.runOnIdle { selected = null; store.bindConversation(null) }
        compose.runOnIdle { selected = target; store.bindConversation(target.id); phase = "Live" }
        compose.onNodeWithText("Earlier answer").assertIsDisplayed()
        compose.runOnIdle { store.accept(snapshot("chat-b", 2, "Another conversation")) }
        compose.onNodeWithText("Another conversation").assertDoesNotExist()
        compose.onNodeWithText("Earlier answer").assertIsDisplayed()
        compose.runOnIdle { store.accept(snapshot(target.id, 2, "Recovered final reply")) }
        compose.onAllNodesWithText("Recovered final reply").assertCountEquals(1)
        compose.onNodeWithText("Recovered final reply").assertIsDisplayed()
        compose.onNodeWithText("Loading conversation…").assertDoesNotExist()
        compose.runOnIdle { assertEquals(3, requests); assertEquals(0, sends); assertEquals(0, modelReads) }
        compose.onNodeWithText("fixture-model · high").performClick()
        compose.runOnIdle { assertEquals(1, modelReads) }
    }
}
