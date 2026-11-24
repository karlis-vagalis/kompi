package com.kompi.app

import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import com.kompi.app.audio.EchoCancellationPolicy
import com.kompi.app.audio.KokoroPlaybackPhase
import com.kompi.app.audio.KokoroSpeechStatus
import com.kompi.app.audio.KokoroTtsEngine
import com.kompi.app.live.LiveConversationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** SDK microphone -> real AudioRecord/Parakeet -> encrypted outbox/Live UI -> Kokoro/AudioTrack.
 * Only the remote reply boundary is controlled. No ASR, text-input or playback callbacks are forged.
 * Missing independent mute controls are enabled acceptance failures, not assumptions/skips.
 */
internal class LiveMuteAcceptanceTest : NativeMicrophoneScenario() {
    private enum class Target { Microphone, Output }

    // Observation only: the engine owns, writes, flushes and releases this actual native track.
    private val activeTrackField = KokoroTtsEngine::class.java.getDeclaredField("activeTrack").apply { isAccessible = true }
    private fun activeTrack() = activeTrackField.get(h.tts) as? AudioTrack
    private fun playbackHead(track: AudioTrack): Long? = runCatching {
        if (track.state == AudioTrack.STATE_INITIALIZED && track.playState == AudioTrack.PLAYSTATE_PLAYING)
            track.playbackHeadPosition.toLong() and 0xffff_ffffL
        else null
    }.getOrNull()

    private fun clickMuteControl(target: Target, mute: Boolean) {
        val microphone = Regex("(?i)\\b(mic|microphone|input|listening|capture)\\b")
        val subject = when (target) {
            Target.Microphone -> microphone
            Target.Output -> Regex("(?i)\\b(output|speakers?|speech|voice|audio|sound|playback|repl(?:y|ies)|answers?|responses?)\\b")
        }
        val operation = if (mute) Regex("(?i)\\b(mute|hold|pause|disable|silence|off)\\b")
            else Regex("(?i)\\b(unmute|resume|enable|restore|unsilence|on|start|listen|play)\\b")
        val namedControl = hasClickAction() and SemanticsMatcher("accessible independent ${target.name.lowercase()} ${if (mute) "mute" else "unmute"}") { node ->
            val name = buildList {
                if (node.config.contains(SemanticsProperties.Text)) addAll(node.config[SemanticsProperties.Text].map { it.text })
                if (node.config.contains(SemanticsProperties.ContentDescription)) addAll(node.config[SemanticsProperties.ContentDescription])
            }.joinToString(" ")
            val state = if (node.config.contains(SemanticsProperties.StateDescription)) node.config[SemanticsProperties.StateDescription] else ""
            // A named switch may expose its state semantically instead of changing its action name.
            val statefulToggle = node.config.contains(SemanticsProperties.ToggleableState) ||
                Regex("(?i)\\b(muted|unmuted|held|paused|on|off|enabled|disabled)\\b").containsMatchIn(state)
            subject.containsMatchIn(name) && (operation.containsMatchIn(name) || statefulToggle) &&
                (target != Target.Output || !microphone.containsMatchIn(name))
        }
        // No fallback to Interrupt, Stop Live, an icon/test tag, or a nonexistent controller API.
        compose.onAllNodes(namedControl).assertCountEquals(1).onFirst()
            .assertIsDisplayed().assertIsEnabled().performClick()
    }

    private fun terminal(request: String) = h.tts.requestStatuses.value[request]?.state in setOf(
        KokoroSpeechStatus.Completed, KokoroSpeechStatus.Cancelled, KokoroSpeechStatus.Failed,
    )

    private fun awaitPlaying(request: String): AudioTrack {
        var observed: AudioTrack? = null
        h.await("Request $request must own non-silent PCM and an advancing actual AudioTrack before the action") {
            val phase = h.tts.playbackPhase.value
            val track = activeTrack()
            if (phase is KokoroPlaybackPhase.Playing && phase.requestId == request && phase.pcmRms > 0f &&
                h.tts.requestStatuses.value[request]?.state == KokoroSpeechStatus.Playing && track != null &&
                (playbackHead(track) ?: 0L) > 0L) observed = track
            observed != null || terminal(request) || h.snapshot.state is LiveConversationState.Failure
        }
        assertNotNull("The requested native reply must actually be playing, not merely queued or completed", observed)
        return checkNotNull(observed)
    }

