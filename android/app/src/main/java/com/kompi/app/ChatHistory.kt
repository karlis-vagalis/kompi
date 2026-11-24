package com.kompi.app

import androidx.compose.runtime.*
import com.kompi.app.transport.awaitChatSnapshot
import com.kompi.app.transport.ConversationTarget
import com.kompi.app.transport.PiSessionStore
import com.kompi.app.transport.ConversationState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first

internal class ChatHistory(val view: PiSessionStore.View, val loading: Boolean, val error: Boolean, val coveredCompletions: Set<String>, val fetching: Boolean, val checkingReply: Boolean, val canCheck: Boolean, val retry: () -> Unit)

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
): ChatHistory {
    // Acceptance promotes the target from draft to saved without opening a different chat.
    val openedAsDraft = remember(focusedChat?.hostId, focusedChat?.id) { focusedChat?.createIfMissing == true }
    val active = remoteState == ConversationState.Active.wire || (remoteState == null && openedAsDraft &&
        (runningRequests.isNotEmpty() || finishedRequests.isNotEmpty()))
    // In-memory only; keep the last two opened chats, fenced by both peer and chat.
    val recent = remember { LinkedHashMap<Pair<String, String>, PiSessionStore.View>() }
    val scopeKey = focusedChat?.let { it.hostId to it.id }
    var coveredCompletions by remember(scopeKey) { mutableStateOf(emptySet<String>()) }
    var missingReply by remember(scopeKey) { mutableStateOf(false) }
    var fetching by remember(scopeKey) { mutableStateOf(false) }
    var errorRevision by remember(scopeKey) { mutableStateOf<Long?>(null) }
    val snapshotError = errorRevision != null && (!active || errorRevision == livePi.snapshotRevision)
    var snapshotRetry by remember(focusedChat?.id, focusedChat?.hostId) { mutableIntStateOf(0) }
    val savedPi = key(scopeKey) {
        val view by produceState<PiSessionStore.View?>(null, active, remoteState, connectionState,
            concurrentSupported, piArchiveSupported, snapshotRetry, finishedRequests) {
            val target = focusedChat ?: return@produceState
            errorRevision = null
            missingReply = false
            if (connectionState != "Live" || !concurrentSupported) return@produceState
            fetching = true
            try {
                if (active) {
                    // Ledger completion is not transcript delivery. Reconcile on entry, reconnect
                    // and newly completed requests; only an installed snapshot ends the wait.
                    value = awaitChatSnapshot(target, views, timeoutMs) {
                        activeSnapshot(target)
                    }
                } else if (!target.createIfMissing) {
                    check(piArchiveSupported)
                    value = archiveSnapshot(target)
                }
                // This read began after the observed terminal IDs. Render the authoritative
                // branch, not an extra optimistic copy (archive entries need not carry request IDs).
                value?.takeIf { it.conversationId == target.id }?.let { snapshot ->
                    val lastUser = snapshot.lines.indexOfLast { it.fromUser }
                    coveredCompletions = finishedRequests.intersect(snapshot.readableReplies)
                    // A valid snapshot containing only the question still isn't an answer.
                    // An unlinked archive entry is not evidence about a particular request.
                    val trailing = snapshot.lines.getOrNull(lastUser)?.key?.takeIf { it.startsWith("user:") }?.removePrefix("user:")
                    if (trailing in finishedRequests && runningRequests.isEmpty() &&
                        snapshot.lines.drop(lastUser + 1).none { !it.activity && !it.fromUser && (it.body.isNotBlank() || it.artifact != null) }) {
                        missingReply = true
                        errorRevision = views.value.snapshotRevision
                    }
                }
            } catch (cancelled: kotlinx.coroutines.TimeoutCancellationException) { errorRevision = views.value.snapshotRevision }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { errorRevision = views.value.snapshotRevision }
            finally { fetching = false }
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
    val piSession = if (focusedChat == null) livePi else {
        livePi.takeIf { it.conversationId == focusedChat.id && !it.loading && active }
            ?: livePi.takeIf { it.conversationId == focusedChat.id && active && it.loading && it.lines.isNotEmpty() }
            ?: savedPi?.takeIf { it.conversationId == focusedChat.id }
            ?: cached
            ?: livePi.takeIf { it.conversationId == focusedChat.id }
            ?: PiSessionStore.View(conversationId = focusedChat.id)
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
        snapshotError, coveredCompletions, fetching, missingReply || (finishedRequests - coveredCompletions).isNotEmpty(), focusedChat != null && concurrentSupported && (active || (!focusedChat.createIfMissing && piArchiveSupported)), { snapshotRetry++ })
}
