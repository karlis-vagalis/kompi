package com.kompi.app.protocol

import com.kompi.app.protocol.generated.*
import org.junit.Assert.*
import org.junit.Test

class MultiSessionWireTest {
    @Test fun sessionPageAndEmptyDirectoryRoundTrip() {
        for (message in listOf(WireMessage.SessionPage(limit = 16), WireMessage.SessionPageResult(sessions = emptyList()))) {
            val envelope = EnvelopeV3(id = "directory", message = message)
            assertEquals(envelope, WireCodec.decode(WireCodec.encode(envelope)))
        }
    }

    @Test fun rejectsZeroPageSize() {
        assertThrows(WireFormatException::class.java) {
            WireCodec.encode(EnvelopeV3(id = "directory", message = WireMessage.SessionPage(limit = 0)))
        }
    }
}
