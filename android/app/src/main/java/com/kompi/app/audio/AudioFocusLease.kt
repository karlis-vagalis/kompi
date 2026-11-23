package com.kompi.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** One process audio callback thread: loss must not queue behind UI work or native inference. */
internal object AudioSafetyDispatcher {
    private val thread = HandlerThread("kompi-audio-safety").apply { start() }
    val handler = Handler(thread.looper)
    val executor = Executor { command -> handler.post(command); Unit }
}

/** Retains the EXACT request/listener which owns focus; GAIN never revives a revoked lease. */
internal class AudioFocusLease private constructor(
    private val manager: AudioManager,
    attributes: AudioAttributes,
    gain: Int,
    onLoss: () -> Unit,
) : Closeable {
    private val live = AtomicBoolean(true)
    private val released = AtomicBoolean(false)
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (live.compareAndSet(true, false)) onLoss()
        }
    }
    private val request = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        AudioFocusRequest.Builder(gain)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(listener, AudioSafetyDispatcher.handler)
            .build()
    } else null

    init {
        val granted = if (request != null) manager.requestAudioFocus(request) else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, gain)
        }
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED || !live.get()) {
            close()
            error("Audio focus is unavailable. Finish the other audio session and start again.")
        }
    }

    fun requireActive() { check(live.get() && !released.get()) { "Audio focus was revoked" } }

    override fun close() {
        live.set(false)
        if (!released.compareAndSet(false, true)) return
        if (request != null) manager.abandonAudioFocusRequest(request) else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(listener)
        }
    }

    companion object {
        fun acquire(context: Context, attributes: AudioAttributes, gain: Int, onLoss: () -> Unit) =
            AudioFocusLease(context.getSystemService(AudioManager::class.java), attributes, gain, onLoss)
    }
}
