package com.kompi.app

import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.kompi.app.audio.MicCaptureCoordinator
import com.kompi.app.audio.OfflineSpeechSession
import com.kompi.app.audio.OfflineTranscript
import com.kompi.app.audio.SpeechRecognitionModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** The SDK RPC returning is deliberately not modeled as proof of AudioRecord delivery. */
internal enum class NativeDictationHandoffTiming {
    AwaitNativeEndpoint,
    StopAtInjectionReturnStress,
}

/** Same real Live → dictation → Live ownership journey, with two explicitly different barriers.
 * Normal acceptance waits for a finalized native endpoint callback; SDK teardown stress does not.
 * Stage bundles survive guest/ADB aborts without exposing recognized words or diagnostic bodies. */
internal fun runNativeDictationHandoff(
    compose: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>,
    h: NativeLiveAudioHarness,
    audio: EmulatorAudioBridge,
    timing: NativeDictationHandoffTiming,
    startListening: () -> Unit,
    spoken: (String, Int) -> Unit,
    microphoneStopped: () -> Unit,
) {
    val manager = compose.activity.getSystemService(AudioManager::class.java)
    val transcripts = CopyOnWriteArrayList<OfflineTranscript>()
    val messages = CopyOnWriteArrayList<String>()
    val recording = AtomicBoolean(false)
    val endpointNanos = AtomicLong(0)
    val waitForEndpoint = timing == NativeDictationHandoffTiming.AwaitNativeEndpoint
    fun checkpoint(stage: String, vararg values: Pair<String, Number>) {
        recordLiveNumericObservation("L71", stage,
            "elapsedNanos" to SystemClock.elapsedRealtimeNanos(),
            "waitForEndpoint" to if (waitForEndpoint) 1 else 0,
            "recordings" to manager.activeRecordingConfigurations.size,
            "liveInputs" to h.inputs.size, "liveSubmissions" to h.submissions.size,
            "dictationTranscripts" to transcripts.size,
            "dictationRecordingCallback" to if (recording.get()) 1 else 0,
            *values)
    }
    checkpoint(if (waitForEndpoint) "normal_handoff_begin" else "sdk_immediate_stop_stress_begin")
    val model = runBlocking(Dispatchers.IO) { LiveAudioCorpus.model(h.context) }
    checkpoint("initial_live_start_enter")
    startListening()
    checkpoint("initial_live_ready")
    spoken("continue", 1)
    checkpoint("initial_live_point_submitted")
    h.reply("The current answer drains before the user explicitly switches input modes.")
    checkpoint("initial_live_reply_admitted")
    h.drained(h.submissions.single().requestId)
    checkpoint("initial_live_reply_drained")
    checkpoint("initial_live_end_enter")
    h.main { h.controller.endLive() }
    checkpoint("initial_live_end_dispatched")
    checkpoint("old_live_native_join_enter")
    runBlocking { withTimeout(20_000) { h.inputs.last().closeAndJoin() } }
    checkpoint("old_live_native_join_return")
    microphoneStopped()
    assertTrue("The previous Live decoder/hardware lease must be free before dictation", MicCaptureCoordinator.awaitIdle(2_000))
    checkpoint("old_live_lease_free")
    assertTrue(manager.activeRecordingConfigurations.isEmpty())

    var dictation: OfflineSpeechSession? = null
    var primaryFailure: Throwable? = null
    try {
        checkpoint("dictation_create_start_enter")
        compose.runOnUiThread {
            dictation = OfflineSpeechSession(h.context, compose.activity, SpeechRecognitionModel.Parakeet(model), null,
                onTranscript = { transcript ->
                    // OfflineSpeechSession only publishes finalized native VAD/decoder results.
                    // This observation does not synthesize words or imply the RPC drained audio.
                    endpointNanos.compareAndSet(0, SystemClock.elapsedRealtimeNanos())
                    transcripts += transcript
                    checkpoint("dictation_endpoint_callback", "endpointObservedNanos" to endpointNanos.get())
                },
                onMessage = messages::add,
                onRecordingChanged = recording::set,
            ).also { it.start() }
        }
        val session = requireNotNull(dictation)
        checkpoint("dictation_start_dispatched")
        compose.waitUntil(90_000) { recording.get() || messages.any { it.contains("Could not") } }
        assertTrue("Actual dictation must acquire capture: $messages", recording.get())
        assertEquals("No concurrent Live recorder may remain", 1, manager.activeRecordingConfigurations.size)
        val audioSessionId = manager.activeRecordingConfigurations.single().clientAudioSessionId
        checkpoint("dictation_recording_acquired", "audioSessionId" to audioSessionId)
        // A recording configuration/start callback is not proof that the replacement HAL input
        // is advancing. Inspect the actual current recorder without replacing its real consumer.
        val captureField = OfflineSpeechSession::class.java.getDeclaredField("capture").apply { isAccessible = true }
        val capture = requireNotNull(captureField.get(session))
        val recorderField = capture.javaClass.getDeclaredField("recorder").apply { isAccessible = true }
        val recorder = recorderField.get(capture) as AudioRecord
        assertEquals("Readiness belongs to the current dictation owner", audioSessionId, recorder.audioSessionId)
        var firstFrame: Long? = null
        var latestFrame = 0L
        var latestTimestamp = 0L
        compose.waitUntil(5_000) {
            val timestamp = AudioTimestamp()
            val current = recorderField.get(capture) === recorder &&
                manager.activeRecordingConfigurations.singleOrNull()?.clientAudioSessionId == audioSessionId
            if (current && recorder.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                latestFrame = timestamp.framePosition
                latestTimestamp = timestamp.nanoTime
                if (firstFrame == null && latestFrame > 0 && latestTimestamp > 0) firstFrame = latestFrame
                firstFrame?.let { latestFrame - it >= 160 } == true
            } else false
        }
        checkpoint("dictation_current_hal_advancing", "audioSessionId" to audioSessionId,
            "firstFrame" to requireNotNull(firstFrame), "latestFrame" to latestFrame, "timestampNanos" to latestTimestamp)
        checkpoint("dictation_inject_enter")
        val injected = audio.inject("negation")
        val injectionReturned = SystemClock.elapsedRealtimeNanos()
        checkpoint("dictation_inject_rpc_return", "rpcReturnedNanos" to injectionReturned, "sdkReportedFrames" to injected.getInt("frames"))
        assertTrue("Actual SDK injection must contain the authored spoken fixture", injected.getInt("frames") > 16_000)
        if (waitForEndpoint) {
            checkpoint("dictation_endpoint_wait_enter")
            compose.waitUntil(90_000) { endpointNanos.get() > 0 && transcripts.isNotEmpty() }
            h.assertWords(LiveAudioCorpus.fixtures.single { it.id == "negation" }, transcripts.joinToString(" ") { it.text })
            checkpoint("dictation_endpoint_verified", "endpointObservedNanos" to endpointNanos.get())
        }
        // In the isolated stress variant there is intentionally no endpoint wait here. These
        // status calls add observable instrumentation overhead, not a guessed delivery sleep.
        checkpoint("dictation_stop_join_enter", "rpcReturnedNanos" to injectionReturned)
        val stopCalled = SystemClock.elapsedRealtimeNanos()
        runBlocking { withTimeout(30_000) { session.stopAndJoin(flush = true) } }
        val stopReturned = SystemClock.elapsedRealtimeNanos()
        checkpoint("dictation_stop_join_return",
            "stopCalledNanos" to stopCalled, "stopReturnedNanos" to stopReturned,
            "rpcReturnToStopCallNanos" to stopCalled - injectionReturned,
            "stopJoinNanos" to stopReturned - stopCalled,
            "endpointObservedNanos" to endpointNanos.get(),
            "endpointObservedBeforeStop" to if (endpointNanos.get() in 1L..stopCalled) 1 else 0)
        if (waitForEndpoint) assertTrue("Normal handoff must observe native finalization before Stop", endpointNanos.get() in 1L..stopCalled)
        compose.waitUntil(10_000) { transcripts.isNotEmpty() }
        // Preserve the original immediate-stop contract too: the isolated stress must retain
        // actual negation/words, or fail honestly. An RPC success alone cannot satisfy this.
        h.assertWords(LiveAudioCorpus.fixtures.single { it.id == "negation" }, transcripts.joinToString(" ") { it.text })
        assertEquals("Dictation must not dispatch an extra Live instruction", 1, h.submissions.size)
        checkpoint("dictation_words_verified")
    } catch (error: Throwable) {
        primaryFailure = error
        throw error
    } finally {
        dictation?.let { session ->
            checkpoint("dictation_cleanup_join_enter")
            val cleanup = runCatching { runBlocking { withTimeout(30_000) { session.stopAndJoin(flush = false) } } }
            // Lifecycle observer removal belongs to the actual Activity's main thread.
            val close = runCatching { compose.runOnUiThread { session.close() } }
            checkpoint("dictation_cleanup_join_return", "cleanupSucceeded" to if (cleanup.isSuccess && close.isSuccess) 1 else 0)
            val cleanupError = cleanup.exceptionOrNull() ?: close.exceptionOrNull()
            if (cleanupError != null) {
                val primary = primaryFailure
                if (primary != null) primary.addSuppressed(cleanupError) else throw cleanupError
            }
        }
    }
    assertTrue("Dictation native cleanup must precede replacement Live", MicCaptureCoordinator.awaitIdle(2_000))
    assertTrue(manager.activeRecordingConfigurations.isEmpty())
    checkpoint("dictation_lease_free")
    checkpoint("replacement_live_start_enter")
    startListening()
    checkpoint("replacement_live_ready")
    spoken("review", 2)
    checkpoint("replacement_live_point_submitted")
    assertEquals(1, manager.activeRecordingConfigurations.size)
    assertEquals(2, h.submissions.size)
    assertEquals(2, h.inputs.size)
    h.reply("The new Live input owns this answer.")
    h.drained(h.submissions.last().requestId)
    checkpoint("replacement_live_reply_drained")
}
