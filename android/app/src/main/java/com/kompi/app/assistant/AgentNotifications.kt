package com.kompi.app.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.kompi.app.MainActivity
import com.kompi.app.R
import com.kompi.app.assistant.push.NotificationReceiptOutcome
import com.kompi.app.assistant.push.PushHostId
import com.kompi.app.assistant.push.ReplayClaim
import com.kompi.app.assistant.push.UnifiedPushReplayLedger
import com.kompi.app.protocol.generated.AgentNotification
import com.kompi.app.protocol.generated.PUSH_MAX_TTL_MS
import org.json.JSONObject

/** Public notification presentation from a paired host. Buttons navigate; they never approve tools. */
internal object AgentNotifications {
    const val PREFERENCE = "agentNotificationsEnabled"
    const val CHANNEL = "kompi_agent_messages"
    private const val LEDGER = "agent-notification-receipts"
    private const val MAX_RECEIPTS = 256

    fun enabled(context: Context): Boolean = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getBoolean(PREFERENCE, false)

    fun postRichForHost(context: Context, hostId: PushHostId, notificationId: String, expiresAtMs: Long, notification: AgentNotification): JSONObject =
        postForHost(context, hostId, notificationId, expiresAtMs, JSONObject(ParsedAgentNotification.json(notification)))

    fun postReplyForHost(context: Context, hostId: PushHostId, notificationId: String, expiresAtMs: Long, reply: com.kompi.app.protocol.generated.ReplyNotification): JSONObject {
        val notice = ReplyNotice.parse(hostId, reply)
            ?: return failure("expired", "This reply notification is invalid or expired.")
        val input = JSONObject().put("title", "Reply ready").put("body", "Open Kompi to read it.")
        return postForHost(context, hostId, notificationId, expiresAtMs, input, notice)
    }

    /** One durable, host-scoped content claim gates both live and cold-push side effects. */
    @Synchronized
    fun postForHost(context: Context, hostId: PushHostId, notificationId: String, expiresAtMs: Long, input: JSONObject, reply: ReplyNotice? = null): JSONObject {
        if (!notificationId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) return failure("invalid_request", "The notification identity is invalid.")
        val now = System.currentTimeMillis()
        if (expiresAtMs <= now) return failure("expired", "This notification expired before Android could post it.")
        val parsed = try { ParsedAgentNotification.parse(input.toString()) }
            catch (_: Exception) { return failure("invalid_request", "The notification content is invalid or exceeds a supported limit.") }
        parsed.use { presentation ->
            val ledger = UnifiedPushReplayLedger(context)
            // A live request's short dispatch deadline must not shorten cross-channel replay protection.
            val retainUntil = maxOf(expiresAtMs, now + PUSH_MAX_TTL_MS)
            val fingerprint = if (reply == null) presentation.fingerprint else "reply:${reply.target.conversationId}"
            return when (ledger.claimNotificationContent(hostId.value, notificationId, retainUntil, now, fingerprint)) {
                ReplayClaim.Claimed -> {
                    val legacyKey = "receipt:$notificationId"
                    val legacyReceipt = context.getSharedPreferences(LEDGER, Context.MODE_PRIVATE).getString(legacyKey, null)
                    if (legacyReceipt != null) {
                        // Legacy preferences have no host/content proof; never claim this content was posted.
                        val outcome = NotificationReceiptOutcome.Unknown
                        if (!ledger.completeNotification(hostId.value, notificationId, outcome)) unknown()
                        else {
                            context.getSharedPreferences(LEDGER, Context.MODE_PRIVATE).edit().remove(legacyKey).commit()
                            unknown()
                        }
                    } else {
                        val result = postParsed(context, hostId.value, notificationId, presentation, expiresAtMs, reply)
                        val outcome = when (result.optString("status")) {
                            "posted", "already_posted" -> NotificationReceiptOutcome.Posted
                            "unknown" -> NotificationReceiptOutcome.Unknown
                            else -> NotificationReceiptOutcome.NotPosted
                        }
                        if (ledger.completeNotification(hostId.value, notificationId, outcome)) result else unknown()
                    }
                }
                ReplayClaim.Duplicate -> when (ledger.notificationOutcome(hostId.value, notificationId)) {
                    NotificationReceiptOutcome.Posted -> alreadyPosted()
                    NotificationReceiptOutcome.NotPosted -> failure("not_posted", "This notification was already attempted and was not posted.")
                    else -> unknown()
                }
                ReplayClaim.Conflict -> failure("request_id_conflict", "This notification ID is already reserved without matching content proof. Nothing was changed.")
                ReplayClaim.Expired -> failure("expired", "This notification expired before Android could post it.")
                ReplayClaim.Full, ReplayClaim.StorageFailure -> failure("storage_unavailable", "The notification receipt could not be saved. Nothing was posted.")
            }
        }
    }

