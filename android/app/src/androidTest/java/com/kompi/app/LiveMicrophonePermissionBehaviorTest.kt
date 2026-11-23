package com.kompi.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.kompi.app.audio.SpeechRecognitionModel
import com.kompi.app.audio.live.LocalLiveSpeechInput
import com.kompi.app.live.LiveConversationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Each method is launched separately after host-side permission revocation (revoking kills an app). */
class LiveMicrophonePermissionBehaviorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var h: NativeLiveAudioHarness
    private lateinit var audio: EmulatorAudioBridge
    @Before fun mount() {
        assertEquals("Runner must revoke permission before this instrumentation invocation", PackageManager.PERMISSION_DENIED,
            ContextCompat.checkSelfPermission(compose.activity, Manifest.permission.RECORD_AUDIO))
        val model = runBlocking(Dispatchers.IO) { LiveAudioCorpus.model(compose.activity) }
        audio = EmulatorAudioBridge()
        h = NativeLiveAudioHarness(compose, inputFactory = { session -> LocalLiveSpeechInput(compose.activity, compose.activity, SpeechRecognitionModel.Parakeet(model), audioSession = session) })
        h.mount()
    }
    @After fun cleanup() { if (::h.isInitialized) h.close(); if (::audio.isInitialized) audio.close() }
    private fun failWithoutPermission() {
        audio.startCapture(); h.start()
        h.await("Missing real microphone permission must fail explicitly", allowFailure = true) { h.snapshot.state is LiveConversationState.Failure }
        assertTrue((h.snapshot.state as LiveConversationState.Failure).message.contains("permission", ignoreCase = true))
        assertTrue(compose.activity.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
        assertEquals("Denied capture must not play a ready cue", 0, audio.stopCapture().getInt("nonzero"))
        assertTrue(h.submissions.isEmpty())
    }

    @Test fun deniedPermissionAndGrantRecoveryRequireExplicitStartBeforeRealRecognition() {
        failWithoutPermission()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val result = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("pm grant com.kompi.app android.permission.RECORD_AUDIO")).bufferedReader().use { it.readText() }
        check(result.isBlank()) { "Test permission grant failed: $result" }
        assertEquals(PackageManager.PERMISSION_GRANTED, ContextCompat.checkSelfPermission(compose.activity, Manifest.permission.RECORD_AUDIO))
        compose.waitForIdle()
        assertEquals(1, h.inputs.size)
        assertTrue(compose.activity.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
        assertTrue(h.snapshot.state is LiveConversationState.Failure)
        h.start()
        h.await("Explicit Start after permission grant must open real capture") { h.snapshot.state == LiveConversationState.Listening || h.snapshot.state is LiveConversationState.Failure }
        audio.inject("continue")
        h.await("Actual virtual-mic speech must be recognized after grant") { h.submissions.size == 1 || h.snapshot.state is LiveConversationState.Failure }
        h.assertWords(LiveAudioCorpus.fixtures.single { it.id == "continue" }, h.submissions.single().text)
        assertEquals(2, h.inputs.size)
    }

    @Test fun deniedPermissionOffersSetupRatherThanNonfunctionalRequestRetry() {
        failWithoutPermission()
        compose.onNode(hasClickAction() and (
            hasText("settings", substring = true, ignoreCase = true) or
                hasText("permission", substring = true, ignoreCase = true) or
                hasText("set up", substring = true, ignoreCase = true)
            )).assertExists("A permission failure needs an actionable setup/settings route, not a request retry")
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }
}
