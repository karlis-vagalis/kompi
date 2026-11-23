package com.kompi.app

import android.media.AudioTrack
import com.kompi.app.audio.KokoroPlaybackPhase
import com.kompi.app.audio.KokoroSpeechStatus
import com.kompi.app.audio.KokoroTtsEngine
import com.kompi.app.live.LiveConversationState
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual AudioRecord/Parakeet/Live/outbox/Kokoro/AudioTrack with SDK-rendered echo plus independent
 * near-end PCM mixed into the SAME active injectAudio stream. No transcript/onset/playout is injected.
 *
 * Requires the opt-in prepared virtual-audio environment and host overlay bridge. Its bounded ack
 * reports PCM submitted to the single active SDK request, NOT Android consumption/decoder endpoint.
 * echoNonzeroDuringOverlay counts samples with both energetic pre-mix far-end and near-end audio.
 * Recognition and durable ownership are therefore awaited separately from the submitted-event ack.
 * These are synthetic, specified-path acoustic checks, not room/OEM/headset certification.
 */
internal class LiveAcousticAcceptanceTest : NativeMicrophoneScenario() {
    private val activeTrackField = KokoroTtsEngine::class.java.getDeclaredField("activeTrack").apply { isAccessible = true }
    private lateinit var acquisitionOwner: NativeAcquisitionOwner

    @Test fun l32_l33_mixedRenderedEchoAndNearEndInterruptionPreserveWordsAndOnlyCancelOldReply() {
        startListening(); spoken("review", 1)
        acquisitionOwner = h.acquisitionCheckpoint().owner
        val captureSession = liveRecordings().single().clientAudioSessionId
        val old = h.submissions.single().requestId

        withRenderedEcho("acoustic-interrupt") {
            h.reply(LONG_DISTINCT_REPLY)
            awaitEchoOnlyPlayback(old, expectedTurns = 1, expectedCancellations = emptyList())
            audio.checkpoint("acoustic-interrupt-before-overlay")
            val interruptAcquisition = h.acquisitionCheckpoint()
            assertOverlay(audio.mixNearEndFixture("interrupt"), requireRenderedOverlap = true)
            awaitEnergeticAcquisition(interruptAcquisition, "interrupt_overlay")
            awaitExactNearEndTurn("interrupt", expectedTurns = 2)
            awaitOnlyExpectedCancellation(old, listOf(old))
            assertSameCapture(captureSession)

            val replacement = h.submissions.last().requestId
            assertNotEquals(old, replacement)
            h.reply("I will explain the error instead. This replacement answer must finish while the same speaker echo path remains active.")
            awaitEnergeticReplyAcquisition(replacement, "replacement_echo")
            h.drained(replacement)
            assertEquals(KokoroSpeechStatus.Cancelled, h.tts.requestStatuses.value[old]?.state)
            assertTrue("The replacement must produce actual non-silent native PCM", h.phases
                .filterIsInstance<KokoroPlaybackPhase.Playing>()
                .any { it.requestId == replacement && it.pcmRms > 0f })
            assertTurnCounts(2, listOf(old))
        }
        // A defined extra input observation after the host echo stream has closed, not a guessed
        // sleep for JNI completion. No old echo tail may become another point during this window.
        audio.inject("silence")
        assertTurnCounts(2, listOf(old))
        assertSameCapture(captureSession)
        assertTrue(h.audioFailures.isEmpty())
    }

