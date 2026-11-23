package com.kompi.app.assistant.native

import android.content.Context
import android.os.Build
import android.os.OutcomeReceiver
import android.os.SystemClock
import com.kompi.app.assistant.actions.PhoneActionAccess
import com.kompi.app.assistant.actions.PhoneCapability
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object NativeHealth {
    private val permissions = mapOf("steps" to "android.permission.health.READ_STEPS", "sleep" to "android.permission.health.READ_SLEEP", "exercise" to "android.permission.health.READ_EXERCISE", "heart_rate" to "android.permission.health.READ_HEART_RATE")
    fun available(context: Context) = Build.VERSION.SDK_INT >= 34 && context.getSystemService("healthconnect") != null
    fun granted(context: Context) = available(context) && permissions.values.any { hasPermission(context, it) }
    fun parse(name: String, input: JSONObject): NativeOperation? {
        if(name != "read_health") return null
        val a = NativeArguments(input, "type", "since", "until", "limit", "cursor")
        val type = a.choice("type", permissions.keys)
        val since = a.long("since", 0, 32_503_680_000_000L)
        val until = a.long("until", since + 1, minOf(32_503_680_000_001L, since + 7L * 86_400_000))
        val limit = a.long("limit", 1, 30, 20).toInt()
        val cursor = a.text("cursor", 128)
        val access = when (type) {
            "steps" -> PhoneCapability.HealthSteps
            "sleep" -> PhoneCapability.HealthSleep
            "exercise" -> PhoneCapability.HealthExercise
            "heart_rate" -> PhoneCapability.HealthHeartRate
            else -> error("Unknown Health Connect type")
        }
        return NativeTask(name, "Read and share up to $limit Health Connect $type records from ${Instant.ofEpochMilli(since)} until ${Instant.ofEpochMilli(until)}. Only data actually shared into Health Connect is available; this is not medical advice.", PhoneActionAccess.StandingGrant(access)) { context, _ ->
            if(!available(context)) nativeError("health_unavailable", "This integration requires Android 14 or newer with Health Connect available.")
            else if(!hasPermission(context, permissions.getValue(type))) missingPermission("Health Connect $type")
            else Api34.read(context, type, since, until, limit, cursor)
        }
    }
    @RequiresApi(34)
    private object Api34 {
        private data class Page(val type: String, val since: Long, val until: Long, val limit: Int, val token: Long, val expires: Long)
        private val pages = LinkedHashMap<String, Page>()
        private val executor = Executor { Dispatchers.IO.dispatch(EmptyCoroutineContext, it) }
        suspend fun read(context: Context, type: String, since: Long, until: Long, limit: Int, cursor: String?): JSONObject {
            val page = if(cursor == null) null else synchronized(pages) { pages[cursor]?.takeIf { it.expires > SystemClock.elapsedRealtime() && it.type == type && it.since == since && it.until == until && it.limit == limit } }
            if(cursor != null && page == null) return nativeError("cursor_expired", "This health page expired or its query changed. Start a fresh query.")
            val manager = context.getSystemService(android.health.connect.HealthConnectManager::class.java)
                ?: return nativeError("health_unavailable", "Health Connect is unavailable.")
            return when(type) {
                "steps" -> records(manager, android.health.connect.datatypes.StepsRecord::class.java, type, since, until, limit, page?.token) { row -> interval(row).put("count", row.count) }
                "sleep" -> records(manager, android.health.connect.datatypes.SleepSessionRecord::class.java, type, since, until, limit, page?.token) { row -> interval(row).put("title", row.title?.toString()?.take(300).orEmpty()) }
                "exercise" -> records(manager, android.health.connect.datatypes.ExerciseSessionRecord::class.java, type, since, until, limit, page?.token) { row -> interval(row).put("exerciseType", row.exerciseType).put("title", row.title?.toString()?.take(300).orEmpty()) }
                else -> records(manager, android.health.connect.datatypes.HeartRateRecord::class.java, type, since, until, limit, page?.token) { row ->
                    // Bound work even when a source publishes unusually large sample series.
                    val samples = row.samples.take(1000)
                    interval(row).put("samplesExamined", samples.size).put("samplesTruncated", row.samples.size > samples.size)
                        .put("minimumBpm", samples.minOfOrNull { it.beatsPerMinute } ?: JSONObject.NULL)
                        .put("maximumBpm", samples.maxOfOrNull { it.beatsPerMinute } ?: JSONObject.NULL)
                        .put("averageBpm", if(samples.isEmpty()) JSONObject.NULL else samples.map { it.beatsPerMinute }.average())
                }
            }
        }
        private fun interval(record: android.health.connect.datatypes.IntervalRecord) = JSONObject()
            .put("start", record.startTime.toEpochMilli()).put("end", record.endTime.toEpochMilli())
            .put("sourcePackage", record.metadata.dataOrigin.packageName)
        private suspend fun <T : android.health.connect.datatypes.Record> records(manager: android.health.connect.HealthConnectManager, recordClass: Class<T>, type: String,
            since: Long, until: Long, limit: Int, token: Long?, map: (T) -> JSONObject): JSONObject = withTimeout(8_000) {
            suspendCancellableCoroutine { continuation ->
                val builder = android.health.connect.ReadRecordsRequestUsingFilters.Builder(recordClass)
                    .setTimeRangeFilter(android.health.connect.TimeInstantRangeFilter.Builder().setStartTime(Instant.ofEpochMilli(since)).setEndTime(Instant.ofEpochMilli(until)).build())
                    .setPageSize(limit).setAscending(true)
                token?.let(builder::setPageToken)
                manager.readRecords(builder.build(), executor, object : OutcomeReceiver<android.health.connect.ReadRecordsResponse<T>, android.health.connect.HealthConnectException> {
                    override fun onResult(response: android.health.connect.ReadRecordsResponse<T>) {
                        if(!continuation.isActive) return
                        try {
                            val items = JSONArray()
                            var omitted = 0
                            response.records.take(limit).forEach { if(!appendBounded(items, map(it))) omitted++ }
                            val next = response.nextPageToken.takeIf { it != -1L }?.let { nextToken ->
                                UUID.randomUUID().toString().also { handle -> synchronized(pages) {
                                    pages.entries.removeAll { it.value.expires <= SystemClock.elapsedRealtime() }
                                    pages[handle] = Page(type, since, until, limit, nextToken, SystemClock.elapsedRealtime() + 120_000)
                                    while(pages.size > 64) pages.remove(pages.keys.first())
                                } }
                            }
                            continuation.resume(JSONObject().put("status", "listed").put("items", items).put("nextCursor", next ?: JSONObject.NULL)
                                .put("omittedForSize", omitted).put("coverage", "Source records only; overlapping data sources are not deduplicated totals. An empty result means no shared records, not zero activity."))
                        } catch(error: Exception) { continuation.resumeWithException(error) }
                    }
                    override fun onError(error: android.health.connect.HealthConnectException) { if(continuation.isActive) continuation.resume(nativeError("health_read_failed", "Health Connect denied or could not complete this read. Review its permissions and data sources.")) }
                })
            }
        }
    }
}
