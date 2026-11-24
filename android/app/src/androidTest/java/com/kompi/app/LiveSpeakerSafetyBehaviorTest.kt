package com.kompi.app

import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.AcousticEchoCanceler
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.kompi.app.audio.SpeechRecognitionModel
import com.kompi.app.audio.KokoroTtsEngine
import com.kompi.app.audio.KokoroSpeechStatus
import com.kompi.app.audio.live.LocalLiveSpeechInput
import com.kompi.app.live.LiveConversationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Stock framework-AEC-less guest: scoped no-software negative AND real-software positive.
 * The default factory remains real; a hardware enable/availability flag is not the positive oracle. */
class LiveSpeakerSafetyBehaviorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val testName = TestName()
    private val onsets = CopyOnWriteArrayList<Long>()
    private lateinit var h: NativeLiveAudioHarness
    private lateinit var audio: EmulatorAudioBridge
    @Before fun mount() {
        if (testName.methodName.startsWith("missingSpeaker")) org.junit.Assume.assumeFalse(AcousticEchoCanceler.isAvailable())
        else org.junit.Assume.assumeTrue(AcousticEchoCanceler.isAvailable())
        val model = runBlocking(Dispatchers.IO) { LiveAudioCorpus.model(compose.activity) }
        audio = EmulatorAudioBridge()
        h = NativeLiveAudioHarness(compose, inputFactory = { session ->
            ObservedNativeLiveInput(LocalLiveSpeechInput(compose.activity, compose.activity, SpeechRecognitionModel.Parakeet(model), audioSession = session), onsets::add)
        })
        h.mount()
    }
    @After fun cleanup() {
        if (::h.isInitialized) h.dumpAudioDiagnostics()
        try { if (::h.isInitialized) h.close() }
        finally { if (::audio.isInitialized) audio.close() }
    }
    @Test fun missingSpeakerAecFailsClosedWithHeadphonesGuidanceAndNoCaptureOrCue() {
        audio.startCapture(); h.start()
        h.await("Unsafe speaker capture must fail", allowFailure = true) { h.snapshot.state is LiveConversationState.Failure }
        val reason = (h.snapshot.state as LiveConversationState.Failure).message
        assertTrue(reason.contains("echo cancellation", ignoreCase = true))
        assertTrue(reason.contains("headphones", ignoreCase = true))
        assertTrue(compose.activity.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
        assertEquals(0, audio.stopCapture().getInt("nonzero"))
        assertTrue(h.submissions.isEmpty())
    }

    @Test fun platformAecOnSpeakerPreservesEchoAndNearEndBargeIn() {
        h.start()
        h.await("Real software-protected capture must become ready") {
            h.snapshot.state == LiveConversationState.Listening || h.snapshot.state is LiveConversationState.Failure
        }
        audio.inject("review")
        h.await("Actual selected-model recognition must submit the initial point") { h.submissions.size == 1 }
        h.assertWords(LiveAudioCorpus.fixtures.single { it.id == "review" }, h.submissions.single().text)
        val manager = compose.activity.getSystemService(AudioManager::class.java)
        val capture = manager.activeRecordingConfigurations.single().clientAudioSessionId
        val old = h.submissions.single().requestId
        val trackField = KokoroTtsEngine::class.java.getDeclaredField("activeTrack").apply { isAccessible = true }
        var failure: Throwable? = null
        audio.startEcho()
        try {
            h.reply("The build finished successfully, and this deliberately long answer continues while its actual speaker output returns through the microphone. A real near end interruption must keep its own words and cancel only this old answer.")
            var firstHead: Long? = null
            h.await("A full second of actual energetic speaker playback must survive without self-interruption") {
                assertEquals(1, onsets.size); assertEquals(1, h.submissions.size); assertTrue(h.cancellations.isEmpty())
                assertNotEquals(KokoroSpeechStatus.Cancelled, h.tts.requestStatuses.value[old]?.state)
                val track = trackField.get(h.tts) as? AudioTrack
                if (track == null) false else {
                    val head = track.playbackHeadPosition.toLong() and 0xffff_ffffL
                    if (firstHead == null) firstHead = head
                    head - firstHead!! >= track.sampleRate
                }
            }
            val mixed = audio.mixNearEndFixture("interrupt")
            val frames = mixed.getInt("overlayFrames")
            val nonzero = mixed.getInt("overlayNonzero")
            val overlap = mixed.getInt("echoNonzeroDuringOverlay")
            val clipped = mixed.getInt("mixedClippedSamples")
            assertTrue(frames >= 16_000); assertTrue(nonzero in 1001..frames)
            assertTrue(overlap in 1001..nonzero); assertTrue(clipped in 0..frames)
            println("REAL_AUDIO_ACOUSTIC overlayFrames=$frames overlayNonzero=$nonzero echoNonzeroDuringOverlay=$overlap mixedClippedSamples=$clipped boundary=submitted_to_single_sdk_request")
            h.await("Mixed near-end speech must become a distinct actual durable point") { h.submissions.size >= 2 }
            fun words(text: String) = Regex("[\\p{L}\\p{N}]+")
                .findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()
            assertEquals(words(LiveAudioCorpus.fixtures.single { it.id == "interrupt" }.spoken), words(h.submissions[1].text))
            assertEquals(2, onsets.size); assertEquals(2, h.submissions.size)
            assertNotEquals(old, h.submissions.last().requestId)
            h.await("Only the real old output must cancel") {
                h.tts.requestStatuses.value[old]?.state == KokoroSpeechStatus.Cancelled && old in h.cancellations
            }
            assertEquals(listOf(old), h.cancellations.toList())
            h.reply("I will explain the error instead. This replacement answer must finish with real software echo protection.")
            h.drained(h.submissions.last().requestId)
            assertEquals(setOf(capture), manager.activeRecordingConfigurations.map { it.clientAudioSessionId }.toSet())
            assertEquals(1, h.inputs.size)
            assertEquals(2, runBlocking { h.repository.pendingPrompts("native-test-host") }.size)
            assertTrue(h.audioFailures.isEmpty())
        } catch (error: Throwable) { failure = error }
        try {
            val evidence = audio.stopEcho()
            assertTrue(evidence.getInt("injectedNonzero") > 1000)
            assertTrue(evidence.getDouble("injectedPeakRms") > 0.01)
            assertEquals(0.8, evidence.getDouble("echoGain"), 0.0001)
            assertEquals(100, evidence.getInt("minimumHostDelayMs"))
        } catch (error: Throwable) { if (failure == null) failure = error else failure.addSuppressed(error) }
        failure?.let { throw it }
    }
}
