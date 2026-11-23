package com.kompi.app.audio.live

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.kompi.app.R
import com.kompi.app.audio.DuplexEchoProcessor
import com.kompi.app.audio.LiveAudioSession
import com.kompi.app.audio.LivePcmBlockWriter
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/** Optional PCM earcon on the exact Live lease. Unlike SoundPool, every successful partial write
 * has a render-reference handoff. Hardware stop is immediate; the writer releases its own track
 * only after it exits, never underneath a blocking write and never by joining on the privacy path.
 */
internal class LiveReadyTone(context: Context) : Closeable {
    private data class Cue(val rate: Int, val samples: FloatArray)
    private val lock = Any()
    private var closed = false
    private var scheduled = false
    private var unavailable = false
    private var writerStarted = false
    private var cue: Cue? = null
    private var session: LiveAudioSession? = null
    private var binding: Closeable? = null
    private var track: AudioTrack? = null
    private var reference: DuplexEchoProcessor.RenderLease? = null

    init {
        val resources = context.resources
        if (!dispatch({
            try {
                val bytes = resources.openRawResourceFd(R.raw.live_ready).use { fd ->
                    fd.createInputStream().use { input ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            check(out.size() + n <= MAX_BYTES)
                            out.write(buffer, 0, n)
                        }
                        out.toByteArray()
                    }
                }
                val decoded = decode(bytes)
                synchronized(lock) { if (!closed) cue = decoded }
                scheduleIfReady()
            } catch (_: Exception) {
                Log.w(TAG, "Listening-ready sound is unavailable")
                markUnavailable()
            }
        })) markUnavailable()
    }

    /** Caller has already claimed the once-per-retained-session cue after actual first capture. */
    fun play(owner: LiveAudioSession) {
        val stop = try { owner.bindStop { close() } } catch (_: Exception) { return }
        val keep = synchronized(lock) {
            if (closed || unavailable || session != null) false else { session = owner; binding = stop; true }
        }
        if (!keep) { stop.close(); owner.finishReadyCue() } else {
            owner.whenReadyCueGranted(::scheduleIfReady)
            scheduleIfReady()
        }
    }

    private fun markUnavailable() {
        val owner = synchronized(lock) { unavailable = true; cue = null; session.takeIf { !writerStarted } }
        owner?.finishReadyCue()
    }

    private fun scheduleIfReady() {
        val owner = synchronized(lock) { session } ?: return
        if (!owner.readyCueAllowed()) return
        val ready = synchronized(lock) {
            if (closed || scheduled || cue == null || session == null) false else { scheduled = true; true }
        }
        if (ready && !dispatch(::playPrepared)) markUnavailable()
    }

    private fun playPrepared() {
        val (sound, owner) = synchronized(lock) {
            if (closed || unavailable) return
            Pair(cue ?: return, session ?: return).also { writerStarted = true }
        }
        var owned: AudioTrack? = null
        var rendered: DuplexEchoProcessor.RenderLease? = null
        var demand: LiveAudioSession.OutputDemand? = null
        try {
            // This background writer owns the cue demand through its actual track finally.
            // Neither decode nor a metadata/privacy lock contains an Android ownership call.
            demand = owner.readyCueOutputDemand()
            val hardware = owner.ensureHardware(checkNotNull(demand))
            hardware.requireCurrent()
            owner.whileActive {
                synchronized(lock) {
                    if (closed || !owner.readyCueAllowed()) return@whileActive
                    val minimum = AudioTrack.getMinBufferSize(sound.rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                    check(minimum > 0)
                    val made = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(sound.rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                        .setBufferSizeInBytes(maxOf(minimum, sound.rate * 80 / 1000 * 4))
                        .setTransferMode(AudioTrack.MODE_STREAM).build()
                    owned = made
                    check(made.state == AudioTrack.STATE_INITIALIZED)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) made.setStartThresholdInFrames(minOf(made.bufferSizeInFrames, sound.rate / 100))
                    rendered = owner.renderReference(made, sound.rate, owner.readyCueRenderReservation())
                    track = made; reference = rendered
                }
            }
            val playback = owned ?: return
            var offset = 0
            var started = false
            val pcmWriter = LivePcmBlockWriter(playback, sound.rate, rendered) {
                owner.requireActive(); check(!synchronized(lock) { closed })
            }
            while (offset < sound.samples.size && !synchronized(lock) { closed }) {
                owner.requireActive()
                val count = minOf(sound.rate / 50, sound.samples.size - offset)
                val written = pcmWriter.append(sound.samples, offset, count)
                offset += count
                if (!started && written > 0) owner.whileActive {
                    synchronized(lock) {
                        if (!closed) { playback.play(); started = true; Log.i(TAG, "phase=listening_ready_tone") }
                    }
                }
            }
            pcmWriter.finish()
            if (!started && offset > 0) owner.whileActive {
                synchronized(lock) { if (!closed) { playback.play(); started = true } }
            }
            rendered?.finishWrites()
            val deadline = System.nanoTime() + sound.samples.size * 1_000_000_000L / sound.rate + 2_000_000_000L
            while (started && !synchronized(lock) { closed } &&
                (playback.playbackHeadPosition.toLong() and 0xffff_ffffL) < offset && System.nanoTime() < deadline) {
                LockSupport.parkNanos(5_000_000) // Observe actual drain, not a readiness/teardown sleep.
            }
        } catch (_: Exception) { Log.w(TAG, "Listening-ready sound could not play") }
        finally {
            demand?.fenceHardware()
            rendered?.close() // Writer has exited; freeze played PCM before resetting/releasing.
            owned?.let { player ->
                runCatching { player.pause() }; runCatching { player.flush() }
                runCatching { player.stop() }; runCatching { player.release() }
            }
            val stop = synchronized(lock) { track = null; reference = null; binding.also { binding = null } }
            stop?.close()
            owner.finishReadyCue()
        }
    }

    override fun close() {
        val stopped = synchronized(lock) {
            if (closed) return
            closed = true
            Triple(track, reference, binding).also { binding = null; cue = null }
        }
        synchronized(lock) { session }?.let { owner ->
            runCatching { owner.readyCueOutputDemand().fenceHardware() }
        }
        stopped.first?.let { player ->
            runCatching { player.pause() }
            stopped.second?.freeze()
            runCatching { player.flush() }
        }
        stopped.third?.close()
        val unstartedOwner = synchronized(lock) { session.takeIf { !writerStarted } }
        unstartedOwner?.finishReadyCue()
        // No release/join: playPrepared's finally owns the exact AudioTrack until its writer exits.
    }

    private fun decode(bytes: ByteArray): Cue {
        check(bytes.size >= 44)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
        check(tag(0) == "RIFF" && tag(8) == "WAVE")
        var position = 12
        var rate = 0; var channels = 0; var format = 0; var bits = 0
        var audioOffset = 0; var audioSize = 0
        while (position + 8 <= bytes.size) {
            val size = data.getInt(position + 4).toLong() and 0xffff_ffffL
            check(size <= MAX_BYTES && position + 8L + size <= bytes.size)
            when (tag(position)) {
                "fmt " -> {
                    check(size >= 16)
                    format = data.getShort(position + 8).toInt(); channels = data.getShort(position + 10).toInt()
                    rate = data.getInt(position + 12); bits = data.getShort(position + 22).toInt()
                }
                "data" -> { audioOffset = position + 8; audioSize = size.toInt() }
            }
            position += 8 + size.toInt() + (size.toInt() and 1)
        }
        check(format == 1 && bits == 16 && channels in 1..2 && rate in 8_000..96_000 && rate % 100 == 0)
        val frames = audioSize / (channels * 2)
        check(audioOffset > 0 && frames in 1..rate * 2 && audioSize % (channels * 2) == 0)
        val samples = FloatArray(frames) { frame ->
            var sum = 0
            for (channel in 0 until channels) sum += data.getShort(audioOffset + (frame * channels + channel) * 2)
            sum.toFloat() / (channels * 32768f)
        }
        return Cue(rate, samples)
    }

    private companion object {
        const val TAG = "KompiLiveAudio"
        const val MAX_BYTES = 1_000_000
        val worker = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8),
            { task -> Thread(task, "kompi-ready-cue").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
            .apply { allowCoreThreadTimeOut(true) }
        fun dispatch(task: () -> Unit): Boolean = try {
            worker.execute { task() }; true
        } catch (_: RejectedExecutionException) {
            Log.w(TAG, "Optional ready sound worker is busy"); false
        }
    }
}
