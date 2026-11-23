package com.kompi.app.transport

import com.kompi.app.protocol.generated.MAX_PI_UI_TEXT_CHARS
import com.kompi.app.protocol.generated.PendingPiUiRequest
import com.kompi.app.protocol.generated.PiUiDialog

/** Parsed user text; the editing buffer may be invalid, an outbound answer may not. */
class PiDialogText private constructor(val value: String) {
    companion object {
        fun parse(raw: String): PiDialogText {
            require(raw.length <= MAX_PI_UI_TEXT_CHARS) { "Pi answer is too large" }
            return PiDialogText(raw)
        }
    }
}

/** The generated Rust-owned wire parser has already checked every nested field. */
class PiDialogRequest private constructor(
    val conversationId: String,
    val interactionId: String,
    val question: PiUiDialog,
    val title: String,
) {
    companion object {
        fun fromWire(value: PendingPiUiRequest): PiDialogRequest {
            val title = when (val question = value.dialog) {
                is PiUiDialog.Select -> question.payload.title
                is PiUiDialog.Confirm -> question.payload.title
                is PiUiDialog.Input -> question.payload.title
                is PiUiDialog.Editor -> question.payload.title
            }
            return PiDialogRequest(value.conversationId, value.interactionId, value.dialog, title.ifBlank { "Pi question" })
        }
    }
}
