package com.kompi.app

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.os.PowerManager
import android.os.SystemClock
import com.kompi.app.assistant.AssistantConversationService
import com.kompi.app.assistant.AssistantCoordinator
import com.kompi.app.audio.AudioWaveform
import com.kompi.app.audio.LiveAudioStartIssue
import com.kompi.app.audio.LiveAudioSession
import com.kompi.app.audio.live.LocalLiveSpeechInput
import com.kompi.app.live.LiveConversationController
import com.kompi.app.live.LiveConversationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/** L90-only, read-only observations of the real assistant capture; never reads/steals its PCM.
 * Reflection follows the published input and its ACTIVE recorder, not the stopped resources
 * retained for cleanup. Only numeric summaries leave this test; no audio or transcript is saved.
 * Snapshot observations may be conflated, so meter counts are observed frames, not HAL counts. */
internal class LiveAssistantLockedCaptureProbe(private val context: Context) : AutoCloseable {
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private val serviceField = AssistantConversationService::class.java.getDeclaredField("current").apply { isAccessible = true }
    private val service = requireNotNull(serviceField.get(null)) { "L90 requires the actual running assistant service" } as AssistantConversationService
    private val controllerField = field(service, "controller")
    private val controller = requireNotNull(controllerField.get(service)) as LiveConversationController
    private val ownershipLock = requireNotNull(field(controller, "audioOwnershipLock").get(controller))
    private val inputField = field(controller, "speechInput")
    private val input = synchronized(ownershipLock) { inputField.get(controller) as LocalLiveSpeechInput }
    private val activeField = field(input, "active")
    private val active = requireNotNull(activeField.get(input))
    private val microphone = requireNotNull(field(active, "microphone").get(active))
    private val recorderField = field(microphone, "recorder")
    private val workerField = field(microphone, "worker")
    private val acquisition = AcquisitionObservation(microphone, recorderField.get(microphone) as? AudioRecord)
    private val sessionField = field(controller, "audioSession")
    private val session = synchronized(ownershipLock) { requireNotNull(sessionField.get(controller)) }
    private val sessionLock = requireNotNull(field(session, "lock").get(session))
    private val echoField = field(session, "echoProcessor")
    private val echo = synchronized(sessionLock) { echoField.get(session) }
    private val echoEnergy = echo?.let(::EchoEnergyObservation)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val manager = context.getSystemService(AudioManager::class.java)
    private val meterFrames = AtomicLong()
    private val nonzeroMeterFrames = AtomicLong()
    private val meterPeakMicros = AtomicLong()
    private val meterLastNanos = AtomicLong()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private val runtime = RuntimeObservation(service, controller, scope)

