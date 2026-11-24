package com.kompi.app.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import com.kompi.app.ui.KompiText
import com.kompi.app.ui.KompiUi

@Composable
fun ReplyPendingRow() {
    KompiText("…", modifier = Modifier.clearAndSetSemantics {
        contentDescription = "Waiting for reply"
        liveRegion = LiveRegionMode.Polite
    }, color = KompiUi.colors.onSurfaceVariant)
}
