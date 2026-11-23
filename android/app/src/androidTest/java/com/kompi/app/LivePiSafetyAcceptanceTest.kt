package com.kompi.app

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import com.kompi.app.data.LocalDatabase
import com.kompi.app.data.LocalRepository
import com.kompi.app.live.*
import com.kompi.app.protocol.generated.*
import com.kompi.app.security.SecureStore
import com.kompi.app.stream.AssistantSpeechStream
import com.kompi.app.transport.Delivery
import com.kompi.app.transport.IrohTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Real Live controller/Room/Keystore/Compose dialog and authenticated native QUIC transport.
 * ASR and remote Pi/tool execution are controlled boundaries, not acoustic or installed-Pi evidence.
 * Preferences and database are uniquely namespaced; no trusted production host is touched. */
class LivePiSafetyAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val nonce = UUID.randomUUID().toString()
    private val dbName = "live-wire-$nonce.db"
    private lateinit var context: Context
    private lateinit var peer: LiveLoopbackPeer
    private lateinit var db: LocalDatabase
    private lateinit var repository: LocalRepository
    private lateinit var store: SecureStore
    private lateinit var pairing: SecureStore.Pairing
    private var network by mutableStateOf<IrohTransport?>(null)
    private var controller by mutableStateOf<LiveConversationController?>(null)
    private lateinit var owner: LiveTestOwner
    private var runtimeScope: CoroutineScope? = null
    private val inputs = CopyOnWriteArrayList<ScriptedLiveInput>()
    private val requests = CopyOnWriteArrayList<String>()
    private val speechOffers = CopyOnWriteArrayList<String>()
    private val stoppedSpeech = CopyOnWriteArrayList<String?>()
    private val gates = CopyOnWriteArrayList<CompletableDeferred<Unit>>()
    private var cancellationFault: String? = null
    private val view get() = controller!!.snapshot.value

    @Before fun mount() {
        val base = compose.activity.applicationContext
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("live-wire-$nonce-$name", mode)
        }
        store = SecureStore(context)
        runBlocking { peer = LiveLoopbackPeer.open(context); pairing = peer.pair(context) }
        openRuntime()
        compose.setContent {
            val live = controller
            val transport = network
            if (live != null && transport != null) {
                val snapshot by live.snapshot.collectAsState()
                val dialogs by transport.piDialogs.collectAsState()
                val dialogError by transport.piDialogError.collectAsState()
                KompiTheme(AppTheme.System) {
                    LiveTalkScreen(snapshot, live::endLive, live::startLive, live::interruptAndListen, live::retryPendingTurn,
                        onToggleMicrophoneMute = live::toggleMicrophoneMute,
                        onToggleOutputMute = live::toggleOutputMute)
                    dialogs.firstOrNull()?.let { question -> PiRpcDialog(question, dialogError) { transport.answerPiDialog(question, it) } }
                }
            }
        }
    }

    private fun openRuntime() {
        db = Room.databaseBuilder(context, LocalDatabase::class.java, dbName).build()
        repository = LocalRepository(db, store)
        compose.runOnUiThread {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { runtimeScope = it }
            val transport = IrohTransport(context, store, repository).also { network = it }
            owner = LiveTestOwner().also { it.registry.currentState = Lifecycle.State.RESUMED }
            val speech = AssistantSpeechStream(
                speak = { id, _, text -> speechOffers += "$id:$text"; true },
                stop = { stoppedSpeech += it }, onTextUpdate = { id, _, text -> controller?.updateAssistantText(id, text) },
                onDiagnostic = { _, _ -> },
            )
            val live = LiveConversationController(owner,
                createSpeechInput = { ScriptedLiveInput().also(inputs::add) },
                sendTurn = { id, text ->
                    requests += id
                    check(transport.sendPromptWithId(text, Delivery.FollowUp, id) == id)
                    controller?.onTurnCommitted(id)
                },
                stopLocalSpeech = { if (it == null) speech.invalidate() else speech.cancel(it) },
                setOutputMuted = speech::setOutputMuted,
                cancelRemote = { id -> transport.cancel(id); cancellationFault?.let { throw IllegalStateException(it) } },
            ).also { controller = it }
            live.onContextChanged(peer.hostId)
            scope.launch { transport.events.collect { event -> when (event) {
                is IrohTransport.Event.ResponseDelta -> speech.accept(event.id, event.conversationId, event.sequence.toLong(), event.text)
                is IrohTransport.Event.Reply -> if (event.conversationId != null) speech.finish(event.id, event.conversationId, event.text)
                else -> Unit
            } } }
            transport.reconnect(pairing)
        }
        await("Actual authenticated transport must synchronize") { network!!.state.value == "Live" }
    }
    private fun await(message: String, condition: () -> Boolean) {
        try { compose.waitUntil(30_000, condition) }
        catch (error: Throwable) { throw AssertionError("$message; transport=${network?.state?.value}; live=${controller?.snapshot?.value}; peerErrors=${peer.errors.map { it.javaClass.simpleName }}", error) }
    }
    private fun start() {
        val previous = inputs.size
        compose.runOnIdle { controller!!.startLive() }
        await("One controlled input must be constructed") { inputs.size == previous + 1 }
        compose.runOnIdle { inputs.last().ready() }
        await("First frame callback must establish Listening") { view.state == LiveConversationState.Listening }
    }
    private fun say(sequence: Long, text: String) = compose.runOnIdle {
        inputs.last().onset(sequence); inputs.last().partial(sequence, text, ""); inputs.last().final(sequence, text)
    }
    private fun closeRuntime() = runBlocking {
        gates.forEach { it.complete(Unit) }
        withContext(Dispatchers.Main) { controller?.closeAndJoin(); runtimeScope?.cancel(); owner.registry.currentState = Lifecycle.State.DESTROYED; controller = null }
        network?.closeAndJoin()
        withContext(Dispatchers.Main) { network = null }
        db.close()
    }
    @After fun cleanup() {
        if (::db.isInitialized) closeRuntime()
        if (::peer.isInitialized) runBlocking { peer.close() }
        if (::context.isInitialized) {
            context.deleteDatabase(dbName)
            compose.activity.deleteSharedPreferences("live-wire-$nonce-identity_v1")
            compose.activity.deleteSharedPreferences("live-wire-$nonce-settings")
            compose.activity.deleteSharedPreferences("live-wire-$nonce-attachment-drafts")
        }
    }

    @Test fun l74_recreatedRuntimeReconcilesAcceptedAndUncertainVoiceIntentsWithoutResendCaptureOrHistorySpeech() {
        start(); say(1, "First accepted voice instruction")
        await("The peer must receive the first actual wire prompt") { peer.prompts.size == 1 }
        val first = peer.prompts.single().id
        await("Phone Room must know the first instruction was accepted") {
            runBlocking { repository.pendingPrompts(peer.hostId).any { it.requestId == first && it.state == "accepted" } }
        }
        // End this local turn through controlled reply-drain, then deliberately lose the next ACK.
        compose.runOnIdle { controller!!.onAssistantPlaybackDrained(first) }
        peer.acknowledgePrompts = false
        say(2, "Second instruction with lost acknowledgement")
        await("The second actual wire prompt must reach the peer before shutdown") { peer.prompts.size == 2 }
        val second = peer.prompts.last().id
        peer.summaries[first] = peer.summaries.getValue(first).copy(outcome = "completed")
        peer.summaries[second] = peer.summaries.getValue(second).copy(outcome = "uncertain")
        closeRuntime()
        openRuntime()
        assertTrue("Recreation is not an authorized Start", inputs.size == 1 && view.state == LiveConversationState.Idle)
        assertTrue(runBlocking { repository.pendingPrompts(peer.hostId) }.isEmpty())
        assertEquals("completed", runBlocking { repository.requestState(peer.hostId, first) }?.outcome)
        assertEquals("uncertain", runBlocking { repository.requestState(peer.hostId, second) }?.outcome)
        val historical = "Recovered history is rendered silently after runtime recreation."
        val entry = JSONObject().put("type", "message").put("id", "old-answer").put("parentId", JSONObject.NULL)
            .put("message", JSONObject().put("role", "assistant").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", historical))))
        runBlocking { peer.send(WireMessage.PiFrame(PiFrame(peer.conversationId, "restart-epoch",
            JSONObject().put("entries", JSONArray().put(entry)).put("leafId", "old-answer").toString(), "snapshot", true, 0, first, 0, "acceptance-session"))) }
        await("Actual snapshot must repair the Pi projection") { network!!.piSession.view.value.lines.any { it.text == historical } }
        assertTrue("No recovered history can gain speech ownership", speechOffers.isEmpty())
        assertEquals("No duplicate execution request after authoritative recovery", listOf(first, second), peer.prompts.map { it.id })
        assertEquals(1, inputs.size)
        assertEquals(LiveConversationState.Idle, view.state)
        println("LIVE_L74 boundary=controller_Room_reopen_real_QUIC_controlled_authoritative_peer appProcessKillAndDaemonRestart=not_claimed")
    }

    @Test fun l91_spokenYesCannotApprovePendingPiConfirmationButExplicitOwningDialogTapCan() {
        val interaction = "confirmation-${UUID.randomUUID()}"
        val question = PendingPiUiRequest(peer.conversationId, PiUiDialog.Confirm(PiUiDialogConfirmPayload("This would run a command.", title = "Run the pending command?")), interaction)
        start()
        runBlocking { peer.send(WireMessage.PiUiState(listOf(question))) }
        await("Pending confirmation must arrive through the actual phone transport") { network!!.piDialogs.value.singleOrNull()?.interactionId == interaction }
        compose.onNodeWithText("Pi needs your input").assertIsDisplayed()
        say(1, "yes") // Controlled recognizer boundary, not acoustic evidence of recognizing 'yes'.
        await("Recognized words must remain a normal voice instruction") { peer.prompts.size == 1 }
        assertEquals("yes", (peer.prompts.single().message as WireMessage.Prompt).text)
        assertTrue("Voice intent must never become a Pi approval", peer.answers.isEmpty())
        assertEquals(interaction, network!!.piDialogs.value.single().interactionId)
        compose.onNodeWithText("Yes", substring = false).performClick()
        await("Explicit UI answer must cross the real transport") { peer.answers.size == 1 }
        val answer = peer.answers.single()
        assertEquals(question.conversationId, answer.conversationId)
        assertEquals(interaction, answer.interactionId)
        assertEquals(PiUiAnswer.Confirmed(PiUiAnswerConfirmedPayload(true)), answer.answer)
        compose.onNodeWithText("Yes", substring = false).assertIsNotEnabled()
        assertEquals(1, peer.answers.size)
    }

    @Test fun l92_interruptAfterControlledCommandSideEffectRetainsEffectAndExposesUncertaintyWithoutRollbackClaim() {
        val effect = File(compose.activity.cacheDir, "live-controlled-effect-$nonce")
        try {
            peer.onPrompt = { effect.writeText("controlled-command-already-ran") }
            start(); say(1, "Run the controlled command before cancellation")
            await("Controlled remote boundary must commit its side effect before interruption") { effect.isFile && peer.prompts.size == 1 }
            val request = peer.prompts.single().id
            cancellationFault = "Cancellation acknowledgement was lost; the command may already have run."
            compose.runOnIdle { controller!!.interruptAndListen() }
            await("Actual cancellation must target only the original request") { peer.cancellations.contains(request) }
            await("Lost cancellation certainty must become explicit failure") { view.state is LiveConversationState.Failure }
            assertEquals(listOf(request), peer.cancellations)
            assertEquals("controlled-command-already-ran", effect.readText())
            val failure = (view.state as LiveConversationState.Failure).message
            assertTrue(failure.contains("may already have run"))
            assertFalse(failure.contains("rolled back", ignoreCase = true))
            assertTrue("Fail-closed input must actually be stopped at this controlled boundary", inputs.single().stops > 0)
            compose.onNodeWithText(failure).assertIsDisplayed()
            assertTrue(stoppedSpeech.isNotEmpty())
            println("LIVE_L92 sideEffect=controlled_temporary_file cancel=actual_QUIC_ack_loss_fault installedPiAndShellTool=not_claimed")
        } finally { effect.delete() }
    }
}
