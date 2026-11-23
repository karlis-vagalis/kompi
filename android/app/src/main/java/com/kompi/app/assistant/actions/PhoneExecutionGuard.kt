package com.kompi.app.assistant.actions

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Recheck after suspended provider work, before an irreversible call or sharing a result. */
internal class PhoneExecutionGuard(
    val foregroundError: () -> JSONObject?,
    val authorizationError: () -> JSONObject?,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PhoneExecutionGuard>
    fun denial() = foregroundError() ?: authorizationError()
}

internal suspend fun phoneExecutionDenial(): JSONObject? {
    val context = currentCoroutineContext()
    context.ensureActive()
    val guard = checkNotNull(context[PhoneExecutionGuard])
    val foreground = withContext(Dispatchers.Main.immediate) { guard.foregroundError() }
    context.ensureActive()
    // Read the current grant on the effect's dispatcher, not before queuing work there.
    return foreground ?: guard.authorizationError()
}
