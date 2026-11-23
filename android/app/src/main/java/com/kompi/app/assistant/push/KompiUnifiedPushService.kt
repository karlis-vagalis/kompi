package com.kompi.app.assistant.push

import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Cold-start-capable UnifiedPush ingress. The SDK's exported, token-authenticated broadcast receiver
 * resolves the instance and decrypts Web Push before invoking this SDK-managed service; this service
 * itself is non-exported.
 * This service never connects to Iroh, opens UI, starts microphone capture, or fetches the network.
 */
class KompiUnifiedPushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        PushIngressDiagnostics.record(PushIngressDiagnostics.Event.ENDPOINT_SEEN)
        runCatching { UnifiedPushRepository.get(applicationContext).acceptEndpoint(instance, endpoint) }
            .onFailure { PushIngressDiagnostics.record(PushIngressDiagnostics.Event.ENDPOINT_CALLBACK_FAILED) }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        PushIngressDiagnostics.messageSeen(message.decrypted)
        runCatching { UnifiedPushRepository.get(applicationContext).acceptPush(message, instance) }
            .onFailure { PushIngressDiagnostics.record(PushIngressDiagnostics.Event.MESSAGE_CALLBACK_FAILED) }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        runCatching { UnifiedPushRepository.get(applicationContext).registrationFailed(instance, reason) }
    }

    override fun onUnregistered(instance: String) {
        runCatching { UnifiedPushRepository.get(applicationContext).markUnregistered(instance) }
    }

    override fun onTempUnavailable(instance: String) {
        runCatching { UnifiedPushRepository.get(applicationContext).markTemporarilyUnavailable(instance) }
    }
}
