package com.kompi.app

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingInputTest {
    private val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
    private val json = """{"v":1,"endpointTicket":"endpoint:ticket_123","secret":"$secret","name":"Peer One"}"""

    @Test fun acceptsQrJsonAndEncodedTokenAsSamePairing() {
        val expected = parsePairingInput(json)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
        val wrappedToken = "kompi-pair-v1:${encoded.take(12)} \n ${encoded.drop(12)}"

        assertEquals(expected, parsePairingInput(wrappedToken))
        assertEquals("Peer One", expected.name)
        assertEquals("endpoint:ticket_123", expected.endpointTicket)
    }

    @Test fun rejectsMalformedTokensAndInvalidUtf8() {
        assertThrows(IllegalArgumentException::class.java) { parsePairingInput("kompi-pair-v1:%%%") }
        val invalidUtf8 = Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(0xc3.toByte(), 0x28))
        assertThrows(IllegalArgumentException::class.java) { parsePairingInput("kompi-pair-v1:$invalidUtf8") }
    }

    @Test fun rejectsInvalidSchemaVersionSecretAndOversizedInput() {
        assertThrows(Exception::class.java) { parsePairingInput(json.replace("\"v\":1", "\"v\":2")) }
        assertThrows(Exception::class.java) { parsePairingInput(json.replace(secret, "AA")) }
        assertThrows(IllegalArgumentException::class.java) { parsePairingInput("x".repeat(8_193)) }
    }
}
