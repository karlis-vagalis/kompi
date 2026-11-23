package com.kompi.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.tasks.audio.texttospeech.SynthesisOptions
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeech
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeechOptions
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.kompi.app.audio.KokoroModelInstaller
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

/** Explicit opt-in corpus preparation through the actual selected CPU Kokoro model. */
@RunWith(AndroidJUnit4::class)
class LiveAudioFixturePreparationTest {
    @Test fun generateRealSpeechFixturesFromPinnedKokoro() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installer = KokoroModelInstaller(File(context.filesDir, "kokoro-models"))
        val asset = installer.selectedAsset()
        val model = installer.installed(asset) ?: error("Pinned Kokoro model missing; run the preparation recipe")
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            withTimeout(180_000) { withContext(dispatcher) {
                val engine = TextToSpeech.create(TextToSpeechOptions(
                    baseOptions = BaseOptions.builder().setModelAssetPath(model.absolutePath).setDelegate(Delegate.CPU).build(),
                    defaultVoiceId = "af_heart", speakingRate = 1f, targetBucket = 128,
                    cacheDirectory = File(context.cacheDir, "kokoro-test-cache").apply { mkdirs() }.absolutePath,
                ))
                try {
                    val voices = engine.listVoices().map { it.voiceId }.toSet()
                    for (fixture in LiveAudioCorpus.fixtures) {
                        check(fixture.voiceId in voices) { "Pinned model lacks required corpus voice ${fixture.voiceId}" }
                        val pcm = ByteArrayOutputStream()
                        var rate = 0
                        var nonzero = 0L
                        engine.synthesizeStream(fixture.spoken, SynthesisOptions(voiceId = fixture.voiceId)).collect { chunk ->
                            if (rate == 0) rate = chunk.sampleRateHz
                            assertEquals(rate, chunk.sampleRateHz)
                            nonzero += chunk.samples.count { kotlin.math.abs(it) > 0.001f }
                            LiveAudioCorpus.appendPcm16(pcm, chunk.samples)
                        }
                        assertTrue("Kokoro must generate actual non-silent audio for ${fixture.id}", nonzero > 1000)
                        assertTrue(pcm.size() > rate)
                        LiveAudioCorpus.writePcm16(File(context.filesDir, "live-audio-fixtures/generated/${fixture.id}.wav"), pcm.toByteArray(), rate)
                        println("REAL_AUDIO_FIXTURE id=${fixture.id} rate=$rate frames=${pcm.size() / 2} nonzero=$nonzero")
                    }
                } finally { engine.close() }
            } }
        } finally { dispatcher.close() }
    }
}
