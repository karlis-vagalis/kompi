package com.kompi.app

import com.kompi.app.audio.LiveAudioSession
import com.kompi.app.audio.live.LocalLiveSpeechInput
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

internal fun emitNativeAudioDiagnostic(line: String) {
    InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "$line\n") })
}

private val captureEffectFields = listOf("requestEpoch", "audioSessionId", "recordIdentity", "observedNanos", "policy", "phase",
    "failureEffect", "failureStage", "unsafeMask") + listOf("aec", "ns", "agc").flatMap { effect ->
    listOf("Available", "Created", "HasControl", "InitialEnabled", "SetStatus", "Enabled").map { effect + it }
}

private val captureClockFields = listOf("stage", "capacityFrames", "readFrames", "readCount",
    "timestampFrame", "timestampNanos", "previousTimestampFrame", "previousTimestampNanos",
    "rateAnchorFrame", "rateAnchorNanos", "beganNanos", "endedNanos", "observedNanos", "gapNanos",
    "runwayNanos", "producerUpperBefore", "producerUpperDuring", "producerUpperAfter",
    "earliestNanos", "latestNanos", "clientEmptyBeganNanos", "clientEmptyEndedNanos",
    "estimatedFirstNanos", "nanosPerSample")

/** One immutable publication per read: numeric allowlist only, never invoke hardware getters. */
private fun numericDiagnostic(target: Any, name: String, fields: List<String>): List<Pair<String, Number>>? {
    val snapshot = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) ?: return null
    return fields.map { field -> field to (snapshot.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(snapshot) as Number) }
}

internal fun printSessionRouteFailure(owner: LiveAudioSession) {
    runCatching {
        val fields = listOf("ownerEpoch", "beganNanos", "endedNanos", "trigger", "reasonMask", "expectedMode", "actualMode",
            "expectedCommunicationId", "actualCommunicationId", "actualCommunicationType", "callbackMode", "callbackCommunicationId",
            "preferredInputId", "preferredInputType", "modeRequested", "deviceRequested", "focusRetained")
        val values = numericDiagnostic(owner, "routeFailureDiagnostics", fields) ?: return@runCatching
        emitNativeAudioDiagnostic("REAL_AUDIO_ROUTE_FAILURE retainedOwnerEpoch=${owner.epoch} " +
            values.joinToString(" ") { (name, number) -> "$name=$number" })
    }.onFailure { runCatching { emitNativeAudioDiagnostic("REAL_AUDIO_ROUTE_FAILURE retainedOwnerEpoch=${owner.epoch} diagnosticCode=1 snapshotAvailable=0") } }
}

/** Numeric AEC evidence only: no PCM, text, hardware read, JNI call, model lock or timing sleep. */
internal fun printSoftwareEchoDiagnostics(owner: LiveAudioSession? = null) {
    fun emit(line: String) = emitNativeAudioDiagnostic(line)
    try {
        val type = LiveAudioSession::class.java
        val current = owner ?: run {
            val registry = type.getDeclaredField("registryLock").apply { isAccessible = true }.get(null)
            synchronized(checkNotNull(registry)) { type.getDeclaredField("active").apply { isAccessible = true }.get(null) }
        } ?: return
        val session = current as LiveAudioSession
        printSessionRouteFailure(session) // Also retain failures before APM creation.
        val dsp = type.getDeclaredField("echoProcessor").apply { isAccessible = true }.get(current) ?: return
        fun field(name: String) = dsp.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(dsp)
        // CopyOnWriteArrayList.toArray snapshots its immutable backing array. No APM/model join,
        // no iterator over a mutating ArrayDeque, and emission occurs outside ALL audio locks.
        val snapshots = (field("diagnosticFrames") as CopyOnWriteArrayList<*>).toTypedArray()
        val first = (field("firstFault") as AtomicReference<*>).get()
        val names = listOf("code", "nativeStatus", "captureFrames", "captureUncertaintyNs", "referenceUncertaintyNs",
            "hasReference", "captureHardware", "renderHardware", "referenceCount", "consumerFilled")
        val fault = if (first == null) "code=0" else names.joinToString(" ") { name ->
            val value = first.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(first) as Number
            "$name=$value"
        }
        val ownerEpoch = session.epoch
        emit("REAL_AUDIO_AEC firstFault $fault failureCode=${(field("failureCode") as AtomicInteger).get()} nativeStatus=${field("nativeStatus")} ownerEpoch=$ownerEpoch")
        snapshots.forEach { emit("REAL_AUDIO_AEC sampledFrame $it ownerEpoch=$ownerEpoch") }
        emit("REAL_AUDIO_AEC lifetime captureFrames=${field("metricsFrames")} inputEnergy=${field("diagnosticTotalInputEnergy")} outputEnergy=${field("diagnosticTotalOutputEnergy")} referenceEnergy=${field("diagnosticTotalReferenceEnergy")} ownerEpoch=$ownerEpoch")
    } catch (error: Exception) {
        emit("REAL_AUDIO_AEC diagnosticsUnavailable=${error.javaClass.simpleName}")
    }
}

