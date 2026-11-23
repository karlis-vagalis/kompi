package com.kompi.app.transport

import java.util.UUID

/** A composer is bound to one host and one ID before it can enqueue any messages. */
internal data class ConversationTarget private constructor(
    val hostId: String,
    val id: String,
    val createIfMissing: Boolean,
) {
    init { require(hostId.isNotBlank() && UUID.fromString(id).toString() == id) }

    companion object {
        fun new(hostId: String) = draft(hostId, UUID.randomUUID().toString())
        fun draft(hostId: String, id: String) = ConversationTarget(hostId, id, true)
        fun saved(hostId: String, id: String) = ConversationTarget(hostId, id, false)
    }
}
