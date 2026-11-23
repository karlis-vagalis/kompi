package com.kompi.app.audio

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Exact-session ownership captured at phrase admission, including an explicit standalone decision.
 * The coroutine context transports that capability through the existing three-argument enqueue API;
 * it never rediscovers the global Live owner after a suspended/backpressured admission.
 */
internal class SpeechOutputAdmission private constructor(
    val demand: LiveAudioSession.OutputDemand?,
) : AbstractCoroutineContextElement(Key) {
    private enum class Owner { PendingQueue, Engine, Released }
    private var owner = Owner.PendingQueue

    /** Transfer and bounded-channel admission are indivisible with pending-queue cancellation. */
    @Synchronized fun tryTransfer(admit: () -> Boolean): Boolean? {
        if (owner != Owner.PendingQueue) return null
        if (!admit()) return false
        owner = Owner.Engine
        return true
    }

    @Synchronized fun closePending() {
        if (owner == Owner.PendingQueue) {
            owner = Owner.Released
            demand?.close()
        }
    }

    /** Only after actual writer/track cleanup, or discard before any hardware was installed. */
    @Synchronized fun releaseEngine() {
        if (owner == Owner.Engine) {
            owner = Owner.Released
            demand?.close()
        }
    }

    companion object Key : CoroutineContext.Key<SpeechOutputAdmission> {
        fun capture(requestId: String, sequence: Long) =
            SpeechOutputAdmission(LiveAudioSession.reserveOutputDemand(requestId, sequence))
    }
}
