package com.kompi.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.kompi.app.audio.AudioWaveform
import com.kompi.app.audio.SpeechUtteranceId
import com.kompi.app.data.LocalDatabase
import com.kompi.app.data.LocalRepository
import com.kompi.app.live.*
import com.kompi.app.protocol.generated.ChatModelConfiguration
import com.kompi.app.security.SecureStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.UUID

/** Script only hardware/model boundaries; the production controller, Room/Keystore and UI stay real. */
internal class ScriptedLiveInput : LiveSpeechInput {
    lateinit var listener: LiveSpeechInput.Listener
    var starts = 0
    @Volatile var stops = 0
    @Volatile var recording = false
        private set
    var joins = 0
    var closes = 0
    var stopFailure: Throwable? = null
    override fun start(listener: LiveSpeechInput.Listener) { this.listener = listener; starts++; recording = true }
    @Synchronized private fun stopRecording() { if (recording) { recording = false; stops++ } }
    override fun requestStopAndFence() { stopRecording() }
    override fun stop(finalizeCurrentUtterance: Boolean) { stopRecording() }
    override suspend fun stopMicrophoneAndFence() { stopRecording(); stopFailure?.let { throw it } }
    override suspend fun stopAndJoin(finalizeCurrentUtterance: Boolean) { stopRecording(); joins++ }
    override suspend fun closeAndJoin() { stopAndJoin(false); close() }
    override fun close() { closes++ }
    fun ready() = listener.onMeter(0f, CapturePhase.Listening, AudioWaveform.Silence)
    fun speechCandidate(sequence: Long) = listener.onSpeechStarted(SpeechUtteranceId(sequence))
    fun bargeIn(sequence: Long) = listener.onBargeInConfirmed(SpeechUtteranceId(sequence))
    fun onset(sequence: Long) { speechCandidate(sequence); bargeIn(sequence) }
    fun partial(sequence: Long, committed: String, pending: String, language: String? = null) =
        listener.onPartial(SpeechUtteranceId(sequence), committed, pending, language)
    fun final(sequence: Long, text: String, language: String? = null) =
        listener.onFinal(SpeechUtteranceId(sequence), text, language)
}

internal class LiveTestOwner : LifecycleOwner {
    val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
}

internal data class VoiceSubmission(val requestId: String, val text: String)

internal class LiveBehaviorHarness(
    val compose: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>,
) {
    val inputs = mutableListOf<ScriptedLiveInput>()
    val attempts = mutableListOf<VoiceSubmission>()
    val stoppedAudio = mutableListOf<String?>()
    val cancelled = mutableListOf<String>()
    val gates = mutableListOf<CompletableDeferred<Unit>>()
    var factoryFailure: Throwable? = null
    var sendFailure: Throwable? = null
    var cancellationFailure: Throwable? = null
    var sendGate: CompletableDeferred<Unit>? = null
    var submissionHost = "host-a"
    var cancelGate: CompletableDeferred<Unit>? = null
    var ended = 0
    var stopObserver: ((String?) -> Unit)? = null
    var outputMuteObserver: ((Boolean) -> Unit)? = null
    lateinit var owner: LiveTestOwner
    lateinit var controller: LiveConversationController
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "live-behavior-${UUID.randomUUID()}.db"
    private val database = Room.databaseBuilder(context, LocalDatabase::class.java, databaseName).build()
    val repository = LocalRepository(database, SecureStore(context))
    private val configuration = ChatModelConfiguration("high", "controlled-model", "controlled-provider")
    val view get() = controller.snapshot.value
    val input get() = inputs.last()

    fun mount() {
        compose.runOnUiThread {
            owner = LiveTestOwner().also { it.registry.currentState = Lifecycle.State.RESUMED }
            controller = LiveConversationController(
                lifecycleOwner = owner,
                createSpeechInput = {
                    factoryFailure?.let { throw it }
                    ScriptedLiveInput().also(inputs::add)
                },
                sendTurn = { request, text ->
                    attempts.add(VoiceSubmission(request, text))
                    sendFailure?.let { throw it }
                    repository.enqueue(submissionHost, request, text, "followUp", emptyList(), configuration)
                    controller.onTurnCommitted(request)
                    sendGate?.await()
                },
                stopLocalSpeech = { stoppedAudio.add(it); stopObserver?.invoke(it) },
                setOutputMuted = { outputMuteObserver?.invoke(it) },
                cancelRemote = { request ->
                    cancelled.add(request)
                    cancelGate?.await()
                    cancellationFailure?.let { throw it }
                },
                onLiveEnded = { ended++ },
            )
            controller.onContextChanged("host-a:conversation-a")
        }
        compose.setContent {
            val snapshot by controller.snapshot.collectAsState()
            KompiTheme(AppTheme.System) {
                LiveTalkScreen(snapshot, controller::endLive, controller::startLive,
                    controller::interruptAndListen, controller::retryPendingTurn,
                    onToggleMicrophoneMute = controller::toggleMicrophoneMute,
                    onToggleOutputMute = controller::toggleOutputMute)
            }
        }
    }
    fun main(action: () -> Unit) = compose.runOnIdle(action)
    fun await(description: String, condition: () -> Boolean) {
        try { compose.waitUntil(timeoutMillis = 5_000, condition = condition) }
        catch (error: Throwable) { throw AssertionError("$description; snapshot=$view, submissions=$attempts, cancellations=$cancelled", error) }
    }
    fun gate(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also(gates::add)
    fun start() {
        val previousInputs = inputs.size
        main { controller.startLive() }
        await("capture should start") { inputs.size == previousInputs + 1 }
        main { input.ready() }
        await("first measured input should show Listening") { view.state == LiveConversationState.Listening }
    }
    fun utterance(sequence: Long, text: String) {
        main { input.onset(sequence); input.partial(sequence, text, ""); input.final(sequence, text) }
        await("utterance should reach durable submission adapter") { attempts.size >= sequence.toInt() }
    }
    fun playing(request: String, text: String = "Public answer") {
        main {
            controller.updateAssistantText(request, text)
            controller.onAssistantPlaying(request, text, 0.35f, AudioWaveform.fromPcm(FloatArray(320) { 0.25f }, 16_000))
        }
        await("owned PCM should show Speaking") { view.state == LiveConversationState.Speaking(request) }
    }
    fun close() {
        gates.forEach { it.complete(Unit) }
        if (::controller.isInitialized) runBlocking {
            withContext(Dispatchers.Main) {
                controller.closeAndJoin()
                owner.registry.currentState = Lifecycle.State.DESTROYED
            }
        }
        database.close()
        context.deleteDatabase(databaseName)
    }
}
