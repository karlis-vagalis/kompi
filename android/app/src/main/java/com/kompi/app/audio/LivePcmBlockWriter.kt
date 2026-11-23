package com.kompi.app.audio

import android.media.AudioTrack

/** Final, clipped PCM in source-rate 10 ms blocks. One analysis immediately precedes each bounded
 * hardware block; ALL successful partial writes finish before another block is analyzed. A final
 * short block is padded only for reference conversion, never written as invented output/mic samples.
 */
internal class LivePcmBlockWriter(
    private val track: AudioTrack,
    rate: Int,
    private val reference: DuplexEchoProcessor.RenderLease?,
    private val checkRunning: () -> Unit,
) {
    private val block = FloatArray(rate / 100)
    private var filled = 0

    init { require(rate in 8_000..96_000 && rate % 100 == 0) }

    fun append(samples: FloatArray, start: Int, count: Int): Int {
        require(start >= 0 && count >= 0 && start <= samples.size - count)
        for (i in start until start + count) {
            if (!samples[i].isFinite()) {
                reference?.rejectPcm()
                throw LiveAudioStartException(LiveAudioStartIssue.EchoUnavailable)
            }
        }
        var offset = start
        var written = 0
        while (offset < start + count) {
            checkRunning()
            val n = minOf(block.size - filled, start + count - offset)
            for (i in 0 until n) block[filled + i] = samples[offset + i].coerceIn(-1f, 1f)
            filled += n; offset += n
            if (filled == block.size) {
                written += writeBlock(filled)
                filled = 0
            }
        }
        return written
    }

    fun finish(): Int {
        if (filled == 0) return 0
        val written = writeBlock(filled)
        filled = 0
        return written
    }

    private fun writeBlock(count: Int): Int {
        checkRunning()
        val pending = reference?.beginWrite()
        try {
            reference?.analyze(block, 0, count)
            var offset = 0
            while (offset < count) {
                checkRunning()
                val written = track.write(block, offset, count - offset, AudioTrack.WRITE_BLOCKING)
                check(written > 0) { "AudioTrack PCM write failed ($written)" }
                reference?.accepted(block, offset, written)
                offset += written
            }
            return offset
        } finally { pending?.close() }
    }
}
