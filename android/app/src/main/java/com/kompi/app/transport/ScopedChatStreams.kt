package com.kompi.app.transport

import android.util.Log
import com.kompi.app.protocol.generated.EnvelopeV3
import com.kompi.app.protocol.generated.FrameCodec
import com.kompi.app.protocol.generated.PiFrame
import com.kompi.app.protocol.generated.WireCodec
import com.kompi.app.protocol.generated.WireFormatException
import com.kompi.app.protocol.generated.WireMessage as Message
import computer.iroh.Connection
import computer.iroh.SendStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** One instance per authenticated QUIC connection. A Route object is its chat generation. */
internal class ScopedChatStreams(
    parent: CoroutineScope,
    private val connection: Connection,
    private val lock: Any,
    private val current: () -> Boolean,
    private val primaryWrite: suspend (EnvelopeV3) -> Unit,
    private val beginProjection: () -> Unit,
    private val onReply: suspend (Route, EnvelopeV3) -> Unit,
) {
    companion object {
        const val CAPABILITY = "chat-streams"
        private const val MAX_ROUTES = 4
        private const val TAG = "KompiChatStreams"
        private const val SNAPSHOT_TIMEOUT = 35_000L
    }

    private val jobs = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val routes = mutableMapOf<String, Route>()
    @Volatile var negotiated = false
    private var focused: String? = null
    private var focusGeneration = 0L
    private var projection: Projection? = null
        set(value) {
            val old = field
            field = value
            if (old is Projection.Pending && old !== value) old.finished.complete(Unit)
        }
    private var knownActive = false
    private var recoveryAttempts = 0
    private var primaryDeadline: Job? = null

    private sealed interface Projection {
        val route: Route? // null denotes primary
        class Pending(override val route: Route?, val request: String) : Projection {
            val finished = CompletableDeferred<Unit>()
            private var header: PiFrame? = null
            private var next = 0L

            fun accept(frame: PiFrame) {
                val first = header ?: frame.also { header = it }
                if (frame.part != next++ || frame.epoch != first.epoch || frame.sessionId != first.sessionId ||
                    frame.sequence != first.sequence) throw WireFormatException("Conversation snapshot identity changed")
            }
        }
        data class Live(override val route: Route?) : Projection
    }

    internal inner class Route(val chat: String) {
        val opened = CompletableDeferred<Boolean>()
        val closed = CompletableDeferred<Unit>()
        private val writes = Mutex()
        lateinit var reader: Job
        private lateinit var output: SendStream
        private var snapshot: CompletableDeferred<Unit>? = null
        private var snapshotRequest: String? = null
        private var bound = false

        fun isCurrent() = synchronized(lock) { current() && routes[chat] === this && reader.isActive }
        fun retire() { reader.cancel() }

        suspend fun write(envelope: EnvelopeV3, nativeWrite: suspend (suspend () -> Unit) -> Unit) {
            val bytes = FrameCodec.frame(WireCodec.encode(envelope))
            writes.withLock {
                if (!isCurrent()) throw RouteLost()
                try {
                    withTimeout(10_000) { nativeWrite { output.writeAll(bytes) } }
                } catch (failure: Exception) {
                    retire() // Never append after a possibly partial frame.
                    if (failure is CancellationException && failure !is kotlinx.coroutines.TimeoutCancellationException) throw failure
                    throw RouteLost(failure)
                }
            }
        }

        suspend fun read() {
            try {
                withTimeout(5_000) { connection.openBi() }.use { stream ->
                    stream.send().use { send ->
                        stream.recv().use { recv ->
                            try {
                                val frames = LiveFrameReader(recv::read)
                                output = send
                                withTimeout(5_000) {
                                    val id = "chat-open-${UUID.randomUUID()}"
                                    send.setPriority(1)
                                    send.writeAll(FrameCodec.frame(WireCodec.encode(EnvelopeV3(id = id, message = Message.ConversationStreamOpen(chat)))))
                                    val reply = WireCodec.decode(frames.next())
                                    val ack = reply.message as? Message.Ack
                                    if (reply.id != id || ack?.state != "accepted" || ack.conversationId != chat) throw RouteLost()
                                    synchronized(lock) {
                                        if (!isCurrent()) throw RouteLost()
                                        if (focused == chat) {
                                            projection = null // Stop accepting primary immediately.
                                            if (knownActive) jobs.launch { requestSnapshot(chat, onlyIfMissing = true) }
                                        }
                                        bound = true
                                        opened.complete(true)
                                    }
                                    Log.i(TAG, "bound chat=$chat")
                                }
                                while (isCurrent()) {
                                    val envelope = WireCodec.decode(frames.next())
                                    if (!isCurrent()) break
                                    onReply(this, envelope)
                                }
                            } finally {
                                withContext(NonCancellable) {
                                    try { send.reset(2uL) } catch (_: Exception) { }
                                    try { recv.stop(2uL) } catch (_: Exception) { }
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Do not log wire payloads or close the authenticated connection.
            } finally {
                synchronized(lock) {
                    val recover = focused == chat && knownActive && bound &&
                        (projection == null || projection?.route === this)
                    if (recover) projection = null
                    routes.remove(chat, this)
                    snapshot?.cancel()
                    opened.complete(false)
                    closed.complete(Unit)
                    Log.i(TAG, "retired chat=$chat recover=$recover")
                    if (recover && current() && recoveryAttempts < 2) {
                        val usePrimary = recoveryAttempts++ != 0
                        val selection = focusGeneration
                        jobs.launch {
                            // QUIC reset races the peer dropping its exclusive route claim.
                            delay(300)
                            requestSnapshot(chat, onlyIfMissing = true, forcePrimary = usePrimary, expectedSelection = selection)
                        }
                    }
                }
            }
        }

        fun pendingSnapshot(): CompletableDeferred<Unit>? = snapshot

        fun pendingSnapshot(request: String): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also {
            snapshot = it
            snapshotRequest = request
        }

        fun finishSnapshot(id: String?, failed: Boolean): Boolean {
            if (id == null || snapshotRequest != id) return false
            val pending = snapshot
            snapshot = null
            snapshotRequest = null
            if (failed) pending?.completeExceptionally(RouteLost()) else pending?.complete(Unit)
            return true
        }
    }

    class RouteLost(cause: Throwable? = null) : IllegalStateException("Conversation stream closed; checking delivery", cause)

    fun focus(chat: String?) = synchronized(lock) {
        if (chat != focused) {
            focused = chat
            focusGeneration++
            knownActive = false
            recoveryAttempts = 0
            primaryDeadline?.cancel()
            projection = null
        }
    }

    suspend fun route(chat: String): Route? {
        val candidate = synchronized(lock) {
            if (!negotiated || !current()) return null
            routes[chat] ?: run {
                if (routes.size >= MAX_ROUTES) return null
                Route(chat).also { route ->
                    routes[chat] = route
                    route.reader = jobs.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { route.read() }
                    route.reader.start()
                }
            }
        }
        return candidate.takeIf { it.opened.await() && it.isCurrent() }
            ?: run { candidate.closed.await(); null }
    }

    /** Called only for a known active runtime, including after the first draft prompt's acceptance. */
    suspend fun requestSnapshot(
        chat: String, onlyIfMissing: Boolean = false, forcePrimary: Boolean = false, expectedSelection: Long? = null,
        afterPending: Boolean = false,
    ) {
        if (afterPending) {
            // A read already underway can predate the terminal ledger event. Its base is
            // not the reconciliation read requested by this caller. Never resend the prompt.
            val prior = synchronized(lock) {
                if (!current() || focused != chat) return
                (projection as? Projection.Pending)?.let {
                    if (it.route != null) it.finished to focusGeneration else {
                        // Primary snapshots have independent IDs. Supersede a stalled pre-terminal
                        // read; its late fragments cannot satisfy the new request.
                        projection = null
                        primaryDeadline?.cancel()
                        null
                    }
                }
            }
            if (prior != null) {
                prior.first.await()
                return requestSnapshot(chat, expectedSelection = prior.second)
            }
        }
        val selection = synchronized(lock) {
            if (!current() || focused != chat || (expectedSelection != null && expectedSelection != focusGeneration)) return
            knownActive = true
            if (projection is Projection.Pending || (onlyIfMissing && projection != null)) return
            focusGeneration
        }
        val route = if (forcePrimary) null else route(chat)
        val id = "scoped-snapshot-${UUID.randomUUID()}"
        val pending = synchronized(lock) {
            if (!current() || focusGeneration != selection || projection is Projection.Pending || (onlyIfMissing && projection != null)) return
            // A focus switch can leave one snapshot on the offscreen route. Wait for its
            // final fragment (discarded), then request the newly selected pane's snapshot.
            if (route?.pendingSnapshot() != null || route?.isCurrent() == false) return
            projection = Projection.Pending(route, id)
            beginProjection()
            if (route == null) {
                primaryDeadline?.cancel()
                primaryDeadline = jobs.launch {
                    delay(SNAPSHOT_TIMEOUT)
                    snapshotFailed(null, id)
                }
            }
            route?.pendingSnapshot(id)
        }
        val envelope = EnvelopeV3(id = id, message = Message.PiConversationSnapshotGet(chat))
        // Owned by the connection, not the composable that requested it.
        jobs.launch {
            try {
                if (route == null) {
                    primaryWrite(envelope)
                } else {
                    withTimeout(SNAPSHOT_TIMEOUT) {
                        route.write(envelope) { it() }
                        pending?.await()
                    }
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                route?.retire()
            } catch (_: RouteLost) {
                // Write failure retired the stream. A correlated host rejection
                // has already cleared its pending state in snapshotFailed().
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snapshotFailed(route, id)
            }
        }
    }

    fun refreshProjection(chat: String) = synchronized(lock) {
        if (knownActive && focused == chat) jobs.launch { requestSnapshot(chat) }
    }

    fun resetPrimaryProjection(): Unit = synchronized(lock) {
        val chat = focused ?: return
        if (!knownActive || projection?.route != null) return
        projection = null
        jobs.launch { requestSnapshot(chat) }
    }

    fun snapshotFailed(route: Route?, id: String) = synchronized(lock) {
        route?.finishSnapshot(id, failed = true)
        val pending = projection as? Projection.Pending
        if (pending?.route === route && pending?.request == id) {
            if (route == null) primaryDeadline?.cancel()
            projection = null
            val chat = focused
            val selection = focusGeneration
            if (chat != null && current() && knownActive && recoveryAttempts < 2) {
                val backoff = 300L * ++recoveryAttempts
                jobs.launch {
                    delay(backoff)
                    requestSnapshot(chat, onlyIfMissing = true, forcePrimary = true, expectedSelection = selection)
                }
            }
        }
    }

    /** Under the transport's projection lock, only the selected route can touch the pane. */
    fun acceptFrame(route: Route?, frame: PiFrame, apply: () -> Boolean): Unit = synchronized(lock) {
        if (!current()) return
        if (route != null && (!route.isCurrent() || route.chat != frame.conversationId)) {
            if (route.isCurrent()) throw WireFormatException("Pi frame belongs to another conversation")
            return
        }
        if (focused == null && route == null && frame.conversationId !in routes) {
            apply() // Unscoped assistant/legacy pane, not a migrated projection.
            return
        }
        val state = projection
        if (focused == frame.conversationId && state != null && state.route === route) {
            when (state) {
                is Projection.Pending -> if (frame.kind != "snapshot") {
                    // A first turn may advance while its requested base is in flight. The
                    // session store buffers this selected route's suffix behind that barrier.
                    apply()
                } else if (frame.requestId == state.request) {
                    state.accept(frame)
                    val installed = apply()
                    if (frame.last) {
                        if (!installed) {
                            if (route != null) throw WireFormatException("Conversation snapshot was not installed")
                            snapshotFailed(null, state.request)
                            return
                        }
                        if (route == null) primaryDeadline?.cancel()
                        recoveryAttempts = 0
                        projection = Projection.Live(route)
                        Log.i(TAG, "snapshot_ready chat=${frame.conversationId} scoped=${route != null}")
                    }
                }
                is Projection.Live -> if (frame.kind != "snapshot") apply()
            }
        }
        if (frame.kind == "snapshot" && frame.last && route?.finishSnapshot(frame.requestId, failed = false) == true &&
            focused == route.chat && projection == null) {
            jobs.launch { requestSnapshot(route.chat, onlyIfMissing = true) }
        }
    }


}
