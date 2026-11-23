package com.kompi.app.assistant

import androidx.annotation.MainThread

/**
 * The framework may deliver onReady after shutdown/destruction. A retired service
 * cannot become available again, and callbacks from a replaced instance cannot
 * withdraw its replacement. All access is confined to the Android main thread.
 */
@MainThread
internal class AssistantServiceAvailability<T : Any> {
    private enum class Phase { AwaitingReady, Ready, Retired }
    private var active: Registration? = null
    private var latest: Registration? = null
    val current: T? get() = active?.service

    /**
     * Fences older first-readiness callbacks immediately, but keeps an already-ready
     * owner available until replacement readiness or that owner's own retirement.
     * Retiring an unready replacement never rolls registration identity back.
     */
    fun register(service: T): Registration = Registration(service).also { latest = it }

    inner class Registration internal constructor(internal val service: T) {
        private var phase = Phase.AwaitingReady

        /** Publishes this instance at most once; late or repeated readiness is ignored. */
        fun onReady(): Boolean {
            if (phase != Phase.AwaitingReady || latest !== this) return false
            phase = Phase.Ready
            active = this
            return true
        }

        /** Returns true only when this instance owned the service being withdrawn. */
        fun retire(): Boolean {
            phase = Phase.Retired
            if (latest === this) latest = null
            if (active !== this) return false
            active = null
            return true
        }
    }
}
