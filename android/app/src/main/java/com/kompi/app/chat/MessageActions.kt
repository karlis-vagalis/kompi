package com.kompi.app.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context.CLIPBOARD_SERVICE
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.kompi.app.ui.KompiText
import com.kompi.app.ui.KompiUi

/** Compact actions shown only after an explicit tap on a real chat message. */
@Composable
internal fun MessageActions(
    messageKey: String,
    source: String,
    assistantMessage: Boolean,
    modifier: Modifier = Modifier,
    readAloudReady: Boolean,
    isReading: Boolean,
    onReadAloud: ((messageKey: String, spokenText: String) -> Unit)?,
    onStopReadAloud: ((messageKey: String) -> Unit)?,
    onOpenVoiceSettings: () -> Unit,
    piDetails: com.kompi.app.transport.PiSessionStore.Details? = null,
) {
    val context = LocalContext.current
    var copied by remember(messageKey) { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ChatActionButton(
                label = "Copy message",
                onClick = {
                    val clipboard = context.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Message", source))
                    copied = true
                },
                visualSize = 48.dp,
                visualWidth = 76.dp,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    ChatLucideIcon(LucideR.drawable.lucide_ic_copy, null, Modifier.size(17.dp))
                    KompiText("Copy", style = KompiUi.typography.labelSmall, maxLines = 1, softWrap = false)
                }
            }
            val actionLabel = when {
                isReading -> "Stop reading"
                !readAloudReady || onReadAloud == null -> "Set up read aloud"
                else -> "Read aloud"
            }
            ChatActionButton(
                label = actionLabel,
                onClick = {
                    when {
                        isReading -> onStopReadAloud?.invoke(messageKey)
                        !readAloudReady || onReadAloud == null -> onOpenVoiceSettings()
                        else -> onReadAloud.invoke(
                            messageKey,
                            if (assistantMessage) assistantMarkdownSpeechText(source) else source,
                        )
                    }
                },
                enabled = true,
                visualSize = 48.dp,
                visualWidth = when {
                    isReading -> 76.dp
                    !readAloudReady || onReadAloud == null -> 88.dp
                    else -> 112.dp
                },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    ChatLucideIcon(
                        if (isReading) LucideR.drawable.lucide_ic_square_stop else LucideR.drawable.lucide_ic_volume_2,
                        null,
                        Modifier.size(17.dp),
                    )
                    KompiText(
                        if (isReading) "Stop" else if (!readAloudReady || onReadAloud == null) "Set up" else "Read aloud",
                        style = KompiUi.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            if (assistantMessage) MessagePerformanceInfo(messageKey)
            piDetails?.let { PiEntryInfo(it) }
        }
        if (copied) {
            KompiText(
                "Copied to clipboard",
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = KompiUi.typography.labelSmall,
                color = KompiUi.colors.onSurfaceVariant,
            )
        }
    }
}
