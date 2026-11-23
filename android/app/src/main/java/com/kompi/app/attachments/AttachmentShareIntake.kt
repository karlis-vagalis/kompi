package com.kompi.app.attachments

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import java.security.MessageDigest

/** A share intent parsed at the exported Activity boundary; only local content URIs survive. */
sealed interface AttachmentSharePayload {
    data object NotShare : AttachmentSharePayload

    data class Share(
        val fingerprint: String,
        val uris: List<Uri>,
        val rejectedCount: Int,
        val omittedCount: Int,
    ) : AttachmentSharePayload {
        val error: String? get() = when {
            uris.isNotEmpty() -> null
            rejectedCount > 0 -> "No supported files were shared. Choose a photo or file stored on this device."
            else -> "No files attached. Share a photo or file to add it to the composer."
        }
    }
}

/**
 * Parses only ACTION_SEND/ACTION_SEND_MULTIPLE attachment payloads. ClipData is read before
 * EXTRA_STREAM so the sender's item order is retained; duplicate grants are collapsed. Reading
 * ClipData is bounded independently of its reported item count.
 */
object AttachmentShareIntake {
    private enum class Action(val wireValue: String) { Single(Intent.ACTION_SEND), Multiple(Intent.ACTION_SEND_MULTIPLE) }
    private val clipScanLimit: Int get() = AttachmentDraftStore.MAX_DRAFT_ATTACHMENTS + 1

    fun parse(intent: Intent?): AttachmentSharePayload {
        val source = intent ?: return AttachmentSharePayload.NotShare
        val action = when (source.action) {
            Intent.ACTION_SEND -> Action.Single
            Intent.ACTION_SEND_MULTIPLE -> Action.Multiple
            else -> return AttachmentSharePayload.NotShare
        }

        val accepted = LinkedHashSet<Uri>()
        val seenUris = HashSet<Uri>()
        val omittedUris = HashSet<Uri>()
        var rejected = 0
        var clipUnscannedCount = 0
        var streamsUnscannedCount = 0
        var hasMultipleStreams = false
        val maxUris = AttachmentDraftStore.MAX_DRAFT_ATTACHMENTS

        fun accept(uri: Uri) {
            if (!seenUris.add(uri)) return
            if (uri.scheme != "content" || uri.authority.isNullOrBlank()) {
                rejected++
            } else if (accepted.size < maxUris) {
                accepted.add(uri)
            } else {
                omittedUris.add(uri)
            }
        }

        val clip = try { source.clipData } catch (_: RuntimeException) { rejected++; null }
        if (clip != null) try {
            val scanned = minOf(clip.itemCount, clipScanLimit)
            for (index in 0 until scanned) clip.getItemAt(index).uri?.let(::accept)
            if (clip.itemCount > scanned) clipUnscannedCount = clip.itemCount - scanned
        } catch (_: RuntimeException) {
            rejected++
        }

        // IntentCompat selects the type-safe API on API 33+ and the legacy API on API 26–32.
        // Malformed/cross-app Parcelable extras are untrusted input, not an Activity crash.
        try {
            when (action) {
                Action.Single -> IntentCompat.getParcelableExtra(source, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let(::accept)
                Action.Multiple -> {
                    val streams = IntentCompat.getParcelableArrayListExtra(source, Intent.EXTRA_STREAM, Uri::class.java)
                    if (streams != null) {
                        hasMultipleStreams = true
                        val scanned = minOf(streams.size, clipScanLimit)
                        for (index in 0 until scanned) streams[index]?.let(::accept)
                        if (streams.size > scanned) streamsUnscannedCount = streams.size - scanned
                    }
                }
            }
        } catch (_: RuntimeException) {
            rejected++
        }

        val unscannedCount = if (action == Action.Multiple && clip != null && hasMultipleStreams) {
            // Android senders commonly mirror one selection in both fields; coalesce that tail.
            maxOf(clipUnscannedCount, streamsUnscannedCount)
        } else clipUnscannedCount + streamsUnscannedCount
        val uris = accepted.toList()
        return AttachmentSharePayload.Share(
            fingerprint = fingerprint(action.wireValue, uris),
            uris = uris,
            rejectedCount = rejected,
            omittedCount = omittedUris.size + unscannedCount,
        )
    }

    private fun fingerprint(action: String, uris: List<Uri>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(action.toByteArray(Charsets.UTF_8))
        digest.update(0)
        uris.forEach { uri ->
            digest.update(uri.toString().toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

data class AttachmentShareReceipt(
    val fingerprint: String,
    val selection: AttachmentSelectionResult?,
    val message: String?,
)

/** Import feedback is kept separate from the durable drafts so a UI can announce partial failures. */
fun AttachmentSharePayload.Share.toReceipt(selection: AttachmentSelectionResult?): AttachmentShareReceipt {
    val messages = buildList {
        error?.let(::add)
        selection?.message?.let(::add)
        if (rejectedCount > 0 && uris.isNotEmpty()) {
            add("$rejectedCount ${if (rejectedCount == 1) "item was" else "items were"} skipped because only shared on-device files can be attached.")
        }
        if (omittedCount > 0) {
            add("$omittedCount ${if (omittedCount == 1) "item was" else "items were"} omitted. You can attach up to ${AttachmentDraftStore.MAX_DRAFT_ATTACHMENTS} files per message.")
        }
    }
    return AttachmentShareReceipt(fingerprint, selection, messages.joinToString(" ").ifBlank { null })
}

private fun MessageDigest.update(value: Int) = update(byteArrayOf(value.toByte()))
