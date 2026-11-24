package com.kompi.app

import android.content.Context
import androidx.room.Room
import com.kompi.app.data.LocalDatabase
import com.kompi.app.data.LocalRepository
import com.kompi.app.protocol.generated.*
import com.kompi.app.security.SecureStore
import com.kompi.app.transport.IrohEndpointIdentity
import com.kompi.app.transport.IrohTransport
import computer.iroh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Authenticated in-process QUIC peer, not a daemon/Pi replacement claimed as E2E. It implements
 * pairing/sync and records the actual phone wire messages. No external host or credential is used. */
internal class LiveLoopbackPeer private constructor(private val endpoint: Endpoint) {
    val hostId = IrohEndpointIdentity.fromBytes(endpoint.id().toBytes())
    val conversationId = UUID.randomUUID().toString()
    val configuration = ChatModelConfiguration("high", "acceptance-model", "controlled-peer")
    val prompts = CopyOnWriteArrayList<EnvelopeV3>()
    val answers = CopyOnWriteArrayList<WireMessage.PiUiResponse>()
    val androidToolAnswers = CopyOnWriteArrayList<WireMessage.AndroidToolResponse>()
    val deviceNotificationAnswers = CopyOnWriteArrayList<WireMessage.DeviceNotificationResponse>()
    val pushRegistrations = CopyOnWriteArrayList<WireMessage.PushRegister>()
    val pushUnregistrations = CopyOnWriteArrayList<WireMessage.PushUnregister>()
    val cancellations = CopyOnWriteArrayList<String>()
    val summaries = ConcurrentHashMap<String, RequestSummary>()
    val errors = CopyOnWriteArrayList<Throwable>()
    val modelRequests = java.util.concurrent.atomic.AtomicInteger()
    val targetedReads = CopyOnWriteArrayList<String>()
    val legacyReplyReads = CopyOnWriteArrayList<String>()
    val legacyReplySnapshots = java.util.concurrent.ConcurrentLinkedQueue<String>()
    @Volatile var independentChats = false
    @Volatile var targetedSnapshot: String? = null
    @Volatile var acknowledgePrompts = true
    @Volatile var cursor = "0"
    @Volatile var onPrompt: ((EnvelopeV3) -> Unit)? = null
    @Volatile var snapshotConversationId: String? = null
    @Volatile var snapshotSessionId: String = "controlled-session"
    private val secret = UUID.randomUUID().toString() + UUID.randomUUID().toString()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val write = Mutex()
    @Volatile private var sender: (suspend (EnvelopeV3) -> Unit)? = null
    private val accept = scope.launch {
        try {
            while (isActive) {
                val incoming = endpoint.acceptNext() ?: break
                launch {
                    try {
                        val connection = incoming.accept().connect()
                        val stream = connection.acceptBi()
                        val send: suspend (EnvelopeV3) -> Unit = { envelope -> write.withLock {
                            stream.send().writeAll(FrameCodec.frame(WireCodec.encode(envelope)))
                        } }
                        sender = send
                        val recv = stream.recv()
                        while (isActive) {
                            val size = ByteBuffer.wrap(recv.readExact(4u)).int
                            require(size in 1..MAX_PAYLOAD)
                            val envelope = WireCodec.decode(recv.readExact(size.toUInt()))
                            when (val message = envelope.message) {
                                is WireMessage.Hello -> {
                                    require(message.endpoint == IrohEndpointIdentity.fromBytes(connection.remoteId().toBytes()))
                                    send(EnvelopeV3(id = envelope.id, message = WireMessage.Hello(listOf("prompt", "cancel", "phone-conversations", "pi-session-events", "pi-rpc-ui", UNIFIED_PUSH_CAPABILITY) + (if (independentChats) listOf("concurrent-conversations") else emptyList()), hostId, 1800)))
                                    send(EnvelopeV3(id = envelope.id, message = WireMessage.Ack(state = "accepted", idleTimeoutSeconds = 1800)))
                                }
                                is WireMessage.Pair -> {
                                    require(message.secret == secret)
                                    send(EnvelopeV3(id = envelope.id, message = WireMessage.Paired(hostId)))
                                }
                                is WireMessage.Sync -> send(EnvelopeV3(id = envelope.id, message = WireMessage.SyncSnapshot(cursor, false, false,
                                    summaries.values.toList(), emptyList(), EffectiveSettings(1800, 604800), "live")))
                                is WireMessage.SyncPage -> send(EnvelopeV3(id = envelope.id, message = WireMessage.SyncEvents(emptyList(), cursor)))
                                is WireMessage.SessionPage -> send(EnvelopeV3(id = envelope.id, message = WireMessage.SessionPageResult(sessions = emptyList())))
                                is WireMessage.RequestPage -> send(EnvelopeV3(id = envelope.id, message = WireMessage.RequestPageResult(requests = summaries.values.toList())))
                                WireMessage.ModelsGet -> {
                                    modelRequests.incrementAndGet()
                                    send(EnvelopeV3(id = envelope.id, message = WireMessage.ModelsResult(ModelCatalog(configuration, emptyList()))))
                                }
                                is WireMessage.ConversationPrompt -> {
                                    require(independentChats && message.conversationId == conversationId)
                                    prompts += envelope
                                    summaries[envelope.id] = RequestSummary(configuration, conversationId, 0, "accepted", envelope.id)
                                    send(EnvelopeV3(id = envelope.id, message = WireMessage.Ack(configuration, conversationId, state = "accepted")))
                                }
                                is WireMessage.PiConversationSnapshotGet -> {
                                    require(independentChats && message.conversationId == conversationId)
                                    targetedReads += message.conversationId
                                    targetedSnapshot?.let { json -> send(EnvelopeV3(id = envelope.id, message = WireMessage.PiFrame(PiFrame(
                                        conversationId, "controlled-epoch", json, "snapshot", true, 0, envelope.id, 1, snapshotSessionId)))) }
                                }
                                WireMessage.PiSnapshotGet -> {
                                    val owner = snapshotConversationId
                                    val recovery = envelope.id.startsWith("legacy-reply-snapshot-")
                                    val json = if (recovery) legacyReplySnapshots.poll() else "{\"entries\":[],\"leafId\":null}"
                                    if (recovery) legacyReplyReads += envelope.id
                                    if (json != null) {
                                        val snapshot = if (owner == null) WireMessage.PiSessionEmpty else WireMessage.PiFrame(PiFrame(
                                            conversationId = owner, epoch = "controlled-$owner", json = json,
                                            kind = "snapshot", last = true, part = 0, sequence = if (recovery) legacyReplyReads.size.toLong() else 0, sessionId = snapshotSessionId))
                                        send(EnvelopeV3(id = envelope.id, message = snapshot))
                                    }
                                }
                                is WireMessage.SettingsUpdate -> send(EnvelopeV3(id = envelope.id, message = WireMessage.SettingsConfirmed(message.settings, message.updateId)))
                                is WireMessage.Prompt -> {
                                    prompts += envelope
                                    summaries.putIfAbsent(envelope.id, RequestSummary(message.configuration, conversationId, 0, "accepted", envelope.id))
                                    onPrompt?.invoke(envelope)
                                    if (acknowledgePrompts) send(EnvelopeV3(id = envelope.id, message = WireMessage.Ack(message.configuration, conversationId, state = "accepted")))
                                }
                                is WireMessage.PiUiResponse -> { answers += message; send(EnvelopeV3(id = envelope.id, message = WireMessage.Ack(state = "accepted"))) }
                                is WireMessage.AndroidToolResponse -> { androidToolAnswers += message }
                                is WireMessage.DeviceNotificationResponse -> {
                                    check(envelope.id == message.requestId) { "Device notification receipt envelope must preserve the request identity" }
                                    deviceNotificationAnswers += message
                                }
                                is WireMessage.PushRegister -> {
                                    pushRegistrations += message
                                    send(EnvelopeV3(id = envelope.id, message = WireMessage.PushRegistrationResult(
                                        registrationId = message.registration.registrationId,
                                        status = "registered",
                                    )))
                                }
                                is WireMessage.PushUnregister -> {
                                    pushUnregistrations += message
                                    send(EnvelopeV3(id = envelope.id, message = WireMessage.PushRegistrationResult(
                                        registrationId = message.registrationId,
                                        status = "unregistered",
                                    )))
                                }
                                is WireMessage.Cancel -> { cancellations += message.target }
                                WireMessage.Ping -> send(EnvelopeV3(id = envelope.id, message = WireMessage.Pong))
                                WireMessage.Pong -> Unit
                                else -> error("Unexpected phone wire message ${message.type}")
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Throwable) { if (isActive && !endpoint.isClosed()) errors += error }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { if (isActive && !endpoint.isClosed()) errors += error }
    }

    suspend fun send(message: WireMessage, id: String = if (message is WireMessage.DeviceNotificationRequest) message.requestId else "peer-${UUID.randomUUID()}") {
        (sender ?: error("The actual phone transport is not connected"))(EnvelopeV3(id = id, message = message))
    }

    /** Real Paired response and authenticated endpoint matching, never direct trust-store forgery. */
    suspend fun pair(context: Context): SecureStore.Pairing {
        val name = "live-pairing-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, LocalDatabase::class.java, name).build()
        val store = SecureStore(context)
        val ticket = EndpointTicket.fromAddr(EndpointAddr(endpoint.id(), null, endpoint.boundSockets())).toString()
        val pending = SecureStore.Pairing(ticket, secret, "Disposable loopback acceptance peer")
        store.stagePairing(pending)
        val transport = IrohTransport(context, store, LocalRepository(db, store))
        try {
            transport.reconnect(pending)
            val confirmed = withTimeout(30_000) { transport.confirmedPairing.first { it != null } }!!
            check(confirmed.confirmed && confirmed.machineId == hostId)
            store.saveChatModel(hostId, configuration)
            return confirmed
        } finally { transport.closeAndJoin(); db.close(); context.deleteDatabase(name) }
    }

    suspend fun close() {
        scope.cancel()
        endpoint.shutdown()
        accept.join()
    }

    companion object {
        suspend fun open(context: Context): LiveLoopbackPeer {
            IrohAndroid.installAndroidContext(context.applicationContext)
            return LiveLoopbackPeer(Endpoint.bind(EndpointOptions(bindAddr = "127.0.0.1:0", alpns = listOf(ALPN.toByteArray()))))
        }
    }
}
