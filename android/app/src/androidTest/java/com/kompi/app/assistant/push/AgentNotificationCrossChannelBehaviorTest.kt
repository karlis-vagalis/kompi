package com.kompi.app.assistant.push

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.kompi.app.assistant.AgentNotifications
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** A live-route post followed by a same-ID push is a single durable host-scoped receipt. */
@RunWith(AndroidJUnit4::class)
class AgentNotificationCrossChannelBehaviorTest {
    @get:Rule val notificationPermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val appContext = ApplicationProvider.getApplicationContext<Context>()
    private val namespace = "push-cross-channel-${UUID.randomUUID()}"
    private val context = object : ContextWrapper(appContext) {
        override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
            appContext.getSharedPreferences("$namespace-$name", mode)
    }
    private val notificationId = "cross-${UUID.randomUUID()}"
    private val hostId = requireNotNull(PushHostId.parse(
        UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "").take(32),
    ))

    @After fun cleanup() {
        context.getSystemService(NotificationManager::class.java).cancel("agent:${hostId.value}:$notificationId", 1)
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("agent-notification-receipts", Context.MODE_PRIVATE).edit().clear().commit()
        val ledger = UnifiedPushReplayLedger(context)
        ledger.writableDatabase.delete("notification_claims", "host_id = ?", arrayOf(hostId.value))
        ledger.writableDatabase.delete("receipts", "host_id = ?", arrayOf(hostId.value))
        ledger.close()
    }

    @Test fun sameHostRequestIsClaimedOnceAcrossDirectAndPushLifetimes() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putBoolean(AgentNotifications.PREFERENCE, true).commit()
        val now = System.currentTimeMillis()
        val direct = AgentNotifications.postForHost(
            context, hostId, notificationId, now + 24L * 60 * 60 * 1000,
            JSONObject().put("title", "First route").put("body", "One alert only."),
        )
        assertEquals("posted", direct.getString("status"))

        // More than the legacy 256-key platform receipt cap must not evict this full-TTL claim.
        val sharedLedger = UnifiedPushReplayLedger(context)
        repeat(260) { index ->
            val fillerId = "filler-$index-${UUID.randomUUID()}"
            assertEquals(ReplayClaim.Claimed, sharedLedger.claimNotification(hostId.value, fillerId, now + 24L * 60 * 60 * 1000, now))
            assertTrue(sharedLedger.completeNotification(hostId.value, fillerId, NotificationReceiptOutcome.NotPosted))
        }
        // A fresh push may have a later expiry and must still collide with the first full-TTL claim.
        val retry = AgentNotifications.postForHost(
            context, hostId, notificationId, now + 48L * 60 * 60 * 1000,
            JSONObject().put("title", "Second route").put("body", "Must not alert again."),
        )
        assertEquals("already_posted", retry.getString("status"))
        val manager = context.getSystemService(NotificationManager::class.java)
        val posted = manager.activeNotifications.filter { it.tag == "agent:${hostId.value}:$notificationId" }
        assertTrue(posted.size == 1)
        assertEquals("First route", posted.firstOrNull()?.notification?.extras?.getString("android.title"))
    }
}
