package com.kompi.app.assistant.actions

/** Stored by name in the trusted-computer record. Never reuse a name for broader access. */
internal enum class PhoneCapability(val label: String, val group: String) {
    Contacts("Read contact names and numbers", "Personal data"),
    CallHistory("Read call history", "Personal data"),
    Sms("Read SMS messages", "Personal data"),
    Location("Read current location", "Personal data"),
    CalendarRead("Read calendars and events", "Personal data"),
    CalendarWrite("Create, edit or delete calendar events", "Personal data"),
    Usage("Read app usage", "Personal data"),
    ScreenText("Share text from the screen where you invoked Kompi", "Personal data"),
    HealthSteps("Read Health Connect steps", "Health Connect"),
    HealthSleep("Read Health Connect sleep", "Health Connect"),
    HealthExercise("Read Health Connect exercise", "Health Connect"),
    HealthHeartRate("Read Health Connect heart rate", "Health Connect"),
    NotificationRead("Read current notifications from allowed apps", "Notifications and media"),
    NotificationReply("Send notification replies from allowed apps", "Notifications and media"),
    NotificationDismiss("Dismiss notifications from allowed apps", "Notifications and media"),
    NotificationSnooze("Snooze notifications from allowed apps", "Notifications and media"),
    MediaRead("Read active media players and titles", "Notifications and media"),
    MediaControl("Control active media players", "Notifications and media"),
    FileList("List files in the selected folder", "Documents"),
    FileRead("Read text files in the selected folder", "Documents"),
    FileWrite("Create text files in the selected folder", "Documents"),
    FileShare("Open the Android share sheet for a selected-folder file", "Documents"),
    DeviceStatus("Read battery, storage, network and next alarm status", "Device controls"),
    Volume("Change volume", "Device controls"),
    Flashlight("Control the flashlight", "Device controls"),
    Vibrate("Vibrate the phone", "Device controls"),
    Brightness("Change brightness and turn off adaptive brightness", "Device controls"),
    DoNotDisturb("Change Do Not Disturb", "Device controls"),
    AppList("List visible launcher apps", "Open apps and system screens"),
    AppLaunch("Open launcher apps", "Open apps and system screens"),
    Maps("Open a map search", "Open apps and system screens"),
    Dialer("Open the dialer with a number", "Open apps and system screens"),
    SmsDraft("Open an SMS draft", "Open apps and system screens"),
    EmailDraft("Open an email draft", "Open apps and system screens"),
    Alarm("Request alarms (Clock may save them immediately)", "Open apps and system screens"),
    Timer("Request timers (Clock may start them immediately)", "Open apps and system screens"),
    WebUrl("Open HTTPS links", "Open apps and system screens"),
    WebSearch("Open web searches", "Open apps and system screens"),
    Settings("Open allowed Android settings and alarm screens", "Open apps and system screens");

    companion object {
        fun fromStoredName(name: String): PhoneCapability? = entries.firstOrNull { it.name == name }
    }
}

internal sealed interface PhoneActionAccess {
    data object Discovery : PhoneActionAccess
    data class StandingGrant(val capability: PhoneCapability) : PhoneActionAccess
    data object Unsupported : PhoneActionAccess
}

internal fun PhoneAction.access(): PhoneActionAccess {
    val capability = when (this) {
        PhoneAction.ListApps -> PhoneCapability.AppList
        is PhoneAction.OpenApp -> PhoneCapability.AppLaunch
        is PhoneAction.Navigate -> PhoneCapability.Maps
        is PhoneAction.Dial -> PhoneCapability.Dialer
        is PhoneAction.ComposeMessage -> PhoneCapability.SmsDraft
        is PhoneAction.ComposeEmail -> PhoneCapability.EmailDraft
        is PhoneAction.SetAlarm -> PhoneCapability.Alarm
        is PhoneAction.SetTimer -> PhoneCapability.Timer
        is PhoneAction.OpenUrl -> PhoneCapability.WebUrl
        is PhoneAction.WebSearch -> PhoneCapability.WebSearch
        is PhoneAction.OpenSettings -> PhoneCapability.Settings
        is PhoneAction.Native -> return operation.access
    }
    return PhoneActionAccess.StandingGrant(capability)
}

internal fun nativeActionAccess(name: String): PhoneActionAccess {
    val capability = when (name) {
        "capabilities" -> return PhoneActionAccess.Discovery
        "search_contacts" -> PhoneCapability.Contacts
        "list_calls" -> PhoneCapability.CallHistory
        "search_sms" -> PhoneCapability.Sms
        "get_location" -> PhoneCapability.Location
        "list_calendars", "list_calendar_events" -> PhoneCapability.CalendarRead
        "create_calendar_event", "update_calendar_event", "delete_calendar_event" -> PhoneCapability.CalendarWrite
        "app_usage" -> PhoneCapability.Usage
        "list_notifications" -> PhoneCapability.NotificationRead
        "reply_notification" -> PhoneCapability.NotificationReply
        "dismiss_notification" -> PhoneCapability.NotificationDismiss
        "snooze_notification" -> PhoneCapability.NotificationSnooze
        "list_media_sessions" -> PhoneCapability.MediaRead
        "control_media" -> PhoneCapability.MediaControl
        "list_files" -> PhoneCapability.FileList
        "read_file" -> PhoneCapability.FileRead
        "save_text_file" -> PhoneCapability.FileWrite
        "share_file" -> PhoneCapability.FileShare
        "phone_status" -> PhoneCapability.DeviceStatus
        "set_volume" -> PhoneCapability.Volume
        "set_flashlight" -> PhoneCapability.Flashlight
        "vibrate" -> PhoneCapability.Vibrate
        "set_brightness" -> PhoneCapability.Brightness
        "set_do_not_disturb" -> PhoneCapability.DoNotDisturb
        else -> return PhoneActionAccess.Unsupported
    }
    return PhoneActionAccess.StandingGrant(capability)
}