    init {
        var previous: AudioWaveform = AssistantCoordinator.snapshot.value.micWaveform
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            AssistantCoordinator.snapshot.collect { snapshot ->
                if (snapshot.micWaveform !== previous) {
                    previous = snapshot.micWaveform
                    val rmsMicros = (snapshot.micLevel.value * 1_000_000).toLong()
                    meterFrames.incrementAndGet()
                    if (rmsMicros > 0) nonzeroMeterFrames.incrementAndGet()
                    meterPeakMicros.updateAndGet { maxOf(it, rmsMicros) }
                    meterLastNanos.set(SystemClock.elapsedRealtimeNanos())
                }
            }
        }
        echoEnergy?.let { energy ->
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                while (isActive) {
                    energy.observe()
                    delay(10)
                }
            }
        }
    }

    data class Sample(
        val recorder: AudioRecord?,
        val currentOwner: Boolean,
        val recordingCount: Int,
        val source: Int,
        val audioSessionId: Int,
        val silenced: Int,
        val recordingState: Int,
        val timestampResult: Int,
        val framePosition: Long,
        val timestampNanos: Long,
        val deviceLocked: Boolean,
        val observedMeterFrames: Long,
    ) {
        val usableLockedCapture: Boolean get() = currentOwner && deviceLocked && recordingCount == 1 &&
            silenced == 0 && recordingState == AudioRecord.RECORDSTATE_RECORDING &&
            timestampResult == AudioRecord.SUCCESS && framePosition > 0 && timestampNanos > 0
    }

    fun sample(): Sample {
        val recorder = recorderField.get(microphone) as? AudioRecord
        val configurations = manager.activeRecordingConfigurations
        val sessionId = recorder?.let { runCatching { it.audioSessionId }.getOrNull() } ?: -1
        val configuration = configurations.singleOrNull { it.clientAudioSessionId == sessionId }
        val timestamp = AudioTimestamp()
        val timestampResult = recorder?.let {
            runCatching { it.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) }.getOrDefault(AudioRecord.ERROR_INVALID_OPERATION)
        } ?: AudioRecord.ERROR_INVALID_OPERATION
        val recordingState = recorder?.let { runCatching { it.recordingState }.getOrNull() } ?: -1
        val currentOwner = synchronized(ownershipLock) {
            serviceField.get(null) === service && controllerField.get(service) === controller &&
                inputField.get(controller) === input && activeField.get(input) === active &&
                recorder != null && recorderField.get(microphone) === recorder
        }
        return Sample(recorder, currentOwner, configurations.size, configuration?.clientAudioSource ?: -1,
            sessionId, configuration?.let { if (it.isClientSilenced) 1 else 0 } ?: -1, recordingState,
            timestampResult, timestamp.framePosition, timestamp.nanoTime, keyguard.isDeviceLocked, meterFrames.get())
    }

    fun checkpoint(stage: String, vararg metrics: Pair<String, Number>): Sample {
        val sample = sample()
        val worker = workerField.get(microphone) as? Thread
        // Never nest the session/publication locks with AEC's processLock: the real capture
        // consumer may check the session while processing audio under processLock.
        val currentSession = synchronized(ownershipLock) { sessionField.get(controller) === session }
        val currentEcho = synchronized(sessionLock) { echoField.get(session) === echo }
        val energyMetrics = echoEnergy?.metrics() ?: emptyArray()
        val runtimeMetrics = runtime.metrics()
        val acquisitionMetrics = acquisition.metrics(sample)
        val sessionState = synchronized(sessionLock) { (field(session, "state").get(session) as Enum<*>).ordinal }
        recordLiveNumericObservation("L90", stage,
            "elapsedNanos" to SystemClock.elapsedRealtimeNanos(),
            "deviceSecure" to if (keyguard.isDeviceSecure) 1 else 0,
            "deviceLocked" to if (sample.deviceLocked) 1 else 0,
            "keyguardLocked" to if (keyguard.isKeyguardLocked) 1 else 0,
            "interactive" to if (context.getSystemService(PowerManager::class.java).isInteractive) 1 else 0,
            "recordings" to sample.recordingCount,
            "currentOwner" to if (sample.currentOwner) 1 else 0,
            "audioSource" to sample.source,
            "audioSessionId" to sample.audioSessionId,
            "clientSilenced" to sample.silenced,
            "recordingState" to sample.recordingState,
            "timestampResult" to sample.timestampResult,
            "framePosition" to sample.framePosition,
            "timestampNanos" to sample.timestampNanos,
            "workerAlive" to if (worker?.isAlive == true) 1 else 0,
            "audioMode" to manager.mode,
            "microphoneMuted" to if (manager.isMicrophoneMute) 1 else 0,
            "observedMeterFrames" to sample.observedMeterFrames,
            "observedNonzeroMeterFrames" to nonzeroMeterFrames.get(),
            "meterPeakMicros" to meterPeakMicros.get(),
            "meterLastElapsedNanos" to meterLastNanos.get(),
            "meterNowMicros" to (AssistantCoordinator.snapshot.value.micLevel.value * 1_000_000).toLong(),
            "aecPresent" to if (echo != null) 1 else 0,
            "aecCurrentOwner" to if (echo != null && currentSession && currentEcho && sample.currentOwner) 1 else 0,
            "serviceInstanceCurrent" to if (serviceField.get(null) === service) 1 else 0,
            "audioSessionState" to sessionState,
            *energyMetrics, *runtimeMetrics, *acquisitionMetrics, *metrics)
        return sample
    }

    /** Keep evidence even when the synchronous SDK injection or the ASR assertion times out. */
    fun startPeriodicCheckpoints() {
        scope.launch {
            while (isActive) {
                delay(1_000)
                checkpoint("locked_capture_progress")
            }
        }
    }

    override fun close() { runtime.close(); runBlocking { job.cancelAndJoin() } }

    /** Observe one volatile immutable acquisition snapshot on the EXACT retained capture, even
     * after its resources/LocalLiveSpeechInput.active are cleared. Never refetch a newer capture
     * or read PCM. Counts/energy precede conversion, FIFO, clock checks and AEC. Read times are
     * read-return times, not hardware timestamps; phase 3 is history, never readiness. */
    private class AcquisitionObservation(private val microphone: Any, recorder: AudioRecord?) {
        private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
        private val diagnosticsField = runCatching { field(microphone, "acquisitionDiagnostics") }.getOrNull()
        private data class Key(val epoch: Long, val session: Int, val identity: Int)
        private data class Reading(val key: Key, val source: Int, val phase: Int, val started: Long,
            val firstRead: Long, val lastRead: Long, val reads: Long, val samples: Long,
            val squares: Double, val peak: Int, val nonzero: Long)
        private val expectedKey = recorder?.let { record -> runCatching {
            Key(field(microphone, "requestEpoch").getLong(microphone), record.audioSessionId, System.identityHashCode(record))
        }.getOrNull() }
        private var previous: Reading? = null
        private var readErrors = 0L
        private var discontinuities = 0L

        private fun read(snapshot: Any): Reading {
            fun number(name: String) = field(snapshot, name).get(snapshot) as Number
            return Reading(Key(number("requestEpoch").toLong(), number("audioSessionId").toInt(), number("recordIdentity").toInt()),
                number("audioSource").toInt(), number("phase").toInt(), number("recordingStartedNanos").toLong(),
                number("firstReadNanos").toLong(), number("lastReadNanos").toLong(), number("successfulReads").toLong(),
                number("successfulReadSamples").toLong(), number("inputSumSquares").toDouble(),
                number("inputPeak").toInt(), number("inputNonzeroSamples").toLong())
        }

        private fun rmsMicros(squares: Double, samples: Long): Long =
            if (samples > 0) (sqrt(squares / samples) / 32768.0 * 1_000_000).toLong() else -1L

        @Synchronized fun metrics(active: Sample): Array<Pair<String, Number>> {
            // ONE volatile get, then only immutable fields of that same snapshot.
            val snapshot = diagnosticsField?.get(microphone)
            val current = snapshot?.let { runCatching { read(it) }.getOrNull().also { value -> if (value == null) readErrors++ } }
            val last = previous
            val retainedMatch = if (current == null || expectedKey == null) -1 else if (current.key == expectedKey) 1 else 0
            val activeMatch = if (current == null || active.recorder == null) -1 else
                if (active.currentOwner && current.key.session == active.audioSessionId &&
                    current.key.identity == System.identityHashCode(active.recorder)) 1 else 0
            val previousMatch = if (current == null || last == null) -1 else if (current.key == last.key) 1 else 0
            val monotonic = current != null && last != null && current.reads >= last.reads &&
                current.samples >= last.samples && current.squares >= last.squares && current.nonzero >= last.nonzero
            if (current != null && last != null && (previousMatch == 0 || !monotonic)) discontinuities++
            val validDelta = retainedMatch == 1 && previousMatch == 1 && monotonic
            val deltaSamples = if (validDelta) requireNotNull(current).samples - requireNotNull(last).samples else -1L
            val deltaSquares = if (validDelta) requireNotNull(current).squares - requireNotNull(last).squares else -1.0
            // Update only this capture's numeric baseline. Identity mismatch invalidates deltas,
            // never becomes a replacement-owner baseline or an invented zero-energy success.
            if (current != null) previous = current
            return arrayOf<Pair<String, Number>>(
                "adcDiagnosticsPresent" to if (diagnosticsField != null) 1 else 0,
                "adcSnapshotReadable" to if (current != null) 1 else 0,
                "adcReadErrors" to readErrors,
                "adcExpectedEpoch" to (expectedKey?.epoch ?: -1L),
                "adcExpectedSessionId" to (expectedKey?.session ?: -1),
                "adcExpectedRecordIdentity" to (expectedKey?.identity ?: -1),
                "adcRequestEpoch" to (current?.key?.epoch ?: -1L),
                "adcAudioSessionId" to (current?.key?.session ?: -1),
                "adcRecordIdentity" to (current?.key?.identity ?: -1),
                "adcAudioSource" to (current?.source ?: -1),
                "adcPhase" to (current?.phase ?: -1),
                "adcRecordingStartedNanos" to (current?.started ?: -1L),
                "adcFirstReadReturnNanos" to (current?.firstRead ?: -1L),
                "adcLastReadReturnNanos" to (current?.lastRead ?: -1L),
                "adcSuccessfulReads" to (current?.reads ?: -1L),
                "adcSuccessfulReadSamples" to (current?.samples ?: -1L),
                "adcTotalRawSquares" to (current?.squares ?: -1.0),
                "adcLifetimeInputPeak" to (current?.peak ?: -1),
                "adcTotalNonzeroSamples" to (current?.nonzero ?: -1L),
                "adcTotalRmsMicros" to (current?.let { rmsMicros(it.squares, it.samples) } ?: -1L),
                "adcRetainedOwnerMatch" to retainedMatch,
                "adcActiveRecordMatch" to activeMatch,
                "adcPreviousOwnerMatch" to previousMatch,
                "adcCounterDiscontinuities" to discontinuities,
                "adcDeltaValid" to if (validDelta) 1 else 0,
                "adcDeltaNoProgress" to if (!validDelta) -1 else if (deltaSamples == 0L) 1 else 0,
                "adcDeltaSamples" to deltaSamples,
                "adcDeltaReadCalls" to if (validDelta) requireNotNull(current).reads - requireNotNull(last).reads else -1L,
                "adcDeltaFromReadReturnNanos" to if (validDelta) requireNotNull(last).lastRead else -1L,
                "adcDeltaRawSquares" to deltaSquares,
                "adcDeltaNonzeroSamples" to if (validDelta) requireNotNull(current).nonzero - requireNotNull(last).nonzero else -1L,
                "adcDeltaRmsMicros" to if (validDelta && deltaSamples > 0) rmsMicros(deltaSquares, deltaSamples) else -1L,
            )
        }
    }

    /** Observe the retained controller directly, before service teardown can replace its public
     * snapshot with Idle. Unconfined collectors do ONLY short numeric/atomic updates: never
     * logging, reflection, blocking, native audio calls, or mutation of the observed runtime. */
    internal class RuntimeObservation(
        private val service: AssistantConversationService,
        private val controller: LiveConversationController,
        scope: CoroutineScope,
    ) : AutoCloseable {
        private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
        private val stoppingField = field(service, "stopping")
        private val runtimeJob = field(service, "runtimeJob").get(service) as? Job
        private val closedField = field(controller, "closed")
        private val requestedField = field(controller, "liveRequested")
        private val generationField = field(controller, "generation")
        private val sessionGenerationField = field(controller, "sessionGeneration")
        private val startingGeneration = AssistantCoordinator.mode.value.generation
        private val firstStop = AtomicReference(Stop.None)
        private val lastStop = AtomicReference(Stop.None)
        private val stopMask = AtomicInteger()
        private val firstStopElapsedNanos = AtomicLong()
        private val releasedElapsedNanos = AtomicLong()
        private val completionCode = AtomicInteger()
        private val completionElapsedNanos = AtomicLong()
        private val completionHandle = runtimeJob?.invokeOnCompletion { cause ->
            // 0 pending; 1 normal completion; 2 cancellation; 3 timeout; 4 other throwable.
            completionCode.set(when (cause) {
                null -> 1
                is TimeoutCancellationException -> 3
                is CancellationException -> 2
                else -> 4
            })
            completionElapsedNanos.set(SystemClock.elapsedRealtimeNanos())
        }

        /** These are observation categories, not invented callbacks. In particular IdleOrEnded
         * cannot distinguish an explicit End from a task/lifecycle/disposal stop by itself. */
        private enum class Stop(val code: Int) {
            None(0), FocusLost(1), RouteLost(2), ConnectionLost(3), EchoUnavailable(4),
            PermissionRequired(5), ForegroundRequired(6), ModelUnavailable(7), CaptureUnavailable(8),
            OtherFailure(9), IdleOrEnded(10), TeardownFailure(11), ReplyEngineFailure(12), StartupCancelled(13),
            PreprocessingUnavailable(14),
        }

        private fun failureCode(message: String): Stop = when (message) {
            "Audio focus was lost. Start Live again when other audio has finished." -> Stop.FocusLost
            "The audio route changed. Check your headphones or speaker route, then start Live again.",
            LiveAudioStartIssue.RouteUnavailable.guidance -> Stop.RouteLost
            LiveAudioStartIssue.EchoUnavailable.guidance -> Stop.EchoUnavailable
            LiveAudioStartIssue.PermissionRequired.guidance -> Stop.PermissionRequired
            LiveAudioStartIssue.ForegroundRequired.guidance,
            "Live voice requires an active screen or assistant service." -> Stop.ForegroundRequired
            LiveAudioStartIssue.ModelUnavailable.guidance -> Stop.ModelUnavailable
            LiveAudioStartIssue.CaptureUnavailable.guidance -> Stop.CaptureUnavailable
            LiveAudioStartIssue.PreprocessingUnavailable.guidance -> Stop.PreprocessingUnavailable
            "Voice session paused. Invoke the assistant again to resume.",
            "The selected computer did not become ready. Open Kompi and check its connection." -> Stop.ConnectionLost
            "Kokoro playback failed. Open Voice settings before trying again." -> Stop.ReplyEngineFailure
            "Assistant startup was cancelled. Invoke it again to retry." -> Stop.StartupCancelled
            else -> if (message.startsWith("Microphone teardown failed.")) Stop.TeardownFailure else Stop.OtherFailure
        }

        private fun observe(stop: Stop) {
            if (stop == Stop.None) return
            lastStop.set(stop)
            stopMask.updateAndGet { it or (1 shl stop.code) }
            if (firstStop.compareAndSet(Stop.None, stop)) firstStopElapsedNanos.set(SystemClock.elapsedRealtimeNanos())
        }

        init {
            scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                controller.snapshot.collect { snapshot ->
                    observe(when (val state = snapshot.state) {
                        LiveConversationState.Idle -> Stop.IdleOrEnded
                        LiveConversationState.Paused -> Stop.ConnectionLost
                        is LiveConversationState.Failure -> failureCode(state.message)
                        else -> Stop.None
                    })
                }
            }
            scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                AssistantCoordinator.failure.collect { it?.let { message -> observe(failureCode(message)) } }
            }
            scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                AssistantCoordinator.mode.collect { mode ->
                    if (!mode.assistant || mode.generation != startingGeneration)
                        releasedElapsedNanos.compareAndSet(0L, SystemClock.elapsedRealtimeNanos())
                }
            }
        }

        // Closed, bounded mapping; no captions, request IDs, error messages or class names.
        private fun stateCode(state: LiveConversationState): Int = when (state) {
            LiveConversationState.Idle -> 0
            LiveConversationState.Preparing -> 1
            LiveConversationState.Listening -> 2
            LiveConversationState.Recognizing -> 3
            is LiveConversationState.Waiting -> 4
            is LiveConversationState.Speaking -> 5
            LiveConversationState.Interrupted -> 6
            LiveConversationState.Paused -> 7
            is LiveConversationState.Failure -> 8
        }

        fun metrics(): Array<Pair<String, Number>> = arrayOf<Pair<String, Number>>(
            "assistantStartingGeneration" to startingGeneration,
            "assistantGeneration" to AssistantCoordinator.mode.value.generation,
            "assistantMode" to if (AssistantCoordinator.mode.value.assistant) 1 else 0,
            "assistantReleasedElapsedNanos" to releasedElapsedNanos.get(),
            "serviceStopping" to if (stoppingField.getBoolean(service)) 1 else 0,
            "serviceLifecycleState" to service.lifecycle.currentState.ordinal,
            "runtimeActive" to if (runtimeJob?.isActive == true) 1 else 0,
            "runtimeCancelled" to if (runtimeJob?.isCancelled == true) 1 else 0,
            "runtimeCompleted" to if (runtimeJob?.isCompleted == true) 1 else 0,
            "runtimeCompletionCode" to completionCode.get(),
            "runtimeCompletionElapsedNanos" to completionElapsedNanos.get(),
            "controllerClosed" to if (closedField.getBoolean(controller)) 1 else 0,
            "controllerLiveRequested" to if (requestedField.getBoolean(controller)) 1 else 0,
            "controllerGeneration" to generationField.getLong(controller),
            "controllerSessionGeneration" to sessionGenerationField.getLong(controller),
            "controllerStateCode" to stateCode(controller.snapshot.value.state),
            "coordinatorStateCode" to stateCode(AssistantCoordinator.snapshot.value.state),
            "firstObservedStopCode" to firstStop.get().code,
            "lastObservedStopCode" to lastStop.get().code,
            "observedStopMask" to stopMask.get(),
            "firstObservedStopElapsedNanos" to firstStopElapsedNanos.get(),
        )

        override fun close() { completionHandle?.dispose() }
    }

    /** Read only cumulative numeric energy counters, never PCM or reset-window sums. All
     * counters are sampled together under processLock, after releasing the ownership locks.
     * Deltas include EVERY intervening APM frame even if this test sampler is delayed. Frequent
     * observations retain interval-RMS peaks, not individual-frame peaks or a speech classifier.
     * An unexpected counter discontinuity is explicit rather than interpreted as zero energy. */
    private class EchoEnergyObservation(private val processor: Any) {
        private fun field(name: String) = processor.javaClass.getDeclaredField(name).apply { isAccessible = true }
        private val processLock = requireNotNull(field("processLock").get(processor))
        private val failed = field("failed").get(processor) as AtomicBoolean
        private val closed = field("closed").get(processor) as AtomicBoolean
        private val captureId = field("captureId").get(processor) as AtomicLong
        // Optional diagnostics are absent on older frozen binaries. Missing is -1, never a
        // fabricated success/zero cause. Read only the fixed numeric first-fault allowlist.
        private val failureCode = runCatching { field("failureCode").get(processor) as AtomicInteger }.getOrNull()
        private val nativeStatus = runCatching { field("nativeStatus") }.getOrNull()
        private val firstFault = runCatching { field("firstFault").get(processor) as AtomicReference<*> }.getOrNull()
        private val framesField = field("metricsFrames")
        private val inputField = field("diagnosticTotalInputEnergy")
        private val outputField = field("diagnosticTotalOutputEnergy")
        private val referenceField = field("diagnosticTotalReferenceEnergy")
        private val observationLock = Any()
        private data class Totals(val frames: Long, val inputEnergy: Double, val outputEnergy: Double, val referenceEnergy: Double)
        private var previous: Totals? = null
        private var reads = 0L
        private var observedFrames = 0L
        private var counterDiscontinuities = 0L
        private var rawEnergy = 0.0
        private var cleanEnergy = 0.0
        private var referenceEnergy = 0.0
        private var rawNonzeroSpans = 0L
        private var cleanNonzeroSpans = 0L
        private var referenceNonzeroSpans = 0L
        private var peakRawRmsMicros = 0L
        private var peakCleanRmsMicros = 0L
        private var peakReferenceRmsMicros = 0L
        private var failureObservedElapsedNanos = 0L

        private fun rmsMicros(energy: Double, frames: Long): Long =
            if (frames == 0L) 0L else (sqrt(energy.coerceAtLeast(0.0) / (frames * 160)) * 1_000_000).toLong()

        fun observe() { synchronized(observationLock) { observeLocked() } }

        private fun observeLocked(): Totals {
            val current = synchronized(processLock) {
                Totals(framesField.getLong(processor), inputField.getDouble(processor),
                    outputField.getDouble(processor), referenceField.getDouble(processor))
            }
            reads++
            if (failed.get() && failureObservedElapsedNanos == 0L) failureObservedElapsedNanos = SystemClock.elapsedRealtimeNanos()
            val last = previous
            if (last != null) {
                val discontinuity = current.frames < last.frames || current.inputEnergy < last.inputEnergy ||
                    current.outputEnergy < last.outputEnergy || current.referenceEnergy < last.referenceEnergy
                if (discontinuity) counterDiscontinuities++
                else if (current.frames > last.frames) {
                    val frames = current.frames - last.frames
                    val input = current.inputEnergy - last.inputEnergy
                    val output = current.outputEnergy - last.outputEnergy
                    val reference = current.referenceEnergy - last.referenceEnergy
                    observedFrames += frames
                    rawEnergy += input
                    cleanEnergy += output
                    referenceEnergy += reference
                    if (input > 0) rawNonzeroSpans++
                    if (output > 0) cleanNonzeroSpans++
                    if (reference > 0) referenceNonzeroSpans++
                    peakRawRmsMicros = maxOf(peakRawRmsMicros, rmsMicros(input, frames))
                    peakCleanRmsMicros = maxOf(peakCleanRmsMicros, rmsMicros(output, frames))
                    peakReferenceRmsMicros = maxOf(peakReferenceRmsMicros, rmsMicros(reference, frames))
                }
            }
            previous = current
            return current
        }

        private fun faultValue(snapshot: Any?, name: String): Number {
            if (snapshot == null) return -1
            return when (val value = runCatching { snapshot.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(snapshot) }.getOrNull()) {
                is Number -> value
                is Boolean -> if (value) 1 else 0
                else -> -1
            }
        }

        fun metrics(): Array<Pair<String, Number>> = synchronized(observationLock) {
            val current = observeLocked()
            val fault = firstFault?.get()
            val captureUncertainty = faultValue(fault, "captureUncertaintyNs").toLong()
            val referenceUncertainty = faultValue(fault, "referenceUncertaintyNs").toLong()
            val combinedUncertainty = if (captureUncertainty >= 0 && referenceUncertainty >= 0 &&
                captureUncertainty <= Long.MAX_VALUE - referenceUncertainty) captureUncertainty + referenceUncertainty else -1L
            arrayOf<Pair<String, Number>>(
                "aecFailureDiagnosticsPresent" to if (failureCode != null && nativeStatus != null && firstFault != null) 1 else 0,
                "aecFailureCode" to (failureCode?.get() ?: -1),
                "aecNativeStatus" to (nativeStatus?.getInt(processor) ?: -1),
                "aecFirstFaultPresent" to if (firstFault == null) -1 else if (fault == null) 0 else 1,
                "aecFaultCode" to faultValue(fault, "code"),
                "aecFaultNativeStatus" to faultValue(fault, "nativeStatus"),
                "aecFaultCaptureFrames" to faultValue(fault, "captureFrames"),
                "aecFaultCaptureUncertaintyNs" to captureUncertainty,
                "aecFaultReferenceUncertaintyNs" to referenceUncertainty,
                "aecFaultCombinedNs" to combinedUncertainty,
                "aecFaultHasReference" to faultValue(fault, "hasReference"),
                "aecFaultCaptureHardwareClock" to faultValue(fault, "captureHardware"),
                "aecFaultReferenceHardwareClock" to faultValue(fault, "renderHardware"),
                "aecFaultReferenceCount" to faultValue(fault, "referenceCount"),
                "aecFaultConsumerFilled" to faultValue(fault, "consumerFilled"),
                "aecFailed" to if (failed.get()) 1 else 0,
                "aecFailureObservedElapsedNanos" to failureObservedElapsedNanos,
                "aecClosed" to if (closed.get()) 1 else 0,
                "aecCaptureId" to captureId.get(),
                "aecFrames" to current.frames,
                "aecTotalRawEnergy" to current.inputEnergy,
                "aecTotalCleanEnergy" to current.outputEnergy,
                "aecTotalReferenceEnergy" to current.referenceEnergy,
                "aecTotalRawRmsMicros" to rmsMicros(current.inputEnergy, current.frames),
                "aecTotalCleanRmsMicros" to rmsMicros(current.outputEnergy, current.frames),
                "aecTotalReferenceRmsMicros" to rmsMicros(current.referenceEnergy, current.frames),
                "aecMetricReads" to reads,
                "aecObservedFrames" to observedFrames,
                "aecGapFree" to if (counterDiscontinuities == 0L) 1 else 0,
                "aecCounterDiscontinuities" to counterDiscontinuities,
                "aecObservedRawEnergy" to rawEnergy,
                "aecObservedCleanEnergy" to cleanEnergy,
                "aecObservedReferenceEnergy" to referenceEnergy,
                "aecRawNonzeroSpans" to rawNonzeroSpans,
                "aecCleanNonzeroSpans" to cleanNonzeroSpans,
                "aecReferenceNonzeroSpans" to referenceNonzeroSpans,
                "aecPeakRawRmsMicros" to peakRawRmsMicros,
                "aecPeakCleanRmsMicros" to peakCleanRmsMicros,
                "aecPeakReferenceRmsMicros" to peakReferenceRmsMicros,
            )
        }
    }
}