/** Emit outside capture callbacks and all audio/model locks. Diagnostic failure never replaces a
 * test/cleanup failure. Each capture emits at most start + two cumulative + end + unavailable,
 * two effects snapshots, one snapshot per capture/input-route failure category, and at most
 * one pending plus one final capture-clock record. Missing/pending clocks never mean healthy. */
internal fun printNativeAcquisitionDiagnostics(captures: Iterable<NativeAcquisitionDiagnostics>) {
    captures.forEach { capture -> runCatching { capture.lines().forEach(::emitNativeAudioDiagnostic) } }
}

/** The producer identity is deliberately distinct from the retained duplex session identity. */
internal data class NativeAcquisitionOwner internal constructor(
    val duplexEpoch: Long,
    val captureIndex: Int,
    val requestEpoch: Long,
    val audioSessionId: Int,
    val recordIdentity: Int,
    val audioSource: Int,
)

/** Descriptor masks describe advertised effect-chain presence, NOT enabled state or acoustic
 * efficacy: AEC=1, NS=2, AGC=4, other=8; -1 is unknown. Metadata is producer-published, not queried. */
internal data class NativeCaptureConfiguration internal constructor(
    val requestEpoch: Long, val audioSessionId: Int, val recordIdentity: Int,
    val configuredSource: Int, val requestedSource: Int, val recordSource: Int,
    val unprocessedSupport: Int, val observedNanos: Long, val configurationAvailable: Int,
    val phase: Int, val clientSource: Int, val deviceSource: Int, val clientSilenced: Int,
    val clientEffectsMask: Int, val deviceEffectsMask: Int,
    val clientEffectCount: Int, val deviceEffectCount: Int, val privacySensitive: Int,
    val requiredInputDeviceId: Int, val requiredInputDeviceType: Int,
    val actualInputDeviceId: Int, val actualInputDeviceType: Int,
) {
    fun matches(owner: NativeAcquisitionOwner): Boolean = requestEpoch == owner.requestEpoch &&
        audioSessionId == owner.audioSessionId && recordIdentity == owner.recordIdentity
}

/** Actual producer-observed control state, NOT configuration descriptor presence. Unknown=-1;
 * unattempted SetStatus=Int.MIN_VALUE. Available=0 does NOT establish Enabled=0. A disabled
 * control assertion must separately check policy/phase, identity, creation/control/set/readback. */
internal data class NativeCaptureEffects internal constructor(
    val requestEpoch: Long, val audioSessionId: Int, val recordIdentity: Int, val observedNanos: Long,
    val policy: Int, val phase: Int, val failureEffect: Int, val failureStage: Int, val unsafeMask: Int,
    val aecAvailable: Int, val aecCreated: Int, val aecHasControl: Int,
    val aecInitialEnabled: Int, val aecSetStatus: Int, val aecEnabled: Int,
    val nsAvailable: Int, val nsCreated: Int, val nsHasControl: Int,
    val nsInitialEnabled: Int, val nsSetStatus: Int, val nsEnabled: Int,
    val agcAvailable: Int, val agcCreated: Int, val agcHasControl: Int,
    val agcInitialEnabled: Int, val agcSetStatus: Int, val agcEnabled: Int,
) {
    fun matches(owner: NativeAcquisitionOwner): Boolean = requestEpoch == owner.requestEpoch &&
        audioSessionId == owner.audioSessionId && recordIdentity == owner.recordIdentity
}

/** Stable native history copied from ONE immutable producer publication; never calls JNI/stats.
 * Session epoch and processor identity are separate from the native capture lease ID. */
