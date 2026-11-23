package com.kompi.app.diagnostics

import android.content.Context
import android.os.SystemClock
import com.kompi.app.protocol.generated.ChatTimingCursor
import com.kompi.app.protocol.generated.WireMessage as V3Message
import java.util.UUID

/** Content-free timing observations, independent of delivery and Pi transcript storage. */
internal object ChatTiming {
    const val CAPABILITY = "chat-timing-v1"
    private val clockId = uuid()
    @Volatile private var store: ChatTimingStore? = null

    enum class Stage {
        PhoneTap, PhoneSubmit, PhoneSubmitFailed, PhoneEnqueued, PhoneEnqueuedOffline, PhoneEnqueuedWaiting,
        PhoneConnectStart, PhoneQuicConnected, PhoneAuthenticated, PhoneSyncReady, PhoneConnectionFailed,
        PhoneAttempt, PhoneReusedConnection, PhoneWriteStart, PhoneWriteComplete, PhoneAttemptFailed,
        PhoneAttemptInterrupted, PhoneAckDecoded, PhoneAckPersisted, PhoneRetryableRejection, PhoneRejected,
        PhoneCompleteDecoded, PhoneDeliveryPersisted, PhoneLedgerReplayPersisted, PhoneErrorDecoded,
        PhoneFirstUpdate, PhoneLastUpdateObserved, PhoneFirstText, PhoneSettledReceived,
        PhoneSnapshotReceived, PhoneSnapshotProjected, PhoneResponseProjected, PhoneResumeSuffixProjected,
        PhoneUserComposed, PhoneUserAcceptedComposed, PhoneUserCanonicalComposed, PhoneAssistantDraw,
    }
    enum class Origin(val wire: String) {
        Local("local"), Live("live"), Catchup("catchup"), History("history"),
        HostSpool("host_spool"),
    }
    data class Time(val monotonicMs: Long, val wallMs: Long)
    data class Entry(
        val stage: String, val clockId: String, val time: Time, val origin: Origin,
        val request: String? = null, val attempt: String? = null, val connection: String? = null,
        val conversation: String? = null, val response: String? = null,
        val outcome: String? = null, val sourceStore: String? = null, val sourceSequence: Long? = null,
        val responseRequest: String? = null,
    )

    @Synchronized fun initialize(context: Context) {
        if (store == null) store = ChatTimingStore(context.applicationContext)
    }
    fun uuid(): String = UUID.randomUUID().toString()
    fun now() = Time(SystemClock.elapsedRealtime(), System.currentTimeMillis())
    private val stages = Stage.entries.associateWith { stage ->
        stage.name.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase(java.util.Locale.ROOT)
    }
    fun record(stage: Stage, request: String? = null, attempt: String? = null,
        connection: String? = null, conversation: String? = null, response: String? = null,
        origin: Origin = Origin.Local, time: Time = now(), outcome: String? = null,
    ) {
        store?.append(Entry(stages.getValue(stage), clockId, time, origin, request, attempt, connection,
            conversation, response, outcome))
    }
    suspend fun cursor(peer: String): ChatTimingCursor? = store?.cursor(peer)
    suspend fun import(peer: String, page: V3Message.ChatTimingPage): Boolean = store?.import(peer, page) ?: false
    fun query(filter: ChatTimingStore.Filter) = checkNotNull(store).query(filter)

    // Compose recomposition or scroll-back must not emit a checkpoint for every row appearance.
    enum class UserPresentation(val stage: Stage) {
        Optimistic(Stage.PhoneUserComposed), Acknowledged(Stage.PhoneUserAcceptedComposed),
        Canonical(Stage.PhoneUserCanonicalComposed),
    }
    private val composedUser = LinkedHashSet<Pair<String, UserPresentation>>()
    @Synchronized fun userComposed(request: String, presentation: UserPresentation) {
        if (!composedUser.add(request to presentation)) return
        if (composedUser.size > 512) composedUser.remove(composedUser.first())
        record(presentation.stage, request)
    }

    private data class Presentation(val request: String, val conversation: String, val connection: String?, val origin: Origin,
        var projected: Boolean = false, var drawn: Boolean = false)
    private val presentations = linkedMapOf<String, Presentation>()
    @Synchronized fun bindResponse(key: String, request: String, conversation: String, connection: String?, origin: Origin) {
        if (key in presentations) return
        presentations[key] = Presentation(request, conversation, connection, origin)
        if (presentations.size > 512) presentations.remove(presentations.keys.first())
    }
    @Synchronized fun responseProjected(key: String, suffix: Boolean = false) {
        val bound = presentations[key] ?: return
        if (bound.projected) return
        bound.projected = true
        record(if (suffix) Stage.PhoneResumeSuffixProjected else Stage.PhoneResponseProjected, bound.request,
            connection = bound.connection, conversation = bound.conversation, response = key, origin = bound.origin)
    }
    @Synchronized fun assistantDraw(key: String) {
        val bound = presentations[key] ?: return
        if (bound.drawn) return
        bound.drawn = true
        record(Stage.PhoneAssistantDraw, bound.request, connection = bound.connection, conversation = bound.conversation,
            response = key, origin = bound.origin)
    }
}
