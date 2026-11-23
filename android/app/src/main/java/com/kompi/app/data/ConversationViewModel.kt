package com.kompi.app.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SyncPhase {
    data object Disconnected : SyncPhase
    data object Connecting : SyncPhase
    data object Synchronizing : SyncPhase
    data object Live : SyncPhase
}

data class ConversationUiState(
    val local: LocalConversationState = LocalConversationState(emptyList(), emptyList(), null, emptyList(), null),
    val sync: SyncPhase = SyncPhase.Disconnected,
    val error: String? = null,
)

/** Lifecycle-scoped observable projection of the sole local repository. */
class ConversationViewModel(
    private val repository: LocalRepository,
    private val hostId: String,
) : ViewModel() {
    private val phase = MutableStateFlow<SyncPhase>(SyncPhase.Disconnected)
    private val failure = MutableStateFlow<String?>(null)

    val state: StateFlow<ConversationUiState> = repository.observe(hostId)
        .combineState(phase, failure)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationUiState())

    fun onSyncPhase(value: SyncPhase) {
        phase.value = value
    }

    fun onError(message: String?) {
        failure.value = message
    }

    data class Notice(val key: String, val text: String, val conversationId: String?)
    private val _notices = MutableStateFlow<List<Notice>>(emptyList())
    val notices: StateFlow<List<Notice>> = _notices

    /** Delivery failures are ephemeral UI notices, never conversation entries. */
    fun showDeliveryNotice(lineKey: String, text: String, conversationId: String?) {
        _notices.value = _notices.value.filterNot { it.key == lineKey } + Notice(lineKey, text, conversationId)
    }

    fun upsertConversation(conversation: com.kompi.app.transport.PhoneConversation) {
        viewModelScope.launch {
            runCatching { repository.upsertConversation(hostId, conversation) }
                .onFailure { failure.value = it.message ?: "Could not save conversation state" }
        }
    }

    fun clearDeliveryNotice(key: String) {
        _notices.value = _notices.value.filterNot { it.key == key }
    }

    fun enqueue(requestId: String, body: String, configuration: com.kompi.app.protocol.generated.ChatModelConfiguration) {
        viewModelScope.launch {
            runCatching { repository.enqueue(hostId, requestId, body, "followUp", emptyList(), configuration) }
                .onFailure { failure.value = it.message ?: "Could not save message" }
        }
    }
}

private fun kotlinx.coroutines.flow.Flow<LocalConversationState>.combineState(
    phase: StateFlow<SyncPhase>,
    error: StateFlow<String?>,
): kotlinx.coroutines.flow.Flow<ConversationUiState> = kotlinx.coroutines.flow.combine(this, phase, error) { local, sync, failure ->
    ConversationUiState(local, sync, failure)
}
