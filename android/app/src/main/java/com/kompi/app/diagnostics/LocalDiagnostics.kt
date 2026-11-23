package com.kompi.app.diagnostics

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Content-free monotonic measurements for local pipeline overhead. */
object LocalDiagnostics {
    enum class Metric(val label: String) {
        LocalSendToVisible("local_send_to_visible"), SendToAck("send_to_ack"), FirstText("first_text"), ResponseToRender("response_to_render"), ReconnectToLive("reconnect_to_live"), Cancellation("cancellation"), SpeechStartup("speech_startup")
    }

    private val startedAtNanos = ConcurrentHashMap<String, Long>()
    private val streamChunks = ConcurrentHashMap<String, AtomicInteger>()
    private val responseRenderUpdates = ConcurrentHashMap<String, AtomicInteger>()

    fun recordResponseRenderUpdate(requestId: String) {
        responseRenderUpdates.computeIfAbsent(requestId) { AtomicInteger() }.incrementAndGet()
    }

    fun recordStreamChunk(requestId: String) {
        streamChunks.computeIfAbsent(requestId) { AtomicInteger() }.incrementAndGet()
    }

    fun finishStream(requestId: String, outcome: String) {
        val count = streamChunks.remove(requestId)?.get() ?: return
        val renderedUpdates = responseRenderUpdates.remove(requestId)?.get() ?: 0
        Log.i("KompiTiming", "metric=stream_chunks count=$count render_updates=$renderedUpdates outcome=$outcome")
    }

    fun begin(metric: Metric, correlation: String = "default") {
        startedAtNanos[key(metric, correlation)] = SystemClock.elapsedRealtimeNanos()
    }

    fun cancel(metric: Metric, correlation: String = "default") {
        startedAtNanos.remove(key(metric, correlation))
    }

    fun finish(metric: Metric, correlation: String = "default") {
        val started = startedAtNanos.remove(key(metric, correlation)) ?: return
        val elapsedMillis = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L
        Log.i("KompiTiming", "metric=${metric.label} elapsed_ms=$elapsedMillis")
    }

    private fun key(metric: Metric, correlation: String) = "${metric.label}:$correlation"
}
