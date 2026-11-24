package com.kompi.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.kompi.app.data.RequestStateRow
import com.kompi.app.protocol.generated.PiFrame
import com.kompi.app.transport.PiSessionStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real production projection + Pi event store + Compose screen. Remote delivery is controlled;
 * these journeys do not claim a real daemon handshake or physical TalkBack verification.
 */
class ChatMessageContinuityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var pending by mutableStateOf(listOf("request-a" to "Keep this message visible"))
    private var requests by mutableStateOf(listOf(state("queued", "")))
    private var dispatch by mutableStateOf("queued")
    private var connection by mutableStateOf("Live")
    private lateinit var store: PiSessionStore
    private var lastLines: List<TranscriptLine> = emptyList()

    private fun state(outcome: String, conversation: String = "conversation-a", id: String = "request-a") =
        RequestStateRow("host-a", id, conversation, outcome)
    private fun frame(kind: String, sequence: Long, payload: JSONObject, request: String? = "request-a", epoch: String = "epoch-a") =
        PiFrame("conversation-a", epoch, payload.toString(), kind, true, 0, request, sequence, "session-a")
    private fun entry(id: String, parent: String? = null, text: String = "Keep this message visible") = JSONObject()
        .put("type", "message").put("id", id).put("parentId", parent ?: JSONObject.NULL)
        .put("message", JSONObject().put("role", "user").put("content", text))
    private fun snapshot(entries: List<JSONObject> = emptyList(), epoch: String = "epoch-a") = frame("snapshot", 0,
        JSONObject().put("entries", JSONArray(entries)).put("leafId", entries.lastOrNull()?.getString("id") ?: JSONObject.NULL), epoch = epoch)
    private fun mount() {
        compose.runOnUiThread {
            store = PiSessionStore(needSnapshot = {}, publicDelta = { _, _, _, _, _ -> }, settled = { _, _, _, _ -> })
            store.bindConversation("conversation-a")
            store.accept(snapshot())
        }
        compose.setContent {
            val view by store.view.collectAsState()
            val echoes = remember { LocalMessageEchoes() }
            val projection = buildTranscriptProjection(view, echoes.reconcile(view.conversationId, pending, requests), requests, emptyList(), outboxStates = mapOf("request-a" to dispatch))
            SideEffect { lastLines = projection.active }
            ChatBehaviorSurface(transcript = projection.active, connection = connection)
        }
    }
    private fun assertOneBubble(status: String? = null) {
        compose.onAllNodesWithText("Keep this message visible").assertCountEquals(1)
        compose.onNodeWithText("Keep this message visible").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("user:request-a"), lastLines.filter { it.fromUser }.map { it.key }) }
        if (status != null) {
            compose.onNodeWithText(status).assertDoesNotExist()
            compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, status)).assertExists()
        }
    }

    @Test fun m1_m2_m3_delayedAcknowledgementReconnectAndEarlyCompletionKeepOneBubble() {
        mount()
        assertOneBubble("Sending")
        // Virtual time proves a long delay cannot time out/hide the pending bubble.
        compose.mainClock.advanceTimeBy(10_000)
        assertOneBubble("Sending")
        for (phase in listOf("Disconnected", "Connecting", "Synchronizing")) {
            compose.runOnIdle { connection = phase; store.reconnect() }
            assertOneBubble("Waiting for connection")
        }
        compose.runOnIdle { connection = "Live"; dispatch = "accepted"; requests = listOf(state("accepted")) }
        assertOneBubble("Accepted by your computer")
        compose.runOnIdle { requests = listOf(state("queued")) }
        assertOneBubble("Accepted by your computer")
        compose.runOnIdle { requests = listOf(state("running")) }
        assertOneBubble("Accepted by your computer")
        compose.onAllNodesWithContentDescription("Waiting for reply").assertCountEquals(1)
        // Room removes terminal outbox rows before the independent Pi content stream arrives.
        compose.runOnIdle { requests = listOf(state("completed")); pending = emptyList() }
        assertOneBubble("Accepted by your computer")
        compose.onNodeWithContentDescription("Waiting for reply").assertDoesNotExist()
        compose.runOnIdle { store.accept(snapshot(epoch = "epoch-b")) }
        assertOneBubble()
        compose.runOnIdle { store.accept(frame("event", 1, JSONObject().put("type", "entry_appended").put("entry", entry("user-a")), epoch = "epoch-b")) }
        assertOneBubble()
        compose.runOnIdle { store.reconnect(); store.accept(snapshot(listOf(entry("user-a")), "epoch-c")) }
        assertOneBubble()
    }

    @Test fun m3_equalTextQueuedIntentsRetainDistinctKeysThroughCanonicalReplacement() {
        pending = listOf("request-a" to "Keep this message visible", "request-b" to "Keep this message visible")
        requests = listOf(state("accepted"), state("queued", "", "request-b"))
        mount()
        compose.onAllNodesWithText("Keep this message visible").assertCountEquals(2)
        compose.runOnIdle { store.accept(frame("event", 1, JSONObject().put("type", "entry_appended").put("entry", entry("user-a")))) }
        compose.onAllNodesWithText("Keep this message visible").assertCountEquals(2)
        compose.runOnIdle {
            store.accept(frame("entries", 1, JSONObject().put("entries", JSONArray().put(entry("user-a")).put(entry("user-b", "user-a"))).put("leafId", "user-b"), request = "request-b"))
        }
        compose.onAllNodesWithText("Keep this message visible").assertCountEquals(2)
        compose.runOnIdle { assertEquals(listOf("user:request-a", "user:request-b"), lastLines.filter { it.fromUser }.map { it.key }) }
    }

    @Test fun m2_q5_terminalFailureNeverDropsTheOriginalMessageOrPretendsItWasSent() {
        mount()
        compose.runOnIdle { dispatch = "dispatching"; connection = "Disconnected" }
        assertOneBubble("Delivery not confirmed")
        compose.onNodeWithText("Check conversation").assertIsNotEnabled()
        compose.onNodeWithText("Discard").assertDoesNotExist()
        compose.onNodeWithText("Review draft").assertDoesNotExist()
        for ((outcome, label) in listOf("failed" to "Accepted by your computer", "uncertain" to "Accepted by your computer", "host-cancelled" to "Accepted by your computer", "rejected" to "Message not accepted", "local-failure" to "Couldn’t send message")) {
            compose.runOnIdle { requests = listOf(state(outcome)); pending = emptyList() }
            assertOneBubble(label)
        }
    }

    @Test fun q3_hostAndConversationChangesCannotLeakRetainedEchoes() {
        val echoes = LocalMessageEchoes()
        assertEquals(1, echoes.reconcile("conversation-a", pending, requests).size)
        assertTrue(echoes.reconcile("conversation-b", emptyList(), emptyList()).isEmpty())
        assertTrue(LocalMessageEchoes().reconcile("conversation-a", emptyList(), requests).isEmpty())
    }
}
