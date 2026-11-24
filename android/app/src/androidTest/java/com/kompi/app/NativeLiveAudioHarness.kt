package com.kompi.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.room.Room
import com.kompi.app.audio.*
import com.kompi.app.data.LocalDatabase
import com.kompi.app.data.LocalRepository
import com.kompi.app.live.*
import com.kompi.app.protocol.generated.ChatModelConfiguration
import com.kompi.app.security.SecureStore
import com.kompi.app.stream.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Real recognition, encrypted outbox, Live controller/UI, speech planner/queue and native AudioTrack.
 * The remote agent response is controlled; no recognized words or PCM playback events are mocked. */
internal class NativeLiveAudioHarness(
    val compose: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>,
    private val inputFactory: ((LiveAudioSession?) -> LiveSpeechInput)? = null,
) {
    val context get() = compose.activity.applicationContext
    val submissions = CopyOnWriteArrayList<VoiceSubmission>()
    val cancellations = CopyOnWriteArrayList<String>()
    val cancellationStartedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile var remoteCancellationGate: CompletableDeferred<Unit>? = null
    private val observedPhases = CopyOnWriteArrayList<KokoroPlaybackPhase>()
    val phases: CopyOnWriteArrayList<KokoroPlaybackPhase>
        get() {
            val status = tts.playbackPhaseReceiptStatus
            check(status == KokoroPlaybackReceiptStatus.Available) { "Actual phase-write receipt unavailable: status=$status" }
            return observedPhases
        }
    val audioFailures = CopyOnWriteArrayList<String>()
    val inputs = CopyOnWriteArrayList<LiveSpeechInput>()
    private val sessionOpenAttempts = java.util.concurrent.atomic.AtomicLong()
    private val openedSessions = CopyOnWriteArrayList<LiveAudioSession>()
    private val acquisitionDiagnostics = CopyOnWriteArrayList<NativeAcquisitionDiagnostics>()
    private val databaseName = "real-live-audio-${UUID.randomUUID()}.db"
    private lateinit var database: LocalDatabase
    lateinit var repository: LocalRepository
    lateinit var controller: LiveConversationController
    lateinit var tts: KokoroTtsEngine
    lateinit var speech: AssistantSpeechStream
    private lateinit var queue: ReplySpeechQueue
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val snapshot get() = controller.snapshot.value
    val recordedInput get() = inputs.last() as RecordedNativeLiveInput

    fun mount() {
        compose.runOnUiThread {
            database = Room.databaseBuilder(context, LocalDatabase::class.java, databaseName).build()
            repository = LocalRepository(database, SecureStore(context))
            tts = KokoroTtsEngine(context, playbackPhaseObserver = { observedPhases.add(it) })
            queue = ReplySpeechQueue(scope, tts::enqueueWhenAvailable, tts::finishRequest, tts::stop,
                failed = { audioFailures.add(it) })
            speech = AssistantSpeechStream(queue::offer, queue::cancel,
                onTextUpdate = { id, _, text -> controller.updateAssistantText(id, text) },
                onDiagnostic = { id, signal -> if (signal == SpeechStreamSignal.Finished) queue.complete(id) },
                hasQueuedAudio = queue::hasPendingSpeech)
            controller = LiveConversationController(compose.activity,
                createSpeechInput = { session ->
                    val input = inputFactory?.invoke(session) ?: RecordedNativeLiveInput(context)
                    inputs.add(input) // Preserve the fixture's original input identities/types.
                    val observed = when (input) {
                        is ObservedNativeLiveInput -> input
                        is com.kompi.app.audio.live.LocalLiveSpeechInput -> ObservedNativeLiveInput(input) { }
                        else -> null // Recorded PCM is not an actual AudioRecord acquisition.
                    }
                    observed?.observeAcquisition(session, inputs.size)?.also(acquisitionDiagnostics::add)
                    observed ?: input
                },
                openAudioSession = { onLocalStop ->
                    // Observe the REAL opener boundary after the actor accepted Start and published
                    // Preparing. Atomic publication distinguishes this attempt from an old Failure,
                    // even when a new startup fails with the same text and Preparing is conflated.
                    sessionOpenAttempts.incrementAndGet()
                    if (inputFactory != null) LiveAudioSession.open(context, onLocalStop).also(openedSessions::add) else null
                },
                setOutputMuted = speech::setOutputMuted,
                sendTurn = { request, text ->
                    repository.enqueue("native-test-host", request, text, "followUp", emptyList(), ChatModelConfiguration("high", "fixture-agent", "fixture"))
                    submissions.add(VoiceSubmission(request, text)); controller.onTurnCommitted(request)
                },
                stopLocalSpeech = { if (it == null) speech.invalidate() else speech.cancel(it) },
                cancelRemote = {
                    cancellationStartedAt[it] = android.os.SystemClock.elapsedRealtimeNanos()
                    cancellations.add(it)
                    remoteCancellationGate?.await()
                },
            )
            controller.onContextChanged("native-test-host:conversation")
            scope.launch { tts.playbackPhase.collect { phase -> controller.onAssistantPlayback(phase) } }
            scope.launch {
                val completed = mutableSetOf<String>()
                tts.requestStatuses.collect { statuses -> statuses.values.forEach { status ->
                    if (status.state == KokoroSpeechStatus.Completed && completed.add(status.requestId)) controller.onAssistantPlaybackDrained(status.requestId)
                } }
            }
        }
        compose.setContent {
            val snapshot by controller.snapshot.collectAsState()
            KompiTheme(AppTheme.System) { LiveTalkScreen(snapshot, controller::endLive, controller::startLive, controller::interruptAndListen, controller::retryPendingTurn,
                onToggleMicrophoneMute = controller::toggleMicrophoneMute,
                onToggleOutputMute = controller::toggleOutputMute) }
        }
    }
    fun main(action: () -> Unit) = compose.runOnIdle(action)
    fun await(description: String, allowFailure: Boolean = false, condition: () -> Boolean) {
        try { compose.waitUntil(timeoutMillis = 90_000, condition = condition) }
        catch (error: Throwable) { throw AssertionError("$description; live=$snapshot, nativeStatuses=${tts.requestStatuses.value}", error) }
        check(allowFailure || snapshot.state !is LiveConversationState.Failure) { "Real Live failed: ${snapshot.state}" }
    }
    fun start() {
        val previous = inputs.size
        val previousAttempt = sessionOpenAttempts.get()
        main { controller.startLive() }
        await("This explicit Start must reach the actual audio opener, then construct capture or fail explicitly", allowFailure = true) {
            sessionOpenAttempts.get() > previousAttempt &&
                (inputs.size > previous || snapshot.state is LiveConversationState.Failure)
        }
    }
    fun play(id: String, expectedSubmission: Int) {
        runBlocking(Dispatchers.IO) { recordedInput.playFixture(id) }
        await("Real ASR must submit $id") { submissions.size >= expectedSubmission || snapshot.state is LiveConversationState.Failure }
        val fixture = LiveAudioCorpus.fixtures.single { it.id == id }
        val actual = submissions[expectedSubmission - 1].text
        assertWords(fixture, actual)
    }
    fun assertWords(fixture: SpokenFixture, actual: String) {
        check(LiveAudioCorpus.words(actual).containsAll(fixture.requiredWords)) {
            "Real recognized text for ${fixture.id} missed required meaning: $actual; required=${fixture.requiredWords}"
        }
        println("REAL_ASR fixture=${fixture.id} chars=${actual.length} requiredWordsPreserved=true")
    }
    fun reply(text: String) {
        runBlocking { withTimeout(90_000) { tts.initialize().getOrThrow() } }
        val id = submissions.last().requestId
        main { check(speech.accept(id, "native-conversation", 0, text)); speech.finish(id, "native-conversation", text) }
    }
    fun drained(id: String) = await("Actual AudioTrack must drain for $id") {
        tts.requestStatuses.value[id]?.state in setOf(KokoroSpeechStatus.Completed, KokoroSpeechStatus.Failed, KokoroSpeechStatus.Cancelled)
    }.also { check(tts.requestStatuses.value[id]?.state == KokoroSpeechStatus.Completed) { "Native output failed: ${tts.requestStatuses.value[id]}" } }

    /** Every exact owned producer, including retired captures. Never forget old IDs on close:
     * otherwise a leaked old recorder could hide behind a newly selected capture or dictation. */
    fun ownedRecordingSessionIds(): Set<Int> = acquisitionDiagnostics.flatMap { it.ownedRecordingSessionIds() }.toSet()

    /** Actual current AudioRecord-read evidence, not a host writer acknowledgment or APM input. */
    fun acquisitionCheckpoint(): NativeAcquisitionSnapshot = acquisitionDiagnostics.lastOrNull()?.checkpoint()
        ?: throw AssertionError("Direct acquisition checkpoint unavailable: nativeCapture=0")

    fun acquisitionDeltaSince(before: NativeAcquisitionSnapshot): NativeAcquisitionDelta =
        acquisitionCheckpoint().deltaSince(before)

    /** Single immutable native-stats publication; no JNI, native/model lock or stale owner. */
    fun dumpAudioDiagnostics() {
        openedSessions.forEach(::printSessionRouteFailure)
        printNativeAcquisitionDiagnostics(acquisitionDiagnostics)
    }

    fun close() {
        try {
            runBlocking {
                withContext(Dispatchers.Main) { controller.closeAndJoin() }
                inputs.forEach { it.closeAndJoin() }
                tts.closeAndJoin(); scope.cancel()
            }
        } finally {
            // Retained captures survive input/.active cleanup. No reopen, hardware read, join or
            // extra privacy-path work: this printer runs AFTER the existing teardown attempts.
            printNativeAcquisitionDiagnostics(acquisitionDiagnostics)
        }
        database.close(); context.deleteDatabase(databaseName)
    }
}
