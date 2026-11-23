package com.kompi.app.audio

import android.media.AudioTimestamp
import android.media.AudioTrack
import android.util.Log
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/** Acquisition-time FIRST-sample estimate in Android MONOTONIC time, retained through FIFO.
 * Hardware timestamps may refine it; a software delivery/buffer-age estimate is NOT ADC-position
 * proof. uncertaintyNanos records provenance, not a physical alignment admission threshold.
 */
data class CaptureFrameTiming(
    val firstSampleNanos: Long,
    val nanosPerSample: Double = 1_000_000_000.0 / ForegroundMicCapture.SAMPLE_RATE,
    val uncertaintyNanos: Long,
    val hardwareClock: Boolean,
)

/** Scoped unavailable-backend fault, never a user setting or replacement microphone/recognizer. */
internal object LiveSoftwareEchoFactory {
    private val lock = Any()
    private var unavailableOwner: Any? = null
    fun unavailableForTesting(): Closeable {
        val token = Any()
        synchronized(lock) {
            check(!LiveAudioSession.isActive && unavailableOwner == null)
            unavailableOwner = token
        }
        return Closeable { synchronized(lock) { if (unavailableOwner === token) unavailableOwner = null } }
    }
    fun create(onFailure: (() -> Boolean, () -> Unit) -> Unit): DuplexEchoProcessor {
        synchronized(lock) { if (unavailableOwner != null) throw LiveAudioStartException(LiveAudioStartIssue.EchoUnavailable) }
        val handle = try { WebRtcAec3Native.create() } catch (_: LinkageError) { 0L }
        if (handle == 0L) throw LiveAudioStartException(LiveAudioStartIssue.EchoUnavailable)
        return DuplexEchoProcessor(handle, onFailure)
    }
}

internal object WebRtcAec3Native {
    init { System.loadLibrary("kompi_aec") }
    external fun create(): Long
    external fun registerRenderStream(handle: Long, rate: Int): Long
    external fun resampleRender(handle: Long, stream: Long, rate: Int, input: FloatArray, output: FloatArray): Int
    external fun retireRenderStream(handle: Long, stream: Long, tail: FloatArray): Int
    external fun dropRenderStream(handle: Long, stream: Long)
    external fun reverse(handle: Long, input: FloatArray, captureSuspended: Boolean): Int
    external fun diagnostics(handle: Long): DoubleArray?
    external fun capture(handle: Long, input: FloatArray, delayMs: Int, output: FloatArray): Int
    external fun destroy(handle: Long)
}

/** One official APM/AEC3 per retained duplex session, not per phrase or microphone wrapper.
 *
 * PRE-HAL contract: LivePcmBlockWriter analyzes one final clipped source-rate 10 ms block, then
 * completes that block's bounded/partial AudioTrack writes before analyzing another. APM analyzes
 * HAL-bound PCM; successful-write/played/drain accounting is SEPARATE and never claims scheduled
 * PCM was heard. Flush leaves bounded already-analyzed write-ahead, followed by reference silence;
 * it does not reset APM or erase the played acoustic tail.
 *
 * Capture is independent of output heads/frontiers/timestamps: every real sample goes through APM
 * in order, then the unchanged 512-sample VAD/ASR consumer cadence. Optional hardware timestamps
 * refine coarse output delay. Official stream delay is capture age plus current output queue /
 * downstream estimate; AEC3's INTERNAL correlating delay estimator remains enabled.
 *
 * processLock serializes only native/framing/metrics, NEVER AudioTrack.write or the VAD/model
 * consumer. Privacy fencing only marks epochs/snapshots heads; native retirement/disposal is async.
 */
