package com.kompi.app.audio

import com.google.mediapipe.tasks.audio.texttospeech.VoiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroVoiceTest {
    @Test
    fun storedVoiceIdsAreParsedAtThePreferenceBoundary() {
        assertEquals("af_heart", KokoroVoiceId.fromStored(" af_heart ")?.value)
        assertNull(KokoroVoiceId.fromStored(null))
        assertNull(KokoroVoiceId.fromStored(" "))
        assertNull(KokoroVoiceId.fromStored("voice id with spaces"))
        assertNull(KokoroVoiceId.fromStored("../external"))
    }

    @Test
    fun modelMetadataProducesReadableVoiceOptions() {
        val voice = KokoroVoice.fromModel(
            VoiceInfo("af_heart", "Heart", "en-US", 24_000, true),
            KokoroVoiceId.DEFAULT,
        )

        assertNotNull(voice)
        assertEquals("af_heart", voice?.id?.value)
        assertEquals("Heart", voice?.displayName)
        assertEquals("en-US", voice?.localeTag)
        assertTrue(voice?.languageLabel?.isNotBlank() == true)
    }

    @Test
    fun bundledVoicesAreAvailableRegardlessOfSeparateDownloadFlag() {
        val defaultVoice = KokoroVoice.fromModel(
            VoiceInfo("af_heart", "Heart", "en-US", 24_000, false),
            KokoroVoiceId.DEFAULT,
        )
        val additionalVoice = KokoroVoice.fromModel(
            VoiceInfo("af_bella", "Bella", "en-US", 24_000, false),
            KokoroVoiceId.DEFAULT,
        )

        assertNotNull(defaultVoice)
        assertNotNull(additionalVoice)
    }

    @Test
    fun nonVoiceModelResourcesAreNotPresentedAsVoices() {
        assertNull(
            KokoroVoice.fromModel(
                VoiceInfo("zh-t2s", "Chinese text table", "und", 24_000, true),
                KokoroVoiceId.DEFAULT,
            ),
        )
    }
}
