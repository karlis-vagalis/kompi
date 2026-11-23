package com.kompi.app.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kompi.app.attachments.AttachmentDispatchStage
import androidx.compose.ui.text.font.FontFamily
import com.kompi.app.ui.KompiLinearProgressIndicator
import com.kompi.app.ui.KompiText
import com.kompi.app.ui.KompiUi

/** Presentation of a durable request; never stored as assistant text. */
enum class ReplyPendingPhase { Queued, Accepted, Running }

@Composable
fun ReplyPendingRow(
    phase: ReplyPendingPhase,
    connected: Boolean,
    attachmentCount: Int = 0,
    attachmentStage: AttachmentDispatchStage? = null,
) {
    val upload = (attachmentStage as? AttachmentDispatchStage.Uploading)?.takeIf { connected }
    val progress = upload?.let { (it.received.toDouble() / it.total.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f) }
    val label = when {
        !connected -> "Waiting for connection"
        attachmentStage is AttachmentDispatchStage.Preparing -> "Preparing file ${attachmentStage.index} of ${attachmentStage.count}"
        upload != null -> "Uploading file ${upload.index} of ${upload.count} · ${(progress!! * 100).toInt()}%"
        attachmentStage is AttachmentDispatchStage.Verifying -> "Verifying file ${attachmentStage.index} of ${attachmentStage.count}"
        attachmentStage == AttachmentDispatchStage.Sending -> "Sending message"
        phase == ReplyPendingPhase.Queued && attachmentCount > 0 -> "Waiting to upload $attachmentCount ${if (attachmentCount == 1) "file" else "files"}"
        phase == ReplyPendingPhase.Queued -> "Queued"
        phase == ReplyPendingPhase.Accepted -> "Waiting for response"
        else -> "> thinking"
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics {
            contentDescription = if (label == "> thinking") "Response is being generated" else label
            liveRegion = LiveRegionMode.Polite
        },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        KompiText(
            label,
            style = KompiUi.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = KompiUi.colors.onSurfaceVariant,
        )
        if (progress != null) KompiLinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
}