    /** A sample-clock observation of sustained playback, not a sleep guessing synthesis readiness. */
    private fun awaitMorePlayback(request: String, track: AudioTrack, invariant: () -> Unit = {}) {
        val initial = checkNotNull(playbackHead(track)) { "Actual reply track stopped before observation" }
        val requiredFrames = track.sampleRate.toLong() // One full second, beyond a buffered stop tail.
        var advanced = 0L
        h.await("The same actual reply must continue advancing by $requiredFrames frames") {
            invariant()
            playbackHead(track)?.let { advanced = maxOf(advanced, it - initial) }
            advanced >= requiredFrames || terminal(request) || h.snapshot.state is LiveConversationState.Failure
        }
        assertTrue("Reply stopped instead of continuing: advanced=$advanced required=$requiredFrames", advanced >= requiredFrames)
    }

    private fun assertQuietOutput() {
        val window = audio.quietOutputWindow() // Bounded SDK output observation; never injects microphone PCM.
        assertEquals("Observe the complete bridge-defined window", 2, window.getInt("observationSeconds"))
        assertEquals("No actual output beyond the 200 ms device-buffer allowance", 0, window.getInt("nonzeroAfter200ms"))
    }

    private fun assertAudible(output: JSONObject) {
        assertTrue("The SDK output must contain real rendered speech", output.getInt("nonzero") > 1000)
        assertTrue("Observe more than one second of actual output samples", output.getInt("frames") > 16_000)
    }

    private fun assertDurableIntents(count: Int) {
        val submitted = h.submissions.toList()
        val persisted = runBlocking(Dispatchers.IO) { h.repository.pendingPrompts("native-test-host") }
        assertEquals("Only deliberate corpus utterances may submit", count, submitted.size)
        assertEquals("Each real point has a distinct request ID", count, submitted.map { it.requestId }.toSet().size)
        assertEquals("Each real point must persist exactly once", count, persisted.size)
        assertEquals(submitted.map { it.requestId }.toSet(), persisted.map { it.requestId }.toSet())
        submitted.forEach { turn ->
            assertTrue("A real spoken request needs an immutable nonempty ID", turn.requestId.isNotBlank())
            assertEquals("Persistence must retain this request's actual ASR text", turn.text,
                persisted.single { it.requestId == turn.requestId }.text)
        }
    }

    private fun speakAndPersist(fixture: String, count: Int): VoiceSubmission {
        // In particular, NEVER inject the next fixture to discover whether a muted mic is closed.
        h.await("Actual communication recording must be open before injecting $fixture") { liveRecordings().size == 1 }
        assertEquals(1, manager.activeRecordingConfigurations.size)
        spoken(fixture, count) // Real corpus PCM through the SDK virtual microphone and native decoder.
        assertDurableIntents(count)
        val turn = h.submissions[count - 1]
        assertEquals("The newly recognized intent owns the current reply", turn.requestId, h.snapshot.replyRequestId)
        return turn
    }

    private fun awaitOutputFlushed(request: String) {
        h.await("Output mute must cancel the old local generation and release its actual track") {
            h.tts.requestStatuses.value[request]?.state == KokoroSpeechStatus.Cancelled &&
                h.tts.playbackPhase.value == KokoroPlaybackPhase.Idle && activeTrack() == null
        }
    }

    private fun assertNoReplay(request: String, phaseBoundary: Int) {
        assertEquals(KokoroSpeechStatus.Cancelled, h.tts.requestStatuses.value[request]?.state)
        assertFalse("Muted/unmuted history must never become actual playing PCM again", h.phases.drop(phaseBoundary)
            .filterIsInstance<KokoroPlaybackPhase.Playing>().any { it.requestId == request })
    }

    private fun assertNextReply(turn: VoiceSubmission, text: String) {
        assertEquals(turn.requestId, h.submissions.last().requestId)
        audio.startCapture()
        h.reply(text)
        awaitPlaying(turn.requestId)
        h.drained(turn.requestId)
        assertAudible(audio.stopCapture())
        h.await("The next actual reply must return to eligible capture only after draining") {
            h.snapshot.state == LiveConversationState.Listening
        }
        assertEquals(KokoroSpeechStatus.Completed, h.tts.requestStatuses.value[turn.requestId]?.state)
        assertEquals(turn.requestId, h.snapshot.replyRequestId)
        assertEquals(text, h.snapshot.assistantText)
        compose.onNodeWithText(text).assertIsDisplayed()
        assertDurableIntents(2)
        assertTrue("Native speech queue must not silently fail", h.audioFailures.isEmpty())
    }

