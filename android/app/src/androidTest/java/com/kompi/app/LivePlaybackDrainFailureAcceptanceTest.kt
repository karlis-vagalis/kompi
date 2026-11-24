package com.kompi.app

import android.media.AudioTrack
import android.os.SystemClock
import com.kompi.app.audio.KokoroPlaybackPhase
import com.kompi.app.audio.KokoroSpeechStatus
import com.kompi.app.audio.KokoroTtsEngine
import com.kompi.app.audio.KokoroTtsState
import com.kompi.app.diagnostics.SpeechAudit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable

/** Actual microphone/ASR/outbox/controller, reply queue, Kokoro PCM and AudioTrack. The ONLY fault
 * is a test-reflected pause of the real owned track at its terminal Draining boundary. No head,
 * completion, PCM, request status or timeout is substituted. This is a controlled playback stall,
 * not a claim of a hardware/driver defect or physical acoustic certification.
 *
 * An Unconfined observer pauses synchronously when the writer publishes Draining, AFTER all writes
 * have been accepted. Pausing earlier could instead block a write and never exercise terminal drain.
 * The accepted-frame counter belongs to that exact track's real render lease; it is never reduced.
 * There is deliberately no flush/stop/head reset until production failure/cancellation cleanup.
 */
internal class LivePlaybackDrainFailureAcceptanceTest : NativeMicrophoneScenario() {
    @Test fun l48_l98_stalledActualPlaybackFailsWithoutCompletedAndExplicitRestartDrains() {
        SpeechAudit.initialize(h.context)
        startListening(); spoken("continue", 1)
        val request = h.submissions.single().requestId
        val fault = TerminalTrackPause(request)
        try {
            audio.startCapture()
            h.reply(STALLED_REPLY)
            val stalled = fault.await()
            assertStalled(stalled)
            val rendered = audio.stopCapture()
            assertTrue("The stalled track must already have rendered real non-silent PCM", rendered.getInt("nonzero") > 1000)

            val terminal = runBlocking {
                withTimeout(stalled.timeoutMs + TIMEOUT_OBSERVATION_ALLOWANCE_MS) {
                    h.tts.requestStatuses.first { it[request]?.state in TERMINAL }.getValue(request)
                }
            }
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - stalled.pausedAtNanos) / 1_000_000
            assertEquals("An unplayed accepted tail must fail explicitly, never become Completed", KokoroSpeechStatus.Failed, terminal.state)
            assertFalse("A drain failure must carry an explicit reason", terminal.failureMessage.isNullOrBlank())
            assertTrue("This must reach the actual drain timeout, not fail at an unrelated earlier boundary", elapsedMs >= stalled.timeoutMs - 100)
            assertNoSuccessfulDrain(request, stalled.sequence)
            awaitRetired(stalled)
            assertTrue("Local playback failure must not cancel accepted remote work", h.cancellations.isEmpty())
            assertEquals("The complete public reply remains readable after a local audio failure", STALLED_REPLY, h.snapshot.assistantText)
            recordLiveNumericObservation("L98", "actual_track_paused_at_terminal_drain_timeout",
                "acceptedTargetFrames" to stalled.acceptedFrames, "pausedActualHead" to stalled.pausedHead,
                "sampleRateHz" to stalled.sampleRate, "expectedTimeoutMs" to stalled.timeoutMs,
                "observedTimeoutMs" to elapsedMs, "renderedNonzeroSamples" to rendered.getInt("nonzero"),
                "completed" to 0, "explicitFailure" to 1, "flushBeforeOutcome" to 0)
        } finally { fault.close() }

