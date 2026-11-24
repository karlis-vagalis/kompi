package com.kompi.app.transport

import com.kompi.app.protocol.generated.WireFormatException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LiveFrameReaderTest {
    @Test fun backlogUsesOneNativeReadButAnIsolatedFrameNeverWaitsForAnother() = runBlocking {
        val one = byteArrayOf(0, 0, 0, 2, 10, 20)
        val reader = LiveFrameReader { one.repeatFrames(100).also { check(++reads == 1) } }
        repeat(100) { assertArrayEquals(byteArrayOf(10, 20), reader.next()) }
        val isolated = LiveFrameReader { one.also { check(++reads == 2) } }
        assertArrayEquals(byteArrayOf(10, 20), isolated.next())
    }
    private var reads = 0
    private fun ByteArray.repeatFrames(n: Int) = ByteArray(size * n) { this[it % size] }

    @Test fun splitHeadersAndBodiesSurviveShortReads() = runBlocking {
        val stream = byteArrayOf(0,0,0,2,10,20,0,0,0,1,30).iterator()
        val reader = LiveFrameReader { byteArrayOf(stream.nextByte()) }
        assertArrayEquals(byteArrayOf(10,20), reader.next())
        assertArrayEquals(byteArrayOf(30), reader.next())
    }
    @Test fun invalidLengthAndTruncatedBodyFailClosed() {
        assertThrows(WireFormatException::class.java) { runBlocking { LiveFrameReader { byteArrayOf(0,1,0,1) }.next() } }
        val chunks = listOf(byteArrayOf(0,0,0,2,1), byteArrayOf()).iterator()
        assertThrows(java.io.EOFException::class.java) { runBlocking { LiveFrameReader { chunks.next() }.next() } }
    }
}
