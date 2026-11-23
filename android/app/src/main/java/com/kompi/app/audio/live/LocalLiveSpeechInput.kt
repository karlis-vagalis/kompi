package com.kompi.app.audio.live

import android.content.Context
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.kompi.app.audio.ForegroundMicCapture
import com.kompi.app.audio.LiveAudioSession
import com.kompi.app.audio.LiveAudioStartException
import com.kompi.app.audio.LiveAudioStartIssue
import com.kompi.app.audio.SpeechRecognitionModel
import com.kompi.app.audio.SpeechUtteranceId
import com.kompi.app.audio.openDecoder
import com.kompi.app.audio.whistle.WhistleVocabulary
import com.kompi.app.live.LiveLocalStopReason
import com.kompi.app.live.LiveSpeechInput
import com.kompi.app.live.LiveTranscriptionStatus
import com.kompi.app.live.CapturePhase as ControllerCapturePhase
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** One fresh capture/decoder owner. A controller-supplied duplex session is BORROWED and survives
 * microphone mute; this wrapper is terminal after stopping. Native disposal remains app-owned.
 */
class LocalLiveSpeechInput(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val model: SpeechRecognitionModel,
    private val language: String? = null,
    private val vocabulary: WhistleVocabulary = WhistleVocabulary.Empty,
    private val audioSession: LiveAudioSession? = null,
) : LiveSpeechInput, Closeable {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val workerThread = AtomicReference<Thread?>()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread({ workerThread.set(Thread.currentThread()); task.run() }, "kompi-local-live-input").apply { isDaemon = true }
    }
    private val readyTone = LiveReadyTone(appContext)
    private var generation = 0L
    private var closed = false
    private var active: ActiveCapture? = null

    override fun start(listener: LiveSpeechInput.Listener) {
        val token = synchronized(this) {
            if (closed) { listener.onLocalStop(engineFailure()); return }
            if (active != null) return
            ++generation
        }
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || !model.engine.supportedOnDevice()) {
            synchronized(this) { closed = true; generation++ }
            val reason = engineFailure(if (!model.engine.supportedOnDevice()) LiveAudioStartIssue.ModelUnavailable else LiveAudioStartIssue.ForegroundRequired)
            if (audioSession != null) audioSession.revoke(reason) { listener.onLocalStop(it) }
            else listener.onLocalStop(reason)
            return
        }
        forward(token) { listener.onTranscriptionStatus(LiveTranscriptionStatus.Preparing(model.engine)) }
        val sessionRef = AtomicReference(audioSession)
        val recognizerRef = AtomicReference<LocalLiveRecognizer?>()
        val recordRef = AtomicReference<ActiveCapture?>()
        val firstMeter = AtomicBoolean(true)
        val capture = ForegroundMicCapture(appContext, lifecycleOwner,
            consumerFactory = {
                val record = checkNotNull(recordRef.get())
                val session = sessionRef.get() ?: LiveAudioSession.open(appContext) { reason ->
                    fence(record)
                    listener.onLocalStop(reason)
                }.also(sessionRef::set)
                session.requireActive()
                if (record.binding.get() == null) {
                    val binding = session.bindStop { fence(record) }
                    if (!record.binding.compareAndSet(null, binding)) binding.close()
                }
                val demand = session.reserveCapture()
                record.captureDemand.set(demand)
                releaseStoppedCaptureDemand(record) // A bounded stop may have won during factory entry.
                check(record.acceptsFailures.get()) { "Microphone capture was stopped during preparation" }
                val decoder = try { model.openDecoder(appContext, language, vocabulary) }
                catch (_: Exception) { throw LiveAudioStartException(LiveAudioStartIssue.ModelUnavailable) }
                val speechClassifier = try { SileroSpeechClassifier(appContext) }
                catch (error: SpeechClassifierUnavailable) {
                    runCatching { decoder.close() }
                    throw LiveAudioStartException(LiveAudioStartIssue.ClassifierUnavailable, error)
                }
                try {
                    session.requireActive()
                    // The bundled, hash-checked ONNX session is loaded and warmed on this IO
                    // startup worker, before hardware capture and away from the AudioRecord reader.
                    // Only this worker waits for route activation.
                    val permit = session.ensureHardware(demand)
                    record.capturePermit.set(permit)
                    releaseStoppedCaptureDemand(record)
                    permit.requireCurrent()
                    val preparedConsumer = LocalLiveRecognizer(decoder, language.takeIf { model is SpeechRecognitionModel.Whistle }, object : LocalLiveListener {
                        override fun onSpeechStarted(utteranceId: SpeechUtteranceId) = forward(token) {
                            listener.onSpeechStarted(utteranceId)
                            listener.onTranscriptionStatus(LiveTranscriptionStatus.Utterance(model.engine, utteranceId, LiveTranscriptionStatus.Phase.Receiving))
                        }
                        override fun onUtteranceEnded(utteranceId: SpeechUtteranceId) = forward(token) {
                            listener.onTranscriptionStatus(LiveTranscriptionStatus.Utterance(model.engine, utteranceId, LiveTranscriptionStatus.Phase.Finalizing))
                        }
                        override fun onEmptyFinal(utteranceId: SpeechUtteranceId) = forward(token) { listener.onEmptyFinal(utteranceId) }
                        override fun onBargeInConfirmed(utteranceId: SpeechUtteranceId) = forward(token) { listener.onBargeInConfirmed(utteranceId) }
                        override fun onMeter(meter: CaptureMeter) = forward(token) {
                            if (firstMeter.compareAndSet(true, false)) {
                                listener.onTranscriptionStatus(LiveTranscriptionStatus.Listening(model.engine))
                                if (session.claimReadyCue()) readyTone.play(session)
                            }
                            listener.onMeter(meter.rms, when (meter.phase) {
                                CapturePhase.Listening -> ControllerCapturePhase.Listening
                                CapturePhase.Recognizing -> ControllerCapturePhase.Recognizing
                            }, meter.waveform)
                        }
                        override fun onPartial(utteranceId: SpeechUtteranceId, partial: TranscriptPartial) = forward(token) {
                            listener.onPartial(utteranceId, partial.committedText, partial.pendingText, partial.language)
                        }
                        override fun onFinal(utteranceId: SpeechUtteranceId, transcript: NonBlankTranscript) = forward(token) {
                            listener.onFinal(utteranceId, transcript.text, transcript.language)
                        }
                        override fun onRecognitionFailure(failure: LocalLiveRecognitionFailure) {
                            localFailure(record, listener, LiveLocalStopReason.EngineFailure(failure.guidance))
                        }
                        override fun onError(message: String) { localFailure(record, listener,
                            engineFailure(record.microphone.captureFailureIssue ?: LiveAudioStartIssue.CaptureUnavailable)) }
                    }, echoCancellation = session.echoPolicy, preferredInputDevice = session.preferredInputDevice,
                        checkAudioRoute = session::checkRoute, speechFrameClassifier = speechClassifier)
                        .also(recognizerRef::set)
                    val echoBoundConsumer = session.protectCapture(preparedConsumer, record.acceptsFailures::get)
                        ?.also(record.echoCapture::set) ?: preparedConsumer
                    permit.requireCurrent()
                    echoBoundConsumer
                } catch (error: Throwable) {
                    val recognizer = recognizerRef.get()
                    if (recognizer != null) recognizer.discardAndJoin()
                    else { speechClassifier.close(); decoder.close() }
                    throw error
                }
            },
            // Android's communication-mode lease requires a communication capture source.
            // The software consumer obtains and explicitly disables standard session processors.
            audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            requireHardwarePermit = {
                checkNotNull(recordRef.get()?.capturePermit?.get()) { "Microphone activation was fenced" }.requireCurrent()
            })
        val record = ActiveCapture(token, capture, recognizerRef, sessionRef, audioSession != null)
        recordRef.set(record)
        val installed = synchronized(this) {
            if (closed || generation != token) false else { active = record; true }
        }
        if (!installed) { capture.close(); return }
        // Bind BEFORE native initialization, so focus/route loss fences even a slow opening model.
        try {
            audioSession?.let { session -> record.binding.set(session.bindStop { fence(record) }) }
            worker.execute {
                val result = runCatching { capture.start().getOrThrow() }
                if (result.isFailure && result.exceptionOrNull() !is LiveAudioSession.DemandRetired) localFailure(record, listener,
                    engineFailure((result.exceptionOrNull() as? LiveAudioStartException)?.issue ?: LiveAudioStartIssue.CaptureUnavailable))
            }
        } catch (_: Exception) { localFailure(record, listener, engineFailure()) }
    }

    private fun engineFailure(issue: LiveAudioStartIssue = LiveAudioStartIssue.CaptureUnavailable) = LiveLocalStopReason.EngineFailure(issue.guidance)

    private fun localFailure(record: ActiveCapture, listener: LiveSpeechInput.Listener, reason: LiveLocalStopReason) {
        if (!isCurrent(record)) return
        val session = record.audioSession.get()
        if (session != null) {
            // The session fences all capture/output bindings before this originating notification.
            session.revoke(reason, originStillActive = record.acceptsFailures::get) { safeReason -> fence(record); listener.onLocalStop(safeReason) }
        } else {
            fence(record)
            listener.onLocalStop(reason)
        }
    }

    private fun isCurrent(record: ActiveCapture) = synchronized(this) {
        !closed && active === record && generation == record.token
    }

    /** No native call or model lock precedes this fence and actual AudioRecord.stop(). */
    private fun fence(record: ActiveCapture) {
        synchronized(this) {
            record.acceptsFailures.set(false)
            if (active === record && !closed) { closed = true; generation++ }
        }
        record.captureDemand.get()?.fence()
        record.microphone.requestStop()
        record.echoCapture.getAndSet(null)?.close()
        readyTone.close()
        queueCleanup(record, finalizeCurrentUtterance = false)
    }

    override fun requestStopAndFence() {
        val record = synchronized(this) { closed = true; generation++; active?.also { it.acceptsFailures.set(false) } }
        // Actual capture fence/stop precedes optional cue disposal and all worker scheduling.
        record?.captureDemand?.get()?.fence()
        record?.microphone?.requestStop()
        record?.echoCapture?.getAndSet(null)?.close()
        readyTone.close()
        if (record != null) queueCleanup(record, finalizeCurrentUtterance = false)
        else worker.shutdown()
    }

    override fun stop(finalizeCurrentUtterance: Boolean) {
        val record = synchronized(this) {
            closed = true
            if (!finalizeCurrentUtterance) generation++
            active?.also { it.acceptsFailures.set(false) }
        }
        record?.captureDemand?.get()?.fence()
        record?.microphone?.requestStop()
        record?.echoCapture?.getAndSet(null)?.close()
        readyTone.close()
        if (record == null) { worker.shutdown(); return }
        queueCleanup(record, finalizeCurrentUtterance)
    }

    override suspend fun stopMicrophoneAndFence() {
        val record = synchronized(this) { active }
        if (record == null) { stop(false); return }
        fence(record)
        // The serial worker may still be opening JNI. Join hardware on an independent dispatcher;
        // revocation prevents any later startup install, while the coordinator retains native ownership.
        withContext(Dispatchers.IO) { beginHardwareStop(record) }
        record.hardwareStopped.await()
        check(record.hardwareJoinSucceeded.get()) { "Microphone reader did not stop; its owner remains quarantined" }
    }

    override suspend fun stopAndJoin(finalizeCurrentUtterance: Boolean) {
        check(Thread.currentThread() !== workerThread.get()) { "Cannot join Live teardown from its own worker" }
        val record = synchronized(this) { active }
        stop(finalizeCurrentUtterance)
        record?.decoderStopped?.await()
    }

    override suspend fun closeAndJoin() {
        withContext(NonCancellable) { stopAndJoin(false) }
        check(withContext(Dispatchers.IO) { worker.awaitTermination(10, TimeUnit.SECONDS) }) { "Live speech worker did not terminate" }
    }

    override fun close() = stop(false)

    private fun beginHardwareStop(record: ActiveCapture) {
        if (!record.hardwareStopStarted.compareAndSet(false, true)) return
        val joined = runCatching { record.microphone.stopAndJoin() }.getOrDefault(false)
        record.hardwareJoinSucceeded.set(joined)
        if (joined) releaseStoppedCaptureDemand(record)
        record.hardwareStopped.complete(Unit)
    }

    private fun releaseStoppedCaptureDemand(record: ActiveCapture) {
        if (record.hardwareJoinSucceeded.get()) {
            record.capturePermit.set(null)
            record.captureDemand.getAndSet(null)?.close()
        }
    }

    private fun queueCleanup(record: ActiveCapture, finalizeCurrentUtterance: Boolean) {
        if (!record.cleanupQueued.compareAndSet(false, true)) return
        try {
            worker.execute {
                try {
                    beginHardwareStop(record)
                    runBlocking { record.hardwareStopped.await() }
                    // Bounded privacy completion is separate. A hung reader retains hardware,
                    // consumer and native decoder; never dispose VAD/JNI underneath onAudio().
                    record.microphone.awaitReaderExit()
                    record.echoCapture.getAndSet(null)?.close()
                    record.binding.getAndSet(null)?.close()
                    if (!record.borrowedSession) record.audioSession.getAndSet(null)?.close()
                    record.recognizer.get()?.let { recognizer ->
                        if (finalizeCurrentUtterance) {
                            val finished = CountDownLatch(1)
                            recognizer.finish { finished.countDown() }
                            check(finished.await(FINALIZE_WAIT_SECONDS, TimeUnit.SECONDS)) { "Local finalization timed out" }
                            recognizer.awaitInferenceTermination()
                        } else recognizer.discardAndJoin()
                    }
                    main.post {
                        try {
                            record.microphone.close()
                            synchronized(this) { if (active === record) active = null }
                            if (record.hardwareJoinSucceeded.get()) record.decoderStopped.complete(Unit)
                            else record.decoderStopped.completeExceptionally(IllegalStateException("Microphone reader exceeded its bounded stop; ownership was retained"))
                        } catch (error: Throwable) { record.decoderStopped.completeExceptionally(error) }
                    }
                } catch (error: Throwable) {
                    record.hardwareStopped.complete(Unit)
                    record.decoderStopped.completeExceptionally(error)
                } finally { worker.shutdown() }
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            record.hardwareStopped.complete(Unit)
            record.decoderStopped.completeExceptionally(error)
        }
    }

    private fun forward(token: Long, callback: () -> Unit) {
        if (synchronized(this) { !closed && token == generation }) runCatching(callback)
    }

    private class ActiveCapture(
        val token: Long,
        val microphone: ForegroundMicCapture,
        val recognizer: AtomicReference<LocalLiveRecognizer?>,
        val audioSession: AtomicReference<LiveAudioSession?>,
        val borrowedSession: Boolean,
        val binding: AtomicReference<Closeable?> = AtomicReference(),
        val echoCapture: AtomicReference<Closeable?> = AtomicReference(),
        val captureDemand: AtomicReference<LiveAudioSession.CaptureDemand?> = AtomicReference(),
        val capturePermit: AtomicReference<LiveAudioSession.CommunicationPermit?> = AtomicReference(),
        val hardwareStopped: CompletableDeferred<Unit> = CompletableDeferred(),
        val hardwareJoinSucceeded: AtomicBoolean = AtomicBoolean(false),
        val hardwareStopStarted: AtomicBoolean = AtomicBoolean(false),
        val decoderStopped: CompletableDeferred<Unit> = CompletableDeferred(),
        val cleanupQueued: AtomicBoolean = AtomicBoolean(false),
        val acceptsFailures: AtomicBoolean = AtomicBoolean(true),
    )
    private companion object { const val FINALIZE_WAIT_SECONDS = 10L }
}
