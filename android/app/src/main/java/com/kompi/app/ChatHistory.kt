package com.kompi.app

import androidx.compose.runtime.*
import com.kompi.app.transport.awaitChatSnapshot
import com.kompi.app.transport.ConversationTarget
import com.kompi.app.transport.PiSessionStore
import com.kompi.app.transport.ConversationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first

internal class ChatHistory(val view: PiSessionStore.View, val loading: Boolean, val error: Boolean, val snapshotCompletions: Set<String>, val fetching: Boolean, val checkingReply: Boolean, val canCheck: Boolean, val retry: () -> Unit)

/** Promotion changes only whether future commands may create the runtime. The pane remains selected. */
@Composable
internal fun PromoteRecordedChat(target: ConversationTarget?, recordedChatId: String?, onPromote: (ConversationTarget) -> Unit) {
    LaunchedEffect(target, recordedChatId) {
        if (target?.createIfMissing == true && recordedChatId == target.id) {
            onPromote(ConversationTarget.saved(target.hostId, target.id))
        }
    }
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
internal fun rememberChatHistory(
    focusedChat: ConversationTarget?, livePi: PiSessionStore.View, views: StateFlow<PiSessionStore.View>,
    remoteState: String?, connectionState: String, concurrentSupported: Boolean, piArchiveSupported: Boolean,
    finishedRequests: Set<String>, activeSnapshot: suspend (ConversationTarget) -> Unit,
    archiveSnapshot: suspend (ConversationTarget) -> PiSessionStore.View, runningRequests: Set<String> = emptySet(),
    timeoutMs: Long = 45_000,
    legacyChat: ConversationTarget? = null,
): ChatHistory {
    val target = focusedChat ?: legacyChat
    val legacy = focusedChat == null && legacyChat != null
    // Acceptance promotes the target from draft to saved without opening a different chat.
    val openedAsDraft = remember(focusedChat?.hostId, focusedChat?.id) { focusedChat?.createIfMissing == true }
    val active = legacy || remoteState == ConversationState.Active.wire || (remoteState == null && openedAsDraft &&
        (runningRequests.isNotEmpty() || finishedRequests.isNotEmpty()))
    val canRead = target != null && (if (legacy) finishedRequests.isNotEmpty() else
        concurrentSupported && (active || (!target.createIfMissing && piArchiveSupported)))
    // In-memory only; keep the last two opened chats, fenced by both peer and chat.
    val recent = remember { LinkedHashMap<Pair<String, String>, PiSessionStore.View>() }
    val scopeKey = target?.let { it.hostId to it.id }
    fun PiSessionStore.View.belongsToTarget() = hostId == target?.hostId && conversationId == target?.id
    fun PiSessionStore.View.hasReplies(ids: Set<String>) = belongsToTarget() && !loading && canonicalReplies.containsAll(ids)
    // A successful branch read may have no request IDs. Don't keep rechecking those
    // historical completions when a new, linked reply arrives.
    var checkedCompletions by remember(scopeKey) { mutableStateOf(emptySet<String>()) }
    var snapshotCompletions by remember(scopeKey) { mutableStateOf(emptySet<String>()) }
    var fetching by remember(scopeKey) { mutableStateOf(false) }
    var errorRevision by remember(scopeKey) { mutableStateOf<Long?>(null) }
    val uncheckedCompletions = finishedRequests - checkedCompletions
    val snapshotError = canRead && connectionState == "Live" && errorRevision != null &&
        (!active || (errorRevision == livePi.snapshotRevision &&
            (uncheckedCompletions.isEmpty() || !livePi.hasReplies(uncheckedCompletions))))
    var snapshotRetry by remember(scopeKey) { mutableIntStateOf(0) }
    val latestRunning by rememberUpdatedState(runningRequests)
    val savedPi = key(scopeKey) {
        val view by produceState<PiSessionStore.View?>(null, active, remoteState, connectionState,
            legacy, canRead, snapshotRetry, finishedRequests) {
            val selected = target ?: return@produceState
            errorRevision = null
            if (connectionState != "Live" || !canRead) return@produceState
            val recoveringReply = uncheckedCompletions.isNotEmpty()
            fetching = true
            try {
                // Budget one follow-up read for a completed turn, not an unbounded poll.
                // Keep both attempts within approximately the ordinary history timeout.
                val attempts = if (recoveringReply) 2 else 1
                for (attempt in 1..attempts) {
                    if (attempt > 1) delay(300)
                    // Reply frames and Room completion travel independently. A canonical reply
                    // may already be visible or arrive while we're waiting to try again.
                    if (active && recoveringReply && views.value.hasReplies(uncheckedCompletions)) {
                        checkedCompletions = finishedRequests
                        return@produceState
                    }
                    val snapshot = try {
                        val readTimeout = timeoutMs / attempts
                        if (active) awaitChatSnapshot(selected, views, readTimeout) { activeSnapshot(selected) }
                        else kotlinx.coroutines.withTimeout(readTimeout) { archiveSnapshot(selected) }.also {
                            check(it.conversationId == selected.id && it.hostId == selected.hostId && !it.loading)
                        }
                    } catch (_: TimeoutCancellationException) { null }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    if (snapshot == null) {
                        if (attempt < attempts) continue
                        errorRevision = views.value.snapshotRevision
                        return@produceState
                    }
                    value = snapshot
                    val lastUser = snapshot.lines.indexOfLast { it.fromUser }
                    snapshotCompletions = finishedRequests.takeIf { lastUser >= 0 }.orEmpty()
                    val trailing = snapshot.lines.getOrNull(lastUser)?.key?.takeIf { it.startsWith("user:") }?.removePrefix("user:")
                    val hasPublicReply = snapshot.lines.drop(lastUser + 1).any {
                        !it.activity && !it.fromUser && !it.streaming && (it.body.isNotBlank() || it.artifact != null)
                    }
                    val needsReply = recoveringReply && !snapshot.hasReplies(uncheckedCompletions) && latestRunning.isEmpty() && !hasPublicReply
                    // With no linked turn we can retry, but can't claim an unlinked user
                    // entry belongs to this request (it might be a newer failed turn).
                    if (needsReply && attempt < attempts) continue
                    if (needsReply && (trailing in uncheckedCompletions || snapshot.lines.isEmpty())) {
                        errorRevision = snapshot.snapshotRevision.takeIf { active } ?: views.value.snapshotRevision
                    } else if (!needsReply) {
                        checkedCompletions = finishedRequests
                    }
                    return@produceState
                }
            } finally { fetching = false }
        }
        view
    }
    // A bound stream may go silent without closing. After one quiet interval
    // reconcile once per in-flight request set, never poll or replay the command.
    LaunchedEffect(scopeKey, runningRequests, connectionState, active, concurrentSupported) {
        if (focusedChat == null || runningRequests.isEmpty() || connectionState != "Live" ||
            !concurrentSupported || !active) return@LaunchedEffect
        views.debounce(20_000).first()
        if (!fetching && errorRevision == null && !views.value.loading) snapshotRetry++
    }
    // A stream can be lost after the entry snapshot was installed. Issue one read-only
    // recovery per loading episode; a failed recovery exposes Retry, not another timer.
    LaunchedEffect(scopeKey, active, connectionState, livePi.loading, livePi.conversationId, concurrentSupported) {
        val target = focusedChat ?: return@LaunchedEffect
        if (!concurrentSupported || connectionState != "Live" || !active ||
            livePi.conversationId != target.id || !livePi.loading) return@LaunchedEffect
        kotlinx.coroutines.delay(15_000)
        if (!fetching && errorRevision == null) snapshotRetry++
    }
    val cached = recent[scopeKey]
    val piSession = if (target == null) livePi else {
        livePi.takeIf { it.belongsToTarget() && active && (!it.loading || it.lines.isNotEmpty()) }
            ?: savedPi?.takeIf { it.belongsToTarget() }
            ?: cached
            ?: livePi.takeIf { it.belongsToTarget() }
            ?: PiSessionStore.View(conversationId = target.id, hostId = target.hostId)
    }
    SideEffect {
        if (scopeKey != null && !piSession.loading && cached !== piSession) {
            recent.remove(scopeKey)
            recent[scopeKey] = piSession
            if (recent.size > 2) recent.remove(recent.keys.first())
        }
    }
    return ChatHistory(piSession, !snapshotError && !openedAsDraft && focusedChat?.createIfMissing == false &&
        piSession.lines.isEmpty() && (fetching || piSession.loading) && concurrentSupported && connectionState == "Live",
        snapshotError, snapshotCompletions, fetching, uncheckedCompletions.isNotEmpty(), canRead, { snapshotRetry++ })
}