internal data class NativeHistorySnapshot internal constructor(
    val ownerEpoch: Long, val processorIdentity: Int,
    val captureFrames: Long, val reverseFrames: Long, val suspendedReverseFrames: Long,
    val suspendedHistoryFaults: Long, val suspendedHistoryEventMask: Int, val statsPresentMask: Int,
    val suspendedTransitionLatencyBlocks: Int, val suspendedAdvancedBlocks: Int,
    val suspendedRecoveryLatencyBlocks: Int, val nativeStatus: Int, val failureCode: Int,
    val observedNanos: Long,
)

internal fun readNativeHistorySnapshot(owner: LiveAudioSession): NativeHistorySnapshot {
    try {
        fun field(value: Any, name: String) = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)
        val processor = field(owner, "echoProcessor")
            ?: throw AssertionError("Native history unavailable: softwareProcessor=0 ownerEpoch=${owner.epoch}")
        val snapshot = field(processor, "nativeHistoryDiagnostics")
            ?: throw AssertionError("Native history unavailable: observedMetadata=0 ownerEpoch=${owner.epoch}")
        fun number(name: String) = field(snapshot, name) as Number
        return NativeHistorySnapshot(owner.epoch, System.identityHashCode(processor),
            number("captureFrames").toLong(), number("reverseFrames").toLong(), number("suspendedReverseFrames").toLong(),
            number("suspendedHistoryFaults").toLong(), number("suspendedHistoryEventMask").toInt(), number("statsPresentMask").toInt(),
            number("suspendedTransitionLatencyBlocks").toInt(), number("suspendedAdvancedBlocks").toInt(),
            number("suspendedRecoveryLatencyBlocks").toInt(), number("nativeStatus").toInt(), number("failureCode").toInt(),
            number("observedNanos").toLong())
    } catch (error: AssertionError) { throw error }
    catch (_: Exception) { throw AssertionError("Native history unavailable: numericMetadataReadable=0 ownerEpoch=${owner.epoch}") }
}

/** Immutable content-free checkpoint of a VERIFIED current AudioRecord producer. Construction
 * requires a live exact recorder/lease binding; historical ended snapshots are printer-only. */
internal class NativeAcquisitionSnapshot internal constructor(
    internal val origin: NativeAcquisitionDiagnostics,
    val owner: NativeAcquisitionOwner,
    val recordingStartedNanos: Long,
    val firstReadReturnNanos: Long,
    val lastReadReturnNanos: Long,
    val successfulReads: Long,
    val successfulReadSamples: Long,
    val inputSumSquares: Double,
    val lifetimeInputPeak: Int,
    val inputNonzeroSamples: Long,
    val configuration: NativeCaptureConfiguration?,
    val effects: NativeCaptureEffects?,
) {
    fun deltaSince(before: NativeAcquisitionSnapshot): NativeAcquisitionDelta {
        if (origin !== before.origin || owner != before.owner || successfulReads < before.successfulReads ||
            successfulReadSamples <= before.successfulReadSamples || inputSumSquares < before.inputSumSquares ||
            inputNonzeroSamples < before.inputNonzeroSamples || lastReadReturnNanos < before.lastReadReturnNanos) {
            throw AssertionError("Direct acquisition delta unavailable: sameOwnerAndPositiveMonotonicProgress=0 owner=$owner beforeOwner=${before.owner}")
        }
        return NativeAcquisitionDelta(owner, successfulReads - before.successfulReads,
            successfulReadSamples - before.successfulReadSamples, inputSumSquares - before.inputSumSquares,
            inputNonzeroSamples - before.inputNonzeroSamples, before.lastReadReturnNanos, lastReadReturnNanos)
    }
}

/** Exact gap-free RAW PCM16 energy window, before conversion/FIFO/APM. A lifetime peak is NOT
 * a window measurement. Zero RMS here means real zero-valued successful reads, never missing data. */
internal class NativeAcquisitionDelta internal constructor(
    val owner: NativeAcquisitionOwner,
    val successfulReads: Long,
    val successfulReadSamples: Long,
    val inputSumSquares: Double,
    val inputNonzeroSamples: Long,
    val fromReadReturnNanos: Long,
    val toReadReturnNanos: Long,
) {
    val normalizedRms: Double get() = sqrt(inputSumSquares / successfulReadSamples) / 32768.0
}

/** Retains one exact input-owned capture, not a later .active or a global recorder. Only reads
 * app-owned fields, one volatile immutable acquisition snapshot and atomic references. No
 * AudioRecord API/JNI, PCM arrays, hardware/model locks, threads, sleeps or ownership mutations. */
