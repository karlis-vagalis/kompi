package com.kompi.app.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Fence local owners and retire the visible service/session before any native cleanup wait. Each
 * synchronous step is isolated so an owner failure is reported but cannot skip remaining stop work.
 */
internal fun scheduleAssistantServiceCleanup(
    scope: CoroutineScope,
    fenceOwners: List<() -> Unit>,
    releaseForeground: () -> Unit,
    releaseLogicalSession: (Throwable?) -> Unit,
    onStopFailure: (Throwable) -> Unit,
    cleanup: suspend (Throwable?) -> Unit,
): Job {
    var failure: Throwable? = null
    fun runStopStep(step: () -> Unit) {
        try {
            step()
        } catch (error: Throwable) {
            if (failure == null) failure = error
            else if (failure !== error) runCatching { failure?.addSuppressed(error) }
            runCatching { onStopFailure(error) }
        }
    }

    fenceOwners.forEach(::runStopStep)
    runStopStep(releaseForeground)
    runStopStep { releaseLogicalSession(failure) }
    val stopFailure = failure
    return scope.launch(start = CoroutineStart.UNDISPATCHED) { cleanup(stopFailure) }
}
