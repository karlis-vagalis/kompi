package com.kompi.app.assistant.push

import android.util.Base64
import java.util.UUID

/** Canonical Iroh EndpointId; it is an identity, never a display name or endpoint URL. */
@JvmInline
value class PushHostId private constructor(val value: String) {
    companion object {
        fun parse(value: String): PushHostId? = value.takeIf { it.matches(Regex("[0-9a-f]{64}")) }?.let(::PushHostId)
    }
}

/** A registration generation. Rotating endpoints always gets a new ID. */
@JvmInline
value class PushRegistrationId private constructor(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun fresh(): PushRegistrationId = PushRegistrationId(UUID.randomUUID())
        fun parse(value: String): PushRegistrationId? = runCatching { UUID.fromString(value) }
            .getOrNull()?.takeIf { it.toString() == value.lowercase() }?.let(::PushRegistrationId)
    }
}

/** Parse endpoint callback data once into a bounded endpoint usable for authenticated registration. */
internal data class PushEndpointMaterial private constructor(
    val url: String,
    val p256dh: String,
    val auth: String,
) {
    override fun toString(): String = "PushEndpointMaterial(url=[REDACTED], p256dh=[REDACTED], auth=[REDACTED])"

    companion object {
        fun parse(url: String, p256dh: String?, auth: String?): PushEndpointMaterial? {
            if (p256dh == null || auth == null || url.length !in 1..2048) return null
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
            if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null) return null
            if (p256dh.length != 87 || auth.length != 22) return null
            val publicKey = decodeBase64Url(p256dh) ?: return null
            val authSecret = decodeBase64Url(auth) ?: return null
            if (publicKey.size != 65 || publicKey[0] != 0x04.toByte() || authSecret.size != 16) return null
            return PushEndpointMaterial(url, p256dh, auth)
        }
    }
}

internal fun decodeBase64Url(value: String): ByteArray? {
    if (value.isEmpty() || value.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it != '_' && it != '-' }) return null
    return runCatching {
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            .takeIf { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) == value }
    }.getOrNull()
}

/** Only non-secret fields are exposed to settings UI. */
internal enum class PushSetupStatus {
    Off,
    ChoosingDistributor,
    WaitingForEndpoint,
    WaitingForComputer,
    Active,
    TemporarilyUnavailable,
    ProviderUnavailable,
    NeedsAttention,
}

internal data class PushSetupSummary(
    val hostId: PushHostId,
    val status: PushSetupStatus,
    val detailCode: PushSetupError? = null,
)

internal enum class PushSetupError {
    NoDistributor,
    RegistrationFailed,
    MissingEncryptionKeys,
    EndpointRejected,
    StorageUnavailable,
    RegistrationLimit,
    ComputerRejected,
}
