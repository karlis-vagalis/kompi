package com.kompi.app.assistant.actions

import android.app.Activity
import android.app.KeyguardManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.telephony.PhoneNumberUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Executes only parsed allowlisted actions. Production calls enter through executeGranted. */
object PhoneActionExecutor {
    internal suspend fun executeGranted(activity: Activity, action: PhoneAction, authorizationError: () -> JSONObject?): JSONObject {
        val guard = PhoneExecutionGuard(
            foregroundError = { foregroundError(activity, activity) },
            authorizationError = authorizationError,
        )
        guard.denial()?.let { return it }
        if (action !is PhoneAction.Native) return execute(activity, activity, action, confirmed = true)
        val result = withContext(guard) { action.operation.execute(activity, activity) }
        if (result.optString("status") !in setOf("listed", "read", "performed", "submitted")) return result
        if (guard.denial() != null) return error("unknown", "Phone access or context changed during execution. Private results were withheld; an operation may already have happened. Do not retry automatically.")
        return result
    }

    internal fun foregroundError(context: Context, activity: Activity?): JSONObject? {
        val host = activity ?: return error("foreground_required", "A foreground screen is required for this action.")
        val lifecycleOwner = host as? LifecycleOwner
        if (host.isFinishing || host.isDestroyed || lifecycleOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true) {
            return error("foreground_required", "A foreground screen is required for this action.")
        }
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard?.isKeyguardLocked != false) return error("device_locked", "Unlock the device before continuing.")
        return null
    }

    /** Fixed handoffs return listed/launched or a classified error. */
    internal fun execute(context: Context, activity: Activity?, action: PhoneAction, confirmed: Boolean): JSONObject {
        if (!confirmed) return error("confirmation_required", "Approve this action before continuing.")
        foregroundError(context, activity)?.let { return it }
        val host = requireNotNull(activity)
        if (action is PhoneAction.Native) return error("asynchronous_action", "Native phone operations require the asynchronous approved execution route.")
        if (action == PhoneAction.ListApps) return listApps(context)
        val intent = try {
            intentFor(context, action)
        } catch (_: Exception) {
            return error("unavailable", "No compatible system app is available for this action.")
        } ?: return error("unavailable", "No compatible system app is available for this action.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            host.startActivity(intent)
            val message = when (action) {
                is PhoneAction.SetAlarm -> "Clock received the alarm request; saved state could not be verified."
                is PhoneAction.SetTimer -> "Clock received the timer request; started state could not be verified."
                else -> "Opened the system interface; the action is not confirmed complete."
            }
            JSONObject().put("status", "launched").put("message", message)
        } catch (_: ActivityNotFoundException) {
            error("unavailable", "No compatible system app is available for this action.")
        } catch (_: SecurityException) {
            error("not_allowed", "The system did not allow this action.")
        } catch (_: RuntimeException) {
            error("launch_failed", "Could not open the system interface.")
        }
    }

    private fun intentFor(context: Context, action: PhoneAction): Intent? = when (action) {
        is PhoneAction.Native -> null
        PhoneAction.ListApps -> null
        is PhoneAction.OpenApp -> context.packageManager.getLaunchIntentForPackage(action.packageName)
        is PhoneAction.Navigate -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(action.query)}"))
        is PhoneAction.Dial -> Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", PhoneNumberUtils.stripSeparators(action.number), null))
        is PhoneAction.ComposeMessage -> Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", PhoneNumberUtils.stripSeparators(action.number), null)).putExtra("sms_body", action.body)
        is PhoneAction.SetAlarm -> Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, action.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, action.minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, action.label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        is PhoneAction.SetTimer -> Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, action.seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, action.label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        is PhoneAction.OpenUrl -> Intent(Intent.ACTION_VIEW, Uri.parse(action.url.value))
        is PhoneAction.ComposeEmail -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, action.recipients.map { it.value }.toTypedArray())
            putExtra(Intent.EXTRA_SUBJECT, action.subject)
            putExtra(Intent.EXTRA_TEXT, action.body)
        }
        is PhoneAction.WebSearch -> Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, action.query)
        is PhoneAction.OpenSettings -> settingsIntent(context, action.target)
    }

    private fun settingsIntent(context: Context, target: SettingsTarget): Intent = when (target) {
        SettingsTarget.Wifi -> Intent(Settings.ACTION_WIFI_SETTINGS)
        SettingsTarget.Bluetooth -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        SettingsTarget.Location -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        SettingsTarget.AppNotifications -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        SettingsTarget.AppDetails -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        SettingsTarget.Alarms -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
    }

    /** Returns only packages exposing a launcher activity; package visibility must be declared by the app. */
    private fun listApps(context: Context): JSONObject {
        return try {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = context.packageManager.queryIntentActivities(query, 0)
            .asSequence()
            .map { it.activityInfo.packageName to (it.loadLabel(context.packageManager)?.toString() ?: it.activityInfo.packageName) }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
            .take(MAX_APPS + 1)
        val array = JSONArray()
        var truncated = false
        apps.forEach { (packageName, label) ->
            if (array.length() >= MAX_APPS) {
                truncated = true
                return@forEach
            }
            array.put(JSONObject().put("packageName", packageName.take(255)).put("label", label.take(120)))
            val candidate = JSONObject().put("status", "listed").put("apps", array).put("truncated", false)
            if (candidate.toString().toByteArray(Charsets.UTF_8).size > MAX_RESULT_BYTES) {
                array.remove(array.length() - 1)
                truncated = true
            }
        }
        var result = JSONObject().put("status", "listed").put("apps", array).put("truncated", truncated)
        while (result.toString().toByteArray(Charsets.UTF_8).size > MAX_RESULT_BYTES && array.length() > 0) {
            array.remove(array.length() - 1)
            truncated = true
            result = JSONObject().put("status", "listed").put("apps", array).put("truncated", truncated)
        }
        if (result.toString().toByteArray(Charsets.UTF_8).size > MAX_RESULT_BYTES) {
            return JSONObject().put("status", "error").put("code", "listing_failed").put("message", "Could not list launcher apps.")
        }
        result
        } catch (_: RuntimeException) {
            error("listing_failed", "Could not list launcher apps.")
        }
    }

    private fun error(code: String, message: String) = JSONObject().put("status", "error").put("code", code).put("message", message)
    private const val MAX_APPS = 30
    private const val MAX_RESULT_BYTES = 14_000
}
