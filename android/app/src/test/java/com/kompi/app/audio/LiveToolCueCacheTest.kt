package com.kompi.app.audio

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiveToolCueCacheTest {
    @get:Rule val temp = TemporaryFolder()
    private val voice = KokoroVoiceId.DEFAULT
    private val otherVoice = requireNotNull(KokoroVoiceId.fromStored("af_bella"))
    private val config = ToolCueSynthesisIdentity("abc", "CPU", "1.2.0", "fixture", voice.value, 1f, 128)
    private val first = requireNotNull(SynthesisText.parse("Checking a few things.", 200))
    private val edited = requireNotNull(SynthesisText.parse("Checking a few things, then I'll report back.", 200))
    private val key = config.key(first, voice)
    private val chunks = listOf(ToolCuePcm(floatArrayOf(0.1f, 0.2f), 24_000), ToolCuePcm(floatArrayOf(-0.3f), 24_000))
    private var syntheses = 0
    private fun synthesize() = flow {
        syntheses++
        chunks.forEach { emit(it) }
    }
    private fun audio(dir: File): LiveToolCueAudio {
        val disk = LiveToolCueCache(dir)
        return LiveToolCueAudio(disk, disk::write)
    }
    private fun assertAudio(actual: List<ToolCuePcm>) {
        assertEquals(chunks.size, actual.size)
        chunks.zip(actual).forEach { (want, got) ->
            assertEquals(want.sampleRateHz, got.sampleRateHz)
            assertArrayEquals(want.samples, got.samples, 0f)
        }
    }

    @Test fun ramAndReopenedDiskReplayPcmWithoutSynthesis() = runBlocking {
        val dir = temp.newFolder()
        val original = audio(dir)
        val hits = mutableListOf<LiveToolCueAudio.Hit>()
        assertAudio(original.stream(key, hits::add, ::synthesize).toList())
        assertAudio(original.stream(key, hits::add, ::synthesize).toList())
        assertAudio(audio(dir).stream(key, hits::add, ::synthesize).toList())
        assertEquals(listOf(LiveToolCueAudio.Hit.Ram, LiveToolCueAudio.Hit.Disk), hits)
        assertEquals(1, syntheses)
        assertTrue(dir.listFiles()!!.single().name.matches(Regex("[a-f0-9]{64}\\.pcm")))
    }

    @Test fun eachOutputAffectingChangeMissesBothTiers() = runBlocking {
        val dir = temp.newFolder()
        val prepared = audio(dir)
        prepared.stream(key, synthesize = ::synthesize).toList()
        val keys = listOf(
            config.key(edited, voice), config.key(first, otherVoice),
            config.copy(modelSha256 = "different").key(first, voice),
            config.copy(delegate = "GPU").key(first, voice),
            config.copy(runtimeVersion = "2").key(first, voice),
            config.copy(deviceRuntime = "updated").key(first, voice),
            config.copy(defaultVoice = otherVoice.value).key(first, voice),
            config.copy(speakingRate = 1.25f).key(first, voice),
            config.copy(targetBucket = 64).key(first, voice),
        )
        for (other in keys) prepared.stream(other, { fail("Got stale cached PCM") }, ::synthesize).toList()
        assertEquals(1 + keys.size, syntheses)
        audio(dir).stream(key, synthesize = { error("Original audio should still be cached") }).toList()
        Unit
    }

    @Test fun nonToolSpeechSynthesizesEvenWhenIdenticalToolPcmIsAvailable() = runBlocking {
        val dir = temp.newFolder()
        val prepared = audio(dir)
        prepared.stream(key, synthesize = ::synthesize).toList()
        // No typed cache key is supplied for thinking, replies, or previews. The output is
        // deliberately identical so a regression to matching by content would be visible.
        for (instance in listOf(prepared, audio(dir))) {
            instance.stream(null, { fail("Non-tool speech used cached PCM") }, ::synthesize).toList()
        }
        assertEquals(3, syntheses)
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test fun corruptTruncatedAndMisnamedEntriesFallBackAndAreReplaced() = runBlocking {
        val dir = temp.newFolder()
        val other = config.key(edited, voice)
        audio(dir).stream(key, synthesize = ::synthesize).toList()
        val file = dir.listFiles()!!.single()
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf().apply { this[lastIndex - 4] = (this[lastIndex - 4].toInt() xor 1).toByte() })
        assertAudio(audio(dir).stream(key, { fail("Corrupt cache hit") }, ::synthesize).toList())
        file.writeBytes(bytes.copyOf(bytes.size - 1))
        File(dir, "abandoned.part").writeBytes(bytes)
        assertAudio(audio(dir).stream(key, { fail("Truncated cache hit") }, ::synthesize).toList())
        File(dir, "${other.hash}.pcm").writeBytes(bytes)
        assertAudio(audio(dir).stream(other, { fail("Misnamed cache hit") }, ::synthesize).toList())
        assertEquals(4, syntheses)
        assertEquals(2, dir.listFiles()!!.size)
    }

    @Test fun diskUnavailableOrUncacheableAudioDoesNotPreventPlayback() = runBlocking {
        val unwritable = temp.newFile() // Not a directory.
        assertAudio(audio(unwritable).stream(key, synthesize = ::synthesize).toList())
        val dir = temp.newFolder()
        val oversized = ToolCuePcm(FloatArray(LiveToolCueCache.MaxSamples + 1), 24_000)
        val unexpectedRate = ToolCuePcm(floatArrayOf(1f), 16_000)
        val reuse = audio(dir)
        for (chunk in listOf(oversized, unexpectedRate)) {
            assertSame(chunk, reuse.stream(key, { fail("Uncacheable audio hit") }) { flow { emit(chunk) } }.toList().single())
        }
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test fun abortedSynthesisDoesNotPublishPartialAudio() = runBlocking {
        val dir = temp.newFolder()
        val reuse = audio(dir)
        val result = runCatching {
            reuse.stream(key) { flow { emit(chunks.first()); throw IllegalStateException("Fixture fault") } }.toList()
        }
        assertTrue(result.isFailure)
        reuse.stream(key, { fail("Partial cache hit") }, ::synthesize).take(1).toList()
        assertEquals(0, dir.listFiles()!!.size)
        assertAudio(reuse.stream(key, { fail("Partial cache hit") }, ::synthesize).toList())
    }

    @Test fun concurrentStoresRespectByteAndCountBudgetsAndDiscardExpiredEntries() = runBlocking {
        val dir = temp.newFolder()
        val keys = (1..40).map { ToolCueKey.hash("tool-$it") }
        val byteBudget = 300L // Each fixture file is 96 bytes: only three fit.
        keys.map { entry -> async(Dispatchers.IO) {
            val cache = LiveToolCueCache(dir, maxBytes = byteBudget, maxEntries = 4)
            cache.write(entry, chunks)
            cache.read(entry)?.let(::assertAudio) // Another writer may have evicted it.
        } }.awaitAll()
        assertEquals(3, dir.listFiles()!!.size)
        assertTrue(dir.listFiles()!!.sumOf(File::length) <= byteBudget)
        val countBound = LiveToolCueCache(dir, maxEntries = 2)
        countBound.write(key, chunks)
        assertEquals(2, dir.listFiles()!!.size)
        assertAudio(countBound.read(key)!!)
        val later = LiveToolCueCache(dir, now = { System.currentTimeMillis() + LiveToolCueCache.RetentionMs + 1000 })
        assertNull(later.read(key))
        assertEquals(0, dir.listFiles()!!.size)
    }
}