    private fun checkpoint(caseId: String, stage: String) = recordLiveNumericObservation(caseId, stage,
        "recordings" to manager.activeRecordingConfigurations.size,
        "liveInputs" to h.inputs.size, "submissions" to h.submissions.size,
        "remoteCancellations" to h.cancellations.size)

    /** A delivery prerequisite on successful RAW ShortArray reads from the exact owned recorder.
     * No ToneGenerator, synthetic microphone frames, host-RMS substitute or VAD tuning is used.
     */
    private fun awaitEnergeticEchoAcquisition(before: NativeAcquisitionSnapshot, request: String, stage: String) {
        var delivered: NativeAcquisitionDelta? = null
        h.await("Actual Android RAW echo must be energetic during $stage, before the original reply drains") {
            val now = h.acquisitionCheckpoint()
            assertEquals("The observed RAW window must retain its actual recorder/session/epoch", before.owner, now.owner)
            if (now.successfulReadSamples - before.successfulReadSamples >= 1_600L) {
                val window = now.deltaSince(before)
                if (window.normalizedRms >= 0.001) delivered = window
            }
            delivered != null || terminal(request) || h.snapshot.state is LiveConversationState.Failure
        }
        val proof = checkNotNull(delivered) { "No energetic RAW Android echo acquisition during $stage" }
        assertEquals("Mute/unmute echo delivery must be proved while the original reply is still playing",
            KokoroSpeechStatus.Playing, h.tts.requestStatuses.value[request]?.state)
        println("REAL_AUDIO_DIRECT_DELIVERY stage=$stage samples=${proof.successfulReadSamples} reads=${proof.successfulReads} rawRms=${proof.normalizedRms} owner=${proof.owner}")
    }


    private fun awaitPublishedMicrophoneMute() {
        // Privacy fencing precedes the actor's selected-state publication. Observe both
        // within the original hardware-stop budget before asserting retained-state invariants.
        compose.waitUntil(3_000) {
            manager.activeRecordingConfigurations.isEmpty() && h.snapshot.microphoneMuted
        }
    }

    private val longReply = "The review is continuing while this answer plays through the real local speech engine. " +
        "There are several details to explain before the final sentence finishes. " +
        "Changing one local audio control must preserve the other independent owner and the public answer. " +
        "The final words remain available for reading after the spoken output has drained."

