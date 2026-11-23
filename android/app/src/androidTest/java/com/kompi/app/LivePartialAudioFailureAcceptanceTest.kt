package com.kompi.app

import android.util.Base64
import com.kompi.app.audio.KokoroPlaybackPhase
import com.kompi.app.audio.KokoroSpeechStatus
import com.kompi.app.audio.SpeechRecognitionModel
import com.kompi.app.audio.openDecoder
import com.kompi.app.audio.whistle.WhistleVocabulary
import com.kompi.app.stream.ReplySpeechQueue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** Real ASR/outbox/controller, production reply queue and real Kokoro/AudioTrack. Only the second
 * phrase's admission boundary throws. This is NOT a claim of a model-kernel/prefetch exception.
 * It requires partial-failure preservation at that explicit seam, rather than fitting assertions
 * to an implementation that cancels earlier audio or silently loses the failure. */
internal class LivePartialAudioFailureAcceptanceTest : NativeMicrophoneScenario() {
    @Test fun l49_laterInjectedAdmissionExceptionPreservesEarlierRealPcmDrainAndSurfacesPartialFailure() {
        startListening(); spoken("continue", 1)
        runBlocking { withTimeout(90_000) { h.tts.initialize().getOrThrow() } }
        val request = h.submissions.single().requestId
        val prefix = "This valid first phrase must keep playing after the later injected failure. The final words of this first phrase are purple bicycle."
        val suffix = "This second phrase cannot be synthesized because its admission boundary fails."
        val faultReached = CompletableDeferred<Unit>()
        val failures = CopyOnWriteArrayList<String>()
        val stops = CopyOnWriteArrayList<String?>()
        val unhandled = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error -> unhandled += error })
        val queue = ReplySpeechQueue(scope,
            enqueue = { id, sequence, text ->
                if (sequence == 0L) h.tts.enqueueWhenAvailable(id, sequence, text)
                else {
                    withTimeout(90_000) { h.tts.playbackPhase.first { it is KokoroPlaybackPhase.Playing && it.requestId == id && it.sequence == 0L } }
                    faultReached.complete(Unit)
                    throw IOException("Test-only later phrase admission failure after real first PCM")
                }
            },
            finish = h.tts::finishRequest,
            stop = { stops += it; h.tts.stop(it) },
            failed = failures::add,
        )
        val captured = File(h.context.cacheDir, "live-l49-synthetic-output.wav")
        try {
            audio.startCapture()
            h.main {
                h.controller.updateAssistantText(request, "$prefix $suffix")
                check(queue.offer(request, 0, prefix))
                check(queue.offer(request, 1, suffix))
                queue.complete(request)
            }
            runBlocking { withTimeout(90_000) { faultReached.await() } }
            // Observe the native drain before inspecting error reporting. Even today's missing
            // exception handling must not let the test exit early without collecting this evidence.
            h.await("Earlier real PCM must drain after the injected later fault") {
                h.phases.filterIsInstance<KokoroPlaybackPhase.Draining>().any { it.requestId == request && it.sequence == 0L } &&
                    h.tts.playbackPhase.value == KokoroPlaybackPhase.Idle
            }
            val output = audio.stopCapture()
            assertTrue("The independent rendered output must contain real PCM", output.getInt("nonzero") > 1000)
            assertTrue("A later phrase failure must not implicitly stop valid admitted audio", stops.isEmpty())
            assertNotEquals(KokoroSpeechStatus.Cancelled, h.tts.requestStatuses.value[request]?.state)
            assertFalse("Failed suffix must never enter playback", h.phases.filterIsInstance<KokoroPlaybackPhase.Playing>().any { it.requestId == request && it.sequence > 0L })
            assertEquals("Public text remains available despite the local partial failure", "$prefix $suffix", h.snapshot.assistantText)
            h.main { h.controller.endLive() }; microphoneStopped()
            runBlocking { h.inputs.last().closeAndJoin() }
            captured.writeBytes(Base64.decode(output.getString("wavBase64"), Base64.DEFAULT))
            val recognized = runBlocking(Dispatchers.IO) {
                val decoder = SpeechRecognitionModel.Parakeet(LiveAudioCorpus.model(h.context)).openDecoder(h.context, null, WhistleVocabulary.Empty)
                decoder.use {
                    val pcm = LiveAudioCorpus.read16kMono(captured)
                    for (offset in pcm.indices step decoder.chunkSamples) decoder.transcribe(pcm.copyOfRange(offset, minOf(offset + decoder.chunkSamples, pcm.size)))
                    decoder.finish().committedText
                }
            }
            assertTrue("Actual rendered prefix must preserve its final words, not only initial PCM", LiveAudioCorpus.words(recognized).containsAll(setOf("purple", "bicycle")))
            assertEquals("Partial failure must be surfaced exactly once through the production queue's failure callback; uncaught=${unhandled.map { it.javaClass.simpleName }}", listOf(request), failures)
            assertTrue("An admission exception must be handled as a partial failure, not escape the owner coroutine", unhandled.isEmpty())
            println("LIVE_L49 earlierNativePcmDrained=true renderedPrefixTailRecognized=true failureBoundary=injected_second_phrase_admission nativeModelKernelFault=not_claimed")
        } finally { queue.cancel(); scope.cancel(); captured.delete() }
    }
}
