package com.kompi.app.assistant.push

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AuthenticatedPushPacketBehaviorTest {
    @Test fun exactInnerBytesMacBindingAndExpiryFailClosed() {
        val host = PushHostId.parse("ab".repeat(32))!!
        val generation = PushRegistrationId.fresh()
        val integrity = ByteArray(32) { (it + 1).toByte() }
        val publicKey = encode(("04" + "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296" +
            "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5").chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        val auth = encode(ByteArray(16) { it.toByte() })
        val registration = requireNotNull(UnifiedPushRepository.Registration.parse(
            host, generation, "https://push.example.invalid/test", publicKey, auth, encode(integrity),
        ))
        val now = System.currentTimeMillis()
        val payload = JSONObject()
            .put("version", 1)
            .put("hostEndpoint", host.value)
            .put("registrationId", generation.toString())
            .put("notificationId", "receipt-${UUID.randomUUID()}")
            .put("issuedAtMs", now)
            .put("expiresAtMs", now + 60_000)
            .put("title", "Build complete")
            .put("body", "The paired computer finished.")
            .toString().toByteArray(StandardCharsets.UTF_8)
        val cleartext = wrap(payload, integrity)
        val parsed = AuthenticatedPushPacket.parse(cleartext, registration)
        assertNotNull(parsed)
        assertEquals("Build complete", parsed!!.title)

        val nonBmpTitle = JSONObject(String(payload, StandardCharsets.UTF_8)).put("title", "😀".repeat(120))
            .toString().toByteArray(StandardCharsets.UTF_8)
        assertNotNull("The agreed 120-codepoint title bound counts Unicode characters, not UTF-16 units", AuthenticatedPushPacket.parse(wrap(nonBmpTitle, integrity), registration))
        val overlongTitle = JSONObject(String(payload, StandardCharsets.UTF_8)).put("title", "😀".repeat(121))
            .toString().toByteArray(StandardCharsets.UTF_8)
        assertNull(AuthenticatedPushPacket.parse(wrap(overlongTitle, integrity), registration))

        val tamperedMac = JSONObject(String(cleartext, StandardCharsets.UTF_8))
            .put("mac", encode(ByteArray(32)))
            .toString().toByteArray(StandardCharsets.UTF_8)
        assertNull(AuthenticatedPushPacket.parse(tamperedMac, registration))

        val otherHost = requireNotNull(UnifiedPushRepository.Registration.parse(
            PushHostId.parse("cd".repeat(32))!!, generation, registration.endpoint, registration.p256dh,
            registration.auth, registration.integrityKey,
        ))
        assertNull(AuthenticatedPushPacket.parse(cleartext, otherHost))
        val otherGeneration = requireNotNull(UnifiedPushRepository.Registration.parse(
            host, PushRegistrationId.fresh(), registration.endpoint, registration.p256dh,
            registration.auth, registration.integrityKey,
        ))
        assertNull(AuthenticatedPushPacket.parse(cleartext, otherGeneration))

        val expiredPayload = JSONObject(String(payload, StandardCharsets.UTF_8))
            .put("expiresAtMs", now - 1)
            .toString().toByteArray(StandardCharsets.UTF_8)
        assertNull(AuthenticatedPushPacket.parse(wrap(expiredPayload, integrity), registration))
    }

    @Test fun replayLedgerClaimsOnceAndScopesReceiptToRegistration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ledger = UnifiedPushReplayLedger(context)
        val hostId = "replay-${UUID.randomUUID()}"
        val registrationId = UUID.randomUUID().toString()
        val notificationId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        try {
            assertEquals(ReplayClaim.Claimed, ledger.claim(hostId, registrationId, notificationId, now + 60_000, now))
            assertEquals(ReplayClaim.Duplicate, ledger.claim(hostId, registrationId, notificationId, now + 60_000, now))
            assertEquals(ReplayClaim.Claimed, ledger.claim(hostId, UUID.randomUUID().toString(), notificationId, now + 60_000, now))
        } finally {
            ledger.close()
            context.deleteDatabase("unified_push_replay.sqlite3")
        }
    }

    @Test fun sharedHostClaimRetainsKnownOutcomeIndependentOfRegistrationGeneration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ledger = UnifiedPushReplayLedger(context)
        val hostId = "ab".repeat(32)
        val notificationId = "shared-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        assertEquals(ReplayClaim.Claimed, ledger.claimNotification(hostId, notificationId, now + 24L * 60 * 60 * 1000, now))
        assertTrue(ledger.completeNotification(hostId, notificationId, NotificationReceiptOutcome.Posted))
        // This is the same host/request identity after a route or registration-generation switch.
        assertEquals(ReplayClaim.Duplicate, ledger.claimNotification(hostId, notificationId, now + 48L * 60 * 60 * 1000, now + 1))
        assertEquals(NotificationReceiptOutcome.Posted, ledger.notificationOutcome(hostId, notificationId))
    }

    private fun wrap(payload: ByteArray, integrity: ByteArray): ByteArray = JSONObject()
        .put("version", 1)
        .put("payload", encode(payload))
        .put("mac", encode(AuthenticatedPushPacket.mac(payload, integrity)))
        .toString().toByteArray(StandardCharsets.UTF_8)

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(
        bytes,
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )
}