    @Test fun l09_microphoneMuteDuringRealReplyReleasesCaptureWithoutCancellingOutputAndExplicitUnmuteStartsFreshInput() {
        startListening()
        val first = speakAndPersist("review", 1)
        val originalInput = h.inputs.single()
        val originalSession = liveRecordings().single().clientAudioSessionId
        h.reply(longReply)
        awaitPlaying(first.requestId)
        checkpoint("L09", "before_microphone_mute")

        // Today's UI validly fails here: it has Interrupt/Stop, but no independent microphone mute.
        clickMuteControl(Target.Microphone, mute = true)
        microphoneStopped()
        fun assertMuted() {
            assertTrue("Mute must close actual AudioRecord, not merely change a label", manager.activeRecordingConfigurations.isEmpty())
            assertEquals("There is no automatic replacement capture while muted", 1, h.inputs.size)
            assertTrue("Microphone mute must not cancel the remote reply", h.cancellations.isEmpty())
        }
        assertMuted()
        checkpoint("L09", "microphone_closed_reply_retained")

        // Capture OUTPUT only while the microphone is closed. No input injection occurs here.
        audio.startCapture()
        awaitMorePlayback(first.requestId, awaitPlaying(first.requestId), ::assertMuted)
        h.await("The original real reply must drain successfully while actual capture remains closed") {
            assertMuted()
            terminal(first.requestId) || h.snapshot.state is LiveConversationState.Failure
        }
        assertEquals(KokoroSpeechStatus.Completed, h.tts.requestStatuses.value[first.requestId]?.state)
        assertAudible(audio.stopCapture())
        assertEquals(longReply, h.snapshot.assistantText)
        assertEquals(first.requestId, h.snapshot.replyRequestId)
        compose.onNodeWithText(longReply).assertIsDisplayed()
        assertQuietOutput()
        assertMuted()
        assertDurableIntents(1)
        checkpoint("L09", "reply_drained_with_microphone_still_closed")

        clickMuteControl(Target.Microphone, mute = false)
        h.await("Explicit unmute must establish fresh real recording and utterance ownership") {
            h.inputs.size == 2 && liveRecordings().size == 1 && h.snapshot.state == LiveConversationState.Listening
        }
        assertNotSame("Unmute must not reuse the fenced input owner", originalInput, h.inputs.last())
        assertNotEquals("A fresh actual AudioRecord session is required", originalSession, liveRecordings().single().clientAudioSessionId)
        assertEquals(1, manager.activeRecordingConfigurations.size)
        assertEquals(longReply, h.snapshot.assistantText)
        assertTrue(h.cancellations.isEmpty())
        assertDurableIntents(1)
        checkpoint("L09", "explicit_unmute_fresh_capture_ready")

        val next = speakAndPersist("negation", 2)
        assertNotEquals(first.requestId, next.requestId)
        assertNotEquals("The next actual corpus instruction is a different intent", first.text, next.text)
        assertNextReply(next, "I will not run that command after unmuting the microphone.")
        assertEquals(2, h.inputs.size)
        assertTrue("Mute, unmute and the completed original reply must not cause remote cancellation", h.cancellations.isEmpty())
        h.main { h.controller.endLive() }
        microphoneStopped()
        checkpoint("L09", "next_intent_persisted_reply_drained_and_ended")
    }

