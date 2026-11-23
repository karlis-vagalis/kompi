package com.kompi.app.attachments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kompi.app.ui.KompiCircularProgress as CircularProgressIndicator
import com.kompi.app.ui.KompiIconButton as IconButton
import com.kompi.app.ui.KompiLinearProgressIndicator as LinearProgressIndicator
import com.kompi.app.ui.KompiOutlinedButton as OutlinedButton
import com.kompi.app.ui.KompiSurface as Surface
import com.kompi.app.ui.KompiText as Text
import com.kompi.app.ui.KompiUi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.kompi.app.chat.ChatLucideIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Transient display state; the durable draft and upload identity live in AttachmentDraftStore. */
data class AttachmentUiStatus(
    val progress: Float = 0f,
    val working: Boolean = false,
    val complete: Boolean = false,
    val error: String? = null,
)

@Composable
fun AttachmentDraftRow(
    attachment: DraftAttachment,
    status: AttachmentUiStatus,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    retryEnabled: Boolean,
    modifier: Modifier = Modifier,
    removeEnabled: Boolean = true,
) {
    val thumbnail = produceState<Bitmap?>(initialValue = null, key1 = attachment.cachedPath) {
        value = withContext(Dispatchers.IO) { decodeThumbnail(attachment.cachedFile) }
    }.value
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = KompiUi.shapes.medium,
        color = KompiUi.colors.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (attachment.isImage && thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = "Preview of ${attachment.fileName}",
                        modifier = Modifier.size(56.dp),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    ChatLucideIcon(
                        LucideR.drawable.lucide_ic_paperclip,
                        contentDescription = if (attachment.isImage) "Image attachment" else "File attachment",
                        tint = KompiUi.colors.onSurfaceVariant,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(attachment.fileName, style = KompiUi.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatAttachmentSize(attachment.length), style = KompiUi.typography.labelSmall, color = KompiUi.colors.onSurfaceVariant)
                when {
                    status.working -> {
                        LinearProgressIndicator(progress = { status.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("Uploading · ${(status.progress.coerceIn(0f, 1f) * 100).toInt()}%", style = KompiUi.typography.labelSmall, color = KompiUi.colors.onSurfaceVariant)
                    }
                    status.error != null -> {
                        Text(status.error, style = KompiUi.typography.labelSmall, color = KompiUi.colors.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        OutlinedButton(onClick = onRetry, enabled = retryEnabled, modifier = Modifier.heightIn(min = 48.dp)) {
                            ChatLucideIcon(LucideR.drawable.lucide_ic_refresh_cw, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("Retry")
                        }
                    }
                    status.complete -> Text("Ready to send", style = KompiUi.typography.labelSmall, color = KompiUi.colors.primary)
                }
            }
            if (status.working) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            IconButton(onClick = onRemove, enabled = !status.working && removeEnabled, modifier = Modifier.size(48.dp)) {
                ChatLucideIcon(LucideR.drawable.lucide_ic_x, contentDescription = "Remove ${attachment.fileName}")
            }
        }
    }
}

internal fun decodeThumbnail(file: File): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    var sampleSize = 1
    while (longest / sampleSize > 512 && sampleSize <= Int.MAX_VALUE / 2) sampleSize *= 2
    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sampleSize })
}.getOrNull()

private fun formatAttachmentSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
