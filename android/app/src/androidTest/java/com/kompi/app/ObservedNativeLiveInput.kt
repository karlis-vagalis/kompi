package com.kompi.app

import android.os.SystemClock
import com.kompi.app.audio.AudioWaveform
import com.kompi.app.audio.LiveAudioSession
import com.kompi.app.audio.live.LocalLiveSpeechInput
import com.kompi.app.audio.SpeechUtteranceId
import com.kompi.app.live.CapturePhase
import com.kompi.app.live.LiveSpeechInput

/** Records actual native onset time and forwards every callback unchanged; never manufactures ASR. */
internal class ObservedNativeLiveInput(
    private val delegate: LiveSpeechInput,
    private val onNativeOnset: (Long) -> Unit,
) : LiveSpeechInput by delegate {
    @Volatile private var acquisition: NativeAcquisitionDiagnostics? = null

    internal fun observeAcquisition(owner: LiveAudioSession?, captureIndex: Int): NativeAcquisitionDiagnostics? {
        val local = delegate as? LocalLiveSpeechInput ?: return null
        return NativeAcquisitionDiagnostics(local, owner, captureIndex).also { acquisition = it }
    }

    override fun start(listener: LiveSpeechInput.Listener) {
        try {
            delegate.start(object : LiveSpeechInput.Listener {
                override fun onSpeechStarted(utteranceId: SpeechUtteranceId) {
                    onNativeOnset(SystemClock.elapsedRealtimeNanos()); listener.onSpeechStarted(utteranceId)
                }
                override fun onBargeInConfirmed(utteranceId: SpeechUtteranceId) = listener.onBargeInConfirmed(utteranceId)
                override fun onPartial(utteranceId: SpeechUtteranceId, committedText: String, pendingText: String, language: String?) = listener.onPartial(utteranceId, committedText, pendingText, language)
                override fun onFinal(utteranceId: SpeechUtteranceId, text: String, language: String?) = listener.onFinal(utteranceId, text, language)
                override fun onMeter(rms: Float, phase: CapturePhase, waveform: AudioWaveform) {
                    acquisition?.observeStart() // Bounded first metadata only; never reads PCM/hardware.
                    listener.onMeter(rms, phase, waveform)
                }
                override fun onLocalStop(reason: com.kompi.app.live.LiveLocalStopReason) {
                    acquisition?.observeStart()
                    listener.onLocalStop(reason)
                }
            })
        } finally {
            // start() publishes the input's capture synchronously, before asynchronous model work.
            // Retain it now, not by refetching .active after cleanup. All stop/fence calls remain
            // directly delegated: no diagnostic work is put in front of the privacy boundary.
            acquisition?.observeStart()
        }
    }
}