    @Test fun l20_l32_sameWordsOverRenderedEchoRemainDistinctDuringAndAfterSpokenReplies() {
        startListening(); spoken("review", 1)
        acquisitionOwner = h.acquisitionCheckpoint().owner
        val captureSession = liveRecordings().single().clientAudioSessionId
        val first = h.submissions.single().requestId
        val cancelled = mutableListOf<String>()

        withRenderedEcho("acoustic-repeat") {
            // Agent words equal both the previous user's words and the forthcoming near-end words.
            // The near-end corpus uses am_michael, not the reply's af_heart waveform. Text matching
            // against current/recent agent speech or previous intent must not discard this point.
            h.reply(SAME_WORDS_REPLY)
            assertNotEquals("The authored same-words overlay must use an independent voice",
                LiveAudioCorpus.fixtures.single { it.id == "review-male" }.voiceId,
                h.tts.selectedVoice.value?.id?.value)
            awaitEchoOnlyPlayback(first, expectedTurns = 1, expectedCancellations = emptyList())
            audio.checkpoint("acoustic-repeat-first-overlay")
            val firstOverlayAcquisition = h.acquisitionCheckpoint()
            assertOverlay(audio.mixNearEndFixture("review-male"), requireRenderedOverlap = true)
            awaitEnergeticAcquisition(firstOverlayAcquisition, "first_same_words_overlay")
            awaitExactNearEndTurn("review-male", expectedTurns = 2)
            cancelled += first
            awaitOnlyExpectedCancellation(first, cancelled)
            assertSameCapture(captureSession)

            // The identical deliberate user intent must remain valid again while a replacement
            // speaks the same words. Neither semantic dedup nor a recent-transcript window is valid.
            val second = h.submissions.last().requestId
            h.reply(SAME_WORDS_REPLY)
            awaitEchoOnlyPlayback(second, expectedTurns = 2, expectedCancellations = cancelled)
            audio.checkpoint("acoustic-repeat-second-overlay")
            val secondOverlayAcquisition = h.acquisitionCheckpoint()
            assertOverlay(audio.mixNearEndFixture("review-male"), requireRenderedOverlap = true)
            awaitEnergeticAcquisition(secondOverlayAcquisition, "second_same_words_overlay")
            awaitExactNearEndTurn("review-male", expectedTurns = 3)
            cancelled += second
            awaitOnlyExpectedCancellation(second, cancelled)
            assertSameCapture(captureSession)

            val third = h.submissions.last().requestId
            h.reply("Please review the latest changes.")
            awaitEnergeticReplyAcquisition(third, "third_reply_echo")
            h.drained(third)
            assertTurnCounts(3, cancelled)

            // Repeat once more AFTER the answer drains, still through the same active injector and
            // capture. This also rejects post-playback transcript cooldown/dedup implementations.
            audio.checkpoint("acoustic-repeat-after-drain-overlay")
            val afterDrainAcquisition = h.acquisitionCheckpoint()
            assertOverlay(audio.mixNearEndFixture("review-male"), requireRenderedOverlap = false)
            awaitEnergeticAcquisition(afterDrainAcquisition, "after_drain_same_words_overlay")
            awaitExactNearEndTurn("review-male", expectedTurns = 4)
            val fourth = h.submissions.last().requestId
            h.reply("This repeated point has its own answer, and no completed answer is cancelled.")
            awaitEnergeticReplyAcquisition(fourth, "fourth_reply_echo")
            h.drained(fourth)
            assertEquals(KokoroSpeechStatus.Completed, h.tts.requestStatuses.value[third]?.state)
            assertEquals(KokoroSpeechStatus.Cancelled, h.tts.requestStatuses.value[first]?.state)
            assertEquals(KokoroSpeechStatus.Cancelled, h.tts.requestStatuses.value[second]?.state)
            assertTurnCounts(4, cancelled)
            assertSameCapture(captureSession)
        }
        audio.inject("silence")
        assertTurnCounts(4, cancelled)
        assertSameCapture(captureSession)
        assertTrue(h.audioFailures.isEmpty())
    }

