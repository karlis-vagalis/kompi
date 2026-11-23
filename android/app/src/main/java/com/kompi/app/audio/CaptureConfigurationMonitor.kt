package com.kompi.app.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.io.Closeable

/** Content-free LAST observed configuration, not a claim about unobservable vendor DSP.
 * Phase 0: selection only; 1: active configuration observed; 2: closed, last values retained.
 * Unavailable fields are -1. Effect masks: AEC=1, NS=2, AGC=4, other=8.
 */
internal data class CaptureConfigurationDiagnostics(
    val requestEpoch: Long,
    val audioSessionId: Int,
    val recordIdentity: Int,
    val configuredSource: Int,
    val requestedSource: Int,
    val recordSource: Int,
    val unprocessedSupport: Int,
    val privacySensitive: Int,
    val requiredInputDeviceId: Int,
    val requiredInputDeviceType: Int,
    val actualInputDeviceId: Int = -1,
    val actualInputDeviceType: Int = -1,
    val phase: Int = 0,
    val observedNanos: Long = 0,
    val configurationAvailable: Int = 0,
    val clientSource: Int = -1,
    val deviceSource: Int = -1,
    val clientSilenced: Int = -1,
    val clientEffectsMask: Int = -1,
    val deviceEffectsMask: Int = -1,
    val clientEffectCount: Int = -1,
    val deviceEffectCount: Int = -1,
)

/** Registered BEFORE recording starts. No queries, callbacks or polling on the acquisition path.
 * Main-executor delivery can be late: missing metadata stays explicitly unavailable, never PCM.
 */
internal class CaptureConfigurationMonitor(
    context: Context,
    private val record: AudioRecord,
    requestEpoch: Long,
    selection: CaptureSourceSelection,
    preferredInput: AudioDeviceInfo?,
    private val publish: (CaptureConfigurationDiagnostics) -> Unit,
) : Closeable {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var closed = false
    private var registered = false
    private var snapshot = CaptureConfigurationDiagnostics(requestEpoch, record.audioSessionId,
        System.identityHashCode(record), selection.configuredSource, selection.requestedSource,
        record.audioSource, selection.unprocessedSupport.code,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { if (record.isPrivacySensitive) 1 else 0 } else -1,
        preferredInput?.id ?: -1, preferredInput?.type ?: -1)
    private val ownerSessionId = snapshot.audioSessionId
    private val callback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val configuration = configs.firstOrNull { it.clientAudioSessionId == ownerSessionId } ?: return
            observe(configuration)
        }
    }

    init {
        publish(snapshot)
        registered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                record.registerAudioRecordingCallback(ContextCompat.getMainExecutor(context), callback)
            } else {
                checkNotNull(manager).registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
            }
            true
        }.getOrDefault(false)
    }

    private fun observe(configuration: AudioRecordingConfiguration) {
        // Device resolution may consult AudioManager. Never hold the publication/close lock
        // across it, and never perform it on acquisition or from a diagnostic reader.
        val device = runCatching { configuration.audioDevice }.getOrNull()
        val clientEffects = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) configuration.clientEffects else null
        val deviceEffects = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) configuration.effects else null
        synchronized(this) {
            if (closed) return
            val next = snapshot.copy(phase = 1, observedNanos = System.nanoTime(), configurationAvailable = 1,
                clientSource = configuration.clientAudioSource,
                actualInputDeviceId = device?.id ?: -1, actualInputDeviceType = device?.type ?: -1)
            snapshot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                next.copy(deviceSource = configuration.audioSource,
                    clientSilenced = if (configuration.isClientSilenced) 1 else 0,
                    clientEffectsMask = effectMask(checkNotNull(clientEffects)), deviceEffectsMask = effectMask(checkNotNull(deviceEffects)),
                    clientEffectCount = clientEffects.size, deviceEffectCount = deviceEffects.size)
            } else next
            publish(snapshot)
        }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            snapshot = snapshot.copy(phase = 2)
            publish(snapshot)
        }
        // Hardware has already stopped and its reader exited before this resource is disposed.
        if (registered) runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) record.unregisterAudioRecordingCallback(callback)
            else manager?.unregisterAudioRecordingCallback(callback)
        }
    }

    private fun effectMask(effects: List<AudioEffect.Descriptor>): Int = effects.fold(0) { mask, effect ->
        mask or when (effect.type) {
            AudioEffect.EFFECT_TYPE_AEC -> 1
            AudioEffect.EFFECT_TYPE_NS -> 2
            AudioEffect.EFFECT_TYPE_AGC -> 4
            else -> 8
        }
    }
}
