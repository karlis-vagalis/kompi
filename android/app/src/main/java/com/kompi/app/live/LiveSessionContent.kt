package com.kompi.app.live

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kompi.app.ui.KompiText
import com.kompi.app.ui.KompiUi

/** One presentation for both entry points; runtime ownership stays with the caller. */
@Composable
internal fun LiveSessionContent(
    snapshot: LiveConversationSnapshot,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onInterrupt: () -> Unit,
    onRetry: () -> Unit,
    onToggleMicrophoneMute: () -> Unit,
    onToggleOutputMute: () -> Unit,
    modifier: Modifier = Modifier,
    message: String = "",
    onOpenVoiceSettings: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxSize()) {
        if (message.isNotBlank()) KompiText(message,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = KompiUi.typography.bodySmall, color = KompiUi.colors.error)
        LiveTalkScreen(snapshot, onEnd, onStart, onInterrupt, onRetry,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            onToggleMicrophoneMute = onToggleMicrophoneMute,
            onToggleOutputMute = onToggleOutputMute,
            onOpenVoiceSettings = onOpenVoiceSettings)
    }
}
