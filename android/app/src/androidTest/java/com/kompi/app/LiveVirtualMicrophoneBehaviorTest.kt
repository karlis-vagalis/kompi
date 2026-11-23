package com.kompi.app

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Base64
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithText
import com.kompi.app.audio.*
import com.kompi.app.live.LiveConversationState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** SDK virtual mic → actual AudioRecord/LocalLiveSpeechInput → real ASR/Live → native output.
 * Requires the explicit authenticated host bridge; absence fails, never skips or injects transcripts. */
internal class LiveVirtualMicrophoneBehaviorTest : NativeMicrophoneScenario() {
    @Test fun virtualMicrophoneRecognizesSpokenPointAndRealReplyReachesEmulatorOutput() {
        audio.startCapture()
        startListening()
        spoken("review", 1)
        val request = h.submissions.single().requestId
        h.reply("The build finished successfully."); h.drained(request)
        val output = audio.stopCapture()
        assertTrue("Actual emulator output must contain non-silent generated speech", output.getInt("nonzero") > 1000)
        assertTrue(output.getInt("frames") > 16_000)
        compose.onNodeWithText("The build finished successfully.").assertIsDisplayed()
        assertEquals(1, h.inputs.size)
        // Verify actual rendered words, not merely nonzero PCM or a declared playback phase.
        h.main { h.controller.endLive() }; microphoneStopped()
        runBlocking { h.inputs.last().closeAndJoin() }
        val captured = File(h.context.cacheDir, "native-live-captured-reply.wav")
        try {
            captured.writeBytes(Base64.decode(output.getString("wavBase64"), Base64.DEFAULT))
            val recognized = runBlocking(Dispatchers.IO) {
                val decoder = SpeechRecognitionModel.Parakeet(LiveAudioCorpus.model(h.context)).openDecoder(h.context, null, com.kompi.app.audio.whistle.WhistleVocabulary.Empty)
                decoder.use {
                    val pcm = LiveAudioCorpus.read16kMono(captured)
                    for (offset in pcm.indices step decoder.chunkSamples) decoder.transcribe(pcm.copyOfRange(offset, minOf(offset + decoder.chunkSamples, pcm.size)))
                    decoder.finish().committedText
                }
            }
            assertTrue("Actual emulator-rendered speech must preserve the reply's words: $recognized", LiveAudioCorpus.words(recognized).containsAll(setOf("build", "finished", "successfully")))
        } finally { captured.delete() }
    }

    @Test fun virtualMicrophoneBargeInCancelsNativePlaybackAndRetainsNewWords() {
        startListening(); spoken("review", 1)
        val first = h.submissions.single().requestId
        h.reply("I am reviewing the changes carefully. There are several details in this answer that will continue while you add another point to this conversation.")
        h.await("Real reply must start before virtual-mic interruption") { h.tts.playbackPhase.value is KokoroPlaybackPhase.Playing }
        spoken("interrupt", 2)
        h.await("Old actual output must be cancelled") { h.tts.requestStatuses.value[first]?.state == KokoroSpeechStatus.Cancelled }
        assertEquals(listOf(first), h.cancellations)
        assertEquals(1, h.inputs.size)
        h.reply("I will explain the error instead."); h.drained(h.submissions.last().requestId)
    }

    @Test fun virtualMicrophoneReadyCueOccursOnceNotForEverySilentFrame() {
        audio.startCapture(); startListening(); audio.inject("silence")
        val initial = audio.stopCapture()
        assertTrue("First actual capture frame must produce the bundled ready chime", initial.getInt("nonzero") > 100)
        audio.startCapture(); audio.inject("silence")
        val repeated = audio.stopCapture()
        assertEquals("Silence and subsequent frames must not replay the ready cue", 0, repeated.getInt("nonzero"))
        assertTrue(h.submissions.isEmpty())
    }

