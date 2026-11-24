package com.kompi.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** Explicit audio-frame boundary for a local speech engine. Frames are mono Float32 at 16 kHz. */
interface Pcm16kConsumer {
    val echoCancellation: EchoCancellationPolicy get() = EchoCancellationPolicy.Disabled
    val preferredInputDevice: AudioDeviceInfo? get() = null
    fun onAudio(samples: FloatArray)
    fun onAudio(samples: FloatArray, timing: CaptureFrameTiming) = onAudio(samples)
    fun onCaptureError(message: String)
}

/** Process-wide fair ownership spans startup, reader exit AND native decoder disposal. */
internal object MicCaptureCoordinator {
    private val semaphore = Semaphore(1, true)
    private val generation = java.util.concurrent.atomic.AtomicLong(0L)
    private val generationLock = Any()
    fun requestGeneration(): Long = synchronized(generationLock) { generation.incrementAndGet() }
    fun isCurrent(expectedGeneration: Long): Boolean = generation.get() == expectedGeneration
    fun cancelRequest(expectedGeneration: Long): Boolean = synchronized(generationLock) {
        generation.compareAndSet(expectedGeneration, expectedGeneration + 1L)
    }
    fun performIfCurrent(expectedGeneration: Long, action: () -> Unit): Boolean = synchronized(generationLock) {
        if (generation.get() != expectedGeneration) false else { action(); true }
    }
    fun awaitIdle(timeoutMillis: Long): Boolean {
        val acquired = try { semaphore.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        if (acquired) semaphore.release()
        return acquired
    }
    fun acquire(expectedGeneration: Long, timeoutMillis: Long = 2_000): Lease? {
        if (!isCurrent(expectedGeneration)) return null
        val acquired = try { semaphore.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        if (!acquired) return null
        if (!isCurrent(expectedGeneration)) { semaphore.release(); return null }
        return Lease(semaphore)
    }
    class Lease internal constructor(private val semaphore: Semaphore) : Closeable {
        private val released = AtomicBoolean(false)
        override fun close() { if (released.compareAndSet(false, true)) semaphore.release() }
    }
}

/** Hardware privacy-stop is independent of slow model startup/cleanup. A stopped record and its
 * effects remain explicitly owned until its reader really exits. A hung owner is quarantined, not
 * released to the next capture. start() is single-use; unmute creates a fresh capture/session ID.
 */
class ForegroundMicCapture(
    context: Context,
    private val owner: LifecycleOwner,
    private val consumer: Pcm16kConsumer? = null,
    private val audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
    private val preferredInputDevice: AudioDeviceInfo? = null,
    private val consumerFactory: (() -> Pcm16kConsumer)? = null,
    /** Local capability checks only. OS acquisition happens after model preparation, outside locks. */
    private val requireHardwarePermit: () -> Unit = {},
) : Closeable, LifecycleEventObserver {
    private class Resources(val record: AudioRecord, val effects: MicrophoneEffects,
        val configuration: CaptureConfigurationMonitor) {
        val stopRequested = AtomicBoolean(false)
        @Volatile var stopFinished = false
        val started = CountDownLatch(1)
        @Volatile var startFailure: Exception? = null
    }
    private val appContext = context.applicationContext
    private val lock = Any()
    private val requestEpoch = MicCaptureCoordinator.requestGeneration()
    private val closed = AtomicBoolean(false)
    private val revoked = AtomicBoolean(false)
    private val startEntered = AtomicBoolean(false)
    private var opening = false
    private var disposing = false
    private var microphoneLease: MicCaptureCoordinator.Lease? = null
    @Volatile private var activeConsumer: Pcm16kConsumer? = consumer
    @Volatile private var recorder: AudioRecord? = null // Active-only observation used by timestamp acceptance.
    private var resources: Resources? = null // Includes stopped-but-not-yet-disposable hardware.
    @Volatile private var requiredInputDevice: AudioDeviceInfo? = null
    @Volatile private var worker: Thread? = null
    private var readerJoined = true
    private var ownershipReleaseRequested = false
    private var hardwareStopFailed = false
    private val reaperStarted = AtomicBoolean(false)
    private enum class CaptureFailureCode { ProcessorBacklog, ReaderIntegrity, ProcessorFailure, PreprocessingFailure }
    private class ProcessorBacklog : IllegalStateException()
    @Volatile private var captureFailureCode: CaptureFailureCode? = null
    @Volatile internal var captureFailureIssue: LiveAudioStartIssue? = null
        private set
    @Volatile private var captureClockFailure: CaptureClockFailureSnapshot? = null
    @Volatile private var acquisitionDiagnostics: AcquisitionDiagnostics? = null
    @Volatile private var captureConfigurationDiagnostics: CaptureConfigurationDiagnostics? = null
    @Volatile private var captureEffectsDiagnostics: CaptureEffectsDiagnostics? = null
    private data class CaptureFailureDiagnostics(val requestEpoch: Long, val audioSessionId: Int,
        val recordIdentity: Int, val observedNanos: Long, val code: Int)
    private data class CaptureInputRouteFailureDiagnostics(val requestEpoch: Long, val audioSessionId: Int,
        val recordIdentity: Int, val observedNanos: Long, val audioSource: Int,
        val requiredInputDeviceId: Int, val requiredInputDeviceType: Int,
        val actualInputDeviceId: Int, val actualInputDeviceType: Int)
    @Volatile private var captureFailureDiagnostics: CaptureFailureDiagnostics? = null
    @Volatile private var captureInputRouteFailureDiagnostics: CaptureInputRouteFailureDiagnostics? = null

    /** Numeric observations of THIS owner's actual AudioRecord.read, before conversion/FIFO.
     * Never logged or persisted. Phase: 0 record adopted, 1 recording started, 2 successful read,
     * 3 acquisition exited (possibly before its first read). Peaks/squares use raw PCM16 units.
     * One acquisition-thread writer publishes immutable snapshots; stop never takes a metric lock.
     */
    private data class AcquisitionDiagnostics(
        val requestEpoch: Long,
        val audioSessionId: Int,
        val recordIdentity: Int,
        val audioSource: Int,
        val phase: Int = 0,
        val recordingStartedNanos: Long = 0,
        val firstReadNanos: Long = 0,
        val lastReadNanos: Long = 0,
        val successfulReads: Long = 0,
        val successfulReadSamples: Long = 0,
        val inputSumSquares: Double = 0.0,
        val inputPeak: Int = 0,
        val inputNonzeroSamples: Long = 0,
    )

    private enum class ClockRule(val code: Int) {
        BeforeReadDeadline(1), ReadTooLong(2), TimestampPosition(3), TimestampFuture(4),
        TimestampStale(5), TimestampReset(6), TimestampWindowRate(8),
        TimestampUnavailable(9), BufferBefore(10), BufferDuring(11), BufferAfter(12),
        ReadBounds(13), ReadPosition(14),
    }
    /** First failed rule only. All values are content-free capture-clock observations; -1/0
     * denote unavailable positions/times or bounds not yet computed, never substituted PCM.
     */
    private data class CaptureClockFailureSnapshot(
        val stage: Int,
        val capacityFrames: Int,
        val readFrames: Long,
        val readCount: Int,
        val timestampFrame: Long,
        val timestampNanos: Long,
        val previousTimestampFrame: Long,
        val previousTimestampNanos: Long,
        val rateAnchorFrame: Long,
        val rateAnchorNanos: Long,
        val beganNanos: Long,
        val endedNanos: Long,
        val observedNanos: Long,
        val gapNanos: Long,
        val runwayNanos: Long,
        val producerUpperBefore: Long,
        val producerUpperDuring: Long,
        val producerUpperAfter: Long,
        val earliestNanos: Long,
        val latestNanos: Long,
        val clientEmptyBeganNanos: Long,
        val clientEmptyEndedNanos: Long,
        val estimatedFirstNanos: Long,
        val nanosPerSample: Double,
    )
    private class CaptureClockDiscontinuity(val snapshot: CaptureClockFailureSnapshot) : IllegalStateException()

    init { owner.lifecycle.addObserver(this) }

    fun start(): Result<Unit> {
        if (!startEntered.compareAndSet(false, true)) return Result.failure(IllegalStateException("Capture request was already used"))
        synchronized(lock) { opening = true }
        try {
            ensureEligible()
            if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) throw LiveAudioStartException(LiveAudioStartIssue.ForegroundRequired)
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw LiveAudioStartException(LiveAudioStartIssue.PermissionRequired)
            check(activeConsumer != null || consumerFactory != null) { "Microphone capture has no PCM consumer" }
            val acquired = MicCaptureCoordinator.acquire(requestEpoch, START_WAIT_MS)
                ?: error("Another microphone capture is still active or stopping")
            synchronized(lock) { microphoneLease = acquired }
            ensureEligible()
            // NEVER hold the stop/ownership lock while opening or prewarming native models.
            if (activeConsumer == null) activeConsumer = checkNotNull(consumerFactory).invoke()
            ensureEligible()
            requireHardwarePermit()
            val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            check(minBytes > 0) { "This device cannot capture 16 kHz mono audio" }
            val sourceSelection = CaptureSourceSelection.forConsumer(checkNotNull(activeConsumer), audioSource)
            val builder = AudioRecord.Builder().setAudioSource(sourceSelection.requestedSource)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(CHANNEL).setEncoding(ENCODING).build())
                .setBufferSizeInBytes(max(minBytes * 2, FRAME_SAMPLES * 2 * 4))
            // Keep communication capture explicitly privacy-sensitive.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && sourceSelection.requestedSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                builder.setPrivacySensitive(true)
            requireHardwarePermit()
            val record = builder.build()
            var adopted = false
            var unadoptedEffects: MicrophoneEffects? = null
            var configuration: CaptureConfigurationMonitor? = null
            try {
                ensureEligible()
                requireHardwarePermit()
                check(record.state == AudioRecord.STATE_INITIALIZED) { "Could not initialize microphone capture" }
                val preferred = activeConsumer?.preferredInputDevice ?: preferredInputDevice
                if (preferred != null && !record.setPreferredDevice(preferred)) throw LiveAudioStartException(LiveAudioStartIssue.RouteUnavailable)
                val effects = MicrophoneEffects.attach(record.audioSessionId, checkNotNull(activeConsumer).echoCancellation,
                    publish = { captureEffectsDiagnostics = it.copy(requestEpoch = requestEpoch, recordIdentity = System.identityHashCode(record)) },
                    onUnsafe = { captureFailed(record, CaptureFailureCode.PreprocessingFailure) })
                unadoptedEffects = effects
                val monitor = CaptureConfigurationMonitor(appContext, record, requestEpoch, sourceSelection, preferred) {
                    captureConfigurationDiagnostics = it
                }
                configuration = monitor
                synchronized(lock) {
                    resources = Resources(record, effects, monitor)
                    acquisitionDiagnostics = AcquisitionDiagnostics(requestEpoch, record.audioSessionId,
                        System.identityHashCode(record), record.audioSource)
                    adopted = true
                    requiredInputDevice = preferred
                }
                val installed = MicCaptureCoordinator.performIfCurrent(requestEpoch) {
                    synchronized(lock) {
                        ensureEligible()
                        readerJoined = false
                        worker = Thread({ readLoop(record) }, "kompi-microphone").apply { start() }
                    }
                }
                check(installed) { "Microphone capture request was superseded" }
                // The acquisition thread starts recording immediately before reading. No route
                // polling, model work, or DSP runs between startRecording and the first read.
                val owned = synchronized(lock) { checkNotNull(resources) }
                owned.started.await()
                owned.startFailure?.let { throw it }
                ensureEligible()
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone capture did not start" }
                return Result.success(Unit)
            } finally {
                if (!adopted) {
                    configuration?.close()
                    unadoptedEffects?.close()
                    record.release() // No reader or recording has ever owned this handle.
                }
            }
        } catch (error: Exception) {
            requestStop()
            return Result.failure(if (error is SecurityException) LiveAudioStartException(LiveAudioStartIssue.PermissionRequired) else error)
        } finally {
            synchronized(lock) { opening = false }
            disposeIfReady()
        }
    }

