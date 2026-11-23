package com.kompi.app.protocol

import com.kompi.app.protocol.generated.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WireVectorsTest {
    private val fixtures = JSONObject(File("../../protocol/test-vectors/v4.json").readText())
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun sharedValidVectorsRoundTrip() {
        val valid = fixtures.getJSONArray("valid")
        for (i in 0 until valid.length()) {
            val vector = valid.getJSONObject(i)
            val expected = vector.getJSONObject("message")
            val bytes = hex(vector.getString("payloadHex"))
            val decoded = WireCodec.decode(bytes)
            assertEquals(expected.getInt("v"), decoded.v)
            assertEquals(expected.getString("id"), decoded.id)
            assertEquals(expected.getJSONObject("message").getString("type"), decoded.message.type)
            assertEquals(decoded, WireCodec.decode(WireCodec.encode(decoded)))
            assertArrayEquals(bytes, FrameCodec.payload(hex(vector.getString("frameHex"))))
        }
    }

    @Test fun sharedInvalidVectorsAreRejected() {
        val invalid = fixtures.getJSONArray("invalid")
        for (i in 0 until invalid.length()) {
            val vector = invalid.getJSONObject(i)
            val expected = if (vector.getString("name") == "invalid-utf8") java.nio.charset.CharacterCodingException::class.java else WireFormatException::class.java
            assertThrows(vector.getString("name"), expected) {
                WireCodec.decode(hex(vector.getString("payloadHex")))
            }
        }
    }
}
