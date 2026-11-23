package com.kompi.app.assistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide quarantine for service-owned native resources still retiring after logical Stop.
 * It is independent of the Android Service instance currently addressable through `current`.
 */
internal object AssistantServiceRetirementBarrier {
    internal class Owner internal constructor()

    private val lock = Any()
    private var retiringOwner: Owner? = null
    private val mutableCleanupPending = MutableStateFlow(false)

    /** True only for service-owned runtime/controller/TTS/transport teardown, not ASR lease release. */
    val cleanupPending = mutableCleanupPending.asStateFlow()

    /** A service can start retiring only if no different owner still holds native resources. */
    fun begin(owner: Owner): Boolean = synchronized(lock) {
        val current = retiringOwner
        if (current == null) {
            retiringOwner = owner
            mutableCleanupPending.value = true
            true
        } else {
            current === owner
        }
    }

    /** Every ACTION_START/invocation admission is denied while any service owner is retiring. */
    fun admitsStart(): Boolean = synchronized(lock) { retiringOwner == null }

    fun isRetiring(): Boolean = synchronized(lock) { retiringOwner != null }

    /** Only the exact owner may clear its barrier, and only after all owned cleanup succeeded. */
    fun complete(owner: Owner, cleanupSucceeded: Boolean): Boolean {
        if (!cleanupSucceeded) return false
        return synchronized(lock) {
            if (retiringOwner !== owner) false
            else {
                retiringOwner = null
                mutableCleanupPending.value = false
                true
            }
        }
    }
}