    @Test fun repeatedIntentAcrossVoicesSpeedAndGainRemainsFourDistinctSpokenTurns() {
        startListening()
        for ((index, fixture) in listOf("review", "review-fast", "review-soft", "review-male").withIndex()) {
            spoken(fixture, index + 1)
            h.reply("Okay, I will wait."); h.drained(h.submissions.last().requestId)
        }
        assertEquals(4, h.submissions.map { it.requestId }.toSet().size)
        assertEquals(1, h.inputs.size)
        assertEquals(1, liveRecordings().size)
        assertTrue(h.cancellations.isEmpty())
    }

    @Test fun shortNonSpeechEnergyBurstDoesNotInterruptActualSpokenReply() {
        startListening(); spoken("review", 1)
        val request = h.submissions.single().requestId
        h.reply("I am still reviewing the changes. A brief non speech sound must not interrupt this spoken answer or create another instruction to the agent.")
        h.await("Native reply must actually play before the energy burst") { h.tts.playbackPhase.value is KokoroPlaybackPhase.Playing }
        audio.inject("tone-burst"); h.drained(request)
        assertEquals(1, h.submissions.size)
        assertEquals(1, onsets.size)
        assertTrue(h.cancellations.isEmpty())
    }

    @Test fun actualRenderedSpeakerLoopbackDoesNotSelfInterruptOrSubmitAnInstruction() {
        startListening(); spoken("review", 1)
        val acquisitionOwner = h.acquisitionCheckpoint().owner
        val request = h.submissions.single().requestId
        audio.startEcho()
        var drainFailure: Throwable? = null
        val evidence: org.json.JSONObject
        try {
            h.reply("This is a deliberately long spoken answer that is fed back through the virtual microphone as speaker echo, not a new user instruction. The reply should finish without interrupting itself.")
            try {
                awaitEnergeticRenderedAcquisition(request, acquisitionOwner)
                h.drained(request)
            } catch (error: Throwable) { drainFailure = error }
        } finally { evidence = audio.stopEcho() }
        assertTrue("Loopback must actually inject energetic rendered PCM", evidence.getInt("injectedNonzero") > 1000)
        assertTrue(evidence.getDouble("injectedPeakRms") > 0.01)
        if (drainFailure != null) throw AssertionError("Rendered-output loopback did not drain; actualOnsets=${onsets.size}, injectedPeakRms=${evidence.getDouble("injectedPeakRms")}", drainFailure)
        audio.inject("silence")
        assertEquals(1, onsets.size)
        assertEquals(1, h.submissions.size)
        assertTrue(h.cancellations.isEmpty())
    }

    /** Prove actual successful RAW AudioRecord reads during THIS echo reply, not seed energy,
     * APM output, a host queue write, or a nonzero lifetime peak from the preceding instruction.
     * The identity-scoped delta rejects recorder/session/epoch replacement instead of rebasing.
     */
    private fun awaitEnergeticRenderedAcquisition(request: String, acquisitionOwner: NativeAcquisitionOwner) {
        val field = KokoroTtsEngine::class.java.getDeclaredField("activeTrack").apply { isAccessible = true }
        var before: NativeAcquisitionSnapshot? = null
        var delivered: NativeAcquisitionDelta? = null
        h.await("Rendered echo must reach the same actual Android recorder while its reply is still playing") {
            val phase = h.tts.playbackPhase.value
            val track = field.get(h.tts) as? AudioTrack
            val playing = phase is KokoroPlaybackPhase.Playing && phase.requestId == request && phase.pcmRms > 0f &&
                track != null && runCatching {
                    track.state == AudioTrack.STATE_INITIALIZED && track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                        (track.playbackHeadPosition.toLong() and 0xffff_ffffL) > 0L
                }.getOrDefault(false)
            if (playing) {
                val now = h.acquisitionCheckpoint()
                assertEquals("The echo journey must keep its original post-seed acquisition owner", acquisitionOwner, now.owner)
                val start = before
                if (start == null) before = now
                else {
                    assertEquals("Echo delivery cannot switch the owned recorder/session/epoch", start.owner, now.owner)
                    if (now.successfulReadSamples - start.successfulReadSamples >= 1_600L) {
                        val window = now.deltaSince(start)
                        if (window.normalizedRms >= 0.001) delivered = window
                    }
                }
            }
            delivered != null || h.tts.requestStatuses.value[request]?.state in setOf(
                KokoroSpeechStatus.Completed, KokoroSpeechStatus.Cancelled, KokoroSpeechStatus.Failed,
            ) || h.snapshot.state is LiveConversationState.Failure
        }
        val proof = checkNotNull(delivered) { "Loopback lacked energetic Android RAW acquisition after seed speech" }
        assertEquals("Delivery must be proved before the real echo reply drains", KokoroSpeechStatus.Playing,
            h.tts.requestStatuses.value[request]?.state)
        println("REAL_AUDIO_DIRECT_DELIVERY stage=rendered_echo_only samples=${proof.successfulReadSamples} reads=${proof.successfulReads} rawRms=${proof.normalizedRms} owner=${proof.owner}")
    }