/** Retains the actual assistant owner AFTER listening, before injection. Unlike the L90 probe,
 * this class never constructs a hardware sample or AEC energy sampler. Only existing immutable
 * producer publications, local fields/atomics and bounded runtime event categories are observed.
 * There are two endpoint dumps, no polling, new thread, model/APM lock, hardware getter or JNI.
 * Close before fixture-forced service teardown so that cleanup cannot invent the stop cause. */
internal class LiveAssistantMetadataOnlyProbe(@Suppress("UNUSED_PARAMETER") context: Context) : java.io.Closeable {
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun value(owner: Any, name: String): Any? = field(owner, name).get(owner)
    private fun flag(owner: Any, name: String): Int = if ((value(owner, name) as AtomicBoolean).get()) 1 else 0
    private val serviceField = AssistantConversationService::class.java.getDeclaredField("current").apply { isAccessible = true }
    private val service = checkNotNull(serviceField.get(null) as? AssistantConversationService)
    private val controller = checkNotNull(value(service, "controller") as? LiveConversationController)
    private val ownershipLock = checkNotNull(value(controller, "audioOwnershipLock"))
    private val ownership = synchronized(ownershipLock) {
        checkNotNull(value(controller, "speechInput") as? LocalLiveSpeechInput) to
            checkNotNull(value(controller, "audioSession") as? LiveAudioSession)
    }
    private val input = ownership.first
    private val session = ownership.second
    private val active = synchronized(input) { checkNotNull(value(input, "active")) }
    private val microphone = checkNotNull(value(active, "microphone"))
    private val captureOwner = (value(active, "audioSession") as AtomicReference<*>).get().also { check(it === session) }
    private val recognizer = (value(active, "recognizer") as AtomicReference<*>).get()
    private val sessionLock = checkNotNull(value(session, "lock"))
    private val processor = synchronized(sessionLock) { value(session, "echoProcessor") }
    private val acquisition = NativeAcquisitionDiagnostics(input, session, 1)
    private val observerJob = SupervisorJob()
    // RuntimeObservation launches only short Unconfined flow collectors; no Default worker,
    // periodic sampler, reflective work or emission runs inside those runtime callbacks.
    private val runtime = LiveAssistantLockedCaptureProbe.RuntimeObservation(service, controller,
        CoroutineScope(observerJob + Dispatchers.Unconfined))
    private val closed = AtomicBoolean()

