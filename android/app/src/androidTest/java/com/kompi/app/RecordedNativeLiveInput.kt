package com.kompi.app

import android.content.Context
import android.os.SystemClock
import com.kompi.app.audio.*
import com.kompi.app.audio.live.*
import com.kompi.app.live.LiveSpeechInput
import com.kompi.app.live.CapturePhase as LivePhase
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** A file replaces only the PCM source. Real Parakeet, VAD, pre-roll and native finalization remain. */
internal class RecordedNativeLiveInput(private val context: Context) : LiveSpeechInput {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val stopped = AtomicBoolean(false)
    private val ready = CompletableDeferred<LocalLiveRecognizer>()
    @Volatile private var recognizer: LocalLiveRecognizer? = null
    var starts = 0
    override fun start(listener: LiveSpeechInput.Listener) {
        starts++
        scope.launch {
            try {
                val decoder = SpeechRecognitionModel.Parakeet(LiveAudioCorpus.model(context)).openDecoder(context, null, com.kompi.app.audio.whistle.WhistleVocabulary.Empty)
                val input = LocalLiveRecognizer(decoder, null, object : LocalLiveListener {
                    override fun onSpeechStarted(utteranceId: SpeechUtteranceId) { if (!stopped.get()) listener.onSpeechStarted(utteranceId) }
                    override fun onBargeInConfirmed(utteranceId: SpeechUtteranceId) { if (!stopped.get()) listener.onBargeInConfirmed(utteranceId) }
                    override fun onMeter(meter: CaptureMeter) { if (!stopped.get()) listener.onMeter(meter.rms, if (meter.phase == com.kompi.app.audio.live.CapturePhase.Listening) LivePhase.Listening else LivePhase.Recognizing, meter.waveform) }
                    override fun onPartial(utteranceId: SpeechUtteranceId, partial: TranscriptPartial) { if (!stopped.get()) listener.onPartial(utteranceId, partial.committedText, partial.pendingText, partial.language) }
                    override fun onFinal(utteranceId: SpeechUtteranceId, transcript: NonBlankTranscript) { if (!stopped.get()) listener.onFinal(utteranceId, transcript.text, transcript.language) }
                    override fun onError(message: String) { if (!stopped.get()) { stop(false); listener.onLocalStop(com.kompi.app.live.LiveLocalStopReason.EngineFailure(message)) } }
                }, EchoCancellationPolicy.Disabled, null, {}, SileroSpeechClassifier(context))
                recognizer = input
                if (stopped.get()) { input.discardAndJoin(); ready.completeExceptionally(CancellationException("Input stopped during native initialization")) }
                else ready.complete(input)
            } catch (error: Throwable) {
                ready.completeExceptionally(error)
                if (!stopped.get()) { stop(false); listener.onLocalStop(com.kompi.app.live.LiveLocalStopReason.EngineFailure("Real local decoder could not start")) }
            }
        }
    }

    suspend fun feed(pcm: FloatArray) {
        val input = withTimeout(90_000) { ready.await() }
        val started = SystemClock.elapsedRealtimeNanos()
        var offset = 0
        while (offset < pcm.size && !stopped.get()) {
            val count = minOf(320, pcm.size - offset)
            input.onAudio(pcm.copyOfRange(offset, offset + count))
            offset += count
            // Audio-clock pacing, not a sleep used to guess completion or synchronize assertions.
            val dueNanos = started + offset * 1_000_000_000L / 16_000
            val wait = (dueNanos - SystemClock.elapsedRealtimeNanos()) / 1_000_000
            if (wait > 0) delay(wait)
        }
    }
    suspend fun playFixture(id: String, trailingSilence: Boolean = true) {
        feed(FloatArray(4_800))
        feed(LiveAudioCorpus.read16kMono(LiveAudioCorpus.fixture(context, id)))
        if (trailingSilence) feed(FloatArray(16_000))
    }
    override fun requestStopAndFence() { stopped.set(true) }
    override fun stop(finalizeCurrentUtterance: Boolean) { requestStopAndFence(); recognizer?.close() }
    override suspend fun stopMicrophoneAndFence() { stop(false) }
    override suspend fun stopAndJoin(finalizeCurrentUtterance: Boolean) { stop(false); withContext(Dispatchers.IO) { recognizer?.discardAndJoin() } }
    override suspend fun closeAndJoin() {
        stop(false)
        // Initialization owns native construction. Join it before disposing its executor.
        withContext(Dispatchers.IO) { recognizer?.discardAndJoin() }
        scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
        withContext(Dispatchers.IO) { recognizer?.discardAndJoin() }
        scope.cancel(); dispatcher.close()
    }
    override fun close() { stop(false) }
}
