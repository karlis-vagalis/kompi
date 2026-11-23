package com.kompi.app.audio

/**
 * Serialized sample assembler, not a clock or endpoint detector. Preserve every admitted sample
 * exactly once while splitting capture/pre-roll blocks at the selected decoder's feed target.
 * A feed boundary never pads, repeats or drops microphone PCM.
 */
internal class StreamingPcmBuffer(private val targetSamples: Int) {
    init { require(targetSamples > 0) }
    private var buffer = FloatArray(targetSamples)
    private var filled = 0

    fun append(samples: FloatArray, emit: (FloatArray) -> Unit) {
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(buffer.size - filled, samples.size - offset)
            samples.copyInto(buffer, filled, offset, offset + count)
            filled += count
            offset += count
            if (filled == buffer.size) {
                val complete = buffer
                buffer = FloatArray(targetSamples)
                filled = 0
                emit(complete)
            }
        }
    }

    /** Real short tail only; finalization belongs in the same serialized inference command. */
    fun drain(): FloatArray {
        val tail = buffer.copyOf(filled)
        clear()
        return tail
    }

    fun clear() {
        buffer = FloatArray(targetSamples)
        filled = 0
    }
}
