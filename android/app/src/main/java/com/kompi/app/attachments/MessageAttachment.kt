package com.kompi.app.attachments

import org.json.JSONArray

/** Presentation sources only. Pi remains the authority for accepted message content. */
sealed interface MessageAttachment {
    val name: String

    data class Local(val draft: DraftAttachment) : MessageAttachment {
        override val name: String get() = draft.fileName
    }
    data class Image(val data: String, val mimeType: String) : MessageAttachment {
        override val name: String get() = "Image"
    }
    data class File(override val name: String) : MessageAttachment
}

data class UserMessagePresentation(val body: String, val attachments: List<MessageAttachment>)

/** Decode the adapter's existing Pi content without changing its original source/copy text. */
fun userMessagePresentation(content: Any?): UserMessagePresentation {
    val images = mutableListOf<MessageAttachment>()
    val sourceText = when (content) {
        is String -> content
        is JSONArray -> (0 until content.length()).mapNotNull { index ->
            val block = content.optJSONObject(index) ?: return@mapNotNull null
            when (block.optString("type")) {
                "text" -> block.optString("text")
                "image" -> {
                    val data = block.optString("data")
                    val mimeType = block.optString("mimeType")
                    if (data.isNotBlank() && mimeType.startsWith("image/")) {
                        images.add(MessageAttachment.Image(data, mimeType))
                        null
                    } else "[Image]"
                }
                else -> null
            }
        }.joinToString("\n")
        else -> ""
    }
    // Pi adds image-dimension instructions for the model. With a real image
    // tile present these are transport presentation metadata, not user prose.
    val text = if (images.isEmpty()) sourceText else sourceText.replace(imageDimensions, "").trimEnd()
    // New sends put file references before the user's text. Recognize only a
    // complete adapter header; keep malformed or ordinary user prose untouched.
    val header = "Attached files available locally:\n"
    if (text.startsWith(header)) {
        val boundary = text.indexOf("\n\n", header.length)
        val end = if (boundary < 0) text.length else boundary
        val files = text.substring(header.length, end).split('\n').map { line ->
            val match = fileLine.matchEntire(line) ?: return UserMessagePresentation(text, images)
            MessageAttachment.File(match.groupValues[1])
        }
        if (files.isNotEmpty()) return UserMessagePresentation(if (boundary < 0) "" else text.substring(boundary + 2), images + files)
    }
    // Existing Pi sessions used a file suffix. Read that layout too without
    // rewriting history or moving the user's text in the canonical transcript.
    val marker = "\n\nAttached files available locally:\n"
    val markerIndex = text.lastIndexOf(marker)
    if (markerIndex >= 0) {
        val lines = text.substring(markerIndex + marker.length).split('\n')
        val files = lines.map { line ->
            val match = fileLine.matchEntire(line) ?: return UserMessagePresentation(text, images)
            MessageAttachment.File(match.groupValues[1])
        }
        if (files.isNotEmpty()) return UserMessagePresentation(text.substring(0, markerIndex), images + files)
    }
    return UserMessagePresentation(text, images)
}

private val fileLine = Regex("^- (.+) \\(local attachment path: /[^\\r\\n\\u0000]+\\)$")
private val imageDimensions = Regex("(?m)(?:\\n\\n)?\\[Image: original [0-9]+x[0-9]+, displayed at [0-9]+x[0-9]+\\. Multiply coordinates by [0-9]+(?:\\.[0-9]+)? to map to original image\\.\\]$")

/** Transient upload stages, never persisted as conversation content. */
sealed interface AttachmentDispatchStage {
    data class Preparing(val index: Int, val count: Int) : AttachmentDispatchStage
    data class Uploading(val index: Int, val count: Int, val received: Long, val total: Long) : AttachmentDispatchStage
    data class Verifying(val index: Int, val count: Int) : AttachmentDispatchStage
    data object Sending : AttachmentDispatchStage
}

sealed interface AttachmentTransferProgress {
    data object Preparing : AttachmentTransferProgress
    data class Uploading(val received: Long, val total: Long) : AttachmentTransferProgress
    data object Verifying : AttachmentTransferProgress
}
