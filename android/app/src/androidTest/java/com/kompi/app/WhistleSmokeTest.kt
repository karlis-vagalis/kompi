package com.kompi.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kompi.app.audio.whistle.WhistleBackend
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device smoke test using a caller-provided public 16 kHz mono PCM16 WAV sample. */
@RunWith(AndroidJUnit4::class)
class WhistleSmokeTest {
    @Test fun officialSampleProducesExpectedWordsLocally() {
        assumeTrue("Whistle smoke test requires arm64-v8a", WhistleBackend.isSupportedOnThisDevice())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val model = File(instrumentation.targetContext.filesDir, "models/whistle/whistle.cact")
        val samplePath = args.getString("whistleSamplePath")
        val expectedText = args.getString("whistleExpectedText")?.trim()?.lowercase()
        assumeTrue("Install the Whistle model under app-private files before this smoke test", model.isFile && model.length() == 16_919_407L)
        assumeTrue("Supply whistleSamplePath and whistleExpectedText instrumentation arguments", !samplePath.isNullOrBlank() && !expectedText.isNullOrBlank())

        val sampleFile = File(samplePath!!)
        assumeTrue("Sample WAV path is unreadable by the app process: $sampleFile", sampleFile.isFile && sampleFile.canRead())
        val pcm = decodeWav(sampleFile.readBytes())
        require(pcm.size in 1..(30 * 16_000)) { "Smoke sample must be 1-30 seconds of PCM" }

        val transcript = WhistleBackend.load(model).transcribeJson(pcm, language = "en")
        assertTrue("Expected '$expectedText' in local transcript '${transcript.text}'", transcript.text.lowercase().contains(expectedText!!))
        assertTrue("Expected English language code, got ${transcript.language}", transcript.language == "en")
    }

    private fun decodeWav(bytes: ByteArray): FloatArray {
        require(bytes.size >= 44) { "WAV is too short" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun fourCc(offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)
        require(fourCc(0) == "RIFF" && fourCc(8) == "WAVE") { "Expected RIFF/WAVE" }
        var offset = 12
        var sampleRate = 0
        var channels = 0
        var encoding = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataLength = 0
        while (offset + 8 <= bytes.size) {
            val id = fourCc(offset)
            val length = buffer.getInt(offset + 4).toLong() and 0xffff_ffffL
            val body = offset + 8
            require(length <= bytes.size - body) { "Truncated WAV chunk $id" }
            when (id) {
                "fmt " -> {
                    require(length >= 16) { "Invalid WAV format chunk" }
                    encoding = buffer.getShort(body).toInt() and 0xffff
                    channels = buffer.getShort(body + 2).toInt() and 0xffff
                    sampleRate = buffer.getInt(body + 4)
                    bitsPerSample = buffer.getShort(body + 14).toInt() and 0xffff
                }
                "data" -> { dataOffset = body; dataLength = length.toInt() }
            }
            offset = body + length.toInt() + (length.toInt() and 1)
        }
        require(encoding == 1 && channels == 1 && sampleRate == 16_000 && bitsPerSample == 16) {
            "WAV must be signed PCM16, mono, and 16 kHz"
        }
        require(dataOffset >= 0 && dataLength > 0 && dataLength % 2 == 0) { "WAV has no aligned PCM data" }
        val samples = FloatArray(dataLength / 2)
        for (i in samples.indices) samples[i] = buffer.getShort(dataOffset + i * 2) / 32768.0f
        return samples
    }
}
