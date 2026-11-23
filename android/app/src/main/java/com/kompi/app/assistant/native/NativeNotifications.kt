package com.kompi.app.assistant.native

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class KompiNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { super.onListenerConnected(); NativeNotifications.listener = this }
    override fun onListenerDisconnected() { NativeNotifications.disconnected(this); super.onListenerDisconnected() }
    override fun onDestroy() { NativeNotifications.disconnected(this); super.onDestroy() }
}

internal object NativeNotifications {
    const val ALLOWED_PACKAGES = "nativeNotificationPackages"
    @Volatile var listener: KompiNotificationListener? = null
    private data class Notice(val item: StatusBarNotification, val fingerprint: String, val expires: Long)
    private data class Session(val token: MediaSession.Token, val label: String, val expires: Long)
    private val notices = LinkedHashMap<String, Notice>()
    private val sessions = LinkedHashMap<String, Session>()
    fun disconnected(service: KompiNotificationListener) { if (listener === service) { listener = null; synchronized(this) { notices.clear(); sessions.clear() } } }
    fun enabled(context: Context) = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    fun allowed(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getStringSet(ALLOWED_PACKAGES, emptySet()).orEmpty().toSet()
    private fun fingerprint(n: StatusBarNotification) = listOf(n.key, n.postTime, n.notification.extras.getCharSequence(Notification.EXTRA_TITLE), n.notification.extras.getCharSequence(Notification.EXTRA_TEXT), n.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT), n.notification.actions?.map { listOf(it.title, it.actionIntent, it.remoteInputs?.map { input -> input.resultKey }) }).toString()
    private fun safe(item: StatusBarNotification): Boolean {
        if (item.notification.visibility == Notification.VISIBILITY_SECRET) return false
        val content = listOf(item.notification.extras.getCharSequence(Notification.EXTRA_TITLE), item.notification.extras.getCharSequence(Notification.EXTRA_TEXT), item.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)).joinToString(" ")
        return !Regex("(?i)\\b(otp|verification|verify|authentication|security code|one.time|passcode|login code|bestätigungscode)\\b|\\b[0-9]{4,8}\\b").containsMatchIn(content)
    }
    @Synchronized private fun notice(id: String): Notice? = notices[id]?.takeIf { it.expires > SystemClock.elapsedRealtime() }
    @Synchronized private fun session(id: String): Session? = sessions[id]?.takeIf { it.expires > SystemClock.elapsedRealtime() }
    private fun current(context: Context, saved: Notice): StatusBarNotification? = listener?.activeNotifications?.firstOrNull {
        it.key == saved.item.key && it.postTime == saved.item.postTime && it.packageName in allowed(context) && safe(it) && fingerprint(it) == saved.fingerprint
    }
    fun parse(name: String, input: JSONObject): NativeOperation? = when (name) {
        "list_notifications" -> {
            val a = NativeArguments(input, "packageName", "limit")
            val filter = a.text("packageName", 255)
            val limit = a.long("limit", 1, 30, 20).toInt()
            NativeTask(name, "Share up to $limit current notifications${filter?.let { " from $it" }.orEmpty()} from the apps you enabled. Authentication-code notifications are filtered conservatively; this cannot guarantee all sensitive content is removed.") { context, _ ->
                val service = listener
                if (!enabled(context) || service == null) nativeError("notification_access_required", "Enable Kompi notification access in Phone access settings.")
                else {
                    val packages = allowed(context)
                    if (filter != null && filter !in packages) nativeError("app_not_allowed", "Enable this app under Notification apps in Phone access settings.")
                    else {
                        val items = JSONArray()
                        var omitted = 0
                        synchronized(this) {
                            notices.entries.removeAll { it.value.expires <= SystemClock.elapsedRealtime() }
                            service.activeNotifications.sortedByDescending { it.postTime }.forEach { item ->
                                if (item.packageName in packages && (filter == null || item.packageName == filter)) {
                                    if (!safe(item)) omitted++
                                    else if (items.length() < limit) {
                                        val id = UUID.randomUUID().toString()
                                        val text = item.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                                            ?: item.notification.extras.getCharSequence(Notification.EXTRA_TEXT)
                                        val record = JSONObject().put("id", id).put("packageName", item.packageName)
                                            .put("title", item.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(200).orEmpty())
                                            .put("text", text?.toString()?.take(2_000).orEmpty()).put("postedAt", item.postTime)
                                            .put("canReply", item.notification.actions?.any { action -> action.remoteInputs?.any { it.allowFreeFormInput } == true } == true)
                                            .put("dismissible", item.isClearable)
                                        if (appendBounded(items, record)) notices[id] = Notice(item, fingerprint(item), SystemClock.elapsedRealtime() + 60_000)
                                    }
                                }
                            }
                            while (notices.size > 128) notices.remove(notices.keys.first())
                        }
                        JSONObject().put("status", "listed").put("items", items).put("filteredSensitive", omitted)
                            .put("coverage", "Current notifications from enabled apps, not full message history. Action handles expire in 60 seconds.")
                    }
                }
            }
        }
        "reply_notification", "dismiss_notification", "snooze_notification" -> {
            val a = NativeArguments(input, *when (name) { "reply_notification" -> arrayOf("id", "text"); "snooze_notification" -> arrayOf("id", "minutes"); else -> arrayOf("id") })
            val id = a.text("id", 128, true)!!
            val saved = notice(id)
            val text = if (name == "reply_notification") a.text("text", 2_000, true) else null
            val minutes = if (name == "snooze_notification") a.long("minutes", 1, 1440) else 0
            val target = saved?.let { "${it.item.packageName}: ${it.item.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(200).orEmpty()}\n${it.item.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.take(500).orEmpty()}" } ?: "Expired notification. List notifications again."
            NativeTask(name, when(name) { "reply_notification" -> "Reply to $target\n\nSend exactly:\n$text"; "snooze_notification" -> "Snooze for $minutes minutes: $target"; else -> "Dismiss: $target" }) { context, _ ->
                val item = saved?.let { current(context, it) }
                if (item == null || !enabled(context)) nativeError("target_changed", "This notification changed, expired, or lost access. List notifications again and review a new request.")
                else when (name) {
                    "reply_notification" -> {
                        val candidates = item.notification.actions.orEmpty().filter { action -> action.remoteInputs?.count { it.allowFreeFormInput } == 1 }
                        if (candidates.size != 1) nativeError("reply_unavailable", "There is no single unambiguous free-text reply action.")
                        else {
                            val action = candidates.single()
                            val replyInput = action.remoteInputs.single { it.allowFreeFormInput }
                            val intent = Intent()
                            RemoteInput.addResultsToIntent(arrayOf(replyInput), intent, android.os.Bundle().apply { putCharSequence(replyInput.resultKey, text) })
                            try { action.actionIntent.send(context, 0, intent); synchronized(this) { notices.remove(id) }; JSONObject().put("status", "submitted").put("message", "Reply submitted to the app; delivery is not confirmed.") }
                            catch (_: PendingIntent.CanceledException) { nativeError("target_changed", "The app withdrew this reply action. Nothing was retried.") }
                        }
                    }
                    "dismiss_notification" -> if (!item.isClearable) nativeError("not_dismissible", "Android marks this notification as ongoing.") else { listener!!.cancelNotification(item.key); synchronized(this) { notices.remove(id) }; JSONObject().put("status", "submitted").put("message", "Dismissal requested.") }
                    else -> { listener!!.snoozeNotification(item.key, minutes * 60_000); synchronized(this) { notices.remove(id) }; JSONObject().put("status", "submitted").put("message", "Snooze requested.") }
                }
            }
        }
        "list_media_sessions" -> {
            NativeArguments(input)
            NativeTask(name, "Share active media players, their current titles, playback state and available controls.") { context, _ ->
                if (!enabled(context)) nativeError("notification_access_required", "Enable notification access for media session discovery.")
                else {
                    val items = JSONArray()
                    synchronized(this) {
                        sessions.entries.removeAll { it.value.expires <= SystemClock.elapsedRealtime() }
                        controllers(context).take(20).forEach { player ->
                            val id = UUID.randomUUID().toString()
                            val label = player.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)?.take(300).orEmpty()
                            val record = JSONObject().put("id", id).put("packageName", player.packageName).put("title", label)
                                .put("state", player.playbackState?.state ?: JSONObject.NULL).put("positionMs", player.playbackState?.position ?: JSONObject.NULL)
                                .put("supportedActions", player.playbackState?.actions ?: 0)
                            if (appendBounded(items, record)) sessions[id] = Session(player.sessionToken, "${player.packageName}: $label", SystemClock.elapsedRealtime() + 60_000)
                        }
                        while (sessions.size > 64) sessions.remove(sessions.keys.first())
                    }
                    JSONObject().put("status", "listed").put("items", items).put("handleLifetimeSeconds", 60)
                }
            }
        }
        "control_media" -> {
            val a = NativeArguments(input, "id", "command", "positionMs")
            val saved = session(a.text("id", 128, true)!!)
            val command = a.choice("command", setOf("play", "pause", "next", "previous", "stop", "seek"))
            val position = if (command == "seek") a.long("positionMs", 0, 604_800_000) else { require(!input.has("positionMs")); 0 }
            NativeTask(name, "Media $command${if(command == "seek") " to $position ms" else ""}: ${saved?.label ?: "Expired session; list players again."}") { context, _ ->
                val controller = if (saved != null && saved.expires > SystemClock.elapsedRealtime() && enabled(context)) controllers(context).firstOrNull { it.sessionToken == saved.token } else null
                val action = when(command) { "play" -> PlaybackState.ACTION_PLAY; "pause" -> PlaybackState.ACTION_PAUSE; "next" -> PlaybackState.ACTION_SKIP_TO_NEXT; "previous" -> PlaybackState.ACTION_SKIP_TO_PREVIOUS; "stop" -> PlaybackState.ACTION_STOP; else -> PlaybackState.ACTION_SEEK_TO }
                if (controller == null) nativeError("target_changed", "The player disappeared or access expired. List media sessions again.")
                else if ((controller.playbackState?.actions ?: 0) and action == 0L) nativeError("unsupported_control", "This player does not advertise that control.")
                else {
                    val controls = controller.transportControls
                    when(command) { "play" -> controls.play(); "pause" -> controls.pause(); "next" -> controls.skipToNext(); "previous" -> controls.skipToPrevious(); "stop" -> controls.stop(); else -> controls.seekTo(position) }
                    JSONObject().put("status", "submitted").put("message", "Control submitted to the player; playback completion is not confirmed.")
                }
            }
        }
        else -> null
    }
    private fun controllers(context: Context): List<MediaController> = context.getSystemService(MediaSessionManager::class.java)
        .getActiveSessions(ComponentName(context, KompiNotificationListener::class.java))
}
