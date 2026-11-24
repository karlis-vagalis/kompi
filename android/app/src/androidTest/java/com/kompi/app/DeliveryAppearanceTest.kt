package com.kompi.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kompi.app.chat.OutgoingMessageStatus
import org.junit.Rule
import org.junit.Test

class DeliveryAppearanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun light() = checkChat(AppTheme.Light)
    @Test fun dark() = checkChat(AppTheme.Dark)

    private fun checkChat(theme: AppTheme) {
        compose.setContent {
            ChatBehaviorSurface(appearance = theme, peer = "Studio computer", transcript = listOf(
                TranscriptLine("What should I bring to the print workshop?", true, "older"),
                TranscriptLine("Bring your sketchbook, a pencil and an apron. Paper and ink are provided.", false, "answer"),
                TranscriptLine("Can you make a packing list?", true, "user:follow-up", deliveryStatus = OutgoingMessageStatus.Sent),
                TranscriptLine("", false, "pending", waitingForReply = true),
            ))
        }
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Accepted by your computer")).assertIsDisplayed()
        compose.onNodeWithContentDescription("Waiting for reply").assertIsDisplayed()
        compose.onNodeWithText("Accepted by your computer").assertDoesNotExist()
        if (InstrumentationRegistry.getArguments().getString("captureStatus") == "true") {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            compose.activity.openFileOutput("status-${theme.name.lowercase()}.png", 0).use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        compose.onNodeWithText("Can you make a packing list?").performClick()
        compose.onNodeWithText("Message details").performClick()
        compose.onNodeWithText("Kompi doesn’t show read receipts.").assertIsDisplayed()
    }
}