internal class NativeAcquisitionDiagnostics(
    private val input: LocalLiveSpeechInput,
    private val owner: LiveAudioSession?,
    private val captureIndex: Int,
) {
    private fun field(value: Any, name: String) = value.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private data class Retained(val microphone: Any, val requestEpoch: Long, val leaseMatch: Int)
    private data class Key(val epoch: Long, val session: Int, val identity: Int)
    private data class Reading(val key: Key, val source: Int, val phase: Int, val started: Long,
        val firstRead: Long, val lastRead: Long, val reads: Long, val samples: Long,
        val squares: Double, val peak: Int, val nonzero: Long)
    private val capture = AtomicReference<Retained?>()
    private val recordIdentity = AtomicReference<Int?>()
    private val start = AtomicReference<Reading?>()
    private val diagnosticCode = AtomicInteger()
    private val printedStart = AtomicBoolean()
    private val printedCumulative = AtomicInteger()
    private val printedEnd = AtomicBoolean()
    private val printedUnavailable = AtomicBoolean()
    private val printedCaptureFailure = AtomicBoolean()
    private val printedClockPending = AtomicBoolean()
    private val printedClockFailure = AtomicBoolean()
    private val printedInputRouteFailure = AtomicBoolean()
    private val printedEffects = AtomicInteger()
    private val ownerEpoch = owner?.epoch ?: -1L

    /** Called after start and at the first real callback only. No printing or work precedes any
     * hardware privacy-stop. Once the capture/start/record identity are retained this is a no-op. */
    fun observeStart() {
        if (diagnosticCode.get() != 0 || (start.get() != null && recordIdentity.get() != null)) return
        runCatching {
            var retained = capture.get()
            if (retained == null) {
                val active = field(input, "active").get(input) ?: return@runCatching
                val microphone = field(active, "microphone").get(active) ?: return@runCatching
                val epoch = field(microphone, "requestEpoch").getLong(microphone)
                val actualOwner = (field(active, "audioSession").get(active) as AtomicReference<*>).get()
                val leaseMatch = if (owner == null) -1 else if (actualOwner === owner) 1 else 0
                capture.compareAndSet(null, Retained(microphone, epoch, leaseMatch))
                retained = capture.get()
            }
            val exact = retained ?: return@runCatching
            // Object identity only, NOT AudioRecord.audioSessionId/getTimestamp/read or JNI.
            field(exact.microphone, "recorder").get(exact.microphone)?.let {
                recordIdentity.compareAndSet(null, System.identityHashCode(it))
            }
            read(exact)?.let { start.compareAndSet(null, it) }
        }.onFailure { diagnosticCode.compareAndSet(0, 1) }
    }

    /** Ownership survives stopped/cleared recorders. IDs come only from this exact retained
     * microphone's immutable adoption/read metadata, not source categories or global .active. */
    fun ownedRecordingSessionIds(): Set<Int> {
        observeStart()
        try {
            val retained = capture.get()
            if (retained == null) {
                if (diagnosticCode.get() != 0) throw AssertionError("Owned recording identities unavailable: retainedCaptureReadable=0")
                return emptySet() // No microphone/adopted recorder has existed for this input.
            }
            return listOfNotNull(start.get(), read(retained)).map { reading ->
                if (reading.key.epoch != retained.requestEpoch || reading.key.session <= 0 ||
                    recordIdentity.get()?.let { it != reading.key.identity } == true) {
                    throw AssertionError("Owned recording identities unavailable: exactRetainedProducer=0 captureIndex=$captureIndex ownerEpoch=$ownerEpoch")
                }
                reading.key.session
            }.toSet()
        } catch (error: AssertionError) { throw error }
        catch (_: Exception) { throw AssertionError("Owned recording identities unavailable: numericMetadataReadable=0 captureIndex=$captureIndex ownerEpoch=$ownerEpoch") }
    }

    /** Strict API for actual-delivery assertions. No polling, waits, calibration, hardware/JNI
     * query or fallback to another capture. Failure is an assertion, never an empty/zero window. */
    fun checkpoint(): NativeAcquisitionSnapshot {
        observeStart()
        try {
            val retained = capture.get() ?: throw AssertionError("Direct acquisition checkpoint unavailable: retainedCapture=0")
            val active = field(input, "active").get(input)
                ?: throw AssertionError("Direct acquisition checkpoint unavailable: activeInput=0")
            val microphone = field(active, "microphone").get(active)
            val actualOwner = (field(active, "audioSession").get(active) as AtomicReference<*>).get()
            val recorder = field(retained.microphone, "recorder").get(retained.microphone)
                ?: throw AssertionError("Direct acquisition checkpoint unavailable: activeRecorder=0")
            val reading = read(retained) ?: throw AssertionError("Direct acquisition checkpoint unavailable: rawSnapshot=0")
            val identity = System.identityHashCode(recorder)
            if (microphone !== retained.microphone || owner == null || actualOwner !== owner || retained.leaseMatch != 1 ||
                reading.key.epoch != retained.requestEpoch || reading.key.identity != identity ||
                recordIdentity.get() != identity || reading.phase != 2 || reading.samples <= 0 ||
                field(retained.microphone, "recorder").get(retained.microphone) !== recorder) {
                throw AssertionError("Direct acquisition checkpoint unavailable: exactCurrentProducer=0 captureIndex=$captureIndex ownerEpoch=$ownerEpoch")
            }
            val key = NativeAcquisitionOwner(ownerEpoch, captureIndex, reading.key.epoch,
                reading.key.session, reading.key.identity, reading.source)
            return NativeAcquisitionSnapshot(this, key, reading.started, reading.firstRead, reading.lastRead,
                reading.reads, reading.samples, reading.squares, reading.peak, reading.nonzero, configuration(retained), effects(retained))
        } catch (error: AssertionError) { throw error }
        catch (_: Exception) { throw AssertionError("Direct acquisition checkpoint unavailable: numericMetadataReadable=0 captureIndex=$captureIndex ownerEpoch=$ownerEpoch") }
    }

    private fun read(retained: Retained): Reading? {
        // Exactly ONE volatile publication is fetched; all fields below belong to that same
        // immutable object. This remains readable after recorder/resources/.active are cleared.
        val snapshot = field(retained.microphone, "acquisitionDiagnostics").get(retained.microphone) ?: return null
        fun number(name: String) = field(snapshot, name).get(snapshot) as Number
        return Reading(Key(number("requestEpoch").toLong(), number("audioSessionId").toInt(), number("recordIdentity").toInt()),
            number("audioSource").toInt(), number("phase").toInt(), number("recordingStartedNanos").toLong(),
            number("firstReadNanos").toLong(), number("lastReadNanos").toLong(), number("successfulReads").toLong(),
            number("successfulReadSamples").toLong(), number("inputSumSquares").toDouble(),
            number("inputPeak").toInt(), number("inputNonzeroSamples").toLong()).also {
                require(it.squares.isFinite() && it.squares >= 0 && it.samples >= 0 && it.reads >= 0 &&
                    it.nonzero in 0L..it.samples && it.peak in 0..32768 && it.phase in 0..3)
            }
    }

    private fun effects(retained: Retained): NativeCaptureEffects? = runCatching {
        val values = numericDiagnostic(retained.microphone, "captureEffectsDiagnostics", captureEffectFields)?.toMap() ?: return@runCatching null
        fun int(name: String) = values.getValue(name).toInt()
        fun long(name: String) = values.getValue(name).toLong()
        NativeCaptureEffects(long("requestEpoch"), int("audioSessionId"), int("recordIdentity"), long("observedNanos"),
            int("policy"), int("phase"), int("failureEffect"), int("failureStage"), int("unsafeMask"),
            int("aecAvailable"), int("aecCreated"), int("aecHasControl"), int("aecInitialEnabled"), int("aecSetStatus"), int("aecEnabled"),
            int("nsAvailable"), int("nsCreated"), int("nsHasControl"), int("nsInitialEnabled"), int("nsSetStatus"), int("nsEnabled"),
            int("agcAvailable"), int("agcCreated"), int("agcHasControl"), int("agcInitialEnabled"), int("agcSetStatus"), int("agcEnabled"))
    }.getOrNull()

    private fun configuration(retained: Retained): NativeCaptureConfiguration? = runCatching {
        val snapshot = field(retained.microphone, "captureConfigurationDiagnostics").get(retained.microphone) ?: return@runCatching null
        fun number(name: String) = field(snapshot, name).get(snapshot) as Number
        NativeCaptureConfiguration(number("requestEpoch").toLong(), number("audioSessionId").toInt(), number("recordIdentity").toInt(),
            number("configuredSource").toInt(), number("requestedSource").toInt(), number("recordSource").toInt(),
            number("unprocessedSupport").toInt(), number("observedNanos").toLong(), number("configurationAvailable").toInt(),
            number("phase").toInt(), number("clientSource").toInt(), number("deviceSource").toInt(), number("clientSilenced").toInt(),
            number("clientEffectsMask").toInt(), number("deviceEffectsMask").toInt(),
            number("clientEffectCount").toInt(), number("deviceEffectCount").toInt(),
            runCatching { number("privacySensitive").toInt() }.getOrDefault(-1),
            runCatching { number("requiredInputDeviceId").toInt() }.getOrDefault(-1),
            runCatching { number("requiredInputDeviceType").toInt() }.getOrDefault(-1),
            runCatching { number("actualInputDeviceId").toInt() }.getOrDefault(-1),
            runCatching { number("actualInputDeviceType").toInt() }.getOrDefault(-1))
    }.getOrNull()

    private fun rmsMicros(squares: Double, samples: Long): Long =
        if (samples > 0) (sqrt(squares / samples) / 32768.0 * 1_000_000).toLong() else -1L

    private fun line(stage: Int, value: Reading, baseline: Reading?, retained: Retained): String {
        val epochMatch = if (value.key.epoch == retained.requestEpoch) 1 else 0
        val recordMatch = recordIdentity.get()?.let { if (it == value.key.identity) 1 else 0 } ?: -1
        val pairMatch = if (baseline == null) -1 else if (value.key == baseline.key) 1 else 0
        val monotonic = baseline != null && value.samples >= baseline.samples && value.reads >= baseline.reads &&
            value.squares >= baseline.squares && value.nonzero >= baseline.nonzero
        val valid = epochMatch == 1 && recordMatch == 1 && retained.leaseMatch == 1 && pairMatch == 1 && monotonic
        val deltaSamples = if (valid) value.samples - requireNotNull(baseline).samples else -1L
        val deltaSquares = if (valid) value.squares - requireNotNull(baseline).squares else -1.0
        val metrics = arrayOf<Pair<String, Number>>(
            "captureIndex" to captureIndex, "ownerEpoch" to ownerEpoch, "stage" to stage,
            "retainedRequestEpoch" to retained.requestEpoch, "leaseMatch" to retained.leaseMatch,
            "requestEpoch" to value.key.epoch, "audioSessionId" to value.key.session,
            "recordIdentity" to value.key.identity, "expectedRecordIdentity" to (recordIdentity.get() ?: -1),
            "epochMatch" to epochMatch, "recordMatch" to recordMatch, "pairMatch" to pairMatch,
            "audioSource" to value.source, "phase" to value.phase,
            "recordingStartedNanos" to value.started, "firstReadReturnNanos" to value.firstRead,
            "lastReadReturnNanos" to value.lastRead, "successfulReads" to value.reads,
            "successfulReadSamples" to value.samples, "inputSumSquares" to value.squares,
            "inputPeak" to value.peak, "inputNonzeroSamples" to value.nonzero,
            "totalRmsMicros" to rmsMicros(value.squares, value.samples),
            "deltaValid" to if (valid) 1 else 0, "deltaSamples" to deltaSamples,
            "deltaSumSquares" to deltaSquares,
            "deltaNonzeroSamples" to if (valid) value.nonzero - requireNotNull(baseline).nonzero else -1L,
            "deltaRmsMicros" to if (valid && deltaSamples > 0) rmsMicros(deltaSquares, deltaSamples) else -1L,
            "noProgress" to if (!valid) -1 else if (deltaSamples == 0L) 1 else 0,
            "diagnosticCode" to diagnosticCode.get(),
        )
        val config = if (stage == 0) null else configuration(retained)
        val configMatch = if (config == null) -1 else if (config.requestEpoch == value.key.epoch &&
            config.audioSessionId == value.key.session && config.recordIdentity == value.key.identity) 1 else 0
        val configMetrics = arrayOf<Pair<String, Number>>(
            "configurationReadable" to if (config != null) 1 else 0,
            "configurationOwnerMatch" to configMatch,
            "configurationAvailable" to (config?.configurationAvailable ?: -1),
            "configurationObservedNanos" to (config?.observedNanos ?: -1L),
            "configurationPhase" to (config?.phase ?: -1),
            "configuredSource" to (config?.configuredSource ?: -1), "requestedSource" to (config?.requestedSource ?: -1),
            "recordSource" to (config?.recordSource ?: -1), "unprocessedSupport" to (config?.unprocessedSupport ?: -1),
            "privacySensitive" to (config?.privacySensitive ?: -1),
            "clientSource" to (config?.clientSource ?: -1), "deviceSource" to (config?.deviceSource ?: -1),
            "clientSilenced" to (config?.clientSilenced ?: -1),
            "clientEffectsMask" to (config?.clientEffectsMask ?: -1), "deviceEffectsMask" to (config?.deviceEffectsMask ?: -1),
            "clientEffectCount" to (config?.clientEffectCount ?: -1), "deviceEffectCount" to (config?.deviceEffectCount ?: -1),
            "requiredInputDeviceId" to (config?.requiredInputDeviceId ?: -1), "requiredInputDeviceType" to (config?.requiredInputDeviceType ?: -1),
            "actualInputDeviceId" to (config?.actualInputDeviceId ?: -1), "actualInputDeviceType" to (config?.actualInputDeviceType ?: -1),
        )
        return "REAL_AUDIO_ACQUISITION " + (metrics + configMetrics).joinToString(" ") { (name, number) -> "$name=$number" }
    }

    private fun metadataLine(retained: Retained, name: String, prefix: String, fields: List<String>, printed: AtomicBoolean? = null): String? {
        if (printed?.get() == true) return null
        return runCatching {
            val values = numericDiagnostic(retained.microphone, name, fields) ?: return@runCatching null
            if (printed != null && !printed.compareAndSet(false, true)) return@runCatching null
            val metadata = values.toMap()
            val epochMatch = if (metadata.getValue("requestEpoch").toLong() == retained.requestEpoch) 1 else 0
            val recordMatch = recordIdentity.get()?.let { if (metadata.getValue("recordIdentity").toInt() == it) 1 else 0 } ?: -1
            val sessionMatch = start.get()?.let { if (metadata.getValue("audioSessionId").toInt() == it.key.session) 1 else 0 } ?: -1
            "$prefix captureIndex=$captureIndex ownerEpoch=$ownerEpoch retainedRequestEpoch=${retained.requestEpoch} " +
                "epochMatch=$epochMatch recordMatch=$recordMatch sessionMatch=$sessionMatch snapshotAvailable=1 " +
                values.joinToString(" ") { (field, number) -> "$field=$number" }
        }.getOrElse {
            if (printed == null || printed.compareAndSet(false, true)) "$prefix captureIndex=$captureIndex ownerEpoch=$ownerEpoch diagnosticCode=1 snapshotAvailable=0" else null
        }
    }

    /** The clock publication has no embedded IDs. Join ONLY this retained microphone's first
     * failure and exact producer key; never attribute it using a later input/global recorder.
     * The clock-failure path publishes its snapshot before acquisition phase 3. Until that
     * terminal phase, an absent clock is pending, never a final absence or healthy clock. */
    private fun clockFailureLine(retained: Retained, current: Reading?): String? {
        if (printedClockFailure.get()) return null
        return runCatching {
            // Acquire the clock publication first: captureFailed publishes the corresponding
            // failure BEFORE this volatile clock, so a present clock cannot outrun its join.
            val clock = numericDiagnostic(retained.microphone, "captureClockFailure", captureClockFields)
            val failure = numericDiagnostic(retained.microphone, "captureFailureDiagnostics",
                listOf("requestEpoch", "audioSessionId", "recordIdentity", "observedNanos", "code"))?.toMap()
            val pending = clock == null && current?.phase != 3
            val printed = if (pending) printedClockPending else printedClockFailure
            if (printed.get()) return@runCatching null
            val expected = start.get()?.key ?: current?.key
            val epochMatch = failure?.let { if (it.getValue("requestEpoch").toLong() == retained.requestEpoch) 1 else 0 } ?: -1
            val recordMatch = failure?.let { values -> recordIdentity.get()?.let {
                if (values.getValue("recordIdentity").toInt() == it) 1 else 0
            } } ?: -1
            val sessionMatch = failure?.let { values -> expected?.let {
                if (values.getValue("audioSessionId").toInt() == it.session) 1 else 0
            } } ?: -1
            val metrics = listOf<Pair<String, Number>>(
                "captureIndex" to captureIndex, "ownerEpoch" to ownerEpoch,
                "retainedRequestEpoch" to retained.requestEpoch, "leaseMatch" to retained.leaseMatch,
                "expectedAudioSessionId" to (expected?.session ?: -1),
                "expectedRecordIdentity" to (recordIdentity.get() ?: -1),
                "captureFailureAvailable" to if (failure != null) 1 else 0,
                "captureFailureCode" to (failure?.getValue("code") ?: -1),
                "captureFailureObservedNanos" to (failure?.getValue("observedNanos") ?: -1L),
                "epochMatch" to epochMatch, "recordMatch" to recordMatch, "sessionMatch" to sessionMatch,
                "failureJoinValid" to if (epochMatch == 1 && recordMatch == 1 && sessionMatch == 1 &&
                    failure?.getValue("code")?.toInt() == 2 && retained.leaseMatch == 1) 1 else 0,
                "readerEnded" to if (current?.phase == 3) 1 else 0,
                "snapshotAvailable" to if (clock != null) 1 else 0,
                "pending" to if (pending) 1 else 0, "diagnosticCode" to 0,
            )
            val line = "REAL_AUDIO_CAPTURE_CLOCK_FAILURE " + (metrics + clock.orEmpty()).joinToString(" ") { (name, number) -> "$name=$number" }
            if (printed.compareAndSet(false, true)) line else null
        }.getOrElse {
            if (printedClockFailure.compareAndSet(false, true))
                "REAL_AUDIO_CAPTURE_CLOCK_FAILURE captureIndex=$captureIndex ownerEpoch=$ownerEpoch retainedRequestEpoch=${retained.requestEpoch} diagnosticCode=1 snapshotAvailable=0 pending=-1"
            else null
        }
    }

    /** Stage 0=start observed, 1=cumulative printer boundary, 2=actual reader-ended phase 3.
     * Ended history is never a readiness grant. Peak is lifetime-only, not subtractable. */
    fun lines(): List<String> {
        observeStart()
        return runCatching {
            val retained = capture.get()
            val current = retained?.let(::read)
            val first = start.get()
            buildList {
                if (retained != null) {
                    metadataLine(retained, "captureFailureDiagnostics", "REAL_AUDIO_CAPTURE_FAILURE",
                        listOf("requestEpoch", "audioSessionId", "recordIdentity", "observedNanos", "code"), printedCaptureFailure)?.let(::add)
                    clockFailureLine(retained, current)?.let(::add)
                    metadataLine(retained, "captureInputRouteFailureDiagnostics", "REAL_AUDIO_INPUT_ROUTE_FAILURE",
                        listOf("requestEpoch", "audioSessionId", "recordIdentity", "observedNanos", "audioSource",
                            "requiredInputDeviceId", "requiredInputDeviceType", "actualInputDeviceId", "actualInputDeviceType"), printedInputRouteFailure)?.let(::add)
                    // Descriptor presence is not enablement. These are actual app-owned API
                    // observations, with unknown/unattempted sentinels preserved verbatim.
                    if (printedEffects.get() < 2) {
                        metadataLine(retained, "captureEffectsDiagnostics", "REAL_AUDIO_CAPTURE_EFFECTS", captureEffectFields)?.let {
                            if (printedEffects.getAndIncrement() < 2) add(it)
                        }
                    }
                }
                if (retained != null && first != null && printedStart.compareAndSet(false, true)) add(line(0, first, first, retained))
                if (retained != null && current != null) {
                    if (printedCumulative.getAndIncrement() < 2) add(line(1, current, first, retained))
                    if (current.phase == 3 && printedEnd.compareAndSet(false, true)) add(line(2, current, first, retained))
                } else if (printedUnavailable.compareAndSet(false, true)) {
                    add("REAL_AUDIO_ACQUISITION captureIndex=$captureIndex ownerEpoch=$ownerEpoch stage=-1 retained=${if (retained != null) 1 else 0} diagnosticCode=${diagnosticCode.get()} snapshotAvailable=0")
                }
            }
        }.getOrElse {
            diagnosticCode.compareAndSet(0, 2)
            if (printedUnavailable.compareAndSet(false, true)) listOf("REAL_AUDIO_ACQUISITION captureIndex=$captureIndex ownerEpoch=$ownerEpoch stage=-1 diagnosticCode=2 snapshotAvailable=0") else emptyList()
        }
    }
}
