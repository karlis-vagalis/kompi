package com.kompi.app.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.kompi.app.audio.whistle.WhistleVocabulary
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

enum class OfflineSpeechSessionPhase { Idle, Preparing, Capturing, Finalizing }

internal typealias OfflineSpeechRecognizerFactory = (
    Context, SpeechRecognitionModel, String?, WhistleVocabulary,
    (OfflineTranscript) -> Unit, (OfflineTranscript) -> Unit, (String) -> Unit,
) -> OfflineSpeechRecognizer

/** Owns one foreground-only local speech session. Model loading/inference stay off the UI thread. */
class OfflineSpeechSession(
    context: Context,
    private val owner: LifecycleOwner,
    private val model: SpeechRecognitionModel,
    private val language: String?,
    private val onTranscript: (OfflineTranscript) -> Unit,
    private val onMessage: (String) -> Unit,
    private val onRecordingChanged: (Boolean) -> Unit,
    private val vocabulary: WhistleVocabulary = WhistleVocabulary.Empty,
    private val onPartial: (OfflineTranscript) -> Unit = {},
    private val onPhaseChanged: (OfflineSpeechSessionPhase) -> Unit = {},
) : Closeable, LifecycleEventObserver {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "kompi-speech-session") }
    private val closed = AtomicBoolean(false)
    private var generation = 0L
    private var capture: ForegroundMicCapture? = null
    private var recognizer: OfflineSpeechRecognizer? = null
    private var starting = false
    private var lastStopOperation: StopOperation? = null
    private var phase = OfflineSpeechSessionPhase.Idle
    internal var recognizerFactory: OfflineSpeechRecognizerFactory = { ctx, selectedModel, selectedLanguage, selectedVocabulary, partial, transcript, error ->
        selectedModel.offlineRecognizer(ctx, selectedLanguage, selectedVocabulary, partial, transcript, error)
    }

    init { owner.lifecycle.addObserver(this) }

    /** Must be called after an explicit foreground user action and outside the mic permission callback's UI work. */
    @Synchronized
    fun start(): Boolean {
        if (closed.get()) { report("Speech session is closed"); return false }
        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            report("Microphone capture requires a foreground screen")
            return false
        }
        if (!model.engine.supportedOnDevice()) {
            report("${model.engine.label} is not supported on this device")
            return false
        }
        if (phase != OfflineSpeechSessionPhase.Idle || starting || capture != null) return false
        starting = true
        val request = ++generation
        transitionPhase(OfflineSpeechSessionPhase.Preparing)
        report("Loading ${model.engine.label} offline speech…")
        worker.execute {
            val loaded = try {
                recognizerFactory(
                    appContext, model, language, vocabulary,
                    { result -> main.post {
                        if (!closed.get() && request == currentGeneration()) onPartial(result)
                    } },
                    { result -> main.post {
                        if (!closed.get() && request == currentGeneration()) onTranscript(result)
                    } },
                    { error -> main.post {
                        if (!closed.get() && request == currentGeneration()) {
                            onMessage(error)
                            stopWorker(flush = false)
                        }
                    } },
                )
            } catch (error: Throwable) {
                main.post {
                    val current = synchronized(this) { if (request == generation) { starting = false; true } else false }
                    if (current) transitionPhase(OfflineSpeechSessionPhase.Idle)
                    if (!closed.get() && current) onMessage("Could not initialize ${model.engine.label}: ${error.message ?: error.javaClass.simpleName}")
                }
                return@execute
            }
            main.post {
                val shouldClose = synchronized(this) { closed.get() || request != generation || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
                if (shouldClose) { loaded.close(); return@post }
                val mic = try { ForegroundMicCapture(appContext, owner, loaded) }
                catch (error: Throwable) {
                    loaded.close()
                    val current = synchronized(this) { if (request == generation) { starting = false; true } else false }
                    if (current) transitionPhase(OfflineSpeechSessionPhase.Idle)
                    if (!closed.get() && current) onMessage("Could not prepare microphone: ${error.message ?: error.javaClass.simpleName}")
                    return@post
                }
                synchronized(this) { recognizer = loaded; capture = mic }
                worker.execute {
                    val startResult = mic.start()
                    main.post {
                        val stillCurrent = synchronized(this) {
                            if (request == generation && !closed.get() && startResult.isSuccess) {
                                starting = false
                                true
                            } else {
                                if (request == generation) starting = false
                                false
                            }
                        }
                        if (stillCurrent) {
                            transitionPhase(OfflineSpeechSessionPhase.Capturing)
                            onRecordingChanged(true)
                            onMessage("Listening · ${model.engine.label} runs locally on this phone")
                        } else {
                            if (startResult.isFailure && !closed.get()) {
                                onMessage(startResult.exceptionOrNull()?.message ?: "Microphone could not start")
                            }
                            val cleanupRequired = synchronized(this) { capture === mic && recognizer === loaded }
                            if (cleanupRequired) stopWorker(flush = false)
                        }
                    }
                }
            }
        }
        return true
    }

    /** End push-to-talk and flush the final VAD utterance. Native inference cannot be cancelled. */
    @Synchronized
    fun stop() { stopWorker(flush = true) }

    /**
     * Stops PTT capture and awaits AudioRecord teardown plus any final queued local inference.
     * Keep [flush] true for the normal PTT stop (the final utterance is added to the draft); use
     * false when switching modes and unfinished PTT speech must be discarded.
     */
    suspend fun stopAndJoin(flush: Boolean = true) {
        val operation = stopWorker(flush)
        val joined = operation.hardwareStopped.await()
        operation.completion.await()
        check(joined) { "Microphone reader did not stop; refusing to overlap another capture" }
    }

    /** Fences callbacks and queues a discard/teardown without waiting on the route-away path. */
    fun stopAndFence(flush: Boolean = false) { stopWorker(flush) }

    /** Privacy stop: await only the bounded microphone-reader join; decoder cleanup continues queued. */
    suspend fun stopMicrophoneAndFence() {
        val operation = stopWorker(flush = false)
        check(operation.hardwareStopped.await()) { "Microphone reader did not stop; capture lease remains held" }
    }

    private data class StopOperation(
        val hardwareStopped: CompletableFuture<Boolean>,
        val completion: CompletableFuture<Unit>,
    )

    @Synchronized
    private fun stopWorker(flush: Boolean): StopOperation {
        if (worker.isShutdown) return lastStopOperation ?: completedStopOperation()
        val hasWork = capture != null || recognizer != null || starting
        if (!hasWork) {
            if (!flush) ++generation
            return lastStopOperation ?: completedStopOperation()
        }

        if (!flush || (starting && capture == null && recognizer == null)) ++generation
        transitionPhase(OfflineSpeechSessionPhase.Finalizing)
        starting = false
        val mic = capture
        val speech = recognizer
        capture = null
        recognizer = null
        mic?.requestStop()
        main.post { if (!closed.get()) onRecordingChanged(false) }
        val operation = StopOperation(CompletableFuture(), CompletableFuture())
        lastStopOperation = operation
        try {
            worker.execute {
                var hardwareJoined = true
                var failure: Throwable? = null
                try { if (mic != null) hardwareJoined = mic.stopAndJoin() }
                catch (error: Throwable) { hardwareJoined = false; failure = error }
                // Privacy completion is bounded and independent of native disposal. If the reader
                // hangs, its exact hardware/consumer/decoder owner remains quarantined off-main.
                operation.hardwareStopped.complete(hardwareJoined)
                if (!hardwareJoined) mic?.awaitReaderExit()
                try {
                    if (flush) speech?.finishUtteranceAndJoin()
                } catch (error: Throwable) {
                    if (failure == null) failure = error
                }
                // Never finalize or close the VAD/native consumer underneath a live reader.
                try { speech?.close() }
                catch (error: Throwable) { if (failure == null) failure = error }
                try {
                    speech?.closeAndJoin()
                } catch (error: Throwable) {
                    if (failure == null) failure = error
                } finally {
                    main.post {
                        mic?.close()
                        synchronized(this) {
                            if (phase == OfflineSpeechSessionPhase.Finalizing && !starting && capture == null && recognizer == null) {
                                transitionPhase(OfflineSpeechSessionPhase.Idle)
                            }
                        }
                        if (failure == null && hardwareJoined) operation.completion.complete(Unit)
                        else operation.completion.completeExceptionally(
                            failure ?: IllegalStateException("Microphone reader did not stop; capture lease remains held")
                        )
                    }
                }
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            operation.hardwareStopped.completeExceptionally(error)
            operation.completion.completeExceptionally(error)
        }
        return operation
    }

    private fun completedStopOperation() = StopOperation(
        CompletableFuture.completedFuture(true),
        CompletableFuture.completedFuture(Unit),
    )

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
            val interruptedCapture = synchronized(this) {
                val active = starting || capture != null
                stopWorker(flush = false)
                active
            }
            if (interruptedCapture) {
                main.post { if (!closed.get()) onMessage("Capture stopped on background; tap the mic to resume.") }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        owner.lifecycle.removeObserver(this)
        stopWorker(flush = false)
        worker.shutdown()
    }

    private fun currentGeneration(): Long = synchronized(this) { generation }

    @Synchronized
    private fun transitionPhase(next: OfflineSpeechSessionPhase) {
        if (phase == next) return
        phase = next
        val notify = { if (synchronized(this) { phase == next }) onPhaseChanged(next) }
        if (Looper.myLooper() == Looper.getMainLooper()) notify() else main.post { notify() }
    }

    private fun report(message: String) { main.post { if (!closed.get()) onMessage(message) } }
}

private suspend fun <T> CompletableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
    whenComplete { value, error ->
        if (error == null) continuation.resume(value)
        else continuation.resumeWithException(error)
    }
}
