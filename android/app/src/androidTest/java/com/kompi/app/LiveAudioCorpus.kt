package com.kompi.app

import android.content.Context
import com.kompi.app.audio.ParakeetModelInstaller
import com.kompi.app.audio.InstalledParakeetModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Public synthetic test sentences, not user recordings or expected transcripts injected into ASR. */
internal data class SpokenFixture(val id: String, val spoken: String, val requiredWords: Set<String>, val voiceId: String = "af_heart")
internal object LiveAudioCorpus {
    val fixtures = listOf(
        SpokenFixture("review", "Please review the latest changes.", setOf("please", "review", "latest", "changes")),
        SpokenFixture("interrupt", "Actually, stop and explain the error.", setOf("actually", "stop", "explain", "error")),
        SpokenFixture("continue", "Continue with the review.", setOf("continue", "review")),
        SpokenFixture("negation", "Do not run that command.", setOf("not", "run", "command")),
        SpokenFixture("review-male", "Please review the latest changes.", setOf("please", "review", "latest", "changes"), voiceId = "am_michael"),
    )
    fun model(context: Context): InstalledParakeetModel =
        ParakeetModelInstaller(File(context.filesDir, "models/parakeet")).installed()
            ?: error("Install the pinned Parakeet model on the disposable test emulator")
    fun fixture(context: Context, id: String): File =
        File(context.filesDir, "live-audio-fixtures/pcm16/$id.wav").also {
            check(it.isFile) { "Missing WAV fixture: run LiveAudioFixturePreparationTest" }
        }
    fun words(text: String): Set<String> = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase()).map { it.value }.toSet()

    fun read16kMono(file: File): FloatArray = read16kMono(file.readBytes())

    fun read16kMono(bytes: ByteArray): FloatArray {
        require(bytes.size in 44..2_000_000)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)
        require(tag(0) == "RIFF" && tag(8) == "WAVE")
        var offset = 12
        var format = 0; var channels = 0; var rate = 0; var bits = 0
        var dataOffset = -1; var dataLength = 0
        while (offset + 8 <= bytes.size) {
            val length = buffer.getInt(offset + 4)
            require(length >= 0 && length <= bytes.size - offset - 8)
            when (tag(offset)) {
                "fmt " -> {
                    require(length >= 16)
                    format = buffer.getShort(offset + 8).toInt()
                    channels = buffer.getShort(offset + 10).toInt()
                    rate = buffer.getInt(offset + 12)
                    bits = buffer.getShort(offset + 22).toInt()
                }
                "data" -> { dataOffset = offset + 8; dataLength = length }
            }
            offset += 8 + length + length % 2
        }
        require(format == 1 && channels == 1 && rate == 16_000 && bits == 16 && dataOffset >= 0 && dataLength % 2 == 0)
        return FloatArray(dataLength / 2) { buffer.getShort(dataOffset + it * 2) / 32768f }
    }

    fun writePcm16(file: File, pcm: ByteArray, sampleRate: Int) {
        require(pcm.size % 2 == 0 && sampleRate > 0)
        file.parentFile!!.mkdirs()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size)
        file.outputStream().use { it.write(header.array()); it.write(pcm) }
    }

    fun appendPcm16(output: ByteArrayOutputStream, samples: FloatArray) {
        for (sample in samples) {
            val value = (sample.coerceIn(-1f, 1f) * 32767).toInt()
            output.write(value and 255); output.write((value ushr 8) and 255)
        }
    }
}
