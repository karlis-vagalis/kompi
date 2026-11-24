package com.kompi.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.util.Log
import com.kompi.app.diagnostics.LocalDiagnostics
import com.google.mediapipe.tasks.audio.texttospeech.SynthesisOptions
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeech as MediaPipeTts
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeechOptions
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class KokoroSpeechStatus { Queued, Playing, Completed, Cancelled, Failed }

internal enum class KokoroPlaybackReceiptStatus { Unavailable, Available, Faulted }

data class KokoroSpeechRequestStatus(
    val requestId: String,
    val state: KokoroSpeechStatus,
    val failureMessage: String? = null,
)

sealed interface KokoroPlaybackPhase {
    data object Idle : KokoroPlaybackPhase
    data class Preparing(val requestId: String, val sequence: Long, val phrase: String) : KokoroPlaybackPhase
    data class Playing(val requestId: String, val sequence: Long, val phrase: String, val pcmRms: Float, val waveform: AudioWaveform) : KokoroPlaybackPhase
    data class Draining(val requestId: String, val sequence: Long, val phrase: String) : KokoroPlaybackPhase
}

private fun KokoroPlaybackPhase.requestIdForClear(): String? = when (this) {
    is KokoroPlaybackPhase.Preparing -> requestId
    is KokoroPlaybackPhase.Playing -> requestId
    is KokoroPlaybackPhase.Draining -> requestId
    KokoroPlaybackPhase.Idle -> null
}

private fun pcmRms(samples: FloatArray, offset: Int, count: Int): Float {
    var sum = 0.0
    for (index in offset until offset + count) {
        val sample = samples[index]
        if (sample.isFinite()) sum += sample.toDouble() * sample.toDouble()
    }
    return kotlin.math.sqrt(sum / count).toFloat().coerceIn(0f, 1f)
}

sealed interface KokoroTtsState {
    data object NotDownloaded : KokoroTtsState
    data class Downloading(val receivedBytes: Long, val totalBytes: Long, val progress: Float) : KokoroTtsState
    data object Initializing : KokoroTtsState
    data class Ready(
        val modelName: String,
        val voiceId: String,
        val languageLabel: String,
        val storageBytes: Long,
    ) : KokoroTtsState
    data class Failed(val message: String, val retryable: Boolean) : KokoroTtsState
    data object Closed : KokoroTtsState
}

/**
 * Offline Kokoro-82M TTS. Does not download anything except from [downloadModels], explicitly
 * invoked by the user's Download models action. Synthesis and PCM playback run away from UI thread.
 */