    init {
        acquisition.observeStart()
        snapshot(0)
    }

    private fun emit(stage: Int, category: String, metrics: () -> List<Pair<String, Number>>) {
        runCatching {
            val began = System.nanoTime()
            val values = metrics()
            emitNativeAudioDiagnostic("$category stage=$stage ownerEpoch=${session.epoch} beganNanos=$began endedNanos=${System.nanoTime()} " +
                values.joinToString(" ") { (name, number) -> "$name=$number" })
        }.onFailure {
            runCatching { emitNativeAudioDiagnostic("$category stage=$stage ownerEpoch=${session.epoch} diagnosticCode=1 snapshotAvailable=0") }
        }
    }

    private fun snapshot(stage: Int) {
        emit(stage, "REAL_AUDIO_ASSISTANT_RUNTIME") {
            val currentOwnership = synchronized(ownershipLock) {
                (value(service, "controller") === controller && value(controller, "speechInput") === input) to
                    (value(controller, "audioSession") === session)
            }
            val currentInput = synchronized(input) { value(input, "active") === active }
            buildList {
                add("elapsedNanos" to SystemClock.elapsedRealtimeNanos())
                add("serviceInstanceCurrent" to if (serviceField.get(null) === service) 1 else 0)
                add("controllerInputCurrent" to if (currentOwnership.first) 1 else 0)
                add("controllerSessionCurrent" to if (currentOwnership.second) 1 else 0)
                add("inputCaptureCurrent" to if (currentInput) 1 else 0)
                add("retainedCaptureOwnerMatch" to if (captureOwner === session) 1 else 0)
                add("hardwareStopStarted" to flag(active, "hardwareStopStarted"))
                add("hardwareJoinSucceeded" to flag(active, "hardwareJoinSucceeded"))
                add("hardwareStoppedCompleted" to if ((value(active, "hardwareStopped") as Job).isCompleted) 1 else 0)
                add("decoderStoppedCompleted" to if ((value(active, "decoderStopped") as Job).isCompleted) 1 else 0)
                add("cleanupQueued" to flag(active, "cleanupQueued"))
                add("acceptsFailures" to flag(active, "acceptsFailures"))
                add("microphoneClosed" to flag(microphone, "closed"))
                add("microphoneRevoked" to flag(microphone, "revoked"))
                add("recognizerPresent" to if (recognizer != null) 1 else 0)
                recognizer?.let { retained ->
                    listOf("accepting", "cancelled", "failed", "terminalFinished").forEach { name ->
                        add("recognizer_$name" to flag(retained, name))
                    }
                }
                addAll(runtime.metrics())
            }
        }
        emit(stage, "REAL_AUDIO_ASSISTANT_LEASE") {
            // Copy only local state under ONE metadata lock. No emission, Binder, focus/device
            // getter, callbacks or other lock is entered here. Requested != actual ownership.
            synchronized(sessionLock) {
                fun requested(name: String) = if (value(session, name) as Boolean) 1 else 0
                fun demands(name: String): List<Any> = (value(session, name) as Map<*, *>).values.filterNotNull()
                val capture = demands("captureDemands")
                val output = demands("outputDemands")
                fun count(values: List<Any>, name: String) = values.count { value(it, name) as Boolean }
                val permission = value(session, "micPermission") as LiveAudioSession.MicPermission
                listOf<Pair<String, Number>>(
                    "stateCode" to (value(session, "state") as Enum<*>).ordinal,
                    "communicationPhaseCode" to (value(session, "communicationPhase") as Enum<*>).ordinal,
                    "communicationEpoch" to (value(session, "communicationEpoch") as Number),
                    "micPermissionCode" to when (permission) {
                        is LiveAudioSession.MicPermission.Allowed -> 1
                        is LiveAudioSession.MicPermission.UserMuted -> 2
                    },
                    "micCaptureGeneration" to permission.captureGeneration,
                    "modeRequested" to requested("modeOwned"), "deviceRequested" to requested("deviceOwned"),
                    "focusHandleRetained" to if (value(session, "focus") != null) 1 else 0,
                    "osReleased" to requested("osReleased"),
                    "lastPhysicalModeCallback" to (value(session, "lastPhysicalMode") as Number),
                    "expectedCommunicationId" to (value(session, "expectedDeviceId") as? Number ?: -1),
                    "captureDemands" to capture.size, "captureGranted" to count(capture, "granted"),
                    "captureFenced" to count(capture, "fenced"), "outputDemands" to output.size,
                    "outputGranted" to count(output, "granted"), "outputFenced" to count(output, "fenced"),
                )
            }
        }
        emit(stage, "REAL_AUDIO_ASSISTANT_AEC_METADATA") {
            val retained = processor
            if (retained == null) listOf("processorPresent" to 0, "snapshotAvailable" to 0)
            else {
                // One immutable native-history publication plus a separate immutable first
                // fault. Never acquire processLock, inspect PCM, request stats or invoke JNI.
                val history = value(retained, "nativeHistoryDiagnostics")
                val fault = (value(retained, "firstFault") as AtomicReference<*>).get()
                buildList {
                    add("processorPresent" to 1)
                    add("processorIdentity" to System.identityHashCode(retained))
                    add("snapshotAvailable" to if (history != null) 1 else 0)
                    add("currentFailureCode" to (value(retained, "failureCode") as AtomicInteger).get())
                    add("firstFaultAvailable" to if (fault != null) 1 else 0)
                    history?.let { publication ->
                        listOf("observedNanos", "captureFrames", "reverseFrames", "suspendedReverseFrames",
                            "suspendedHistoryFaults", "statsPresentMask", "suspendedHistoryEventMask",
                            "suspendedTransitionLatencyBlocks", "suspendedAdvancedBlocks", "suspendedRecoveryLatencyBlocks",
                            "nativeStatus", "failureCode", "normalInsertOverruns", "normalPrepareExcessResets",
                            "normalPrepareUnderruns", "currentRenderLatencyBlocks", "subtractorFullResets").forEach { name ->
                            add(name to (value(publication, name) as Number))
                        }
                    }
                    fault?.let { publication ->
                        listOf("code", "nativeStatus", "captureFrames", "captureUncertaintyNs", "referenceUncertaintyNs",
                            "hasReference", "captureHardware", "renderHardware", "referenceCount", "consumerFilled").forEach { name ->
                            add("fault_$name" to (value(publication, name) as Number))
                        }
                    }
                }
            }
        }
        printNativeAcquisitionDiagnostics(listOf(acquisition))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            // Detach before emission; no joins/waits and no observation of the fixture's later
            // forced Stop. All retained capture publications remain readable after retirement.
            runtime.close()
            observerJob.cancel()
            snapshot(1)
            printSessionRouteFailure(session)
        } finally {
            runtime.close()
            observerJob.cancel()
        }
    }
}
