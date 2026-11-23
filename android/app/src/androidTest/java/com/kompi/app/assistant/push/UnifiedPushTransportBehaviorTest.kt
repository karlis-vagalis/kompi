package com.kompi.app.assistant.push

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.kompi.app.LiveLoopbackPeer
import com.kompi.app.assistant.AgentNotifications
import com.kompi.app.data.LocalDatabase
import com.kompi.app.data.LocalRepository
import com.kompi.app.security.SecureStore
import com.kompi.app.transport.IrohTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.unifiedpush.android.connector.UnifiedPush
import java.util.UUID

/** Authenticated local-Iroh register/unregister exchange plus test-APK UnifiedPush SDK IPC and decrypt. */
@RunWith(AndroidJUnit4::class)
class UnifiedPushTransportBehaviorTest {
    @get:Rule val notificationPermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val appContext = ApplicationProvider.getApplicationContext<Context>()
    private var peer: LiveLoopbackPeer? = null
    private var transport: IrohTransport? = null
    private var database: LocalDatabase? = null

    @After fun cleanup() {
        runBlocking {
            transport?.closeAndJoin()
            database?.close()
            peer?.close()
            runCatching { UnifiedPush.removeDistributor(appContext) }
            appContext.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .remove(AgentNotifications.PREFERENCE).commit()
            runCatching { FakeUnifiedPushDistributorControl.clear(appContext) }
        }
    }

    @Test fun lateOldGenerationFlushCannotSuppressSamePeerReconnectRetry() = runBlocking {
        val hostId = "ab".repeat(32)
        val operationId = "push-register-race"
        val oldSender = com.kompi.app.transport.PushConnectionSender(1L, hostId, send = {})
        // The old flush captures its stream before reconnect, then is deliberately paused before
        // claiming the operation ID, matching the stale-sibling interleaving.
        val capturedOldSender = oldSender
        val resumeOldFlush = CompletableDeferred<Unit>()
        val oldFlush = async {
            resumeOldFlush.await()
            capturedOldSender.sentOperationIds.add(operationId)
        }
        val reconnectedSender = com.kompi.app.transport.PushConnectionSender(2L, hostId, send = {})

        resumeOldFlush.complete(Unit)
        assertTrue("The stale flush must remain scoped to its original generation", oldFlush.await())
        assertTrue("The new same-peer generation must retry the operation", reconnectedSender.sentOperationIds.add(operationId))
        assertFalse("A second flush in one generation must still deduplicate", reconnectedSender.sentOperationIds.add(operationId))
    }

