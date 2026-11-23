package com.kompi.app.assistant.push

import com.kompi.app.assistant.push.PushIngressDiagnostics.PacketRejection
import com.kompi.app.protocol.generated.WireCodec
import com.kompi.app.protocol.generated.AgentNotification
import com.kompi.app.protocol.generated.ReplyNotification
import com.kompi.app.protocol.generated.REPLY_NOTIFICATION_TTL_MS
import com.kompi.app.protocol.generated.PUSH_MAX_CIPHERTEXT_BYTES
import com.kompi.app.protocol.generated.PUSH_MAX_TTL_MS
import com.kompi.app.protocol.generated.PUSH_MAX_FUTURE_SKEW_MS
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.ObjectMapper
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A push that has passed SDK ECE decryption, authenticated host MAC, binding and expiry checks. */
internal data class AuthenticatedPushPacket private constructor(
    val notificationId: String,
    val expiresAtEpochMillis: Long,
    val content: Content,
) {
    sealed interface Content {
        data class Agent(val notification: AgentNotification) : Content
        data class Reply(val reply: ReplyNotification) : Content
    }

    val title: String? get() = (content as? Content.Agent)?.notification?.title

    companion object {
        private const val MAX_CLEAR_BYTES = PUSH_MAX_CIPHERTEXT_BYTES
        private val strictJson = ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(MAX_CLEAR_BYTES).maxNumberLength(20).build()).build())
        private const val MAX_TTL_MILLIS = PUSH_MAX_TTL_MS
        private const val MAX_CLOCK_SKEW_MILLIS = PUSH_MAX_FUTURE_SKEW_MS
        private val DOMAIN = "kompi-push-v1\u0000".toByteArray(StandardCharsets.UTF_8)

        /** Parse once at the trust boundary; malformed, unencrypted or unauthenticated data is rejected. */
        fun parse(cleartext: ByteArray, registration: UnifiedPushRepository.Registration): AuthenticatedPushPacket? {
            if (cleartext.isEmpty() || cleartext.size > MAX_CLEAR_BYTES) return reject(PacketRejection.SIZE)
            val wrapperText = strictUtf8(cleartext) ?: return reject(PacketRejection.ENCODING)
            val wrapper = parseObject(wrapperText) ?: return reject(PacketRejection.ENVELOPE)
            if (!hasExactly(wrapper, setOf("version", "payload", "mac"))) return reject(PacketRejection.ENVELOPE)
            if (wrapper.opt("version") != 1) return reject(PacketRejection.ENVELOPE)
            val payload = decodeBase64Url(wrapper.optString("payload")) ?: return reject(PacketRejection.ENCODING)
            val claimedMac = decodeBase64Url(wrapper.optString("mac"))?.takeIf { it.size == 32 }
                ?: return reject(PacketRejection.AUTHENTICATION)
            val integrityKey = decodeBase64Url(registration.integrityKey)?.takeIf { it.size == 32 }
                ?: return reject(PacketRejection.AUTHENTICATION)
            val expectedMac = runCatching {
                Mac.getInstance("HmacSHA256").run {
                    init(SecretKeySpec(integrityKey, "HmacSHA256"))
                    update(DOMAIN)
                    doFinal(payload)
                }
            }.getOrNull() ?: return reject(PacketRejection.AUTHENTICATION)
            if (!MessageDigest.isEqual(expectedMac, claimedMac)) return reject(PacketRejection.AUTHENTICATION)

            val bodyText = strictUtf8(payload) ?: return reject(PacketRejection.ENCODING)
            val body = parseObject(bodyText) ?: return reject(PacketRejection.ENVELOPE)
            val fields = body.keys().asSequence().toSet()
            val version = body.opt("version")
            val richPayload = if (version == 2) runCatching { WireCodec.parseRichPushNotificationPayload(bodyText) }.getOrNull()
                ?: return reject(PacketRejection.CONTENT) else null
            val base = setOf("version", "hostEndpoint", "registrationId", "notificationId", "issuedAtMs", "expiresAtMs")
            val expected = when (version) {
                1 -> base + setOf("title", "body")
                2 -> {
                    // Rust-generated root parser owns every rich-packet field and nested constraint.
                    fields
                }
                3 -> base + "conversationId"
                else -> return reject(PacketRejection.ENVELOPE)
            }
            if (fields != expected) return reject(PacketRejection.ENVELOPE)
            if (body.optString("hostEndpoint") != registration.hostId.value) return reject(PacketRejection.BINDING)
            val registrationId = PushRegistrationId.parse(body.optString("registrationId")) ?: return reject(PacketRejection.BINDING)
            if (registrationId != registration.registrationId) return reject(PacketRejection.BINDING)
            val notificationId = body.opt("notificationId") as? String ?: return reject(PacketRejection.CONTENT)
            if (!notificationId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) return reject(PacketRejection.CONTENT)
            val issuedAt = exactLong(body.opt("issuedAtMs")) ?: return reject(PacketRejection.EXPIRY)
            val expiresAt = exactLong(body.opt("expiresAtMs")) ?: return reject(PacketRejection.EXPIRY)
            val now = System.currentTimeMillis()
            if (issuedAt <= 0L || expiresAt <= issuedAt || expiresAt <= now || issuedAt > now + MAX_CLOCK_SKEW_MILLIS) {
                return reject(PacketRejection.EXPIRY)
            }
            if (issuedAt > Long.MAX_VALUE - MAX_TTL_MILLIS || expiresAt > issuedAt + MAX_TTL_MILLIS) {
                return reject(PacketRejection.EXPIRY)
            }
            if (version == 3) {
                if (expiresAt > issuedAt + REPLY_NOTIFICATION_TTL_MS) return reject(PacketRejection.EXPIRY)
                val payload = runCatching { WireCodec.parseReplyPushNotificationPayload(bodyText) }.getOrNull()
                    ?: return reject(PacketRejection.CONTENT)
                return AuthenticatedPushPacket(notificationId, expiresAt, Content.Reply(ReplyNotification(
                    completedAtMs = issuedAt, conversationId = payload.conversationId,
                )))
            }
            val richInput = if (version == 2) body.opt("notification") as? JSONObject ?: return reject(PacketRejection.CONTENT) else null
            val input = richInput ?: JSONObject().put("title", body.opt("title")).put("body", body.opt("body"))
            val notification = richPayload?.notification ?: runCatching { WireCodec.parseAgentNotification(input.toString()) }.getOrNull() ?: return reject(PacketRejection.CONTENT)
            // Images are deliberately live-only; cold push never expands native bitmap content.
            if (notification.rich?.style is com.kompi.app.protocol.generated.NotificationStyle.BigPicture) return reject(PacketRejection.CONTENT)
            val title = notification.title
            val text = notification.body
            if (title.isBlank() || text.isBlank()) return reject(PacketRejection.CONTENT)
            return AuthenticatedPushPacket(notificationId, expiresAt, Content.Agent(notification))
        }

        private fun reject(reason: PacketRejection): AuthenticatedPushPacket? {
            PushIngressDiagnostics.packetRejected(reason)
            return null
        }

        /** Canonical MAC input shared with Rust: domain separator followed by exact payload bytes. */
        internal fun mac(payload: ByteArray, integrityKey: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(integrityKey, "HmacSHA256"))
            update(DOMAIN)
            doFinal(payload)
        }

        private fun parseObject(text: String): JSONObject? = runCatching {
            strictJson.readTree(text).takeIf { it.isObject } ?: return null
            JSONObject(text)
        }.getOrNull()

        private fun hasExactly(value: JSONObject, expected: Set<String>): Boolean =
            value.keys().asSequence().toSet() == expected

        private fun exactLong(value: Any?): Long? {
            return when (value) { is Long -> value; is Int -> value.toLong(); else -> null }
        }

        private fun strictUtf8(bytes: ByteArray): String? = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
    }
}
