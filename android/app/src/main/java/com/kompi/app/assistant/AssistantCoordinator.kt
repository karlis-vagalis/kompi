package com.kompi.app.assistant

import com.kompi.app.live.LiveConversationSnapshot
import com.kompi.app.live.LiveConversationState
import com.kompi.app.transport.PiDialogRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Main-thread handoff between the Activity runtime and an explicitly started assistant runtime. */
internal object AssistantCoordinator {
    data class Mode(val generation: Long = 0, val assistant: Boolean = false)
    data class SessionToken internal constructor(val generation: Long)
    data class SessionClaim internal constructor(val token: SessionToken, val releaseUi: suspend () -> Unit)
    private val mutableMode = MutableStateFlow(Mode())
    val mode = mutableMode.asStateFlow()
    val snapshot = MutableStateFlow(LiveConversationSnapshot())
    val dialogs = MutableStateFlow<List<PiDialogRequest>>(emptyList())
    val dialogError = MutableStateFlow<String?>(null)
    val androidTools = MutableStateFlow<List<com.kompi.app.transport.AndroidToolRequest>>(emptyList())
    val failure = MutableStateFlow<String?>(null)
    val hostName = MutableStateFlow("")
    val connectionState = MutableStateFlow("Disconnected")
    class UiReplacement internal constructor()
    class UiHandoff internal constructor(internal val replacement: UiReplacement, val releasePrevious: suspend () -> Unit)
    private var currentReplacement: UiReplacement? = null
    private val uiTeardown = Mutex()
    private var uiOwner: Any? = null
    private var releaseUi: (suspend () -> Unit)? = null

    fun registerUi(owner: Any, release: suspend () -> Unit) {
        check(uiOwner == null || uiOwner === owner) { "Another Kompi UI runtime is active" }
        uiOwner = owner
        releaseUi = release
    }

    fun unregisterUi(owner: Any) {
        if (uiOwner === owner) { uiOwner = null; releaseUi = null }
    }

    fun beginUiReplacement(): UiHandoff {
        val replacement = UiReplacement().also { currentReplacement = it }
        return UiHandoff(replacement, detachUi())
    }

    fun isCurrent(handoff: UiHandoff): Boolean = currentReplacement === handoff.replacement

    /** Transfer ownership before awaiting teardown; a replacement must await this before creating UI. */
    private fun detachUi(): suspend () -> Unit {
        val release = releaseUi
        uiOwner = null
        releaseUi = null
        return { uiTeardown.withLock { release?.invoke() } }
    }

    /** Captures the old owner's teardown before recomposition can unregister it. */
    fun claim(): SessionClaim {
        check(!mutableMode.value.assistant) { "Assistant is already active" }
        currentReplacement = null
        val release = detachUi()
        failure.value = null
        connectionState.value = "Disconnected"
        hostName.value = ""
        dialogs.value = emptyList()
        androidTools.value = emptyList()
        dialogError.value = null
        snapshot.value = LiveConversationSnapshot(state = LiveConversationState.Preparing)
        val generation = mutableMode.value.generation + 1
        mutableMode.value = Mode(generation, assistant = true)
        return SessionClaim(SessionToken(generation), release)
    }

    fun isCurrent(token: SessionToken): Boolean =
        mutableMode.value.assistant && mutableMode.value.generation == token.generation

    /** Publish scoped session state only while its generation still owns the assistant UI. */
    fun publish(token: SessionToken, update: () -> Unit): Boolean {
        if (!isCurrent(token)) return false
        update()
        return true
    }

    fun released(token: SessionToken, error: String?): Boolean {
        if (!isCurrent(token)) return false
        snapshot.value = LiveConversationSnapshot()
        connectionState.value = "Disconnected"
        hostName.value = ""
        dialogs.value = emptyList()
        androidTools.value = emptyList()
        dialogError.value = null
        AssistantScreenContext.clear()
        failure.value = error
        mutableMode.value = Mode(token.generation, assistant = false)
        return true
    }
}
