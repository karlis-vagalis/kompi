package com.kompi.app.assistant

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.kompi.app.MainActivity
import com.kompi.app.protocol.generated.AgentNotification
import com.kompi.app.protocol.generated.NOTIFICATION_IMAGE_MAX_BYTES
import com.kompi.app.protocol.generated.NOTIFICATION_IMAGE_MAX_DIMENSION
import com.kompi.app.protocol.generated.NOTIFICATION_IMAGE_MAX_PIXELS
import com.kompi.app.protocol.generated.NotificationProgress
import com.kompi.app.protocol.generated.NotificationStyle
import com.kompi.app.protocol.generated.NotificationTarget
import com.kompi.app.protocol.generated.WireCodec
import java.io.Closeable
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.Base64

/** A generated-schema-checked presentation with immutable, bounded image bytes. */
internal class ParsedAgentNotification private constructor(
    val value: AgentNotification,
    val fingerprint: String,
    private val picture: Bitmap?,
) : Closeable {
    fun apply(builder: Notification.Builder, context: Context, host: String, requestId: String): Notification.Builder {
        builder.setContentTitle(value.title).setContentText(value.body)
        when (val style = value.rich?.style) {
            is NotificationStyle.BigText -> builder.setStyle(Notification.BigTextStyle().bigText(style.payload.text))
            is NotificationStyle.Inbox -> builder.setStyle(Notification.InboxStyle().also { inbox -> style.payload.lines.forEach(inbox::addLine) })
            is NotificationStyle.BigPicture -> builder.setStyle(Notification.BigPictureStyle().bigPicture(requireNotNull(picture)))
            null -> builder.setStyle(Notification.BigTextStyle().bigText(value.body))
        }
        value.rich?.let { rich ->
            rich.subtext?.let(builder::setSubText)
            rich.timeoutMs?.let(builder::setTimeoutAfter)
            // Public API 26+: this is always a grouped child, so only a summary may alert.
            // We never create a summary; unlike per-notification sound, this honors channel sound too.
            if (rich.silent == true) builder.setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
            when (val progress = rich.progress) {
                is NotificationProgress.Determinate -> builder.setProgress(100, progress.payload.percent.toInt(), false)
                is NotificationProgress.Indeterminate -> builder.setProgress(0, 0, true)
                null -> Unit
            }
            rich.buttons.orEmpty().forEachIndexed { index, button ->
                val intent = when (val target = button.target) {
                    is NotificationTarget.OpenKompi -> Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    is NotificationTarget.Https -> Intent(context, NotificationNavigationActivity::class.java)
                        .putExtra(NotificationNavigationActivity.URL_EXTRA, requireNotNull(NotificationNavigationActivity.safeHttps(target.payload.url)))
                }.setData(identity(host, requestId, "button-$index"))
                builder.addAction(Notification.Action.Builder(null, button.label,
                    PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
            }
        }
        return builder
    }

    override fun close() { picture?.recycle() }

    companion object {
        private val mapper = ObjectMapper().registerKotlinModule().setSerializationInclusion(JsonInclude.Include.NON_NULL)
        fun json(notification: AgentNotification): String = mapper.writeValueAsString(notification)

        fun parse(json: String): ParsedAgentNotification {
            val value = WireCodec.parseAgentNotification(json)
            require(value.title.isNotBlank() && value.body.isNotBlank())
            value.rich?.buttons.orEmpty().forEach { button ->
                require(button.label.isNotBlank())
                val target = button.target
                if (target is NotificationTarget.Https) require(NotificationNavigationActivity.safeHttps(target.payload.url) != null)
            }
            val image = (value.rich?.style as? NotificationStyle.BigPicture)?.payload?.image
            val picture = image?.let { decodePicture(it.base64) }
            // Canonical DTO content, including every rich field and immutable image, not reserialized MAC input.
            val canonical = canonical(mapper.readTree(this.json(value)))
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            return ParsedAgentNotification(value, fingerprint, picture)
        }

        fun identity(host: String, requestId: String, suffix: String): Uri = Uri.Builder()
            .scheme("kompi-agent-notification").authority(host).appendPath(requestId).appendPath(suffix).build()

        private fun canonical(node: JsonNode): JsonNode = when {
            node.isObject -> mapper.createObjectNode().also { out ->
                node.fieldNames().asSequence().sorted().forEach { key -> out.set<JsonNode>(key, canonical(node.get(key))) }
            }
            node.isArray -> mapper.createArrayNode().also { out -> node.forEach { out.add(canonical(it)) } }
            else -> node
        }

        /** Reject compressed metadata/animation before native PNG parsing can expand it. */
        private fun verifyPngContainer(bytes: ByteArray) {
            var offset = 8
            var chunks = 0
            var ended = false
            var hasPixels = false
            while (offset < bytes.size) {
                require(++chunks <= 128 && bytes.size - offset >= 12 && !ended)
                val length = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
                require(length >= 0 && length <= bytes.size - offset - 12)
                val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
                require(type in setOf("IHDR", "PLTE", "tRNS", "sRGB", "gAMA", "IDAT", "IEND"))
                val crc = CRC32().apply { update(bytes, offset + 4, length + 4) }.value
                val expectedCrc = ByteBuffer.wrap(bytes, offset + 8 + length, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
                require(crc == expectedCrc)
                if (chunks == 1) {
                    require(type == "IHDR" && length == 13)
                    val header = ByteBuffer.wrap(bytes, offset + 8, 13).order(ByteOrder.BIG_ENDIAN)
                    val width = header.int
                    val height = header.int
                    require(width in 1..NOTIFICATION_IMAGE_MAX_DIMENSION && height in 1..NOTIFICATION_IMAGE_MAX_DIMENSION && width.toLong() * height <= NOTIFICATION_IMAGE_MAX_PIXELS)
                    require((header.get().toInt() and 255) == 8)
                    require((header.get().toInt() and 255) in setOf(0, 2, 3, 4, 6))
                    require(header.get().toInt() == 0 && header.get().toInt() == 0 && header.get().toInt() in 0..1)
                } else require(type != "IHDR")
                when (type) {
                    "PLTE" -> require(length in 3..768 && length % 3 == 0)
                    "tRNS" -> require(length in 1..256)
                    "sRGB" -> require(length == 1)
                    "gAMA" -> require(length == 4)
                    "IDAT" -> hasPixels = true
                    "IEND" -> { require(length == 0 && hasPixels); ended = true }
                }
                offset += length + 12
            }
            require(ended && offset == bytes.size)
        }

        private fun decodePicture(encoded: String): Bitmap {
            // Check compressed allocation before invoking a native decoder. Never fetch a URL.
            require(encoded.length <= (NOTIFICATION_IMAGE_MAX_BYTES * 4 + 2) / 3)
            val bytes = Base64.getUrlDecoder().decode(encoded)
            require(bytes.isNotEmpty() && bytes.size <= NOTIFICATION_IMAGE_MAX_BYTES && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == encoded)
            val png = bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            val jpeg = bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
            require(png || jpeg)
            if (png) verifyPngContainer(bytes)
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outMimeType in setOf("image/png", "image/jpeg") &&
                    bounds.outWidth in 1..NOTIFICATION_IMAGE_MAX_DIMENSION && bounds.outHeight in 1..NOTIFICATION_IMAGE_MAX_DIMENSION &&
                    bounds.outWidth.toLong() * bounds.outHeight <= NOTIFICATION_IMAGE_MAX_PIXELS)
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                    inDensity = 0
                    inTargetDensity = 0
                }
                val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
                if (bitmap.width != bounds.outWidth || bitmap.height != bounds.outHeight || bitmap.allocationByteCount.toLong() > NOTIFICATION_IMAGE_MAX_PIXELS * 4L) {
                    bitmap.recycle()
                    error("Notification image exceeded its decoded budget")
                }
                bitmap.density = Bitmap.DENSITY_NONE
                return bitmap
            } catch (_: OutOfMemoryError) {
                throw IllegalArgumentException("Notification image could not fit its memory budget")
            }
        }
    }
}
