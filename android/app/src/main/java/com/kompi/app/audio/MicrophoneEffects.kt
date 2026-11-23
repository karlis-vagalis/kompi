package com.kompi.app.audio

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Disabled leaves source defaults alone; SoftwareOwned obtains and explicitly disables controls. */
enum class EchoCancellationPolicy { Disabled, Optional, Required, SoftwareOwned }

/** Actual public API control observations, separate from recording-configuration descriptors.
 * policy is EchoCancellationPolicy.ordinal; phase 0 configuring, 1 ready, 2 failed, 3 released.
 * Unknown booleans=-1; unavailable!=disabled. SetStatus MIN_VALUE means not attempted.
 * failureEffect/unsafeMask bits AEC=1, NS=2, AGC=4. Stages: availability=1, create=2,
 * control=3, set=4, readback=5, later control loss=6, later unexpected enable=7, monitor=8.
 */
internal data class CaptureEffectsDiagnostics(
    val audioSessionId: Int,
    val policy: Int,
    val requestEpoch: Long = -1,
    val recordIdentity: Int = -1,
    val observedNanos: Long = 0,
    val phase: Int = 0,
    val failureEffect: Int = 0,
    val failureStage: Int = 0,
    val unsafeMask: Int = 0,
    val aecAvailable: Int = -1, val aecCreated: Int = 0, val aecHasControl: Int = -1,
    val aecInitialEnabled: Int = -1, val aecSetStatus: Int = Int.MIN_VALUE, val aecEnabled: Int = -1,
    val nsAvailable: Int = -1, val nsCreated: Int = 0, val nsHasControl: Int = -1,
    val nsInitialEnabled: Int = -1, val nsSetStatus: Int = Int.MIN_VALUE, val nsEnabled: Int = -1,
    val agcAvailable: Int = -1, val agcCreated: Int = 0, val agcHasControl: Int = -1,
    val agcInitialEnabled: Int = -1, val agcSetStatus: Int = Int.MIN_VALUE, val agcEnabled: Int = -1,
)

