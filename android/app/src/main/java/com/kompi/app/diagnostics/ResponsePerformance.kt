package com.kompi.app.diagnostics

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.kompi.app.protocol.generated.GenerationStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** Content-free measurements. Client/host clocks are never subtracted from one another. */
object ResponsePerformance {
    data class Measurement(
        val firstTextMs: Double? = null,
        val totalMs: Double? = null,
        val firstDrawMs: Double? = null,
        val ackMs: Double? = null,
        val chunks: Int = 0,
        val pi: GenerationStats? = null,
    ) {
        val tokensPerSecond: Double? get() = pi?.let { stats ->
            stats.outputTokens?.takeIf { stats.totalMs > 0 }?.toDouble()?.div(stats.totalMs / 1000.0)
        }
    }
    private class Running(val started: Long? = SystemClock.elapsedRealtimeNanos()) {
        val flow = MutableStateFlow(Measurement())
        var logged = false
        var lastSequence = -1L
        val entryKeys = mutableSetOf<String>()
    }
    private const val LIMIT = 256
    private val runs = linkedMapOf<String, Running>()
    private val entryRequests = mutableMapOf<String, String>()
    private val unboundDraws = linkedMapOf<String, Long>()
    private var prefs: android.content.SharedPreferences? = null

    @Synchronized fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("response_performance_v1", Context.MODE_PRIVATE)
        prefs!!.all.entries.toList().takeLast(LIMIT).forEach { (id, raw) ->
            if (raw !is String) return@forEach
            runCatching {
                val json = JSONObject(raw)
                val run = Running(started = null)
                val stats = json.optJSONObject("pi")?.let { value ->
                    fun optional(key: String): Long? = if (value.has(key) && !value.isNull(key)) value.getLong(key) else null
                    GenerationStats(value.getLong("assistantMessages"), optional("daemonTotalMs"), optional("firstTextMs"),
                        value.optString("model").takeIf { it.isNotEmpty() }, optional("outputTokens"),
                        value.optString("provider").takeIf { it.isNotEmpty() }, optional("reasoningTokens"), value.getLong("textChunks"), value.getLong("totalMs"))
                }
                fun number(key: String): Double? = if (json.has(key) && !json.isNull(key)) json.getDouble(key) else null
                run.flow.value = Measurement(number("firstTextMs"), number("totalMs"), number("firstDrawMs"), number("ackMs"), json.getInt("chunks"), stats)
                run.logged = true
                json.optJSONArray("entries")?.let { values -> for (i in 0 until values.length()) { val key = values.getString(i); run.entryKeys.add(key); entryRequests[key] = id } }
                runs[id] = run
            }
        }
    }

    @Synchronized fun begin(id: String) {
        if (id in runs) return
        while (runs.size >= LIMIT) {
            val oldest = runs.keys.first()
            runs.remove(oldest)?.entryKeys?.forEach { entryRequests.remove(it) }
            prefs?.edit()?.remove(oldest)?.apply()
        }
        runs[id] = Running()
        Log.i("KompiBenchmark", JSONObject().put("event", "start").put("requestId", id).toString())
    }

    @Synchronized fun bindEntry(key: String, requestId: String?) {
        val run = requestId?.let(runs::get) ?: return
        entryRequests[key] = requestId
        run.entryKeys.add(key)
        unboundDraws.remove(key)?.let { drawn ->
            val started = run.started
            if (run.flow.value.firstDrawMs == null && started != null) run.flow.value = run.flow.value.copy(firstDrawMs = (drawn - started) / 1_000_000.0)
        }
        if (run.flow.value.totalMs != null) publish(requestId, run)
    }

    @Synchronized fun observe(key: String): StateFlow<Measurement>? = (entryRequests[key] ?: key.takeIf(runs::containsKey))?.let { runs[it]?.flow }
    @Synchronized fun requestFor(key: String): String? = entryRequests[key] ?: key.takeIf(runs::containsKey)
    private fun elapsed(run: Running): Double? = run.started?.let { (SystemClock.elapsedRealtimeNanos() - it) / 1_000_000.0 }

    @Synchronized fun acknowledge(id: String) { runs[id]?.let { run -> if (run.flow.value.ackMs == null) run.flow.value = run.flow.value.copy(ackMs = elapsed(run)) } }
    @Synchronized fun delta(id: String, sequence: Long) {
        val run = runs[id] ?: return
        if (run.flow.value.totalMs != null || sequence <= run.lastSequence) return
        run.lastSequence = sequence
        val previous = run.flow.value
        if (previous.firstTextMs == null) LocalDiagnostics.begin(LocalDiagnostics.Metric.ResponseToRender, id)
        run.flow.value = previous.copy(firstTextMs = previous.firstTextMs ?: elapsed(run), chunks = previous.chunks + 1)
    }
    @Synchronized fun firstDraw(key: String) {
        ChatTiming.assistantDraw(key)
        val id = requestFor(key)
        if (id == null) {
            unboundDraws.putIfAbsent(key, SystemClock.elapsedRealtimeNanos())
            if (unboundDraws.size > 512) unboundDraws.remove(unboundDraws.keys.first())
            return
        }
        val run = runs[id] ?: return
        if (run.started == null || run.flow.value.firstDrawMs != null) return
        run.flow.value = run.flow.value.copy(firstDrawMs = elapsed(run))
        LocalDiagnostics.finish(LocalDiagnostics.Metric.ResponseToRender, id)
        if (run.flow.value.totalMs != null) publish(id, run)
    }
    @Synchronized fun finish(id: String) {
        val run = runs[id] ?: return
        if (run.flow.value.totalMs == null) run.flow.value = run.flow.value.copy(totalMs = elapsed(run))
        publish(id, run)
    }
    @Synchronized fun stats(id: String, stats: GenerationStats) {
        val run = runs[id] ?: return
        run.flow.value = run.flow.value.copy(pi = stats)
        publish(id, run)
    }
    private fun publish(id: String, run: Running) {
        if (run.flow.value.totalMs == null) return
        persist(id, run)
        if (!run.logged && run.flow.value.pi != null && (run.flow.value.firstTextMs == null || run.flow.value.firstDrawMs != null)) {
            run.logged = true
            Log.i("KompiBenchmark", json(run).put("event", "complete").put("requestId", id).toString())
        }
    }
    private fun persist(id: String, run: Running) { prefs?.edit()?.putString(id, json(run).toString())?.apply() }
    private fun json(run: Running): JSONObject {
        val m = run.flow.value
        return JSONObject().put("firstTextMs", m.firstTextMs ?: JSONObject.NULL).put("totalMs", m.totalMs ?: JSONObject.NULL)
            .put("firstDrawMs", m.firstDrawMs ?: JSONObject.NULL).put("ackMs", m.ackMs ?: JSONObject.NULL).put("chunks", m.chunks)
            .put("pi", m.pi?.let { stats -> JSONObject().put("assistantMessages", stats.assistantMessages).put("daemonTotalMs", stats.daemonTotalMs ?: JSONObject.NULL)
                .put("firstTextMs", stats.firstTextMs ?: JSONObject.NULL).put("model", stats.model ?: "").put("provider", stats.provider ?: "")
                .put("outputTokens", stats.outputTokens ?: JSONObject.NULL).put("reasoningTokens", stats.reasoningTokens ?: JSONObject.NULL)
                .put("textChunks", stats.textChunks).put("totalMs", stats.totalMs) } ?: JSONObject.NULL)
            .put("entries", org.json.JSONArray(run.entryKeys.toList()))
    }
}
