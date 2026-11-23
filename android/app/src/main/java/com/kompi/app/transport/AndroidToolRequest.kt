package com.kompi.app.transport

import org.json.JSONObject

enum class AndroidToolKind {
    PhoneAction, ScreenContext, SendNotification;

    companion object {
        fun parse(value: String): AndroidToolKind = when (value) {
            "phone_action" -> PhoneAction
            "screen_context" -> ScreenContext
            "send_notification" -> SendNotification
            else -> error("Unsupported Android tool")
        }
    }
}

/** Ephemeral capability pinned to the authenticated connection that delivered it. */
class AndroidToolRequest internal constructor(
    val conversationId: String,
    val requestId: String,
    val tool: AndroidToolKind,
    val actionJson: String?,
    internal val connectionGeneration: Long,
    internal val deadlineElapsedMs: Long,
    internal val screenInvocation: Long? = null,
) {
    fun input(): JSONObject = JSONObject(actionJson ?: "{}")
}
