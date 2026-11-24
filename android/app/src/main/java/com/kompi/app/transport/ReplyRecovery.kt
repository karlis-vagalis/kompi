package com.kompi.app.transport

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** A ledger event or successful write cannot satisfy this barrier. A snapshot for the
 * selected chat (even an authoritative empty one) must actually be installed. */
internal suspend fun awaitChatSnapshot(
    target: ConversationTarget,
    view: StateFlow<PiSessionStore.View>,
    timeoutMs: Long = 45_000,
    requestSnapshot: suspend () -> Unit,
): PiSessionStore.View {
    val before = view.value.snapshotRevision
    return withTimeout(timeoutMs) {
        requestSnapshot()
        view.first { it.hostId == target.hostId && it.conversationId == target.id && !it.loading && it.snapshotRevision > before }
    }
}
