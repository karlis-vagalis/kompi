package com.kompi.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure input-boundary test; this does not mock or claim to exercise Android TextToSpeech. */
class TtsTextTest {
    @Test fun trimsAndBoundsSpokenText() {
        assertEquals("hello", TtsText.bounded("  hello  "))
        assertEquals("", TtsText.bounded("  \n"))
        assertEquals(TtsText.MAX_CHARS, TtsText.bounded("x".repeat(TtsText.MAX_CHARS + 1)).length)
    }
}
