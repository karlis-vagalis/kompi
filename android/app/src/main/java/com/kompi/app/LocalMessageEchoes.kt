package com.kompi.app

import com.kompi.app.data.RequestStateRow

/** Presentation cache only: ledger settlement must not erase a bubble before Pi publishes it.
 * Scoped to the selected host; never participates in transport retry or execution decisions.
 */
internal class LocalMessageEchoes {
    private var conversationId: String? = null
    private val messages = linkedMapOf<String, String>()

    fun reconcile(conversation: String?, pending: List<Pair<String, String>>, requests: List<RequestStateRow>): List<Pair<String, String>> {
        if (conversationId != null && conversation != conversationId) messages.clear()
        conversationId = conversation
        messages.putAll(pending)
        val states = requests.associateBy { it.requestId }
        messages.entries.removeAll { (id, _) ->
            val state = states[id]
            state?.outcome == "cancelled" && state.conversationId.isBlank() ||
                state?.conversationId?.let { it.isNotBlank() && conversation != null && it != conversation } == true
        }
        return messages.map { it.key to it.value }
    }
}
