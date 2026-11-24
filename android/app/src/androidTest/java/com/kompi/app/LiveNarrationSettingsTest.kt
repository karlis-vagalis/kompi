package com.kompi.app

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LiveNarrationSettingsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun settingsKeepIndependentPreviewsAndPersistEditsAfterRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("settings", 0)
        val prefixKey = "thinkingSpeechPrefix"
        val toolKey = "liveToolSpeechMessage"
        val previous = listOf(prefixKey, toolKey).associateWith { prefs.getString(it, null) }
        prefs.edit().putString(prefixKey, "One second, I'm").remove(toolKey).commit()
        fun openSettings() = ActivityScenario.launch<MainActivity>(
            Intent(context, MainActivity::class.java).putExtra("com.kompi.app.shortcut", "settings"),
        )
        val tool = "Let me look that up, then I'll report back."
        try {
            openSettings().use {
                val toolMessage = compose.onNodeWithContentDescription("Live tool-call message")
                val prefix = compose.onNodeWithContentDescription("Thinking title prefix")
                compose.onNodeWithText("Example: One second, I'm checking the details").performScrollTo().assertIsDisplayed()
                toolMessage.performScrollTo().performTextReplacement(tool)
                compose.onNodeWithText("Preview: $tool").performScrollTo().assertIsDisplayed()
                prefix.performScrollTo().performTextReplacement("I'm now")
                compose.onNodeWithText("Example: I'm now checking the details").performScrollTo().assertIsDisplayed()
                assertEquals("I'm now", prefs.getString(prefixKey, null))
                assertEquals(tool, prefs.getString(toolKey, null))
            }
            openSettings().use {
                compose.onNodeWithText("Preview: $tool").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Example: I'm now checking the details").performScrollTo().assertIsDisplayed()
            }
        } finally {
            prefs.edit().apply {
                previous.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) }
            }.commit()
        }
    }
}
