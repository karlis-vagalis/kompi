package com.kompi.app.transport

import com.kompi.app.protocol.generated.MAX_PAYLOAD
import com.kompi.app.protocol.generated.WireFormatException

/** One reader per ordered stream. A native read returns available bytes; never wait to fill a batch. */
internal class LiveFrameReader(private val readAvailable: suspend (UInt) -> ByteArray) {
    private var bytes = byteArrayOf()
    private var pos = 0

    private suspend fun refill() {
        if (pos == bytes.size) {
            bytes = readAvailable(64u * 1024u)
            pos = 0
            if (bytes.isEmpty()) throw java.io.EOFException("Live stream closed")
        }
    }

    suspend fun next(): ByteArray {
        var size = 0
        repeat(4) {
            refill()
            size = (size shl 8) or (bytes[pos++].toInt() and 255)
        }
        if (size !in 1..MAX_PAYLOAD) throw WireFormatException("Invalid live frame length")
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            refill()
            val count = minOf(size - offset, bytes.size - pos)
            bytes.copyInto(result, offset, pos, pos + count)
            offset += count
            pos += count
        }
        return result
    }
}