    @Test fun authenticatedRegistrationAcknowledgementAndOptOutSyncOverIroh() = runBlocking {
        appContext.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putBoolean(AgentNotifications.PREFERENCE, true).commit()
        peer = LiveLoopbackPeer.open(appContext)
        val paired = peer!!.pair(appContext)
        val secureStore = SecureStore(appContext)
        database = Room.inMemoryDatabaseBuilder(appContext, LocalDatabase::class.java).build()
        transport = IrohTransport(appContext, secureStore, LocalRepository(database!!, secureStore))
        transport!!.reconnect(paired)
        awaitStage("connected_live", 30_000) { transport!!.state.first { it == "Live" } }

        val hostId = requireNotNull(PushHostId.parse(peer!!.hostId))
        val repository = UnifiedPushRepository.get(appContext)
        val instance = repository.startRegistration(hostId)
        assertNotNull("Trusted host with notification consent should be eligible", instance)
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val distributorPackage = testContext.packageName
        assertTrue("The test APK must be discoverable through the official SDK intent", UnifiedPush.getDistributors(appContext).contains(distributorPackage))
        // The test APK is a separate UID and is not launched by target-app instrumentation.
        // Bootstrap its real receiver with acknowledged explicit IPC before the SDK's unchanged
        // package-scoped REGISTER, which intentionally does not include stopped packages.
        FakeUnifiedPushDistributorControl.clear(appContext)
        assertFalse(FakeUnifiedPushDistributorControl.registrationReceived(appContext))
        UnifiedPush.saveDistributor(appContext, distributorPackage)
        UnifiedPush.register(appContext, instance = instance!!, messageForDistributor = "Kompi instrumentation receiver")

        val registered = try {
            awaitStage("registration_received", 15_000) {
                while (peer!!.pushRegistrations.isEmpty()) delay(25)
                peer!!.pushRegistrations.first()
            }
        } catch (failure: AssertionError) {
            val distributorReceived = FakeUnifiedPushDistributorControl.registrationReceived(appContext)
            val summary = repository.summaries().singleOrNull { it.hostId == hostId }
            throw AssertionError(
                "stage=registration_received distributor_received=$distributorReceived " +
                    "setup_status=${summary?.status} setup_error=${summary?.detailCode} " +
                    "peer_error=${peer!!.errors.firstOrNull()?.javaClass?.simpleName}",
                failure,
            )
        }
        val registration = registered.registration
        assertEquals(hostId.value, paired.machineId)
        assertNotNull(PushRegistrationId.parse(registration.registrationId))
        assertTrue(registration.endpoint.startsWith("https://"))
        assertTrue(registration.p256dh.isNotBlank())
        assertTrue(registration.auth.isNotBlank())
        assertTrue(registration.integrityKey.isNotBlank())
        awaitStage("registered_acknowledgement", 10_000) {
            while (repository.summaries().none { it.hostId == hostId && it.status == PushSetupStatus.Active }) delay(25)
        }

        val notificationId = "sdk-${UUID.randomUUID()}"
        val issuedAt = System.currentTimeMillis()
        val expiresAt = issuedAt + 60_000L
        val inner = JSONObject()
            .put("version", 1)
            .put("hostEndpoint", hostId.value)
            .put("registrationId", registration.registrationId)
            .put("notificationId", notificationId)
            .put("issuedAtMs", issuedAt)
            .put("expiresAtMs", expiresAt)
            .put("title", "SDK push acceptance")
            .put("body", "The official SDK decrypted this test message.")
            .toString().toByteArray(Charsets.UTF_8)
        val integrityKey = Base64.decode(registration.integrityKey, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val authenticated = JSONObject()
            .put("version", 1)
            .put("payload", Base64.encodeToString(inner, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            .put("mac", Base64.encodeToString(AuthenticatedPushPacket.mac(inner, integrityKey), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            .toString().toByteArray(Charsets.UTF_8)
        val encrypted = WebPushTestEncryptor.encrypt(authenticated, registration.p256dh, registration.auth)
        FakeUnifiedPushDistributorControl.stage(appContext, encrypted)
        FakeUnifiedPushDistributorControl.deliver(appContext)
        val canonicalTag = "agent:${hostId.value}:$notificationId"
        awaitStage("sdk_notification_posted", 15_000) {
            while (appContext.getSystemService(NotificationManager::class.java).activeNotifications.none { it.tag == canonicalTag }) delay(50)
        }
        val duplicateLiveRoute = AgentNotifications.postForHost(
            appContext, hostId, notificationId, System.currentTimeMillis() + 24L * 60 * 60 * 1000,
            JSONObject().put("title", "Duplicate route").put("body", "Must not post again."),
        )
        assertEquals("already_posted", duplicateLiveRoute.getString("status"))

        repository.disable(hostId)
        awaitStage("opt_out_sync", 15_000) {
            while (peer!!.pushUnregistrations.none { it.registrationId == registration.registrationId } ||
                repository.summaries().none { it.hostId == hostId && it.status == PushSetupStatus.Off }) delay(25)
        }
    }

    private suspend fun <T> awaitStage(stage: String, timeoutMs: Long, await: suspend () -> T): T =
        try {
            withTimeout(timeoutMs) { await() }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("stage=$stage timed out after ${timeoutMs}ms", timeout)
        }
}
