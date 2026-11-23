package com.kompi.app.assistant.native

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.provider.CalendarContract
import com.kompi.app.assistant.actions.phoneExecutionDenial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

internal object NativePersonalData {
    private data class EventTarget(val id: Long, val calendar: Long, val title: String, val start: Long, val end: Long, val zone: String, val expires: Long)
    private val targets = LinkedHashMap<String, EventTarget>()
    fun usageAllowed(context: Context): Boolean = context.getSystemService(AppOpsManager::class.java)
        .checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    fun parse(name: String, input: JSONObject): NativeOperation? = when(name) {
        "list_calendars" -> {
            NativeArguments(input)
            NativeTask(name, "Share the names and read/write access levels of local Android calendars.") { context, _ ->
                if (!hasPermission(context, Manifest.permission.READ_CALENDAR)) missingPermission("Calendar")
                else readPage(context, CalendarContract.Calendars.CONTENT_URI,
                    arrayOf("_id", "calendar_displayName", "calendar_access_level"), null, null, "_id ASC", 30, 0) { cursor ->
                    JSONObject().put("id", cursor.number("_id").toString()).put("name", cursor.text("calendar_displayName", 200))
                        .put("writable", cursor.number("calendar_access_level") >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)
                }
            }
        }
        "list_calendar_events" -> {
            val a = NativeArguments(input, "since", "until", "calendarId", "limit", "offset")
            val since = a.long("since", 0, 32_503_680_000_000L)
            val until = a.long("until", since + 1, minOf(32_503_680_000_001L, since + 31L * 86_400_000))
            val calendar = a.text("calendarId", 20)?.also { require(it.toLongOrNull()?.let { n -> n > 0 } == true) }
            val limit = a.long("limit", 1, 30, 20).toInt()
            val offset = a.long("offset", 0, 5000, 0).toInt()
            NativeTask(name, "Share up to $limit local calendar occurrences from ${Instant.ofEpochMilli(since)} until ${Instant.ofEpochMilli(until)}${calendar?.let { " in calendar $it" }.orEmpty()} (offset $offset).") { context, _ ->
                if (!hasPermission(context, Manifest.permission.READ_CALENDAR)) missingPermission("Calendar")
                else {
                    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply { ContentUris.appendId(this, since); ContentUris.appendId(this, until) }.build()
                    readPage(context, uri, arrayOf("event_id", "calendar_id", "title", "begin", "end", "eventTimezone", "eventLocation", "description", "rrule", "rdate", "original_id", "allDay"),
                        if(calendar == null) null else "calendar_id = ?", calendar?.let { arrayOf(it) }, "begin ASC, event_id ASC", limit, offset) { cursor ->
                        val record = JSONObject().put("title", cursor.text("title", 300)).put("start", cursor.number("begin")).put("end", cursor.number("end"))
                            .put("calendarId", cursor.number("calendar_id").toString()).put("timeZone", cursor.text("eventTimezone", 100))
                            .put("location", cursor.text("eventLocation", 500)).put("description", cursor.text("description", 1000)).put("allDay", cursor.number("allDay") != 0L)
                        val recurring = cursor.text("rrule", 100).isNotEmpty() || cursor.text("rdate", 100).isNotEmpty() || !cursor.isNull(cursor.getColumnIndexOrThrow("original_id"))
                        if (!recurring && cursor.number("allDay") == 0L) {
                            val handle = UUID.randomUUID().toString()
                            synchronized(this) {
                                targets.entries.removeAll { it.value.expires <= SystemClock.elapsedRealtime() }
                                targets[handle] = EventTarget(cursor.number("event_id"), cursor.number("calendar_id"), cursor.text("title", 300), cursor.number("begin"), cursor.number("end"), cursor.text("eventTimezone", 100), SystemClock.elapsedRealtime() + 120_000)
                                while(targets.size > 128) targets.remove(targets.keys.first())
                            }
                            record.put("editHandle", handle)
                        }
                        record.put("recurring", recurring)
                    }
                }
            }
        }
        "create_calendar_event" -> {
            val a = NativeArguments(input, "calendarId", "title", "start", "end", "timeZone", "description", "location")
            val calendar = a.text("calendarId", 20, true)!!.toLong().also { require(it > 0) }
            val title = a.text("title", 300, true)!!
            val start = a.long("start", 0, 32_503_680_000_000L)
            val end = a.long("end", start + 1, minOf(32_503_680_000_001L, start + 31L * 86_400_000))
            val zone = a.text("timeZone", 100, true)!!.also { ZoneId.of(it) }
            val description = a.text("description", 2000).orEmpty()
            val location = a.text("location", 500).orEmpty()
            NativeTask(name, "Create event “$title” in calendar $calendar\n${Instant.ofEpochMilli(start).atZone(ZoneId.of(zone))} – ${Instant.ofEpochMilli(end).atZone(ZoneId.of(zone))}\nLocation: $location\nDescription: $description") { context, _ ->
                if (!hasPermission(context, Manifest.permission.READ_CALENDAR) || !hasPermission(context, Manifest.permission.WRITE_CALENDAR)) missingPermission("Calendar read and write")
                else withContext(Dispatchers.IO) {
                    if (!writable(context, calendar)) nativeError("read_only_calendar", "This calendar is unavailable or read-only.")
                    else {
                        phoneExecutionDenial()?.let { return@withContext it }
                        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply {
                            put("calendar_id", calendar); put("title", title); put("dtstart", start); put("dtend", end)
                            put("eventTimezone", zone); put("description", description); put("eventLocation", location)
                        })
                        if (uri == null) nativeError("not_applied", "The calendar provider did not confirm insertion.")
                        else performed("Event created in the local Android calendar provider. Remote synchronization is not confirmed.").put("eventId", ContentUris.parseId(uri).toString())
                    }
                }
            }
        }
        "update_calendar_event", "delete_calendar_event" -> {
            val a = NativeArguments(input, *if(name == "delete_calendar_event") arrayOf("handle") else arrayOf("handle", "title", "start", "end", "timeZone"))
            val handle = a.text("handle", 128, true)!!
            val target = synchronized(this) { targets[handle]?.takeIf { it.expires > SystemClock.elapsedRealtime() } }
            val title = if(name == "update_calendar_event") a.text("title", 300) else null
            val start = if(input.has("start")) a.long("start", 0, 32_503_680_000_000L) else target?.start ?: 0
            val end = if(input.has("end")) a.long("end", start + 1, minOf(32_503_680_000_001L, start + 31L * 86_400_000)) else target?.end ?: 1
            val zone = if(name == "update_calendar_event") a.text("timeZone", 100)?.also { ZoneId.of(it) } else null
            require(end > start)
            NativeTask(name, "${if(name == "delete_calendar_event") "Delete" else "Update"} ${target?.let { "“${it.title}” (${Instant.ofEpochMilli(it.start)} – ${Instant.ofEpochMilli(it.end)}), calendar ${it.calendar}" } ?: "expired calendar target"}${if(name == "update_calendar_event") "\nNew title: ${title ?: target?.title}\nNew start/end: ${Instant.ofEpochMilli(start)} – ${Instant.ofEpochMilli(end)}\nTime zone: ${zone ?: target?.zone}" else ""}") { context, _ ->
                if (target == null || target.expires <= SystemClock.elapsedRealtime()) nativeError("target_expired", "List calendar events again and review a new request.")
                else if (!hasPermission(context, Manifest.permission.READ_CALENDAR) || !hasPermission(context, Manifest.permission.WRITE_CALENDAR)) missingPermission("Calendar read and write")
                else withContext(Dispatchers.IO) {
                    if (!writable(context, target.calendar)) nativeError("read_only_calendar", "This calendar is unavailable or read-only.")
                    else {
                        // Conditional provider mutation prevents touching a moved/renamed/recurring target.
                        val selection = "_id = ? AND calendar_id = ? AND title = ? AND dtstart = ? AND dtend = ? AND eventTimezone = ? AND (rrule IS NULL OR rrule = '') AND (rdate IS NULL OR rdate = '') AND original_id IS NULL AND deleted = 0 AND allDay = 0"
                        val args = arrayOf(target.id.toString(), target.calendar.toString(), target.title, target.start.toString(), target.end.toString(), target.zone)
                        phoneExecutionDenial()?.let { return@withContext it }
                        if (target.expires <= SystemClock.elapsedRealtime()) return@withContext nativeError("target_expired", "List calendar events again before acting on this event.")
                        val count = if(name == "delete_calendar_event") context.contentResolver.delete(CalendarContract.Events.CONTENT_URI, selection, args)
                        else context.contentResolver.update(CalendarContract.Events.CONTENT_URI, ContentValues().apply { put("title", title ?: target.title); put("dtstart", start); put("dtend", end); put("eventTimezone", zone ?: target.zone) }, selection, args)
                        synchronized(this@NativePersonalData) { targets.remove(handle) }
                        if(count == 1) performed("Local calendar event ${if(name == "delete_calendar_event") "deleted" else "updated"}. Remote synchronization is not confirmed.")
                        else nativeError("target_changed", "The original event no longer matches. Nothing was changed; list events again.")
                    }
                }
            }
        }
        "app_usage" -> {
            val a = NativeArguments(input, "since", "until", "limit")
            val since = a.long("since", 0, 32_503_680_000_000L)
            val until = a.long("until", since + 1, minOf(32_503_680_000_001L, since + 31L * 86_400_000))
            val limit = a.long("limit", 1, 30, 20).toInt()
            NativeTask(name, "Share app-usage statistics from ${Instant.ofEpochMilli(since)} until ${Instant.ofEpochMilli(until)} for up to $limit apps. Android's aggregated intervals may extend beyond these boundaries.") { context, _ ->
                if(!usageAllowed(context)) nativeError("usage_access_required", "Allow Usage access for Kompi in Phone access settings.")
                else withContext(Dispatchers.IO) {
                    val stats = context.getSystemService(UsageStatsManager::class.java).queryAndAggregateUsageStats(since, until)
                    val items = JSONArray()
                    stats.values.sortedByDescending { it.totalTimeInForeground }.take(limit).forEach {
                        appendBounded(items, JSONObject().put("packageName", it.packageName).put("foregroundMilliseconds", it.totalTimeInForeground).put("lastUsedAt", it.lastTimeUsed).put("intervalStart", it.firstTimeStamp).put("intervalEnd", it.lastTimeStamp))
                    }
                    JSONObject().put("status", "listed").put("items", items).put("coverage", "Android aggregate usage intervals, not exact per-second screen history.")
                }
            }
        }
        else -> null
    }
    private fun writable(context: Context, calendar: Long) = context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
        arrayOf("calendar_access_level"), "_id = ?", arrayOf(calendar.toString()), null)?.use { it.moveToFirst() && it.getInt(0) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR } == true
}
