package com.kompi.app.live

/** Local safety stops revoke audio ownership, never authorize cancellation of remote coding work. */
sealed interface LiveLocalStopReason {
    data object FocusLost : LiveLocalStopReason
    data object RouteLost : LiveLocalStopReason
    data object Backgrounded : LiveLocalStopReason
    data object ConnectionLost : LiveLocalStopReason

    /** Callers supply user-safe guidance, not exception payloads, transcript text or credentials. */
    class EngineFailure(message: String) : LiveLocalStopReason {
        val message: String = message.replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ").trim().take(240)
            .ifEmpty { "Local voice stopped. Check your speech models and start Live again." }
    }
}
