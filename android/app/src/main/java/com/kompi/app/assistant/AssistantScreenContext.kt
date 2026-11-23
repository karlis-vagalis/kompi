package com.kompi.app.assistant

import android.app.KeyguardManager
import android.app.assist.AssistStructure
import android.content.Context
import android.os.SystemClock
import android.text.InputType
import android.view.View
import com.kompi.app.assistant.actions.PhoneCapability
import com.kompi.app.security.SecureStore
import org.json.JSONObject
import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Invocation-scoped, in-memory text only. Never reads screenshots, URLs, extras or autofill values. */
internal object AssistantScreenContext {
    const val PREFERENCE = "assistantScreenContextEnabled"
    private const val MAX_CHARS = 12_000
    private const val MAX_NODES = 2_000
    private const val MAX_AGE_MS = 120_000L

    sealed interface State {
        data object Unavailable : State
        data object Loading : State
        data class Ready(val application: String, val text: String, val capturedAt: Long, val truncated: Boolean) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Unavailable)
    val state = mutableState.asStateFlow()
    @Volatile var generation = 0L
        private set
    private var invocationMachineId: String? = null

    fun enabled(context: Context): Boolean = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getBoolean(PREFERENCE, false)

    fun begin(context: Context): Long {
        val store = SecureStore(context)
        val machine = if (enabled(context) && !locked(context)) runCatching {
            store.selectedMachineId()?.takeIf { PhoneCapability.ScreenText in store.phoneGrants(it).orEmpty() }
        }.getOrNull() else null
        generation++
        invocationMachineId = machine
        mutableState.value = if (machine != null) State.Loading else State.Unavailable
        return generation
    }

    fun clear() { generation++; invocationMachineId = null; mutableState.value = State.Unavailable }
    fun clear(invocation: Long) { if (generation == invocation) clear() }

    fun capture(context: Context, invocation: Long, structure: AssistStructure?) {
        if (invocation != generation || mutableState.value !is State.Loading) return
        mutableState.value = if (!enabled(context) || locked(context) || structure == null) State.Unavailable
            else runCatching { extract(structure) }.getOrDefault(State.Unavailable)
    }

    fun result(context: Context, machineId: String, invocation: Long?): JSONObject {
        if (invocation != generation) return failure("The assistant invocation changed. No screen text was shared.")
        if (!com.kompi.app.assistant.native.selectedAssistant(context)) return failure("Kompi is not Android’s selected assistant.")
        if (!enabled(context)) return failure("Screen context is disabled. Enable it in Assistant settings, then invoke Kompi from the screen to share.")
        if (locked(context)) { clear(); return failure("Unlock the phone before sharing screen context.") }
        if (invocationMachineId != machineId) return failure("No screen text was collected for this computer. Invoke Kompi again from the screen to share.")
        return when (val current = mutableState.value) {
            State.Loading -> failure("Android has not supplied screen context yet. Try again shortly.")
            State.Unavailable -> failure("No screen text is available. Invoke Kompi from an unlocked app that allows assistant context.")
            is State.Ready -> if (SystemClock.elapsedRealtime() - current.capturedAt > MAX_AGE_MS) {
                clear(); failure("Screen context has expired. Invoke Kompi again from the screen to share.")
            } else boundedResult(current)
        }
    }

    private fun extract(structure: AssistStructure): State {
        val nodes = ArrayDeque<AssistStructure.ViewNode>()
        // Bound both window and tree traversal; content supplied by another application is untrusted.
        for (index in 0 until minOf(structure.windowNodeCount, 16)) nodes.add(structure.getWindowNodeAt(index).rootViewNode)
        val text = StringBuilder()
        val seen = hashSetOf<String>()
        var visited = 0
        var truncated = structure.windowNodeCount > 16
        while (nodes.isNotEmpty() && visited++ < MAX_NODES && text.length < MAX_CHARS) {
            val node = nodes.removeFirst()
            // Editable fields and sensitive/autofill subtrees are deliberately excluded, even when
            // an app fails to identify a password correctly. Other visible text can still contain sensitive data.
            if (node.isAssistBlocked || node.visibility != View.VISIBLE || node.alpha <= 0f ||
                node.inputType != InputType.TYPE_NULL || !node.autofillHints.isNullOrEmpty()) continue
            for (value in listOf(node.text, node.contentDescription)) {
                val line = value?.take(MAX_CHARS)?.toString()?.trim().orEmpty()
                if (line.isNotEmpty() && seen.add(line)) {
                    val remaining = MAX_CHARS - text.length
                    if (remaining <= 1) { truncated = true; break }
                    text.append(line.take(remaining - 1)).append('\n')
                    if (line.length >= remaining) truncated = true
                }
            }
            val children = minOf(node.childCount, MAX_NODES - visited - nodes.size)
            if (children < node.childCount) truncated = true
            for (index in 0 until maxOf(children, 0)) nodes.addLast(node.getChildAt(index))
        }
        if (nodes.isNotEmpty()) truncated = true
        if (text.isBlank()) return State.Unavailable
        return State.Ready(structure.activityComponent?.packageName.orEmpty(), text.toString().trim(), SystemClock.elapsedRealtime(), truncated)
    }

    private fun boundedResult(current: State.Ready): JSONObject {
        val result = JSONObject().put("status", "shared").put("application", current.application)
            .put("text", current.text).put("truncated", current.truncated)
            .put("source", "Android assist text captured at assistant invocation, not a live screen")
        while (result.toString().toByteArray(Charsets.UTF_8).size > 14_000) {
            result.put("text", result.getString("text").take(result.getString("text").length * 4 / 5))
            result.put("truncated", true)
        }
        return result
    }

    private fun locked(context: Context): Boolean = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false
    private fun failure(message: String) = JSONObject().put("status", "unavailable").put("message", message)
}