    private fun ensureEligible() {
        check(!closed.get() && !revoked.get() && MicCaptureCoordinator.isCurrent(requestEpoch)) { "Microphone capture request was stopped or superseded" }
    }

    /** Fences first and stops actual recording now. Does NOT release underneath a blocking read. */
    fun requestStop() {
        revoked.set(true)
        MicCaptureCoordinator.cancelRequest(requestEpoch)
        val owned = synchronized(lock) { recorder = null; resources }
        if (owned != null && owned.stopRequested.compareAndSet(false, true)) {
            owned.started.countDown() // A stopped startup must not wait for a scheduled reader.
            try {
                if (owned.record.recordingState == AudioRecord.RECORDSTATE_RECORDING) owned.record.stop()
            } catch (_: IllegalStateException) {
                synchronized(lock) { hardwareStopFailed = true }
            } finally { owned.stopFinished = true }
        }
        worker?.let { reader ->
            reader.interrupt()
            if (reader.isAlive) reapAfterReaderExit(reader)
        }
        disposeIfReady()
    }

    fun stop() { stopAndJoin() }

    /** Bounded reader join, never a model/JNI join. Failure retains the exact quarantined owner. */
    fun stopAndJoin(): Boolean {
        requestStop()
        val reader = worker
        if (reader === Thread.currentThread()) return false
        if (reader != null) {
            try { reader.join(STOP_JOIN_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            if (reader.isAlive) reapAfterReaderExit(reader) else synchronized(lock) {
                if (worker === reader) worker = null
                readerJoined = true
            }
        }
        disposeIfReady()
        return synchronized(lock) { readerJoined && worker?.isAlive != true && resources?.stopFinished != false && !hardwareStopFailed }
    }

    /** Native owners must await actual reader exit before touching consumer/VAD/decoder disposal. */
    fun awaitReaderExit() {
        val reader = worker ?: return
        check(reader !== Thread.currentThread()) { "Cannot join the microphone reader from itself" }
        var interrupted = false
        while (reader.isAlive) try { reader.join() } catch (_: InterruptedException) { interrupted = true }
        synchronized(lock) { if (worker === reader) worker = null; readerJoined = true }
        disposeIfReady()
        if (interrupted) Thread.currentThread().interrupt()
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) requestStop()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) { ownershipReleaseRequested = true }
        requestStop()
        owner.lifecycle.removeObserver(this)
        disposeIfReady()
    }

