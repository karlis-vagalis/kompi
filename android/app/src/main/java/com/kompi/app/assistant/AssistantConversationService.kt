package com.kompi.app.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.widget.RemoteViews
import android.service.voice.VoiceInteractionService
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.kompi.app.R
import com.kompi.app.audio.KokoroSpeechStatus
import com.kompi.app.audio.KokoroTtsEngine
import com.kompi.app.audio.KokoroTtsState
import com.kompi.app.audio.KokoroVoiceId
import com.kompi.app.audio.MicCaptureCoordinator
import com.kompi.app.audio.WhistleModelInstaller
import com.kompi.app.audio.live.LocalLiveSpeechInput
import com.kompi.app.audio.ParakeetModelInstaller
import com.kompi.app.audio.SpeechInputEngine
import com.kompi.app.audio.SpeechRecognitionModel
import com.kompi.app.audio.whistle.WhistleVocabulary
import com.kompi.app.data.LocalDatabase
import com.kompi.app.data.LocalRepository
import com.kompi.app.live.LiveConversationController
import com.kompi.app.live.LiveConversationState
import com.kompi.app.protocol.generated.PiUiAnswer
import com.kompi.app.security.SecureStore
import com.kompi.app.stream.AssistantSpeechStream
import com.kompi.app.stream.ActivitySpeech.Gate as ActivitySpeechGate
import com.kompi.app.transport.Delivery
import com.kompi.app.transport.IrohTransport
import com.kompi.app.transport.PiDialogRequest
import com.kompi.app.transport.ResultState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Owns one user-invoked, host-pinned Live session, independently of Activity/screen lifetime. */
class AssistantConversationService : Service(), LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runtimeJob: kotlinx.coroutines.Job? = null
    private var sessionToken: AssistantCoordinator.SessionToken? = null
    private var serviceDestroyed = false
    private var cleanupFinished = false
    private var shutdownStartedNanos = 0L
    private var controller: LiveConversationController? = null
    private var transport: IrohTransport? = null
    private var tts: KokoroTtsEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopping = false
    private val retirementOwner = AssistantServiceRetirementBarrier.Owner()

    override fun onCreate() {
        super.onCreate()
        registry.currentState = Lifecycle.State.CREATED
        current = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START && !AssistantServiceRetirementBarrier.admitsStart()) {
            AssistantCoordinator.failure.value = STOPPING_MESSAGE
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) { shutdown(null, explicitlyEnded = true); return START_NOT_STICKY }
        if (intent?.action != ACTION_START) { shutdown(null); return START_NOT_STICKY }
        if (stopping) {
            AssistantCoordinator.failure.value = STOPPING_MESSAGE
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (runtimeJob != null) return START_NOT_STICKY
        val problem = prerequisite(this)
        if (problem != null) { shutdown(problem); return START_NOT_STICKY }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Kompi assistant", NotificationManager.IMPORTANCE_LOW))
            check(manager.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE) { "Enable Kompi assistant notifications in Android settings." }
            val stop = PendingIntent.getService(this, 1, Intent(this, AssistantConversationService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val open = PendingIntent.getActivity(this, 2, activityIntent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val controls = RemoteViews(packageName, R.layout.assistant_notification).apply {
                setOnClickPendingIntent(R.id.assistant_notification_open, open)
                setOnClickPendingIntent(R.id.assistant_notification_stop, stop)
            }
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Kompi voice assistant")
                .setContentText("Running in the background")
                .setContentIntent(open)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true).setOnlyAlertOnce(true)
                .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
                .setStyle(Notification.DecoratedCustomViewStyle())
                .setCustomContentView(controls)
                .setCustomBigContentView(controls.clone())
                .build()
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTIFICATION, notification)
            registry.currentState = Lifecycle.State.STARTED
            val claim = AssistantCoordinator.claim()
            sessionToken = claim.token
            runtimeJob = scope.launch {
                try { runConversation(claim) }
                catch (timeout: kotlinx.coroutines.TimeoutCancellationException) { shutdown("The selected computer did not become ready. Open Kompi and check its connection.") }
                catch (cancelled: CancellationException) { if (!stopping) shutdown("Assistant startup was cancelled. Invoke it again to retry.") }
                catch (error: Exception) { shutdown(error.message ?: "Could not start the Kompi assistant.") }
            }
        } catch (error: Exception) { shutdown(error.message ?: "Android did not allow the assistant microphone service.") }
        return START_NOT_STICKY // Never restart microphone capture after process death or task removal.
    }

    private suspend fun runConversation(claim: AssistantCoordinator.SessionClaim): Unit = coroutineScope {
        val token = claim.token
        val startupNanos = System.nanoTime()
        fun startupPhase(phase: String) {
            android.util.Log.i("KompiAssistantStartup", "phase=$phase elapsed_ms=${(System.nanoTime() - startupNanos) / 1_000_000}")
        }
        startupPhase("handoff_begin")
        withContext(NonCancellable) { claim.releaseUi() } // Finish the old owner's teardown even if Stop arrives during handoff.
        check(withContext(Dispatchers.IO) { MicCaptureCoordinator.awaitIdle(2_000) }) { "Previous microphone capture is still closing. Try again." }
        startupPhase("handoff_finished")
        val store = SecureStore(this@AssistantConversationService)
        val hostId = store.selectedMachineId() ?: error("Pair and select a computer in Kompi before invoking the assistant.")
        val host = store.trustedMachines().first { it.machineId == hostId }
        check(host.pairing.confirmed) { "Finish pairing this computer before invoking the assistant." }
        val settings = getSharedPreferences("settings", MODE_PRIVATE)
        val speechEngine = SpeechInputEngine.read(settings)
        check(speechEngine.supportedOnDevice()) { "${speechEngine.label} is not supported on this device." }
        val model: SpeechRecognitionModel = withContext(Dispatchers.IO) {
            when (speechEngine) {
                SpeechInputEngine.Whistle -> WhistleModelInstaller(File(filesDir, "models/whistle")).installed()
                    ?.takeIf { WhistleModelInstaller.isVerifiedModel(it.file) }?.let { SpeechRecognitionModel.Whistle(it) }
                SpeechInputEngine.Parakeet -> ParakeetModelInstaller(File(filesDir, "models/parakeet")).installed()
                    ?.let { SpeechRecognitionModel.Parakeet(it) }
            }
        } ?: error("Download ${speechEngine.label} in Kompi's Voice settings before invoking the assistant.")
        check(settings.getBoolean("ttsEnabled", true)) { "Enable spoken replies in Kompi before invoking the assistant." }
        val vocabulary = WhistleVocabulary.parse(settings.getString("whistleVocabulary", "").orEmpty()).getOrThrow()
        val language = settings.getString("whistleLanguage", "auto")?.takeUnless { it == "auto" }
        AssistantCoordinator.publish(token) { AssistantCoordinator.hostName.value = host.displayName }
        val engine = KokoroTtsEngine(applicationContext).also { tts = it }
        engine.restoreVoicePreference(KokoroVoiceId.fromStored(settings.getString("kokoroVoiceId", null)))
        // Reply synthesis is independent of connection/capture. Keep initialization a child of
        // this runtime so Stop joins it before closing native resources; failure still ends Live.
        val voiceReady = async(Dispatchers.IO) {
            startupPhase("voice_initializing")
            engine.initialize().getOrThrow()
            startupPhase("voice_ready")
        }
        val db = LocalDatabase.open(applicationContext)
        val network = IrohTransport(applicationContext, store, LocalRepository(db, store)).also { transport = it }
        val activitySpeech = com.kompi.app.stream.ActivitySpeech(
            // Thinking filler is optional; do not admit it into an uninitialized engine.
            speak = { id, phrase ->
                engine.state.value is KokoroTtsState.Ready &&
                    engine.enqueue(id, 0, phrase).also { if (it) engine.finishRequest(id) }
            },
            stop = { id -> engine.stop(id) },
        )
        val replySpeech = com.kompi.app.stream.ReplySpeechQueue(
            scope = this,
            enqueue = { id, sequence, phrase ->
                voiceReady.await()
                engine.enqueueWhenAvailable(id, sequence, phrase)
            },
            finish = engine::finishRequest,
            stop = engine::stop,
            failed = { id ->
                android.util.Log.w("KompiReplySpeech", "phase=enqueue_failed request=$id")
                controller?.updateActivity(id, "Spoken reply could not finish. The full text remains available.")
            },
        )
        val stream = AssistantSpeechStream(
            speak = replySpeech::offer,
            stop = { id -> if (id == null) activitySpeech.invalidate() else activitySpeech.retire(id); replySpeech.cancel(id) },
            onTextUpdate = { id, _, text -> controller?.updateAssistantText(id, text) },
            onDiagnostic = { id, signal ->
                android.util.Log.i("KompiSpeechStream", "request=$id signal=${signal.name}")
                if (signal == com.kompi.app.stream.SpeechStreamSignal.UnspeakableToken) {
                    android.widget.Toast.makeText(applicationContext,
                        "This reply contains an item too long to read aloud. The full text is still available.",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            },
            speechLocale = { engine.selectedVoice.value?.localeTag?.let(java.util.Locale::forLanguageTag) ?: java.util.Locale.getDefault() },
            hasQueuedAudio = { id -> replySpeech.hasPendingSpeech(id) || engine.hasPendingSpeech(id) },
        )
        fun activitySpeechGate(): ActivitySpeechGate = when {
            !settings.getBoolean("thinkingSpeechEnabled", true) || stream.isOutputMuted() -> ActivitySpeechGate.Muted
            engine.state.value !is KokoroTtsState.Ready -> ActivitySpeechGate.VoiceNotReady
            replySpeech.hasPendingSpeech() -> ActivitySpeechGate.ReplyAudioPending
            engine.requestStatuses.value.keys.any { id -> !id.startsWith("activity-") && engine.hasPendingSpeech(id) } -> ActivitySpeechGate.OtherAudioPending
            else -> ActivitySpeechGate.Open
        }
        scope.launch { while (isActive) {
            delay(100)
            stream.drainBufferedSpeech()
        } }
        val requests = linkedSetOf<String>()
        val live = LiveConversationController(
            lifecycleOwner = this@AssistantConversationService,
            createSpeechInput = { audioSession -> LocalLiveSpeechInput(applicationContext, this@AssistantConversationService, model, language, vocabulary, audioSession = audioSession) },
            openAudioSession = { onLocalStop -> com.kompi.app.audio.LiveAudioSession.open(applicationContext, onLocalStop) },
            setOutputMuted = stream::setOutputMuted,
            sendTurn = { id, text ->
                check(store.selectedMachineId() == hostId) { "Selected computer changed. Invoke the assistant again." }
                activitySpeech.begin(id)
                requests.add(id)
                while (requests.size > 64) requests.remove(requests.first())
                check(network.sendPromptWithId(text, Delivery.FollowUp, id) == id) { "Voice prompt was not durably queued." }
                controller?.onTurnCommitted(id)
            },
            stopLocalSpeech = { id -> if (id == null) stream.invalidate() else stream.cancel(id) },
            cancelRemote = { id -> activitySpeech.retire(id); network.cancel(id) },
        ).also { controller = it }
        live.onContextChanged(hostId)
        scope.launch {
            live.snapshot.drop(1).collect { snapshot ->
                AssistantCoordinator.publish(token) { AssistantCoordinator.snapshot.value = snapshot }
                when (val state = snapshot.state) {
                    is LiveConversationState.Failure -> shutdown(state.message)
                    LiveConversationState.Paused -> shutdown("Voice session paused. Invoke the assistant again to resume.")
                    else -> Unit
                }
            }
        }
        scope.launch { engine.playbackPhase.collect { phase -> live.onAssistantPlayback(phase, activitySpeech.playbackOwner(phase)) } }
        scope.launch { engine.state.collect { if (it is KokoroTtsState.Failed) shutdown("Kokoro playback failed. Open Voice settings before trying again.") } }
        scope.launch {
            engine.requestStatuses.collect { statuses ->
                statuses.values.filter { it.requestId in requests && it.state in setOf(KokoroSpeechStatus.Completed, KokoroSpeechStatus.Cancelled, KokoroSpeechStatus.Failed) }
                    .forEach { if (it.state == KokoroSpeechStatus.Completed) live.onAssistantPlaybackDrained(it.requestId) }
                statuses.values.filter { it.requestId.startsWith("activity-") && it.state in setOf(KokoroSpeechStatus.Completed, KokoroSpeechStatus.Cancelled, KokoroSpeechStatus.Failed) }
                    .forEach { activitySpeech.onPlaybackDrained(it.requestId) }
            }
        }
        scope.launch { network.piDialogs.collect { requests -> AssistantCoordinator.publish(token) { AssistantCoordinator.dialogs.value = requests } } }
        scope.launch { network.androidToolRequests.collect { requests -> AssistantCoordinator.publish(token) { AssistantCoordinator.androidTools.value = requests } } }
        scope.launch { network.piDialogError.collect { error -> AssistantCoordinator.publish(token) { AssistantCoordinator.dialogError.value = error } } }
        scope.launch {
            network.events.collect { event ->
                when (event) {
                    is IrohTransport.Event.ResponseDelta -> if (event.id in requests) {
                        activitySpeech.onReplyText(event.id)
                        stream.accept(event.id, event.conversationId, event.sequence.toLong(), event.text)
                    }
                    is IrohTransport.Event.Progress -> if (event.id in requests) {
                        if (event.activity.startsWith("Thinking: ")) live.updateThinkingTitle(event.id, event.activity.removePrefix("Thinking: "))
                        else if (event.activity == "Thinking" || event.activity == "Using tools" || event.activity.startsWith("Tool: ")) live.updateActivity(event.id, event.activity)
                        com.kompi.app.diagnostics.SpeechAudit.record(event.id, stage = "activity_received",
                            detail = "kind=${when { event.activity == "Thinking" -> "thinking_start"; event.activity.startsWith("Thinking: ") -> "thinking_heading"; event.activity == "Using tools" -> "tools_cue"; else -> "tool" }}")
                        activitySpeech.offer(event.id, event.activity, settings.getString("thinkingSpeechPrefix", "One second, I'm").orEmpty(), allowTools = true,
                            gate = activitySpeechGate())
                    }
                    is IrohTransport.Event.Reply -> if (event.id in requests) {
                        if (event.state == ResultState.Completed) activitySpeech.complete(event.id) else activitySpeech.retire(event.id)
                        if (event.state == ResultState.Completed && event.conversationId != null) stream.finish(event.id, event.conversationId, event.text)
                        else stream.cancel(event.id)
                        if (event.state == ResultState.Completed) replySpeech.complete(event.id)
                        else { engine.finishRequest(event.id); live.onAssistantPlaybackDrained(event.id) }
                    }
                    is IrohTransport.Event.Failure -> if (event.id in requests) {
                        activitySpeech.retire(event.id)
                        stream.cancel(event.id)
                        engine.finishRequest(event.id)
                        live.onAssistantPlaybackDrained(event.id)
                    }
                    else -> Unit // Recovered/history replies are never spoken as a new assistant turn.
                }
            }
        }
        startupPhase("connecting")
        network.reconnect(host.pairing)
        withTimeout(20_000) { network.state.first { it == "Live" } }
        check(network.phoneConversationsSupported.value) { "This computer does not support Live conversations." }
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kompi:assistant").also { it.acquire() }
        scope.launch { network.state.collect { state ->
            AssistantCoordinator.publish(token) { AssistantCoordinator.connectionState.value = state }
            if (state != "Live") { activitySpeech.invalidate(); stream.invalidate(); live.onConnectionLost() }
        } }
        startupPhase("capture_requested")
        live.startLive()
        awaitCancellation()
    }

    private fun shutdown(error: String?, explicitlyEnded: Boolean = false) {
        if (stopping) return
        // Install the process-wide quarantine before the foreground/logical owner is released.
        // A newer Android Service instance must not inherit or clear this owner's retirement.
        AssistantServiceRetirementBarrier.begin(retirementOwner)
        stopping = true
        shutdownStartedNanos = System.nanoTime()
        scheduleAssistantServiceCleanup(
            scope = cleanupScope,
            fenceOwners = listOf(
                {
                    if (explicitlyEnded) controller?.endLive()
                    else controller?.stopLocally(com.kompi.app.live.LiveLocalStopReason.Backgrounded)
                },
                { tts?.stop() },
                { wakeLock?.let { if (it.isHeld) it.release() } },
                { registry.currentState = Lifecycle.State.DESTROYED },
                { runtimeJob?.cancel(); scope.cancel() }, // Fence every old observer before a replacement session can claim state.
            ),
            releaseForeground = {
                // Privacy stop is synchronous; native initialization/decoder cleanup may be
                // uninterruptible. Never leave a foreground service/notification alive while
                // waiting for that separate owner to finish releasing resources.
                try { stopForeground(STOP_FOREGROUND_REMOVE) } finally { stopSelf() }
                logShutdownPhase("foreground_release")
            },
            releaseLogicalSession = { stopFailure ->
                sessionToken?.let { token ->
                    val reportedError = error ?: stopFailure?.let {
                        "Voice resources encountered a stop error; capture is fenced. Wait for audio cleanup before invoking again."
                    }
                    AssistantCoordinator.released(token, reportedError)
                }
                logShutdownPhase("logical_release")
            },
            onStopFailure = {
                logShutdownPhase("stop_step_failed")
                sessionToken?.let { token -> AssistantCoordinator.publish(token) {
                    AssistantCoordinator.failure.value = "Voice shutdown encountered a local resource error; capture is fenced."
                } }
            },
        ) { stopFailure ->
            withContext(NonCancellable) {
                var cleanupSucceeded = true
                if (stopFailure != null) logShutdownPhase("cleanup_after_stop_error")
                suspend fun attempt(stage: String, action: suspend () -> Unit) {
                    try { shutdownStage(stage, action) }
                    catch (_: Throwable) {
                        cleanupSucceeded = false
                        android.util.Log.w("KompiAssistantShutdown", "phase=${stage}_failed")
                    }
                }
                try {
                    attempt("runtime_join") { runtimeJob?.join() }
                    attempt("controller_close") { controller?.closeAndJoin() }
                    attempt("tts_close") { tts?.closeAndJoin() }
                    attempt("transport_close") { transport?.closeAndJoin() }
                } finally {
                    runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
                    cleanupFinished = cleanupSucceeded
                    if (cleanupFinished) AssistantServiceRetirementBarrier.complete(retirementOwner, cleanupSucceeded = true)
                    if (cleanupFinished && serviceDestroyed && current === this@AssistantConversationService) current = null
                    logShutdownPhase(if (cleanupFinished) "cleanup_finished" else "cleanup_incomplete")
                    cleanupScope.cancel()
                }
            }
        }
    }

    private suspend fun shutdownStage(name: String, action: suspend () -> Unit) {
        logShutdownPhase("${name}_begin")
        try { action() }
        finally { logShutdownPhase("${name}_end") }
    }

    private fun logShutdownPhase(phase: String) {
        val elapsed = (System.nanoTime() - shutdownStartedNanos) / 1_000_000
        android.util.Log.i("KompiAssistantShutdown", "phase=$phase elapsed_ms=$elapsed")
    }

    override fun onTaskRemoved(rootIntent: Intent?) { shutdown(null); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() {
        serviceDestroyed = true
        if (cleanupFinished && current === this) current = null
        shutdown(null)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "kompi-assistant"
        private const val NOTIFICATION = 20
        private const val ACTION_START = "com.kompi.app.assistant.START"
        private const val ACTION_STOP = "com.kompi.app.assistant.STOP"
        private const val STOPPING_MESSAGE = "The previous voice session is still closing. Its microphone is fenced; Kompi will not start another assistant session until its resources release. Open Kompi again after cleanup completes."
        private var current: AssistantConversationService? = null

        fun activityIntent(context: Context) = Intent(context, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** Navigation revokes voice ownership, not the remote request. Stop remains explicit. */
        internal fun leaveForApp(context: Context, route: String = "chat") {
            require(route in setOf("chat", "settings"))
            current?.shutdown(null, explicitlyEnded = false)
            context.startActivity(Intent(context, com.kompi.app.MainActivity::class.java)
                .putExtra("com.kompi.app.shortcut", route)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            if (context is android.app.Activity && context !is com.kompi.app.MainActivity) context.finish()
        }

        fun prerequisite(context: Context): String? {
            if (!context.getSharedPreferences("settings", MODE_PRIVATE).getBoolean("assistantEnabled", false)) return "Enable Earbud assistant in Kompi settings first. Locked use grants access to your selected coding agent."
            if (!VoiceInteractionService.isActiveService(context, ComponentName(context, KompiVoiceInteractionService::class.java))) return "Select Kompi as Android's default digital assistant first."
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return "Grant microphone permission in Kompi's Earbud assistant settings first."
            if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return "Grant Nearby devices permission in Kompi's Earbud assistant settings first."
            if (!context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) return "Allow Kompi notifications so the voice session has a visible Stop control."
            return null
        }

        /** Called only from the system-bound VoiceInteractionSession, never from an exported Activity intent. */
        internal fun invoke(context: Context) {
            if (!AssistantServiceRetirementBarrier.admitsStart()) {
                AssistantCoordinator.failure.value = STOPPING_MESSAGE
                return
            }
            val problem = prerequisite(context)
            if (problem != null) { current?.shutdown(problem); AssistantCoordinator.failure.value = problem; return }
            if (current?.stopping == true) {
                AssistantCoordinator.failure.value = STOPPING_MESSAGE
                return
            }
            if (AssistantCoordinator.mode.value.assistant) return
            try { ContextCompat.startForegroundService(context, Intent(context, AssistantConversationService::class.java).setAction(ACTION_START)) }
            catch (error: Exception) { AssistantCoordinator.failure.value = error.message ?: "Android did not allow starting the voice assistant." }
        }

        fun stop(context: Context) { current?.shutdown(null, explicitlyEnded = true) }
        fun startLive() { current?.controller?.startLive() }
        fun toggleMicrophoneMute() { current?.controller?.toggleMicrophoneMute() }
        fun toggleOutputMute() { current?.controller?.toggleOutputMute() }
        fun interrupt() { current?.controller?.interruptAndListen() }
        fun retry() { current?.controller?.retryPendingTurn() }
        suspend fun executeForegroundAndroidTool(request: com.kompi.app.transport.AndroidToolRequest, activity: android.app.Activity) {
            val network = current?.transport ?: error("Assistant session has ended")
            network.executeForegroundAndroidTool(request, activity, fromAssistant = true)
        }
        suspend fun answer(request: PiDialogRequest, answer: PiUiAnswer) {
            val network = current?.transport ?: error("Assistant session has ended")
            network.answerPiDialog(request, answer)
        }
    }
}
