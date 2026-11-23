package com.kompi.app.transport

enum class Delivery(val wire: String) {
    FollowUp("followUp");

    companion object {
        fun parse(value: String): Delivery = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("Unsupported prompt delivery")
    }
}

enum class AckState(val wire: String) {
    Accepted("accepted"), Queued("queued"), Received("received");

    companion object {
        fun parse(value: String): AckState = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("Unsupported acknowledgement state")
    }
}

enum class ConversationState(val wire: String) {
    Active("active"), Expired("expired"), Closed("closed"), Unavailable("unavailable");

    companion object {
        fun parse(value: String): ConversationState = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("Unsupported phone conversation state")
    }
}

enum class ResultState(val wire: String) {
    Completed("completed"), Failed("failed"), Cancelled("cancelled");

    companion object {
        fun parse(value: String): ResultState = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("Unsupported result state")
    }
}

data class PhoneConversation(
    val conversationId: String,
    val state: ConversationState,
    val createdAt: String,
    val expiresAt: String,
    val idleTimeoutSeconds: Int,
    val piSessionId: String? = null,
    val sessionName: String? = null,
    val configuration: com.kompi.app.protocol.generated.ChatModelConfiguration? = null,
)