    private fun disposeIfReady() {
        val disposable = synchronized(lock) {
            if (opening || disposing || !readerJoined || !revoked.get()) return
            val owned = resources
            if (owned != null && !owned.stopFinished) return
            if (owned != null) { resources = null; disposing = true }
            owned
        }
        if (disposable != null) {
            try {
                runCatching { disposable.configuration.close() }
                runCatching { disposable.effects.close() }
                runCatching { disposable.record.release() }
            } finally { synchronized(lock) { disposing = false } }
        }
        synchronized(lock) {
            if (!opening && !disposing && resources == null && readerJoined && ownershipReleaseRequested) {
                microphoneLease?.close()
                microphoneLease = null
            }
        }
    }

    private data class CapturedFrame(val samples: FloatArray, val timing: CaptureFrameTiming)

    private fun isRecording(record: AudioRecord) = !revoked.get() && !closed.get() &&
        MicCaptureCoordinator.isCurrent(requestEpoch) && recorder === record

    /** Stops hardware before notifying a possibly blocking model/observer. One terminal event. */
    private fun captureFailed(record: AudioRecord, code: CaptureFailureCode, clockFailure: CaptureClockFailureSnapshot? = null) {
        val report = synchronized(lock) {
            if (!isRecording(record)) false else {
                captureFailureCode = code
                captureFailureIssue = when {
                    code == CaptureFailureCode.PreprocessingFailure -> LiveAudioStartIssue.PreprocessingUnavailable
                    clockFailure != null -> LiveAudioStartIssue.CaptureTimingUnavailable
                    else -> LiveAudioStartIssue.CaptureUnavailable
                }
                captureFailureDiagnostics = CaptureFailureDiagnostics(requestEpoch, record.audioSessionId,
                    System.identityHashCode(record), System.nanoTime(), code.ordinal + 1)
                captureClockFailure = clockFailure
                revoked.set(true)
                true
            }
        }
        if (!report) return
        requestStop()
        android.util.Log.w("KompiLiveAudio", "phase=capture_continuity_lost code=$code clock=$clockFailure")
        activeConsumer?.onCaptureError(checkNotNull(captureFailureIssue).guidance)
    }