internal class MicrophoneEffects private constructor(
    private val sessionId: Int,
    private val policy: EchoCancellationPolicy,
    private val publish: (CaptureEffectsDiagnostics) -> Unit,
    private val onUnsafe: () -> Unit,
) : Closeable {
    private enum class Kind(val bit: Int) { Aec(1), Ns(2), Agc(4) }
    private val released = AtomicBoolean(false)
    private val unsafe = AtomicBoolean(false)
    private val stateLock = Any() // Immutable metadata only; NEVER held across an effect API/stop.
    private var state = CaptureEffectsDiagnostics(sessionId, policy.ordinal)
    private val effects = ArrayList<Pair<Kind, AudioEffect>>(3)

    private fun update(change: (CaptureEffectsDiagnostics) -> CaptureEffectsDiagnostics) = synchronized(stateLock) {
        state = change(state).copy(observedNanos = System.nanoTime())
        publish(state)
    }

    private fun record(kind: Kind, available: Int? = null, created: Int? = null, control: Int? = null,
        initial: Int? = null, status: Int? = null, enabled: Int? = null) = update { s ->
        when (kind) {
            Kind.Aec -> s.copy(aecAvailable = available ?: s.aecAvailable, aecCreated = created ?: s.aecCreated,
                aecHasControl = control ?: s.aecHasControl, aecInitialEnabled = initial ?: s.aecInitialEnabled,
                aecSetStatus = status ?: s.aecSetStatus, aecEnabled = enabled ?: s.aecEnabled)
            Kind.Ns -> s.copy(nsAvailable = available ?: s.nsAvailable, nsCreated = created ?: s.nsCreated,
                nsHasControl = control ?: s.nsHasControl, nsInitialEnabled = initial ?: s.nsInitialEnabled,
                nsSetStatus = status ?: s.nsSetStatus, nsEnabled = enabled ?: s.nsEnabled)
            Kind.Agc -> s.copy(agcAvailable = available ?: s.agcAvailable, agcCreated = created ?: s.agcCreated,
                agcHasControl = control ?: s.agcHasControl, agcInitialEnabled = initial ?: s.agcInitialEnabled,
                agcSetStatus = status ?: s.agcSetStatus, agcEnabled = enabled ?: s.agcEnabled)
        }
    }

    private fun disable(kind: Kind, available: () -> Boolean, create: () -> AudioEffect?) {
        var stage = 1
        try {
            val exists = available()
            record(kind, available = if (exists) 1 else 0)
            if (!exists) return // No advertised implementation; not a claim about hidden vendor DSP.
            stage = 2
            val effect = checkNotNull(create())
            effects += kind to effect
            record(kind, created = 1)
            stage = 3
            val control = effect.hasControl()
            val initial = effect.enabled
            record(kind, control = if (control) 1 else 0, initial = if (initial) 1 else 0)
            check(control)
            stage = 4
            val status = effect.setEnabled(false) // Obtain and request OFF even if already OFF.
            record(kind, status = status)
            check(status == AudioEffect.SUCCESS)
            stage = 5
            val enabled = effect.enabled
            val retainedControl = effect.hasControl()
            record(kind, control = if (retainedControl) 1 else 0, enabled = if (enabled) 1 else 0)
            check(retainedControl && !enabled)
        } catch (_: Exception) {
            unsafe.set(true)
            update { it.copy(phase = 2, failureEffect = kind.bit, failureStage = stage, unsafeMask = it.unsafeMask or kind.bit) }
            throw LiveAudioStartException(LiveAudioStartIssue.PreprocessingUnavailable)
        }
    }

    private fun controlChanged(kind: Kind, control: Boolean? = null, enabled: Boolean? = null) {
        val bad = control == false || enabled == true
        val notify = synchronized(stateLock) {
            if (released.get()) return
            // Latch safety BEFORE publishing any bad observation/readiness can race with it.
            val firstUnsafe = bad && unsafe.compareAndSet(false, true)
            record(kind, control = control?.let { if (it) 1 else 0 }, enabled = enabled?.let { if (it) 1 else 0 })
            if (bad) update { it.copy(phase = 2, failureEffect = kind.bit,
                failureStage = if (control == false) 6 else 7, unsafeMask = it.unsafeMask or kind.bit) }
            firstUnsafe
        }
        // No metadata/resource lock may precede the actual hardware privacy stop.
        if (notify) onUnsafe()
    }

    private fun configureSoftware() {
        disable(Kind.Aec, AcousticEchoCanceler::isAvailable) { AcousticEchoCanceler.create(sessionId) }
        disable(Kind.Ns, NoiseSuppressor::isAvailable) { NoiseSuppressor.create(sessionId) }
        disable(Kind.Agc, AutomaticGainControl::isAvailable) { AutomaticGainControl.create(sessionId) }
        for ((kind, effect) in effects) {
            try {
                effect.setControlStatusListener { _, control -> controlChanged(kind, control = control) }
                effect.setEnableStatusListener { _, enabled -> controlChanged(kind, enabled = enabled) }
                // Close the setup window between the initial OFF proof and listener install.
                val control = effect.hasControl()
                val enabled = effect.enabled
                record(kind, control = if (control) 1 else 0, enabled = if (enabled) 1 else 0)
                if (!control || enabled) controlChanged(kind, control = control, enabled = enabled)
            } catch (_: Exception) {
                unsafe.set(true)
                update { it.copy(phase = 2, failureEffect = kind.bit, failureStage = 8, unsafeMask = it.unsafeMask or kind.bit) }
                throw LiveAudioStartException(LiveAudioStartIssue.PreprocessingUnavailable)
            }
        }
        synchronized(stateLock) {
            requireSafe()
            update { it.copy(phase = 1) }
        }
    }

    /** Pure local flags only; safe immediately BEFORE startRecording, never an effect/JNI query. */
    fun requireSafe() {
        if (policy == EchoCancellationPolicy.SoftwareOwned && (unsafe.get() || released.get()))
            throw LiveAudioStartException(LiveAudioStartIssue.PreprocessingUnavailable)
    }

    override fun close() {
        synchronized(stateLock) { if (!released.compareAndSet(false, true)) return }
        effects.asReversed().forEach { (_, effect) -> runCatching { effect.release() } }
        update { it.copy(phase = 3) } // Retain actual last API observations, not invented OFF states.
    }

    companion object {
        fun attach(sessionId: Int, policy: EchoCancellationPolicy,
            publish: (CaptureEffectsDiagnostics) -> Unit = {}, onUnsafe: () -> Unit = {}): MicrophoneEffects {
            val result = MicrophoneEffects(sessionId, policy, publish, onUnsafe)
            result.update { it }
            try {
                if (policy == EchoCancellationPolicy.SoftwareOwned) result.configureSoftware()
                else if (policy != EchoCancellationPolicy.Disabled) {
                    var echo: AcousticEchoCanceler? = null
                    if (AcousticEchoCanceler.isAvailable()) {
                        echo = AcousticEchoCanceler.create(sessionId)
                        echo?.let { result.effects += Kind.Aec to it; if (!it.enabled) it.setEnabled(true) }
                    }
                    if (policy == EchoCancellationPolicy.Required && echo?.enabled != true)
                        throw LiveAudioStartException(LiveAudioStartIssue.EchoUnavailable)
                    // Preserve the existing optional hardware-AEC/headset behavior.
                    runCatching {
                        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId) else null
                    }.getOrNull()?.let { effect ->
                        result.effects += Kind.Ns to effect
                        runCatching { effect.setEnabled(true) }
                    }
                    result.update { it.copy(phase = 1) }
                } else result.update { it.copy(phase = 1) }
                return result
            } catch (error: Exception) {
                result.close()
                if (policy == EchoCancellationPolicy.SoftwareOwned)
                    throw LiveAudioStartException(LiveAudioStartIssue.PreprocessingUnavailable)
                if (policy == EchoCancellationPolicy.Required)
                    throw LiveAudioStartException(LiveAudioStartIssue.EchoUnavailable)
                return MicrophoneEffects(sessionId, policy, publish, onUnsafe).also { it.update { s -> s.copy(phase = 1) } }
            }
        }
    }
}
