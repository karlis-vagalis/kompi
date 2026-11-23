package com.kompi.app.assistant.native

import android.Manifest
import android.app.NotificationManager
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

internal object NativeActions {
    fun parse(name: String, input: JSONObject): NativeOperation? = try {
        if (name == "capabilities") {
            NativeArguments(input)
            NativeTask(name, "Share which native phone integrations are installed and currently permitted. No contacts, messages or location are included.") { context, _ ->
                val items = JSONArray()
                fun add(name: String, available: Boolean, setup: String) { items.put(JSONObject().put("name", name).put("available", available).put("setup", setup)) }
                add("search_contacts", hasPermission(context, Manifest.permission.READ_CONTACTS), "Contacts permission")
                add("list_calls", selectedAssistant(context) && hasPermission(context, Manifest.permission.READ_CALL_LOG), "Selected default assistant, Call history permission and installer allowlisting")
                add("search_sms", selectedAssistant(context) && hasPermission(context, Manifest.permission.READ_SMS), "Selected default assistant, SMS permission and installer allowlisting; SMS only, not RCS")
                add("list_notifications", NativeNotifications.enabled(context) && NativeNotifications.listener != null && NativeNotifications.allowed(context).isNotEmpty(), "Notification access and an enabled app allowlist")
                add("notification_actions", NativeNotifications.enabled(context) && NativeNotifications.listener != null, "Current opaque notification handle; target app must expose the action")
                add("media_sessions", NativeNotifications.enabled(context), "Notification access; active player must support the requested control")
                add("phone_status", true, "No additional permission")
                add("set_volume", true, "System volume policy; does not change silent/ringer mode")
                add("set_flashlight", hasPermission(context, Manifest.permission.CAMERA), "Camera permission and available flash hardware")
                add("vibrate", true, "Vibration hardware and system policy")
                add("get_location", hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) || hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION), "Foreground location permission and enabled location provider")
                add("set_brightness", Settings.System.canWrite(context), "Modify system settings special access")
                add("set_do_not_disturb", context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted, "Do Not Disturb special access")
                add("calendar_read", hasPermission(context, Manifest.permission.READ_CALENDAR), "Calendar read permission; local calendars only")
                add("calendar_write", hasPermission(context, Manifest.permission.READ_CALENDAR) && hasPermission(context, Manifest.permission.WRITE_CALENDAR), "Calendar read/write permission and writable calendar; no recurring/all-day mutations")
                add("app_usage", NativePersonalData.usageAllowed(context), "Usage access special permission")
                add("files", NativeFiles.tree(context) != null, "User-selected persisted document folder; expiring file handles")
                add("read_health", NativeHealth.granted(context), "Android 14+ Health Connect and selected read permissions; source data must be shared")
                JSONObject().put("status", "listed").put("capabilities", items).put("approval", "Availability is an Android setup snapshot, not authorization. Hardware or policy may still deny execution.")
            }
        } else NativeCommunications.parse(name, input) ?: NativeNotifications.parse(name, input) ?: NativeDevice.parse(name, input) ?: NativePersonalData.parse(name, input) ?: NativeFiles.parse(name, input) ?: NativeHealth.parse(name, input)
    } catch (error: IllegalStateException) { throw IllegalArgumentException("Invalid native action argument.", error)
    } catch (error: java.time.DateTimeException) { throw IllegalArgumentException("Invalid date or time zone.", error) }
}