    @Test fun l09_microphoneMuteThroughEnergeticEchoPreservesHistoryAndExplicitUnmuteDuringReplyDoesNotSelfInterrupt() {
        startListening()
        val first = speakAndPersist("review", 1)
        val originalInput = h.inputs.single()
        val originalSession = liveRecordings().single().clientAudioSessionId
        val originalAcquisition = h.acquisitionCheckpoint()
        assertEquals("RAW acquisition must belong to the actual live recording", originalSession, originalAcquisition.owner.audioSessionId)

        fun assertOnlyOriginalIntent() {
            assertEquals("Far-end echo must not create a new speech onset", 1, onsets.size)
            assertEquals("Only the deliberate seed speech may submit an instruction", 1, h.submissions.size)
            assertEquals(first.requestId, h.submissions.single().requestId)
            assertTrue("Microphone mute/unmute must never remotely cancel the answer", h.cancellations.isEmpty())
        }

        audio.startEcho()
        var failure: Throwable? = null
        try {
            h.reply(longReply)
            val track = awaitPlaying(first.requestId)
            // The seed has already completed and this answer's actual track is playing. Only new
            // successful RAW reads can satisfy this window; lifetime seed energy is excluded.
            val beforeEcho = h.acquisitionCheckpoint()
            assertEquals("Echo must reach the original post-seed acquisition owner", originalAcquisition.owner, beforeEcho.owner)
            awaitEnergeticEchoAcquisition(beforeEcho, first.requestId, "before_microphone_mute_echo")
            assertOnlyOriginalIntent()

            clickMuteControl(Target.Microphone, mute = true)
            awaitPublishedMicrophoneMute()
            fun assertMutedWhileOriginalOutputPlays() {
                assertTrue("Privacy mute must close every actual recording", manager.activeRecordingConfigurations.isEmpty())
                assertTrue(h.snapshot.microphoneMuted)
                assertFalse("Input mute must not mute independent output", h.snapshot.outputMuted)
                assertEquals("Mute cannot create a hidden replacement capture", 1, h.inputs.size)
                assertSame(originalInput, h.inputs.single())
                assertSame("The original actual output track must survive microphone mute", track, activeTrack())
                assertEquals(KokoroSpeechStatus.Playing, h.tts.requestStatuses.value[first.requestId]?.state)
                assertOnlyOriginalIntent()

            }

            val initialHead = checkNotNull(playbackHead(track)) { "Reply ended before muted playback observation" }
            val requiredFrames = track.sampleRate.toLong() * 5 / 4 // 1.25 actual output seconds, not a sleep.
            var advanced = 0L
            h.await("With recordings=0, the same real AudioTrack must advance for MORE than one second") {
                assertMutedWhileOriginalOutputPlays()
                playbackHead(track)?.let { advanced = maxOf(advanced, it - initialHead) }
                advanced >= requiredFrames
            }
            assertTrue(advanced >= requiredFrames)
            assertMutedWhileOriginalOutputPlays()

            checkpoint("L09", "energetic_echo_microphone_closed_output_advanced")

            // Explicitly unmute WHILE the SAME answer/AudioTrack still owns output, not after drain.
            clickMuteControl(Target.Microphone, mute = false)
            var resumedAcquisition: NativeAcquisitionSnapshot? = null
            h.await("Explicit unmute during playback must establish a fresh actual recording and RAW producer") {
                assertEquals("The original answer must not drain or cancel before unmute completes",
                    KokoroSpeechStatus.Playing, h.tts.requestStatuses.value[first.requestId]?.state)
                assertOnlyOriginalIntent()
                if (h.inputs.size == 2 && liveRecordings().size == 1 && !h.snapshot.microphoneMuted) {
                    resumedAcquisition = runCatching { h.acquisitionCheckpoint() }.getOrNull()
                }
                resumedAcquisition != null || h.snapshot.state is LiveConversationState.Failure
            }
            val resumed = checkNotNull(resumedAcquisition)
            assertNotSame("Unmute must not reuse the fenced input owner", originalInput, h.inputs.last())
            assertNotEquals("Unmute needs a fresh real AudioRecord session", originalSession, liveRecordings().single().clientAudioSessionId)
            assertEquals("Fresh RAW acquisition must belong to the actual live recording",
                liveRecordings().single().clientAudioSessionId, resumed.owner.audioSessionId)
            assertNotEquals("Unmute needs a fresh actual recorder", originalAcquisition.owner.recordIdentity, resumed.owner.recordIdentity)
            assertNotEquals("Unmute needs fresh capture-request ownership", originalAcquisition.owner.requestEpoch, resumed.owner.requestEpoch)
            assertEquals("Fresh capture must still belong to the retained duplex session", originalAcquisition.owner.duplexEpoch, resumed.owner.duplexEpoch)
            assertSame(track, activeTrack())
            assertEquals(1, manager.activeRecordingConfigurations.size)
            awaitEnergeticEchoAcquisition(resumed, first.requestId, "unmuted_during_same_reply_echo")
            assertOnlyOriginalIntent()
            checkpoint("L09", "fresh_capture_energetic_echo_original_output_retained")

            h.drained(first.requestId)
            assertEquals("The resumed recorder must remain the owner through the reply drain", resumed.owner, h.acquisitionCheckpoint().owner)
            assertEquals(KokoroSpeechStatus.Completed, h.tts.requestStatuses.value[first.requestId]?.state)
            assertEquals(longReply, h.snapshot.assistantText)
            assertEquals(first.requestId, h.snapshot.replyRequestId)
            compose.onNodeWithText(longReply).assertIsDisplayed()
            assertOnlyOriginalIntent()
            assertDurableIntents(1)
        } catch (error: Throwable) { failure = error }
        try {
            val evidence = audio.stopEcho()
            assertTrue("The real echo path must submit energetic rendered speech", evidence.getInt("injectedNonzero") > 1000)
            assertTrue(evidence.getDouble("injectedPeakRms") > 0.01)
            assertEquals(0.8, evidence.getDouble("echoGain"), 0.0001)
            assertEquals(100, evidence.getInt("minimumHostDelayMs"))
        } catch (error: Throwable) {
            val original = failure
            if (original == null) failure = error else original.addSuppressed(error)
        }
        failure?.let { throw it }
        audio.inject("silence")
        assertOnlyOriginalIntent()
        assertDurableIntents(1)
        assertEquals(2, h.inputs.size)
        assertEquals(1, manager.activeRecordingConfigurations.size)
        assertTrue("The real mute/echo journey must not conceal native failures", h.audioFailures.isEmpty())
        h.main { h.controller.endLive() }
        microphoneStopped()
        checkpoint("L09", "unmuted_during_reply_echo_drained_and_ended")
    }

