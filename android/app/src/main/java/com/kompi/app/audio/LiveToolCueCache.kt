package com.kompi.app.audio

import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

internal data class ToolCuePcm(val samples: FloatArray, val sampleRateHz: Int)

/** Includes the exact input to the phonemizer, not a fragment or the raw settings field. */
internal data class ToolCueSynthesisIdentity(
    val modelSha256: String,
    val delegate: String,
    val runtimeVersion: String,
    val deviceRuntime: String,
    val defaultVoice: String,
    val speakingRate: Float,
    val targetBucket: Int,
) {
    fun key(text: SynthesisText, voice: KokoroVoiceId): ToolCueKey = ToolCueKey.hash(
        "kokoro/live-tool/mono-float32-be/v1", LiveToolCueCache.SampleRateHz.toString(),
        modelSha256, delegate, runtimeVersion, deviceRuntime, defaultVoice,
        speakingRate.toRawBits().toString(), targetBucket.toString(), voice.value, text.input,
    )
}

@JvmInline
internal value class ToolCueKey private constructor(val hash: String) {
    companion object {
        fun hash(vararg fields: String): ToolCueKey {
            val digest = MessageDigest.getInstance("SHA-256")
            fields.forEach { field ->
                val bytes = field.toByteArray(Charsets.UTF_8)
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                digest.update(bytes)
            }
            val hash = digest.digest()
            return ToolCueKey(buildString(hash.size * 2) {
                val hex = "0123456789abcdef"
                for (byte in hash) {
                    val value = byte.toInt() and 0xff
                    append(hex[value ushr 4]).append(hex[value and 0xf])
                }
            })
        }
    }
}

/** App-private, bounded best-effort disk storage. Never pass thinking or reply audio here. */
internal class LiveToolCueCache(
    private val directory: File,
    private val maxBytes: Long = 8L * 1024 * 1024,
    private val maxEntries: Int = 16,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun read(key: ToolCueKey): List<ToolCuePcm>? = diskOrNull {
        prune()
        val file = fileFor(key)
        if (!file.isFile) return@diskOrNull null
        val size = file.length()
        val audio = if (size in MinFileBytes.toLong()..MaxFileBytes.toLong()) {
            val bytes = ByteArray(size.toInt())
            DataInputStream(file.inputStream().buffered()).use { stream ->
                stream.readFully(bytes)
                if (stream.read() == -1) decode(key, bytes) else null
            }
        } else null
        if (audio == null) Files.deleteIfExists(file.toPath())
        audio
    }

    fun write(key: ToolCueKey, chunks: List<ToolCuePcm>) {
        val bytes = encode(key, chunks) ?: return
        if (bytes.size > maxBytes) return
        diskOrNull {
            if (!directory.isDirectory && !directory.mkdirs()) return@diskOrNull
            val file = fileFor(key)
            prune(replacing = file, incomingBytes = bytes.size)
            val temp = File.createTempFile("cue-", ".part", directory)
            try {
                temp.writeBytes(bytes)
                // A crash can leave an ignored partial file; only complete entries are published.
                Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temp.toPath())
            }
        }
    }

    private fun fileFor(key: ToolCueKey) = File(directory, "${key.hash}.pcm")

    /** MainActivity and AssistantConversationService use the same Android process and lock. */
    private fun <T> diskOrNull(block: () -> T): T? = synchronized(DiskLock) {
        try { block() } catch (_: IOException) { null } catch (_: SecurityException) { null }
    }

    private fun prune(replacing: File? = null, incomingBytes: Int = 0) {
        val files = directory.listFiles() ?: return
        val cutoff = now() - RetentionMs
        val retained = files.filter { file ->
            when {
                file.name.endsWith(".part") -> { Files.deleteIfExists(file.toPath()); false }
                !EntryName.matches(file.name) -> false
                file.lastModified() < cutoff || file.length() !in MinFileBytes.toLong()..MaxFileBytes.toLong() -> {
                    Files.deleteIfExists(file.toPath()); false
                }
                else -> file != replacing
            }
        }.sortedBy(File::lastModified)
        var bytes = retained.sumOf(File::length) + incomingBytes
        var entries = retained.size + if (replacing == null) 0 else 1
        for (file in retained) {
            if (bytes <= maxBytes && entries <= maxEntries) break
            val size = file.length()
            Files.deleteIfExists(file.toPath())
            bytes -= size
            entries--
        }
    }

    private fun encode(key: ToolCueKey, chunks: List<ToolCuePcm>): ByteArray? {
        if (chunks.size !in 1..MaxChunks || chunks.any { !it.canCache() }) return null
        val samples = chunks.sumOf { it.samples.size.toLong() }
        if (samples > MaxSamples) return null
        val buffer = ByteBuffer.allocate(HeaderBytes + CRCBytes + (samples.toInt() + chunks.size) * Int.SIZE_BYTES)
        buffer.putInt(Magic).put(key.hash.toByteArray(Charsets.US_ASCII)).putInt(chunks.size)
        for (chunk in chunks) {
            buffer.putInt(chunk.samples.size)
            for (sample in chunk.samples) buffer.putFloat(sample)
        }
        buffer.putInt(checksum(buffer.array()))
        return buffer.array()
    }

    private fun decode(key: ToolCueKey, bytes: ByteArray): List<ToolCuePcm>? {
        val buffer = ByteBuffer.wrap(bytes)
        val payloadEnd = bytes.size - CRCBytes
        if (buffer.getInt(payloadEnd) != checksum(bytes) || buffer.int != Magic) return null
        val storedKey = ByteArray(KeyBytes).also { buffer.get(it) }
        if (!storedKey.contentEquals(key.hash.toByteArray(Charsets.US_ASCII))) return null
        val count = buffer.int
        if (count !in 1..MaxChunks) return null
        var total = 0
        val chunks = ArrayList<ToolCuePcm>(count)
        repeat(count) {
            if (payloadEnd - buffer.position() < Int.SIZE_BYTES) return null
            val samples = buffer.int
            if (samples !in 1..(MaxSamples - total) || samples > (payloadEnd - buffer.position()) / Float.SIZE_BYTES) return null
            total += samples
            val chunk = ToolCuePcm(FloatArray(samples) { buffer.float }, SampleRateHz)
            if (!chunk.canCache()) return null
            chunks += chunk
        }
        return chunks.takeIf { buffer.position() == payloadEnd }
    }

    private fun checksum(bytes: ByteArray): Int = CRC32().apply { update(bytes, 0, bytes.size - CRCBytes) }.value.toInt()

    companion object {
        const val SampleRateHz = 24_000
        const val MaxSamples = 480_000
        const val MaxChunks = 256
        internal const val RetentionMs = 24 * 60 * 60 * 1000L
        private const val Magic = 0x4b435531 // KCU1
        private const val KeyBytes = 64
        private const val CRCBytes = Int.SIZE_BYTES
        private const val HeaderBytes = Int.SIZE_BYTES + KeyBytes + Int.SIZE_BYTES
        private const val MinFileBytes = HeaderBytes + CRCBytes + Int.SIZE_BYTES + Float.SIZE_BYTES
        private const val MaxFileBytes = HeaderBytes + CRCBytes + (MaxChunks + MaxSamples) * Int.SIZE_BYTES
        private val EntryName = Regex("[a-f0-9]{64}\\.pcm")
        private val DiskLock = Any()
    }
}