    /** Plain local wrapper retained for existing callers; rich content always requires a host identity. */
    @Synchronized
    fun post(context: Context, requestId: String, input: JSONObject, expiresAtMs: Long? = null): JSONObject {
        val presentation = ParsedAgentNotification.parse(input.toString())
        presentation.use {
            require(it.value.rich == null) { "Rich notifications require an authenticated host." }
            return postParsed(context, "local", requestId, it, expiresAtMs)
        }
    }

    private fun postParsed(context: Context, host: String, requestId: String, presentation: ParsedAgentNotification, expiresAtMs: Long?, reply: ReplyNotice? = null): JSONObject {
        if (reply != null && !ReplyNotifications.mayPost(context, reply)) return suppressedReply()
        if (!enabled(context)) return failure("disabled", "Enable Agent notifications in Kompi's Assistant settings.")
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled() || (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) {
            return failure("permission_required", "Allow Kompi notifications in Android settings.")
        }
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Agent notifications", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Messages sent by your paired agent"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        if (manager.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) {
            return failure("channel_disabled", "Enable the Agent notifications channel in Android settings.")
        }
        val ledger = context.getSharedPreferences(LEDGER, Context.MODE_PRIVATE)
        val receiptKey = "receipt:$host:$requestId"
        ledger.getString(receiptKey, null)?.let { receipt -> return if (receipt.endsWith(":posted")) alreadyPosted() else unknown() }
        val now = System.currentTimeMillis()
        val recent = ledger.getString("recent", "").orEmpty().split(',').mapNotNull(String::toLongOrNull)
            .filter { it <= now && now - it < 60_000L }.takeLast(5)
        if (recent.size >= 5) return failure("rate_limited", "At most five agent notification attempts are allowed per minute.")
        val edit = ledger.edit().putString(receiptKey, "$now:posting").putString("recent", (recent + now).joinToString(","))
        ledger.all.entries.filter { it.key.startsWith("receipt:") }
            .sortedBy { (it.value as? String)?.substringBefore(':')?.toLongOrNull() ?: 0L }
            .take(maxOf(0, ledger.all.keys.count { it.startsWith("receipt:") } - MAX_RECEIPTS + 1)).forEach { edit.remove(it.key) }
        if (!edit.commit()) return failure("storage_unavailable", "The notification receipt could not be saved. Nothing was posted.")
        val openIntent = if (reply == null) {
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } else {
            Intent(context, NotificationNavigationActivity::class.java)
                .putExtra(ReplyNoticeNavigation.HOST_EXTRA, reply.target.hostId.value)
                .putExtra(ReplyNoticeNavigation.CONVERSATION_EXTRA, reply.target.conversationId)
        }
        val open = PendingIntent.getActivity(context, 0,
            openIntent.setData(ParsedAgentNotification.identity(host, requestId, "open")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val rich = presentation.value.rich
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground).setContentIntent(open).setAutoCancel(true)
            .setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setGroup("kompi-agent:$host:${rich?.group ?: "messages"}").setCategory(Notification.CATEGORY_MESSAGE)
        val notification = try { presentation.apply(builder, context, host, requestId).build() }
            catch (_: Exception) {
                ledger.edit().remove(receiptKey).commit()
                return failure("invalid_request", "Android could not prepare this notification.")
            }
        if (expiresAtMs != null && expiresAtMs <= System.currentTimeMillis()) {
            ledger.edit().remove(receiptKey).commit()
            return failure("expired", "This notification expired before Android could post it.")
        }
        // Only this namespace can be replaced. It cannot collide with a Stop/foreground notice or another host.
        val tag = if (rich?.tag != null) "kompi-agent:$host:tag:${rich.tag}" else "kompi-agent:$host:id:$requestId"
        return try {
            if (reply == null) manager.notify(tag, 1, notification)
            else if (!ReplyNotifications.postIfBackground(context, reply) { manager.notify(tag, 1, notification) }) {
                ledger.edit().remove(receiptKey).commit()
                return suppressedReply()
            }
            if (ledger.edit().putString(receiptKey, "$now:posted").commit()) {
                JSONObject().put("status", "posted").put("message", "Posted to Android. This does not confirm the user saw or heard it.")
            } else unknown()
        } catch (_: SecurityException) {
            ledger.edit().remove(receiptKey).commit()
            failure("permission_required", "Android refused the notification. Check Kompi's notification permission.")
        } catch (_: RuntimeException) { unknown() }
    }

    private fun suppressedReply() = failure("suppressed", "The reply notification is off or Kompi has been visible since the reply finished.")
    private fun alreadyPosted() = JSONObject().put("status", "already_posted")
        .put("message", "Android accepted this notification earlier. User visibility is not confirmed.")
    private fun unknown() = failure("unknown", "A notification attempt may have succeeded. Do not resend automatically.")
    private fun failure(status: String, message: String) = JSONObject().put("status", status).put("message", message)
}