    @Test fun l09_microphoneMuteRetainsQuiescentOwnerForEightSecondsAfterReplyDrainUntilExplicitUnmute() {
        startListening()
        val first = speakAndPersist("review", 1)
        val originalInput = h.inputs.single()
        val originalSession = liveRecordings().single().clientAudioSessionId
        val originalAcquisition = h.acquisitionCheckpoint()

        fun assertPlatformCapture(snapshot: NativeAcquisitionSnapshot) {
            assertEquals("The actual recorder must use the owned communication source",
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, snapshot.owner.audioSource)
            val effects = checkNotNull(snapshot.effects) { "Actual current recorder effect-control metadata is required" }
            assertTrue("Effect observations must belong to this exact recorder/session/request", effects.matches(snapshot.owner))
            assertTrue(effects.policy == EchoCancellationPolicy.Required.ordinal || effects.policy == EchoCancellationPolicy.Optional.ordinal)
            assertEquals("Effect setup must really be ready", 1, effects.phase)
            assertEquals(0, effects.failureEffect)
            assertEquals(0, effects.failureStage)
            assertEquals(0, effects.unsafeMask)
            if (effects.policy == EchoCancellationPolicy.Required.ordinal) assertEquals(1, effects.aecEnabled)
        }
        assertEquals(originalSession, originalAcquisition.owner.audioSessionId)
        assertPlatformCapture(originalAcquisition)

        fun assertMutedOwnerRetained() {
            assertTrue("User mute must keep every actual recording closed", manager.activeRecordingConfigurations.isEmpty())
            assertTrue("The user-requested microphone mute must remain selected", h.snapshot.microphoneMuted)
            assertFalse("Microphone mute must not select output mute", h.snapshot.outputMuted)
            assertEquals("No capture may restart automatically while muted", 1, h.inputs.size)
            assertSame(originalInput, h.inputs.single())
            assertEquals("Only the actual seed speech may create an onset", 1, onsets.size)
            assertEquals(1, h.submissions.size)
            assertEquals(first.requestId, h.submissions.single().requestId)
            assertTrue("Quiescent microphone mute must not remotely cancel work", h.cancellations.isEmpty())
            assertFalse("A legitimate input/output-idle framework mode change must not fail Live",
                h.snapshot.state is LiveConversationState.Failure)
            assertNotEquals("Quiescence is not an implicit End", LiveConversationState.Idle, h.snapshot.state)
            assertNotEquals("Quiescence must not locally stop the retained owner", LiveConversationState.Interrupted, h.snapshot.state)
            assertNotEquals("Quiescence must not pause the retained owner", LiveConversationState.Paused, h.snapshot.state)
            assertEquals(first.requestId, h.snapshot.replyRequestId)
            assertEquals(longReply, h.snapshot.assistantText)
            assertTrue("No native failure may be hidden while the user is muted", h.audioFailures.isEmpty())
        }

        h.reply(longReply)
        val originalTrack = awaitPlaying(first.requestId)
        clickMuteControl(Target.Microphone, mute = true)
        awaitPublishedMicrophoneMute()
        assertMutedOwnerRetained()
        audio.startCapture() // Output-only observation; no microphone PCM is injected while muted.
        awaitMorePlayback(first.requestId, originalTrack, ::assertMutedOwnerRetained)
        h.await("The original real answer must drain while actual microphone capture stays closed") {
            assertMutedOwnerRetained()
            terminal(first.requestId) || h.snapshot.state is LiveConversationState.Failure
        }
        assertEquals(KokoroSpeechStatus.Completed, h.tts.requestStatuses.value[first.requestId]?.state)
        assertAudible(audio.stopCapture())
        h.await("A drained answer must release its actual output track without releasing the logical Live owner") {
            assertMutedOwnerRetained()
            activeTrack() == null && h.tts.playbackPhase.value == KokoroPlaybackPhase.Idle
        }
        compose.onNodeWithText(longReply).assertIsDisplayed()
        assertDurableIntents(1)

        val phaseBoundary = h.phases.size
        val quiescentStartNanos = SystemClock.elapsedRealtimeNanos()

        // Four DEFINED two-second output measurements exercise the framework's >6-second
        // no-active-input/output transition. This is measured silent quiescence, not an arbitrary
        // sleep, a JNI-completion guess, a retry extension, or fake microphone input.
        repeat(4) { index ->
            assertMutedOwnerRetained()
            val window = audio.quietOutputWindow()
            assertEquals("Each quiescent observation must retain the defined two-second window", 2, window.getInt("observationSeconds"))
            assertTrue("An empty output observation cannot prove silence", window.getInt("frames") > 16_000)
            assertEquals("No output may recur beyond the defined device-buffer tail", 0, window.getInt("nonzeroAfter200ms"))
            if (index > 0) assertEquals("After the first measured buffer-tail window, all output must remain silent", 0, window.getInt("nonzero"))
            assertMutedOwnerRetained()
            assertNull("No actual output track may restart during quiescence", activeTrack())
            assertEquals(KokoroPlaybackPhase.Idle, h.tts.playbackPhase.value)
            assertFalse("Completed output must not replay or create surprise generated PCM", h.phases.drop(phaseBoundary)
                .any { it is KokoroPlaybackPhase.Playing })

            recordLiveNumericObservation("L09", "quiescent_output_window_${index + 1}",
                "elapsedNanos" to SystemClock.elapsedRealtimeNanos() - quiescentStartNanos,
                "observedMode" to manager.mode, "recordings" to manager.activeRecordingConfigurations.size,
                "outputFrames" to window.getInt("frames"), "outputNonzero" to window.getInt("nonzero"))
        }
        val elapsedNanos = SystemClock.elapsedRealtimeNanos() - quiescentStartNanos
        assertTrue("Four measured quiet windows must span at least eight monotonic seconds: elapsedNanos=$elapsedNanos",
            elapsedNanos >= 8_000_000_000L)
        assertMutedOwnerRetained()
        assertDurableIntents(1)
        checkpoint("L09", "eight_seconds_quiescent_owner_retained")

        // Only this explicit user action may reacquire physical communication ownership/capture.
        clickMuteControl(Target.Microphone, mute = false)
        var resumedAcquisition: NativeAcquisitionSnapshot? = null
        h.await("Explicit unmute after quiescence must create a fresh actual recording and ready RAW producer") {
            assertTrue("Unmute must not cancel the completed remote point", h.cancellations.isEmpty())
            if (h.inputs.size == 2 && liveRecordings().size == 1 && !h.snapshot.microphoneMuted &&
                h.snapshot.state == LiveConversationState.Listening) {
                resumedAcquisition = runCatching { h.acquisitionCheckpoint() }.getOrNull()
            }
            resumedAcquisition != null || h.snapshot.state is LiveConversationState.Failure
        }
        val resumed = checkNotNull(resumedAcquisition)
        assertNotSame("Unmute must construct a fresh input owner", originalInput, h.inputs.last())
        assertEquals(1, manager.activeRecordingConfigurations.size)
        assertNotEquals("Unmute needs a fresh actual recording session", originalSession, liveRecordings().single().clientAudioSessionId)
        assertEquals(liveRecordings().single().clientAudioSessionId, resumed.owner.audioSessionId)
        assertNotEquals(originalAcquisition.owner.recordIdentity, resumed.owner.recordIdentity)
        assertNotEquals(originalAcquisition.owner.requestEpoch, resumed.owner.requestEpoch)
        assertEquals("Quiescence must retain the logical duplex epoch", originalAcquisition.owner.duplexEpoch, resumed.owner.duplexEpoch)
        assertPlatformCapture(resumed)
        assertTrue(h.cancellations.isEmpty())

        val next = speakAndPersist("negation", 2)
        assertNotEquals(first.requestId, next.requestId)
        assertNotEquals(first.text, next.text)
        val deliveredNegation = h.acquisitionDeltaSince(resumed)
        assertTrue("The next real instruction must reach the fresh recorder as energetic RAW reads",
            deliveredNegation.successfulReadSamples >= 1_600L && deliveredNegation.normalizedRms >= 0.001)
        assertNextReply(next, "I will not run that command after explicitly unmuting the microphone.")
        assertEquals("The new recording remains the actual owner through recognition and reply drain", resumed.owner, h.acquisitionCheckpoint().owner)
        assertEquals(2, onsets.size)
        assertEquals(2, h.inputs.size)
        assertTrue("Mute, quiescence, explicit unmute and the distinct next point must not remotely cancel either completed reply", h.cancellations.isEmpty())
        h.main { h.controller.endLive() }
        microphoneStopped()
        checkpoint("L09", "quiescent_unmute_next_intent_persisted_and_ended")
    }