        // Recovery is explicit and uses a fresh real capture/recognition turn, not a scripted intent.
        restartAndRecognize()
        val replacement = h.submissions.last().requestId
        assertNotEquals(request, replacement)
        h.reply(RECOVERY_REPLY)
        h.drained(replacement)
        assertEquals(KokoroSpeechStatus.Failed, h.tts.requestStatuses.value[request]?.state)
        assertEquals(KokoroSpeechStatus.Completed, h.tts.requestStatuses.value[replacement]?.state)
    }

    @Test fun l48_l98_cancelledStalledDrainExitsPromptlyAndCannotFailNewerActualPlayback() {
        SpeechAudit.initialize(h.context)
        startListening(); spoken("continue", 1)
        val request = h.submissions.single().requestId
        val fault = TerminalTrackPause(request)
        val stalled: StalledTrack
        try {
            h.reply(STALLED_REPLY)
            stalled = fault.await()
            assertStalled(stalled)
            val cancellationStarted = SystemClock.elapsedRealtimeNanos()
            h.main { h.tts.stop(request) }
            runBlocking { withTimeout(CANCELLATION_EXIT_MS) { stalled.worker.join() } }
            val exitMs = (SystemClock.elapsedRealtimeNanos() - cancellationStarted) / 1_000_000
            assertEquals(KokoroSpeechStatus.Cancelled, h.tts.requestStatuses.value[request]?.state)
            assertTrue("Cancellation must exit well before the stalled drain budget", exitMs < stalled.timeoutMs)
            assertTrue("Cancelling a drain is not an engine failure", h.tts.state.value is KokoroTtsState.Ready)
            assertNoSuccessfulDrain(request, stalled.sequence)
            awaitRetired(stalled)
            recordLiveNumericObservation("L98", "actual_paused_drain_cancellation",
                "acceptedTargetFrames" to stalled.acceptedFrames, "pausedActualHead" to stalled.pausedHead,
                "workerExitMs" to exitMs, "workerExitBoundMs" to CANCELLATION_EXIT_MS,
                "drainTimeoutMs" to stalled.timeoutMs, "completed" to 0, "explicitCancellation" to 1)
        } finally { fault.close() }

        restartAndRecognize()
        val replacement = h.submissions.last().requestId
        assertNotEquals(request, replacement)
        h.reply(RECOVERY_REPLY)
        h.await("The replacement must own actual playback before stale cancellation") {
            h.tts.playbackPhase.value.let { it is KokoroPlaybackPhase.Playing && it.requestId == replacement }
        }
        h.main { h.tts.stop(request) } // Repeated old-request cancellation must not stop the new track.
        h.drained(replacement)
        assertEquals(KokoroSpeechStatus.Cancelled, h.tts.requestStatuses.value[request]?.state)
        assertEquals(KokoroSpeechStatus.Completed, h.tts.requestStatuses.value[replacement]?.state)
        assertTrue("The cancelled drain worker cannot later time out against a new owner", stalled.worker.isCompleted)
    }

    private data class StalledTrack(
        val track: AudioTrack,
        val worker: Job,
        val sequence: Long,
        val acceptedFrames: Long,
        val pausedHead: Long,
        val sampleRate: Int,
        val pausedAtNanos: Long,
    ) {
        // Exercise the production budget unchanged; cap the real utterance, not its accepted target.
        val timeoutMs: Long get() = acceptedFrames * 1_000 / sampleRate + DRAIN_GRACE_MS
    }

    private inner class TerminalTrackPause(request: String) : Closeable {
        private val reached = CompletableDeferred<StalledTrack>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val observer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            h.tts.playbackPhase.collect { phase ->
                if (phase is KokoroPlaybackPhase.Draining && phase.requestId == request && !reached.isCompleted) {
                    try {
                        val track = engineField("activeTrack") as AudioTrack
                        val worker = engineField("activeSynthesis") as Job
                        val reference = checkNotNull(engineField("activeRenderReference"))
                        val render = reference
                        val accepted = render.javaClass.getDeclaredField("accepted").apply { isAccessible = true }.getLong(render)
                        val sampleRate = track.sampleRate
                        check(track.playState == AudioTrack.PLAYSTATE_PLAYING)
                        track.pause() // Fault injection into actual playback. NEVER flush/reset the head.
                        reached.complete(StalledTrack(track, worker, phase.sequence, accepted,
                            track.playbackHeadPosition.toLong() and 0xffff_ffffL, sampleRate,
                            SystemClock.elapsedRealtimeNanos()))
                    } catch (error: Throwable) { reached.completeExceptionally(error) }
                }
            }
        }
        fun await(): StalledTrack = runBlocking { withTimeout(90_000) { reached.await() } }
        override fun close() { observer.cancel(); scope.cancel() }
    }

    private fun assertStalled(stalled: StalledTrack) {
        assertEquals(AudioTrack.PLAYSTATE_PAUSED, stalled.track.playState)
        assertTrue("Real playback must have progressed before the fault", stalled.pausedHead > 0)
        assertTrue("The actual accepted target must still contain an unplayed tail", stalled.pausedHead < stalled.acceptedFrames)
        assertTrue("The test's unchanged production timeout must be bounded", stalled.timeoutMs <= MAX_DRAIN_BUDGET_MS)
        assertTrue("Actual Kokoro PCM must be energetic, not a fake Playing callback", h.phases.any {
            it is KokoroPlaybackPhase.Playing && it.pcmRms > 0f
        })
    }

    private fun assertNoSuccessfulDrain(request: String, lastSequence: Long) {
        SpeechAudit.query(request).use { rows ->
            val sequence = rows.getColumnIndexOrThrow("phrase_sequence")
            val stage = rows.getColumnIndexOrThrow("stage")
            val detail = rows.getColumnIndexOrThrow("detail")
            while (rows.moveToNext()) {
                assertFalse("An unplayed last phrase must not be audited as drained",
                    rows.getString(stage) == "phrase_drained" && !rows.isNull(sequence) && rows.getLong(sequence) == lastSequence)
                assertFalse("No transient false Completed may hide behind a later terminal status",
                    rows.getString(stage) == "request_terminal" && rows.getString(detail) == KokoroSpeechStatus.Completed.name)
            }
        }
    }

    private fun awaitRetired(stalled: StalledTrack) {
        compose.waitUntil(2_000) {
            stalled.track.state == AudioTrack.STATE_UNINITIALIZED &&
                engineField("activeTrack") !== stalled.track && h.tts.playbackPhase.value == KokoroPlaybackPhase.Idle
        }
    }

    private fun restartAndRecognize() {
        h.main { h.controller.endLive() }
        microphoneStopped()
        runBlocking { h.inputs.last().closeAndJoin() }
        startListening(); spoken("review", 2)
    }

    private fun engineField(name: String): Any? = KokoroTtsEngine::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(h.tts)

    private companion object {
        const val STALLED_REPLY = "This actual native reply must retain its final audible words until playback really drains."
        const val RECOVERY_REPLY = "This fresh reply must play completely after the user explicitly restarts the conversation."
        const val DRAIN_GRACE_MS = 5_000L
        const val MAX_DRAIN_BUDGET_MS = 20_000L
        const val TIMEOUT_OBSERVATION_ALLOWANCE_MS = 2_000L
        const val CANCELLATION_EXIT_MS = 1_500L
        val TERMINAL = setOf(KokoroSpeechStatus.Completed, KokoroSpeechStatus.Failed, KokoroSpeechStatus.Cancelled)
    }
}
