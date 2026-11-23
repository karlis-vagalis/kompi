package com.kompi.app.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kompi.app.chat.ChatLucideIcon
import com.composables.icons.lucide.R as LucideR

/** Shared conversation chrome for app and system-assistant entry points. */
@Composable
internal fun ConversationHeader(
    peerId: String?, hostName: String?, state: String,
    session: SessionResetDeadline?, onBack: (() -> Unit)? = null,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
        .heightIn(min = 56.dp).padding(horizontal = 12.dp)) {
        if(onBack != null) KompiIconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).size(48.dp)) {
            ChatLucideIcon(LucideR.drawable.lucide_ic_arrow_left, contentDescription = "Back")
        }
        ConnectionStatusPill(peerId, hostName, state, session,
            Modifier.align(Alignment.Center).widthIn(max = (maxWidth - 112.dp).coerceAtLeast(48.dp)))
    }
}