    /** Observe a full second of the real playback head before admitting the overlay. This is a
     * specified echo-only observation, not a calibrated acoustic delay or a sleep guessing readiness.
     * Cancellation/failure/completion ends the wait with a useful failure instead of hiding self-echo
     * behind a later successful near-end injection. Reflection only observes the real track.
     */
    private fun awaitEchoOnlyPlayback(request: String, expectedTurns: Int, expectedCancellations: List<String>) {
        var observedFrames = 0L
        var requiredFrames = Long.MAX_VALUE
        var acquisitionStart: NativeAcquisitionSnapshot? = null
        h.await("Energetic rendered echo must not interrupt the real reply before near-end speech") {
            val track = activeTrackField.get(h.tts) as? AudioTrack
            if (track != null) runCatching {
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    val head = track.playbackHeadPosition.toLong() and 0xffff_ffffL
                    val rate = track.sampleRate
                    if (track.state == AudioTrack.STATE_INITIALIZED) {
                        observedFrames = head
                        requiredFrames = rate.toLong() // One second in this track's sample clock.
                        if (head > 0 && acquisitionStart == null) acquisitionStart = h.acquisitionCheckpoint()
                    }
                }
            }
            observedFrames >= requiredFrames || h.tts.requestStatuses.value[request]?.state in setOf(
                KokoroSpeechStatus.Cancelled, KokoroSpeechStatus.Failed, KokoroSpeechStatus.Completed,
            ) || h.snapshot.state is LiveConversationState.Failure
        }
        assertTrue("The real reply must survive the echo-only playback-head observation; frames=$observedFrames required=$requiredFrames status=${h.tts.requestStatuses.value[request]?.state}",
            observedFrames >= requiredFrames)
        assertEquals("Only a still-playing reply can be interrupted by the next overlay",
            KokoroSpeechStatus.Playing, h.tts.requestStatuses.value[request]?.state)
        assertTrue("The observed track must carry actual non-silent generated PCM", h.phases
            .filterIsInstance<KokoroPlaybackPhase.Playing>()
            .any { it.requestId == request && it.pcmRms > 0f })
        awaitEnergeticAcquisition(checkNotNull(acquisitionStart) { "Echo playback never established a direct acquisition baseline" },
            "echo_only_playback", request)
        assertTurnCounts(expectedTurns, expectedCancellations)
    }

    /** Baselines are taken AFTER seed recognition and after this reply really starts playing.
     * A prior utterance's lifetime peak or a host write acknowledgment cannot satisfy this proof.
     */
    private fun awaitEnergeticReplyAcquisition(request: String, stage: String) {
        var before: NativeAcquisitionSnapshot? = null
        h.await("Reply must own actual playing output before its RAW acquisition window") {
            val track = activeTrackField.get(h.tts) as? AudioTrack
            val phase = h.tts.playbackPhase.value
            if (phase is KokoroPlaybackPhase.Playing && phase.requestId == request && phase.pcmRms > 0f && track != null &&
                runCatching { track.state == AudioTrack.STATE_INITIALIZED && track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                    (track.playbackHeadPosition.toLong() and 0xffff_ffffL) > 0L }.getOrDefault(false)) {
                before = h.acquisitionCheckpoint()
            }
            before != null || replyTerminal(request) || h.snapshot.state is LiveConversationState.Failure
        }
        awaitEnergeticAcquisition(checkNotNull(before) { "Reply ended without an actual playback acquisition baseline" }, stage, request)
    }

    private fun replyTerminal(request: String) = h.tts.requestStatuses.value[request]?.state in setOf(
        KokoroSpeechStatus.Completed, KokoroSpeechStatus.Cancelled, KokoroSpeechStatus.Failed,
    )

    /** RAW successful AudioRecord.read(ShortArray) moments, before conversion/FIFO/APM/VAD.
     * This is a delivery prerequisite, NOT a production speech threshold or an AEC quality metric.
     * Never rebaseline to a replacement recorder/epoch when a delta cannot be formed.
     */
    private fun awaitEnergeticAcquisition(before: NativeAcquisitionSnapshot, stage: String, playingRequest: String? = null) {
        assertEquals("Every acoustic window must belong to the original post-seed acquisition owner", acquisitionOwner, before.owner)
        var delivered: NativeAcquisitionDelta? = null
        h.await("Energetic Android RAW acquisition required for $stage; host submission is not delivery") {
            val now = h.acquisitionCheckpoint()
            assertEquals("Acoustic delivery must remain on the same actual recorder/session/epoch", before.owner, now.owner)
            if (now.successfulReadSamples - before.successfulReadSamples >= 1_600L) {
                val window = now.deltaSince(before)
                if (window.normalizedRms >= 0.001) delivered = window
            }
            delivered != null || (playingRequest != null && replyTerminal(playingRequest)) || h.snapshot.state is LiveConversationState.Failure
        }
        val proof = checkNotNull(delivered) { "No energetic Android RAW acquisition during $stage" }
        if (playingRequest != null) assertEquals("Energetic echo must reach Android before this reply drains",
            KokoroSpeechStatus.Playing, h.tts.requestStatuses.value[playingRequest]?.state)
        println("REAL_AUDIO_DIRECT_DELIVERY stage=$stage samples=${proof.successfulReadSamples} reads=${proof.successfulReads} rawRms=${proof.normalizedRms} owner=${proof.owner}")
    }

    private fun assertOverlay(ack: JSONObject, requireRenderedOverlap: Boolean) {
        val frames = ack.getInt("overlayFrames")
        val nonzero = ack.getInt("overlayNonzero")
        val overlap = ack.getInt("echoNonzeroDuringOverlay")
        val clipped = ack.getInt("mixedClippedSamples")
        assertTrue("The host must submit the real near-end PCM to the active SDK request", frames >= 16_000)
        assertTrue("A silent overlay cannot establish near-end preservation", nonzero in 1001..frames)
        assertTrue("Overlap counts actual jointly energetic samples", overlap in 0..nonzero)
        assertTrue("Saturating-mix accounting must remain bounded", clipped in 0..frames)
        if (requireRenderedOverlap) assertTrue("Near-end speech must overlap energetic rendered echo in the SAME injector",
            overlap > 1000)
        println("REAL_AUDIO_ACOUSTIC overlayFrames=$frames overlayNonzero=$nonzero echoNonzeroDuringOverlay=$overlap mixedClippedSamples=$clipped boundary=submitted_to_single_sdk_request")
    }

    private fun awaitExactNearEndTurn(fixtureId: String, expectedTurns: Int) {
        h.await("Real mixed near-end speech must become its own durable point") {
            h.submissions.size >= expectedTurns || h.snapshot.state is LiveConversationState.Failure
        }
        val fixture = LiveAudioCorpus.fixtures.single { it.id == fixtureId }
        val actual = h.submissions[expectedTurns - 1]
        h.assertWords(fixture, actual.text)
        // Ignore punctuation/case, not words, order or repeated material. Extra far-end text is not
        // an acceptable instruction merely because the requested words also occur somewhere in it.
        assertEquals("Only the authored near-end words may become this instruction",
            orderedWords(fixture.spoken), orderedWords(actual.text))
        assertEquals("Exactly one real onset must belong to each deliberate point", expectedTurns, onsets.size)
        assertEquals("Echo must not add another instruction", expectedTurns, h.submissions.size)
        assertEquals(expectedTurns, h.submissions.map { it.requestId }.toSet().size)
        val durable = runBlocking { h.repository.pendingPrompts("native-test-host") }
        assertEquals("Every repeated point must retain distinct durable ownership", expectedTurns, durable.size)
        assertEquals(actual.text, durable.single { it.requestId == actual.requestId }.text)
    }

    private fun awaitOnlyExpectedCancellation(old: String, expected: List<String>) {
        h.await("Actual old output must be cancelled and remote cancellation dispatched once") {
            h.tts.requestStatuses.value[old]?.state == KokoroSpeechStatus.Cancelled && old in h.cancellations
        }
        assertEquals(expected, h.cancellations.toList())
    }

    private fun assertTurnCounts(expected: Int, cancellations: List<String>) {
        assertEquals("Echo alone must not create an onset", expected, onsets.size)
        assertEquals("Only deliberate near-end points may be submitted", expected, h.submissions.size)
        assertEquals("Repeated words still need distinct request IDs", expected, h.submissions.map { it.requestId }.toSet().size)
        assertEquals("Only an actually interrupted reply may be cancelled", cancellations, h.cancellations.toList())
    }

    private fun assertSameCapture(sessionId: Int) {
        assertEquals("Barge-in/echo processing must not restart input or swallow initial words", 1, h.inputs.size)
        assertEquals(setOf(sessionId), liveRecordings().map { it.clientAudioSessionId }.toSet())
    }

    /** Preserve the first assertion/host-abort evidence even if bounded echo cleanup also fails. */
    private fun withRenderedEcho(label: String, body: () -> Unit) {
        audio.checkpoint("$label-start")
        audio.startEcho()
        var failure: Throwable? = null
        try { body() } catch (error: Throwable) { failure = error }
        try {
            val evidence = audio.stopEcho()
            assertTrue("The echo path must actually inject rendered PCM", evidence.getInt("injectedNonzero") > 1000)
            assertTrue("Echo must not be silently attenuated away by the test fixture", evidence.getDouble("injectedPeakRms") > 0.01)
            assertEquals(0.8, evidence.getDouble("echoGain"), 0.0001)
            assertEquals(100, evidence.getInt("minimumHostDelayMs"))
        } catch (error: Throwable) {
            val original = failure
            if (original == null) failure = error else original.addSuppressed(error)
        }
        failure?.let { throw it }
        audio.checkpoint("$label-complete")
    }

    private fun orderedWords(text: String): List<String> = Regex("[\\p{L}\\p{N}]+")
        .findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()

    private companion object {
        const val LONG_DISTINCT_REPLY = "The build finished successfully, and the generated report is available for review. " +
            "I am continuing this deliberately long answer while the speaker output is returned through the microphone. " +
            "A real new point must interrupt this answer without including any of these rendered words in the new instruction."
        val SAME_WORDS_REPLY = List(8) { "Please review the latest changes." }.joinToString(" ")
    }
}
