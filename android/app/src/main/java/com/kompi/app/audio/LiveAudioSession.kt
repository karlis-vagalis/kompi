package com.kompi.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.kompi.app.live.LiveLocalStopReason
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicReference

/** A single duplex focus/route owner, retained across listening, replies AND microphone mute.
 * Capture wrappers and playback borrow an epoch-bound binding; they never release this focus.
 */
class LiveAudioSession private constructor(
    private val appContext: Context,
    private val manager: AudioManager,
    private val onLocalStop: (LiveLocalStopReason) -> Unit,
) : Closeable {
    private enum class State { Opening, Running, Revoked, Closed }
    val epoch: Long = epochs.incrementAndGet()
    private val lock = Any()
    private val osReleaseLock = Any() // Never acquired by fenceHardware or any hardware stop hook.
    private var state = State.Opening
    private var focus: AudioFocusLease? = null
    private var modeOwned = false
    private var deviceOwned = false
    private var osReleased = false
    private var expectedDeviceId: Int? = null
    private var isolatedOutput = false
    private var callbacksRegistered = false
    private enum class CommunicationPhase { Preparing, Effective, Stopping, Relinquishing, Quiescent, Acquiring }
    private var communicationPhase = CommunicationPhase.Preparing
    private var communicationEpoch = 0L
    private var idleDeviceId: Int? = null
    private var pinnedDevice: AudioDeviceInfo? = null
    private val modeAcknowledgement = Object()
    @Volatile private var lastPhysicalMode = -1
    private var modeAcknowledgementSequence = 0L
    private var physicalClaimSequence = -1L
    private val controlThread = AtomicReference<Thread?>()
    private val control = Executors.newSingleThreadExecutor { task ->
        Thread({ controlThread.set(Thread.currentThread()); task.run() }, "kompi-communication-lease").apply { isDaemon = true }
    }
    private var releaseQueued = false
    sealed interface MicPermission {
        val captureGeneration: Long
        data class Allowed(override val captureGeneration: Long) : MicPermission
        data class UserMuted(override val captureGeneration: Long) : MicPermission
    }
    private var micPermission: MicPermission = MicPermission.Allowed(0)
    internal val currentMicPermission: MicPermission get() = synchronized(lock) { micPermission }
    private class DemandRecord(val captureGeneration: Long? = null, var fenced: Boolean = false, var granted: Boolean = false)
    private val captureDemands = LinkedHashMap<Any, DemandRecord>()
    private val outputDemands = LinkedHashMap<Any, DemandRecord>()
    private var readyCueDemand: OutputDemand? = null
    private enum class RouteCheckTrigger(val code: Int) {
        Explicit(1), DeviceRemoved(2), DeviceAdded(3), ModeChanged(4), CommunicationDeviceChanged(5)
    }
    /** FIRST failed observation only. Requests are not proof of current Android mode ownership.
     * reasonMask: mode=1, communication device=2, getter failure=4, legacy isolation=8.
     * The bracket records sequential API observations, not an atomic physical-route claim.
     */
    private data class RouteFailureDiagnostics(val ownerEpoch: Long, val beganNanos: Long,
        val endedNanos: Long, val trigger: Int, val reasonMask: Int, val expectedMode: Int,
        val actualMode: Int, val expectedCommunicationId: Int, val actualCommunicationId: Int,
        val actualCommunicationType: Int, val callbackMode: Int, val callbackCommunicationId: Int,
        val preferredInputId: Int, val preferredInputType: Int, val modeRequested: Int,
        val deviceRequested: Int, val focusRetained: Int)
    @Volatile private var routeFailureDiagnostics: RouteFailureDiagnostics? = null
    private val stops = LinkedHashMap<Any, (LiveLocalStopReason) -> Unit>()
    private var readyCueClaimed = false
    private val renderAdmissions = java.util.ArrayDeque<RenderReservation>()
    private var readyCueReservation: RenderReservation? = null
    var echoPolicy: EchoCancellationPolicy = EchoCancellationPolicy.Required
        private set
    var preferredInputDevice: AudioDeviceInfo? = null
        private set

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { checkRouteOrRevoke(RouteCheckTrigger.DeviceRemoved) }
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { checkRouteOrRevoke(RouteCheckTrigger.DeviceAdded) }
    }
    private val modeListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        AudioManager.OnModeChangedListener { mode ->
            synchronized(modeAcknowledgement) { lastPhysicalMode = mode; modeAcknowledgementSequence++; modeAcknowledgement.notifyAll() }
            val unsafeReceipt = synchronized(lock) {
                val expected = if (communicationPhase == CommunicationPhase.Effective) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
                val unsafe = (state == State.Opening || state == State.Running) && when (communicationPhase) {
                    CommunicationPhase.Effective -> mode != AudioManager.MODE_IN_COMMUNICATION
                    CommunicationPhase.Preparing, CommunicationPhase.Quiescent -> mode != AudioManager.MODE_NORMAL
                    else -> mode != AudioManager.MODE_NORMAL && mode != AudioManager.MODE_IN_COMMUNICATION
                }
                if (unsafe && routeFailureDiagnostics == null) {
                    val now = System.nanoTime()
                    // Callback receipt itself is the failed observation; device/getter metadata
                    // is unavailable here. Never erase an observed foreign owner with a later getter.
                    routeFailureDiagnostics = RouteFailureDiagnostics(epoch, now, now, RouteCheckTrigger.ModeChanged.code, 1,
                        expected, mode, expectedDeviceId ?: -1, -1, -1, mode, -1,
                        preferredInputDevice?.id ?: -1, preferredInputDevice?.type ?: -1,
                        if (modeOwned) 1 else 0, if (deviceOwned) 1 else 0, if (focus != null) 1 else 0)
                }
                unsafe
            }
            if (unsafeReceipt) revoke(LiveLocalStopReason.RouteLost)
            else checkRouteOrRevoke(RouteCheckTrigger.ModeChanged, callbackMode = mode)
        } else null
    private val deviceListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        AudioManager.OnCommunicationDeviceChangedListener { device ->
            checkRouteOrRevoke(RouteCheckTrigger.CommunicationDeviceChanged, callbackCommunicationId = device?.id ?: -1)
        } else null

    internal fun requireActive() = synchronized(lock) {
        check(state == State.Running) { "The Live audio session has stopped. Start Live again." }
        focus?.requireActive()
    }

    /** Explicit user intent is separate from the physical recording lifetime. This publication
     * precedes our own stop, so Android's resulting inactive-mode callback has a causal owner. */
    fun setMicPermission(permission: MicPermission) {
        synchronized(lock) {
            if (state != State.Running || permission.captureGeneration < micPermission.captureGeneration) return
            if (permission.captureGeneration == micPermission.captureGeneration &&
                micPermission is MicPermission.UserMuted && permission is MicPermission.Allowed) return
            micPermission = permission
            if (permission is MicPermission.UserMuted) {
                captureDemands.values.forEach { it.fenced = true }
                markStoppingLocked()
            }
        }
        scheduleRelinquish()
    }

    internal class DemandRetired(message: String) : IllegalStateException(message)

    internal inner class CaptureDemand internal constructor(private val key: Any) : Closeable {
        fun fence() { synchronized(lock) { captureDemands[key]?.fenced = true; markStoppingLocked() } }
        override fun close() {
            synchronized(lock) { captureDemands.remove(key) }
            scheduleRelinquish()
        }
        internal fun hardware(): CommunicationPermit = ensureHardwareKey(key, capture = true)
    }

    inner class OutputDemand internal constructor(private val key: Any) : Closeable {
        val session: LiveAudioSession get() = this@LiveAudioSession
        internal fun fenceHardware() { synchronized(lock) { outputDemands[key]?.fenced = true; markStoppingLocked() } }
        override fun close() {
            synchronized(lock) { outputDemands.remove(key); markStoppingLocked() }
            scheduleRelinquish()
        }
        internal fun hardware(): CommunicationPermit = ensureHardwareKey(key, capture = false)
    }

    internal inner class CommunicationPermit internal constructor(
        private val key: Any, private val capture: Boolean, private val leaseEpoch: Long,
    ) {
        fun requireCurrent() = synchronized(lock) {
            requireDemandLocked(key, capture)
            check(communicationPhase == CommunicationPhase.Effective && communicationEpoch == leaseEpoch) {
                "The communication activation has been fenced"
            }
        }
    }

    internal fun reserveCapture(): CaptureDemand = synchronized(lock) {
        requireActive()
        val permission = micPermission as? MicPermission.Allowed ?: throw DemandRetired("Microphone permission is muted")
        val key = Any()
        captureDemands[key] = DemandRecord(permission.captureGeneration)
        if (communicationPhase == CommunicationPhase.Quiescent) communicationPhase = CommunicationPhase.Preparing
        CaptureDemand(key)
    }

    private fun reserveOutputLocked(): OutputDemand {
        requireActive()
        val key = Any()
        outputDemands[key] = DemandRecord()
        if (communicationPhase == CommunicationPhase.Quiescent) communicationPhase = CommunicationPhase.Preparing
        return OutputDemand(key)
    }

    internal fun readyCueOutputDemand(): OutputDemand = synchronized(lock) { checkNotNull(readyCueDemand) }
    internal fun ensureHardware(demand: CaptureDemand): CommunicationPermit = demand.hardware()
    internal fun ensureHardware(demand: OutputDemand): CommunicationPermit {
        check(demand.session === this)
        return demand.hardware()
    }

    private fun requireDemandLocked(key: Any, capture: Boolean): DemandRecord {
        requireActive()
        val record = (if (capture) captureDemands else outputDemands)[key] ?: throw DemandRetired("Audio demand was released")
        if (record.fenced) throw DemandRetired("Audio demand was fenced")
        if (capture && (micPermission !is MicPermission.Allowed ||
            record.captureGeneration != micPermission.captureGeneration)) throw DemandRetired("Microphone generation was retired")
        return record
    }

    private fun markStoppingLocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (communicationPhase == CommunicationPhase.Effective &&
            captureDemands.values.none { it.granted && !it.fenced } && outputDemands.values.none { it.granted && !it.fenced }) {
            // Pending model/phrase work is logically owned, but cannot keep Android's physical
            // activity lease alive. It will remain Preparing, not Quiescent, after release.
            communicationPhase = CommunicationPhase.Stopping
        } else if (communicationPhase == CommunicationPhase.Preparing && micPermission is MicPermission.UserMuted &&
            !modeOwned && !deviceOwned && captureDemands.isEmpty() && outputDemands.isEmpty())
            communicationPhase = CommunicationPhase.Quiescent
    }

    private fun noGrantedHardwareLocked() = captureDemands.values.none { it.granted } && outputDemands.values.none { it.granted }
    private fun shouldRelinquishLocked(): Boolean = state == State.Running && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        communicationPhase == CommunicationPhase.Stopping && noGrantedHardwareLocked()

    private fun scheduleRelinquish() {
        val launch = synchronized(lock) {
            markStoppingLocked()
            if (releaseQueued || !shouldRelinquishLocked()) false else { releaseQueued = true; true }
        }
        if (!launch) return
        try {
            control.execute {
                try {
                    if (synchronized(lock) { shouldRelinquishLocked() }) relinquishCommunication()
                } catch (_: Exception) { revoke(LiveLocalStopReason.RouteLost) }
                finally { synchronized(lock) { releaseQueued = false }; scheduleRelinquish() }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { synchronized(lock) { releaseQueued = false } }
    }

    /** Only IO/model-prepared callers wait here; all Android ownership APIs execute on one gate. */
    private fun <T> onControl(action: () -> T): T {
        if (Thread.currentThread() === controlThread.get()) return action()
        try { return control.submit(Callable { action() }).get() }
        catch (error: ExecutionException) { throw error.cause ?: error }
    }

    private fun ensureHardwareKey(key: Any, capture: Boolean): CommunicationPermit = onControl {
        activateHardwareDemand(key, capture)
    }

    private fun activateHardwareDemand(key: Any, capture: Boolean): CommunicationPermit {
        return try {
            synchronized(lock) { requireDemandLocked(key, capture) }
            if (synchronized(lock) { communicationPhase == CommunicationPhase.Stopping }) {
                // An authorized new demand cannot overtake the old capture's actual stop proof.
                val deadline = System.nanoTime() + 4_000_000_000L
                while (!synchronized(lock) { noGrantedHardwareLocked() }) {
                    synchronized(lock) { requireDemandLocked(key, capture) }
                    check(System.nanoTime() < deadline) { "Previous audio hardware has not stopped" }
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000)
                }
                relinquishCommunication()
            }
            val (needsClaim, observedLeaseEpoch) = synchronized(lock) {
                (communicationPhase != CommunicationPhase.Effective) to communicationEpoch
            }
            if (needsClaim) {
                check(routeMatches(RouteCheckTrigger.Explicit)) { "Another audio owner changed the idle route" }
                synchronized(lock) { requireDemandLocked(key, capture); communicationPhase = CommunicationPhase.Acquiring; communicationEpoch++ }
                val beforeClaim = synchronized(modeAcknowledgement) { modeAcknowledgementSequence }
                manager.mode = AudioManager.MODE_IN_COMMUNICATION
                synchronized(lock) { modeOwned = true }
                check(manager.mode == AudioManager.MODE_IN_COMMUNICATION) { "Android rejected communication ownership" }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val target = checkNotNull(pinnedDevice)
                    val available = manager.availableCommunicationDevices.firstOrNull { it.id == target.id && it.type == target.type }
                        ?: error("The selected communication device was removed")
                    check(manager.setCommunicationDevice(available)) { "Android rejected the selected communication route" }
                    synchronized(lock) { deviceOwned = true }
                    if (target.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || target.type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                        val input = BluetoothAudioRoute.inputForDevice(appContext, available)
                            ?: error("The selected headset has no matching microphone input. Reconnect it before starting Live.")
                        val previousInput = preferredInputDevice
                        check(previousInput == null || (previousInput.id == input.id && previousInput.type == input.type)) {
                            "The selected headset microphone was replaced"
                        }
                        synchronized(lock) { preferredInputDevice = input }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    run { manager.isSpeakerphoneOn = !isolatedOutput }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    physicalClaimSequence = awaitPhysicalMode(AudioManager.MODE_IN_COMMUNICATION, beforeClaim)
                synchronized(lock) { requireDemandLocked(key, capture) }
                check(manager.mode == AudioManager.MODE_IN_COMMUNICATION) { "Communication ownership changed during activation" }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    check(manager.communicationDevice?.id == expectedDeviceId) { "The communication route changed during activation" }
            } else check(routeMatches(RouteCheckTrigger.Explicit)) { "The communication route changed" }
            synchronized(lock) {
                val record = requireDemandLocked(key, capture)
                if (needsClaim) check(communicationPhase == CommunicationPhase.Acquiring && communicationEpoch == observedLeaseEpoch + 1) {
                    "The communication claim transaction was superseded"
                }
                if (!needsClaim && (communicationPhase != CommunicationPhase.Effective || communicationEpoch != observedLeaseEpoch)) null else {
                    record.granted = true
                    // Publish Effective and its first actual hardware capability atomically.
                    communicationPhase = CommunicationPhase.Effective
                    CommunicationPermit(key, capture, communicationEpoch)
                }
            } ?: activateHardwareDemand(key, capture) // Our old owner began stopping during observation.
        } catch (retired: DemandRetired) {
            // User mute/cancellation during preparation is not a route fault or an implicit End.
            // If this demand was retired halfway through a new claim, finish our own rollback.
            if (synchronized(lock) { state == State.Running && communicationPhase == CommunicationPhase.Acquiring }) {
                try { relinquishCommunication() } catch (_: Exception) { revoke(LiveLocalStopReason.RouteLost) }
            }
            throw retired
        } catch (error: Throwable) {
            revoke(LiveLocalStopReason.RouteLost)
            throw error
        }
    }

    /** Remove only this PID's mode request and this client's route request; keep focus/listeners/APM.
     * Pending authorized demands wait behind this same gate and can never be stomped by a late clear. */
    private fun relinquishCommunication() {
        synchronized(lock) {
            requireActive()
            check(noGrantedHardwareLocked()) { "Audio hardware still owns the communication lease" }
            communicationPhase = CommunicationPhase.Relinquishing
            communicationEpoch++
        }
        if (modeOwned) {
            manager.mode = AudioManager.MODE_NORMAL
            synchronized(lock) { modeOwned = false }
            check(manager.mode == AudioManager.MODE_NORMAL) { "Another communication owner is active" }
            // A cached NORMAL from an older lease cannot acknowledge this lease's release.
            awaitPhysicalMode(AudioManager.MODE_NORMAL, physicalClaimSequence)
        }
        synchronized(lock) { requireActive() }
        check(manager.mode == AudioManager.MODE_NORMAL) { "Another communication owner is active" }
        if (deviceOwned && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.clearCommunicationDevice()
            synchronized(lock) { deviceOwned = false }
        }
        val baseline = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) manager.communicationDevice?.id ?: -1 else -1
        check(manager.mode == AudioManager.MODE_NORMAL) { "Another communication owner became active" }
        check(pinnedRouteAvailable()) { "The selected communication device was removed" }
        synchronized(lock) {
            requireActive()
            idleDeviceId = baseline
            communicationPhase = if (micPermission is MicPermission.UserMuted && captureDemands.isEmpty() && outputDemands.isEmpty())
                CommunicationPhase.Quiescent else CommunicationPhase.Preparing
        }
    }

    private fun awaitPhysicalMode(mode: Int, afterSequence: Long): Long {
        val deadline = System.nanoTime() + 3_000_000_000L
        synchronized(modeAcknowledgement) {
            while (lastPhysicalMode != mode || modeAcknowledgementSequence <= afterSequence) {
                synchronized(lock) { requireActive() }
                check(lastPhysicalMode == -1 || lastPhysicalMode == AudioManager.MODE_NORMAL ||
                    lastPhysicalMode == AudioManager.MODE_IN_COMMUNICATION) { "Another communication mode became active" }
                val left = deadline - System.nanoTime()
                check(left > 0) { "Android did not acknowledge the communication transition" }
                modeAcknowledgement.wait(maxOf(1, left / 1_000_000))
            }
            return modeAcknowledgementSequence
        }
    }

    private fun pinnedRouteAvailable(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val target = pinnedDevice
        target != null && manager.availableCommunicationDevices.any { it.id == target.id && it.type == target.type }
    } else true

    /** Registration and revocation serialize: no hardware can attach to a revoked epoch. */
    internal fun bindStop(stop: (LiveLocalStopReason) -> Unit): Closeable {
        val key = Any()
        synchronized(lock) {
            requireActive()
            stops[key] = stop
        }
        return Closeable { synchronized(lock) { stops.remove(key) }; Unit }
    }

    /** Short hardware installation only; never call a decoder/model constructor under this lock. */
    internal fun <T> whileActive(action: () -> T): T = synchronized(lock) {
        requireActive()
        check(communicationPhase == CommunicationPhase.Effective) { "Communication hardware is not active" }
        action()
    }

    fun checkRoute() {
        requireActive()
        if (!routeMatches(RouteCheckTrigger.Explicit)) {
            revoke(LiveLocalStopReason.RouteLost)
            error("The Live audio route changed. Start Live again on the selected route.")
        }
    }

    private fun routeMatches(trigger: RouteCheckTrigger, callbackMode: Int = -1,
        callbackCommunicationId: Int = -1): Boolean {
        val began = System.nanoTime()
        val phase = synchronized(lock) { communicationPhase }
        val expectedMode = if (phase == CommunicationPhase.Effective) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
        var actualMode = -1
        var actualDeviceId = -1
        var actualDeviceType = -1
        var reason = 0
        try {
            actualMode = manager.mode
            val modeMatches = when (phase) {
                CommunicationPhase.Effective -> actualMode == AudioManager.MODE_IN_COMMUNICATION
                CommunicationPhase.Stopping, CommunicationPhase.Acquiring ->
                    actualMode == AudioManager.MODE_NORMAL || actualMode == AudioManager.MODE_IN_COMMUNICATION
                CommunicationPhase.Relinquishing -> actualMode == AudioManager.MODE_NORMAL ||
                    (synchronized(lock) { modeOwned } && actualMode == AudioManager.MODE_IN_COMMUNICATION)
                CommunicationPhase.Preparing, CommunicationPhase.Quiescent -> actualMode == AudioManager.MODE_NORMAL
            }
            if (!modeMatches) reason = reason or 1
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val device = manager.communicationDevice
                actualDeviceId = device?.id ?: -1
                actualDeviceType = device?.type ?: -1
                val strictActive = phase == CommunicationPhase.Effective ||
                    (phase == CommunicationPhase.Stopping && actualMode == AudioManager.MODE_IN_COMMUNICATION)
                val idleBaseline = synchronized(lock) { idleDeviceId }
                if ((strictActive && device?.id != expectedDeviceId) || !pinnedRouteAvailable() ||
                    ((phase == CommunicationPhase.Preparing || phase == CommunicationPhase.Quiescent) &&
                        idleBaseline != null && (device?.id ?: -1) != idleBaseline)) reason = reason or 2
            } else {
                @Suppress("DEPRECATION")
                val isolated = manager.isWiredHeadsetOn || manager.isBluetoothScoOn
                if (isolated != isolatedOutput) reason = reason or 8
            }
        } catch (_: Exception) { reason = reason or 4 }
        // A local stop publication can change the predicate during sequential OS observations.
        // Re-observe that new transaction, never latch a failure against a superseded phase.
        if (synchronized(lock) { communicationPhase != phase }) return routeMatches(trigger, callbackMode, callbackCommunicationId)
        if (reason == 0) return true
        val ended = System.nanoTime()
        synchronized(lock) {
            if (state == State.Running && routeFailureDiagnostics == null) {
                routeFailureDiagnostics = RouteFailureDiagnostics(epoch, began, ended, trigger.code, reason,
                    expectedMode, actualMode, expectedDeviceId ?: -1,
                    actualDeviceId, actualDeviceType, callbackMode, callbackCommunicationId,
                    preferredInputDevice?.id ?: -1, preferredInputDevice?.type ?: -1,
                    if (modeOwned) 1 else 0, if (deviceOwned) 1 else 0, if (focus != null) 1 else 0)
            }
        }
        return false // Same mode + exact device/isolation predicate; no fallback or reassertion.
    }

    private fun checkRouteOrRevoke(trigger: RouteCheckTrigger, callbackMode: Int = -1,
        callbackCommunicationId: Int = -1) {
        // Observe after our current OS transaction, not halfway through our own mode/device claim.
        // Mode acknowledgements are published by the listener BEFORE this work is queued.
        try {
            control.execute {
                if (synchronized(lock) { state == State.Running } &&
                    !routeMatches(trigger, callbackMode, callbackCommunicationId)) revoke(LiveLocalStopReason.RouteLost)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { /* Terminal owner. */ }
    }

    /** Exclusive renderer capability, reserved BEFORE async cue decode or TTS construction. */
    internal inner class RenderReservation {
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        private val released = java.util.concurrent.atomic.AtomicBoolean(false)
        fun release() {
            if (!released.compareAndSet(false, true)) return
            val next = synchronized(lock) {
                renderAdmissions.remove(this)
                renderAdmissions.peekFirst().takeIf { state == State.Running }
            }
            ready.cancel()
            next?.ready?.complete(Unit)
        }
    }

    private fun enqueueRenderLocked(): RenderReservation = RenderReservation().also { renderAdmissions.addLast(it) }

    internal class RenderLease(val rate: Int, private val reservation: RenderReservation) : Closeable {
        @Volatile var accepted: Long = 0
            private set
        fun written(count: Int) { accepted += count }
        override fun close() = reservation.release()
    }

    internal fun renderReference(track: AudioTrack, rate: Int, reservation: RenderReservation): RenderLease = whileActive {
        check(track.sampleRate == rate && renderAdmissions.peekFirst() === reservation && reservation.ready.isCompleted && !reservation.ready.isCancelled)
        RenderLease(rate, reservation)
    }

    internal suspend fun awaitSpeechRenderProducer(): RenderReservation {
        val reservation = synchronized(lock) { requireActive(); enqueueRenderLocked() }
        val first = synchronized(lock) { renderAdmissions.peekFirst() === reservation }
        if (first) reservation.ready.complete(Unit)
        try {
            reservation.ready.await()
            requireActive()
            return reservation
        } catch (error: Throwable) { reservation.release(); throw error }
    }

    internal fun whenReadyCueGranted(action: () -> Unit) {
        val reservation = synchronized(lock) { readyCueReservation } ?: return
        reservation.ready.invokeOnCompletion { error -> if (error == null) action() }
    }

    internal fun readyCueAllowed(): Boolean = synchronized(lock) {
        state == State.Running && readyCueReservation != null && renderAdmissions.peekFirst() === readyCueReservation
    }

    internal fun readyCueRenderReservation(): RenderReservation = synchronized(lock) {
        check(readyCueAllowed())
        checkNotNull(readyCueReservation)
    }

    internal fun finishReadyCue() {
        val owned = synchronized(lock) { readyCueReservation to readyCueDemand.also { readyCueDemand = null } }
        owned.first?.release()
        owned.second?.close()
    }

    internal fun claimReadyCue(): Boolean {
        val reservation = synchronized(lock) {
            if (state != State.Running || readyCueClaimed) return false
            readyCueClaimed = true
            readyCueDemand = reserveOutputLocked()
            enqueueRenderLocked().also { readyCueReservation = it }
        }
        val first = synchronized(lock) { renderAdmissions.peekFirst() === reservation }
        if (first) reservation.ready.complete(Unit)
        return true
    }

    /** Wake/cancel waiters AFTER actual hardware stop bindings have fenced their exact owners. */
    private fun renderCancellationStopsLocked(): List<(LiveLocalStopReason) -> Unit> =
        renderAdmissions.toList().also { renderAdmissions.clear() }.map { reservation ->
            { _: LiveLocalStopReason -> reservation.ready.cancel() }
        }

    /** Immediate public-entry privacy fence, even if the controller actor is blocked. No callback,
     * focus/route Binder teardown or native disposal/join here. The revoked registry remains occupied
     * until the actor closes this exact session, so late output cannot acquire standalone focus.
     */
    fun fenceHardware(reason: LiveLocalStopReason) {
        takeRevocationStops()?.forEach { stop ->
            runCatching { stop(reason) }.onFailure { Log.w(TAG, "Audio safety stop failed") }
        }
    }

    private fun takeRevocationStops(originStillActive: () -> Boolean = { true }): List<(LiveLocalStopReason) -> Unit>? = synchronized(lock) {
        if (state == State.Revoked || state == State.Closed || !originStillActive()) null else {
            state = State.Revoked
            (stops.values.toList() + renderCancellationStopsLocked()).also { stops.clear() }
        }
    }

    /** First fence EVERY binding, then stop owned hardware/output, release OS claims, then notify.
     * An input-engine failure may supply its originating listener instead of the session listener.
     * Native disposal/join is explicitly not part of this privacy path.
     */
    internal fun revoke(reason: LiveLocalStopReason, originStillActive: () -> Boolean = { true }, notify: ((LiveLocalStopReason) -> Unit)? = null): Boolean {
        val actions = takeRevocationStops(originStillActive) ?: return false
        actions.forEach { stop -> runCatching { stop(reason) }.onFailure { Log.w(TAG, "Audio safety stop failed") } }
        synchronized(modeAcknowledgement) { modeAcknowledgement.notifyAll() }
        val finish = {
            releaseOsOwnership()
            runCatching { (notify ?: onLocalStop)(reason) }.onFailure { Log.w(TAG, "Audio stop listener failed") }
            Unit
        }
        // Safety callbacks must never wait for the gate: it may need a mode acknowledgement
        // delivered by that same dispatcher. Hardware was already fenced/stopped above.
        if (Thread.currentThread() === controlThread.get()) finish() else {
            try { control.execute { finish() } }
            catch (_: java.util.concurrent.RejectedExecutionException) {
                // Concurrent close already owns terminal OS cleanup and registry retirement.
                // Never fall back to Binder work on the safety callback thread.
            }
        }
        return true
    }

    override fun close() {
        val actions = synchronized(lock) {
            if (state == State.Closed) return
            state = State.Closed
            (stops.values.toList() + renderCancellationStopsLocked()).also { stops.clear() }
        }
        actions.forEach { runCatching { it(LiveLocalStopReason.Backgrounded) } }
        synchronized(modeAcknowledgement) { modeAcknowledgement.notifyAll() }
        releaseOsOwnership()
        synchronized(registryLock) { if (active === this) active = null }
        control.shutdown()
    }

    private fun releaseOsOwnership() = onControl {
        synchronized(osReleaseLock) {
            val ownedFocus = synchronized(lock) {
                if (osReleased) return@onControl
                focus.also { focus = null }
            }
            if (callbacksRegistered) {
                runCatching { manager.unregisterAudioDeviceCallback(devices) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    modeListener?.let { runCatching { manager.removeOnModeChangedListener(it) } }
                    deviceListener?.let { runCatching { manager.removeOnCommunicationDeviceChangedListener(it) } }
                }
            }
            // These process-owned Binder releases must FINISH before close() frees the registry.
            // Concurrent focus-loss revoke + actor close must not mistake "release started" for
            // "release complete", then let the old clearCommunicationDevice stomp a fresh lease.
            if (deviceOwned && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { manager.clearCommunicationDevice() }
            if (modeOwned) runCatching { manager.mode = AudioManager.MODE_NORMAL }
            runCatching { ownedFocus?.close() }
            synchronized(lock) { osReleased = true; modeOwned = false; deviceOwned = false }
        }
    }

    private fun prepare(context: Context) {
        onControl {
            check(manager.mode == AudioManager.MODE_NORMAL) { "Another call is using communication audio. End it before starting Live." }
            // Register before the first ownership claim, including its physical mode acknowledgement.
            callbacksRegistered = true
            manager.registerAudioDeviceCallback(devices, AudioSafetyDispatcher.handler)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                modeListener?.let { manager.addOnModeChangedListener(AudioSafetyDispatcher.executor, it) }
                deviceListener?.let { manager.addOnCommunicationDeviceChangedListener(AudioSafetyDispatcher.executor, it) }
            }
            val acquired = AudioFocusLease.acquire(context, attributes, AudioManager.AUDIOFOCUS_GAIN) {
                revoke(LiveLocalStopReason.FocusLost)
            }
            val installed = synchronized(lock) {
                if (state != State.Opening) false else { focus = acquired; true }
            }
            if (!installed) { acquired.close(); error("Live audio focus was revoked during startup") }
            val isolated: Boolean
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val available = manager.availableCommunicationDevices
                val selected = manager.communicationDevice?.takeIf { isHeadset(it.type) }
                    ?.let { old -> available.firstOrNull { it.id == old.id } }
                val headsets = available.filter { isHeadset(it.type) }
                val output = selected ?: headsets.firstOrNull { isWiredHeadset(it.type) } ?: headsets.singleOrNull() ?: run {
                    check(headsets.isEmpty()) { "Select which headset to use before starting Live." }
                    check(manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).none {
                        isHeadset(it.type) || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    }) { "The connected headphones have no communication route. Reconnect or select a headset." }
                    available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        ?: error("No speaker or headset communication route is available")
                }
                // Pin identity now, but acquire the physical mode/route only just before real
                // hardware. Slow decoder/model preparation cannot consume Android's activity grace.
                isolated = isHeadset(output.type)
                expectedDeviceId = output.id
                pinnedDevice = output
            } else {
                @Suppress("DEPRECATION")
                isolated = manager.isWiredHeadsetOn || manager.isBluetoothScoOn
            }
            isolatedOutput = isolated
            idleDeviceId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) manager.communicationDevice?.id ?: -1 else -1
        }
        synchronized(lock) {
            check(state == State.Opening) { "Live audio was revoked during setup" }
            echoPolicy = if (isolatedOutput) EchoCancellationPolicy.Optional else EchoCancellationPolicy.Required
            state = State.Running
        }
        onControl { check(routeMatches(RouteCheckTrigger.Explicit)) { "The idle audio route changed during preparation" } }
        Log.i(TAG, "Duplex focus ready: epoch=$epoch isolated=$isolatedOutput")
    }

    companion object {
        private const val TAG = "KompiLiveAudio"
        private val epochs = AtomicLong(0)
        private val registryLock = Any()
        private var active: LiveAudioSession? = null
        internal val attributes: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val isActive: Boolean get() = synchronized(registryLock) { active != null }

        fun open(context: Context, onLocalStop: (LiveLocalStopReason) -> Unit): LiveAudioSession {
            val session = LiveAudioSession(context.applicationContext, context.getSystemService(AudioManager::class.java), onLocalStop)
            synchronized(registryLock) {
                check(active == null) { "Another Live audio session is still closing" }
                active = session
            }
            try { session.prepare(context); return session }
            catch (error: Throwable) { session.close(); throw error }
        }

        fun reserveOutputDemand(requestId: String, sequence: Long): OutputDemand? = synchronized(registryLock) {
            require(requestId.isNotBlank() && sequence >= 0)
            active?.let { session -> synchronized(session.lock) { session.reserveOutputLocked() } }
        }

        internal fun borrowPlayback(stop: () -> Unit): PlaybackAudioLease? = synchronized(registryLock) {
            active?.let { PlaybackAudioLease.BorrowedLive(it, it.bindStop { stop() }) }
        }

        private fun isWiredHeadset(type: Int): Boolean = type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || type == AudioDeviceInfo.TYPE_USB_HEADSET
        private fun isHeadset(type: Int): Boolean = isWiredHeadset(type) ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }
}

