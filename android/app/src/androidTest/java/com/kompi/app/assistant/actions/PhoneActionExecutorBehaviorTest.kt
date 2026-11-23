package com.kompi.app.assistant.actions

import android.app.KeyguardManager
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kompi.app.assistant.AgentNotifications
import com.kompi.app.assistant.AssistantScreenContext
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry

/** Device-bound tests of the confirmation boundary, safe intent construction and real notification delivery. */
@RunWith(AndroidJUnit4::class)
class PhoneActionExecutorBehaviorTest {
    @Before fun requireDisposableEmulator() {
        require(InstrumentationRegistry.getArguments().getString("kompiDisposableEmulator") == "true") {
            "Requires explicit -e kompiDisposableEmulator true on a disposable emulator"
        }
        require(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")) {
            "Android action/notification behavior tests must not run on a physical device"
        }
    }

    @Test fun phoneActionsRequireConfirmationForegroundAndUnlockedHostAndUseSafeIntents() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scenario = ActivityScenario.launch(PhoneActionTestHostActivity::class.java)
        try {
            scenario.onActivity { host ->
                assertEquals(Lifecycle.State.RESUMED, host.lifecycle.currentState)
                assertFalse("The designated isolated emulator must be unlocked", context.getSystemService(KeyguardManager::class.java).isDeviceLocked)

                val dial = parsed("""{"action":"dial","number":"+1 (555) 123-4567"}""")
                assertEquals("confirmation_required", PhoneActionExecutor.execute(context, host, dial, confirmed = false).getString("code"))
                assertNull(host.startedIntent)
                assertEquals("foreground_required", PhoneActionExecutor.execute(context, null, dial, confirmed = true).getString("code"))

                val listing = PhoneActionExecutor.execute(context, host, parsed("""{"action":"list_apps"}"""), confirmed = true)
                assertEquals("listed", listing.getString("status"))
                assertTrue(listing.toString().toByteArray(Charsets.UTF_8).size <= 14_000)

                val cases = listOf(
                    """{"action":"open_app","packageName":"com.kompi.app"}""" to Intent.ACTION_MAIN,
                    """{"action":"navigate","query":"Central Station"}""" to Intent.ACTION_VIEW,
                    """{"action":"dial","number":"+1 (555) 123-4567"}""" to Intent.ACTION_DIAL,
                    """{"action":"compose_message","number":"5551234","body":"Hello"}""" to Intent.ACTION_SENDTO,
                    """{"action":"set_alarm","hour":7,"minute":30,"label":"Wake"}""" to android.provider.AlarmClock.ACTION_SET_ALARM,
                    """{"action":"set_timer","seconds":90,"label":"Tea"}""" to android.provider.AlarmClock.ACTION_SET_TIMER,
                    """{"action":"open_url","url":"HTTPS://Example.COM/a/../b?q=1"}""" to Intent.ACTION_VIEW,
                    """{"action":"compose_email","to":["one@example.com"],"subject":"Subject","body":"Body"}""" to Intent.ACTION_SENDTO,
                    """{"action":"web_search","query":"weather today"}""" to Intent.ACTION_WEB_SEARCH,
                    """{"action":"open_settings","target":"wifi"}""" to android.provider.Settings.ACTION_WIFI_SETTINGS,
                    """{"action":"open_settings","target":"bluetooth"}""" to android.provider.Settings.ACTION_BLUETOOTH_SETTINGS,
                    """{"action":"open_settings","target":"location"}""" to android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS,
                    """{"action":"open_settings","target":"app_notifications"}""" to android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                    """{"action":"open_settings","target":"app_details"}""" to android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    """{"action":"open_settings","target":"alarms"}""" to android.provider.AlarmClock.ACTION_SHOW_ALARMS,
                )
                cases.forEach { (json, expectedAction) ->
                    host.startedIntent = null
                    val result = PhoneActionExecutor.execute(context, host, parsed(json), confirmed = true)
                    assertEquals("launched", result.getString("status"))
                    val intent = host.startedIntent
                    assertNotNull("Expected handoff for $json", intent)
                    assertEquals(expectedAction, intent!!.action)
                    assertFalse("No action may request direct phone calls", intent.action == Intent.ACTION_CALL)
                    assertFalse("No action may send SMS directly", intent.action == Intent.ACTION_SEND)
                    if (intent.action == Intent.ACTION_SENDTO) assertTrue(intent.data?.scheme in setOf("smsto", "mailto"))
                    when {
                        intent.action == Intent.ACTION_DIAL -> assertEquals("tel", intent.data?.scheme)
                        intent.action == Intent.ACTION_SENDTO && intent.data?.scheme == "smsto" -> {
                            assertEquals("Hello", intent.getStringExtra("sms_body"))
                        }
                        intent.action == Intent.ACTION_SENDTO && intent.data?.scheme == "mailto" -> {
                            assertArrayEquals(arrayOf("one@example.com"), intent.getStringArrayExtra(Intent.EXTRA_EMAIL))
                            assertEquals("Subject", intent.getStringExtra(Intent.EXTRA_SUBJECT))
                            assertEquals("Body", intent.getStringExtra(Intent.EXTRA_TEXT))
                            assertFalse(intent.hasExtra(Intent.EXTRA_STREAM))
                        }
                        intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "https" -> {
                            assertEquals("https://example.com/b?q=1", intent.data.toString())
                        }
                        intent.action == Intent.ACTION_WEB_SEARCH -> {
                            assertEquals("weather today", intent.getStringExtra(android.app.SearchManager.QUERY))
                        }
                        intent.action == android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS -> {
                            assertEquals(context.packageName, intent.getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE))
                        }
                        intent.action == android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS -> {
                            assertEquals("package", intent.data?.scheme)
                            assertEquals(context.packageName, intent.data?.schemeSpecificPart)
                        }
                        intent.action == android.provider.AlarmClock.ACTION_SET_ALARM -> {
                            assertEquals(7, intent.getIntExtra(android.provider.AlarmClock.EXTRA_HOUR, -1))
                            assertEquals(30, intent.getIntExtra(android.provider.AlarmClock.EXTRA_MINUTES, -1))
                            assertFalse(intent.getBooleanExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true))
                        }
                        intent.action == android.provider.AlarmClock.ACTION_SET_TIMER -> {
                            assertEquals(90, intent.getIntExtra(android.provider.AlarmClock.EXTRA_LENGTH, -1))
                            assertFalse(intent.getBooleanExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true))
                        }
                    }
                }
                host.failStartActivity = true
                val unavailable = PhoneActionExecutor.execute(context, host, parsed("""{"action":"web_search","query":"test"}"""), confirmed = true)
                assertEquals("unavailable", unavailable.getString("code"))
                host.failStartActivity = false
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity { host ->
                host.startedIntent = null
                val result = PhoneActionExecutor.execute(context, host, parsed("""{"action":"dial","number":"5551234"}"""), confirmed = true)
                assertEquals("foreground_required", result.getString("code"))
                assertNull(host.startedIntent)
            }
        } finally {
            scenario.close()
        }
    }

    @Test fun notificationConsentPermissionPostDedupeAndRateLimitUsePlatformManager() {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val namespace = "notification-test-${java.util.UUID.randomUUID()}"
        val context = object : android.content.ContextWrapper(appContext) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                baseContext.getSharedPreferences("$namespace-$name", mode)
        }
        val prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val ids = (1..6).map { "instrumentation-${System.nanoTime()}-$it" }
        try {
            // Opt-in is false by default; failure must not post or consume rate budget.
            prefs.edit().putBoolean(AgentNotifications.PREFERENCE, false).commit()
            assertEquals("disabled", AgentNotifications.post(context, ids[0], notification(ids[0])).getString("status"))

            prefs.edit().putBoolean(AgentNotifications.PREFERENCE, true).commit()
            assertTrue("The clean emulator must permit notifications", manager.areNotificationsEnabled())

            // This sequence intentionally lives in one test: unique request IDs prevent static dedupe collisions,
            // while the five-in-a-minute budget is exercised without relying on cross-test static resets.
            repeat(5) { index ->
                val result = AgentNotifications.post(context, ids[index], notification(ids[index]))
                assertEquals("posted", result.getString("status"))
                assertTrue(result.getString("message").contains("does not confirm"))
            }
            assertEquals("already_posted", AgentNotifications.post(context, ids[0], notification(ids[0])).getString("status"))
            assertEquals("rate_limited", AgentNotifications.post(context, ids[5], notification(ids[5])).getString("status"))
            val activeTags = manager.activeNotifications.map { it.tag }.toSet()
            assertTrue(ids.take(5).all { "agent:$it" in activeTags })
        } finally {
            ids.forEach { manager.cancel("agent:$it", 1) }
            prefs.edit().clear().commit()
        }
    }

    @Test fun screenContextRequiresConsentAndInvocationScopedCapture() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertFalse("The designated isolated emulator must be unlocked", context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
        val prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val prior = prefs.getBoolean(AssistantScreenContext.PREFERENCE, false)
        try {
            prefs.edit().putBoolean(AssistantScreenContext.PREFERENCE, false).commit()
            AssistantScreenContext.clear()
            assertEquals("unavailable", AssistantScreenContext.result(context, "unpaired-test-host", AssistantScreenContext.generation).getString("status"))
            prefs.edit().putBoolean(AssistantScreenContext.PREFERENCE, true).commit()
            val invocation = AssistantScreenContext.begin(context)
            // A null assist structure is the platform's unavailable-context case, not an empty successful share.
            AssistantScreenContext.capture(context, invocation, android.app.assist.AssistStructure())
            assertEquals("unavailable", AssistantScreenContext.result(context, "unpaired-test-host", invocation).getString("status"))
            val staleInvocation = AssistantScreenContext.begin(context)
            val currentInvocation = AssistantScreenContext.begin(context)
            AssistantScreenContext.capture(context, staleInvocation, null)
            assertTrue(AssistantScreenContext.state.value is AssistantScreenContext.State.Unavailable) // No trusted host has a screen-text grant.
            AssistantScreenContext.clear(currentInvocation)
            assertTrue(AssistantScreenContext.state.value is AssistantScreenContext.State.Unavailable)
        } finally {
            AssistantScreenContext.clear()
            prefs.edit().putBoolean(AssistantScreenContext.PREFERENCE, prior).commit()
        }
    }

    private fun parsed(json: String): PhoneAction = (PhoneActionParser.parse(json) as PhoneActionParseResult.Parsed).action
    private fun notification(id: String) = JSONObject().put("title", "Test $id").put("body", "Notification behavior test")

}
