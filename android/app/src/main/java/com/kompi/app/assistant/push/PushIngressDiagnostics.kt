package com.kompi.app.assistant.push

import android.util.Log
import com.kompi.app.BuildConfig

/** Debug-only ingress checkpoints. The API accepts no identities, secrets, payloads or exceptions. */
internal object PushIngressDiagnostics {
    private const val TAG = "KompiPushIngress"

    enum class Event {
        ENDPOINT_SEEN,
        ENDPOINT_BOUND,
        ENDPOINT_INVALID_INSTANCE,
        ENDPOINT_UNTRUSTED,
        ENDPOINT_DISABLED,
        ENDPOINT_TEMPORARY,
        ENDPOINT_NO_BINDING,
        ENDPOINT_MISSING_KEYS,
        ENDPOINT_REJECTED_MATERIAL,
        ENDPOINT_GENERATION_LIMIT,
        ENDPOINT_CALLBACK_FAILED,
        MESSAGE_NOT_DECRYPTED,
        MESSAGE_INVALID_INSTANCE,
        MESSAGE_UNTRUSTED,
        MESSAGE_DISABLED,
        MESSAGE_PERMISSION_REQUIRED,
        MESSAGE_NO_BINDING,
        MESSAGE_DISABLED_BINDING,
        MESSAGE_PROVISIONAL_BINDING,
        MESSAGE_CALLBACK_FAILED,
        PACKET_REJECTED,
        PACKET_ACCEPTED,
        REPLAY_DUPLICATE,
        REPLAY_EXPIRED,
        REPLAY_FULL,
        REPLAY_STORAGE_FAILURE,
    }

    enum class PacketRejection {
        SIZE, ENVELOPE, ENCODING, AUTHENTICATION, BINDING, EXPIRY, CONTENT,
    }

    fun record(event: Event) {
        if (BuildConfig.DEBUG) Log.d(TAG, "phase=${event.name.lowercase()}")
    }

    fun messageSeen(decrypted: Boolean) {
        if (BuildConfig.DEBUG) Log.d(TAG, "phase=message_seen decrypted=$decrypted")
    }

    fun packetRejected(reason: PacketRejection) {
        if (BuildConfig.DEBUG) Log.d(TAG, "phase=packet_rejected reason=${reason.name.lowercase()}")
    }

    fun postResult(status: String) {
        if (!BuildConfig.DEBUG) return
        // Never interpolate an unrecognized value, even if a future caller supplies external data.
        val safeStatus = when (status) {
            "posted", "already_posted", "not_posted", "unknown", "expired", "disabled",
            "permission_required", "channel_disabled", "rate_limited", "storage_unavailable",
            "invalid_request", "suppressed" -> status
            else -> "unrecognized"
        }
        Log.d(TAG, "phase=notification_result status=$safeStatus")
    }
}
