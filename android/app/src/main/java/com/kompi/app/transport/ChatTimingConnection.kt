package com.kompi.app.transport

import com.kompi.app.diagnostics.ChatTiming
import com.kompi.app.diagnostics.ChatTiming.Stage
import com.kompi.app.protocol.generated.EnvelopeV3
import com.kompi.app.protocol.generated.WireMessage as V3Message
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** A real physical connection, not the transport's retry/reconnect generation. */
internal class ChatTimingConnection(val id: String = ChatTiming.uuid()) {
    private val attemptedBefore = AtomicBoolean(false)
    private val live = AtomicBoolean(false)
    private val pending = ConcurrentHashMap<String, Attempt>()
    private val exchange = AtomicReference<Pair<String, CompletableDeferred<V3Message.ChatTimingPage>>?>(null)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile var supported = false

    data class Attempt(val request: String, val id: String, val connection: String) {
        fun record(stage: Stage, conversation: String? = null) = ChatTiming.record(stage, request, id, connection, conversation)
    }

    fun record(stage: Stage) = ChatTiming.record(stage, connection = id)
    fun ready() { if (live.compareAndSet(false, true)) record(Stage.PhoneSyncReady) }
    fun begin(request: String): Attempt = Attempt(request, ChatTiming.uuid(), id).also {
        pending[request] = it
        it.record(Stage.PhoneAttempt)
        if (attemptedBefore.getAndSet(true)) it.record(Stage.PhoneReusedConnection)
    }
    fun match(request: String): Attempt? = pending[request]
    fun finish(attempt: Attempt) { pending.remove(attempt.request, attempt) }
    fun interrupt() {
        for ((request, attempt) in pending) {
            if (pending.remove(request, attempt)) attempt.record(Stage.PhoneAttemptInterrupted)
        }
    }
    fun stop() { interrupt(); exchange.getAndSet(null)?.second?.cancel(); wake.close() }
    fun refresh() { wake.trySend(Unit) }
    fun receivePage(envelopeId: String, page: V3Message.ChatTimingPage) {
        exchange.get()?.takeIf { it.first == envelopeId }?.second?.complete(page)
    }

    suspend fun pull(peer: String, send: suspend (EnvelopeV3) -> Unit, isLive: () -> Boolean) {
        while (true) {
            if (!isLive()) { delay(500); continue }
            // The daemon batches metadata on a separate writer; let the final batch commit.
            delay(350)
            try {
                var cursor = ChatTiming.cursor(peer)
                for (index in 0 until 8) {
                    val id = "timing-${ChatTiming.uuid()}"
                    val reply = CompletableDeferred<V3Message.ChatTimingPage>()
                    val pendingExchange = id to reply
                    exchange.set(pendingExchange)
                    val page = try {
                        withTimeoutOrNull(2_000) {
                            send(EnvelopeV3(id = id, message = V3Message.ChatTimingGet(cursor)))
                            reply.await()
                        }
                    } finally {
                        exchange.compareAndSet(pendingExchange, null)
                        reply.cancel()
                    } ?: break
                    if (!ChatTiming.import(peer, page) || !page.hasMore) break
                    cursor = ChatTiming.cursor(peer)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { /* Diagnostic sync is not a transport failure. */ }
            withTimeoutOrNull(15_000) { wake.receive() }
        }
    }
}
