package com.kompi.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseSpeechEngineTest {
    @Test fun freshAndUpgradedInstallsResolveToPackagedEngine() {
        assertEquals(listOf(SpeechInputEngine.Parakeet), SpeechInputEngine.packaged)
        for (stored in listOf(null, "whistle", "parakeet")) {
            assertEquals(SpeechInputEngine.Parakeet, SpeechInputEngine.fromStored(stored))
        }
    }
}