    @Test fun l10_outputMuteFlushesRealAudioButKeepsCaptureAndTextWithoutRemoteCancelOrUnmuteReplay() {
        startListening()
        val first = speakAndPersist("review", 1)
        val originalInput = h.inputs.single()
        val originalSession = liveRecordings().single().clientAudioSessionId
        fun assertOriginalCaptureStillOpen() {
            assertEquals("Output controls must not construct replacement microphone input", 1, h.inputs.size)
            assertSame(originalInput, h.inputs.single())
            assertEquals(1, manager.activeRecordingConfigurations.size)
            assertEquals("The original actual recorder must remain open", originalSession, liveRecordings().single().clientAudioSessionId)
        }
        runBlocking(Dispatchers.IO) { withTimeout(90_000) { h.tts.initialize().getOrThrow() } }
        audio.startCapture()
        // Keep the controlled REMOTE stream open so new public clauses really arrive while muted.
        h.main { assertTrue(h.speech.accept(first.requestId, "native-conversation", 0L, longReply)) }
        awaitMorePlayback(first.requestId, awaitPlaying(first.requestId), ::assertOriginalCaptureStillOpen)
        assertAudible(audio.stopCapture())
        awaitPlaying(first.requestId)
        checkpoint("L10", "before_output_mute")

        // Today's missing accessible output control is an enabled red gate, not a skipped phase.
        clickMuteControl(Target.Output, mute = true)
        awaitOutputFlushed(first.requestId)
        assertQuietOutput()
        assertOriginalCaptureStillOpen()
        assertTrue("Output mute is not remote cancellation", h.cancellations.isEmpty())
        assertEquals(first.requestId, h.snapshot.replyRequestId)
        assertEquals(longReply, h.snapshot.assistantText)
        checkpoint("L10", "old_audio_flushed_capture_unchanged")

        val mutedPhaseBoundary = h.phases.size
        val continuation = " Further public text continues to arrive while spoken output is muted."
        val completedText = longReply + continuation
        // The real stream/planner boundary, not a forged controller text or audio callback. These
        // clauses would produce Kokoro PCM if the muted generation were not properly fenced.
        h.main {
            assertTrue("Local output mute must not discard the remote text stream",
                h.speech.accept(first.requestId, "native-conversation", 1L, continuation))
            h.speech.finish(first.requestId, "native-conversation", completedText)
        }
        h.await("Continued remote public text must remain visible independently of local output") {
            h.snapshot.assistantText == completedText && h.snapshot.replyRequestId == first.requestId
        }
        compose.onNodeWithText(completedText).assertIsDisplayed()
        awaitOutputFlushed(first.requestId)
        assertQuietOutput()
        assertNoReplay(first.requestId, mutedPhaseBoundary)
        assertOriginalCaptureStillOpen()
        assertTrue(h.cancellations.isEmpty())
        assertDurableIntents(1)
        checkpoint("L10", "muted_remote_continuation_visible_and_quiet")

        clickMuteControl(Target.Output, mute = false)
        assertQuietOutput()
        assertNoReplay(first.requestId, mutedPhaseBoundary)
        assertOriginalCaptureStillOpen()
        assertEquals(completedText, h.snapshot.assistantText)
        compose.onNodeWithText(completedText).assertIsDisplayed()
        assertTrue("Neither output mute nor output unmute may cancel remote work", h.cancellations.isEmpty())
        assertDurableIntents(1)
        checkpoint("L10", "unmuted_without_history_replay")

        val next = speakAndPersist("negation", 2)
        assertNotEquals(first.requestId, next.requestId)
        assertNotEquals(first.text, next.text)
        assertOriginalCaptureStillOpen()
        assertNextReply(next, "I will not run that command. The next spoken reply works after unmuting output.")
        assertNoReplay(first.requestId, mutedPhaseBoundary)
        assertOriginalCaptureStillOpen()
        // Only this NEW deliberate speech onset may interrupt a still-owned original remote turn;
        // no mute/unmute above did so, and neither that onset nor late work may cancel the new ID.
        assertTrue("A later speech interruption may target only the original request, at most once",
            h.cancellations.size <= 1 && h.cancellations.all { it == first.requestId })
        h.main { h.controller.endLive() }
        microphoneStopped()
        checkpoint("L10", "next_intent_persisted_reply_drained_and_ended")
    }
}
