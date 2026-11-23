package com.kompi.app.transport

import android.content.Context
import android.util.Log
import com.kompi.app.diagnostics.ChatTiming
import com.kompi.app.diagnostics.ChatTiming.Origin
import com.kompi.app.diagnostics.ChatTiming.Stage
import com.kompi.app.diagnostics.LocalDiagnostics
import com.kompi.app.diagnostics.ResponsePerformance
import com.kompi.app.attachments.AttachmentDraftStore
import com.kompi.app.attachments.DraftAttachment
import com.kompi.app.data.LocalRepository
import com.kompi.app.data.LocalRepository.RejectionDisposition
import com.kompi.app.data.StoredPrompt
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.EndpointTicket
import computer.iroh.IrohAndroid
import computer.iroh.presetN0
import com.kompi.app.protocol.generated.ALPN as V3_ALPN
import com.kompi.app.protocol.generated.EnvelopeV3 as EnvelopeV3
import com.kompi.app.protocol.generated.FrameCodec as V3FrameCodec
import com.kompi.app.protocol.generated.MAX_PAYLOAD
import com.kompi.app.protocol.generated.ARTIFACT_DOWNLOADS_CAPABILITY
import com.kompi.app.protocol.generated.ATTACHMENTS_CAPABILITY
import com.kompi.app.protocol.generated.ATTACHMENT_CHUNK_MAX_BYTES
import com.kompi.app.protocol.generated.ATTACHMENT_MAX_FILE_BYTES
import com.kompi.app.protocol.generated.ATTACHMENT_MAX_FILES_PER_MESSAGE
import com.kompi.app.protocol.generated.DEFAULT_IDLE_TIMEOUT_SECONDS
import com.kompi.app.protocol.generated.DEFAULT_UPLOAD_RETENTION_SECONDS
import com.kompi.app.protocol.generated.MIN_UPLOAD_RETENTION_SECONDS
import com.kompi.app.protocol.generated.MAX_UPLOAD_RETENTION_SECONDS
import com.kompi.app.protocol.generated.MAX_SYNC_PAGE
import com.kompi.app.protocol.generated.EffectiveSettings
import com.kompi.app.protocol.generated.WireCodec as V3WireCodec
import com.kompi.app.protocol.generated.WireFormatException
import com.kompi.app.protocol.generated.WireMessage as V3Message
import com.kompi.app.security.SecureStore
import com.kompi.app.assistant.AssistantScreenContext
import com.kompi.app.assistant.actions.PhoneActionAccess
import com.kompi.app.assistant.actions.PhoneActionExecutor
import com.kompi.app.assistant.actions.PhoneActionParseResult
import com.kompi.app.assistant.actions.PhoneActionParser
import com.kompi.app.assistant.actions.PhoneCapability
import com.kompi.app.assistant.actions.access
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import com.kompi.app.attachments.AttachmentDispatchStage
import com.kompi.app.attachments.AttachmentTransferProgress
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Stable identity extracted from the signed Iroh endpoint ticket, not its relay/address text. */
internal object IrohEndpointIdentity {
    fun fromTicket(ticket: String): String = fromBytes(EndpointTicket.fromString(ticket).endpointAddr().id().toBytes())
    fun fromBytes(bytes: ByteArray): String {
        require(bytes.size == 32) { "Iroh EndpointId must be exactly 32 bytes" }
        return bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

/** Connection-bound send state; operations are claimed only while holding IrohTransport.pushSyncMutex. */
internal class PushConnectionSender(
    val generation: Long,
    val peerId: String,
    val send: suspend (EnvelopeV3) -> Unit,
    val sentOperationIds: MutableSet<String> = mutableSetOf(),
)

/** Official Iroh FFI transport. Audio is never passed to this class. */
class IrohTransport(context: Context, private val store: SecureStore, private val repository: LocalRepository) {
    companion object {
        val ALPN = V3_ALPN.toByteArray(Charsets.UTF_8)
        private const val SYNC_TAG = "KompiSync"
    }

    sealed interface Event {
        data class Delivery(val id: String, val state: AckState, val sessionId: String? = null, val conversationId: String? = null) : Event
        data class Progress(val id: String, val activity: String, val sessionId: String? = null, val conversationId: String? = null) : Event
        data class ResponseDelta(val id: String, val sessionId: String, val conversationId: String, val sequence: Int, val text: String) : Event
        data class Reply(val id: String, val text: String, val state: ResultState, val sessionId: String? = null, val conversationId: String? = null) : Event
        data class Failure(val id: String, val code: String, val message: String, val sessionId: String? = null, val conversationId: String? = null, val retryable: Boolean = false, val uncertain: Boolean = false) : Event
        data class ConversationChanged(val metadata: PhoneConversation, val replyTo: String? = null) : Event
        data class AttachmentProgress(val id: String, val received: Long, val total: Long) : Event
        data class AttachmentFailure(val id: String, val message: String) : Event
        data class AttachmentAccepted(val id: String, val fileName: String, val length: Long) : Event
        data class DurableSyncEvent(val event: com.kompi.app.protocol.generated.SyncEvent) : Event
    }

    private val appContext = context.applicationContext
    private val attachmentDraftStore = AttachmentDraftStore(appContext)
    private val unifiedPushRepository = com.kompi.app.assistant.push.UnifiedPushRepository.get(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow("Disconnected")
    val state = _state.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError = _lastError.asStateFlow()
    private val _confirmedPairing = MutableStateFlow<SecureStore.Pairing?>(null)
    val confirmedPairing = _confirmedPairing.asStateFlow()
    private val _piDialogs = MutableStateFlow<List<PiDialogRequest>>(emptyList())
    val piDialogs = _piDialogs.asStateFlow()
    private val _piDialogError = MutableStateFlow<String?>(null)
    val piDialogError = _piDialogError.asStateFlow()
    private val _androidToolRequests = MutableStateFlow<List<AndroidToolRequest>>(emptyList())
    val androidToolRequests = _androidToolRequests.asStateFlow()
    private val executingAndroidTools = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<AndroidToolRequest, Boolean>())
    private val recentAndroidToolIds = linkedSetOf<String>()
    private val _pendingPromptOutbox = MutableStateFlow<List<SecureStore.PendingPrompt>>(emptyList())
    val pendingPromptOutbox = _pendingPromptOutbox.asStateFlow()
    private val _attachmentDispatchStages = MutableStateFlow<Map<String, AttachmentDispatchStage>>(emptyMap())
    val attachmentDispatchStages = _attachmentDispatchStages.asStateFlow()
    private val _phoneConversationsSupported = MutableStateFlow(false)
    val phoneConversationsSupported = _phoneConversationsSupported.asStateFlow()
    private val _piArchiveSupported = MutableStateFlow(false)
    val piArchiveSupported = _piArchiveSupported.asStateFlow()
    private val _piHostHistorySupported = MutableStateFlow(false)
    val piHostHistorySupported = _piHostHistorySupported.asStateFlow()
    private val _concurrentConversationsSupported = MutableStateFlow(false)
    val concurrentConversationsSupported = _concurrentConversationsSupported.asStateFlow()
    private val historyReplies = ConcurrentHashMap<String, CompletableDeferred<V3Message>>()
    @Volatile private var focusedChat: ConversationTarget? = null
    private val _conversationContinueSupported = MutableStateFlow(false)
    val conversationContinueSupported = _conversationContinueSupported.asStateFlow()
    private val conversationIntentMutex = Mutex()
    private val _attachmentsSupported = MutableStateFlow(false)
    val attachmentsSupported = _attachmentsSupported.asStateFlow()
    private val _artifactDownloadsSupported = MutableStateFlow(false)
    val artifactDownloadsSupported = _artifactDownloadsSupported.asStateFlow()
    private val artifactDownloadMutex = Mutex()
    private val _effectiveIdleTimeoutSeconds = MutableStateFlow<Int?>(null)
    val effectiveIdleTimeoutSeconds = _effectiveIdleTimeoutSeconds.asStateFlow()
    private val _idleTimeoutPending = MutableStateFlow(false)
    val idleTimeoutPending = _idleTimeoutPending.asStateFlow()
    private val _idleTimeoutError = MutableStateFlow<String?>(null)
    val idleTimeoutError = _idleTimeoutError.asStateFlow()
    @Volatile private var latestIdleTimeoutRequestSeconds: Int? = null
    @Volatile private var latestIdleTimeoutRequestId: String? = null
    private val pendingIdleTimeoutRequests = ConcurrentHashMap<String, EffectiveSettings>()
    @Volatile private var deferredIdleTimeoutSeconds: Int? = null
    private var modelCatalogHost: String? = null
    private val _modelCatalog = MutableStateFlow<com.kompi.app.protocol.generated.ModelCatalog?>(null)
    val modelCatalog = _modelCatalog.asStateFlow()
    private val _chatConfiguration = MutableStateFlow(store.selectedMachineId()?.let { store.chatModel(it) })
    val chatConfiguration = _chatConfiguration.asStateFlow()
    private val _confirmedConfiguration = MutableStateFlow(store.selectedMachineId()?.let { store.chatModel(it, confirmed = true) })
    val confirmedConfiguration = _confirmedConfiguration.asStateFlow()
    private val _modelsLoading = MutableStateFlow(false)
    val modelsLoading = _modelsLoading.asStateFlow()
    private val _modelError = MutableStateFlow<String?>(null)
    val modelError = _modelError.asStateFlow()
    private val modelReplies = ConcurrentHashMap<String, CompletableDeferred<com.kompi.app.protocol.generated.ModelCatalog>>()
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()
    // Filled synchronously by one bounded Pi record, then drained by the connection coroutine.
    // Suspending emission backpressures the network instead of silently losing speech chunks.
    private val pendingPiEvents = ArrayDeque<Event>()
    val piSession: PiSessionStore = PiSessionStore(
        needSnapshot = { scope.launch {
            val target = focusedChat
            runCatching {
                if (target == null) send(id("pi-snapshot"), V3Message.PiSnapshotGet)
                else if (_concurrentConversationsSupported.value) {
                    val chatStreams = outboxConnection?.chatStreams
                    if (chatStreams?.negotiated == true) chatStreams.refreshProjection(target.id)
                    else requestConversationSnapshot(target)
                }
            }.onFailure { if (it !is CancellationException) _lastError.value = "Could not load Pi session" }
        } },
        publicDelta = { request, session, conversation, sequence, text ->
            com.kompi.app.diagnostics.SpeechAudit.bindSession(request, session, conversation)
            pendingPiEvents.addLast(Event.ResponseDelta(request, session, conversation, sequence.toInt(), text))
            LocalDiagnostics.finish(LocalDiagnostics.Metric.FirstText, request)
        },
        settled = { request, session, conversation, text ->
            pendingPiEvents.addLast(Event.Reply(request, text, ResultState.Completed, session, conversation))
        },
        thinkingTitle = { request, session, conversation, title ->
            com.kompi.app.diagnostics.SpeechAudit.bindSession(request, session, conversation)
            pendingPiEvents.addLast(Event.Progress(request, "Thinking: $title", session, conversation))
        },
        liveActivity = { request, session, conversation, activity ->
            com.kompi.app.diagnostics.SpeechAudit.bindSession(request, session, conversation)
            pendingPiEvents.addLast(Event.Progress(request, activity, session, conversation))
        },
        usingTools = { request, session, conversation ->
            pendingPiEvents.addLast(Event.Progress(request, "Using tools", session, conversation))
        },
        coalesceFrames = com.kompi.app.BuildConfig.PI_FRAME_COALESCING,
    )
    @Volatile private var timingConnection: ChatTimingConnection? = null
    init { ResponsePerformance.initialize(appContext); ChatTiming.initialize(appContext) }
    private val closeCompletion = CompletableDeferred<Unit>()
    private val closing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val pushSyncMutex = Mutex()
    @Volatile private var pushConnectionSender: PushConnectionSender? = null
    @Volatile private var unifiedPushSupported = false
    @Volatile private var outboxConnection: OutboxConnection? = null
    private val promptAcknowledgements = ConcurrentHashMap<String, CompletableDeferred<AckState>>()
    private val agentSessionMutex = Mutex()
    private val lifecycleLock = Any()
    private var connectionGeneration = 0L
    @Volatile private var requestedPairing: SecureStore.Pairing? = null
    private var endpoint: Endpoint? = null
    @Volatile private var activeConnection: Connection? = null
    private var connectionJob: Job? = null
    private var heartbeatJob: Job? = null
    @Volatile private var sender: suspend (EnvelopeV3) -> Unit = { throw IllegalStateException("Not connected") }
    @Volatile private var ready = false
    @Volatile private var synchronizationCaughtUp = false
    @Volatile private var requestPagesComplete = false
    @Volatile private var sessionPagesComplete = false
    private val snapshotSessionIds = linkedSetOf<String>()
    private data class ScopedRequest(val hostId: String, val id: String)
    private val recentlyAcceptedIds = ConcurrentHashMap.newKeySet<ScopedRequest>()
    private class PermanentOutboxFailure(message: String) : IllegalStateException(message)
    private val networkAvailabilityMonitor = NetworkAvailabilityMonitor(appContext)
    private val networkMonitorJob = scope.launch {
        var observedGeneration = networkAvailabilityMonitor.state.value.generation
        var observedOnline = networkAvailabilityMonitor.state.value.isOnline
        networkAvailabilityMonitor.state.collect { availability ->
            if (availability.generation == observedGeneration) return@collect
            observedGeneration = availability.generation
            val availabilityChanged = availability.isOnline != observedOnline
            observedOnline = availability.isOnline
            Log.i(SYNC_TAG, "phase=network_changed elapsed_ms=${android.os.SystemClock.elapsedRealtime()} generation=${availability.generation} network=${availability.networkHandle ?: "none"} online=${availability.isOnline} availability_edge=$availabilityChanged")
            if (availabilityChanged) {
                requestedPairing?.let { reconnect(it, force = true, trigger = "network_availability_edge") }
            } else {
                Log.i(SYNC_TAG, "phase=network_identity_changed_no_rebind generation=${availability.generation} network=${availability.networkHandle ?: "none"}")
            }
        }
    }

    private val pendingOutboxRefresh = Mutex()
    private fun refreshPendingOutbox() {
        scope.launch {
            pendingOutboxRefresh.withLock {
                val hostId = store.selectedMachineId()
                val pending = hostId?.let { repository.pendingPrompts(it).map(::toSecurePrompt) }.orEmpty()
                if (store.selectedMachineId() == hostId) _pendingPromptOutbox.value = pending
            }
        }
    }

    private fun toSecurePrompt(row: StoredPrompt) = SecureStore.PendingPrompt(
        id = row.requestId,
        text = row.text,
        delivery = Delivery.parse(row.delivery),
        accepted = row.state == "accepted",
        machineId = row.hostId,
        attachmentIds = row.attachmentIds,
        configuration = row.configuration,
        targetConversationId = row.targetConversationId,
        waitingForConversation = row.state == "waiting-for-conversation",
        createIfMissing = row.createIfMissing,
    )

    init {
        IrohAndroid.installAndroidContext(appContext)
        scope.launch { refreshPendingOutbox() }
        scope.launch {
            unifiedPushRepository.changes.collect {
                val peerId = requestedPairing?.takeIf { it.confirmed }?.machineId
                if (peerId != null && ready && unifiedPushSupported) {
                    runCatching { flushPushOperations(peerId) }
                        .onFailure { Log.w(SYNC_TAG, "phase=push_sync_failed") }
                }
            }
        }
    }

    /** Replaces the sole connection loop. The prior loop is cancelled, its connection closed, then joined. */
    fun reconnect(pairing: SecureStore.Pairing) = reconnect(pairing, force = false)

    private fun reconnect(pairing: SecureStore.Pairing, force: Boolean, trigger: String = "pairing_or_resume") {
        synchronized(lifecycleLock) {
            check(!closing.get()) { "Iroh transport is closed" }
            if (!force && requestedPairing == pairing && connectionJob?.isActive == true) return
            // Fence push sync before changing authenticated peer identity. A settings callback must
            // never serialize the next host's registration on the previous host's connection.
            ready = false
            unifiedPushSupported = false
            pushConnectionSender = null
            requestedPairing = pairing
            modelCatalogHost = null
            _modelCatalog.value = null
            _chatConfiguration.value = store.selectedMachineId()?.let { store.chatModel(it) }
            _confirmedConfiguration.value = store.selectedMachineId()?.let { store.chatModel(it, confirmed = true) }
            modelReplies.values.forEach { it.cancel() }
            modelReplies.clear()
            _modelsLoading.value = false
            _modelError.value = null
            _lastError.value = null
            _confirmedPairing.value = null
            _piDialogs.value = emptyList()
            _piDialogError.value = null
            _androidToolRequests.value = emptyList()
            val generation = ++connectionGeneration
            _attachmentDispatchStages.value = emptyMap()
            Log.i(SYNC_TAG, "phase=reconnect_requested elapsed_ms=${android.os.SystemClock.elapsedRealtime()} generation=$generation forced=$force trigger=$trigger")
            LocalDiagnostics.begin(LocalDiagnostics.Metric.ReconnectToLive)
            val previous = connectionJob
            val staleEndpoint = endpoint
            endpoint = null
            previous?.cancel()
            heartbeatJob?.cancel(); heartbeatJob = null
            retireOutboxConnection()
            activeConnection?.close(0, "reconnect".toByteArray())
            activeConnection = null
            ready = false
            _phoneConversationsSupported.value = false
            _piArchiveSupported.value = false
            _piHostHistorySupported.value = false
            _concurrentConversationsSupported.value = false
            retireHistoryReplies()
            _attachmentsSupported.value = false
            _artifactDownloadsSupported.value = false
            _effectiveIdleTimeoutSeconds.value = null
            _idleTimeoutPending.value = true
            _idleTimeoutError.value = null
            pendingIdleTimeoutRequests.clear()
            latestIdleTimeoutRequestSeconds = null
            latestIdleTimeoutRequestId = null
            deferredIdleTimeoutSeconds = null
            connectionJob = scope.launch {
                // Shutdown aborts native connect/read operations which may outlive coroutine cancellation.
                var endpointWasReleased = shutdownEndpoint(staleEndpoint, "endpoint_shutdown_for_rebind_generation_$generation")
                previous?.join()
                if (!isCurrent(generation)) return@launch
                var confirmed = pairing.confirmed
                val pairRequestId = id("pair")
                while (isCurrent(generation)) {
                    if (!networkAvailabilityMonitor.state.value.isOnline) {
                        _state.value = "Disconnected"
                        Log.i(SYNC_TAG, "phase=network_unavailable_wait")
                        networkAvailabilityMonitor.state.first { it.isOnline }
                        if (!isCurrent(generation)) return@launch
                    }
                    val audit = ChatTimingConnection()
                    var auditSync: Job? = null
                    var connectionOutbox: OutboxConnection? = null
                    audit.record(Stage.PhoneConnectStart)
                    try {
                        _state.value = "Connecting"
                        var peerSupportsPhoneConversations = false
                        var peerSupportsAttachments = false
                        val ep = endpoint ?: run {
                            val candidate = Endpoint.bind(EndpointOptions(
                                preset = presetN0(), secretKey = store.identitySeed(), alpns = listOf(ALPN)
                            ))
                            val installed = synchronized(lifecycleLock) {
                                if (generation != connectionGeneration || requestedPairing == null || endpoint != null) {
                                    false
                                } else {
                                    endpoint = candidate
                                    true
                                }
                            }
                            if (!installed) {
                                shutdownEndpoint(candidate, "endpoint_shutdown_stale_generation")
                                if (!isCurrent(generation)) return@launch
                                throw IllegalStateException("Connection endpoint ownership changed")
                            }
                            Log.i(SYNC_TAG, "phase=${if (endpointWasReleased) "endpoint_recreated" else "endpoint_bound"} generation=$generation")
                            endpointWasReleased = false
                            candidate
                        }
                        val ticket = EndpointTicket.fromString(pairing.endpointTicket)
                        val peerId = IrohEndpointIdentity.fromBytes(ticket.endpointAddr().id().toBytes())
                        Log.i(SYNC_TAG, "phase=quic_connect_started")
                        val conn = ep.connect(ticket.endpointAddr(), ALPN)
                        audit.record(Stage.PhoneQuicConnected)
                        Log.i(SYNC_TAG, "phase=quic_connected")
                        val installedConnection = synchronized(lifecycleLock) {
                            if (generation != connectionGeneration || requestedPairing == null || endpoint !== ep) false
                            else {
                                activeConnection = conn
                                true
                            }
                        }
                        if (!installedConnection) {
                            conn.close(0, "stale-generation".toByteArray())
                            throw CancellationException("Connection generation changed before promotion")
                        }
                        val authenticatedPeerId = IrohEndpointIdentity.fromBytes(conn.remoteId().toBytes())
                        if (authenticatedPeerId != peerId) throw SecurityException("Iroh authenticated peer ID does not match the scanned ticket")
                        audit.record(Stage.PhoneAuthenticated)
                        Log.i(SYNC_TAG, "phase=peer_authenticated")
                        val stream = conn.openBi()
                        val send = stream.send()
                        send.setPriority(1)
                        val writeMutex = Mutex()
                        val connectionSender: suspend (EnvelopeV3) -> Unit = { envelope ->
                            writeMutex.withLock {
                                if (!isCurrent(generation) || activeConnection !== conn) throw CancellationException("Iroh connection changed before write")
                                val frame = V3FrameCodec.frame(V3WireCodec.encode(envelope))
                                val attempt = when (val message = envelope.message) {
                                    is V3Message.TimedPrompt -> audit.match(envelope.id)?.takeIf { it.id == message.attemptId }
                                    is V3Message.ConversationPrompt -> audit.match(envelope.id)?.takeIf { it.id == message.attemptId }
                                    is V3Message.Prompt -> audit.match(envelope.id)
                                    else -> null
                                }
                                attempt?.record(Stage.PhoneWriteStart)
                                try {
                                    send.writeAll(frame)
                                } catch (failure: Exception) {
                                    // A partial frame cannot be retried on this control stream.
                                    conn.close(1, "control write failed".toByteArray())
                                    throw failure
                                }
                                attempt?.record(Stage.PhoneWriteComplete)
                            }
                        }
                        val outbox = OutboxConnection(peerId, generation, conn, connectionSender, audit)
                        connectionOutbox = outbox
                        // A first-time staged pairing has no trusted machineId until Paired; bind this
                        // sender to the already-verified ticket instead of rejecting that handshake.
                        val senderInstalled = synchronized(lifecycleLock) {
                            if (generation != connectionGeneration || requestedPairing?.endpointTicket != pairing.endpointTicket || activeConnection !== conn) false
                            else {
                                sender = connectionSender
                                outboxConnection = outbox
                                outbox.chatStreams.focus(focusedChat?.takeIf { it.hostId == peerId }?.id)
                                timingConnection = audit
                                pushConnectionSender = PushConnectionSender(generation, peerId, connectionSender)
                                true
                            }
                        }
                        if (!senderInstalled) throw CancellationException("Connection generation changed before sender promotion")
                        val clientEndpointId = IrohEndpointIdentity.fromBytes(ep.id().toBytes())
                        // The wire shape requires a phone TTL; the daemon treats this Room-confirmed value as advisory only.
                        val hostSettings = repository.hostSettings(peerId)
                        val requestedIdleTimeoutSeconds = hostSettings?.effectiveIdleTimeoutSeconds?.toInt()
                            ?: DEFAULT_IDLE_TIMEOUT_SECONDS
                        Log.i(SYNC_TAG, "phase=hello_sent timeout_source=${if (hostSettings?.effectiveIdleTimeoutSeconds != null) "room_effective" else "generated_default"}")
                        latestIdleTimeoutRequestSeconds = requestedIdleTimeoutSeconds
                        val helloRequestId = id("hello")
                        send(helloRequestId, V3Message.Hello(
                            capabilities = listOf("prompt", "cancel", "phone-conversations", "pi-session-events", "concurrent-conversations", ATTACHMENTS_CAPABILITY, "pi-rpc-ui", "device-notifications", com.kompi.app.protocol.generated.RICH_DEVICE_NOTIFICATIONS_CAPABILITY, com.kompi.app.protocol.generated.REPLY_NOTIFICATIONS_CAPABILITY, com.kompi.app.protocol.generated.UNIFIED_PUSH_CAPABILITY) +
                                if (com.kompi.app.BuildConfig.CHAT_STREAMS) listOf(ScopedChatStreams.CAPABILITY) else emptyList(),
                            endpoint = clientEndpointId,
                            session = null,
                            idleTimeoutSeconds = requestedIdleTimeoutSeconds
                        ))
                        _state.value = if (confirmed) "Reconnecting" else "Waiting for pairing confirmation"
                        var pairSent = false
                        val recv = stream.recv()
                        while (isCurrent(generation)) {
                            val sizeBytes = recv.readExact(4u)
                            val size = ByteBuffer.wrap(sizeBytes).order(ByteOrder.BIG_ENDIAN).int
                            if (size !in 1..MAX_PAYLOAD) throw WireFormatException("Invalid network frame length")
                            val envelope = V3WireCodec.decode(recv.readExact(size.toUInt()))
                            if (!isCurrent(generation) || activeConnection !== conn) break
                            val message = envelope.message
                            when (message) {
                                is V3Message.ChatTimingPage -> audit.receivePage(envelope.id, message)
                                is V3Message.ChatTimingGet, is V3Message.TimedPrompt -> throw WireFormatException("Unexpected chat timing request")
                                is V3Message.ModelsResult -> modelReplies.remove(envelope.id)?.complete(message.catalog)
                                is V3Message.PiHistoryPageResult, is V3Message.ConversationDeleted ->
                                    historyReplies[envelope.id]?.complete(message)
                                is V3Message.PiHistoryPage, is V3Message.ConversationDelete ->
                                    throw WireFormatException("Unexpected session history request from computer")
                                is V3Message.PiFrame -> receivePiFrame(outbox, null, message.frame)
                                is V3Message.PiResetRequired -> {
                                    if (!outbox.chatStreams.negotiated || focusedChat == null) piSession.resetRequired()
                                    else outbox.chatStreams.resetPrimaryProjection()
                                }
                                is V3Message.PiSessionEmpty -> bindLegacyConversation(null)
                                is V3Message.GenerationStats -> ResponsePerformance.stats(message.requestId, message.stats)
                                V3Message.PiSnapshotGet, is V3Message.PiConversationSnapshotGet, is V3Message.PiArchiveGet ->
                                    throw WireFormatException("Unexpected Pi snapshot request")
                                is V3Message.PiUiState -> {
                                    _piDialogs.value = message.requests.map(PiDialogRequest::fromWire)
                                    _piDialogError.value = null
                                }
                                is V3Message.PiConversationUiState -> {
                                    val requests = message.requests.map(PiDialogRequest::fromWire)
                                    val conversation = message.conversationId
                                    check(requests.all { it.conversationId == conversation }) { "Pi dialog belongs to a different session" }
                                    _piDialogs.update { current -> current.filterNot { it.conversationId == conversation } + requests }
                                    _piDialogError.value = null
                                }
                                is V3Message.PiUiResponse -> throw WireFormatException("Unexpected Pi UI response from host")
                                is V3Message.AndroidToolRequest -> receiveAndroidTool(message, generation)
                                is V3Message.AndroidToolResponse -> throw WireFormatException("Unexpected Android tool response from host")
                                is V3Message.DeviceNotificationRequest -> {
                                    if (envelope.id != message.requestId) throw WireFormatException("Device notification request identity mismatch")
                                    receiveDeviceNotification(message, generation)
                                }
                                is V3Message.DeviceRichNotificationRequest -> {
                                    if (envelope.id != message.requestId) throw WireFormatException("Device notification request identity mismatch")
                                    receiveDeviceNotification(message.requestId, message.timeoutMs, generation) { hostId, expiresAt ->
                                        com.kompi.app.assistant.AgentNotifications.postRichForHost(appContext, hostId, message.requestId, expiresAt, message.notification)
                                    }
                                }
                                is V3Message.DeviceReplyNotificationRequest -> {
                                    if (envelope.id != message.requestId) throw WireFormatException("Reply notification request identity mismatch")
                                    receiveDeviceNotification(message.requestId, message.timeoutMs, generation) { hostId, expiresAt ->
                                        com.kompi.app.assistant.AgentNotifications.postReplyForHost(appContext, hostId, message.requestId, expiresAt, message.reply)
                                    }
                                }
                                is V3Message.DeviceNotificationResponse -> throw WireFormatException("Unexpected device notification response from host")
                                is V3Message.PushRegistrationResult -> handlePushRegistrationResult(
                                    pairing.machineId,
                                    envelope.id,
                                    message,
                                )
                                is V3Message.AndroidToolResolved -> synchronized(lifecycleLock) {
                                    _androidToolRequests.value = _androidToolRequests.value.filterNot {
                                        it.conversationId == message.conversationId && it.requestId == message.requestId
                                    }
                                }
                                is V3Message.Paired -> {
                                    if (confirmed || !pairSent || envelope.id != pairRequestId || message.endpoint != peerId) throw SecurityException("Unexpected pairing confirmation")
                                    synchronized(lifecycleLock) {
                                        if (!isCurrent(generation)) throw CancellationException("Pairing attempt was cancelled")
                                        val trusted = store.confirmPairing(pairing, peerId)
                                        requestedPairing = trusted
                                        confirmed = true
                                        _confirmedPairing.value = trusted
                                    }
                                    store.markMachineSeen(peerId)
                                    refreshPendingOutbox()
                                    agentSessionMutex.withLock {
                                        becomeReady(conn, peerId, peerSupportsPhoneConversations)
                                    }
                                }
                                is V3Message.Pair -> throw SecurityException("Unexpected pairing request from peer")
                                is V3Message.Hello -> {
                                    Log.i(SYNC_TAG, "phase=server_hello confirmed=$confirmed ready=$ready")
                                    if (message.endpoint != null && message.endpoint != peerId) throw SecurityException("Peer endpoint ID does not match its authenticated Iroh ticket")
                                    outbox.chatStreams.negotiated = com.kompi.app.BuildConfig.CHAT_STREAMS &&
                                        ScopedChatStreams.CAPABILITY in message.capabilities &&
                                        "concurrent-conversations" in message.capabilities
                                    Log.i(SYNC_TAG, "phase=chat_streams_negotiated enabled=${outbox.chatStreams.negotiated}")
                                    audit.supported = ChatTiming.CAPABILITY in message.capabilities
                                    if (audit.supported && auditSync == null) auditSync = scope.launch {
                                        audit.pull(peerId, connectionSender) { timingConnection === audit && _state.value == "Live" }
                                    }
                                    peerSupportsPhoneConversations = "phone-conversations" in message.capabilities
                                    unifiedPushSupported = com.kompi.app.protocol.generated.UNIFIED_PUSH_CAPABILITY in message.capabilities
                                    if (!unifiedPushSupported) {
                                        com.kompi.app.assistant.push.PushHostId.parse(pairing.machineId)?.let(unifiedPushRepository::markCapabilityUnsupported)
                                    }
                                    require("pi-session-events" in message.capabilities) { "Daemon does not support Pi session synchronization" }
                                    peerSupportsAttachments = ATTACHMENTS_CAPABILITY in message.capabilities
                                    _attachmentsSupported.value = peerSupportsAttachments
                                    _artifactDownloadsSupported.value = ARTIFACT_DOWNLOADS_CAPABILITY in message.capabilities
                                    _phoneConversationsSupported.value = peerSupportsPhoneConversations
                                    _piArchiveSupported.value = "pi-archive" in message.capabilities
                                    _piHostHistorySupported.value = "pi-host-history" in message.capabilities
                                    _concurrentConversationsSupported.value = "concurrent-conversations" in message.capabilities
                                    store.rememberConcurrentSupport(peerId, _concurrentConversationsSupported.value)
                                    _conversationContinueSupported.value = "conversation-continue" in message.capabilities
                                    store.rememberContinuationSupport(peerId, _conversationContinueSupported.value)
                                    if (confirmed && !ready) {
                                        store.markMachineSeen(peerId)
                                        becomeReady(conn, peerId, peerSupportsPhoneConversations)
                                    } else if (PairHandshake.shouldSendCredential(confirmed, pairSent)) {
                                        // Credentials are sent only after the QUIC peer identity and server Hello are verified.
                                        send(pairRequestId, V3Message.Pair(pairing.name, pairing.secret))
                                        pairSent = true
                                        _state.value = "Pairing"
                                    }
                                }
                                is V3Message.Prompt -> throw WireFormatException("Unexpected prompt from host")
                                is V3Message.Ack -> {
                                    if (envelope.id.startsWith("pi-ui-response-")) continue
                                    val configureRequest = pendingIdleTimeoutRequests[envelope.id]
                                    if (envelope.id == helloRequestId || configureRequest != null) {
                                        val ackState = AckState.parse(message.state)
                                        if (ackState != AckState.Accepted) {
                                            if (configureRequest != null && ackState in setOf(AckState.Queued, AckState.Received)) continue
                                            throw WireFormatException("Idle-timeout acknowledgement was not accepted")
                                        }
                                        if (configureRequest != null) pendingIdleTimeoutRequests.remove(envelope.id)
                                        val effective = message.idleTimeoutSeconds ?: throw WireFormatException("Idle-timeout acknowledgement omitted effective timeout")
                                        if (envelope.id == helloRequestId) {
                                            Log.i(SYNC_TAG, "phase=hello_ack accepted=true effective_present=true")
                                            repository.observeEffectiveIdleTimeout(peerId, effective.toLong())
                                        }
                                        validateIdleTimeout(effective)
                                        val expected = configureRequest?.idleTimeoutSeconds ?: requestedIdleTimeoutSeconds
                                        val isCurrentRequest = latestIdleTimeoutRequestSeconds == expected && if (configureRequest == null) {
                                            latestIdleTimeoutRequestId == null && deferredIdleTimeoutSeconds == null
                                        } else latestIdleTimeoutRequestId == envelope.id
                                        if (isCurrentRequest) {
                                            _effectiveIdleTimeoutSeconds.value = effective
                                            _idleTimeoutPending.value = false
                                            _idleTimeoutError.value = null
                                        }
                                        continue
                                    }
                                    receiveChatReply(outbox, null, envelope)
                                }
                                is V3Message.AttachmentReady, is V3Message.AttachmentProgress, is V3Message.AttachmentAccepted -> {
                                    if (outbox.attachmentReplies[envelope.id]?.complete(message) == true && message is V3Message.AttachmentAccepted) {
                                        emitIfCurrent(generation, Event.AttachmentAccepted(message.uploadId, message.fileName, message.length))
                                    }
                                }
                                is V3Message.AttachmentOffer, is V3Message.AttachmentChunk, is V3Message.AttachmentFinish -> throw WireFormatException("Unexpected attachment request from Pi")
                                is V3Message.ArtifactGet, is V3Message.ArtifactReady, is V3Message.ArtifactData, V3Message.ArtifactEnd -> throw WireFormatException("Unexpected artifact frame on the control stream")
                                is V3Message.Error -> {
                                    if (envelope.id.startsWith("history-page-") || envelope.id.startsWith("history-delete-")) {
                                        historyReplies[envelope.id]?.complete(message)
                                        continue
                                    }
                                    if (envelope.id.startsWith("selected-snapshot-")) continue
                                    if (envelope.id.startsWith("scoped-snapshot-")) {
                                        outbox.chatStreams.snapshotFailed(null, envelope.id)
                                        continue
                                    }
                                    val modelReply = modelReplies.remove(envelope.id)
                                    if (modelReply != null) {
                                        modelReply.completeExceptionally(IllegalStateException(message.message))
                                        continue
                                    }
                                    if (envelope.id.startsWith("pi-ui-response-")) {
                                        _piDialogError.value = message.message
                                        continue
                                    }
                                    promptAcknowledgements.remove(envelope.id)?.completeExceptionally(IllegalStateException(message.message))
                                    if (envelope.id.startsWith("attachment-offer-") && outbox.expectReply(envelope.id) == null) {
                                        outbox.attachmentReplies[envelope.id]?.completeExceptionally(IllegalStateException(message.message))
                                        continue
                                    }
                                    if (envelope.id == helloRequestId) throw WireFormatException("Daemon rejected phone Hello: ${message.message}")
                                    val failedTimeout = pendingIdleTimeoutRequests.remove(envelope.id)
                                    if (failedTimeout != null) {
                                        repository.rejectPendingSettings(peerId, failedTimeout)
                                        if (latestIdleTimeoutRequestSeconds == failedTimeout.idleTimeoutSeconds && latestIdleTimeoutRequestId == envelope.id) {
                                            _idleTimeoutPending.value = false
                                            _idleTimeoutError.value = message.message
                                        }
                                        enterLiveIfCaughtUp(generation)
                                        continue
                                    }
                                    receiveChatReply(outbox, null, envelope)
                                }
                                is V3Message.SyncSnapshot -> {
                                    _state.value = "Synchronizing"
                                    repository.applySnapshot(peerId, message)
                                    message.outstandingRequests.forEach {
                                        auditRequestReplay(it, audit.id)
                                        outbox.resolveFromLedger(it.requestId, it.outcome != "rejected")
                                    }
                                    _effectiveIdleTimeoutSeconds.value = message.settings.idleTimeoutSeconds
                                    val durableSettings = repository.hostSettings(peerId)
                                    Log.i(SYNC_TAG, "phase=sync_snapshot cursor_present=${message.cursor != "0"} more_requests=${message.hasMoreRequests} session_count=${message.sessions.size} room_pending_idle=${durableSettings?.pendingIdleTimeoutSeconds != null} room_pending_retention=${durableSettings?.pendingUploadRetentionSeconds != null}")
                                    val deferredIdle = deferredIdleTimeoutSeconds
                                    // A snapshot is authoritative for any request IDs in flight before it.
                                    pendingIdleTimeoutRequests.clear()
                                    latestIdleTimeoutRequestId = null
                                    deferredIdleTimeoutSeconds = null
                                    val idle = durableSettings?.pendingIdleTimeoutSeconds
                                    val retention = durableSettings?.pendingUploadRetentionSeconds
                                    if (idle != null || retention != null || deferredIdle != null) {
                                        val updateId = id("settings-reconcile")
                                        val effectiveIdle = (idle ?: deferredIdle?.toLong() ?: durableSettings?.effectiveIdleTimeoutSeconds ?: message.settings.idleTimeoutSeconds.toLong()).toInt()
                                        val effectiveRetention = (retention ?: durableSettings?.effectiveUploadRetentionSeconds ?: message.settings.uploadRetentionSeconds.toLong()).toInt()
                                        val update = EffectiveSettings(effectiveIdle, effectiveRetention)
                                        pendingIdleTimeoutRequests[updateId] = update
                                        latestIdleTimeoutRequestId = updateId
                                        latestIdleTimeoutRequestSeconds = effectiveIdle
                                        send(updateId, V3Message.SettingsUpdate(update, updateId))
                                    } else {
                                        latestIdleTimeoutRequestSeconds = message.settings.idleTimeoutSeconds
                                    }
                                    _idleTimeoutPending.value = pendingIdleTimeoutRequests.isNotEmpty()
                                    Log.i(SYNC_TAG, "phase=settings_reconciled pending_requests=${pendingIdleTimeoutRequests.size} deferred=${deferredIdleTimeoutSeconds != null}")
                                    snapshotSessionIds.clear()
                                    bindLegacyConversation(message.sessions.firstOrNull { it.state == "active" }?.conversationId)
                                    message.sessions.forEach { metadata ->
                                        snapshotSessionIds.add(metadata.conversationId)
                                        val converted = metadata.toDomain()
                                        updateActiveConversation(converted)
                                        emitIfCurrent(generation, Event.ConversationChanged(converted))
                                    }
                                    send(id("sync-catchup"), V3Message.SyncPage(after = message.cursor, limit = MAX_SYNC_PAGE))
                                    requestPagesComplete = !message.hasMoreRequests
                                    if (message.hasMoreRequests) send(id("request-page"), V3Message.RequestPage(after = null, limit = MAX_SYNC_PAGE))
                                    sessionPagesComplete = false
                                    send(id("session-page"), V3Message.SessionPage(after = null, limit = MAX_SYNC_PAGE))
                                }
                                is V3Message.SyncEvents -> {
                                    for (syncEvent in message.events) {
                                        repository.applyDurableEvent(peerId, syncEvent.cursor, syncEvent.event)
                                        val catchup = _state.value != "Live"
                                        val stage = if (catchup) Stage.PhoneLedgerReplayPersisted else Stage.PhoneDeliveryPersisted
                                        val origin = if (catchup) Origin.Catchup else Origin.Live
                                        when (val event = syncEvent.event) {
                                            is com.kompi.app.protocol.generated.DurableEvent.RequestChanged -> {
                                                val request = event.payload.request
                                                ChatTiming.record(stage, request = request.requestId, conversation = request.conversationId,
                                                    connection = audit.id, origin = origin, outcome = request.outcome)
                                                outbox.resolveFromLedger(request.requestId, request.outcome != "rejected")
                                            }
                                            is com.kompi.app.protocol.generated.DurableEvent.ResponseCompleted -> {
                                                ChatTiming.record(stage, request = event.payload.requestId, connection = audit.id, origin = origin, outcome = event.payload.outcome)
                                                outbox.resolveFromLedger(event.payload.requestId, event.payload.outcome != "rejected")
                                            }
                                            else -> Unit
                                        }
                                        if (syncEvent.event is com.kompi.app.protocol.generated.DurableEvent.SessionChanged) {
                                            val conversation = syncEvent.event.payload.session.toDomain()
                                            updateActiveConversation(conversation)
                                            emitIfCurrent(generation, Event.ConversationChanged(conversation))
                                        }
                                        emitIfCurrent(generation, Event.DurableSyncEvent(syncEvent))
                                    }
                                    Log.i(SYNC_TAG, "phase=durable_events_applied count=${message.events.size} page_complete=${message.events.size < MAX_SYNC_PAGE}")
                                    if (message.events.size >= MAX_SYNC_PAGE) {
                                        send(id("sync-catchup"), V3Message.SyncPage(after = message.nextCursor, limit = MAX_SYNC_PAGE))
                                    } else {
                                        synchronizationCaughtUp = true
                                        Log.i(SYNC_TAG, "phase=cursor_caught_up")
                                        enterLiveIfCaughtUp(generation)
                                    }
                                }
                                is V3Message.SyncResetRequired -> send(id("sync"), V3Message.Sync(after = null))
                                is V3Message.SyncPage -> throw WireFormatException("Unexpected sync-page request from host")
                                is V3Message.RequestPageResult -> {
                                    repository.reconcileOutstandingRequests(peerId, message.requests)
                                    message.requests.forEach {
                                        auditRequestReplay(it, audit.id)
                                        outbox.resolveFromLedger(it.requestId, it.outcome != "rejected")
                                    }
                                    Log.i(SYNC_TAG, "phase=request_page_received count=${message.requests.size} more=${message.nextCursor != null}")
                                    message.nextCursor?.let { send(id("request-page"), V3Message.RequestPage(after = it, limit = MAX_SYNC_PAGE)) } ?: run {
                                        requestPagesComplete = true
                                        Log.i(SYNC_TAG, "phase=request_pages_complete")
                                        enterLiveIfCaughtUp(generation)
                                    }
                                }
                                is V3Message.Sync -> throw WireFormatException("Unexpected sync request from host")
                                is V3Message.RequestPage -> throw WireFormatException("Unexpected request-page from host")
                                is V3Message.SessionPageResult -> {
                                    message.sessions.forEach { metadata ->
                                        snapshotSessionIds.add(metadata.conversationId)
                                        val conversation = metadata.toDomain()
                                        repository.upsertConversation(peerId, conversation)
                                        updateActiveConversation(conversation)
                                    }
                                    Log.i(SYNC_TAG, "phase=session_page_received count=${message.sessions.size} more=${message.nextCursor != null}")
                                    message.nextCursor?.let { send(id("session-page"), V3Message.SessionPage(after = it, limit = MAX_SYNC_PAGE)) } ?: run {
                                        repository.reconcileSessions(peerId, snapshotSessionIds.toList())
                                        sessionPagesComplete = true
                                        Log.i(SYNC_TAG, "phase=session_pages_complete")
                                        enterLiveIfCaughtUp(generation)
                                    }
                                }
                                is V3Message.SessionPage -> throw WireFormatException("Unexpected session-page request from host")
                                is V3Message.SettingsConfirmed -> {
                                    pendingIdleTimeoutRequests.remove(message.updateId)
                                    repository.confirmSettings(peerId, message.settings.idleTimeoutSeconds.toLong(), message.settings.uploadRetentionSeconds.toLong())
                                    _effectiveIdleTimeoutSeconds.value = message.settings.idleTimeoutSeconds
                                    _idleTimeoutPending.value = pendingIdleTimeoutRequests.isNotEmpty()
                                    Log.i(SYNC_TAG, "phase=settings_confirmed pending_requests=${pendingIdleTimeoutRequests.size}")
                                    _idleTimeoutError.value = null
                                    enterLiveIfCaughtUp(generation)
                                }
                                is V3Message.SettingsRejected -> {
                                    val rejected = pendingIdleTimeoutRequests.remove(message.updateId)
                                    repository.rejectPendingSettings(peerId, rejected)
                                    _idleTimeoutPending.value = pendingIdleTimeoutRequests.isNotEmpty()
                                    _idleTimeoutError.value = message.message
                                    enterLiveIfCaughtUp(generation)
                                }
                                is V3Message.PreacceptanceRejected, is V3Message.ResponseComplete, is V3Message.Conversation,
                                is V3Message.Status -> receiveChatReply(outbox, null, envelope)
                                is V3Message.ConversationClose -> throw WireFormatException("Unexpected conversation-close from host")
                                is V3Message.AttachmentExpired -> emitIfCurrent(generation, Event.AttachmentFailure(message.uploadId, "Upload expired"))
                                V3Message.Ping -> send(envelope.id, V3Message.Pong)
                                V3Message.Pong, is V3Message.Cancel -> Unit
                                else -> Unit
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        if (failure is CancellationException) throw failure
                        audit.record(Stage.PhoneConnectionFailed)
                        audit.interrupt()
                        piSession.flushTimingUpdates(audit.id)
                        val safeReason = sanitizeConnectionReason(failure.message)
                        _piDialogs.value = emptyList()
                        _piDialogError.value = null
                        _androidToolRequests.value = emptyList()
                        val failedEndpoint = synchronized(lifecycleLock) {
                            if (generation != connectionGeneration || requestedPairing == null) return@launch
                            Log.w(SYNC_TAG, "phase=connection_failed generation=$generation reason=$safeReason")
                            heartbeatJob?.cancel(); heartbeatJob = null
                            activeConnection?.close(0, "reconnect".toByteArray())
                            activeConnection = null
                            val ownedEndpoint = endpoint
                            endpoint = null
                            retireOutboxConnection()
                            ready = false
                            _attachmentDispatchStages.value = emptyMap()
                            _phoneConversationsSupported.value = false
                            _piArchiveSupported.value = false
                            _piHostHistorySupported.value = false
                            _concurrentConversationsSupported.value = false
                            retireHistoryReplies()
                            _attachmentsSupported.value = false
                            _artifactDownloadsSupported.value = false
                            _state.value = "Reconnecting"
                            _lastError.value = safeReason
                            ownedEndpoint
                        }
                        promptAcknowledgements.values.forEach { it.completeExceptionally(IllegalStateException("Iroh connection lost")) }
                        promptAcknowledgements.clear()
                        if (failedEndpoint != null) {
                            endpointWasReleased = shutdownEndpoint(failedEndpoint, "endpoint_shutdown_for_rebind_generation_$generation")
                        }
                        emitIfCurrent(generation, Event.Failure("", "connection", safeReason))
                        delay(2_000)
                    } finally {
                        connectionOutbox?.close()
                        auditSync?.cancel()
                        audit.stop()
                        piSession.flushTimingUpdates(audit.id)
                    }
                }
            }
        }
    }

    private suspend fun receivePiFrame(
        outbox: OutboxConnection, route: ScopedChatStreams.Route?, frame: com.kompi.app.protocol.generated.PiFrame,
    ) {
        val projected = synchronized(lifecycleLock) {
            val target = focusedChat
            if (!outbox.isCurrent() || (target != null && target.hostId != outbox.hostId)) return
            fun apply(): Boolean {
                piSession.accept(frame, outbox.audit.id, allowUnobservedPrefix = target != null)
                retireOtherConversationTools()
                return frame.kind == "snapshot" && frame.last && piSession.installedSnapshot(frame)
            }
            try {
                if (outbox.chatStreams.negotiated) outbox.chatStreams.acceptFrame(route, frame, ::apply)
                else if (target == null || target.id == frame.conversationId) apply()
                pendingPiEvents.toList()
            } finally {
                pendingPiEvents.clear()
            }
        }
        for (event in projected) {
            if (outbox.isCurrent() && route?.isCurrent() != false &&
                (focusedChat?.id ?: frame.conversationId) == frame.conversationId) emitIfCurrent(outbox.generation, event)
        }
    }

    private suspend fun receiveScopedEnvelope(outbox: OutboxConnection, route: ScopedChatStreams.Route, envelope: EnvelopeV3) {
        when (val message = envelope.message) {
            is V3Message.PiFrame -> receivePiFrame(outbox, route, message.frame)
            is V3Message.Error -> {
                if (envelope.id.startsWith("scoped-snapshot-")) outbox.chatStreams.snapshotFailed(route, envelope.id)
                else receiveChatReply(outbox, route, envelope)
            }
            else -> receiveChatReply(outbox, route, envelope)
        }
    }

    private suspend fun receiveChatReply(outbox: OutboxConnection, route: ScopedChatStreams.Route?, envelope: EnvelopeV3) {
        if (!outbox.isCurrent() || route?.isCurrent() == false) return
        val peerId = outbox.hostId
        val audit = outbox.audit
        val generation = outbox.generation
        val message = envelope.message
        val chat = when (message) {
            is V3Message.Ack -> message.conversationId
            is V3Message.Error -> message.conversationId
            is V3Message.Status -> message.conversationId
            is V3Message.ResponseComplete -> message.conversationId
            is V3Message.Conversation -> message.conversationId
            else -> null
        }
        if (route != null && chat != null && route.chat != chat) throw WireFormatException("Reply belongs to another conversation")
        if (!outbox.matchesReplyRoute(envelope.id, route)) return
        when (message) {
            is V3Message.Ack -> {
                val state = AckState.parse(message.state)
                if ((state == AckState.Accepted || state == AckState.Queued) &&
                    outbox.expectReply(envelope.id, message.conversationId, route) != null) {
                    val attempt = audit.match(envelope.id)
                    attempt?.record(Stage.PhoneAckDecoded, message.conversationId)
                    ResponsePerformance.acknowledge(envelope.id)
                    LocalDiagnostics.finish(LocalDiagnostics.Metric.SendToAck, envelope.id)
                    message.conversationId?.let { repository.bindPromptToConversation(peerId, envelope.id, it) }
                    val accepted = repository.pendingPrompts(peerId).firstOrNull { it.requestId == envelope.id }
                    attachmentDraftStore.markSent(accepted?.attachmentIds?.toSet().orEmpty())
                    message.configuration?.let { configuration ->
                        check(accepted?.configuration == null || accepted.configuration == configuration) { "Host acknowledged a different model configuration" }
                        store.saveChatModel(peerId, configuration, confirmed = true)
                        if (outbox.isCurrent()) _confirmedConfiguration.value = configuration
                    }
                    repository.markPromptAccepted(peerId, envelope.id)
                    attempt?.let { it.record(Stage.PhoneAckPersisted, message.conversationId); audit.finish(it) }
                    audit.refresh()
                    synchronized(lifecycleLock) {
                        if (outbox.isCurrent() && route?.isCurrent() != false) {
                            _attachmentDispatchStages.update { it - envelope.id }
                            rememberAccepted(peerId, envelope.id)
                            promptAcknowledgements.remove(envelope.id)?.complete(state)
                        }
                    }
                    outbox.resolve(envelope.id, OutboxResult.Advance)
                    refreshPendingOutbox()
                    message.conversationId?.let { chat -> outbox.requestScopedSnapshot(chat) }
                }
                emitIfCurrent(generation, Event.Delivery(envelope.id, state, message.sessionId, message.conversationId))
            }
            is V3Message.Status -> {
                emitIfCurrent(generation, Event.Progress(envelope.id, message.activity, message.sessionId, message.conversationId))
            }
            is V3Message.Error -> {
                audit.match(envelope.id)?.let { it.record(Stage.PhoneErrorDecoded, message.conversationId); audit.finish(it) }
                if (outbox.expectReply(envelope.id, message.conversationId, route) != null) {
                    val rejected = repository.recordPromptRejection(peerId, envelope.id, RejectionDisposition.Remove)
                    outbox.resolve(envelope.id, OutboxResult.Advance)
                    if (!rejected) return // The durable ledger already resolved this prompt.
                }
                refreshPendingOutbox()
                emitIfCurrent(generation, Event.Failure(envelope.id, message.code, message.message, message.sessionId, message.conversationId))
            }
            is V3Message.PreacceptanceRejected -> {
                if (envelope.id != message.requestId) throw WireFormatException("Rejection ID does not match the request")
                val waiting = outbox.expectReply(message.requestId, route = route) ?: return
                // An ambiguous replay first checks the host ledger without reuploading.
                // Only an explicit missing-file rejection authorizes a new upload.
                val retryable = message.retryable || (waiting.probingAttachments && message.code == "attachment-unavailable")
                val disposition = when {
                    retryable -> RejectionDisposition.Retry
                    message.code == "conversation-not-current" -> RejectionDisposition.Hold
                    else -> RejectionDisposition.Remove
                }
                val rejected = repository.recordPromptRejection(peerId, message.requestId, disposition)
                if (!rejected) {
                    outbox.resolve(message.requestId, OutboxResult.Advance)
                    return
                }
                audit.match(message.requestId)?.let {
                    it.record(if (retryable) Stage.PhoneRetryableRejection else Stage.PhoneRejected)
                    audit.finish(it)
                }
                audit.refresh()
                val result = when (disposition) {
                    RejectionDisposition.Retry -> OutboxResult.Retry
                    RejectionDisposition.Hold -> OutboxResult.Hold
                    RejectionDisposition.Remove -> OutboxResult.Advance
                }
                outbox.resolve(message.requestId, result)
                refreshPendingOutbox()
                emitIfCurrent(generation, Event.Failure(message.requestId, message.code, message.message, retryable = retryable))
            }
            is V3Message.ResponseComplete -> {
                // Delivery completion only; canonical Pi batches own conversation reconciliation.
                val attempt = audit.match(message.requestId)
                ChatTiming.record(Stage.PhoneCompleteDecoded, request = message.requestId, attempt = attempt?.id,
                    connection = audit.id, conversation = message.conversationId, outcome = message.outcome, origin = Origin.Live)
                if (attempt != null) audit.finish(attempt)
                audit.refresh()
                LocalDiagnostics.finish(LocalDiagnostics.Metric.Cancellation, message.requestId)
                rememberAccepted(peerId, message.requestId)
                emitIfCurrent(generation, Event.Delivery(message.requestId, AckState.Accepted, conversationId = message.conversationId))
            }
            is V3Message.Conversation -> {
                validateConversation(message.conversationId, message.sessionId, message.sessionName,
                    message.idleTimeoutSeconds, message.createdAt, message.expiresAt)
                val conversation = PhoneConversation(message.conversationId, ConversationState.parse(message.state),
                    message.createdAt, message.expiresAt, message.idleTimeoutSeconds, message.sessionId, message.sessionName, message.configuration)
                repository.upsertConversation(peerId, conversation)
                updateActiveConversation(conversation)
                emitIfCurrent(generation, Event.ConversationChanged(conversation, envelope.id))
            }
            is V3Message.GenerationStats -> ResponsePerformance.stats(message.requestId, message.stats)
            else -> throw WireFormatException("Unexpected conversation stream reply")
        }
    }

    private fun auditRequestReplay(request: com.kompi.app.protocol.generated.RequestSummary, connection: String) {
        ChatTiming.record(Stage.PhoneLedgerReplayPersisted, request = request.requestId, connection = connection,
            conversation = request.conversationId, origin = Origin.Catchup, outcome = request.outcome)
    }

    private suspend fun emitIfCurrent(generation: Long, event: Event) {
        if (isCurrent(generation)) _events.emit(event)
    }

    private fun isCurrent(generation: Long): Boolean = synchronized(lifecycleLock) {
        generation == connectionGeneration && requestedPairing != null
    }

    private suspend fun becomeReady(conn: Connection, peerId: String, conversations: Boolean) {
        Log.i(SYNC_TAG, "phase=sync_start conversations=$conversations")
        ready = true
        piSession.reconnect()
        synchronizationCaughtUp = false
        requestPagesComplete = false
        sessionPagesComplete = false
        _state.value = "Synchronizing"
        _lastError.value = null
        store.markMachineSeen(peerId)
        // Reconcile provider/notification consent at the authenticated foreground boundary so
        // uninstall or permission revocation fences old bindings without requiring Settings UI.
        runCatching { unifiedPushRepository.refreshProviderAvailability() }
            .onFailure { Log.w(SYNC_TAG, "phase=push_provider_refresh_failed") }
        startHeartbeat(conn)
        if (deferredIdleTimeoutSeconds != null && !conversations) {
            _idleTimeoutPending.value = false
            _idleTimeoutError.value = "This daemon does not support conversation timeout configuration"
        }
        val cursor = repository.currentCursor(peerId)
        Log.i(SYNC_TAG, "phase=sync_request cursor_present=${cursor != "0"}")
        send(id("sync"), V3Message.Sync(after = cursor.takeUnless { it == "0" }))
        if (unifiedPushSupported) {
            runCatching { flushPushOperations(peerId) }
                .onFailure { Log.w(SYNC_TAG, "phase=push_sync_failed") }
        }
    }

    private suspend fun enterLiveIfCaughtUp(generation: Long) {
        val current = isCurrent(generation)
        if (!current || !ready || !synchronizationCaughtUp || !requestPagesComplete || !sessionPagesComplete || pendingIdleTimeoutRequests.isNotEmpty()) {
            Log.i(SYNC_TAG, "phase=live_gate_blocked current=$current ready=$ready cursor_caught_up=$synchronizationCaughtUp requests_complete=$requestPagesComplete sessions_complete=$sessionPagesComplete pending_settings=${pendingIdleTimeoutRequests.size}")
            return
        }
        Log.i(SYNC_TAG, "phase=live elapsed_ms=${android.os.SystemClock.elapsedRealtime()} generation=$generation")
        timingConnection?.ready()
        _state.value = "Live"
        if (_modelCatalog.value == null && !_modelsLoading.value) scope.launch { refreshModels() }
        LocalDiagnostics.finish(LocalDiagnostics.Metric.ReconnectToLive)
        wakeOutbox()
    }

    private suspend fun send(id: String, message: V3Message) {
        sender(EnvelopeV3(id = id, message = message))
    }

    private suspend fun flushPushOperations(peerId: String) = pushSyncMutex.withLock {
        if (!ready || !unifiedPushSupported) return@withLock
        val hostId = com.kompi.app.assistant.push.PushHostId.parse(peerId) ?: return@withLock
        for (operation in unifiedPushRepository.pendingOperations(hostId)) {
            val operationId: String
            val message: V3Message
            when (operation) {
                is com.kompi.app.assistant.push.UnifiedPushRepository.PendingOperation.Unregister -> {
                    operationId = "push-unregister-${operation.registrationId}"
                    message = V3Message.PushUnregister(operation.registrationId.toString())
                }
                is com.kompi.app.assistant.push.UnifiedPushRepository.PendingOperation.Register -> {
                    operationId = "push-register-${operation.registration.registrationId}"
                    val registration = operation.registration
                    message = V3Message.PushRegister(
                        com.kompi.app.protocol.generated.PushRegistration(
                            auth = registration.auth,
                            endpoint = registration.endpoint,
                            integrityKey = registration.integrityKey,
                            p256dh = registration.p256dh,
                            registrationId = registration.registrationId.toString(),
                        ),
                    )
                }
            }
            // Capture the sender under the lifecycle lock immediately before committing this
            // operation to a connection. The closure is tied to its original stream, so a later
            // reconnect can never retarget old-host registration material to a new peer.
            val connectionSender = synchronized(lifecycleLock) {
                pushConnectionSender?.takeIf {
                    it.generation == connectionGeneration && it.peerId == peerId &&
                        ready && unifiedPushSupported
                }
            } ?: return@withLock
            if (!connectionSender.sentOperationIds.add(operationId)) continue
            try {
                connectionSender.send(EnvelopeV3(id = operationId, message = message))
            } catch (failure: Throwable) {
                connectionSender.sentOperationIds.remove(operationId)
                throw failure
            }
        }
    }

    private fun handlePushRegistrationResult(
        peerId: String,
        envelopeId: String,
        result: V3Message.PushRegistrationResult,
    ) {
        val hostId = com.kompi.app.assistant.push.PushHostId.parse(peerId) ?: return
        val registrationId = com.kompi.app.assistant.push.PushRegistrationId.parse(result.registrationId) ?: return
        val registerRequestId = "push-register-$registrationId"
        val unregisterRequestId = "push-unregister-$registrationId"
        when (result.status) {
            "registered" -> {
                if (envelopeId != registerRequestId) throw SecurityException("Mismatched push registration acknowledgement")
                unifiedPushRepository.markRegistered(hostId, registrationId)
            }
            "unregistered" -> {
                if (envelopeId != unregisterRequestId) throw SecurityException("Mismatched push unregistration acknowledgement")
                unifiedPushRepository.confirmUnregistered(hostId, registrationId)
            }
            "rejected" -> {
                val error = when (result.code) {
                    "origin-not-allowed", "invalid-endpoint" -> com.kompi.app.assistant.push.PushSetupError.EndpointRejected
                    "invalid-keys", "missing-encryption-keys" -> com.kompi.app.assistant.push.PushSetupError.MissingEncryptionKeys
                    "registration-limit" -> com.kompi.app.assistant.push.PushSetupError.RegistrationLimit
                    else -> com.kompi.app.assistant.push.PushSetupError.ComputerRejected
                }
                when (envelopeId) {
                    registerRequestId -> unifiedPushRepository.markRegisterRejected(hostId, registrationId, error)
                    unregisterRequestId -> unifiedPushRepository.markUnregisterRejected(hostId, registrationId, error)
                    else -> throw SecurityException("Mismatched push rejection")
                }
            }
            else -> throw WireFormatException("Unexpected push registration status")
        }
    }

    internal data class HistoryPage(val sessions: List<PhoneConversation>, val nextCursor: String?)

    private fun retireHistoryReplies() {
        for ((request, reply) in historyReplies) {
            if (historyReplies.remove(request, reply)) reply.completeExceptionally(IllegalStateException("Connection ended before confirmation."))
        }
    }

    private suspend fun historyExchange(hostId: String, prefix: String, request: V3Message): V3Message {
        val requestId = id(prefix)
        val reply = CompletableDeferred<V3Message>()
        val sendOnConnection = synchronized(lifecycleLock) {
            check(ready && _state.value == "Live" && _piHostHistorySupported.value &&
                requestedPairing?.machineId == hostId && store.selectedMachineId() == hostId) { "Connect to this computer first." }
            historyReplies[requestId] = reply
            sender
        }
        try {
            return withTimeout(35_000) {
                sendOnConnection(EnvelopeV3(id = requestId, message = request))
                val result = reply.await()
                check(store.selectedMachineId() == hostId && sendOnConnection === sender) { "Connection changed before confirmation." }
                if (result is V3Message.Error) throw HistoryRequestRejected(result.code)
                result
            }
        } finally {
            historyReplies.remove(requestId, reply)
        }
    }

    internal class HistoryRequestRejected(val code: String) : IllegalStateException(code)

    internal suspend fun loadHistoryPage(hostId: String, after: String?): HistoryPage {
        val result = historyExchange(hostId, "history-page", V3Message.PiHistoryPage(after = after, limit = 32))
            as? V3Message.PiHistoryPageResult ?: error("Computer did not return a session history page.")
        val sessions = result.sessions.map { it.toDomain() }
        check(result.nextCursor == null || (result.nextCursor != after && result.nextCursor == sessions.lastOrNull()?.conversationId)) {
            "Computer returned an invalid session history cursor."
        }
        return HistoryPage(sessions, result.nextCursor)
    }

    internal suspend fun deleteConversation(chat: ConversationTarget) {
        check(!chat.createIfMissing && focusedChat?.id != chat.id) { "Cannot delete the selected session." }
        check(repository.pendingPrompts(chat.hostId).none { it.targetConversationId == chat.id }) { "This session has unsent messages." }
        val confirmed = historyExchange(chat.hostId, "history-delete", V3Message.ConversationDelete(chat.id)) as? V3Message.ConversationDeleted
        check(confirmed?.conversationId == chat.id) { "Computer did not confirm deletion of this session." }
    }

    /** Foreground routing is local. Switching the pane never closes or cancels the former runtime. */
    internal fun focusConversation(target: ConversationTarget?) = synchronized(lifecycleLock) {
        val previous = focusedChat
        if (previous == target) return@synchronized
        focusedChat = target
        outboxConnection?.let { it.chatStreams.focus(target?.takeIf { chat -> chat.hostId == it.hostId }?.id) }
        if (previous?.hostId != target?.hostId) bindConversation(null)
        if (target != null) bindConversation(target.id)
    }

    internal suspend fun requestConversationSnapshot(target: ConversationTarget) {
        val connection = synchronized(lifecycleLock) {
            if (!ready || !_concurrentConversationsSupported.value || requestedPairing?.machineId != target.hostId ||
                focusedChat?.id != target.id) return
            outboxConnection ?: return
        }
        if (connection.chatStreams.negotiated) connection.chatStreams.requestSnapshot(target.id)
        else connection.send(id("selected-snapshot"), V3Message.PiConversationSnapshotGet(target.id))
    }

    /** A saved snapshot can populate a writable pane without starting a runtime. */
    suspend fun loadArchivedChat(hostId: String, conversationId: String): PiSessionStore.View = withContext(Dispatchers.IO) {
        withTimeout(35_000) {
            val connection = checkNotNull(activeConnection)
            check(ready && _state.value == "Live" && _piArchiveSupported.value && store.selectedMachineId() == hostId)
            val selection = PiSessionStore(needSnapshot = {}, publicDelta = { _, _, _, _, _ -> }, settled = { _, _, _, _ -> })
            selection.bindConversation(conversationId)
            val requestId = id("pi-archive")
            val stream = connection.openBi()
            stream.send().use { sendStream ->
                stream.recv().use { receiveStream ->
                    val request = EnvelopeV3(id = requestId, message = V3Message.PiArchiveGet(conversationId))
                    sendStream.writeAll(V3FrameCodec.frame(V3WireCodec.encode(request)))
                    sendStream.finish()
                    var complete = false
                    var source: Pair<String, String>? = null
                    var part = 0L
                    while (!complete) {
                        val size = ByteBuffer.wrap(receiveStream.readExact(4u)).order(ByteOrder.BIG_ENDIAN).int
                        if (size !in 1..MAX_PAYLOAD) throw WireFormatException("Invalid archive frame size")
                        val envelope = V3WireCodec.decode(receiveStream.readExact(size.toUInt()))
                        check(activeConnection === connection && store.selectedMachineId() == hostId)
                        check(envelope.id == requestId)
                        val frame = (envelope.message as? V3Message.PiFrame)?.frame ?: error("Archive unavailable")
                        check(frame.kind == "snapshot" && frame.sequence == 0L && frame.requestId == null &&
                            frame.conversationId == conversationId && frame.part == part++)
                        val identity = frame.sessionId to frame.epoch
                        check(source == null || source == identity)
                        source = identity
                        selection.accept(frame)
                        complete = frame.last
                    }
                    selection.view.value.also { check(!it.loading && it.sessionId == source?.first) }
                }
            }
        }
    }

    suspend fun continueConversation(hostId: String, target: String) = conversationIntentMutex.withLock {
        check(ready && _state.value == "Live" && _conversationContinueSupported.value && store.selectedMachineId() == hostId) {
            "Connect to a computer that supports continuing conversations."
        }
        check(repository.pendingPrompts(hostId).all { it.state == "waiting-for-conversation" && it.targetConversationId == target }) {
            "Send or discard pending messages in the current chat before switching."
        }
        val expected = piSession.currentConversationId()
        val requestId = id("conversation-activate")
        kotlinx.coroutines.coroutineScope {
            val result = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                events.first { event ->
                    (event is Event.Failure && event.id == requestId) ||
                        (event is Event.ConversationChanged && event.replyTo == requestId &&
                            event.metadata.conversationId == target && event.metadata.state == ConversationState.Active)
                }
            }
            try {
                withTimeout(110_000) {
                    val connection = checkNotNull(outboxConnection)
                    connection.sendChat(target, requestId, V3Message.ConversationActivate(conversationId = target, expectedCurrent = expected))
                    when (val event = result.await()) {
                        is Event.Failure -> error(event.message)
                        else -> {
                            wakeOutbox()
                            Unit
                        }
                    }
                }
            } finally {
                result.cancel()
            }
        }
    }

    /** Close only the current idle runtime; its Pi transcript remains on the host. */
    suspend fun startNewChat() = agentSessionMutex.withLock {
        check(ready && _state.value == "Live" && _phoneConversationsSupported.value) {
            "Connect to your computer before starting a new chat."
        }
        val hostId = store.selectedMachineId() ?: error("No selected host")
        check(repository.pendingPrompts(hostId).isEmpty()) {
            "Send or discard pending messages before starting a new chat."
        }
        val conversationId = piSession.view.value.conversationId ?: return@withLock
        val requestId = id("conversation-close")
        kotlinx.coroutines.coroutineScope {
            // Subscribe before sending so an immediate close/error cannot be missed.
            val result = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                events.first { event ->
                    (event is Event.Failure && event.id == requestId) ||
                        (event is Event.ConversationChanged && event.metadata.conversationId == conversationId &&
                            event.metadata.state != ConversationState.Active)
                }
            }
            try {
                withTimeout(15_000) {
                    val connection = checkNotNull(outboxConnection)
                    connection.sendChat(conversationId, requestId, V3Message.ConversationClose(conversationId))
                    when (val event = result.await()) {
                        is Event.Failure -> error(event.message)
                        else -> Unit // The authoritative metadata handler has already cleared the pane.
                    }
                }
            } finally {
                result.cancel()
            }
        }
    }

    suspend fun configureConversationIdleTimeout(seconds: Int) {
        validateIdleTimeout(seconds)
        val hostId = store.selectedMachineId() ?: error("No selected host")
        repository.updatePendingSettings(hostId, idleTimeoutSeconds = seconds.toLong(), uploadRetentionSeconds = null)
        latestIdleTimeoutRequestSeconds = seconds
        submitSettingsUpdate(seconds)
    }

    suspend fun configureUploadRetention(seconds: Int) {
        require(seconds in MIN_UPLOAD_RETENTION_SECONDS..MAX_UPLOAD_RETENTION_SECONDS && seconds % 3600 == 0) { "Upload retention must be between ${MIN_UPLOAD_RETENTION_SECONDS / 3600} and ${MAX_UPLOAD_RETENTION_SECONDS / 3600} whole hours" }
        val hostId = store.selectedMachineId() ?: error("No selected host")
        repository.updatePendingSettings(hostId, idleTimeoutSeconds = null, uploadRetentionSeconds = seconds.toLong())
        submitSettingsUpdate(null)
    }

    private suspend fun submitSettingsUpdate(idleOverride: Int?) {
        _idleTimeoutPending.value = true
        _idleTimeoutError.value = null
        if (!ready) {
            latestIdleTimeoutRequestId = null
            idleOverride?.let { deferredIdleTimeoutSeconds = it }
            return
        }
        if (!_phoneConversationsSupported.value) {
            _idleTimeoutPending.value = false
            _idleTimeoutError.value = "This daemon does not support conversation settings configuration"
            throw IllegalStateException(_idleTimeoutError.value)
        }
        val hostId = store.selectedMachineId() ?: error("No selected host")
        val local = repository.hostSettings(hostId)
        val effective = _effectiveIdleTimeoutSeconds.value ?: local?.effectiveIdleTimeoutSeconds?.toInt() ?: latestIdleTimeoutRequestSeconds ?: SecureStore.DEFAULT_IDLE_TIMEOUT_SECONDS
        val retention = local?.pendingUploadRetentionSeconds?.toInt()
            ?: local?.effectiveUploadRetentionSeconds?.toInt()
            ?: DEFAULT_UPLOAD_RETENTION_SECONDS
        val settings = EffectiveSettings(idleOverride ?: local?.pendingIdleTimeoutSeconds?.toInt() ?: effective, retention)
        val requestId = id("settings-update")
        latestIdleTimeoutRequestId = requestId
        latestIdleTimeoutRequestSeconds = settings.idleTimeoutSeconds
        pendingIdleTimeoutRequests[requestId] = settings
        try {
            send(requestId, V3Message.SettingsUpdate(settings, requestId))
        } catch (failure: Throwable) {
            pendingIdleTimeoutRequests.remove(requestId)
            _idleTimeoutPending.value = pendingIdleTimeoutRequests.isNotEmpty()
            _idleTimeoutError.value = failure.message ?: "Could not update settings"
            throw failure
        }
    }

    suspend fun refreshModels() {
        val hostId = store.selectedMachineId() ?: return
        if (!ready) { _modelError.value = "Connect to load configured models."; return }
        val generation = connectionGeneration
        val requestId = id("models")
        val reply = CompletableDeferred<com.kompi.app.protocol.generated.ModelCatalog>()
        modelReplies[requestId] = reply
        _modelsLoading.value = true
        _modelError.value = null
        try {
            val catalog = kotlinx.coroutines.withTimeout(150_000) {
                send(requestId, V3Message.ModelsGet)
                reply.await()
            }
            if (!isCurrent(generation) || store.selectedMachineId() != hostId) return
            modelCatalogHost = hostId
            _modelCatalog.value = catalog
            if (_chatConfiguration.value == null) {
                val initial = _confirmedConfiguration.value ?: catalog.defaultConfiguration
                initial?.let { selectChatModel(it) }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            if (isCurrent(generation)) _modelError.value = "Loading models timed out. Retry when the computer is ready."
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (isCurrent(generation)) _modelError.value = failure.message ?: "Could not load models. Retry when connected."
        } finally {
            modelReplies.remove(requestId)
            if (isCurrent(generation)) _modelsLoading.value = false
        }
    }

    fun selectChatModel(configuration: com.kompi.app.protocol.generated.ChatModelConfiguration) {
        val catalog = _modelCatalog.value ?: error("Connect to load configured models first.")
        require(catalog.models.any { it.provider == configuration.provider && it.modelId == configuration.modelId && configuration.effort in it.efforts }) { "Choose an available model and supported effort." }
        val hostId = store.selectedMachineId() ?: error("No selected computer")
        check(modelCatalogHost == hostId) { "The selected computer changed. Reload its models." }
        store.saveChatModel(hostId, configuration)
        _chatConfiguration.value = configuration
    }

    suspend fun sendPrompt(text: String, delivery: Delivery = Delivery.FollowUp, requestId: String? = null): String =
        sendPromptWithAttachments(text, delivery, emptyList(), requestId = requestId)

    suspend fun sendPromptWithId(text: String, delivery: Delivery, requestId: String): String =
        sendPrompt(text, delivery, requestId)

    suspend fun sendPromptWithAttachments(
        text: String,
        delivery: Delivery,
        attachments: List<DraftAttachment>,
        onProgress: (uploadId: String, received: Long, total: Long) -> Unit = { _, _, _ -> },
        requestId: String? = null,
    ): String = enqueuePrompt(text, delivery, attachments, requestId, null)

    internal suspend fun sendPromptTo(target: ConversationTarget, text: String, attachments: List<DraftAttachment>, requestId: String): String =
        enqueuePrompt(text, Delivery.FollowUp, attachments, requestId, target)

    private suspend fun enqueuePrompt(text: String, delivery: Delivery, attachments: List<DraftAttachment>, requestId: String?, destination: ConversationTarget?): String {
        val trimmed = text.trim()
        val uniqueAttachments = attachments.distinctBy { it.uploadId }
        require(uniqueAttachments.size == attachments.size) { "Duplicate attachment" }
        require(uniqueAttachments.size <= ATTACHMENT_MAX_FILES_PER_MESSAGE)
        require((trimmed.isNotEmpty() || uniqueAttachments.isNotEmpty()) && trimmed.length <= 16_000)
        val stableId = requestId ?: UUID.randomUUID().toString()
        ResponsePerformance.begin(stableId)
        var effectiveRequestId = stableId
        require(stableId.length in com.kompi.app.protocol.generated.REQUEST_ID_MIN_LENGTH..com.kompi.app.protocol.generated.REQUEST_ID_MAX_LENGTH && com.kompi.app.protocol.generated.REQUEST_ID_PATTERN.toRegex().matches(stableId)) { "Invalid request ID" }
        if (uniqueAttachments.isNotEmpty()) {
            require(runCatching { UUID.fromString(stableId) }.isSuccess) { "Attachment prompt request IDs must be UUIDs" }
            require(uniqueAttachments.none { it.uploadId == stableId }) { "Prompt and upload IDs must be distinct" }
        }
        ChatTiming.record(Stage.PhoneSubmit, request = stableId)
        val hostId = store.selectedMachineId() ?: error("No selected computer")
        check(destination == null || destination.hostId == hostId) { "The selected computer changed. Your message was not sent." }
        repository.pendingPrompts(hostId).firstOrNull { it.requestId == stableId }?.let { existing ->
            check(existing.text == trimmed && existing.attachmentIds == uniqueAttachments.map { it.uploadId } &&
                (destination == null || (existing.targetConversationId == destination.id && existing.createIfMissing == destination.createIfMissing))) { "Request ID is already bound to a different message" }
            return existing.requestId // Retries retain their original model snapshot.
        }
        val configuration = store.chatModel(hostId) ?: error("Choose a model after connecting to your computer before sending.")
        val displayedConversation = piSession.currentConversationId()
        val target = destination?.id ?: if (store.supportsContinuation(hostId)) displayedConversation
            ?: if (_state.value != "Live") repository.activeConversationId(hostId) else null
            else null
        val create = destination?.createIfMissing == true
        val candidate = SecureStore.PendingPrompt(stableId, trimmed, delivery, machineId = hostId,
            attachmentIds = uniqueAttachments.map { it.uploadId }, configuration = configuration, targetConversationId = target, createIfMissing = create)
        V3WireCodec.encode(EnvelopeV3(id = candidate.id, message = promptEnvelope(candidate)))
        try {
            // Room serializes enqueue transactions. Never wait for the session mutex here:
            // pairing/cancellation may hold it while writing to the network.
            val hostId = candidate.machineId ?: error("No selected host")
            if (ready && _state.value == "Live" && uniqueAttachments.isNotEmpty()) {
                check(_attachmentsSupported.value) { "This daemon does not support attachments" }
            }
            effectiveRequestId = candidate.id
            conversationIntentMutex.withLock {
                check(store.selectedMachineId() == hostId && (destination != null || piSession.currentConversationId() == displayedConversation)) {
                    "The current chat changed. Your message was not sent."
                }
                repository.enqueue(hostId, candidate.id, candidate.text, candidate.delivery.wire, candidate.attachmentIds, configuration,
                    targetConversationId = target, createIfMissing = create)
            }
            val enqueueStage = when {
                !networkAvailabilityMonitor.state.value.isOnline -> Stage.PhoneEnqueuedOffline
                !ready || _state.value != "Live" -> Stage.PhoneEnqueuedWaiting
                else -> Stage.PhoneEnqueued
            }
            ChatTiming.record(enqueueStage, request = candidate.id)
            refreshPendingOutbox()
            if (ready && _state.value == "Live") wakeOutbox()
            return effectiveRequestId
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            throw IllegalStateException(failure.message ?: "Prompt remains queued on this phone; retry when connected", failure)
        }
    }

    suspend fun uploadAttachment(
        attachment: DraftAttachment,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
        onTransferProgress: (AttachmentTransferProgress) -> Unit = {},
    ) {
        val outbox = outboxConnection?.takeIf { it.isCurrent() && ready } ?: error("Iroh peer is not connected")
        uploadAttachmentOn(outbox, attachment, onProgress, onTransferProgress)
    }

    private suspend fun uploadAttachmentOn(
        outbox: OutboxConnection,
        attachment: DraftAttachment,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
        onTransferProgress: (AttachmentTransferProgress) -> Unit = {},
    ) {
        outbox.withUpload(attachment.uploadId) {
            onTransferProgress(AttachmentTransferProgress.Preparing)
            check(_attachmentsSupported.value) { "This daemon does not support attachments" }
            withContext(Dispatchers.IO) { verifyCachedAttachment(attachment) }
            val file = attachment.cachedFile
            val digest = attachment.sha256
            val total = attachment.length
            suspend fun exchange(message: V3Message): V3Message {
                val waiting = CompletableDeferred<V3Message>()
                val requestId = id("attachment-offer")
                outbox.attachmentReplies[requestId] = waiting
                try {
                    outbox.send(requestId, message)
                    return withTimeoutOrNull(60_000) { waiting.await() } ?: error("Attachment offer timed out")
                } finally {
                    outbox.attachmentReplies.remove(requestId, waiting)
                }
            }
            try {
                val readyReply = exchange(V3Message.AttachmentOffer(attachment.fileName, total, attachment.mediaType, digest, attachment.uploadId)) as? V3Message.AttachmentReady
                    ?: throw IllegalStateException("Daemon did not accept the attachment offer")
                require(readyReply.uploadId == attachment.uploadId && readyReply.chunkSize in 1..ATTACHMENT_CHUNK_MAX_BYTES)
                val startOffset = readyReply.offset
                require(startOffset == 0L || startOffset == total) { "Upload cannot safely resume; retry from a clean transfer" }
                if (!outbox.isCurrent()) throw CancellationException("Attachment connection changed")
                outbox.connection.openBi().use { transferStream ->
                    transferStream.send().use { transferSend ->
                        transferStream.recv().use { transferRecv ->
                            transferSend.setPriority(0)
                            suspend fun transferExchange(requestId: String, message: V3Message): V3Message =
                                withTimeoutOrNull(60_000) {
                                    if (!outbox.isCurrent()) throw CancellationException("Iroh connection changed during attachment transfer")
                                    val frame = V3FrameCodec.frame(V3WireCodec.encode(EnvelopeV3(id = requestId, message = message)))
                                    transferSend.writeAll(frame)
                                    val sizeBytes = transferRecv.readExact(4u)
                                    val size = ByteBuffer.wrap(sizeBytes).order(ByteOrder.BIG_ENDIAN).int
                                    if (size !in 1..MAX_PAYLOAD) throw WireFormatException("Invalid bulk-stream frame length")
                                    val response = V3WireCodec.decode(transferRecv.readExact(size.toUInt()))
                                    if (!outbox.isCurrent()) throw CancellationException("Iroh connection changed during attachment transfer")
                                    if (response.id != requestId) throw WireFormatException("Bulk-stream response ID does not match request")
                                    response.message
                                } ?: error("Attachment transfer timed out")
                            val streamOpenId = id("attachment-stream")
                            val streamAck = transferExchange(streamOpenId, V3Message.StreamOpen("attachment-transfer")) as? V3Message.Ack
                                ?: throw WireFormatException("Daemon did not acknowledge the attachment-transfer stream")
                            require(streamAck.state == "accepted") { "Daemon rejected the attachment-transfer stream" }
                            var sent = startOffset
                            onTransferProgress(AttachmentTransferProgress.Uploading(sent, total))
                            if (startOffset != total) {
                                withContext(Dispatchers.IO) {
                                    FileInputStream(file).use { input ->
                                        while (sent < total) {
                                            val count = minOf(readyReply.chunkSize.toLong(), total - sent).toInt()
                                            val chunk = ByteArray(count)
                                            var readCount = 0
                                            while (readCount < count) {
                                                val read = input.read(chunk, readCount, count - readCount)
                                                if (read < 0) throw IllegalStateException("Cached attachment ended before its recorded length")
                                                readCount += read
                                            }
                                            val progress = transferExchange(attachment.uploadId, V3Message.AttachmentChunk(chunk, sent, attachment.uploadId)) as? V3Message.AttachmentProgress
                                                ?: throw IllegalStateException("Daemon did not acknowledge the attachment chunk")
                                            sent += count
                                            require(progress.uploadId == attachment.uploadId && progress.receivedBytes == sent) { "Daemon acknowledged an unexpected attachment offset" }
                                            _events.tryEmit(Event.AttachmentProgress(attachment.uploadId, sent, total))
                                            onProgress(sent, total)
                                            onTransferProgress(AttachmentTransferProgress.Uploading(sent, total))
                                        }
                                    }
                                }
                            }
                            onTransferProgress(AttachmentTransferProgress.Verifying)
                            val accepted = transferExchange(attachment.uploadId, V3Message.AttachmentFinish(attachment.uploadId)) as? V3Message.AttachmentAccepted
                                ?: throw IllegalStateException("Daemon did not accept the completed attachment")
                            require(accepted.uploadId == attachment.uploadId && accepted.length == total && accepted.sha256 == digest)
                            transferSend.finish()
                            onProgress(total, total)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (outbox.isCurrent()) _events.tryEmit(Event.AttachmentFailure(attachment.uploadId, failure.message ?: "Attachment upload failed"))
                throw failure
            }
        }
    }

    internal suspend fun downloadArtifact(target: ArtifactDownloadTarget, destination: android.net.Uri) = artifactDownloadMutex.withLock {
        saveOfferedArtifact(appContext, destination) {
            val connection = synchronized(lifecycleLock) {
                activeConnection?.takeIf {
                    ready && _artifactDownloadsSupported.value &&
                        requestedPairing?.takeIf { pairing -> pairing.confirmed }?.machineId == target.peer &&
                        store.selectedMachineId() == target.peer
                }
            } ?: throw ArtifactSaveException("Reconnect to the computer to download this file.")
            readOfferedArtifact(connection, target, id("artifact-get")) {
                ready && activeConnection === connection && store.selectedMachineId() == target.peer
            }
        }
    }

    private fun promptEnvelope(prompt: SecureStore.PendingPrompt): V3Message {
        val attachments = prompt.attachmentIds.takeIf { it.isNotEmpty() }
        val configuration = prompt.configuration ?: error("This queued message has no model selection. Discard it and resend with a selected model.")
        val target = prompt.targetConversationId
        return if (target == null) V3Message.Prompt(attachments, configuration, prompt.delivery.wire, prompt.text)
        else V3Message.ConversationPrompt(attachments = attachments, configuration = configuration,
            conversationId = target, createIfMissing = true.takeIf { prompt.createIfMissing }, delivery = prompt.delivery.wire, text = prompt.text)
    }

    private fun verifyCachedAttachment(attachment: DraftAttachment) {
        require(runCatching { UUID.fromString(attachment.uploadId) }.isSuccess) { "Invalid attachment ID" }
        require(attachment.length in 1..ATTACHMENT_MAX_FILE_BYTES && attachment.sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid attachment metadata" }
        require(attachment.fileName.isNotBlank() && attachment.fileName.length <= 255 && attachment.fileName.none { it == '/' || it == '\\' || it.code < 0x20 } && attachment.mediaType.isNotBlank() && attachment.mediaType.length <= 127 && attachment.mediaType.none { it.code < 0x20 })
        val cached = attachment.cachedFile.canonicalFile
        val cacheRoot = File(appContext.filesDir, "attachment-drafts").canonicalFile
        require(cached == File(cacheRoot, "${attachment.uploadId}.file").canonicalFile && cached.parentFile == cacheRoot && cached.isFile && cached.length() == attachment.length) { "Cached attachment is missing or has changed" }
        val hash = MessageDigest.getInstance("SHA-256")
        FileInputStream(cached).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        val actual = hash.digest().joinToString("") { "%02x".format(it) }
        require(actual == attachment.sha256) { "Cached attachment integrity check failed" }
    }

    suspend fun discardUnacceptedPrompt(id: String): Boolean = agentSessionMutex.withLock {
        val hostId = store.selectedMachineId() ?: return@withLock false
        val outbox = outboxConnection?.takeIf { it.hostId == hostId }
        val discarded = if (outbox == null) repository.discardUnacceptedPrompt(hostId, id) else outbox.discard(id)
        refreshPendingOutbox()
        outbox?.wake()
        discarded
    }

    private fun startHeartbeat(connection: Connection) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && ready && activeConnection === connection) {
                delay(25_000)
                if (ready && activeConnection === connection) runCatching { send(id("ping"), V3Message.Ping) }
                    .onFailure { connection.close(1, "heartbeat failed".toByteArray()) }
            }
        }
    }

    // An unrouted durable Prompt is not the currently displayed chat. Keep such
    // records in one conservative FIFO without rewriting their wire intent.
    private sealed interface OutboxDestination {
        data class Conversation(val id: String) : OutboxDestination
        data object Unmapped : OutboxDestination
    }

    private fun SecureStore.PendingPrompt.outboxDestination(): OutboxDestination =
        targetConversationId?.let(OutboxDestination::Conversation) ?: OutboxDestination.Unmapped

    private enum class OutboxResult { Advance, Hold, Retry }

    private data class OutboxHead(val prompt: SecureStore.PendingPrompt, val uncertain: Boolean)

    private data class OutboxAttempt(
        val destination: OutboxDestination,
        val attachmentIds: List<String>,
        val probingAttachments: Boolean,
        var route: ScopedChatStreams.Route? = null,
        val result: CompletableDeferred<OutboxResult> = CompletableDeferred(),
    )

    private fun StoredPrompt.outboxHead() = OutboxHead(toSecurePrompt(this), state == "dispatching")

    private fun wakeOutbox() { outboxConnection?.wake() }

    /** Called under lifecycleLock; a new physical connection owns a clean scheduler. */
    private fun retireOutboxConnection() {
        val old = outboxConnection
        outboxConnection = null
        old?.close()
    }

    private inner class OutboxConnection(
        val hostId: String,
        val generation: Long,
        val connection: Connection,
        private val write: suspend (EnvelopeV3) -> Unit,
        val audit: ChatTimingConnection,
    ) {
        private val jobs = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.IO)
        val chatStreams: ScopedChatStreams = ScopedChatStreams(jobs, connection, lifecycleLock, ::isCurrent, write, piSession::reconnect) {
            route, envelope -> receiveScopedEnvelope(this, route, envelope)
        }
        private val wakeups = Channel<Unit>(Channel.CONFLATED)
        private val scanMutex = Mutex()
        private val lanes = mutableSetOf<OutboxDestination>()
        private val awaiting = ConcurrentHashMap<String, OutboxAttempt>()
        val attachmentReplies = ConcurrentHashMap<String, CompletableDeferred<V3Message>>()
        private val uploads = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        init {
            jobs.launch {
                for (signal in wakeups) {
                    try {
                        scan()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Log.w(SYNC_TAG, "phase=outbox_scan_failed reason=${sanitizeConnectionReason(failure.message)}")
                    }
                }
            }
        }

        fun isCurrent(): Boolean =
            outboxConnection === this && activeConnection === connection && store.selectedMachineId() == hostId

        private fun canDrain(): Boolean = isCurrent() && ready && _state.value == "Live"

        private suspend fun settingsReady(): Boolean {
            val settings = repository.hostSettings(hostId)
            return settings?.pendingIdleTimeoutSeconds == null && settings?.pendingUploadRetentionSeconds == null
        }

        fun wake() { wakeups.trySend(Unit) }

        fun close() {
            wakeups.close()
            jobs.cancel()
            attachmentReplies.values.forEach { it.cancel() }
            uploads.values.forEach { it.cancel() }
        }

        suspend fun send(id: String, message: V3Message) {
            if (!isCurrent() || !ready) throw CancellationException("Outbox connection changed")
            write(EnvelopeV3(id = id, message = message))
        }

        fun requestScopedSnapshot(chat: String) {
            if (chatStreams.negotiated) jobs.launch { chatStreams.requestSnapshot(chat, onlyIfMissing = true) }
        }

        suspend fun sendChat(chat: String, id: String, message: V3Message) {
            val route = chatStreams.route(chat)
            if (route == null) send(id, message)
            else route.write(EnvelopeV3(id = id, message = message)) { it() }
        }

        suspend fun <T> withUpload(uploadId: String, action: suspend () -> T): T {
            val slot = CompletableDeferred<Unit>()
            while (true) {
                val previous = uploads.putIfAbsent(uploadId, slot) ?: break
                previous.await()
            }
            try {
                if (!isCurrent() || !ready) throw CancellationException("Attachment connection changed")
                return action()
            } finally {
                uploads.remove(uploadId, slot)
                slot.complete(Unit)
            }
        }

        fun setAttachmentStage(id: String, stage: AttachmentDispatchStage?) = synchronized(lifecycleLock) {
            if (isCurrent()) _attachmentDispatchStages.update { if (stage == null) it - id else it + (id to stage) }
        }

        fun expectReply(id: String, conversationId: String? = null, route: ScopedChatStreams.Route? = null): OutboxAttempt? {
            val attempt = currentAttempt(id) ?: return null
            if (attempt.route !== route || route?.isCurrent() == false) return null
            val explicit = attempt.destination as? OutboxDestination.Conversation
            if (explicit != null && conversationId != null && conversationId != explicit.id) {
                throw WireFormatException("Prompt reply does not match the queued conversation")
            }
            return attempt
        }

        fun matchesReplyRoute(id: String, route: ScopedChatStreams.Route?): Boolean =
            awaiting[id]?.let { it.route === route } ?: true

        private fun currentAttempt(id: String): OutboxAttempt? =
            if (isCurrent()) awaiting[id]?.takeUnless { it.result.isCompleted } else null

        fun resolve(id: String, result: OutboxResult) {
            if (isCurrent()) awaiting[id]?.result?.complete(result)
        }

        // Call only after committing authoritative Room state, not on a live response's
        // unpersisted delivery hint. The receipt can be lost despite durable admission.
        suspend fun resolveFromLedger(id: String, admitted: Boolean) {
            val attempt = currentAttempt(id) ?: return
            if (repository.isPromptDispatchUncertain(hostId, id) || !isCurrent()) return
            if (admitted) {
                attachmentDraftStore.markSent(attempt.attachmentIds.toSet())
                rememberAccepted(hostId, id)
                (attempt.destination as? OutboxDestination.Conversation)?.let { requestScopedSnapshot(it.id) }
            }
            attempt.result.complete(OutboxResult.Advance)
            refreshPendingOutbox()
        }

        suspend fun discard(id: String): Boolean = scanMutex.withLock {
            check(id !in awaiting.keys) { "Wait for the computer to acknowledge this message before discarding it" }
            repository.discardUnacceptedPrompt(hostId, id)
        }

        private suspend fun scan() = scanMutex.withLock {
            if (!canDrain() || !settingsReady()) return@withLock
            val heads = repository.pendingPrompts(hostId).asSequence()
                .filterNot { it.state == "accepted" }.map { it.outboxHead() }.distinctBy { it.prompt.outboxDestination() }
            for (head in heads) {
                val destination = head.prompt.outboxDestination()
                if (destination in lanes || !eligible(head.prompt)) continue
                lanes.add(destination)
                jobs.launch { drain(destination, head) }
            }
        }

        private suspend fun eligible(head: SecureStore.PendingPrompt): Boolean {
            val concurrent = _concurrentConversationsSupported.value
            if ((head.createIfMissing && !concurrent) ||
                (head.targetConversationId != null && !_conversationContinueSupported.value && !concurrent)) {
                if (!head.waitingForConversation) repository.holdPromptForConversation(hostId, head.id)
                return false // Never downgrade an explicitly routed message to an unrouted Prompt.
            }
            if (!concurrent && head.waitingForConversation && head.targetConversationId != piSession.currentConversationId()) return false
            if (head.configuration == null) {
                _events.tryEmit(Event.Failure(head.id, "model-selection-required", "This queued message has no model snapshot. Discard it and resend with a selected model."))
                return false
            }
            return true
        }

        private suspend fun drain(destination: OutboxDestination, first: OutboxHead) {
            var head = first
            var backoffMillis = 1_000L
            var rescan = true
            try {
                while (canDrain()) {
                    when (dispatch(head)) {
                        OutboxResult.Advance -> return
                        OutboxResult.Hold -> { rescan = false; return }
                        OutboxResult.Retry -> {
                            // Keep the lane reserved during delay so a wakeup cannot bypass its head.
                            delay(backoffMillis)
                            backoffMillis = (backoffMillis * 2).coerceAtMost(30_000L)
                            if (!canDrain() || !settingsReady()) return
                            head = repository.pendingPrompts(hostId).firstOrNull {
                                it.requestId == first.prompt.id && it.state != "accepted"
                            }?.outboxHead() ?: return
                            if (!eligible(head.prompt)) return
                        }
                    }
                }
            } finally {
                // Serialize release with discovery: a scan that read an old durable
                // head cannot start it after the prior worker has released the lane.
                withContext(NonCancellable) { scanMutex.withLock { lanes.remove(destination) } }
                if (rescan) wake()
            }
        }

        private suspend fun dispatch(head: OutboxHead): OutboxResult {
            val pending = head.prompt
            val attempt = audit.begin(pending.id)
            val reply = OutboxAttempt(pending.outboxDestination(), pending.attachmentIds,
                head.uncertain && pending.attachmentIds.isNotEmpty())
            var writing = false
            try {
                if (pending.attachmentIds.isNotEmpty() && !reply.probingAttachments) {
                    if (!_attachmentsSupported.value) {
                        throw PermanentOutboxFailure("Queued attachments require a daemon with attachment support.")
                    }
                    val cachedById = attachmentDraftStore.load().associateBy { it.uploadId }
                    pending.attachmentIds.forEachIndexed { index, attachmentId ->
                        val attachment = cachedById[attachmentId]
                            ?: throw PermanentOutboxFailure("A queued attachment is missing from private storage")
                        uploadAttachmentOn(this, attachment, onTransferProgress = { progress ->
                            val stage = when (progress) {
                                AttachmentTransferProgress.Preparing -> AttachmentDispatchStage.Preparing(index + 1, pending.attachmentIds.size)
                                is AttachmentTransferProgress.Uploading -> AttachmentDispatchStage.Uploading(index + 1, pending.attachmentIds.size, progress.received, progress.total)
                                AttachmentTransferProgress.Verifying -> AttachmentDispatchStage.Verifying(index + 1, pending.attachmentIds.size)
                            }
                            setAttachmentStage(pending.id, stage)
                        })
                    }
                    setAttachmentStage(pending.id, AttachmentDispatchStage.Sending)
                }
                val prompt = promptEnvelope(pending)
                val message = if (!audit.supported) prompt else when (prompt) {
                    is V3Message.ConversationPrompt -> prompt.copy(attemptId = attempt.id)
                    is V3Message.Prompt -> V3Message.TimedPrompt(
                        attachments = prompt.attachments, attemptId = attempt.id, configuration = prompt.configuration,
                        delivery = prompt.delivery, text = prompt.text,
                    )
                    else -> error("Unexpected outbox message")
                }
                val route = (message as? V3Message.ConversationPrompt)?.let { chatStreams.route(it.conversationId) }
                reply.route = route
                scanMutex.withLock {
                    if (!canDrain() || !settingsReady()) return OutboxResult.Advance
                    if (!repository.beginPromptDispatch(hostId, pending.id)) return OutboxResult.Advance
                    awaiting[pending.id] = reply // Also covers an ACK racing write completion.
                }
                LocalDiagnostics.begin(LocalDiagnostics.Metric.SendToAck, pending.id)
                LocalDiagnostics.begin(LocalDiagnostics.Metric.FirstText, pending.id)
                writing = true
                if (route == null) send(pending.id, message)
                else route.write(EnvelopeV3(id = pending.id, message = message)) { native ->
                    attempt.record(Stage.PhoneWriteStart)
                    native()
                    attempt.record(Stage.PhoneWriteComplete)
                }
                val result = withTimeoutOrNull(120_000) {
                    select {
                        reply.result.onAwait { it }
                        route?.closed?.onAwait { OutboxResult.Retry }
                    }
                }
                if (result != null) return result
                // An authoritative ledger update may already have settled an ACK that never arrived.
                if (!repository.isPromptDispatchUncertain(hostId, pending.id)) return OutboxResult.Advance
                // Reconcile on a fresh physical connection. Reusing the same stream
                // here could confuse a late negative with a new attempt of this ID.
                if (isCurrent()) {
                    _events.tryEmit(Event.Failure(pending.id, "ack-timeout",
                        "The computer did not confirm delivery. Reconnecting to check the message.", retryable = true, uncertain = true))
                    if (route == null) connection.close(1, "prompt acknowledgement timeout".toByteArray())
                    else { route.retire(); route.closed.await() }
                }
                return OutboxResult.Retry
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!isCurrent()) throw CancellationException("Outbox connection retired", failure)
                attempt.record(Stage.PhoneAttemptFailed)
                if (failure is ScopedChatStreams.RouteLost) reply.route?.closed?.await()
                if (failure is PermanentOutboxFailure) {
                    repository.removePrompt(hostId, pending.id)
                    refreshPendingOutbox()
                    _events.emit(Event.Failure(pending.id, "attachment-permanent", failure.message ?: "Attachment cannot be sent"))
                    return OutboxResult.Advance
                }
                _events.emit(Event.Failure(pending.id, if (writing) "connection" else "attachment",
                    sanitizeConnectionReason(failure.message), retryable = true, uncertain = writing))
                return OutboxResult.Retry
            } finally {
                awaiting.remove(pending.id, reply)
                audit.finish(attempt)
                setAttachmentStage(pending.id, null)
            }
        }
    }

    fun wasPromptAccepted(id: String): Boolean {
        val hostId = store.selectedMachineId() ?: return false
        return ScopedRequest(hostId, id) in recentlyAcceptedIds ||
            _pendingPromptOutbox.value.any { it.machineId == hostId && it.id == id && it.accepted }
    }

    private fun rememberAccepted(hostId: String, id: String) {
        recentlyAcceptedIds.add(ScopedRequest(hostId, id))
        if (recentlyAcceptedIds.size > 256) recentlyAcceptedIds.firstOrNull()?.let(recentlyAcceptedIds::remove)
    }

    private suspend fun shutdownEndpoint(endpoint: Endpoint?, successPhase: String): Boolean {
        if (endpoint == null) return false
        return withContext(NonCancellable) {
            try {
                endpoint.shutdown()
                Log.i(SYNC_TAG, "phase=$successPhase")
                true
            } catch (_: Throwable) {
                Log.w(SYNC_TAG, "phase=endpoint_shutdown_failed")
                false
            }
        }
    }

    private fun sanitizeConnectionReason(reason: String?): String {
        val value = (reason ?: "Iroh connection closed")
            .replace(Regex("[\\u0000-\\u001f\\u007f]"), " ")
            .replace(Regex("(?i)(secret|token|ticket|credential)=?[^\\s,;]+")) { match -> "${match.groupValues[1]}=[redacted]" }
            .replace(Regex("[A-Za-z0-9_-]{40,}"), "[redacted]")
            .trim()
        return value.take(240).ifBlank { "Iroh connection closed" }
    }

    suspend fun answerPiDialog(request: PiDialogRequest, answer: com.kompi.app.protocol.generated.PiUiAnswer) {
        check(ready && _piDialogs.value.any { it.interactionId == request.interactionId && it.conversationId == request.conversationId }) { "This Pi question is no longer active" }
        _piDialogError.value = null
        send(id("pi-ui-response"), V3Message.PiUiResponse(answer, request.conversationId, request.interactionId))
    }

    private suspend fun receiveDeviceNotification(message: V3Message.DeviceNotificationRequest, generation: Long) =
        receiveDeviceNotification(message.requestId, message.timeoutMs, generation) { hostId, expiresAt ->
            com.kompi.app.assistant.AgentNotifications.postForHost(appContext, hostId, message.requestId, expiresAt,
                org.json.JSONObject().put("title", message.title).put("body", message.body))
        }

    private suspend fun receiveDeviceNotification(
        requestId: String, timeoutMs: Long, generation: Long,
        post: (com.kompi.app.assistant.push.PushHostId, Long) -> org.json.JSONObject,
    ) {
        val receivedAt = android.os.SystemClock.elapsedRealtime()
        val outcome = withContext(Dispatchers.Main.immediate) {
            synchronized(lifecycleLock) {
                if (!isCurrent(generation)) return@withContext null
                val result = when {
                    !ready -> org.json.JSONObject().put("status", "not_ready").put("message", "Kompi is not ready to receive notifications.")
                    android.os.SystemClock.elapsedRealtime() - receivedAt >= timeoutMs ->
                        org.json.JSONObject().put("status", "expired").put("message", "The notification request expired before posting.")
                    else -> try {
                        val hostId = requestedPairing?.machineId?.let(com.kompi.app.assistant.push.PushHostId::parse)
                        if (hostId == null) {
                            org.json.JSONObject().put("status", "not_ready").put("message", "Kompi is not ready to receive notifications.")
                        } else {
                            val remainingMs = timeoutMs - (android.os.SystemClock.elapsedRealtime() - receivedAt)
                            val expiresAtMs = System.currentTimeMillis() + remainingMs.coerceAtLeast(0L)
                            post(hostId, expiresAtMs)
                        }
                    } catch (_: Exception) {
                        org.json.JSONObject().put("status", "invalid_request").put("message", "The notification request could not be accepted.")
                    }
                }
                result to sender
            }
        } ?: return
        val result = outcome.first
        val receipt = when (result.optString("status")) {
            "posted", "already_posted" -> "posted"
            "unknown" -> "unknown"
            else -> "not-posted"
        }
        if (isCurrent(generation)) outcome.second(EnvelopeV3(id = requestId, message = V3Message.DeviceNotificationResponse(
            requestId = requestId, status = receipt,
            code = if (receipt == "posted") null else result.optString("status").take(64),
            message = result.optString("message", "Android accepted the notification; user visibility is not confirmed.").take(512),
        )))
    }

    private suspend fun receiveAndroidTool(message: V3Message.AndroidToolRequest, generation: Long) {
        check(ready && isCurrent(generation)) { "Android tool received before authenticated synchronization" }
        val tool = AndroidToolKind.parse(message.tool)
        val request = AndroidToolRequest(message.conversationId, message.requestId,
            tool, message.actionJson, generation,
            android.os.SystemClock.elapsedRealtime() + minOf(50_000L, message.timeoutMs),
            screenInvocation = AssistantScreenContext.generation.takeIf { tool == AndroidToolKind.ScreenContext })
        val responseSender = synchronized(lifecycleLock) {
            if (!ready || !isCurrent(generation)) throw CancellationException("Android request belongs to a retired connection")
            if (piSession.currentConversationId() != request.conversationId) return@synchronized sender
            check(_androidToolRequests.value.size < 8) { "Too many pending Android tools" }
            check(recentAndroidToolIds.add(request.requestId)) { "Duplicate Android tool request" }
            while (recentAndroidToolIds.size > 256) recentAndroidToolIds.remove(recentAndroidToolIds.first())
            _androidToolRequests.value += request
            null
        }
        if (responseSender != null) {
            if (isCurrent(generation)) responseSender(EnvelopeV3(id = id("android-tool-rejected"), message = V3Message.AndroidToolResponse(
                conversationId = request.conversationId, requestId = request.requestId, success = false,
                errorCode = "conversation_changed", errorMessage = "This request does not belong to the phone's current conversation. No action was taken.",
            )))
            return
        }
        if (request.tool == AndroidToolKind.SendNotification) {
            completeAndroidTool(request) {
                val hostId = requestedPairing?.machineId?.let(com.kompi.app.assistant.push.PushHostId::parse)
                    ?: return@completeAndroidTool org.json.JSONObject().put("status", "not_ready").put("message", "Kompi is not ready to receive notifications.")
                val remainingMs = request.deadlineElapsedMs - android.os.SystemClock.elapsedRealtime()
                val expiresAtMs = System.currentTimeMillis() + remainingMs.coerceAtLeast(0L)
                com.kompi.app.assistant.AgentNotifications.postForHost(appContext, hostId, request.requestId, expiresAtMs, request.input())
            }
        } else scope.launch {
            delay(maxOf(1L, request.deadlineElapsedMs - android.os.SystemClock.elapsedRealtime()))
            if (_androidToolRequests.value.any { it === request }) {
                runCatching { completeAndroidTool(request) {
                    org.json.JSONObject().put("status", "expired").put("message", "The Android request expired. No action was taken.")
                } }
            }
        }
    }

    internal suspend fun executeForegroundAndroidTool(request: AndroidToolRequest, activity: android.app.Activity, fromAssistant: Boolean = false) {
        fun denied(code: String, message: String) = org.json.JSONObject().put("status", "error").put("code", code).put("message", message)
        completeAndroidTool(request) {
            val machine = requestedPairing?.takeIf { it.confirmed }?.machineId
                ?: return@completeAndroidTool denied("not_paired", "A paired computer is required.")
            fun accessError(capability: PhoneCapability?): org.json.JSONObject? {
                val current = synchronized(lifecycleLock) {
                    ready && isCurrent(request.connectionGeneration) && requestedPairing?.machineId == machine &&
                        piSession.currentConversationId() == request.conversationId &&
                        _androidToolRequests.value.any { it === request } &&
                        android.os.SystemClock.elapsedRealtime() < request.deadlineElapsedMs
                }
                if (!current) return denied("retired", "This request is no longer active. No action was taken.")
                val grants = store.phoneGrants(machine) ?: return denied("not_paired", "This computer is no longer paired.")
                if (capability != null && capability !in grants) return denied(
                    "phone_access_disabled",
                    "This computer is not allowed to use “${capability.label}”. Change its access in Kompi Settings → Assistant activation → Phone access.",
                )
                return null
            }
            when (request.tool) {
                AndroidToolKind.PhoneAction -> when (val parsed = PhoneActionParser.parse(request.actionJson ?: "{}")) {
                    is PhoneActionParseResult.Rejected -> denied(parsed.code, parsed.message)
                    is PhoneActionParseResult.Parsed -> when (val access = parsed.action.access()) {
                        PhoneActionAccess.Unsupported -> denied("unsupported", "This phone action has no authorization policy.")
                        PhoneActionAccess.Discovery -> {
                            accessError(null)?.let { return@completeAndroidTool it }
                            val grants = store.phoneGrants(machine).orEmpty()
                            PhoneActionExecutor.executeGranted(activity, parsed.action) { accessError(null) }.also { result ->
                                if (result.optString("status") == "listed") {
                                    result.put("standingGrants", org.json.JSONArray(grants.map { it.name }))
                                    result.put("approval", "Only standing grants for this paired computer may be used. Android permissions and unlocked foreground use are also required.")
                                }
                            }
                        }
                        is PhoneActionAccess.StandingGrant -> PhoneActionExecutor.executeGranted(activity, parsed.action) { accessError(access.capability) }
                    }
                }
                AndroidToolKind.ScreenContext -> {
                    accessError(PhoneCapability.ScreenText)?.let { return@completeAndroidTool it }
                    PhoneActionExecutor.foregroundError(activity, activity)?.let { return@completeAndroidTool it }
                    if (!fromAssistant) denied("assistant_required", "Screen text is only available during an active Android assistant invocation.")
                    else AssistantScreenContext.result(activity, machine, request.screenInvocation)
                }
                AndroidToolKind.SendNotification -> denied("unsupported", "Notifications use their own delivery route.")
            }
        }
    }

    /** Consume exactly once BEFORE side effects; network loss never retries an Android action. */
    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal suspend fun completeAndroidTool(request: AndroidToolRequest, execute: suspend () -> org.json.JSONObject) {
      var claimed = false
      try {
        val outcome = withContext(Dispatchers.Main.immediate) {
            val reply = synchronized(lifecycleLock) {
                check(ready && isCurrent(request.connectionGeneration) &&
                    piSession.currentConversationId() == request.conversationId &&
                    _androidToolRequests.value.any { it === request } &&
                    executingAndroidTools.add(request)) { "This Android request is no longer active" }
                claimed = true
                // Mark consumed before effects, but keep the request handler mounted during IO.
                // Removing it now would cancel its coroutine as soon as Compose disposes it.
                sender
            }
            try {
                val remaining = request.deadlineElapsedMs - android.os.SystemClock.elapsedRealtime()
                val result = if (remaining <= 0) {
                    org.json.JSONObject().put("status", "expired").put("message", "The request expired. No action was taken.")
                } else try {
                    kotlinx.coroutines.withTimeout(remaining) { execute() }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    org.json.JSONObject().put("status", "unknown").put("message", "The request timed out. An operation may already have happened; do not retry automatically.")
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: SecurityException) {
                    org.json.JSONObject().put("status", "error").put("code", "permission_required").put("message", "Android denied access. Review Phone access in Kompi settings.")
                } catch (_: Exception) {
                    org.json.JSONObject().put("status", "failed").put("message", "Android could not perform this request. Check its parameters and permissions.")
                }
                result to reply
            } catch (cancelled: CancellationException) { throw cancelled }
        }
        val raw = outcome.first
        val result = if (raw.toString().toByteArray(Charsets.UTF_8).size <= 16_384) raw else
            org.json.JSONObject().put("status", "error").put("code", "result_too_large").put("message", "The result exceeded its safe size limit. Use a smaller page. Do not repeat a write automatically.")
        val json = result.toString()
        val succeeded = result.optString("status") in setOf("launched", "listed", "read", "performed", "submitted", "shared", "posted", "already_posted")
        // send() may fail, but the consumed request cannot execute a second time.
        if (isCurrent(request.connectionGeneration)) outcome.second(EnvelopeV3(id = id("android-tool-response"), message = V3Message.AndroidToolResponse(
            conversationId = request.conversationId, requestId = request.requestId,
            success = succeeded,
            resultJson = json,
            errorCode = if (succeeded) null else result.optString("code", result.optString("status", "android_request_failed")).take(64),
            errorMessage = if (succeeded) null else result.optString("message", "Android did not complete this request.").take(512),
        )))
      } finally {
        // Keep the request handler alive until the send attempt ends; its scope owns the IO.
        synchronized(lifecycleLock) {
            if (claimed && executingAndroidTools.remove(request)) {
                _androidToolRequests.value = _androidToolRequests.value.filterNot { it === request }
            }
        }
      }
    }

    suspend fun cancel(target: String) {
        agentSessionMutex.withLock {
            check(ready)
            LocalDiagnostics.begin(LocalDiagnostics.Metric.Cancellation, target)
            val outbox = outboxConnection
            val chat = outbox?.let { repository.requestState(it.hostId, target)?.conversationId }
            if (outbox != null && chat != null) outbox.sendChat(chat, id("cancel"), V3Message.Cancel(target = target))
            else send(id("cancel"), V3Message.Cancel(target = target))
        }
    }

    suspend fun closeAndJoin() { close(); closeCompletion.await() }

    fun close() {
        if (!closing.compareAndSet(false, true)) return
        networkMonitorJob.cancel()
        networkAvailabilityMonitor.close()
        val oldJob = connectionJob
        stop()
        val old = endpoint
        endpoint = null
        scope.launch {
            try {
                // Joining first can wait forever on an FFI connect/read which needs endpoint shutdown.
                shutdownEndpoint(old, "endpoint_shutdown_on_close")
                oldJob?.join()
            } finally {
                closeCompletion.complete(Unit)
                scope.cancel()
            }
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            connectionGeneration++
            requestedPairing = null
            retireOutboxConnection()
        }
        ready = false
        _attachmentDispatchStages.value = emptyMap()
        _piDialogs.value = emptyList()
        _piDialogError.value = null
        _androidToolRequests.value = emptyList()
        _phoneConversationsSupported.value = false
        _piArchiveSupported.value = false
        _piHostHistorySupported.value = false
        _concurrentConversationsSupported.value = false
        retireHistoryReplies()
        _attachmentsSupported.value = false
        _artifactDownloadsSupported.value = false
        promptAcknowledgements.values.forEach { it.completeExceptionally(IllegalStateException("Iroh transport stopped")) }
        promptAcknowledgements.clear()
        _effectiveIdleTimeoutSeconds.value = null
        _idleTimeoutPending.value = false
        _idleTimeoutError.value = null
        pendingIdleTimeoutRequests.clear()
        latestIdleTimeoutRequestSeconds = null
        latestIdleTimeoutRequestId = null
        deferredIdleTimeoutSeconds = null
        scope.launch { refreshPendingOutbox() }
        heartbeatJob?.cancel(); heartbeatJob = null
        activeConnection?.close(0, "stop".toByteArray())
        activeConnection = null
        connectionJob?.cancel()
        sender = { throw IllegalStateException("Not connected") }
        _state.value = "Disconnected"
    }

    private fun id(prefix: String) = "$prefix-${UUID.randomUUID()}"

    private fun com.kompi.app.protocol.generated.ConversationMetadata.toDomain(): PhoneConversation {
        val conversationState = ConversationState.parse(state)
        validateConversation(conversationId, sessionId, sessionName, idleTimeoutSeconds, createdAt, expiresAt)
        return PhoneConversation(conversationId, conversationState, createdAt, expiresAt, idleTimeoutSeconds, sessionId, sessionName, configuration)
    }

    /** Called with lifecycleLock held so retirement and execution use the same ownership boundary. */
    private fun retireOtherConversationTools() {
        val current = piSession.currentConversationId()
        _androidToolRequests.value = _androidToolRequests.value.filter { it.conversationId == current }
    }

    private fun bindConversation(conversationId: String?) = synchronized(lifecycleLock) {
        piSession.bindConversation(conversationId)
        retireOtherConversationTools()
    }

    private fun bindLegacyConversation(conversationId: String?) = synchronized(lifecycleLock) {
        if (focusedChat == null) bindConversation(conversationId)
    }

    private fun updateActiveConversation(conversation: PhoneConversation) = synchronized(lifecycleLock) {
        val target = focusedChat
        if (target != null && target.id != conversation.conversationId) return@synchronized
        if (conversation.state == ConversationState.Active) {
            bindConversation(conversation.conversationId)
            conversation.configuration?.let { configuration ->
                val host = store.selectedMachineId() ?: return@let
                store.saveChatModel(host, configuration, confirmed = true)
                _confirmedConfiguration.value = configuration
                if (_chatConfiguration.value == null) {
                    store.saveChatModel(host, configuration)
                    _chatConfiguration.value = configuration
                }
            }
        }
        else if (target == null && piSession.currentConversationId() == conversation.conversationId) bindConversation(null)
    }

    private fun validateIdleTimeout(value: Number): Int {
        val seconds = value.toLong()
        require(seconds in SecureStore.MIN_IDLE_TIMEOUT_SECONDS.toLong()..SecureStore.MAX_IDLE_TIMEOUT_SECONDS.toLong()) { "Daemon returned an out-of-policy idle timeout" }
        return seconds.toInt()
    }

    private fun validateConversation(conversationId: String, piSessionId: String, sessionName: String?, timeout: Int, createdAt: String, expiresAt: String) {
        require(runCatching { UUID.fromString(conversationId) }.isSuccess) { "Invalid conversation ID" }
        require(piSessionId.isNotBlank() && piSessionId.length <= 128) { "Invalid daemon session ID" }
        require(sessionName == null || sessionName.isNotBlank() && sessionName.length <= 512) { "Invalid Pi session title" }
        validateIdleTimeout(timeout)
        require(runCatching { java.time.Instant.parse(createdAt); java.time.Instant.parse(expiresAt) }.isSuccess) { "Invalid conversation timestamps" }
    }
}

internal object PairHandshake {
    fun shouldSendCredential(confirmed: Boolean, sentOnThisConnection: Boolean) = !confirmed && !sentOnThisConnection
}