internal class DuplexEchoProcessor(private val handle: Long,
    private val onFailure: (() -> Boolean, () -> Unit) -> Unit) : Closeable {
    private class SyncLost(val code: Int = 1) : IllegalStateException()
    private data class FailureSnapshot(val code: Int, val nativeStatus: Int, val captureFrames: Long,
        val captureUncertaintyNs: Long, val referenceUncertaintyNs: Long, val hasReference: Int,
        val captureHardware: Int, val renderHardware: Int, val referenceCount: Int, val consumerFilled: Int)
    private data class Observation(val raw: Long, val timestamp: AudioTimestamp?, val began: Long,
        val ended: Long, val bufferNanos: Long, val sequence: Long)
    private class Render(val id: Long, val rate: Int, var track: AudioTrack?) {
        @Volatile var frozen = false
        @Volatile var ended = false
        var accepted = 0L
        var analyzed = 0L
        var analyzing = 0 // Reserved pre-HAL block; a concurrent freeze must credit it too.
        var flushCreditPublished = false
        var played = 0L
        var lastRaw = 0L
        var wraps = 0L
        var writesInFlight = 0
        var inputComplete = false
        var issuedObservation = 0L
        var appliedObservation = 0L
        var observation: Observation? = null
        var emptyFrontier = -1L
        var converter = 0L // processLock only, retired exactly once, never on privacy caller.
        var lastReverseEnergy = 0.0
    }
    private data class DelayEstimate(val nanos: Long, val uncertainty: Long, val hardware: Boolean,
        val idle: Boolean, val energy: Double)
    private data class FlushCredit(val events: Long, val acceptedNanos: Long, val unacceptedNanos: Long)
    private data class FlushGeometry(val publishedNanos: Long, val renderId: Long, val rate: Int,
        val analyzed: Long, val analyzing: Int, val accepted: Long, val played: Long,
        val headObservedNanos: Long, val acceptedCreditNanos: Long, val unacceptedCreditNanos: Long)
    private val dataLock = Any()
    private val processLock = Any()
    private val closed = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val failureCode = AtomicInteger(0)
    private val firstFault = AtomicReference<FailureSnapshot?>()
    @Volatile private var nativeStatus = 0
    private val ids = AtomicLong()
    private val captureId = AtomicLong()
    private val captureReadyEpoch = AtomicLong()
    private val referenceSilence = FloatArray(FRAME)
    private var idleReferenceSince = 0L // processLock; zero means no established quiet interval.
    // One conserved MEDIA phase, not a claim of ADC/DAC alignment. Positive is known quiet
    // not yet analyzed (<10 ms after service); negative is reference time already analyzed.
    // Never clear this on a new track, EOF, flush, retirement or capture-owner replacement.
    private var referencePhaseNanos = 0L
    private var referenceQuietElapsedNanos = 0L
    private var referenceRealFrames = 0L
    private var referenceQuietFrames = 0L
    private var referenceEofPaddedFrames = 0L
    private var referenceEofPaddingNanos = 0L
    private var referenceTailFrames = 0L
    private var referenceFlushEvents = 0L
    private var referenceFlushAcceptedNanos = 0L
    private var referenceFlushUnacceptedNanos = 0L
    private var referencePhaseMinimumNanos = 0L
    private var referencePhaseFaults = 0L
    private var referenceQuietBacklogFaults = 0L
    // Privacy callers publish only bounded scalar credit under dataLock. They never take
    // processLock or enter JNI; the processing owner consumes it before any quiet analysis.
    private var pendingFlushEvents = 0L
    private var pendingFlushAcceptedNanos = 0L
    private var pendingFlushUnacceptedNanos = 0L
    private var lastFlushGeometry: FlushGeometry? = null // dataLock; one bounded receipt, no PCM/track.
    private val renders = LinkedHashMap<Long, Render>()
    private val input = FloatArray(FRAME)
    private var filled = 0
    private var currentCapture = 0L
    private var frameTime = 0L
    private var frameUncertainty = 0L
    private var frameHardwareClock = false
    private val consumerFrame = FloatArray(ForegroundMicCapture.FRAME_SAMPLES)
    private var consumerFilled = 0
    private var consumerPreparedFrames = 0L
    private var consumerPreparedEnergy = 0.0
    private var lastConsumerRms = 0.0
    @Volatile private var metricsFrames = 0L
    private var metricIn = 0.0
    private var metricOut = 0.0
    @Volatile private var diagnosticTotalInputEnergy = 0.0
    @Volatile private var diagnosticTotalOutputEnergy = 0.0
    @Volatile private var diagnosticTotalReferenceEnergy = 0.0
    @Volatile private var diagnosticReferenceUncertainty = 0L
    @Volatile private var diagnosticHasReference = false
    @Volatile private var diagnosticRenderHardware = false
    @Volatile private var diagnosticStreamDelayNs = 0L
    @Volatile private var diagnosticReverseFrames = 0L
    private data class NativeHistoryDiagnostics(val observedNanos: Long, val captureFrames: Long,
        val reverseFrames: Long, val suspendedReverseFrames: Long, val suspendedHistoryFaults: Long,
        val statsPresentMask: Int, val suspendedHistoryEventMask: Int,
        val suspendedTransitionLatencyBlocks: Int, val suspendedAdvancedBlocks: Int,
        val suspendedRecoveryLatencyBlocks: Int, val nativeStatus: Int, val failureCode: Int,
        val normalInsertOverruns: Long, val normalPrepareExcessResets: Long, val normalPrepareUnderruns: Long,
        val normalInsertLatencyBefore: Int, val normalInsertLatencyAfter: Int,
        val normalExcessLatencyBefore: Int, val normalExcessLatencyAfter: Int,
        val normalUnderrunLatencyBefore: Int, val normalUnderrunLatencyAfter: Int,
        val currentRenderLatencyBlocks: Int, val subtractorFullResets: Long,
        val referenceOnlyState: Int,
        val referenceOnlyInheritedLatencyBlocks: Int,
        val referenceOnlyInheritedFullRateLagBlocks: Int,
        val referenceOnlyPairBeforeLatencyBlocks: Int,
        val referenceOnlyPairAfterLatencyBlocks: Int,
        val referenceOnlyPairBeforeFullRateLagBlocks: Int,
        val referenceOnlyPairAfterFullRateLagBlocks: Int,
        val referenceOnlyTicks: Long,
        val referenceOnlyPhaseFaults: Long,
        val referenceOnlyCanonicalMinimumLatencyBlocks: Int,
        val referenceOnlyDetectorClock: Long,
        val referenceOnlyNormalDetectorClockAtEntry: Long,
        val referenceOnlyNormalDetectorClock: Long,
        val referenceOnlyNormalMinimumLatencyAtEntry: Int,
        val referenceOnlyNormalMinimumLatency: Int,
        val referenceOnlyCaptureClockAtEntry: Long,
        val referenceOnlyCaptureClock: Long,
        val referenceOnlyLowRateCapacityBlocks: Int,
        val referenceOnlyMaximumFullRateLagBlocks: Int,
        val referenceOnlyRenderClockAtEntry: Long,
        val referenceOnlyRenderClock: Long,
        val referenceOnlyTicksAtEntry: Long,
        val referenceOnlyResumeLatencyBlocks: Int,
        val referenceOnlyResumeFullRateLagBlocks: Int,
        val decisionPresenceMask: Int,
        val decisionProcessedBlocks: Long,
        val decisionProcessedSamples: Long,
        val decisionSelectedLinearBlocks: Long,
        val decisionSelectedRawBlocks: Long,
        val decisionUsableLinearBlocks: Long,
        val decisionTransparentBlocks: Long,
        val decisionNearendBlocks: Long,
        val decisionCaptureSaturatedBlocks: Long,
        val decisionEchoSaturatedBlocks: Long,
        val decisionInitialStateBlocks: Long,
        val decisionResidualUsableSaturatedBlocks: Long,
        val decisionResidualLinearUncertaintyBlocks: Long,
        val decisionResidualLinearSpectrumBlocks: Long,
        val decisionResidualUnusableSaturatedBlocks: Long,
        val decisionResidualRenderPowerBlocks: Long,
        val decisionCaptureInputTimeEnergyQ30: Long,
        val decisionLinearTimeEnergyQ30: Long,
        val decisionSelectedTimeEnergyQ30: Long,
        val decisionOutputTimeEnergyQ30: Long,
        val decisionPreGainSpectralEnergyQ30: Long,
        val decisionPostGainSpectralEnergyQ30: Long,
        val decisionGainSquaredSumQ20: Long,
        val decisionGainBinCount: Long,
        val decisionLastGainMinQ15: Int,
        val decisionLastGainMaxQ15: Int,
        val decisionLastFlagMask: Int,
        val decisionLastResidualBranch: Int,
        val decisionInvalidNumericBlocks: Long,
        val decisionLastObservedBlockSequence: Long,
        val decisionEchoRemoverCalls: Long)
    @Volatile private var nativeHistoryDiagnostics: NativeHistoryDiagnostics? = null
    /** One immutable, numeric-only publication. Existing native-history fields/API stay intact.
     * Source-frame geometry belongs to renderRate; reverse/capture counts are 160-sample blocks.
     * EOF padding is included in realReverseFrames, not an additional reverse call. Flush credit
     * describes already analyzed source time, not additional PCM or proof that it was heard.
     */
    private data class ReferenceMediaDiagnostics(
        val observedNanos: Long, val captureFrames: Long, val reverseFrames: Long,
        val realReverseFrames: Long, val quietReverseFrames: Long, val eofPaddedFrames: Long,
        val eofPaddingNanos: Long, val retirementTailFrames: Long,
        val flushCreditEvents: Long, val flushAcceptedNanos: Long, val flushUnacceptedNanos: Long,
        val quietElapsedNanos: Long, val phaseNanos: Long, val phaseMinimumNanos: Long,
        val phaseFaults: Long, val quietBacklogFaults: Long,
        val pendingFlushEvents: Long, val pendingFlushAcceptedNanos: Long, val pendingFlushUnacceptedNanos: Long,
        val renderId: Long, val renderRate: Int, val analyzedSourceFrames: Long,
        val analyzingSourceFrames: Int, val acceptedSourceFrames: Long, val playedSourceFrames: Long,
        val writesInFlight: Int, val renderFrozen: Int, val headObservedNanos: Long,
        val headTimestampFrames: Long, val headTimestampNanos: Long,
        val captureFirstSampleNanos: Long, val captureNanosPerSample: Double,
        val captureUncertaintyNanos: Long, val captureHardwareClock: Int,
        val lastFlushPublishedNanos: Long, val lastFlushRenderId: Long, val lastFlushRate: Int,
        val lastFlushAnalyzedSourceFrames: Long, val lastFlushAnalyzingSourceFrames: Int,
        val lastFlushAcceptedSourceFrames: Long, val lastFlushPlayedSourceFrames: Long,
        val lastFlushHeadObservedNanos: Long, val lastFlushAcceptedNanos: Long, val lastFlushUnacceptedNanos: Long,
    )
    @Volatile private var referenceMediaDiagnostics: ReferenceMediaDiagnostics? = null
    private var frameNanosPerSample = 0.0
    private var lastRenderGeometry: Render? = null // track is nulled before stream removal.
    // Same reflected name/runtime type. Only complete immutable COW arrays are published.
    @Volatile private var diagnosticFrames = CopyOnWriteArrayList<String>()
    private data class DiagnosticFrame(val sequence: Long, val inputEnergy: Double,
        val outputEnergy: Double, val trace: String)
    private val diagnosticInitialFrames = ArrayList<DiagnosticFrame>(30)
    private val diagnosticRecentFrames = ArrayList<DiagnosticFrame>(50)
    private val diagnosticOnsetFrames = ArrayList<DiagnosticFrame>(31)
    private val idleReferenceTask = idleClock.scheduleWithFixedDelay(::serviceSuspendedIdleReference, 10, 10,
        java.util.concurrent.TimeUnit.MILLISECONDS)

    inner class CaptureLease(private val id: Long, private val consumer: Pcm16kConsumer,
        private val originActive: () -> Boolean) : SoftwareEchoCaptureConsumer, Closeable {
        private val active = AtomicBoolean(true)
        override val echoCancellation get() = EchoCancellationPolicy.SoftwareOwned
        override val preferredInputDevice get() = consumer.preferredInputDevice
        override fun onCaptureError(message: String) = consumer.onCaptureError(message)
        private fun ownsCapture() = active.get() && originActive() && captureId.get() == id
        override fun onAudio(samples: FloatArray) { fail(9, ::ownsCapture) }
        override fun onAudio(samples: FloatArray, timing: CaptureFrameTiming) {
            if (!ownsCapture() || closed.get()) return
            try {
                val deliveries = process(id, samples, timing)
                for (clean in deliveries) if (ownsCapture() && !closed.get()) consumer.onAudio(clean)
            } catch (error: Exception) {
                val code = (error as? SyncLost)?.code ?: 1
                if (isProcessorFault(code)) fail(code) else fail(code, ::ownsCapture)
            }
        }
        override fun close() {
            active.set(false)
            captureReadyEpoch.compareAndSet(id, 0L)
            captureId.compareAndSet(id, 0L)
        }
    }

    fun capture(consumer: Pcm16kConsumer, originActive: () -> Boolean): CaptureLease {
        check(!closed.get())
        val id = ids.incrementAndGet()
        captureId.set(id)
        return CaptureLease(id, consumer, originActive)
    }

    interface RenderLease : Closeable {
        fun beginWrite(): Closeable
        fun analyze(samples: FloatArray, offset: Int, count: Int)
        fun accepted(samples: FloatArray, offset: Int, count: Int)
        fun finishWrites()
        fun freeze()
        fun rejectPcm()
    }

    private inner class RenderReference(private val render: Render) : RenderLease {
        override fun beginWrite(): Closeable {
            synchronized(dataLock) {
                check(!closed.get() && !render.frozen && !render.ended && !render.inputComplete)
                check(render.writesInFlight == 0)
                render.writesInFlight = 1
            }
            val complete = AtomicBoolean(false)
            return Closeable { if (complete.compareAndSet(false, true)) synchronized(dataLock) { render.writesInFlight = 0 } }
        }
        override fun analyze(samples: FloatArray, offset: Int, count: Int) {
            require(offset >= 0 && count in 1..render.rate / 100 && offset <= samples.size - count)
            // JNI accepts canonical source-rate 10 ms frames. Only the reference/converter
            // input is zero-padded; AudioTrack and accepted/analyzed source counts stay EXACT.
            val actual = FloatArray(render.rate / 100)
            samples.copyInto(actual, 0, offset, offset + count)
            if (actual.any { !it.isFinite() || it !in -1f..1f }) { rejectPcm(); throw SyncLost(10) }
            refresh(render)
            try {
                synchronized(processLock) {
                    check(!closed.get())
                    absorbFlushCredit()
                    // Finish ONLY a previously known quiet interval before reserving the new
                    // real block. A quiet/ledger fault must not create an unanalyzed receipt.
                    if (idleReferenceSince != 0L) advanceIdleReferenceClock(System.nanoTime())
                    synchronized(dataLock) {
                        check(!render.frozen && !render.ended && render.analyzing == 0)
                        render.analyzing = count
                        lastRenderGeometry = render
                    }
                    idleReferenceSince = 0L
                    try {
                        if (render.converter == 0L) render.converter = WebRtcAec3Native.registerRenderStream(handle, render.rate)
                            .also { if (it == 0L) throw SyncLost(6) }
                        val reverse = FloatArray(FRAME)
                        nativeOk(WebRtcAec3Native.resampleRender(handle, render.converter, render.rate, actual, reverse))
                        val paddingNanos = (actual.size - count) * 1_000_000_000L / render.rate
                        reverse(reverse, ReferenceKind.Real, paddingNanos)
                        render.lastReverseEnergy = energy(reverse)
                        synchronized(dataLock) { render.analyzed += count; render.analyzing = 0 }
                    } finally {
                        synchronized(dataLock) { render.analyzing = 0 }
                        publishReferenceMediaDiagnostics()
                    }
                }
            } catch (error: Exception) {
                val code = (error as? SyncLost)?.code ?: 1
                // Native state and phase belong to the APM owner, not a retired producer.
                // A failed reserved analysis may already have a provisional freeze receipt;
                // never let later quiet processing continue after that backend failure.
                if (isProcessorFault(code)) fail(code) else fail(code) { !render.frozen && !render.ended }
                throw error
            }
        }
        override fun accepted(samples: FloatArray, offset: Int, count: Int) {
            require(offset >= 0 && count >= 0 && offset <= samples.size - count)
            synchronized(dataLock) {
                if (!render.ended) {
                    render.accepted += count
                    check(render.accepted <= render.analyzed)
                }
            }
        }
        override fun finishWrites() { synchronized(dataLock) { render.inputComplete = true } }
        override fun rejectPcm() = fail(10) { !render.frozen && !render.ended }
        override fun freeze() {
            var fault = 0
            try { refresh(render) } catch (error: Exception) { fault = (error as? SyncLost)?.code ?: 1 }
            synchronized(dataLock) {
                publishFlushCredit(render)
                render.frozen = true; render.track = null
            }
            cleanup.execute {
                try { synchronized(processLock) { if (!closed.get()) { absorbFlushCredit(); retire(render) } } }
                catch (error: Exception) { fail((error as? SyncLost)?.code ?: 1) }
            }
            if (fault != 0) fail(fault)
        }
        override fun close() {
            try {
                refresh(render)
                synchronized(processLock) {
                    if (!closed.get()) {
                        synchronized(dataLock) {
                            publishFlushCredit(render)
                            render.frozen = true; render.track = null
                        }
                        absorbFlushCredit()
                        retire(render)
                    }
                }
            } catch (error: Exception) { fail((error as? SyncLost)?.code ?: 1) }
            finally {
                synchronized(dataLock) {
                    publishFlushCredit(render)
                    render.frozen = true; render.ended = true; render.track = null; renders.remove(render.id)
                }
            }
        }
    }

    fun render(track: AudioTrack, rate: Int): RenderLease {
        require(rate in 8_000..96_000 && rate % 100 == 0)
        val render = Render(ids.incrementAndGet(), rate, track)
        synchronized(dataLock) {
            check(!closed.get() && renders.values.none { !it.ended })
            check(renders.size < MAX_STREAMS)
            renders[render.id] = render
        }
        return RenderReference(render)
    }

    /** Observation starts are numbered before JNI; stale concurrent observations never reset heads. */
    private fun refresh(render: Render) {
        val watched = synchronized(dataLock) {
            if (render.frozen || render.ended) null else render.track?.let { it to ++render.issuedObservation }
        } ?: return
        val (track, sequence) = watched
        val observation = try {
            val began = System.nanoTime()
            val raw = track.playbackHeadPosition.toLong() and 0xffff_ffffL
            val timestamp = AudioTimestamp().takeIf { track.getTimestamp(it) && it.nanoTime > 0 && it.framePosition > 0 }
            Observation(raw, timestamp, began, System.nanoTime(), track.bufferSizeInFrames * 1_000_000_000L / render.rate, sequence)
        } catch (_: IllegalStateException) { return }
        synchronized(dataLock) {
            if (render.frozen || render.ended || sequence <= render.appliedObservation) return
            render.appliedObservation = sequence
            if (observation.raw < render.lastRaw) {
                if (render.lastRaw - observation.raw > 0x8000_0000L) render.wraps += 0x1_0000_0000L
                else throw SyncLost(3)
            }
            render.lastRaw = observation.raw
            render.played = observation.raw + render.wraps
            if (render.played >= render.analyzed + render.analyzing) render.emptyFrontier = render.played
            render.observation = observation
        }
    }

    /** Coarse, current pipeline delay. No physical capture/render equality or head admission gate. */
    private fun delayEstimate(): DelayEstimate = synchronized(dataLock) {
        val render = renders.values.firstOrNull { !it.frozen && !it.ended }
            ?: return@synchronized DelayEstimate(0, 0, false, true, 0.0)
        val observation = render.observation
        val period = 1_000_000_000.0 / render.rate
        val scheduled = maxOf(render.accepted, render.analyzed + render.analyzing)
        val queued = ((scheduled - render.played).coerceAtLeast(0) * period).toLong()
        val idle = scheduled <= render.played && render.writesInFlight == 0
        if (idle) return@synchronized DelayEstimate(0, observation?.bufferNanos ?: 0, false, true, 0.0)
        // A timestamp at/before an exhausted source frontier describes the OLD presentation
        // segment. It cannot extrapolate through a queue-empty gap into newly written PCM.
        val timestamp = observation?.timestamp?.takeIf { it.framePosition + render.wraps > render.emptyFrontier }
        val downstream = if (timestamp != null && observation != null) {
            var position = timestamp.framePosition + render.wraps
            if (position - render.played > 0x8000_0000L) position -= 0x1_0000_0000L
            if (render.played - position > 0x8000_0000L) position += 0x1_0000_0000L
            timestamp.nanoTime + ((render.played - position) * period).toLong() - observation.ended
        } else (observation?.bufferNanos ?: 0) / 2
        val age = observation?.let { (System.nanoTime() - it.ended).coerceAtLeast(0) } ?: 0
        val uncertainty = if (timestamp != null) 1_000_000L + age else (observation?.bufferNanos ?: 0) + age
        val converterDelay = if (render.rate == 16_000) 0 else 16_000_000_000L / render.rate
        DelayEstimate(queued + downstream - converterDelay, uncertainty, timestamp != null,
            false, render.lastReverseEnergy)
    }

    private fun process(id: Long, samples: FloatArray, timing: CaptureFrameTiming): List<FloatArray> {
        if (timing.firstSampleNanos <= 0 || !timing.nanosPerSample.isFinite() ||
            timing.nanosPerSample !in 60_000.0..65_000.0 || timing.uncertaintyNanos < 0) throw SyncLost(2)
        val activeRenders = synchronized(dataLock) { renders.values.toList() }
        activeRenders.forEach(::refresh)
        val deliveries = ArrayList<FloatArray>(2)
        synchronized(processLock) {
            if (closed.get() || captureId.get() != id) return deliveries
            if (currentCapture != id) { currentCapture = id; filled = 0; consumerFilled = 0 }
            for (i in samples.indices) {
                if (filled == 0) {
                    frameTime = timing.firstSampleNanos + (i * timing.nanosPerSample).toLong()
                    frameUncertainty = timing.uncertaintyNanos; frameHardwareClock = timing.hardwareClock
                    frameNanosPerSample = timing.nanosPerSample
                } else {
                    frameUncertainty = maxOf(frameUncertainty, timing.uncertaintyNanos)
                    frameHardwareClock = frameHardwareClock && timing.hardwareClock
                }
                input[filled++] = samples[i]
                if (filled != FRAME) continue
                if (closed.get() || captureId.get() != id) return deliveries
                absorbFlushCredit()
                val render = delayEstimate()
                if (render.idle) advanceIdleReferenceClock(System.nanoTime())
                // Real analysis, not a pending beginWrite(), closes a known quiet interval.
                val delay = System.nanoTime() - frameTime + render.nanos
                diagnosticStreamDelayNs = delay
                diagnosticReferenceUncertainty = render.uncertainty
                diagnosticHasReference = !render.idle; diagnosticRenderHardware = render.hardware
                if (delay !in 0..500_000_000L) throw SyncLost(8)
                val output = FloatArray(FRAME)
                nativeOk(WebRtcAec3Native.capture(handle, input, (delay / 1_000_000).toInt(), output))
                if (captureId.get() == id) captureReadyEpoch.set(id)
                val rawEnergy = energy(input); val cleanEnergy = energy(output)
                metricIn += rawEnergy; metricOut += cleanEnergy
                diagnosticTotalInputEnergy += rawEnergy; diagnosticTotalOutputEnergy += cleanEnergy
                metricsFrames++; filled = 0
                val nativeMetrics = publishNativeHistory()
                if (metricsFrames % 10 == 0L) {
                    val nativeTrace = nativeMetrics?.let { " aecReportedDelayMs=${it[0]} aecReportedErl=${it[1]} aecReportedErle=${it[2]} suspendedReverseFrames=${it[3]} suspendedHistoryFaults=${it[4]} aecStatsPresentMask=${it[5]} suspendedHistoryEventMask=${it[6]} suspendedTransitionLatencyBlocks=${it[7]} suspendedAdvancedBlocks=${it[8]} suspendedRecoveryLatencyBlocks=${it[9]} normalInsertOverruns=${it[10]} normalPrepareExcessResets=${it[11]} normalPrepareUnderruns=${it[12]} normalInsertLatencyBefore=${it[13]} normalInsertLatencyAfter=${it[14]} normalExcessLatencyBefore=${it[15]} normalExcessLatencyAfter=${it[16]} normalUnderrunLatencyBefore=${it[17]} normalUnderrunLatencyAfter=${it[18]} currentRenderLatencyBlocks=${it[19]} subtractorFullResets=${it[20]}" + referenceOnlyTrace(it) + decisionTrace(it) } ?: ""
                    retainDiagnosticFrame(DiagnosticFrame(metricsFrames, diagnosticTotalInputEnergy,
                        diagnosticTotalOutputEnergy, "captureFrames=$metricsFrames aecHandle=$handle captureLeaseId=$id rawRms=${sqrt(rawEnergy / FRAME)} cleanRms=${sqrt(cleanEnergy / FRAME)} renderRms=${sqrt(render.energy / FRAME)} streamDelayNs=$delay captureUncertaintyNs=$frameUncertainty referenceUncertaintyNs=${render.uncertainty} reverseFrames=$diagnosticReverseFrames hardwareClocks=${frameHardwareClock && render.hardware} inputEnergy=$diagnosticTotalInputEnergy outputEnergy=$diagnosticTotalOutputEnergy referenceEnergy=$diagnosticTotalReferenceEnergy consumerPreparedFrames=$consumerPreparedFrames consumerPreparedEnergy=$consumerPreparedEnergy lastConsumerRms=$lastConsumerRms$nativeTrace${referenceMediaTrace()}"))
                }
                if (metricsFrames % 100 == 0L) {
                    Log.i("KompiLiveAudio", "phase=software_aec captureFrames=$metricsFrames rawRms=${sqrt(metricIn / (100 * FRAME))} cleanRms=${sqrt(metricOut / (100 * FRAME))} streamDelayNs=$delay")
                    metricIn = 0.0; metricOut = 0.0
                }
                var offset = 0
                while (offset < FRAME) {
                    val n = minOf(FRAME - offset, consumerFrame.size - consumerFilled)
                    output.copyInto(consumerFrame, consumerFilled, offset, offset + n)
                    consumerFilled += n; offset += n
                    if (consumerFilled == consumerFrame.size) {
                        val consumerEnergy = energy(consumerFrame)
                        consumerPreparedEnergy += consumerEnergy; consumerPreparedFrames++
                        lastConsumerRms = sqrt(consumerEnergy / consumerFrame.size)
                        deliveries += consumerFrame.copyOf(); consumerFilled = 0
                    }
                }
            }
        }
        return deliveries
    }

    private fun retire(render: Render) {
        if (render.converter == 0L) { publishReferenceMediaDiagnostics(); return }
        // Tail is a complete native 10 ms block even though only a kernel fragment is energetic.
        // Account any earlier known quiet FIRST, then retain all 10 ms as future quiet credit.
        if (idleReferenceSince != 0L) advanceIdleReferenceClock(System.nanoTime())
        val tail = FloatArray(FRAME)
        nativeOk(WebRtcAec3Native.retireRenderStream(handle, render.converter, tail))
        render.converter = 0
        reverse(tail, ReferenceKind.Tail)
    }
    private enum class ReferenceKind { Real, Quiet, Tail }

    /** All callers hold processLock. The native suspended latch clears only on REAL capture. */
    private fun reverse(samples: FloatArray, kind: ReferenceKind, paddingNanos: Long = 0L) {
        val epoch = captureId.get()
        nativeOk(WebRtcAec3Native.reverse(handle, samples, epoch == 0L || captureReadyEpoch.get() != epoch))
        diagnosticReverseFrames++
        diagnosticTotalReferenceEnergy += energy(samples)
        when (kind) {
            ReferenceKind.Real -> {
                referenceRealFrames++
                if (paddingNanos > 0L) {
                    referenceEofPaddedFrames++
                    referenceEofPaddingNanos += paddingNanos
                    creditReferenceTime(paddingNanos)
                }
            }
            ReferenceKind.Quiet -> { referenceQuietFrames++; referencePhaseNanos -= FRAME_NANOS }
            ReferenceKind.Tail -> { referenceTailFrames++; creditReferenceTime(FRAME_NANOS) }
        }
        publishNativeHistory()
    }

    /** dataLock only; called before the exact track's flush/reset, at most once per stream.
     * Includes the one reserved native block even if privacy races its analysis. Its subsequent
     * hardware write remains separately fenced. No PCM, native call or process-lock acquisition.
     */
    private fun publishFlushCredit(render: Render) {
        if (render.flushCreditPublished) return
        render.flushCreditPublished = true
        val analyzed = render.analyzed + render.analyzing
        val acceptedUnplayed = (render.accepted - render.played).coerceAtLeast(0L)
        val analyzedUnaccepted = (analyzed - maxOf(render.accepted, render.played)).coerceAtLeast(0L)
        if (acceptedUnplayed == 0L && analyzedUnaccepted == 0L) return
        val acceptedNanos = acceptedUnplayed * 1_000_000_000L / render.rate
        val unacceptedNanos = analyzedUnaccepted * 1_000_000_000L / render.rate
        pendingFlushEvents++
        pendingFlushAcceptedNanos += acceptedNanos
        pendingFlushUnacceptedNanos += unacceptedNanos
        lastFlushGeometry = FlushGeometry(System.nanoTime(), render.id, render.rate,
            render.analyzed, render.analyzing, render.accepted, render.played,
            render.observation?.ended ?: 0L, acceptedNanos, unacceptedNanos)
    }

    /** processLock only. Credit is never clipped, forgiven on replacement or converted to fake
     * microphone samples. Repeated immediate cancellations must expose, not hide, excessive lead.
     */
    private fun absorbFlushCredit(now: Long = System.nanoTime()) {
        // Fold elapsed known quiet and the pending debit BEFORE checking the shared bound.
        // Do not emit zeros between them: that would spend time already covered by this credit.
        accrueKnownQuiet(now)
        val pending = synchronized(dataLock) {
            if (pendingFlushEvents == 0L) return
            val result = FlushCredit(pendingFlushEvents, pendingFlushAcceptedNanos, pendingFlushUnacceptedNanos)
            pendingFlushEvents = 0L; pendingFlushAcceptedNanos = 0L; pendingFlushUnacceptedNanos = 0L
            result
        }
        referenceFlushEvents += pending.events
        referenceFlushAcceptedNanos += pending.acceptedNanos
        referenceFlushUnacceptedNanos += pending.unacceptedNanos
        creditReferenceTime(pending.acceptedNanos + pending.unacceptedNanos)
    }

    private fun creditReferenceTime(nanos: Long) {
        referencePhaseNanos -= nanos
        referencePhaseMinimumNanos = minOf(referencePhaseMinimumNanos, referencePhaseNanos)
        // Reuse the existing admitted APM-delay horizon, not an acoustic tuning parameter.
        if (referencePhaseNanos < -MAX_REFERENCE_CREDIT_NANOS) {
            referencePhaseFaults++
            publishReferenceMediaDiagnostics()
            throw SyncLost(12)
        }
    }

    private fun publishReferenceMediaDiagnostics() {
        synchronized(dataLock) {
            val render = renders.values.lastOrNull() ?: lastRenderGeometry
            val observation = render?.observation
            val flush = lastFlushGeometry
            referenceMediaDiagnostics = ReferenceMediaDiagnostics(System.nanoTime(), metricsFrames,
                diagnosticReverseFrames, referenceRealFrames, referenceQuietFrames,
                referenceEofPaddedFrames, referenceEofPaddingNanos, referenceTailFrames,
                referenceFlushEvents, referenceFlushAcceptedNanos, referenceFlushUnacceptedNanos,
                referenceQuietElapsedNanos, referencePhaseNanos, referencePhaseMinimumNanos,
                referencePhaseFaults, referenceQuietBacklogFaults,
                pendingFlushEvents, pendingFlushAcceptedNanos, pendingFlushUnacceptedNanos,
                render?.id ?: 0L, render?.rate ?: 0, render?.analyzed ?: 0L,
                render?.analyzing ?: 0, render?.accepted ?: 0L, render?.played ?: 0L,
                render?.writesInFlight ?: 0, if (render?.frozen == true) 1 else 0,
                observation?.ended ?: 0L, observation?.timestamp?.framePosition ?: 0L,
                observation?.timestamp?.nanoTime ?: 0L, frameTime, frameNanosPerSample,
                frameUncertainty, if (frameHardwareClock) 1 else 0,
                flush?.publishedNanos ?: 0L, flush?.renderId ?: 0L, flush?.rate ?: 0,
                flush?.analyzed ?: 0L, flush?.analyzing ?: 0, flush?.accepted ?: 0L, flush?.played ?: 0L,
                flush?.headObservedNanos ?: 0L, flush?.acceptedCreditNanos ?: 0L, flush?.unacceptedCreditNanos ?: 0L)
        }
    }

    private fun referenceMediaTrace(): String {
        val d = referenceMediaDiagnostics ?: return ""
        return " realReverseFrames=${d.realReverseFrames} quietReverseFrames=${d.quietReverseFrames}" +
            " eofPaddedFrames=${d.eofPaddedFrames} eofPaddingNs=${d.eofPaddingNanos} retirementTailFrames=${d.retirementTailFrames}" +
            " flushCreditEvents=${d.flushCreditEvents} flushAcceptedNs=${d.flushAcceptedNanos} flushUnacceptedNs=${d.flushUnacceptedNanos}" +
            " quietElapsedNs=${d.quietElapsedNanos} referencePhaseNs=${d.phaseNanos} referencePhaseMinNs=${d.phaseMinimumNanos}" +
            " referencePhaseFaults=${d.phaseFaults} quietBacklogFaults=${d.quietBacklogFaults}" +
            " pendingFlushEvents=${d.pendingFlushEvents} pendingFlushAcceptedNs=${d.pendingFlushAcceptedNanos} pendingFlushUnacceptedNs=${d.pendingFlushUnacceptedNanos}" +
            " renderId=${d.renderId} renderRate=${d.renderRate} analyzedSourceFrames=${d.analyzedSourceFrames}" +
            " analyzingSourceFrames=${d.analyzingSourceFrames} acceptedSourceFrames=${d.acceptedSourceFrames} playedSourceFrames=${d.playedSourceFrames}" +
            " writesInFlight=${d.writesInFlight} renderFrozen=${d.renderFrozen} headObservedNs=${d.headObservedNanos}" +
            " headTimestampFrames=${d.headTimestampFrames} headTimestampNs=${d.headTimestampNanos}" +
            " captureFirstSampleNs=${d.captureFirstSampleNanos} captureNanosPerSample=${d.captureNanosPerSample} captureHardwareClock=${d.captureHardwareClock}" +
            " lastFlushPublishedNs=${d.lastFlushPublishedNanos} lastFlushRenderId=${d.lastFlushRenderId} lastFlushRate=${d.lastFlushRate}" +
            " lastFlushAnalyzedSourceFrames=${d.lastFlushAnalyzedSourceFrames} lastFlushAnalyzingSourceFrames=${d.lastFlushAnalyzingSourceFrames}" +
            " lastFlushAcceptedSourceFrames=${d.lastFlushAcceptedSourceFrames} lastFlushPlayedSourceFrames=${d.lastFlushPlayedSourceFrames}" +
            " lastFlushHeadObservedNs=${d.lastFlushHeadObservedNanos} lastFlushAcceptedNs=${d.lastFlushAcceptedNanos} lastFlushUnacceptedNs=${d.lastFlushUnacceptedNanos}"
    }

    /** Single immutable numeric snapshot, including while the actual microphone is CLOSED.
     * Called only by the serialized processing owner; observers never enter JNI/processLock.
     */
    /** Appended scalar proof of an unchanged physical FIR origin; no observer JNI. */
    /** Diagnostic-only cohort selection: 100 ms cumulative RAW/clean deltas, NOT
     * audio admission, near-end detection, VAD or suppression control. The first
     * >=.001 RMS / >=6 dB attenuated interval after the initial 3 s protects its
     * baseline and next 30 consecutive rows (3 s of actual capture), even if later
     * rows are quiet/unattenuated. No cohort does NOT prove absence of loss.
     * First30 + latest50 + protected31 => at most111 distinct numeric rows.
     */
    private fun retainDiagnosticFrame(frame: DiagnosticFrame) {
        val previous = diagnosticRecentFrames.lastOrNull()
        if (diagnosticOnsetFrames.isEmpty() && frame.sequence > 300 &&
            previous != null && frame.sequence - previous.sequence == 10L) {
            val inputDelta = frame.inputEnergy - previous.inputEnergy
            val outputDelta = frame.outputEnergy - previous.outputEnergy
            val samples = (frame.sequence - previous.sequence) * FRAME
            if (inputDelta.isFinite() && outputDelta.isFinite() &&
                inputDelta >= samples * 0.000001 && outputDelta >= 0.0 &&
                outputDelta <= inputDelta * 0.25) {
                diagnosticOnsetFrames.add(previous.copy(trace = previous.trace +
                    " diagnosticOnsetCohort=1 diagnosticOnsetOffset=-1"))
            }
        }
        if (diagnosticOnsetFrames.isNotEmpty() && diagnosticOnsetFrames.size < 31) {
            diagnosticOnsetFrames.add(frame.copy(trace = frame.trace +
                " diagnosticOnsetCohort=1 diagnosticOnsetOffset=${diagnosticOnsetFrames.size - 1}"))
        }
        if (diagnosticInitialFrames.size < 30) diagnosticInitialFrames.add(frame)
        diagnosticRecentFrames.add(frame)
        if (diagnosticRecentFrames.size > 50) diagnosticRecentFrames.removeAt(0)
        val retained = sortedMapOf<Long, DiagnosticFrame>()
        diagnosticInitialFrames.forEach { retained[it.sequence] = it }
        diagnosticRecentFrames.forEach { retained[it.sequence] = it }
        diagnosticOnsetFrames.forEach { retained[it.sequence] = it }
        // One atomic immutable-array publication; readers never observe clear/add gaps.
        diagnosticFrames = CopyOnWriteArrayList(retained.values.map { it.trace })
    }

    private fun decisionTrace(values: DoubleArray): String =
        " decisionSchema=1 decisionCaptureBlockSamples=64 decisionFftSamples=128" +
        " decisionPresenceMask=${values[45].toInt()}" +
        " decisionProcessedBlocks=${values[46].toLong()}" +
        " decisionProcessedSamples=${values[47].toLong()}" +
        " decisionSelectedLinearBlocks=${values[48].toLong()}" +
        " decisionSelectedRawBlocks=${values[49].toLong()}" +
        " decisionUsableLinearBlocks=${values[50].toLong()}" +
        " decisionTransparentBlocks=${values[51].toLong()}" +
        " decisionNearendBlocks=${values[52].toLong()}" +
        " decisionCaptureSaturatedBlocks=${values[53].toLong()}" +
        " decisionEchoSaturatedBlocks=${values[54].toLong()}" +
        " decisionInitialStateBlocks=${values[55].toLong()}" +
        " decisionResidualUsableSaturatedBlocks=${values[56].toLong()}" +
        " decisionResidualLinearUncertaintyBlocks=${values[57].toLong()}" +
        " decisionResidualLinearSpectrumBlocks=${values[58].toLong()}" +
        " decisionResidualUnusableSaturatedBlocks=${values[59].toLong()}" +
        " decisionResidualRenderPowerBlocks=${values[60].toLong()}" +
        " decisionCaptureInputTimeEnergyQ30=${values[61].toLong()}" +
        " decisionLinearTimeEnergyQ30=${values[62].toLong()}" +
        " decisionSelectedTimeEnergyQ30=${values[63].toLong()}" +
        " decisionOutputTimeEnergyQ30=${values[64].toLong()}" +
        " decisionPreGainSpectralEnergyQ30=${values[65].toLong()}" +
        " decisionPostGainSpectralEnergyQ30=${values[66].toLong()}" +
        " decisionGainSquaredSumQ20=${values[67].toLong()}" +
        " decisionGainBinCount=${values[68].toLong()}" +
        " decisionLastGainMinQ15=${values[69].toInt()}" +
        " decisionLastGainMaxQ15=${values[70].toInt()}" +
        " decisionLastFlagMask=${values[71].toInt()}" +
        " decisionLastResidualBranch=${values[72].toInt()}" +
        " decisionInvalidNumericBlocks=${values[73].toLong()}" +
        " decisionLastObservedBlockSequence=${values[74].toLong()}" +
        " decisionEchoRemoverCalls=${values[75].toLong()}"

    private fun referenceOnlyTrace(values: DoubleArray): String =
        " referenceOnlyState=${values[21]} referenceOnlyInheritedLatencyBlocks=${values[22]} referenceOnlyInheritedFullRateLagBlocks=${values[23]}" +
        " referenceOnlyPairBeforeLatencyBlocks=${values[24]} referenceOnlyPairAfterLatencyBlocks=${values[25]} referenceOnlyPairBeforeFullRateLagBlocks=${values[26]}" +
        " referenceOnlyPairAfterFullRateLagBlocks=${values[27]} referenceOnlyTicks=${values[28]} referenceOnlyPhaseFaults=${values[29]}" +
        " referenceOnlyCanonicalMinimumLatencyBlocks=${values[30]} referenceOnlyDetectorClock=${values[31]} referenceOnlyNormalDetectorClockAtEntry=${values[32]}" +
        " referenceOnlyNormalDetectorClock=${values[33]} referenceOnlyNormalMinimumLatencyAtEntry=${values[34]} referenceOnlyNormalMinimumLatency=${values[35]}" +
        " referenceOnlyCaptureClockAtEntry=${values[36]} referenceOnlyCaptureClock=${values[37]} referenceOnlyLowRateCapacityBlocks=${values[38]}" +
        " referenceOnlyMaximumFullRateLagBlocks=${values[39]} referenceOnlyRenderClockAtEntry=${values[40]} referenceOnlyRenderClock=${values[41]}" +
        " referenceOnlyTicksAtEntry=${values[42]} referenceOnlyResumeLatencyBlocks=${values[43]} referenceOnlyResumeFullRateLagBlocks=${values[44]}"

    private fun publishNativeHistory(): DoubleArray? {
        publishReferenceMediaDiagnostics()
        val values = WebRtcAec3Native.diagnostics(handle) ?: return null
        nativeHistoryDiagnostics = NativeHistoryDiagnostics(System.nanoTime(), metricsFrames,
            diagnosticReverseFrames, values[3].toLong(), values[4].toLong(), values[5].toInt(),
            values[6].toInt(), values[7].toInt(), values[8].toInt(), values[9].toInt(),
            nativeStatus, failureCode.get(), values[10].toLong(), values[11].toLong(), values[12].toLong(),
            values[13].toInt(), values[14].toInt(), values[15].toInt(), values[16].toInt(),
            values[17].toInt(), values[18].toInt(), values[19].toInt(), values[20].toLong(),
            values[21].toInt(), values[22].toInt(), values[23].toInt(), values[24].toInt(),
            values[25].toInt(), values[26].toInt(), values[27].toInt(), values[28].toLong(),
            values[29].toLong(), values[30].toInt(), values[31].toLong(), values[32].toLong(),
            values[33].toLong(), values[34].toInt(), values[35].toInt(), values[36].toLong(),
            values[37].toLong(), values[38].toInt(), values[39].toInt(), values[40].toLong(),
            values[41].toLong(), values[42].toLong(), values[43].toInt(), values[44].toInt(),
            values[45].toInt(),
            values[46].toLong(),
            values[47].toLong(),
            values[48].toLong(),
            values[49].toLong(),
            values[50].toLong(),
            values[51].toLong(),
            values[52].toLong(),
            values[53].toLong(),
            values[54].toLong(),
            values[55].toLong(),
            values[56].toLong(),
            values[57].toLong(),
            values[58].toLong(),
            values[59].toLong(),
            values[60].toLong(),
            values[61].toLong(),
            values[62].toLong(),
            values[63].toLong(),
            values[64].toLong(),
            values[65].toLong(),
            values[66].toLong(),
            values[67].toLong(),
            values[68].toLong(),
            values[69].toInt(),
            values[70].toInt(),
            values[71].toInt(),
            values[72].toInt(),
            values[73].toLong(),
            values[74].toLong(),
            values[75].toLong())
        return values
    }

    /** Logical reference silence, NOT microphone frames and NOT another physical AudioTrack.
     * True client-empty output (including synthesis gaps with a retained producer) is checked
     * inside the native lock. No fixed-rate catch-up bursts, hardware joins, or post-resume work.
     */
    private fun serviceSuspendedIdleReference() {
        val epoch = captureId.get()
        if (closed.get() || (epoch != 0L && captureReadyEpoch.get() == epoch)) return
        try {
            synchronized(dataLock) { renders.values.toList() }.forEach(::refresh)
            synchronized(processLock) {
                val current = captureId.get()
                if (closed.get() || (current != 0L && captureReadyEpoch.get() == current)) return
                absorbFlushCredit()
                if (!delayEstimate().idle) return
                advanceIdleReferenceClock(System.nanoTime())
            }
        } catch (error: Exception) {
            val code = (error as? SyncLost)?.code ?: 1
            if (isProcessorFault(code)) fail(code) else fail(code) { !closed.get() && captureReadyEpoch.get() == 0L }
        }
    }

    /** Shared known-quiet clock; no physical head/capture equality or microphone admission gate.
     * Conserved identity (integer ns; source durations rounded down by <1 ns per component):
     * phase = quietElapsed - eofPadding - tailFrames*10ms - flushCredit - quietFrames*10ms.
     * Negative phase is already analyzed time; elapsed quiet repays it BEFORE another zero block.
     * Positive sub-frame phase survives new tracks too. No startup/retirement/cancel rebasing.
     */
    private fun accrueKnownQuiet(now: Long) {
        if (idleReferenceSince == 0L) return
        val elapsed = now - idleReferenceSince
        check(elapsed >= 0L)
        idleReferenceSince = now
        referenceQuietElapsedNanos += elapsed
        referencePhaseNanos += elapsed
    }

    private fun advanceIdleReferenceClock(now: Long) {
        absorbFlushCredit(now)
        if (idleReferenceSince == 0L) { idleReferenceSince = now; publishReferenceMediaDiagnostics(); return }
        val frames = (referencePhaseNanos / FRAME_NANOS).coerceAtLeast(0L)
        // Preserve the existing maximum native catch-up work. Unlike the old rebase, expose
        // an unserviceable interval instead of silently discarding its media-clock accounting.
        if (frames > MAX_QUIET_CATCHUP_FRAMES) {
            referenceQuietBacklogFaults++
            publishReferenceMediaDiagnostics()
            throw SyncLost(12)
        }
        repeat(frames.toInt()) { reverse(referenceSilence, ReferenceKind.Quiet) }
        publishReferenceMediaDiagnostics()
    }

    private fun energy(samples: FloatArray): Double = samples.sumOf { it.toDouble() * it }
    private fun isProcessorFault(code: Int) = code == 6 || code == 11 || code == 12
    private fun nativeOk(status: Int) {
        if (status != 0) {
            nativeStatus = status
            publishNativeHistory() // Retain the exact phase fault before the processor stops.
            throw SyncLost(11)
        }
    }
    private fun fail(code: Int = 1, originStillActive: () -> Boolean = { true }) {
        if (closed.get() || failed.get() || !originStillActive()) return
        val snapshot = FailureSnapshot(code, nativeStatus, metricsFrames, frameUncertainty,
            diagnosticReferenceUncertainty, if (diagnosticHasReference) 1 else 0,
            if (frameHardwareClock) 1 else 0, if (diagnosticRenderHardware) 1 else 0,
            synchronized(dataLock) { renders.size }, consumerFilled)
        onFailure(originStillActive) {
            if (failed.compareAndSet(false, true)) { failureCode.set(code); firstFault.compareAndSet(null, snapshot) }
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        idleReferenceTask.cancel(false)
        captureId.set(0); captureReadyEpoch.set(0)
        synchronized(dataLock) { renders.values.forEach { it.frozen = true; it.track = null }; renders.clear() }
        cleanup.execute { synchronized(processLock) { WebRtcAec3Native.destroy(handle) } }
    }
    private companion object {
        const val FRAME = 160
        const val FRAME_NANOS = 10_000_000L
        const val MAX_REFERENCE_CREDIT_NANOS = 500_000_000L
        const val MAX_QUIET_CATCHUP_FRAMES = 80L
        const val MAX_STREAMS = 8
        val idleClock = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "kompi-aec-idle-reference").apply { isDaemon = true } }
        val cleanup = Executors.newSingleThreadExecutor { task -> Thread(task, "kompi-aec-dispose").apply { isDaemon = true } }
    }
}
