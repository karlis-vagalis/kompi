package com.kompi.app.assistant.push

import android.Manifest
import android.content.Context
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Phase 1 for the external cold-receiver acceptance harness. Generates an ephemeral registration
 * using the published SDK and real authenticated Iroh pairing, then exports only the short-lived
 * sender fixture needed by the external Rust sender. Never exports or reads Kompi identity keys.
 * The driver must delete the fixture after delivery.
 */
@RunWith(AndroidJUnit4::class)
class UnifiedPushColdReceiverFixtureTest {
    @get:Rule val notificationPermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @Test fun prepareEphemeralSdkRegistrationFixture() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val settings = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val previousNotificationsEnabled = settings.getBoolean(AgentNotifications.PREFERENCE, false)
        val secureStore = SecureStore(app)
        val previousSelectedMachineId = secureStore.selectedMachineId()
        val previousDistributor = UnifiedPush.getSavedDistributor(app)
        val fixtureSnapshot = JSONObject()
            .put("schema", "kompi-unified-push-cold-fixture-v1")
            .put("appPackage", app.packageName)
            .put("previousSelectedMachineId", previousSelectedMachineId)
            .put("previousNotificationsEnabled", previousNotificationsEnabled)
            .put("previousDistributor", previousDistributor)
        app.openFileOutput(FIXTURE_FILE, Context.MODE_PRIVATE).use { it.write(fixtureSnapshot.toString().toByteArray(Charsets.UTF_8)) }
        var peer: LiveLoopbackPeer? = null
        var database: LocalDatabase? = null
        var transport: IrohTransport? = null
        try {
            settings.edit().putBoolean(AgentNotifications.PREFERENCE, true).commit()
            val livePeer = LiveLoopbackPeer.open(app)
            peer = livePeer
            val paired = livePeer.pair(app)
            val hostId = requireNotNull(PushHostId.parse(livePeer.hostId))
            fixtureSnapshot.put("hostEndpoint", hostId.value).put("testPackage", testContext.packageName)
            app.openFileOutput(FIXTURE_FILE, Context.MODE_PRIVATE).use {
                it.write(fixtureSnapshot.toString().toByteArray(Charsets.UTF_8))
            }
            val localDatabase = Room.inMemoryDatabaseBuilder(app, LocalDatabase::class.java).build()
            database = localDatabase
            val localTransport = IrohTransport(app, secureStore, LocalRepository(localDatabase, secureStore))
            transport = localTransport
            localTransport.reconnect(paired)
            withTimeout(30_000) { localTransport.state.first { it == "Live" } }

            val repository = UnifiedPushRepository.get(app)
            val instance = requireNotNull(repository.startRegistration(hostId))
            val testPackage = testContext.packageName
            assertTrue(
                "Test APK must appear through the official UnifiedPush distributor discovery API",
                UnifiedPush.getDistributors(app).contains(testPackage),
            )
            UnifiedPush.saveDistributor(app, testPackage)
            // Explicitly bootstrap/clear the standalone distributor package before the SDK's
            // package-scoped REGISTER broadcast, then prove no old token can satisfy the wait.
            FakeUnifiedPushDistributorControl.clear(app)
            check(!FakeUnifiedPushDistributorControl.registrationReceived(app)) {
                "Test distributor unexpectedly retained a registration before SDK register"
            }
            UnifiedPush.register(app, instance = instance, messageForDistributor = "Kompi cold-receiver fixture")

            val registration = try {
                withTimeout(20_000) {
                    while (livePeer.pushRegistrations.isEmpty()) delay(25)
                    livePeer.pushRegistrations.first().registration
                }
            } catch (_: TimeoutCancellationException) {
                val distributorReceived = FakeUnifiedPushDistributorControl.registrationReceived(app)
                val summary = repository.summaries().firstOrNull { it.hostId == hostId }
                throw AssertionError(
                    "push registration timed out: distributor_received=$distributorReceived " +
                        "setup_status=${summary?.status} setup_error=${summary?.detailCode} " +
                        "peer_error_count=${livePeer.errors.size}",
                )
            }
            withTimeout(10_000) {
                while (repository.summaries().none { it.hostId == hostId && it.status == PushSetupStatus.Active }) delay(25)
            }
            // This file is explicitly an ephemeral sender fixture and is app-private. It contains
            // no machine identity private key; the Python driver removes it in a finally block.
            fixtureSnapshot
                .put("registrationId", registration.registrationId)
                .put("p256dh", registration.p256dh)
                .put("auth", registration.auth)
                .put("integrityKey", registration.integrityKey)
            app.openFileOutput(FIXTURE_FILE, Context.MODE_PRIVATE).use {
                it.write(fixtureSnapshot.toString().toByteArray(Charsets.UTF_8))
            }
        } finally {
            runCatching { transport?.closeAndJoin() }
            runCatching { database?.close() }
            runCatching { peer?.close() }
        }
    }

    /** Stage externally-produced raw WebPush bytes into the test APK's distributor IPC fixture. */
    @Test fun stageEncryptedPayloadFromPrivateFixtureFile() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val fixture = JSONObject(app.openFileInput(FIXTURE_FILE).bufferedReader().use { it.readText() })
        val expectedPackage = fixture.getString("testPackage")
        check(testContext.packageName == expectedPackage) { "Fixture belongs to a different instrumentation package" }
        val ciphertext = app.openFileInput(CIPHERTEXT_FILE).use { it.readBytes() }
        require(ciphertext.size in 1..4096) { "Encrypted test payload is outside the production ciphertext bound" }
        try {
            FakeUnifiedPushDistributorControl.stage(app, ciphertext)
        } catch (failure: Throwable) {
            runCatching { FakeUnifiedPushDistributorControl.clear(app) }
                .exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    @Test fun cleanupEphemeralFixtureAndRegistration(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val settings = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val fixture = JSONObject(app.openFileInput(FIXTURE_FILE).bufferedReader().use { it.readText() })
        fixture.optString("hostEndpoint").takeIf(String::isNotEmpty)?.let { value ->
            val host = requireNotNull(PushHostId.parse(value)) { "Invalid temporary host in cleanup snapshot" }
            UnifiedPushRepository.get(app).forget(host)
            val store = SecureStore(app)
            if (store.trustedMachines().any { it.machineId == host.value }) store.forgetTrustedMachine(host.value)
            check(app.getSharedPreferences("identity_v1", Context.MODE_PRIVATE).edit()
                .remove("chat_model_draft_${host.value}")
                .remove("chat_model_confirmed_${host.value}")
                .commit()) { "Could not remove temporary chat-model configuration" }
            fixture.optString("previousSelectedMachineId").takeIf(String::isNotEmpty)?.let { previous ->
                if (store.trustedMachines().any { it.machineId == previous }) store.selectMachine(previous)
                else error("Previously selected trusted host is missing during cleanup")
            }
        }
        check(settings.edit().putBoolean(
            AgentNotifications.PREFERENCE,
            fixture.optBoolean("previousNotificationsEnabled", false),
        ).commit()) { "Could not restore notification opt-in" }
        val previousDistributor = fixture.optString("previousDistributor").takeIf(String::isNotEmpty)
        if (previousDistributor == null) UnifiedPush.removeDistributor(app)
        else UnifiedPush.saveDistributor(app, previousDistributor)
        FakeUnifiedPushDistributorControl.clear(app)
        // Delete the private recovery snapshot only after every restoration step has succeeded.
        if (app.fileList().contains(FIXTURE_FILE)) check(app.deleteFile(FIXTURE_FILE)) { "Could not remove private fixture" }
        app.deleteFile(CIPHERTEXT_FILE)
    }

    companion object {
        const val FIXTURE_FILE = "unified-push-cold-fixture.json"
        const val CIPHERTEXT_FILE = "unified-push-cold-ciphertext.bin"
    }
}
