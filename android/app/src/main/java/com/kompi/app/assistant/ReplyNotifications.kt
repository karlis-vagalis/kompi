package com.kompi.app.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.kompi.app.assistant.push.PushHostId
import com.kompi.app.protocol.generated.PUSH_MAX_FUTURE_SKEW_MS
import com.kompi.app.protocol.generated.REPLY_NOTIFICATION_TTL_MS
import com.kompi.app.protocol.generated.ReplyNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

internal data class ReplyNoticeTarget private constructor(val hostId: PushHostId, val conversationId: String) {
    companion object {
        fun parse(hostId: PushHostId, conversationId: String?): ReplyNoticeTarget? {
            if (conversationId == null || conversationId.length != 36) return null
            val id = runCatching { UUID.fromString(conversationId) }.getOrNull() ?: return null
            if (id.toString() != conversationId) return null
            return ReplyNoticeTarget(hostId, conversationId)
        }
    }
}

internal class ReplyNotice private constructor(val target: ReplyNoticeTarget, val completedAtMs: Long) {
    companion object {
        fun parse(hostId: PushHostId, reply: ReplyNotification): ReplyNotice? {
            val target = ReplyNoticeTarget.parse(hostId, reply.conversationId) ?: return null
            val now = System.currentTimeMillis()
            if (reply.completedAtMs <= now - REPLY_NOTIFICATION_TTL_MS || reply.completedAtMs > now + PUSH_MAX_FUTURE_SKEW_MS) return null
            return ReplyNotice(target, reply.completedAtMs)
        }
    }
}

internal object ReplyNotifications {
    const val PREFERENCE = "replyNotificationsEnabled"
    private const val LAST_VISIBLE = "lastVisibleAtMs"
    private val visible = mutableSetOf<Activity>()
    private fun visibilityPrefs(context: Context) = context.getSharedPreferences("reply-visibility", Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getBoolean(PREFERENCE, true)

    @Synchronized
    fun visibilityChanged(activity: Activity, isVisible: Boolean) {
        if (isVisible) visible.add(activity) else visible.remove(activity)
        // Also suppress notices delayed until after a foreground visit. Do not infer
        // a completion from cached request/snapshot metadata on app start.
        visibilityPrefs(activity).edit().putLong(LAST_VISIBLE, System.currentTimeMillis()).apply()
    }

    @Synchronized
    fun mayPost(context: Context, notice: ReplyNotice): Boolean = enabled(context) && visible.isEmpty() &&
        notice.completedAtMs > visibilityPrefs(context).getLong(LAST_VISIBLE, 0L)

    /** Serialize the final Android side effect with visibility changes, not push or DB work. */
    @Synchronized
    fun postIfBackground(context: Context, notice: ReplyNotice, post: () -> Unit): Boolean {
        if (!mayPost(context, notice)) return false
        post()
        return true
    }
}

/** Input may only come from our non-exported notification navigation Activity. */
internal object ReplyNoticeNavigation {
    const val HOST_EXTRA = "com.kompi.app.notification.REPLY_HOST"
    const val CONVERSATION_EXTRA = "com.kompi.app.notification.REPLY_CONVERSATION"
    class Tap(val target: ReplyNoticeTarget)

    private val pending = MutableStateFlow<Tap?>(null)
    val target = pending.asStateFlow()

    fun fromNotification(intent: Intent): ReplyNoticeTarget? {
        val host = PushHostId.parse(intent.getStringExtra(HOST_EXTRA).orEmpty()) ?: return null
        return ReplyNoticeTarget.parse(host, intent.getStringExtra(CONVERSATION_EXTRA))
    }

    fun open(target: ReplyNoticeTarget) { pending.value = Tap(target) }
    fun consume(tap: Tap) { pending.compareAndSet(tap, null) }
}
