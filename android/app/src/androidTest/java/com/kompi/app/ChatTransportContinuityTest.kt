package com.kompi.app

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.kompi.app.data.LocalConversationState
import com.kompi.app.data.LocalDatabase
import com.kompi.app.data.LocalRepository
import com.kompi.app.protocol.generated.*
import com.kompi.app.security.SecureStore
import com.kompi.app.transport.IrohTransport
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real Android transport, authenticated QUIC, encrypted Room outbox and production chat
 * projection/screen. The remote daemon/Pi is a controllable native peer, not production E2E.
 */
class ChatTransportContinuityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val nonce = UUID.randomUUID().toString()
    private lateinit var context: Context
    private lateinit var db: LocalDatabase
    private lateinit var repository: LocalRepository
    private lateinit var secure: SecureStore
    private lateinit var peer: LiveLoopbackPeer
    private lateinit var pairing: SecureStore.Pairing
    private var transport by mutableStateOf<IrohTransport?>(null)
    private var rendered: List<TranscriptLine> = emptyList()
    private var sawBubble = false
    private val composedUserKeys = mutableListOf<List<String>>()
    private var dark by mutableStateOf(false)

    private fun openTransport() {
        compose.runOnUiThread { transport = IrohTransport(context, secure, repository).also { it.reconnect(pairing) } }
        compose.waitUntil(30_000) { transport!!.state.value == "Live" }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = File(context.getExternalFilesDir(null), "chat-continuity").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    @After fun cleanup() {
        runBlocking {
            transport?.closeAndJoin()
            if (::peer.isInitialized) peer.close()
        }
        if (::db.isInitialized) { db.close(); context.deleteDatabase("chat-wire-$nonce.db") }
    }

    @Test fun m1_m2_m3_r3_realQuicWithheldAckAndTransportReplacementPreserveOneBubble() = runJourney(loseAcceptance = false)

    @Test fun m3_r1_r3_lostAcceptanceReconcilesOnRealQuicWithoutResendingOrHidingBubble() = runJourney(loseAcceptance = true)

    private fun preparePeer() {
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val base = compose.activity.applicationContext
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("chat-wire-$nonce-$name", mode)
        }
        secure = SecureStore(context)
        db = Room.databaseBuilder(context, LocalDatabase::class.java, "chat-wire-$nonce.db").build()
        repository = LocalRepository(db, secure)
        runBlocking { peer = LiveLoopbackPeer.open(context); pairing = peer.pair(context) }
    }

    @Test fun completedOfflineNeedsReadableReplyAfterReopen() = replyRecovery(firstTurn = false)
    @Test fun firstTurnCompletesWithoutLeavingNewChat() = replyRecovery(firstTurn = true)

    private fun replyRecovery(firstTurn: Boolean) {
        preparePeer()
        peer.independentChats = true
        val target = com.kompi.app.transport.ConversationTarget.saved(peer.hostId, peer.conversationId)
        val id = "request-recover-once"
        var opened by mutableStateOf(true)
        var selectedTarget by mutableStateOf(if (firstTurn) com.kompi.app.transport.ConversationTarget.draft(target.hostId, target.id) else target)
        openTransport()
        compose.setContent {
            val net = transport!!
            val phase by net.state.collectAsState()
            val local by remember(net) { repository.observe(peer.hostId) }.collectAsState(LocalConversationState(emptyList(), emptyList(), null, emptyList(), null))
            val selected = selectedTarget.takeIf { opened }
            val recorded = local.sessions.firstOrNull { it.conversationId == target.id }
            PromoteRecordedChat(selected, recorded?.conversationId) { net.focusConversation(it); selectedTarget = it }
            LaunchedEffect(net, selected) { net.focusConversation(selected) }
            val live by net.piSession.view.collectAsState()
            val pane = key(net) { rememberChatHistory(selected, live, net.piSession.view, if (firstTurn) recorded?.state else "active", phase, true, false,
                local.requests.filter { it.outcome == "completed" }.map { it.requestId }.toSet(), net::requestConversationSnapshot,
                { error("Unexpected archive read") }, timeoutMs = 2_000) }
            val echoes = remember(selected?.id) { LocalMessageEchoes() }.reconcile(pane.view.conversationId,
                local.outbox.map { it.requestId to it.text }, local.requests)
            val lines = buildTranscriptProjection(pane.view, echoes, local.requests, emptyList(), outboxStates = local.outbox.associate { it.requestId to it.state }, observedWrites = net.observedWrites.collectAsState().value.keys, snapshotCompletions = pane.snapshotCompletions).active
            ChatBehaviorSurface(history = pane, connection = phase, transcript = lines,
                onSend = { text, files -> net.sendPromptTo(checkNotNull(selected), text, files, id) })
        }
        compose.onNodeWithTag("prompt-input").performTextInput("Count the fixture cards")
        compose.onNodeWithContentDescription("Send message").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(30_000) { peer.prompts.size == 1 }
        compose.waitUntil(10_000) { compose.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Accepted by your computer")).fetchSemanticsNodes().isNotEmpty() }
        val finalHistory = """{"entries":[{"type":"message","id":"user","message":{"role":"user","content":"Count the fixture cards"}},{"type":"message","id":"answer","parentId":"user","message":{"role":"assistant","content":"There are four fixture cards."}}],"leafId":"answer"}"""
        if (firstTurn) {
            runBlocking {
                val metadata = ConversationMetadata(peer.configuration, target.id, "2025-01-01T00:00:00Z", "2025-01-01T00:30:00Z", 1800, peer.snapshotSessionId, state = "active")
                peer.send(WireMessage.SyncEvents(listOf(SyncEvent("1", DurableEvent.SessionChanged(SessionChangedPayload(metadata)))), "1"))
            }
            compose.waitUntil(10_000) { !selectedTarget.createIfMissing }
            compose.onNodeWithText("Count the fixture cards").assertIsDisplayed()
            compose.onNodeWithText("Loading conversation…").assertDoesNotExist()
            compose.onNodeWithText("Loading earlier messages…").assertDoesNotExist()
            runBlocking {
                // Final record arrives before the base: it cannot be rendered or treated as history yet.
                peer.send(WireMessage.PiFrame(PiFrame(target.id, "controlled-epoch", finalHistory, "settled", true, 0, id, 1, peer.snapshotSessionId)))
                peer.targetedSnapshot = finalHistory
                peer.send(WireMessage.SyncEvents(listOf(SyncEvent("2", DurableEvent.RequestChanged(
                    RequestChangedPayload(peer.summaries.getValue(id).copy(outcome = "completed"))))), "2"))
            }
        } else {
            compose.runOnIdle { opened = false }
            runBlocking { transport!!.closeAndJoin() }
            peer.summaries[id] = peer.summaries.getValue(id).copy(outcome = "completed")
            openTransport()
            compose.runOnIdle { opened = true }
            compose.onNodeWithText("Loading conversation…").assertIsDisplayed()
            compose.onNodeWithContentDescription("Waiting for reply").assertDoesNotExist()
            runBlocking {
                // Correct chat but stale read ID: neither the spinner nor this pane's rows may
                // be satisfied with a fragment that belongs to an earlier snapshot.
                peer.send(WireMessage.PiFrame(PiFrame(target.id, "controlled-epoch", finalHistory.replace("four fixture cards", "obsolete cards"),
                    "snapshot", true, 0, "stale-read", 0, peer.snapshotSessionId)))
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Couldn’t load the reply.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("There are obsolete cards.").assertDoesNotExist()
            compose.onNodeWithText("Loading conversation…").assertDoesNotExist()
            peer.targetedSnapshot = finalHistory
            compose.onNodeWithText("Check for reply").performClick()
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("There are four fixture cards.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("There are four fixture cards.").assertIsDisplayed()
        compose.onAllNodesWithText("There are four fixture cards.").assertCountEquals(1)
        compose.onNodeWithText("Loading conversation…").assertDoesNotExist()
        compose.onAllNodesWithText("Count the fixture cards").assertCountEquals(1)
        compose.onNodeWithText("Count the fixture cards").assertIsDisplayed()
        assertEquals(listOf(id), peer.prompts.map { it.id })
        assertEquals(setOf(target.id), peer.targetedReads.toSet())
        assertEquals(0, peer.modelRequests.get())
    }

    private fun runJourney(loseAcceptance: Boolean) {
        preparePeer()
        peer.acknowledgePrompts = false
        openTransport()
        compose.setContent {
            val network = transport!!
            val local by remember(network) { repository.observe(peer.hostId) }.collectAsState(LocalConversationState(emptyList(), emptyList(), null, emptyList(), null))
            val pi by network.piSession.view.collectAsState()
            val phase by network.state.collectAsState()
            val echoes = remember { LocalMessageEchoes() }
            val pending = local.outbox.map { it.requestId to it.text }
            val projection = buildTranscriptProjection(pi, echoes.reconcile(pi.conversationId, pending, local.requests), local.requests, emptyList(), outboxStates = local.outbox.associate { it.requestId to it.state }, observedWrites = network.observedWrites.collectAsState().value.keys)
            SideEffect {
                rendered = projection.active
                val keys = rendered.filter { it.fromUser }.map { it.key }
                if (sawBubble || "user:wire-request" in keys) {
                    sawBubble = true
                    composedUserKeys.add(keys)
                }
            }
            ChatBehaviorSurface(modifier = Modifier.imePadding(), transcript = projection.active, connection = phase,
                appearance = if (dark) AppTheme.Dark else AppTheme.Light,
                onSend = { text, _ -> network.sendPromptWithId(text, com.kompi.app.transport.Delivery.FollowUp, "wire-request") })
        }
        compose.onNodeWithTag("prompt-input").performTextInput("A message that stays here")
        val sentAt = SystemClock.elapsedRealtime()
        // Invoke the native accessibility action: an IME inset animation can move the
        // button between synthetic pointer-coordinate lookup and touch injection.
        compose.onNodeWithContentDescription("Send message").assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { action -> assertTrue(action()) }
        try {
            compose.waitUntil(30_000) { peer.prompts.size == 1 && rendered.any { it.key == "user:wire-request" } }
        } catch (failure: Throwable) {
            capture("submission-failure")
            throw AssertionError("prompts=${peer.prompts.size} phase=${transport!!.state.value} keys=${rendered.map { it.key }} peerErrors=${peer.errors.map { it.message }} tree=${compose.onRoot().printToString()}", failure)
        }
        val visibleMs = SystemClock.elapsedRealtime() - sentAt
        fun assertBubble() {
            compose.onAllNodesWithText("A message that stays here").assertCountEquals(1)
            compose.onNodeWithText("A message that stays here").assertIsDisplayed()
            compose.runOnIdle { assertEquals(listOf("user:wire-request"), rendered.filter { it.fromUser }.map { it.key }) }
        }
        capture("first-bubble")
        assertBubble()
        compose.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Sending")).assertIsDisplayed()
        capture("sending-light")
        compose.runOnIdle { dark = true }
        capture("sending-dark")
        // A controlled peer withholds acceptance for ten seconds of UI virtual time.
        compose.mainClock.advanceTimeBy(10_000)
        assertBubble()
        if (loseAcceptance) {
            // The peer received the intent, but the phone never received its ACK. Recovery must
            // reconcile the real Room outbox against the remote summary before retransmission.
            runBlocking { transport!!.closeAndJoin() }
            assertBubble()
            compose.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Delivery not confirmed")).assertIsDisplayed()
            capture("disconnected-dark")
            openTransport()
        } else {
            runBlocking {
                peer.send(WireMessage.Ack(peer.configuration, peer.conversationId, state = "queued"), id = "wire-request")
            }
            // A matching host queue acknowledgement is sufficient without any ledger update.
            compose.waitUntil(10_000) { rendered.any { it.deliveryStatus == com.kompi.app.chat.OutgoingMessageStatus.Sent } }
            peer.cursor = "1"
            runBlocking { peer.send(WireMessage.SyncEvents(listOf(SyncEvent("1", DurableEvent.RequestChanged(
                RequestChangedPayload(RequestSummary(peer.configuration, peer.conversationId, 0, "accepted", "wire-request"))
            ))), "1")) }
        }
        compose.waitUntil(30_000) { rendered.any { it.deliveryStatus == com.kompi.app.chat.OutgoingMessageStatus.Sent } }
        assertBubble()
        compose.onNodeWithText("> thinking").assertDoesNotExist()
        compose.onNodeWithContentDescription("Waiting for reply").assertIsDisplayed()
        capture("received-dark")
        runBlocking { transport!!.closeAndJoin() }
        assertBubble()
        openTransport()
        compose.waitUntil(30_000) { rendered.any { it.key == "user:wire-request" } }
        assertBubble()
        val user = JSONObject().put("type", "message").put("id", "canonical-user").put("parentId", JSONObject.NULL)
            .put("message", JSONObject().put("role", "user").put("content", "A message that stays here"))
        fun wireFrame(kind: String, sequence: Long, value: JSONObject) = WireMessage.PiFrame(PiFrame(
            peer.conversationId, "wire-epoch", value.toString(), kind, true, 0, "wire-request", sequence, "wire-session"))
        runBlocking {
            peer.send(wireFrame("snapshot", 0, JSONObject().put("entries", JSONArray()).put("leafId", JSONObject.NULL)))
            peer.send(wireFrame("event", 1, JSONObject().put("type", "entry_appended").put("entry", user)))
        }
        compose.waitUntil(30_000) { rendered.any { it.fromUser && it.piDetails != null } }
        assertBubble()
        capture("canonical-dark")
        val canonicalMs = SystemClock.elapsedRealtime() - sentAt
        Log.i("ChatContinuity", "native_quic lost_ack=$loseAcceptance local_visible_ms=$visibleMs canonical_ms=$canonicalMs prompts_received=${peer.prompts.size}")
        assertEquals("Accepted work must not be retransmitted on replacement", 1, peer.prompts.size)
        compose.runOnIdle {
            assertTrue("Every composed state after local echo must retain exactly the same bubble: $composedUserKeys",
                composedUserKeys.isNotEmpty() && composedUserKeys.all { it == listOf("user:wire-request") })
        }
    }
}
