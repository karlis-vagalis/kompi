package com.kompi.app.attachments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.kompi.app.protocol.generated.ATTACHMENT_MAX_FILE_BYTES
import com.kompi.app.ui.KompiText as Text
import com.kompi.app.ui.KompiUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Square previews stay inside their user bubble, above its optional message text. */
@Composable
fun MessageAttachmentPreviews(attachments: List<MessageAttachment>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            val thumbnail by produceState<Bitmap?>(null, attachment) {
                value = withContext(Dispatchers.IO) {
                    when (attachment) {
                        is MessageAttachment.Local -> if (attachment.draft.isImage) decodeThumbnail(attachment.draft.cachedFile) else null
                        is MessageAttachment.Image -> decodeImage(attachment.data)
                        is MessageAttachment.File -> null
                    }
                }
            }
            Column(
                Modifier.width(136.dp).clip(RoundedCornerShape(12.dp))
                    .background(KompiUi.colors.surfaceContainerLow),
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                    if (thumbnail != null) {
                        Image(
                            bitmap = thumbnail!!.asImageBitmap(),
                            contentDescription = "Preview of ${attachment.name}",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        ChatLucideIcon(
                            if (attachment is MessageAttachment.Image || attachment is MessageAttachment.Local && attachment.draft.isImage)
                                LucideR.drawable.lucide_ic_image else LucideR.drawable.lucide_ic_file,
                            contentDescription = if (attachment is MessageAttachment.Image) "Image attachment" else "File attachment",
                            modifier = Modifier.size(32.dp),
                            tint = KompiUi.colors.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    attachment.name,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    style = KompiUi.typography.labelSmall,
                    color = KompiUi.colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun decodeImage(encoded: String): Bitmap? = runCatching {
    if (encoded.length.toLong() > (ATTACHMENT_MAX_FILE_BYTES + 2) / 3 * 4) return null
    val bytes = Base64.decode(encoded, Base64.DEFAULT)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512 && sample <= Int.MAX_VALUE / 2) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()