class KokoroTtsEngine(
    context: Context,
    private val playbackPhaseObserver: ((KokoroPlaybackPhase) -> Unit)? = null,
) : Closeable {
    private data class Speech(val requestId: String, val sequence: Long, val generation: Long, val phrase: SynthesisText, val voiceId: KokoroVoiceId, val admission: SpeechOutputAdmission, val queuedAtNanos: Long = System.nanoTime()) {
        val text: String get() = phrase.source
    }
    private sealed interface SynthesisPacket
    private data class PcmPacket(val speech: Speech, val samples: FloatArray, val sampleRateHz: Int) : SynthesisPacket
    private data class FailedSynthesis(val error: Exception) : SynthesisPacket
    private class NoPcmSpeechException : IllegalStateException("Kokoro produced no PCM for a phrase")
    private enum class RequestDisposition { Accepting, Finished, Cancelled }
    private enum class EnqueueOutcome { Accepted, Backpressured, Unavailable }
    private data class SpeechInput(val requestId: String, val sequence: Long, val phrase: SynthesisText)
    private data class RequestState(
        var disposition: RequestDisposition,
        var nextSequence: Long,
        var pendingPhrases: Int = 0,
        val voiceId: KokoroVoiceId? = null,
    )

    private val appContext = context.applicationContext
    private val modelInstaller = KokoroModelInstaller(File(appContext.filesDir, "kokoro-models"))
    private val closed = AtomicBoolean(false)
    private val lifecycleMutex = Mutex()
    /** Never close a LiteRT runtime while a synthesis call is still using it. */
    private val runtimeMutex = Mutex()
    private val mutableState = MutableStateFlow<KokoroTtsState>(KokoroTtsState.NotDownloaded)
    val state: StateFlow<KokoroTtsState> = mutableState.asStateFlow()
    private val mutableAvailableVoices = MutableStateFlow<List<KokoroVoice>>(emptyList())
    val availableVoices: StateFlow<List<KokoroVoice>> = mutableAvailableVoices.asStateFlow()
    private val mutableSelectedVoice = MutableStateFlow<KokoroVoice?>(null)
    val selectedVoice: StateFlow<KokoroVoice?> = mutableSelectedVoice.asStateFlow()
    @Volatile private var preferredVoiceId = KokoroVoiceId.DEFAULT
    private val mutableEnabled = MutableStateFlow(true)
    val isEnabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()
    private val mutableRequestStatuses = MutableStateFlow<Map<String, KokoroSpeechRequestStatus>>(emptyMap())
    /** Latest bounded per-request playback states; Completed means playback drained, not just synthesized. */
    val requestStatuses: StateFlow<Map<String, KokoroSpeechRequestStatus>> = mutableRequestStatuses.asStateFlow()
    private val mutablePlaybackPhase = MutableStateFlow<KokoroPlaybackPhase>(KokoroPlaybackPhase.Idle)
    /** Exact phrase currently synthesized/played by the real AudioTrack path; no word timing is implied. */
    val playbackPhase: StateFlow<KokoroPlaybackPhase> = mutablePlaybackPhase.asStateFlow()
    @Volatile internal var playbackPhaseReceiptStatus = if (playbackPhaseObserver == null)
        KokoroPlaybackReceiptStatus.Unavailable else KokoroPlaybackReceiptStatus.Available
        private set

    /** Actual assignments (including equal values), not DAC evidence. Called under stateLock.
     * The constructor-latched observer must be short, nonblocking and non-reentrant. Its failure
     * faults only the receipt; it must never become a playback failure or change the flow write. */
    private fun publishPlaybackPhase(phase: KokoroPlaybackPhase) {
        mutablePlaybackPhase.value = phase
        val observer = playbackPhaseObserver ?: return
        if (playbackPhaseReceiptStatus == KokoroPlaybackReceiptStatus.Faulted) return
        try { observer(phase) }
        catch (_: Throwable) { playbackPhaseReceiptStatus = KokoroPlaybackReceiptStatus.Faulted }
    }

    // Do not pin CPU cores or boost inference to real-time/audio priority. Only the
    // short, blocking AudioTrack writer gets audio priority; GPU inference is separate.
    private val inferenceDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE)
            task.run()
        }, "KokoroInference")
    }.asCoroutineDispatcher()
    private val playbackDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            task.run()
        }, "KokoroPlayback")
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + playbackDispatcher)
    private val queue = Channel<Speech>(capacity = MAX_QUEUE)
    private val queueSpace = Channel<Unit>(capacity = Channel.CONFLATED)
    private val requestStates = LinkedHashMap<String, RequestState>()
    private var speechGeneration = 0L
    private val stateLock = Any()
    @Volatile private var runtime: MediaPipeTts? = null
    @Volatile private var activeRequestId: String? = null
    @Volatile private var activeSynthesis: Job? = null
    @Volatile private var activeDownload: Job? = null
    @Volatile private var activeTrack: AudioTrack? = null
    @Volatile private var activeRenderReference: DuplexEchoProcessor.RenderLease? = null
    // Only the synthesis job hands this back to the queue owner, which waits for that job to join.
    private var deferredSpeech: Speech? = null
    private val consumerJob: Job

    init {
        consumerJob = scope.launch {
            while (isActive) {
                val speech = deferredSpeech?.also { deferredSpeech = null }
                    ?: queue.receiveCatching().getOrNull()?.also { queueSpace.trySend(Unit) } ?: break
                val cancelledBeforeStart = synchronized(stateLock) {
                    activeRequestId = speech.requestId
                    isSpeechCancelledLocked(speech)
                }
                if (cancelledBeforeStart) {
                    synchronized(stateLock) { activeRequestId = null }
                    finishPhrase(speech)
                    continue
                }
                synchronized(stateLock) {
                    setRequestStatusLocked(speech.requestId, KokoroSpeechStatus.Playing)
                    publishPlaybackPhase(KokoroPlaybackPhase.Preparing(speech.requestId, speech.sequence, speech.text))
                }
                val batch = arrayListOf(speech)
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        synthesizeAndPlay(speech, batch)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (!isSpeechCancelled(speech) && !closed.get()) {
                            // Keep native teardown out of this failure/cancellation race. A later
                            // initialize(), download, or close releases the idle runtime under
                            // runtimeMutex after the consumer has quiesced.
                            Log.e(TAG, "Kokoro synthesis failed for request ${speech.requestId}", error)
                            synchronized(stateLock) {
                                requestStates[speech.requestId]?.disposition = RequestDisposition.Cancelled
                                setRequestStatusLocked(speech.requestId, KokoroSpeechStatus.Failed, error.message ?: "Kokoro is unavailable")
                                discardQueuedPhrasesLocked(speech.requestId)
                            }
                            // Empty synthesis is a phrase failure, not proof that installed voice/runtime is missing.
                            if (error !is NoPcmSpeechException) setStateUnlessClosed(KokoroTtsState.Failed(error.message ?: "Kokoro is unavailable", retryable = true))
                            queueSpace.trySend(Unit)
                        }
                    }
                }
                synchronized(stateLock) { activeSynthesis = job }
                job.start()
                try {
                    job.join()
                } catch (cancelled: CancellationException) {
                    if (!isActive) throw cancelled
                } finally {
                    (playbackLease as? PlaybackAudioLease.BorrowedLive)?.fenceHardware()
                    activeRenderReference?.close()
                    activeRenderReference = null
                    activeTrack?.let(::releaseTrack)
                    activeTrack = null
                    playbackLease?.close()
                    playbackLease = null
                    activeRequestId = null
                    activeSynthesis = null
                    var unexpectedCancellation = false
                    synchronized(stateLock) {
                        // join() does not rethrow a child's cancellation, including cancellation
                        // before a LAZY worker starts. Never promote that outcome to Completed.
                        if (job.isCancelled && !isSpeechCancelledLocked(speech)) {
                            requestStates[speech.requestId]?.disposition = RequestDisposition.Cancelled
                            setRequestStatusLocked(speech.requestId, KokoroSpeechStatus.Failed, INCOMPLETE_PLAYBACK_MESSAGE)
                            discardQueuedPhrasesLocked(speech.requestId)
                            unexpectedCancellation = true
                        }
                        if (mutablePlaybackPhase.value.requestIdForClear() == speech.requestId) publishPlaybackPhase(KokoroPlaybackPhase.Idle)
                    }
                    if (unexpectedCancellation) queueSpace.trySend(Unit)
                    batch.forEach { phrase ->
                        LocalDiagnostics.cancel(LocalDiagnostics.Metric.SpeechStartup, "${phrase.requestId}:${phrase.sequence}")
                        finishPhrase(phrase)
                    }
                }
            }
        }
    }

    /** Probe/load an already-installed model only. This never downloads weights. */
    suspend fun initialize(): Result<Unit> = lifecycleMutex.withLock {
        if (closed.get()) return@withLock Result.failure(IllegalStateException("Kokoro engine is closed"))
        val asset = modelInstaller.selectedAsset()
        stop()
        activeSynthesis?.join()
        val model = modelInstaller.installed(asset)
            ?: run {
                mutableState.value = KokoroTtsState.NotDownloaded
                return@withLock Result.failure(IllegalStateException("Kokoro model is not downloaded"))
            }
        mutableState.value = KokoroTtsState.Initializing
        try {
            verifyModel(model, asset.sha256)
            createRuntime(model, asset)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            if (closed.get()) setClosedState() else throw cancelled
            Result.failure(cancelled)
        } catch (error: Exception) {
            setStateUnlessClosed(KokoroTtsState.Failed(error.message ?: "Could not initialize Kokoro", retryable = true))
            Result.failure(error)
        }
    }

    /** Call only from an explicit user gesture. Cancelling this coroutine removes the partial file. */
    suspend fun downloadModels(): Result<Unit> = lifecycleMutex.withLock {
        if (closed.get()) return@withLock Result.failure(IllegalStateException("Kokoro engine is closed"))
        val asset = modelInstaller.selectedAsset()
        val downloadJob = currentCoroutineContext()[Job]
        activeDownload = downloadJob
        try {
            setStateUnlessClosed(KokoroTtsState.Downloading(0, asset.sizeBytes, 0f))
            stop()
            activeSynthesis?.join()
            runtimeMutex.withLock {
                withContext(inferenceDispatcher) { runtime?.close() }
                runtime = null
            }
            val model = modelInstaller.download(
                onProgress = { received, total ->
                    setStateUnlessClosed(KokoroTtsState.Downloading(received, total, received.toFloat() / total.toFloat()))
                },
                asset = asset,
            )
            setStateUnlessClosed(KokoroTtsState.Initializing)
            createRuntime(model, asset)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            if (closed.get()) setClosedState() else setStateUnlessClosed(KokoroTtsState.NotDownloaded)
            throw cancelled
        } catch (error: Exception) {
            setStateUnlessClosed(KokoroTtsState.Failed(error.message ?: "Kokoro model download failed", retryable = true))
            Result.failure(error)
        } finally {
            if (activeDownload === downloadJob) activeDownload = null
        }
    }

    /** Cancels the active explicit model transfer; a partial asset is deleted by the installer. */
    fun cancelDownload() {
        activeDownload?.cancel(CancellationException("Kokoro model download cancelled"))
    }

    /**
     * Non-blocking phrase enqueue. Caller should send short sentence/clause chunks and must handle
     * false (model unavailable, invalid/oversize text, or bounded queue full) without blocking UI.
     */
    fun enqueue(requestId: String, sequence: Long, text: String): Boolean {
        val input = parseSpeechInput(requestId, sequence, text) ?: return false
        val admission = try { SpeechOutputAdmission.capture(requestId, sequence) } catch (_: IllegalStateException) { return false }
        return try { offerSpeech(input, admission, cancelIfFull = true) == EnqueueOutcome.Accepted }
        finally { admission.closePending() }
    }

    /** Bounded backpressure for long manual read-aloud; never cancels a request merely for a full queue. */
    suspend fun enqueueWhenAvailable(requestId: String, sequence: Long, text: String): Boolean {
        val input = parseSpeechInput(requestId, sequence, text) ?: return false
        val admission = currentCoroutineContext()[SpeechOutputAdmission]
            ?: try { SpeechOutputAdmission.capture(requestId, sequence) } catch (_: IllegalStateException) { return false }
        val generation = synchronized(stateLock) { speechGeneration }
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                when (offerSpeech(input, admission, cancelIfFull = false, expectedGeneration = generation)) {
                    EnqueueOutcome.Accepted -> return true
                    EnqueueOutcome.Unavailable -> return false
                    EnqueueOutcome.Backpressured -> if (queueSpace.receiveCatching().getOrNull() == null) return false
                }
            }
        } finally { admission.closePending() }
    }

    private fun parseSpeechInput(requestId: String, sequence: Long, text: String): SpeechInput? {
        if (requestId.isBlank() || requestId.length > MAX_REQUEST_ID_CHARS || sequence < 0) return null
        val phrase = SynthesisText.parse(text, MAX_TEXT_CHARS) ?: return null
        return SpeechInput(requestId, sequence, phrase)
    }

    private fun offerSpeech(input: SpeechInput, admission: SpeechOutputAdmission, cancelIfFull: Boolean, expectedGeneration: Long? = null): EnqueueOutcome {
        val (requestId, sequence, phrase) = input
        synchronized(stateLock) {
            if (closed.get() || !mutableEnabled.value || runtime == null || mutableState.value !is KokoroTtsState.Ready ||
                (expectedGeneration != null && expectedGeneration != speechGeneration)
            ) return EnqueueOutcome.Unavailable
            val request = requestStates[requestId]
            if (request != null && request.disposition != RequestDisposition.Accepting) return EnqueueOutcome.Unavailable
            if (request == null) {
                makeRoomForRequestLocked()
                if (requestStates.size >= MAX_TRACKED_REQUESTS || sequence != 0L) return EnqueueOutcome.Unavailable
                val voiceId = mutableSelectedVoice.value?.id ?: return EnqueueOutcome.Unavailable
                requestStates[requestId] = RequestState(RequestDisposition.Accepting, 0L, voiceId = voiceId)
            }
            val state = requestStates.getValue(requestId)
            val voiceId = state.voiceId ?: return EnqueueOutcome.Unavailable
            if (state.nextSequence != sequence) {
                state.disposition = RequestDisposition.Cancelled
                setRequestStatusLocked(requestId, KokoroSpeechStatus.Cancelled)
                return EnqueueOutcome.Unavailable
            }
            val transferred = admission.tryTransfer {
                queue.trySend(Speech(requestId, sequence, speechGeneration, phrase, voiceId, admission)).isSuccess
            } ?: return EnqueueOutcome.Unavailable
            if (transferred) {
                com.kompi.app.diagnostics.SpeechAudit.record(requestId, sequence, "queued", "source_characters=${phrase.source.length} input_characters=${phrase.input.length}")
                state.nextSequence++
                state.pendingPhrases++
                setRequestStatusLocked(requestId, if (activeRequestId == requestId) KokoroSpeechStatus.Playing else KokoroSpeechStatus.Queued)
                return EnqueueOutcome.Accepted
            }
            if (!cancelIfFull) return EnqueueOutcome.Backpressured
            state.disposition = RequestDisposition.Cancelled
            setRequestStatusLocked(requestId, KokoroSpeechStatus.Cancelled)
            return EnqueueOutcome.Unavailable
        }
    }

    /** Restores a validated preference before model initialization; the model catalog resolves it. */
    fun restoreVoicePreference(voiceId: KokoroVoiceId?) {
        synchronized(stateLock) { preferredVoiceId = voiceId ?: KokoroVoiceId.DEFAULT }
    }

    /** Selects only a voice present in the installed model's available catalog. */
    fun selectVoice(voiceId: KokoroVoiceId): Boolean = synchronized(stateLock) {
        val voice = mutableAvailableVoices.value.firstOrNull { it.id == voiceId } ?: return@synchronized false
        preferredVoiceId = voice.id
        mutableSelectedVoice.value = voice
        (mutableState.value as? KokoroTtsState.Ready)?.let { ready ->
            mutableState.value = ready.copy(voiceId = voice.id.value, languageLabel = voice.languageLabel)
        }
        true
    }

    /** Mute/unmute speech. Muting also interrupts active synthesis and playback. */
    fun setEnabled(value: Boolean) {
        if (closed.get()) return
        mutableEnabled.value = value
        if (!value) stop()
    }

    /** Accepting requests can be idle between Pi tool steps; their last status is not queue occupancy. */
    fun hasPendingSpeech(requestId: String): Boolean = synchronized(stateLock) {
        requestStates[requestId]?.let { it.disposition != RequestDisposition.Cancelled && it.pendingPhrases > 0 } == true
    }

    /** Marks a completed request as terminal so late chunks with the same ID cannot be queued. */
    fun finishRequest(requestId: String) {
        if (requestId.isBlank() || requestId.length > MAX_REQUEST_ID_CHARS) return
        synchronized(stateLock) {
            val request = requestStates[requestId]
            if (request != null) {
                if (request.disposition == RequestDisposition.Accepting) request.disposition = RequestDisposition.Finished
                if (request.disposition == RequestDisposition.Finished && request.pendingPhrases == 0) {
                    setRequestStatusLocked(requestId, KokoroSpeechStatus.Completed)
                }
            } else {
                makeRoomForRequestLocked()
                if (requestStates.size < MAX_TRACKED_REQUESTS) {
                    requestStates[requestId] = RequestState(RequestDisposition.Finished, 0L)
                    setRequestStatusLocked(requestId, KokoroSpeechStatus.Completed)
                }
            }
        }
    }

    /** Stops one phone reply or all queued/active speech. Null is appropriate for barge-in/mute. */
    fun stop(requestId: String? = null) {
        val jobToCancel: Job?
        val trackToStop: AudioTrack?
        val referenceToStop: DuplexEchoProcessor.RenderLease?
        val engineToCancel: MediaPipeTts?
        val playbackToFence: PlaybackAudioLease?
        synchronized(stateLock) {
            if (requestId == null) {
                speechGeneration++
                requestStates.forEach { (id, state) ->
                    state.disposition = RequestDisposition.Cancelled
                    if (mutableRequestStatuses.value[id]?.state in setOf(KokoroSpeechStatus.Queued, KokoroSpeechStatus.Playing)) {
                        setRequestStatusLocked(id, KokoroSpeechStatus.Cancelled)
                    }
                }
                while (true) {
                    val discarded = queue.tryReceive().getOrNull() ?: break
                    com.kompi.app.diagnostics.SpeechAudit.record(discarded.requestId, discarded.sequence, "phrase_discarded", "stop_all")
                    discarded.admission.releaseEngine()
                }
                requestStates.forEach { (id, state) -> state.pendingPhrases = if (id == activeRequestId) 1 else 0 }
                jobToCancel = activeSynthesis
            } else {
                if (requestId.length <= MAX_REQUEST_ID_CHARS) {
                    if (requestId !in requestStates) makeRoomForRequestLocked()
                    val request = requestStates[requestId]
                    if (request != null) request.disposition = RequestDisposition.Cancelled
                    else if (requestStates.size < MAX_TRACKED_REQUESTS) {
                        requestStates[requestId] = RequestState(RequestDisposition.Cancelled, 0L)
                    }
                    if (requestId in requestStates &&
                        (mutableRequestStatuses.value[requestId]?.state in setOf(KokoroSpeechStatus.Queued, KokoroSpeechStatus.Playing) || request == null)
                    ) {
                        setRequestStatusLocked(requestId, KokoroSpeechStatus.Cancelled)
                    }
                }
                val keep = ArrayList<Speech>()
                var removedCount = 0
                while (true) {
                    val next = queue.tryReceive().getOrNull() ?: break
                    if (next.requestId != requestId) keep += next else {
                        removedCount++
                        com.kompi.app.diagnostics.SpeechAudit.record(next.requestId, next.sequence, "phrase_discarded", "request_stopped")
                        next.admission.releaseEngine()
                    }
                }
                keep.forEach { queue.trySend(it) }
                requestStates[requestId]?.let { it.pendingPhrases = (it.pendingPhrases - removedCount).coerceAtLeast(0) }
                jobToCancel = activeSynthesis.takeIf { activeRequestId == requestId }
            }
            trackToStop = activeTrack.takeIf { jobToCancel != null }
            referenceToStop = activeRenderReference.takeIf { jobToCancel != null }
            playbackToFence = playbackLease.takeIf { jobToCancel != null }
            engineToCancel = runtime
            if (jobToCancel != null) publishPlaybackPhase(KokoroPlaybackPhase.Idle)
        }
        queueSpace.trySend(Unit)
        if (jobToCancel != null) {
            val cancellationStarted = System.nanoTime()
            jobToCancel.invokeOnCompletion {
                Log.i(TAG, "Kokoro cancellation: workerExitMs=${(System.nanoTime() - cancellationStarted) / 1_000_000}")
            }
            // Stop audible output first. Native GPU cancellation may wait for a kernel and must
            // not block the UI; attach it to the worker so join()/runtime replacement still waits.
            (playbackToFence as? PlaybackAudioLease.BorrowedLive)?.fenceHardware()
            trackToStop?.let { track ->
                runCatching { track.pause() }
                referenceToStop?.freeze() // Freeze this exact track's head BEFORE flush resets it.
                runCatching { track.flush() }
            }
            // UNDISTPATCHED registers this child before cancelling its parent. Its initial
            // dispatcher must differ from IO: otherwise withContext(IO) can run native cancel
            // inline on the actual UI/safety caller despite the misleading IO context tag.
            CoroutineScope(jobToCancel + Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
                // A completed parent rejects this child; never cancel a newer native stream.
                if (!isActive) return@launch
                withContext(NonCancellable + Dispatchers.IO) { runCatching { engineToCancel?.cancel() } }
            }
            jobToCancel.cancel(CancellationException("Kokoro speech stopped"))
            Log.i(TAG, "Kokoro cancellation requested: uiStopMs=${(System.nanoTime() - cancellationStarted) / 1_000_000}")
        }
    }

    private suspend fun createRuntime(model: File, asset: KokoroModelInstaller.ModelAsset) {
        runtimeMutex.withLock {
            withContext(inferenceDispatcher) {
            val delegate = when (asset.delegate) {
                KokoroModelInstaller.EngineDelegate.CPU -> Delegate.CPU
                KokoroModelInstaller.EngineDelegate.GPU -> Delegate.GPU
            }
            val options = TextToSpeechOptions(
                baseOptions = BaseOptions.builder()
                    .setModelAssetPath(model.absolutePath)
                    .setDelegate(delegate)
                    .build(),
                defaultVoiceId = asset.voice,
                speakingRate = 1.0f,
                targetBucket = TARGET_BUCKET,
                cacheDirectory = File(appContext.cacheDir, "kokoro-cache").apply { mkdirs() }.absolutePath,
            )
            val replacement = MediaPipeTts.create(options)
            try {
                val defaultVoiceId = KokoroVoiceId.fromModel(asset.voice)
                    ?: error("Pinned model contains an invalid default voice ID")
                val voices = replacement.listVoices()
                    .mapNotNull { KokoroVoice.fromModel(it, defaultVoiceId) }
                    .distinctBy { it.id }
                    .sortedWith(compareBy<KokoroVoice> { it.languageLabel }.thenBy { it.displayName }.thenBy { it.id.value })
                val defaultVoice = voices.firstOrNull { it.id == defaultVoiceId }
                    ?: error("Pinned model does not provide required voice ${asset.voice}")
                val selectedVoice = voices.firstOrNull { it.id == preferredVoiceId } ?: defaultVoice
                if (closed.get()) throw CancellationException("Kokoro engine closed during initialization")
                runtime?.close()
                preparedCue = null
                runtime = replacement
                mutableAvailableVoices.value = voices
                mutableSelectedVoice.value = selectedVoice
                preferredVoiceId = selectedVoice.id
            } catch (error: Throwable) {
                runCatching { replacement.close() }
                    .onFailure { Log.w(TAG, "Could not release incomplete Kokoro runtime", it) }
                throw error
            }
            synchronized(stateLock) {
                if (closed.get()) throw CancellationException("Kokoro engine closed during initialization")
                mutableState.value = KokoroTtsState.Ready(
                    modelName = asset.label,
                    voiceId = mutableSelectedVoice.value?.id?.value ?: asset.voice,
                    languageLabel = mutableSelectedVoice.value?.languageLabel ?: DEFAULT_LANGUAGE_LABEL,
                    storageBytes = model.length(),
                )
            }
            }
        }
    }

    private data class CueChunk(val samples: FloatArray, val sampleRateHz: Int)
    private data class PreparedCue(val voice: KokoroVoiceId, val input: String, val chunks: List<CueChunk>)
    // Accessed only under runtimeMutex. Exactly one fixed phrase, never user or reply audio.
    private var preparedCue: PreparedCue? = null
    fun prepareToolCue(prefix: String) {
        val phrase = listOf(prefix.trim().take(39), com.kompi.app.stream.ActivityCueText.Tools)
            .filter { it.isNotEmpty() }.joinToString(" ")
        val input = SynthesisText.parse(phrase, MAX_TEXT_CHARS)?.input ?: return
        scope.launch(inferenceDispatcher) {
            runtimeMutex.withLock {
                val engine = runtime ?: return@withLock
                val voice = mutableSelectedVoice.value?.id ?: return@withLock
                if (!mutableEnabled.value || preparedCue?.let { it.voice == voice && it.input == input } == true) return@withLock
                try {
                    val chunks = mutableListOf<CueChunk>()
                    var samples = 0L
                    engine.synthesizeStream(input, SynthesisOptions(voiceId = voice.value)).collect {
                        samples += it.samples.size
                        check(samples <= 480_000)
                        if (it.samples.isNotEmpty()) chunks += CueChunk(it.samples.copyOf(), it.sampleRateHz)
                    }
                    if (chunks.isNotEmpty()) preparedCue = PreparedCue(voice, input, chunks)
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { /* Real speech still has its normal synthesis path. */ }
            }
        }
    }

    private suspend fun synthesizeAndPlay(first: Speech, batch: MutableList<Speech>) {
        if (isSpeechCancelled(first)) throw CancellationException("Speech stopped")
        // The writer outlives the inner producer scope: its real partial EOF write and drain
        // must test the owning synthesis job, not that already-completed ScopeCoroutine.
        val writeContext = currentCoroutineContext()
        val benchmarkPreview = first.requestId.startsWith("preview-")
        val allocatedBefore = if (benchmarkPreview) Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() else null
        val gcBefore = if (benchmarkPreview) Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() else null
        val cpuBefore = if (benchmarkPreview) Process.getElapsedCpuTime() else 0L
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermalBefore = if (benchmarkPreview && Build.VERSION.SDK_INT >= 29) powerManager.currentThermalStatus else -1
        val startedAt = System.nanoTime()
        var producerComputeNanos = 0L
        var producerBackpressureNanos = 0L
        var writeNanos = 0L
        var writeSlices = 0L
        var samplesWritten = 0L
        var nonzeroSamples = 0L
        var sampleRate = 0
        var pcmWriter: LivePcmBlockWriter? = null
        var lastStartedSequence = -1L
        var playbackStarted = false
        var lastMeterFrame = 0L
        var lastSpeech = first
        val permit = PlaybackAudioLease.acquireForDemand(appContext, {
            if (!isSpeechCancelled(first)) stop(first.requestId)
        }, first.admission.demand)
        synchronized(stateLock) {
            if (isSpeechCancelledLocked(first)) { permit.close(); throw CancellationException("Speech stopped") }
            playbackLease = permit
        }
        var synthesisFailure: Exception? = null
        var producerWasCancelled = false
        coroutineScope {
            // Native inference must not wait for a blocking AudioTrack write. Two native PCM chunks
            // provide bounded lookahead, including the next already-queued clause of this request.
            val pcm = Channel<SynthesisPacket>(capacity = PCM_LOOKAHEAD_CHUNKS)
            val producer = launch(inferenceDispatcher) {
                // Only native inference owns the model mutex. The independent writer may await
                // an OS communication transition without holding a model/native/privacy lock.
                runtimeMutex.withLock {
                val engine = runtime ?: error("Kokoro runtime is unavailable")
                try {
                    var speech = first
                    while (true) {
                        if (isSpeechCancelled(speech)) throw CancellationException("Speech stopped")
                        com.kompi.app.diagnostics.SpeechAudit.record(speech.requestId, speech.sequence, "synthesis_started")
                        val synthesisStarted = System.nanoTime()
                        val metricKey = "${speech.requestId}:${speech.sequence}"
                        LocalDiagnostics.begin(LocalDiagnostics.Metric.SpeechStartup, metricKey)
                        var firstPcm = true
                        var producedSamples = 0L
                        var sendWaitNanos = 0L
                        if (speech.phrase.input != speech.text) {
                            Log.i(TAG, "Kokoro text formatting: request=${speech.requestId} seq=${speech.sequence} sourceCharacters=${speech.text.length} inputCharacters=${speech.phrase.input.length}")
                        }
                        val cached = preparedCue?.takeIf { speech.requestId.startsWith("activity-") && it.input == speech.phrase.input && it.voice == speech.voiceId }
                        if (cached != null) com.kompi.app.diagnostics.SpeechAudit.record(speech.requestId, speech.sequence, "prepared_cue_used")
                        val audio = kotlinx.coroutines.flow.flow {
                            if (cached != null) for (chunk in cached.chunks) emit(chunk)
                            else engine.synthesizeStream(speech.phrase.input, SynthesisOptions(voiceId = speech.voiceId.value)).collect {
                                emit(CueChunk(it.samples, it.sampleRateHz))
                            }
                        }
                        audio.collect { chunk ->
                            if (isSpeechCancelled(speech)) throw CancellationException("Speech stopped")
                            if (chunk.samples.isEmpty()) return@collect
                            if (firstPcm) {
                                firstPcm = false
                                com.kompi.app.diagnostics.SpeechAudit.record(speech.requestId, speech.sequence, "first_pcm")
                                Log.i(TAG, "Kokoro first PCM: request=${speech.requestId} seq=${speech.sequence} latencyMs=${(System.nanoTime() - synthesisStarted) / 1_000_000} queueWaitMs=${(synthesisStarted - speech.queuedAtNanos) / 1_000_000} sampleRateHz=${chunk.sampleRateHz}")
                            }
                            producedSamples += chunk.samples.size
                            val sendStarted = System.nanoTime()
                            pcm.send(PcmPacket(speech, chunk.samples, chunk.sampleRateHz))
                            sendWaitNanos += System.nanoTime() - sendStarted
                        }
                        producerComputeNanos += System.nanoTime() - synthesisStarted - sendWaitNanos
                        producerBackpressureNanos += sendWaitNanos
                        if (producedSamples == 0L) {
                            Log.w(TAG, "Kokoro empty phrase: request=${speech.requestId} seq=${speech.sequence} characters=${speech.text.length} letters=${speech.text.count { it.isLetter() }} digits=${speech.text.count { it.isDigit() }}")
                            throw NoPcmSpeechException()
                        }
                        Log.i(TAG, "Kokoro synthesis: request=${speech.requestId} seq=${speech.sequence} wallMs=${(System.nanoTime() - synthesisStarted) / 1_000_000} pcmSamples=$producedSamples")
                        val next = queue.tryReceive().getOrNull()?.also { queueSpace.trySend(Unit) } ?: break
                        // Never speculate across request, generation or voice boundaries.
                        if (next.requestId != first.requestId || next.generation != first.generation || next.voiceId != first.voiceId ||
                            next.admission.demand?.session !== first.admission.demand?.session) {
                            deferredSpeech = next
                            break
                        }
                        batch += next
                        speech = next
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // Deliver errors after the PCM already produced. Throwing from this child
                    // would cancel its sibling writer and erase valid prefetched speech.
                    pcm.send(FailedSynthesis(error))
                } finally {
                    pcm.close()
                }
                }
            }
            // Also close on cancellation before the body starts or while awaiting runtimeMutex.
            producer.invokeOnCompletion { pcm.close() }
            try {
                for (result in pcm) {
                    val packet = when (result) {
                        is PcmPacket -> result
                        is FailedSynthesis -> { synthesisFailure = result.error; break }
                    }
                    val speech = packet.speech
                    if (isSpeechCancelled(speech)) throw CancellationException("Speech stopped")
                    if (sampleRate == 0) {
                        val renderAdmission = if (permit is PlaybackAudioLease.BorrowedLive)
                            permit.session.awaitSpeechRenderProducer() else null
                        sampleRate = packet.sampleRateHz
                        val created = try { when (permit) {
                            is PlaybackAudioLease.BorrowedLive -> {
                                val hardware = permit.session.ensureHardware(checkNotNull(first.admission.demand))
                                hardware.requireCurrent()
                                permit.session.whileActive { createTrack(sampleRate, permit.attributes, live = true) }
                            }
                            is PlaybackAudioLease.OwnedStandalone -> { permit.requireActive(); createTrack(sampleRate, permit.attributes) }
                        } } catch (error: Throwable) { renderAdmission?.release(); throw error }
                        var reference: DuplexEchoProcessor.RenderLease? = null
                        try {
                            if (permit is PlaybackAudioLease.BorrowedLive) reference = permit.session.renderReference(created, sampleRate, checkNotNull(renderAdmission))
                            pcmWriter = LivePcmBlockWriter(created, sampleRate, reference) {
                                writeContext.ensureActive(); permit.requireActive()
                            }
                            synchronized(stateLock) {
                                if (isSpeechCancelledLocked(speech)) throw CancellationException("Speech stopped")
                                permit.requireActive()
                                activeTrack = created
                                activeRenderReference = reference
                            }
                        } catch (error: Throwable) { reference?.close(); renderAdmission?.release(); releaseTrack(created); throw error }
                    }
                    check(packet.sampleRateHz == sampleRate) { "Kokoro changed sample rate within a request" }
                    val track = activeTrack ?: error("Kokoro AudioTrack is unavailable")
                    val writeFrames = sampleRate * AUDIO_WRITE_MS / 1_000
                    var offset = 0
                    while (offset < packet.samples.size) {
                        if (isSpeechCancelled(speech)) throw CancellationException("Speech stopped")
                        val count = minOf(writeFrames, packet.samples.size - offset)
                        val firstFrame = samplesWritten + 1
                        val writeStarted = System.nanoTime()
                        permit.requireActive()
                        val accepted = checkNotNull(pcmWriter).append(packet.samples, offset, count)
                        writeNanos += System.nanoTime() - writeStarted
                        writeSlices++
                        samplesWritten += accepted
                        if (accepted == 0) { offset += count; continue }
                        if (!playbackStarted) {
                            // Prime with real PCM before play(), rather than starting an empty track.
                            synchronized(stateLock) {
                                if (isSpeechCancelledLocked(speech)) throw CancellationException("Speech stopped")
                                when (permit) {
                                    is PlaybackAudioLease.BorrowedLive -> permit.session.whileActive { track.play() }
                                    is PlaybackAudioLease.OwnedStandalone -> { permit.requireActive(); track.play() }
                                }
                                playbackStarted = true
                            }
                        }
                        if (lastStartedSequence != speech.sequence) {
                            // Observe onset independently: waiting here would starve the audio writer.
                            if (lastStartedSequence >= 0) {
                                val previousSequence = lastStartedSequence
                                launch(Dispatchers.IO) {
                                    awaitFirstPlaybackFrame(track, firstFrame - 1)
                                    com.kompi.app.diagnostics.SpeechAudit.record(speech.requestId, previousSequence, "phrase_drained")
                                }
                            }
                            launch(Dispatchers.IO) {
                                awaitFirstPlaybackFrame(track, firstFrame)
                                com.kompi.app.diagnostics.SpeechAudit.record(speech.requestId, speech.sequence, "playback_head_advanced")
                                LocalDiagnostics.finish(LocalDiagnostics.Metric.SpeechStartup, "${speech.requestId}:${speech.sequence}")
                                Log.i(TAG, "Kokoro first audio: request=${speech.requestId} seq=${speech.sequence} enqueueToAudioMs=${(System.nanoTime() - speech.queuedAtNanos) / 1_000_000}")
                            }
                            lastStartedSequence = speech.sequence
                            lastMeterFrame = 0L
                        }
                        if (lastMeterFrame == 0L || samplesWritten - lastMeterFrame >= sampleRate * AUDIO_METER_MS / 1_000) {
                            val playing = KokoroPlaybackPhase.Playing(
                                speech.requestId, speech.sequence, speech.text,
                                pcmRms(packet.samples, offset, count),
                                AudioWaveform.fromPcm(packet.samples, sampleRate, offset, count),
                            )
                            synchronized(stateLock) {
                                if (!isSpeechCancelledLocked(speech)) publishPlaybackPhase(playing)
                            }
                            lastMeterFrame = samplesWritten
                        }
                        offset += count
                    }
                    nonzeroSamples += packet.samples.count { it.isFinite() && kotlin.math.abs(it) > 1e-6f }
                    lastSpeech = speech
                }
                producer.join()
                // A cancelled producer can close PCM normally without failing this scope.
                // Preserve/drain its valid prefix, but do not report generation as complete.
                producerWasCancelled = producer.isCancelled
            } finally {
                producer.cancel()
                pcm.cancel()
            }
        }
        if (producerWasCancelled && pcmWriter == null) throw CancellationException(INCOMPLETE_PLAYBACK_MESSAGE)
        val track = activeTrack ?: throw (synthesisFailure ?: NoPcmSpeechException())
        samplesWritten += checkNotNull(pcmWriter).finish()
        if (samplesWritten == 0L) throw NoPcmSpeechException()
        if (!playbackStarted) synchronized(stateLock) {
            if (isSpeechCancelledLocked(first)) throw CancellationException("Speech stopped")
            when (permit) {
                is PlaybackAudioLease.BorrowedLive -> permit.session.whileActive { track.play() }
                is PlaybackAudioLease.OwnedStandalone -> { permit.requireActive(); track.play() }
            }
            playbackStarted = true
        }
        val drainStarted = System.nanoTime()
        activeRenderReference?.finishWrites()
        synchronized(stateLock) {
            if (isSpeechCancelledLocked(first)) throw CancellationException("Speech stopped")
            publishPlaybackPhase(KokoroPlaybackPhase.Draining(lastSpeech.requestId, lastSpeech.sequence, lastSpeech.text))
        }
        awaitPlaybackCompletion(track, samplesWritten, sampleRate).getOrThrow()
        com.kompi.app.diagnostics.SpeechAudit.record(lastSpeech.requestId, lastSpeech.sequence, "phrase_drained")
        val pipelineWallMs = (System.nanoTime() - startedAt) / 1_000_000
        Log.i(TAG, "Kokoro audio: request=${first.requestId} phrases=${batch.size} pcmSamples=$samplesWritten nonzeroPcmSamples=$nonzeroSamples durationMs=${samplesWritten * 1_000 / sampleRate} pipelineWallMs=$pipelineWallMs playbackDrainMs=${(System.nanoTime() - drainStarted) / 1_000_000} underruns=${track.underrunCount}")
        // A partial reply is still reported as failed, but only after its valid audio drains.
        if (producerWasCancelled) throw CancellationException(INCOMPLETE_PLAYBACK_MESSAGE)
        synthesisFailure?.let { throw it }
        if (benchmarkPreview) {
            val cpuMs = Process.getElapsedCpuTime() - cpuBefore
            val allocatedAfter = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
            val gcAfter = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
            val allocatedBytes = if (allocatedBefore != null && allocatedAfter != null) allocatedAfter - allocatedBefore else -1L
            val gcCount = if (gcBefore != null && gcAfter != null) gcAfter - gcBefore else -1L
            val thermalAfter = if (Build.VERSION.SDK_INT >= 29) powerManager.currentThermalStatus else -1
            // Preview-only: PSS/runtime-stat queries must not add work to ordinary reply/filler audio.
            // Process CPU/allocations include UI/native work; producer compute is not pure inference.
            Log.i(TAG, "Kokoro benchmark: request=${first.requestId} targetBucket=$TARGET_BUCKET cpuMs=$cpuMs allocatedBytes=$allocatedBytes gcCount=$gcCount pssKb=${Debug.getPss()} producerComputeMs=${producerComputeNanos / 1_000_000} producerBackpressureMs=${producerBackpressureNanos / 1_000_000} writeMs=${writeNanos / 1_000_000} writeSlices=$writeSlices thermalBefore=$thermalBefore thermalAfter=$thermalAfter")
        }
    }

    private suspend fun awaitFirstPlaybackFrame(track: AudioTrack, firstFrame: Long) {
        val timeoutAt = System.nanoTime() + FIRST_PCM_PLAYBACK_TIMEOUT_MS * 1_000_000L
        while ((track.playbackHeadPosition.toLong() and 0xffff_ffffL) < firstFrame) {
            currentCoroutineContext().ensureActive()
            check(System.nanoTime() < timeoutAt) { "Kokoro AudioTrack did not start playback" }
            delay(10)
        }
    }

    /** Success proves all accepted PCM played; timeout fails explicitly and cancellation still throws. */
    private suspend fun awaitPlaybackCompletion(track: AudioTrack, frameCount: Long, sampleRate: Int): Result<Unit> {
        val durationMs = frameCount * 1_000L / sampleRate
        val timeoutAt = System.nanoTime() + (durationMs + PLAYBACK_DRAIN_TIMEOUT_GRACE_MS) * 1_000_000L
        while (true) {
            currentCoroutineContext().ensureActive()
            val playedFrames = track.playbackHeadPosition.toLong() and 0xffff_ffffL
            if (playedFrames >= frameCount) return Result.success(Unit)
            if (System.nanoTime() >= timeoutAt) {
                Log.w(TAG, "Kokoro AudioTrack drain timed out: playedFrames=$playedFrames pcmFrames=$frameCount")
                return Result.failure(IllegalStateException("Kokoro audio playback timed out before all accepted frames played"))
            }
            delay(20)
        }
    }

    private fun createTrack(sampleRate: Int, attributes: AudioAttributes, live: Boolean = false): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        check(minBuffer > 0) { "AudioTrack does not support Kokoro PCM format" }
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(if (live) minBuffer else minBuffer * 3, sampleRate * AUDIO_BUFFER_MS / 1_000 * Float.SIZE_BYTES))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        check(track.state == AudioTrack.STATE_INITIALIZED) { "Could not initialize Kokoro AudioTrack" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            track.setStartThresholdInFrames(minOf(track.bufferSizeInFrames, sampleRate / 100))
        }
        Log.i(TAG, "Kokoro track: sampleRateHz=$sampleRate bufferFrames=${track.bufferSizeInFrames} performanceMode=${track.performanceMode}")
        return track
    }

    @Volatile private var playbackLease: PlaybackAudioLease? = null

    private fun releaseTrack(track: AudioTrack) {
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    private fun makeRoomForRequestLocked() {
        if (requestStates.size < MAX_TRACKED_REQUESTS) return
        val evictable = requestStates.entries.firstOrNull {
            it.key != activeRequestId && it.value.pendingPhrases == 0 && it.value.disposition != RequestDisposition.Accepting
        }
        if (evictable != null) {
            requestStates.remove(evictable.key)
            mutableRequestStatuses.value = mutableRequestStatuses.value - evictable.key
        }
    }

    private fun setRequestStatusLocked(requestId: String, status: KokoroSpeechStatus, failureMessage: String? = null) {
        if (status != mutableRequestStatuses.value[requestId]?.state && status in setOf(KokoroSpeechStatus.Completed, KokoroSpeechStatus.Cancelled, KokoroSpeechStatus.Failed)) {
            com.kompi.app.diagnostics.SpeechAudit.record(requestId, 0, "request_terminal", status.name)
            Log.i(TAG, "Kokoro request terminal: request=$requestId state=$status phrases=${requestStates[requestId]?.nextSequence ?: 0}")
        }
        mutableRequestStatuses.value = mutableRequestStatuses.value +
            (requestId to KokoroSpeechRequestStatus(requestId, status, failureMessage))
    }

    private fun finishPhrase(speech: Speech) {
        // The consumer invokes this only after its actual track has been stopped/released, or
        // when this phrase was cancelled before hardware. Keep accepted work owned until then.
        speech.admission.releaseEngine()
        synchronized(stateLock) {
            val request = requestStates[speech.requestId] ?: return
            request.pendingPhrases = (request.pendingPhrases - 1).coerceAtLeast(0)
            when {
                request.disposition == RequestDisposition.Cancelled -> Unit
                request.disposition == RequestDisposition.Finished && request.pendingPhrases == 0 ->
                    setRequestStatusLocked(speech.requestId, KokoroSpeechStatus.Completed)
                request.pendingPhrases > 0 -> setRequestStatusLocked(speech.requestId, KokoroSpeechStatus.Queued)
            }
        }
    }

    /** Remove queued phrases for a failed request and account for them; caller holds stateLock. */
    private fun discardQueuedPhrasesLocked(requestId: String) {
        val keep = ArrayList<Speech>()
        var removed = 0
        while (true) {
            val next = queue.tryReceive().getOrNull() ?: break
            if (next.requestId == requestId) {
                removed++
                com.kompi.app.diagnostics.SpeechAudit.record(next.requestId, next.sequence, "phrase_discarded", "request_failed")
                next.admission.releaseEngine()
            } else keep += next
        }
        keep.forEach { queue.trySend(it) }
        requestStates[requestId]?.let { it.pendingPhrases = (it.pendingPhrases - removed).coerceAtLeast(0) }
    }

    private fun setStateUnlessClosed(value: KokoroTtsState) {
        synchronized(stateLock) {
            if (!closed.get()) mutableState.value = value
        }
    }

    private fun setClosedState() {
        synchronized(stateLock) { mutableState.value = KokoroTtsState.Closed }
    }

    private fun isSpeechCancelled(speech: Speech): Boolean = synchronized(stateLock) { isSpeechCancelledLocked(speech) }

    private fun isSpeechCancelledLocked(speech: Speech): Boolean =
        closed.get() || speech.generation != speechGeneration ||
            requestStates[speech.requestId]?.disposition == RequestDisposition.Cancelled

    private suspend fun verifyModel(file: File, expectedSha256: String) = withContext(Dispatchers.IO) {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(actual == expectedSha256) { "Installed Kokoro model checksum failed" }
    }

    private val closeCompletion = kotlinx.coroutines.CompletableDeferred<Unit>()

    suspend fun closeAndJoin() { close(); closeCompletion.await() }

    /** Initiates cancellation; native runtime release is deferred until synthesis/download exit. */
    override fun close() {
        val shouldClose = synchronized(stateLock) {
            if (!closed.compareAndSet(false, true)) false
            else {
                mutableState.value = KokoroTtsState.Closed
                true
            }
        }
        if (!shouldClose) return
        stop()
        activeDownload?.cancel(CancellationException("Kokoro engine closed"))
        queue.close()
        queueSpace.close()
        // Do not synchronously close LiteRT while collect()/AudioTrack writes may still be native.
        // The consumer drains its cancellation/finally path first; lifecycleMutex also waits out a
        // model transfer/initialization before the runtime is released.
        scope.launch(Dispatchers.IO) {
            try {
                consumerJob.join()
                lifecycleMutex.withLock {
                    runtimeMutex.withLock {
                        val closingRuntime = runtime
                        runtime = null
                        runCatching { withContext(inferenceDispatcher) { closingRuntime?.close() } }
                            .onFailure { Log.w(TAG, "Could not release Kokoro runtime during close", it) }
                    }
                }
            } finally {
                scope.cancel()
                inferenceDispatcher.close()
                playbackDispatcher.close()
                closeCompletion.complete(Unit)
            }
        }
    }

    companion object {
        const val DEFAULT_VOICE_ID = "af_heart"
        const val DEFAULT_LANGUAGE_LABEL = "English"
        const val MAX_QUEUE = 8
        const val MAX_TEXT_CHARS = 200
        const val MAX_TRACKED_REQUESTS = 256
        private const val INCOMPLETE_PLAYBACK_MESSAGE = "Speech playback stopped before completion."
        private const val MAX_REQUEST_ID_CHARS = 128
        private const val PLAYBACK_DRAIN_TIMEOUT_GRACE_MS = 5_000L
        private const val FIRST_PCM_PLAYBACK_TIMEOUT_MS = 2_000L
        private const val PCM_LOOKAHEAD_CHUNKS = 2
        private const val AUDIO_WRITE_MS = 20
        private const val AUDIO_METER_MS = 40
        private const val AUDIO_BUFFER_MS = 80
        // Graph tokens, not characters. A larger batch of 64 led to more producer work,
        // no startup gain. Keep one resident runtime, cached graphs and FIFO speech.
        private const val TARGET_BUCKET = 128
        private const val TAG = "KompiKokoro"
    }
}
