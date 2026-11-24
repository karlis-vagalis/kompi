package com.kompi.app

import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.kompi.app.audio.SpeechRecognitionModel
import com.kompi.app.audio.live.LocalLiveSpeechInput
import com.kompi.app.live.LiveConversationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import java.util.concurrent.CopyOnWriteArrayList

/** Shared real-capture setup. Route/HAL stress runs after core journeys in a separate invocation. */
internal abstract class NativeMicrophoneScenario {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    protected lateinit var h: NativeLiveAudioHarness
    protected lateinit var audio: EmulatorAudioBridge
    protected val onsets = CopyOnWriteArrayList<Long>()
    protected val manager get() = compose.activity.getSystemService(AudioManager::class.java)
    protected fun liveRecordings(): List<android.media.AudioRecordingConfiguration> {
        // AudioManager's client-visible configurations are matched to exact adopted Live
        // session IDs, including ALL retired owners. Source is diagnostic, never ownership:
        // RAW/recognition sources must not hide leaks or include unrelated dictation capture.
        val ownedSessions = h.ownedRecordingSessionIds()
        return manager.activeRecordingConfigurations.filter { it.clientAudioSessionId in ownedSessions }
    }
    protected fun microphoneStopped() {
        try { compose.waitUntil(3_000) { liveRecordings().isEmpty() } }
        catch (error: Throwable) { throw AssertionError("Actual AudioRecord session remained open: ${liveRecordings().map { it.clientAudioSessionId }}; Live=${h.snapshot.state}", error) }
    }
    @Before fun mount() {
        val model = runBlocking(Dispatchers.IO) { LiveAudioCorpus.model(compose.activity) }
        audio = EmulatorAudioBridge()
        h = NativeLiveAudioHarness(compose, inputFactory = { session ->
            ObservedNativeLiveInput(LocalLiveSpeechInput(compose.activity, compose.activity, SpeechRecognitionModel.Parakeet(model), audioSession = session), onsets::add)
        })
        h.mount()
    }
    @After fun cleanup() { if (::h.isInitialized) { h.dumpAudioDiagnostics(); h.close() }; if (::audio.isInitialized) audio.close() }
    protected fun startListening() {
        h.start()
        h.await("Normal Live microphone must become ready or fail explicitly") { h.snapshot.state == LiveConversationState.Listening || h.snapshot.state is LiveConversationState.Failure }
    }
    protected fun spoken(fixture: String, count: Int) {
        val result = audio.inject(fixture)
        assertTrue(result.getInt("frames") > 16_000)
        h.await("Actual microphone recognition must submit $fixture") { h.submissions.size >= count || h.snapshot.state is LiveConversationState.Failure }
        val corpusId = if (fixture in setOf("review-fast", "review-soft")) "review" else fixture
        h.assertWords(LiveAudioCorpus.fixtures.single { it.id == corpusId }, h.submissions[count - 1].text)
    }
}
