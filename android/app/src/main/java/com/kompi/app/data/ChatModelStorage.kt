package com.kompi.app.data

import android.util.Base64
import com.kompi.app.protocol.generated.ChatModelConfiguration
import com.kompi.app.protocol.generated.EnvelopeV3
import com.kompi.app.protocol.generated.ModelCatalog
import com.kompi.app.protocol.generated.WireCodec
import com.kompi.app.protocol.generated.WireMessage

/** Reuse the generated boundary codec for encrypted local model snapshots. */
internal object ChatModelStorage {
    private const val PROMPT_PREFIX = "kompi-model-prompt:"
    data class PendingContent(val text: String, val configuration: ChatModelConfiguration?, val targetConversationId: String? = null, val createIfMissing: Boolean = false)

    fun encodeConfiguration(configuration: ChatModelConfiguration): ByteArray = WireCodec.encode(
        EnvelopeV3(id = "model-selection", message = WireMessage.ModelsResult(ModelCatalog(configuration, emptyList()))),
    )

    fun decodeConfiguration(bytes: ByteArray): ChatModelConfiguration =
        ((WireCodec.decode(bytes).message as WireMessage.ModelsResult).catalog.defaultConfiguration
            ?: error("Stored model selection is missing"))

    fun encodePrompt(text: String, configuration: ChatModelConfiguration, attachmentIds: List<String>, targetConversationId: String? = null, createIfMissing: Boolean = false): String {
        val attachments = attachmentIds.takeIf { it.isNotEmpty() }
        val message = if (targetConversationId == null) {
            WireMessage.Prompt(attachments = attachments, configuration = configuration, delivery = "followUp", text = text)
        } else {
            WireMessage.ConversationPrompt(attachments = attachments, configuration = configuration, conversationId = targetConversationId,
                createIfMissing = true.takeIf { createIfMissing }, delivery = "followUp", text = text)
        }
        return PROMPT_PREFIX + Base64.encodeToString(WireCodec.encode(EnvelopeV3(id = "pending-prompt", message = message)), Base64.NO_WRAP)
    }

    fun decodePrompt(value: String): PendingContent {
        // A pre-selection outbox item retains its text, but cannot acquire a guessed model.
        if (!value.startsWith(PROMPT_PREFIX)) return PendingContent(value, null)
        return when (val message = WireCodec.decode(Base64.decode(value.removePrefix(PROMPT_PREFIX), Base64.NO_WRAP)).message) {
            is WireMessage.Prompt -> PendingContent(message.text, message.configuration)
            is WireMessage.ConversationPrompt -> PendingContent(message.text, message.configuration, message.conversationId, message.createIfMissing == true)
            else -> error("Stored message is not a prompt")
        }
    }
}