    @Test fun rapidActualCaptureStartEndAndRestartHasOneOwnerAndNoSurpriseTail() {
        repeat(3) {
            h.start(); h.main { h.controller.endLive() }
            microphoneStopped()
        }
        assertTrue(h.submissions.isEmpty())
        startListening(); spoken("continue", 1)
        assertEquals(1, liveRecordings().size)
        h.main { h.controller.endLive() }; microphoneStopped()
    }

    @Test fun connectionLossActuallyClosesMicrophoneBeforeRecovery() {
        startListening(); spoken("review", 1)
        assertEquals(1, liveRecordings().size)
        h.main { h.controller.onConnectionLost() }
        microphoneStopped() // Immediate hardware privacy is independent of queued actor cleanup.
        h.await("Connection-loss cleanup must publish Paused after real hardware has stopped") {
            h.snapshot.state == LiveConversationState.Paused || h.snapshot.state is LiveConversationState.Failure
        }
        assertEquals(LiveConversationState.Paused, h.snapshot.state)
        val count = h.inputs.size
        h.main { h.controller.onContextChanged("native-test-host:conversation") }
        assertEquals(count, h.inputs.size)
        assertTrue(liveRecordings().isEmpty())
        startListening(); spoken("continue", 2)
    }

    @Test fun actualActivityBackgroundStopsCaptureAndResumeDoesNotRestartIt() {
        startListening(); assertEquals(1, liveRecordings().size)
        try {
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            microphoneStopped()
        } finally { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        compose.waitForIdle()
        assertTrue(liveRecordings().isEmpty())
        assertEquals(1, h.inputs.size)
        assertTrue(h.submissions.isEmpty())
        startListening(); spoken("continue", 1)
    }

    @Test fun backgroundStopsActualPlaybackButDoesNotCancelAcceptedRemotePoint() {
        startListening(); spoken("review", 1)
        h.reply("This accepted review continues remotely while you leave the screen, but the local spoken answer must stop when the activity goes into the background.")
        h.await("Native answer must play before background") { h.tts.playbackPhase.value is KokoroPlaybackPhase.Playing }
        try {
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            microphoneStopped()
            assertTrue("Passive background must not issue remote cancellation", h.cancellations.isEmpty())
        } finally { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
    }

    @Test fun detectedRouteFaultDuringNativePlaybackActuallyReleasesCaptureAndAudio() {
        startListening(); spoken("review", 1)
        val request = h.submissions.single().requestId
        h.reply("This native spoken answer must stop if the capture reader detects that its route is no longer safe. The microphone and local output must really be released.")
        h.await("Native reply must play before injected route fault") { h.tts.playbackPhase.value is KokoroPlaybackPhase.Playing }
        NativeRouteFault.loseCurrentRoute()
        microphoneStopped()
        compose.waitUntil(3_000) { h.tts.requestStatuses.value[request]?.state == KokoroSpeechStatus.Cancelled }
        assertEquals(0, audio.quietOutputWindow().getInt("nonzeroAfter200ms"))
        assertEquals(1, h.inputs.size)
    }

    @Test fun actualAudioFocusLossClosesCaptureAndRegainDoesNotResumeIt() {
        startListening()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener {}.build()
        try {
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(focus))
            microphoneStopped() // Immediate hardware revocation must precede logical/UI cleanup.
            h.await("Focus loss must publish an explicit recoverable terminal state", allowFailure = true) {
                h.snapshot.state is LiveConversationState.Failure
            }
            compose.onNodeWithText("Start Live").assertIsDisplayed().assertIsEnabled()
        } finally { manager.abandonAudioFocusRequest(focus) }
        compose.waitForIdle()
        assertTrue(liveRecordings().isEmpty())
        assertEquals("Returning focus must not create another capture owner", 1, h.inputs.size)
        assertTrue(h.snapshot.state is LiveConversationState.Failure)
        compose.onNodeWithText("Start Live").assertIsDisplayed().assertIsEnabled()
        startListening(); spoken("continue", 1)
    }

    @Test fun minimumCommunicationVolumeIsNotBoostedAndActualPlaybackStillDrains() {
        // Live uses USAGE_VOICE_COMMUNICATION, not the unrelated media-volume slider.
        val stream = AudioManager.STREAM_VOICE_CALL
        val volume = manager.getStreamVolume(stream)
        val minimum = manager.getStreamMinVolume(stream)
        try {
            startListening(); spoken("review", 1)
            manager.setStreamVolume(stream, minimum, 0)
            audio.startCapture()
            h.reply("I will review the changes."); h.drained(h.submissions.single().requestId)
            val output = audio.stopCapture()
            assertEquals(minimum, manager.getStreamVolume(stream))
            if (minimum == 0) assertEquals("A genuinely zero-volume speech stream must remain silent", 0, output.getInt("nonzero"))
        } finally { manager.setStreamVolume(stream, volume, 0) }
    }

    @Test fun preexistingCommunicationModeIsNotStolenAndDoesNotOpenCaptureOrCue() {
        val mode = manager.mode
        try {
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            audio.startCapture(); h.start()
            h.await("Another communication owner must prevent Live capture", allowFailure = true) { h.snapshot.state is LiveConversationState.Failure }
            assertTrue((h.snapshot.state as LiveConversationState.Failure).message.contains("call", ignoreCase = true))
            assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
            assertTrue(liveRecordings().isEmpty())
            assertEquals(0, audio.stopCapture().getInt("nonzero"))
        } finally { manager.mode = mode }
    }

    @Test fun realBargeInFlushesOutputWhileRemoteCancellationIsStillBlocked() {
        val gate = CompletableDeferred<Unit>(); h.remoteCancellationGate = gate
        try {
            startListening(); spoken("review", 1)
            val old = h.submissions.single().requestId
            h.reply("This is a deliberately long answer that continues speaking while a second point interrupts it. Remote cancellation is intentionally blocked and must never delay the actual local audio stop.")
            h.await("Actual native answer must start") { h.tts.playbackPhase.value is KokoroPlaybackPhase.Playing }
            spoken("interrupt", 2)
            h.await("Remote cancellation must be invoked only after local stop") { h.cancellationStartedAt.containsKey(old) }
            assertFalse(gate.isCompleted)
            val onsetToCancelMs = (h.cancellationStartedAt.getValue(old) - onsets.last()) / 1_000_000
            assertTrue("Native onset to local stop/cancel dispatch must be bounded: $onsetToCancelMs ms", onsetToCancelMs in 0..1000)
            audio.startCapture(); audio.inject("silence")
            val output = audio.stopCapture()
            assertEquals("No old output beyond a 200 ms device-buffer allowance while remote cancel is blocked", 0, output.getInt("nonzeroAfter200ms"))
            assertEquals(KokoroSpeechStatus.Cancelled, h.tts.requestStatuses.value[old]?.state)
            audio.checkpoint("barge-onset-to-stop-ms-$onsetToCancelMs")
            println("REAL_AUDIO_BARGE_IN onsetToLocalStopThenCancelDispatchMs=$onsetToCancelMs remoteStillBlocked=true")
        } finally { gate.complete(Unit) }
    }
}
