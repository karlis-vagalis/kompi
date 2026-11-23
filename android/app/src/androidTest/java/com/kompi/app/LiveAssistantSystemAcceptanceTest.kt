package com.kompi.app

import android.Manifest
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.service.voice.VoiceInteractionService
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.kompi.app.assistant.*
import com.kompi.app.audio.MicCaptureCoordinator
import com.kompi.app.audio.SpeechRecognitionModel
import com.kompi.app.audio.live.LocalLiveSpeechInput
import com.kompi.app.data.LocalDatabase
import com.kompi.app.diagnostics.SpeechAudit
import com.kompi.app.live.LiveConversationState
import com.kompi.app.live.LiveSpeechInput
import com.kompi.app.security.SecureStore
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Real Android role/session/FGS/keyguard/notification + selected native models and AudioRecord.
 * The paired computer is an authenticated ephemeral QUIC fixture, NOT an installed daemon/Pi.
 * Refuses valuable devices/state. Run only explicitly selected methods on the runner-owned AVD:
 * -e kompiDisposableEmulator true, with AUDIO/NEARBY_DEVICES/NOTIFICATIONS already granted.
 * Role/consent changes here are explicit fixture setup, never a bypass of production prerequisites. */
class LiveAssistantSystemAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = compose.activity.applicationContext
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val audioManager get() = context.getSystemService(AudioManager::class.java)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private lateinit var prefs: SharedPreferences
    private lateinit var savedSettings: Map<String, *>
    private var oldRoleHolders = emptyList<String>()
    private var oldVoice: String? = null
    private var oldAssistant: String? = null
    private var fixtureAuthorized = false
    private var pinSet = false
    private var peer: LiveLoopbackPeer? = null
    private var audio: EmulatorAudioBridge? = null
    private var oldInput: LocalLiveSpeechInput? = null
    private var metadataProbe: LiveAssistantMetadataOnlyProbe? = null
    private val uiToken = Any()
    private val voiceComponent = ComponentName("com.kompi.app", "com.kompi.app.assistant.KompiVoiceInteractionService")
    private val testPin = "246813"

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
    private fun setting(name: String) = shell("settings get secure $name").takeUnless { it.isBlank() || it == "null" }
    private fun restoreSetting(name: String, value: String?) {
        if (value == null) shell("settings delete secure $name")
        else { require(value.matches(Regex("[A-Za-z0-9_./:]+"))); shell("settings put secure $name $value") }
    }
    private fun await(message: String, timeout: Long = 30_000, condition: () -> Boolean) {
        try { compose.waitUntil(timeout, condition) }
        catch (error: Throwable) { throw AssertionError("$message; mode=${AssistantCoordinator.mode.value}; state=${AssistantCoordinator.snapshot.value.state}; failure=${AssistantCoordinator.failure.value}; recordingCount=${audioManager.activeRecordingConfigurations.size}", error) }
    }
    private fun noResources() {
        instrumentation.waitForIdleSync()
        await("Assistant teardown must release the real recorder and visible FGS") {
            !AssistantCoordinator.mode.value.assistant && audioManager.activeRecordingConfigurations.isEmpty() &&
                notifications.activeNotifications.none { it.notification.channelId == "kompi-assistant" }
        }
        assertTrue("Capture/decoder lease must actually be available", MicCaptureCoordinator.awaitIdle(2_000))
        await("Service-owned runtime, controller, TTS and transport cleanup must complete before a replacement invocation", 30_000) {
            !AssistantServiceRetirementBarrier.cleanupPending.value
        }
        assertFalse("No service-scoped wake lock may remain", shell("dumpsys power").lineSequence().any { it.contains("kompi:assistant") && it.contains("PARTIAL_WAKE_LOCK") })
    }

    @Before fun guardDisposableState() {
        require(InstrumentationRegistry.getArguments().getString("kompiDisposableEmulator") == "true") {
            "Requires explicit -e kompiDisposableEmulator true on the runner-owned disposable AVD"
        }
        require(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")) { "Never run assistant-role fixtures on a physical device" }
        require(!context.getSystemService(KeyguardManager::class.java).isDeviceSecure) { "Fixture refuses an existing device PIN/password" }
        require(SecureStore(context).trustedMachines().isEmpty() && SecureStore(context).currentPairing() == null) { "Fixture refuses existing trust/pending pairing; use a clean disposable package" }
        require(!AssistantCoordinator.mode.value.assistant && audioManager.activeRecordingConfigurations.isEmpty()) { "Fixture refuses an existing audio owner" }
        for (permission in listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)) {
            require(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) { "Host must explicitly grant $permission before this invocation; instrumentation must not revoke its own permissions" }
        }
        require(notifications.areNotificationsEnabled()) { "Host must enable real notification access" }
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        savedSettings = prefs.all.toMap()
        oldRoleHolders = shell("cmd role get-role-holders --user 0 android.app.role.ASSISTANT").lineSequence().filter { it.matches(Regex("[A-Za-z0-9_.]+")) }.toList()
        oldVoice = setting("voice_interaction_service")
        oldAssistant = setting("assistant")
        fixtureAuthorized = true
        SpeechAudit.initialize(context)
        check(prefs.edit().putBoolean("assistantEnabled", false).putString("speechInputEngine", "parakeet").putBoolean("ttsEnabled", true).commit())
    }

    private fun selectRole() {
        val result = shell("cmd role add-role-holder --user 0 android.app.role.ASSISTANT com.kompi.app 0")
        check(result.isBlank()) { "Android role request failed: $result" }
        shell("settings put secure voice_interaction_service ${voiceComponent.flattenToString()}")
        await("Android must actually bind the selected VoiceInteractionService") { VoiceInteractionService.isActiveService(context, voiceComponent) }
    }
    private fun enableAndPair() {
        check(prefs.edit().putBoolean("assistantEnabled", true).commit())
        selectRole()
        runBlocking { peer = LiveLoopbackPeer.open(context).also { it.pair(context) } }
        audio = EmulatorAudioBridge()
        runBlocking(Dispatchers.IO) { LiveAudioCorpus.model(context) } // Fails with preparation guidance, never skips/downloads.
    }
    private fun gesture() {
        val result = shell("cmd voiceinteraction show")
        check(!result.contains("Error", ignoreCase = true) && !result.contains("Unknown command", ignoreCase = true)) { "Framework assistant gesture command unavailable: $result" }
    }
    private fun listening() {
        gesture()
        await("Authorized framework session must create actual native capture", 120_000) {
            AssistantCoordinator.snapshot.value.state == LiveConversationState.Listening || AssistantCoordinator.failure.value != null
        }
        assertNull("Authorized assistant startup failed", AssistantCoordinator.failure.value)
        assertTrue(AssistantCoordinator.mode.value.assistant)
        assertEquals(1, audioManager.activeRecordingConfigurations.size)
        assertNotNull(notifications.activeNotifications.singleOrNull { it.notification.channelId == "kompi-assistant" })
    }
    private fun notificationStop() {
        val status = notifications.activeNotifications.single { it.notification.channelId == "kompi-assistant" }
        assertTrue(status.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        assertEquals("Running in the background", status.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        // Inflate the actual published RemoteViews and dispatch its installed PendingIntent. This
        // tests the OS notification Stop action, not a direct service method or forged START intent.
        compose.runOnUiThread {
            val view = status.notification.contentView.apply(context, FrameLayout(context))
            assertEquals("Open", view.findViewById<TextView>(R.id.assistant_notification_open).text.toString())
            assertEquals("Stop", view.findViewById<TextView>(R.id.assistant_notification_stop).text.toString())
            assertTrue(view.findViewById<View>(R.id.assistant_notification_stop).performClick())
        }
        noResources()
    }
    private fun restorePrefs() {
        val edit = prefs.edit().clear()
        savedSettings.forEach { (key, value) -> when (value) {
            is Boolean -> edit.putBoolean(key, value)
            is String -> edit.putString(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        check(edit.commit())
    }
    @After fun cleanup() {
        if (!fixtureAuthorized) return
        // Observe the stopped/failed runtime before forced cleanup changes its state.
        runCatching { metadataProbe?.close() }.onFailure {
            println("LIVE_L93 metadataUnavailable=${it.javaClass.simpleName}")
        }
        metadataProbe = null
        try {
            compose.runOnUiThread { AssistantConversationService.stop(context); AssistantCoordinator.unregisterUi(uiToken) }
            noResources()
        } finally {
            runBlocking { oldInput?.closeAndJoin(); audio?.close(); peer?.close() }
            if (pinSet) { shell("locksettings clear --old $testPin"); shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard"); pinSet = false }
            val store = SecureStore(context)
            peer?.hostId?.let { host -> if (store.trustedMachines().any { it.machineId == host }) store.forgetTrustedMachine(host) }
            store.clearPendingPairing()
            peer?.hostId?.let { host -> check(context.getSharedPreferences("identity_v1", Context.MODE_PRIVATE).edit()
                .remove("chat_model_draft_$host").remove("chat_model_confirmed_$host").commit()) }
            // Delete only this ephemeral peer's Room rows; never clear the app/database/identity.
            peer?.hostId?.let { host -> runBlocking(Dispatchers.IO) {
                val sqlite = LocalDatabase.open(context).openHelper.writableDatabase
                for (table in listOf("outbox", "request_state", "sessions", "host_settings", "sync_cursor", "applied_events"))
                    sqlite.execSQL("DELETE FROM $table WHERE hostId = ?", arrayOf(host))
            } }
            shell("cmd role remove-role-holder --user 0 android.app.role.ASSISTANT com.kompi.app 0")
            oldRoleHolders.forEach { shell("cmd role add-role-holder --user 0 android.app.role.ASSISTANT $it 0") }
            restoreSetting("voice_interaction_service", oldVoice)
            restoreSetting("assistant", oldAssistant)
            restorePrefs()
        }
    }

    @Test fun l87_exportedIntentsBootAndMissingConsentOrRoleNeverAuthorizeCapture() {
        shell("cmd role remove-role-holder --user 0 android.app.role.ASSISTANT com.kompi.app 0")
        shell("settings delete secure voice_interaction_service")
        await("Role removal must be real") { !VoiceInteractionService.isActiveService(context, voiceComponent) }
        shell("am start -W -n com.kompi.app/.assistant.HeadsetAssistantActivity -a android.intent.action.VOICE_COMMAND")
        noResources()
        selectRole() // Role alone is insufficient: explicit app consent is still false.
        gesture()
        await("Missing consent must provide an explicit failure") { AssistantCoordinator.failure.value != null }
        assertTrue(AssistantCoordinator.failure.value!!.contains("Enable Earbud assistant"))
        noResources()
        check(prefs.edit().putBoolean("assistantEnabled", true).commit())
        shell("cmd role remove-role-holder --user 0 android.app.role.ASSISTANT com.kompi.app 0")
        shell("settings delete secure voice_interaction_service")
        await("Consent without role cannot pass the actual framework role guard") { !VoiceInteractionService.isActiveService(context, voiceComponent) }
        shell("am start -W -n com.kompi.app/.assistant.HeadsetAssistantActivity -a android.intent.action.VOICE_COMMAND")
        shell("am start -W -n com.kompi.app/.assistant.HeadsetAssistantActivity -a com.kompi.app.assistant.START")
        // Target only this disposable package, never broadcast a fake boot globally.
        shell("am broadcast -a android.intent.action.BOOT_COMPLETED -p com.kompi.app")
        noResources()
        compose.runOnUiThread { context.startService(Intent(context, AssistantConversationService::class.java)) }
        noResources() // Null/unknown service action must be NOT_STICKY and must not claim a session.
    }

    @Test fun l88_frameworkGesturesReuseOneHostPinnedForegroundSessionAfterActualUiCaptureHandoff() {
        enableAndPair()
        val model = runBlocking(Dispatchers.IO) { LiveAudioCorpus.model(context) }
        val firstFrame = AtomicBoolean(false)
        val released = CompletableDeferred<Unit>()
        compose.runOnUiThread {
            oldInput = LocalLiveSpeechInput(context, compose.activity, SpeechRecognitionModel.Parakeet(model)).also { input ->
                input.start(object : LiveSpeechInput.Listener {
                    override fun onSpeechStarted(utteranceId: com.kompi.app.audio.SpeechUtteranceId) {}
                    override fun onPartial(utteranceId: com.kompi.app.audio.SpeechUtteranceId, committedText: String, pendingText: String, language: String?) {}
                    override fun onFinal(utteranceId: com.kompi.app.audio.SpeechUtteranceId, text: String, language: String?) {}
                    override fun onMeter(rms: Float, phase: com.kompi.app.live.CapturePhase, waveform: com.kompi.app.audio.AudioWaveform) { firstFrame.set(true) }
                    override fun onLocalStop(reason: com.kompi.app.live.LiveLocalStopReason) { error("Old UI capture stopped: $reason") }
                })
            }
            AssistantCoordinator.registerUi(uiToken) { oldInput!!.closeAndJoin(); released.complete(Unit) }
        }
        await("Initial real Activity-owned microphone must be ready", 90_000) { firstFrame.get() }
        val oldSession = audioManager.activeRecordingConfigurations.single().clientAudioSessionId
        listening()
        assertTrue("Service replacement must await the old native decoder release", released.isCompleted)
        assertNotEquals(oldSession, audioManager.activeRecordingConfigurations.single().clientAudioSessionId)
        val generation = AssistantCoordinator.mode.value.generation
        gesture(); gesture()
        await("Repeated gestures keep the same live service session") { AssistantCoordinator.snapshot.value.state == LiveConversationState.Listening }
        assertEquals(generation, AssistantCoordinator.mode.value.generation)
        assertEquals(1, audioManager.activeRecordingConfigurations.size)
        assertEquals("Disposable loopback acceptance peer", AssistantCoordinator.hostName.value)
        audio!!.inject("review")
        await("Actual service ASR must reach the pinned authenticated peer") { peer!!.prompts.size == 1 }
        val text = (peer!!.prompts.single().message as com.kompi.app.protocol.generated.WireMessage.Prompt).text
        assertTrue(LiveAudioCorpus.words(text).containsAll(setOf("please", "review", "latest", "changes")))
        notificationStop()
    }

    @Test fun l89_notificationStopReleasesRealNativeResourcesAndNullRestartCannotRecapture() {
        enableAndPair(); listening()
        val firstGeneration = AssistantCoordinator.mode.value.generation
        val firstSessionId = audioManager.activeRecordingConfigurations.single().clientAudioSessionId
        val pinnedHost = AssistantCoordinator.hostName.value
        notificationStop()

        // A null restart must not alter the retired generation or re-open capture. Then explicitly
        // prove that the real framework gesture can acquire a fresh session after full cleanup.
        compose.runOnUiThread { context.startService(Intent(context, AssistantConversationService::class.java)) }
        noResources()
        assertEquals(firstGeneration, AssistantCoordinator.mode.value.generation)
        listening()
        assertTrue("A new framework gesture must claim a fresh service generation", AssistantCoordinator.mode.value.generation > firstGeneration)
        assertEquals("The replacement session remains pinned to the same authenticated fixture peer", pinnedHost, AssistantCoordinator.hostName.value)
        assertEquals(1, audioManager.activeRecordingConfigurations.size)
        assertNotEquals(firstSessionId, audioManager.activeRecordingConfigurations.single().clientAudioSessionId)
        notificationStop()
    }

    @Test fun l89_actualRoleRevocationStopsCaptureAndDoesNotResumeWhenRoleReturns() {
        enableAndPair(); listening()
        shell("cmd role remove-role-holder --user 0 android.app.role.ASSISTANT com.kompi.app 0")
        shell("settings delete secure voice_interaction_service")
        await("Framework must revoke the actual role") { !VoiceInteractionService.isActiveService(context, voiceComponent) }
        noResources()
        val generation = AssistantCoordinator.mode.value.generation
        selectRole()
        noResources()
        assertEquals(generation, AssistantCoordinator.mode.value.generation)
    }

    @Test fun l89_removingActualAssistantTaskStopsServiceWithoutStickyCaptureRestart() {
        enableAndPair(); listening()
        val manager = context.getSystemService(ActivityManager::class.java)
        val tasks = manager.appTasks.filter { it.taskInfo?.baseIntent?.component?.className == AssistantActivity::class.java.name }
        assertEquals("Framework gesture must own one real assistant task", 1, tasks.size)
        tasks.single().finishAndRemoveTask()
        noResources()
    }

    @Test fun l89_actualMissingKokoroInitializationFailsServiceWithoutFallbackAndReleasesResources() {
        enableAndPair()
        val models = File(context.filesDir, "kokoro-models")
        val held = File(context.filesDir, "live-test-held-kokoro-${UUID.randomUUID()}")
        require(models.isDirectory && !held.exists()) { "Requires prepared real Kokoro model directory" }
        check(models.renameTo(held)) { "Could not atomically isolate the disposable emulator's model fixture" }
        try {
            gesture()
            await("Actual missing selected TTS engine must terminate startup", 120_000) { AssistantCoordinator.failure.value != null }
            noResources()
            assertTrue(AssistantCoordinator.failure.value!!.contains("Kokoro", ignoreCase = true))
        } finally { check(held.renameTo(models)) { "Restore the held Kokoro fixture at ${held.path}" } }
    }

    @Test fun l90_consentedFrameworkAssistantContinuesRealRecognitionAfterSecureKeyguardLocks() {
        enableAndPair(); listening()
        LiveAssistantLockedCaptureProbe(context).use { capture ->
            capture.checkpoint("framework_listening")
            try {
                shell("locksettings set-pin $testPin")
                pinSet = true // Cleanup must attempt removal even if a later setup assertion fails.
                await("Disposable fixture must establish a real secure keyguard") { context.getSystemService(KeyguardManager::class.java).isDeviceSecure }
                capture.checkpoint("secure_keyguard_established")
                shell("input keyevent KEYCODE_SLEEP")
                await("The emulator must actually be device-locked, not only Activity-backgrounded") { context.getSystemService(KeyguardManager::class.java).isDeviceLocked }
                assertTrue(VoiceInteractionService.isActiveService(context, voiceComponent))
                assertTrue(prefs.getBoolean("assistantEnabled", false))
                assertEquals(1, audioManager.activeRecordingConfigurations.size)
                val locked = capture.checkpoint("secure_keyguard_locked")
                capture.startPeriodicCheckpoints()
                // Listening/configuration alone does not prove that the current HAL input is
                // advancing after screen-off. Never inject into a closed/stale SDK input. Wait
                // for real locked, unsilenced current-owner PCM progress, not an arbitrary delay.
                var first: LiveAssistantLockedCaptureProbe.Sample? = null
                var latest = locked
                await("The actual locked assistant owner must receive unsilenced advancing PCM before SDK injection", 5_000) {
                    latest = capture.sample()
                    val initial = first
                    if (!latest.usableLockedCapture) { first = null; false }
                    else if (initial == null || initial.recorder !== latest.recorder) { first = latest; false }
                    else latest.framePosition - initial.framePosition >= 160 &&
                        latest.timestampNanos > initial.timestampNanos && latest.observedMeterFrames > locked.observedMeterFrames
                }
                capture.checkpoint("locked_current_hal_advancing", "firstFrame" to requireNotNull(first).framePosition,
                    "latestFrame" to latest.framePosition, "metersAtLock" to locked.observedMeterFrames)
                capture.checkpoint("injection_enter")
                val injected = audio!!.inject("continue")
                capture.checkpoint("injection_rpc_returned", "sdkReportedFrames" to injected.getInt("frames"))
                assertTrue("Actual SDK injection must contain the authored spoken fixture", injected.getInt("frames") > 16_000)
                await("Explicitly consented service must recognize while secure keyguard remains locked", 90_000) { peer!!.prompts.size == 1 }
                assertTrue(context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
                val prompt = peer!!.prompts.single().message as com.kompi.app.protocol.generated.WireMessage.Prompt
                assertTrue(LiveAudioCorpus.words(prompt.text).containsAll(setOf("continue", "review")))
                capture.checkpoint("real_locked_recognition_verified", "prompts" to peer!!.prompts.size)
            } finally {
                capture.checkpoint("case_final", "prompts" to peer!!.prompts.size,
                    "failurePresent" to if (AssistantCoordinator.failure.value != null) 1 else 0)
            }
        }
        notificationStop()
        println("LIVE_L90 secureKeyguard=true role=framework_bound consent=explicit_fixture fgs=visible nativeAsr=true speakerAuthentication=false physicalGestureAndDisclosureUi=not_claimed")
    }

    @Test fun l93_actualAssistantPersistsOnlyEncryptedIntentAndNoPcmOrSpeechTextDiagnostics() {
        enableAndPair()
        fun privateFiles() = listOf(context.filesDir, context.cacheDir, context.noBackupFilesDir)
            .flatMap { root -> root.walkTopDown().filter { it.isFile }.map { it.canonicalPath }.toList() }.toSet()
        val before = privateFiles()
        listening()
        metadataProbe = runCatching { LiveAssistantMetadataOnlyProbe(context) }.getOrElse {
            println("LIVE_L93 metadataUnavailable=${it.javaClass.simpleName}")
            null
        }
        audio!!.inject("review")
        await("Actual speech must reach only the text prompt boundary") { peer!!.prompts.size == 1 }
        val envelope = peer!!.prompts.single()
        val prompt = envelope.message as com.kompi.app.protocol.generated.WireMessage.Prompt
        assertTrue(LiveAudioCorpus.words(prompt.text).containsAll(setOf("please", "review", "latest", "changes")))
        assertTrue(prompt.attachments.isNullOrEmpty())
        val repository = com.kompi.app.data.LocalRepository(LocalDatabase.open(context), SecureStore(context))
        val durable = runBlocking { repository.pendingPrompts(peer!!.hostId) }.single { it.requestId == envelope.id }
        assertEquals(prompt.text, durable.text)
        val row = runBlocking { LocalDatabase.open(context).localDao().outboxItem(peer!!.hostId, envelope.id) }!!
        assertFalse("The outbox is encrypted, not a second plaintext transcript", row.encryptedBody.contains(prompt.text))
        notificationStop()
        SpeechAudit.query(null).use { rows ->
            while (rows.moveToNext()) for (column in 0 until rows.columnCount) if (!rows.isNull(column))
                assertFalse("Speech text must not occur in audit column ${rows.columnNames[column]}", rows.getString(column).contains(prompt.text, ignoreCase = true))
        }
        val logs = shell("logcat -d --pid=${android.os.Process.myPid()} -v brief -t 5000")
        assertFalse("Speech text must not leak to diagnostic Logcat", logs.contains(prompt.text, ignoreCase = true))
        for (path in privateFiles() - before) {
            val file = File(path)
            val header = file.inputStream().use { stream -> ByteArray(12).also { stream.read(it) } }
            assertFalse("No new microphone audio container may be persisted: ${file.name}",
                String(header, Charsets.ISO_8859_1).startsWith("RIFF") || file.extension.lowercase() in setOf("wav", "pcm", "raw", "flac", "m4a", "aac", "ogg"))
        }
        println("LIVE_L93 boundary=real_service_files_audit_logcat_and_actual_QUIC_prompt noPersistedNewPcm=true noDiagnosticSpeechText=true packetCaptureAndInstalledDaemonLogs=not_claimed")
    }
}