private fun ToolCuePcm.canCache() = sampleRateHz == LiveToolCueCache.SampleRateHz &&
    samples.isNotEmpty() && samples.size <= LiveToolCueCache.MaxSamples && samples.all(Float::isFinite)

/** Called under the engine's runtime mutex. Cache misses still stream the first PCM immediately. */
internal class LiveToolCueAudio(
    private val disk: LiveToolCueCache,
    private val persist: (ToolCueKey, List<ToolCuePcm>) -> Unit,
) {
    enum class Hit { Ram, Disk }
    private var prepared: Pair<ToolCueKey, List<ToolCuePcm>>? = null

    fun clearRam() { prepared = null }

    fun stream(
        key: ToolCueKey?,
        onHit: (Hit) -> Unit = {},
        synthesize: () -> Flow<ToolCuePcm>,
    ): Flow<ToolCuePcm> = flow {
        if (key == null) {
            synthesize().collect { emit(it) }
            return@flow
        }
        prepared?.takeIf { it.first == key }?.let { (_, chunks) ->
            onHit(Hit.Ram)
            chunks.forEach { emit(it) }
            return@flow
        }
        withContext(Dispatchers.IO) { disk.read(key) }?.let { chunks ->
            prepared = key to chunks
            onHit(Hit.Disk)
            chunks.forEach { emit(it) }
            return@flow
        }
        var collected: MutableList<ToolCuePcm>? = mutableListOf()
        var total = 0L
        synthesize().collect { chunk ->
            if (chunk.samples.isNotEmpty()) {
                total += chunk.samples.size
                collected?.let { chunks ->
                    if (total <= LiveToolCueCache.MaxSamples && chunks.size < LiveToolCueCache.MaxChunks &&
                        chunk.sampleRateHz == LiveToolCueCache.SampleRateHz
                    ) {
                        chunks += chunk.copy(samples = chunk.samples.copyOf())
                    } else collected = null
                }
                emit(chunk)
            }
        }
        collected?.takeIf { it.isNotEmpty() && it.all(ToolCuePcm::canCache) }?.let { chunks ->
            prepared = key to chunks
            persist(key, chunks)
        }
    }
}
