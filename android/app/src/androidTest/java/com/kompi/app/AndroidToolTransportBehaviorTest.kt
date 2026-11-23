package com.kompi.app

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.kompi.app.assistant.AgentNotifications
import com.kompi.app.data.LocalDatabase
import com.kompi.app.data.LocalRepository
import com.kompi.app.protocol.generated.WireMessage
import com.kompi.app.security.SecureStore
import com.kompi.app.transport.IrohTransport
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Actual authenticated QUIC -> production transport -> single-use execution -> wire response.
 * The peer is controlled, not a daemon/Pi E2E claim. No external host, model or microphone. */
class AndroidToolTransportBehaviorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val nonce = UUID.randomUUID().toString()
    private lateinit var context: Context
    private lateinit var db: LocalDatabase
    private lateinit var peer: LiveLoopbackPeer
    private lateinit var transport: IrohTransport

    @Before fun open() {
        require(InstrumentationRegistry.getArguments().getString("kompiDisposableEmulator") == "true")
        require(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")) { "Emulator only" }
        val base = compose.activity.applicationContext
        require(!base.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("android-tool-$nonce-$name", mode)
        }
        val secure = SecureStore(context)
        db = Room.databaseBuilder(context, LocalDatabase::class.java, "android-tool-$nonce.db").build()
        runBlocking {
            peer = LiveLoopbackPeer.open(context)
            val pairing = peer.pair(context)
            transport = IrohTransport(context, secure, LocalRepository(db, secure))
            transport.reconnect(pairing)
        }
        compose.waitUntil(30_000) { transport.state.value == "Live" }
    }

    @After fun close() {
        runBlocking {
            if (::transport.isInitialized) transport.closeAndJoin()
            if (::peer.isInitialized) peer.close()
        }
        if (::db.isInitialized) { db.close(); context.deleteDatabase("android-tool-$nonce.db") }
    }

    private fun bindConversation(id: String) {
        peer.snapshotConversationId = id
        peer.snapshotSessionId = "controlled-session-$id"
        val now = java.time.Instant.now()
        runBlocking { peer.send(WireMessage.Conversation(
            configuration = peer.configuration, conversationId = id, createdAt = now.toString(),
            expiresAt = now.plusSeconds(1800).toString(), idleTimeoutSeconds = 1800,
            sessionId = "controlled-session-$id", sessionName = "Controlled tool session", state = "active")) }
        compose.waitUntil(10_000) { transport.piSession.view.value.conversationId == id }
    }

    private fun request(timeoutMs: Long = 60_000): String {
        if (transport.piSession.view.value.conversationId != peer.conversationId) bindConversation(peer.conversationId)
        val id = UUID.randomUUID().toString()
        runBlocking { peer.send(WireMessage.AndroidToolRequest(
            actionJson = "{\"action\":\"list_apps\"}", conversationId = peer.conversationId,
            requestId = id, timeoutMs = timeoutMs, tool = "phone_action")) }
        return id
    }

    @Test fun confirmedRequestExecutesOnceAndReturnsOnlyToAuthenticatedPeer() {
        val id = request()
        compose.waitUntil(10_000) { transport.androidToolRequests.value.any { it.requestId == id } }
        val capability = transport.androidToolRequests.value.single()
        var executions = 0
        runBlocking { transport.completeAndroidTool(capability) { executions++; JSONObject().put("status", "launched") } }
        compose.waitUntil(10_000) { peer.androidToolAnswers.size == 1 }
        val answer = peer.androidToolAnswers.single()
        assertEquals(id, answer.requestId)
        assertEquals(peer.conversationId, answer.conversationId)
        assertTrue(answer.success)
        assertNull(answer.errorCode)
        assertEquals("launched", JSONObject(answer.resultJson!!).getString("status"))
        assertTrue(transport.androidToolRequests.value.isEmpty())
        assertTrue(runCatching { runBlocking {
            transport.completeAndroidTool(capability) { executions++; JSONObject().put("status", "launched") }
        } }.isFailure)
        assertEquals("A second tap must not repeat any side effect", 1, executions)
    }

    @Test fun cancellationExpiryAndTransportStopFenceOldCapabilities() {
        val cancelledId = request()
        compose.waitUntil(10_000) { transport.androidToolRequests.value.any { it.requestId == cancelledId } }
        val cancelled = transport.androidToolRequests.value.single()
        runBlocking { peer.send(WireMessage.AndroidToolResolved(peer.conversationId, cancelledId)) }
        compose.waitUntil(10_000) { transport.androidToolRequests.value.isEmpty() }
        var executions = 0
        assertTrue(runCatching { runBlocking {
            transport.completeAndroidTool(cancelled) { executions++; JSONObject().put("status", "launched") }
        } }.isFailure)

        val expiredId = request(timeoutMs = 100)
        compose.waitUntil(10_000) { peer.androidToolAnswers.any { it.requestId == expiredId } }
        val expired = peer.androidToolAnswers.single { it.requestId == expiredId }
        assertFalse(expired.success)
        assertEquals("expired", expired.errorCode)
        assertEquals("expired", JSONObject(expired.resultJson!!).getString("status"))
        assertTrue(transport.androidToolRequests.value.isEmpty())

        val stoppedId = request()
        compose.waitUntil(10_000) { transport.androidToolRequests.value.any { it.requestId == stoppedId } }
        val stopped = transport.androidToolRequests.value.single()
        transport.stop()
        assertTrue(transport.androidToolRequests.value.isEmpty())
        assertTrue(runCatching { runBlocking {
            transport.completeAndroidTool(stopped) { executions++; JSONObject().put("status", "launched") }
        } }.isFailure)
        assertEquals(0, executions)
    }

    @Test fun sameConnectionConversationReplacementAndEmptySessionRetireOldApprovals() {
        val oldId = request()
        compose.waitUntil(10_000) { transport.androidToolRequests.value.any { it.requestId == oldId } }
        val oldApproval = transport.androidToolRequests.value.single()
        val replacement = UUID.randomUUID().toString()
        bindConversation(replacement)
        compose.waitUntil(10_000) { transport.androidToolRequests.value.isEmpty() }
        var executions = 0
        assertTrue(runCatching { runBlocking {
            transport.completeAndroidTool(oldApproval) { executions++; JSONObject().put("status", "launched") }
        } }.isFailure)
        val wrongId = UUID.randomUUID().toString()
        runBlocking { peer.send(WireMessage.AndroidToolRequest(
            actionJson = "{\"action\":\"list_apps\"}", conversationId = peer.conversationId,
            requestId = wrongId, timeoutMs = 60_000, tool = "phone_action")) }
        compose.waitUntil(10_000) { peer.androidToolAnswers.any { it.requestId == wrongId } }
        assertEquals("conversation_changed", peer.androidToolAnswers.single { it.requestId == wrongId }.errorCode)
        assertTrue(transport.androidToolRequests.value.isEmpty())
        val currentId = UUID.randomUUID().toString()
        runBlocking { peer.send(WireMessage.AndroidToolRequest(
            actionJson = "{\"action\":\"list_apps\"}", conversationId = replacement,
            requestId = currentId, timeoutMs = 60_000, tool = "phone_action")) }
        compose.waitUntil(10_000) { transport.androidToolRequests.value.any { it.requestId == currentId } }
        val currentApproval = transport.androidToolRequests.value.single()
        peer.snapshotConversationId = null
        runBlocking { peer.send(WireMessage.PiSessionEmpty) }
        compose.waitUntil(10_000) { transport.piSession.view.value.conversationId == null && transport.androidToolRequests.value.isEmpty() }
        assertTrue(runCatching { runBlocking {
            transport.completeAndroidTool(currentApproval) { executions++; JSONObject().put("status", "launched") }
        } }.isFailure)
        assertEquals(0, executions)
    }

    @Test fun terminalDeviceNotificationsDoNotRequireConversationAndReportActualPosting() {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        require(manager.areNotificationsEnabled()) { "Grant notifications on the disposable emulator before running." }
        val disabledId = UUID.randomUUID().toString()
        runBlocking { peer.send(WireMessage.DeviceNotificationRequest(
            body = "Terminal task finished", requestId = disabledId, timeoutMs = 30_000, title = "Task finished")) }
        compose.waitUntil(10_000) { peer.deviceNotificationAnswers.any { it.requestId == disabledId } }
        val denied = peer.deviceNotificationAnswers.single { it.requestId == disabledId }
        assertEquals("not-posted", denied.status)
        assertEquals("disabled", denied.code)

        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putBoolean(AgentNotifications.PREFERENCE, true).commit()
        val postedId = UUID.randomUUID().toString()
        val notificationTag = "agent:${peer.hostId}:$postedId"
        try {
            val request = WireMessage.DeviceNotificationRequest(
                body = "Terminal task finished", requestId = postedId, timeoutMs = 30_000, title = "Task finished")
            runBlocking { peer.send(request) }
            compose.waitUntil(10_000) { peer.deviceNotificationAnswers.any { it.requestId == postedId } }
            assertEquals("posted", peer.deviceNotificationAnswers.single { it.requestId == postedId }.status)
            val notification = manager.activeNotifications.single { it.tag == notificationTag }
            assertEquals("Task finished", notification.notification.extras.getString(android.app.Notification.EXTRA_TITLE))
            assertEquals(android.app.Notification.VISIBILITY_PRIVATE, notification.notification.visibility)
            runBlocking { peer.send(request) }
            compose.waitUntil(10_000) { peer.deviceNotificationAnswers.count { it.requestId == postedId } == 2 }
            assertTrue(peer.deviceNotificationAnswers.filter { it.requestId == postedId }.all { it.status == "posted" })
            assertEquals(1, manager.activeNotifications.count { it.tag == notificationTag })
            assertTrue("Notifications must not create a Pi conversation or submit a prompt", peer.prompts.isEmpty())
            assertNull(transport.piSession.view.value.conversationId)
            assertTrue(transport.androidToolRequests.value.isEmpty())
        } finally { manager.cancel(notificationTag, 1) }
    }

    @Test fun interruptedDurableNotificationReceiptReturnsUnknownWithoutPostingAgain() {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        require(manager.areNotificationsEnabled())
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putBoolean(AgentNotifications.PREFERENCE, true).commit()
        val id = UUID.randomUUID().toString()
        // Emulate process loss after the write-ahead receipt, before a posting acknowledgement.
        // There is deliberately no notification body/title stored in this private receipt.
        context.getSharedPreferences("agent-notification-receipts", Context.MODE_PRIVATE).edit()
            .putString("receipt:$id", "${System.currentTimeMillis()}:posting").commit()
        runBlocking { peer.send(WireMessage.DeviceNotificationRequest(
            body = "Do not duplicate this alert", requestId = id, timeoutMs = 30_000, title = "Task finished")) }
        compose.waitUntil(10_000) { peer.deviceNotificationAnswers.any { it.requestId == id } }
        val answer = peer.deviceNotificationAnswers.single { it.requestId == id }
        assertEquals("unknown", answer.status)
        assertEquals("unknown", answer.code)
        assertTrue(answer.message!!.contains("Do not resend automatically"))
        assertTrue(manager.activeNotifications.none { it.tag == "agent:${peer.hostId}:$id" })
    }

    @Test fun notificationOptInFailureReturnsWithoutOpeningConfirmationUi() {
        bindConversation(peer.conversationId)
        assertFalse(AgentNotifications.enabled(context))
        val id = UUID.randomUUID().toString()
        runBlocking { peer.send(WireMessage.AndroidToolRequest(
            actionJson = JSONObject().put("title", "Build finished").put("body", "Example completion message").toString(),
            conversationId = peer.conversationId, requestId = id, timeoutMs = 60_000, tool = "send_notification")) }
        compose.waitUntil(10_000) { peer.androidToolAnswers.any { it.requestId == id } }
        val answer = peer.androidToolAnswers.single { it.requestId == id }
        assertFalse(answer.success)
        assertEquals("disabled", answer.errorCode)
        assertEquals("disabled", JSONObject(answer.resultJson!!).getString("status"))
        assertTrue(transport.androidToolRequests.value.isEmpty())
    }
}
