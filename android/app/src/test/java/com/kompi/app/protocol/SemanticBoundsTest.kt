package com.kompi.app.protocol

import com.kompi.app.protocol.generated.*
import org.junit.Assert.*
import org.junit.Test

class SemanticBoundsTest {
    private fun roundTrip(message: WireMessage) = WireCodec.decode(WireCodec.encode(EnvelopeV3(id="example", message=message))).message

    @Test fun toolResultByteBoundaryAndAlternatives() {
        val ok = WireMessage.AndroidToolResponse("12345678-1234-4123-8123-123456789abc", requestId="example", success=true, resultJson="é".repeat(8192))
        assertEquals(ok, roundTrip(ok))
        assertThrows(WireFormatException::class.java) { roundTrip(ok.copy(resultJson=ok.resultJson + "a")) }
        assertThrows(WireFormatException::class.java) { roundTrip(ok.copy(resultJson=null)) }
        val failure = ok.copy(success=false, resultJson="details", errorCode="denied", errorMessage="")
        assertEquals(failure, roundTrip(failure))
        assertThrows(WireFormatException::class.java) { roundTrip(failure.copy(errorCode="")) }
    }

    @Test fun promptsAllowBlankOnlyWithAttachments() {
        val id = "12345678-1234-4123-8123-123456789abc"
        val config = ChatModelConfiguration("low", "example", "test")
        val blank = "\u0085\u00a0\t"
        val prompt = WireMessage.Prompt(listOf(id), config, "followUp", blank)
        val timed = WireMessage.TimedPrompt(listOf(id), id, config, "followUp", blank)
        assertEquals(prompt.copy(text=""), roundTrip(prompt))
        assertEquals(timed.copy(text=""), roundTrip(timed))
        assertThrows(WireFormatException::class.java) { roundTrip(prompt.copy(attachments=null)) }
        assertThrows(WireFormatException::class.java) { roundTrip(timed.copy(attachments=emptyList())) }
    }

    @Test fun syncCursorDomain() {
        for (cursor in listOf("0", "9223372036854775806", Long.MAX_VALUE.toString())) {
            val message = WireMessage.Sync(cursor)
            assertEquals(message, roundTrip(message))
        }
        for (cursor in listOf("-1", "01", "9223372036854775808")) {
            assertThrows(WireFormatException::class.java) { roundTrip(WireMessage.Sync(cursor)) }
        }
    }
}