/** The borrowing decision is made once and retained through cleanup; never re-read global state. */
internal sealed interface PlaybackAudioLease : Closeable {
    val attributes: AudioAttributes
    fun requireActive()
    class BorrowedLive(val session: LiveAudioSession, private val binding: Closeable,
        private val demand: LiveAudioSession.OutputDemand? = null) : PlaybackAudioLease {
        fun fenceHardware() { demand?.fenceHardware() }
        override val attributes get() = LiveAudioSession.attributes
        override fun requireActive() { session.requireActive() }
        override fun close() = binding.close()
    }
    class OwnedStandalone(private val focus: AudioFocusLease) : PlaybackAudioLease {
        override val attributes = mediaAttributes
        override fun requireActive() = focus.requireActive()
        override fun close() = focus.close()
    }
    companion object {
        private val mediaAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        fun acquireForDemand(context: Context, stop: () -> Unit, demand: LiveAudioSession.OutputDemand?): PlaybackAudioLease =
            if (demand != null) BorrowedLive(demand.session, demand.session.bindStop { stop() }, demand)
            else OwnedStandalone(AudioFocusLease.acquire(context, mediaAttributes, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, stop))
        fun acquire(context: Context, stop: () -> Unit): PlaybackAudioLease = LiveAudioSession.borrowPlayback(stop)
            ?: OwnedStandalone(AudioFocusLease.acquire(context, mediaAttributes, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, stop))
    }
}