    /** This owner cannot exit (and release record/effects/lease) before BOTH acquisition and DSP.
     * A hung processor never blocks AudioRecord.stop; it only quarantines its stopped resources.
     */
    private fun readLoop(record: AudioRecord) {
        val owned = synchronized(lock) { checkNotNull(resources) }
        val capacity = record.bufferSizeInFrames
        // Bound application backlog by one actual hardware buffer, rounded to consumer frames.
        // Never block acquisition behind DSP or drop an oldest/newest packet to appear healthy.
        val frames = ArrayBlockingQueue<CapturedFrame>(max(1, (capacity + FRAME_SAMPLES - 1) / FRAME_SAMPLES))
        val acquisition = Thread({ acquireFrames(record, owned, capacity, frames) }, "kompi-mic-reader")
        try {
            acquisition.start()
            while (!revoked.get() && !closed.get() && MicCaptureCoordinator.isCurrent(requestEpoch)) {
                val frame = frames.take()
                if (!isRecording(record)) break
                checkNotNull(activeConsumer).onAudio(frame.samples, frame.timing)
            }
        } catch (_: Exception) {
            captureFailed(record, CaptureFailureCode.ProcessorFailure)
        } finally {
            requestStop()
            acquisition.interrupt()
            var interrupted = false
            while (acquisition.isAlive) try { acquisition.join() } catch (_: InterruptedException) { interrupted = true }
            frames.clear()
            reapAfterReaderExit(Thread.currentThread())
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun acquireFrames(record: AudioRecord, owned: Resources, capacity: Int, frames: ArrayBlockingQueue<CapturedFrame>) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val startBefore = System.nanoTime()
            val started = MicCaptureCoordinator.performIfCurrent(requestEpoch) {
                synchronized(lock) {
                    ensureEligible()
                    owned.effects.requireSafe() // Local flags only; no extra effect API on acquisition.
                    requireHardwarePermit()
                    recorder = record
                    record.startRecording()
                }
            }
            check(started && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone capture was stopped or superseded" }
            acquisitionDiagnostics = checkNotNull(acquisitionDiagnostics).copy(phase = 1, recordingStartedNanos = System.nanoTime())
            val clock = CaptureReadClock(startBefore, capacity)
            val pcm = ShortArray(FRAME_SAMPLES)
            val timestamp = AudioTimestamp()
            var pending = FloatArray(FRAME_SAMPLES)
            var filled = 0
            var pendingTiming: CaptureFrameTiming? = null
            var firstFrame = true
            var readMode = AudioRecord.READ_NON_BLOCKING
            owned.started.countDown()
            while (isRecording(record)) {
                val began = System.nanoTime()
                clock.beforeRead(began)
                val modeForRead = readMode
                val count = record.read(pcm, 0, pcm.size - filled, modeForRead)
                val ended = System.nanoTime()
                if (count > 0) observeSuccessfulRead(pcm, count, ended)
                if (!isRecording(record)) break
                when {
                    count > 0 -> {
                        readMode = AudioRecord.READ_NON_BLOCKING
                        val required = requiredInputDevice
                        // A route must actually be established for the first PCM too. Waiting on
                        // an unobserved route while recording would lose/guess those first words.
                        if (required != null) {
                            val actual = record.routedDevice // The same pre-existing route query, once.
                            if (!sameDevice(actual, required)) {
                                captureInputRouteFailureDiagnostics = CaptureInputRouteFailureDiagnostics(requestEpoch,
                                    record.audioSessionId, System.identityHashCode(record), System.nanoTime(), record.audioSource,
                                    required.id, required.type, actual?.id ?: -1, actual?.type ?: -1)
                                error("The selected microphone route changed")
                            }
                        }
                        val timed = record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS && timestamp.nanoTime > 0
                        val timing = clock.afterRead(began, ended, System.nanoTime(), count, timestamp.takeIf { timed })
                        if (filled == 0) pendingTiming = timing else {
                            val first = checkNotNull(pendingTiming)
                            val projected = first.firstSampleNanos + (filled * first.nanosPerSample).toLong()
                            pendingTiming = first.copy(uncertaintyNanos = max(first.uncertaintyNanos,
                                abs(projected - timing.firstSampleNanos) + timing.uncertaintyNanos +
                                    ceil(count * abs(first.nanosPerSample - timing.nanosPerSample)).toLong()),
                                hardwareClock = first.hardwareClock && timing.hardwareClock)
                        }
                        for (i in 0 until count) pending[filled + i] = pcm[i] / 32768.0f
                        filled += count
                        if (filled == FRAME_SAMPLES) {
                            if (!frames.offer(CapturedFrame(pending, checkNotNull(pendingTiming)))) throw ProcessorBacklog()
                            if (firstFrame) { firstFrame = false; android.util.Log.i("KompiLiveAudio", "phase=capture_first_pcm") }
                            pending = FloatArray(FRAME_SAMPLES); filled = 0; pendingTiming = null
                        }
                    }
                    count == 0 && modeForRead == AudioRecord.READ_NON_BLOCKING -> {
                        // No positive sample is discarded while draining. Zero observes only
                        // CLIENT-readable emptiness, not HAL/ADC position or acoustic latency.
                        clock.afterClientEmpty(began, ended)
                        readMode = AudioRecord.READ_BLOCKING // No polling/spin waiting for PCM.
                    }
                    count == AudioRecord.ERROR_DEAD_OBJECT -> error("Microphone was disconnected")
                    count <= 0 -> error("Microphone read failed or made no progress")
                }
            }
        } catch (error: Exception) {
            owned.startFailure = error
            captureFailed(record, when {
                error is ProcessorBacklog -> CaptureFailureCode.ProcessorBacklog
                error is LiveAudioStartException && error.issue == LiveAudioStartIssue.PreprocessingUnavailable -> CaptureFailureCode.PreprocessingFailure
                else -> CaptureFailureCode.ReaderIntegrity
            }, (error as? CaptureClockDiscontinuity)?.snapshot)
        } finally {
            acquisitionDiagnostics = checkNotNull(acquisitionDiagnostics).copy(phase = 3)
            owned.started.countDown()
            if (isRecording(record)) captureFailed(record, CaptureFailureCode.ReaderIntegrity)
        }
    }

    private fun observeSuccessfulRead(pcm: ShortArray, count: Int, ended: Long) {
        val previous = checkNotNull(acquisitionDiagnostics)
        var squares = 0.0
        var peak = previous.inputPeak
        var nonzero = 0L
        for (i in 0 until count) {
            val sample = pcm[i].toInt()
            squares += sample.toDouble() * sample
            peak = max(peak, abs(sample))
            if (sample != 0) nonzero++
        }
        acquisitionDiagnostics = previous.copy(phase = 2,
            firstReadNanos = if (previous.successfulReads == 0L) ended else previous.firstReadNanos,
            lastReadNanos = ended,
            successfulReads = previous.successfulReads + 1L,
            successfulReadSamples = previous.successfulReadSamples + count,
            inputSumSquares = previous.inputSumSquares + squares,
            inputPeak = peak,
            inputNonzeroSamples = previous.inputNonzeroSamples + nonzero)
    }

    /** Read order and client-buffer age are observable; exact HAL-to-client position and upstream
     * acoustic latency are not. APM receives an acquisition-time delivery estimate, NOT a physical
     * same-interval proof. Missing hardware timestamps never manufacture a cumulative clock fault.
     * Actual reader deadline/span, queue overflow and observed hardware clock faults still stop.
     */
    private class CaptureReadClock(private val startBefore: Long, private val capacity: Int) {
        private val fastest = 60_000.0
        private val slowest = 65_000.0
        private var period = 1_000_000_000.0 / SAMPLE_RATE
        private var readFrames = 0L
        // Begin brackets conservatively bound when a read consumed data or observed client-empty.
        private var lastReadAnchor = startBefore
        private val runway = capacity * fastest
        private var previousFrame = -1L
        private var previousTime = 0L
        private var rateFrame = 0L
        private var rateTime = 0L
        private var clientEmptyBegan = 0L
        private var clientEmptyEnded = 0L

        // Acquisition-thread observations are copied only on the first failed rule. A client-empty
        // bracket never pretends that an earlier observed continuity failure has been repaired.
        private var readBegan = startBefore
        private var readEnded = 0L
        private var observedAt = startBefore
        private var readCount = 0
        private var observedFrame = -1L
        private var observedTime = 0L
        private var earliest = 0L
        private var latest = 0L
        private var estimatedFirst = 0L

        private fun requireClock(condition: Boolean, rule: ClockRule) {
            if (condition) return
            throw CaptureClockDiscontinuity(CaptureClockFailureSnapshot(rule.code, capacity, readFrames, readCount,
                observedFrame, observedTime, previousFrame, previousTime, rateFrame, rateTime,
                readBegan, readEnded, observedAt, readBegan - lastReadAnchor, ceil(runway).toLong(),
                -1L, -1L, -1L, earliest, latest, clientEmptyBegan, clientEmptyEnded, estimatedFirst, period))
        }

        fun beforeRead(now: Long) {
            readBegan = now; readEnded = 0L; observedAt = now; readCount = 0
            observedFrame = -1L; observedTime = 0L
            earliest = 0L; latest = 0L; estimatedFirst = 0L
            requireClock(now - lastReadAnchor < runway, ClockRule.BeforeReadDeadline)
        }

        private fun requireReadDeadline(began: Long, ended: Long, observed: Long) {
            requireClock(ended - began < runway, ClockRule.ReadTooLong)
            // Include scheduling/query delay BEFORE moving the anchor. A later read or zero cannot
            // erase a period when the dedicated reader might have overrun its actual buffer.
            requireClock(observed - lastReadAnchor < runway, ClockRule.BufferDuring)
        }

        fun afterClientEmpty(began: Long, ended: Long) {
            readBegan = began; readEnded = ended; observedAt = ended
            requireReadDeadline(began, ended, ended)
            clientEmptyBegan = began; clientEmptyEnded = ended
            lastReadAnchor = began
            // Only a CLIENT-readable empty observation. It does not locate the first physical
            // sample of a later upstream burst, so it must NOT tighten physical sample bounds.
        }

        fun afterRead(began: Long, ended: Long, observed: Long, count: Int, timestamp: AudioTimestamp?): CaptureFrameTiming {
            readBegan = began; readEnded = ended; observedAt = observed; readCount = count
            observedFrame = timestamp?.framePosition ?: -1L; observedTime = timestamp?.nanoTime ?: 0L
            requireReadDeadline(began, ended, observed)
            if (timestamp != null) {
                requireClock(timestamp.framePosition >= 0, ClockRule.TimestampPosition)
                requireClock(timestamp.nanoTime <= observed, ClockRule.TimestampFuture)
                if (previousFrame >= 0) {
                    val df = timestamp.framePosition - previousFrame
                    val dt = timestamp.nanoTime - previousTime
                    requireClock(df >= 0 && dt >= 0 && ((df == 0L) == (dt == 0L)), ClockRule.TimestampReset)
                    // Adjacent HAL timestamps can jitter by ~1 ms for a 20 ms update on some devices.
                    // Their quotient is not a sample-rate measurement. Keep reset/deadline checks
                    // immediate, but diagnose rate only over the full buffer-sized window below.
                } else { rateFrame = timestamp.framePosition; rateTime = timestamp.nanoTime }
                if (timestamp.framePosition - rateFrame >= capacity) {
                    val measured = (timestamp.nanoTime - rateTime).toDouble() / (timestamp.framePosition - rateFrame)
                    requireClock(measured.isFinite() && measured in fastest..slowest, ClockRule.TimestampWindowRate)
                    period = measured
                    rateFrame = timestamp.framePosition; rateTime = timestamp.nanoTime
                }
            }
            earliest = began - ceil(capacity * slowest).toLong()
            latest = ended - ((count - 1) * fastest).toLong()
            requireClock(latest >= earliest, ClockRule.ReadBounds)
            val hardwareCandidate = timestamp?.takeIf { observed - it.nanoTime < runway }?.let {
                it.nanoTime + ((readFrames - it.framePosition) * period).toLong()
            }
            val hardware = hardwareCandidate != null && hardwareCandidate in earliest..latest
            // Timestamp absence/staleness or an unproven HAL-to-client mapping downgrades this
            // estimate, never a separately observed reset/deadline/overflow fault checked above.
            estimatedFirst = if (hardware) checkNotNull(hardwareCandidate) else earliest + (latest - earliest) / 2
            val uncertainty = max(estimatedFirst - earliest, latest - estimatedFirst) +
                ceil(slowest + (count - 1) * (slowest - fastest)).toLong()
            readFrames += count
            lastReadAnchor = began
            if (timestamp != null) { previousFrame = timestamp.framePosition; previousTime = timestamp.nanoTime }
            // Keep first-sample offsets at acquisition. FIFO residence and each later 10 ms
            // subframe's offset remain observable to APM; never replace this with consumer-now.
            return CaptureFrameTiming(estimatedFirst, period, uncertainty, hardwareClock = hardware)
        }
    }

    private fun reapAfterReaderExit(reader: Thread) {
        if (!reaperStarted.compareAndSet(false, true)) return
        Thread({
            var interrupted = false
            while (reader.isAlive) try { reader.join() } catch (_: InterruptedException) { interrupted = true }
            synchronized(lock) { if (worker === reader) worker = null; readerJoined = true }
            disposeIfReady()
            if (interrupted) Thread.currentThread().interrupt()
        }, "kompi-mic-join").apply { isDaemon = true; start() }
    }

    private fun sameDevice(actual: AudioDeviceInfo?, preferred: AudioDeviceInfo): Boolean = actual != null &&
        actual.type == preferred.type && if (preferred.address.isNullOrBlank()) actual.id == preferred.id else actual.address == preferred.address

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val START_WAIT_MS = 2_000L
        private const val STOP_JOIN_MS = 2_000L
        internal const val FRAME_SAMPLES = 512
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
