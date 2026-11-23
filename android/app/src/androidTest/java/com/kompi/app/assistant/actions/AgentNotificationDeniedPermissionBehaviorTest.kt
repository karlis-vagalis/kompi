package com.kompi.app.assistant.actions

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kompi.app.assistant.AgentNotifications
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Run only in the dedicated pass after the host revokes POST_NOTIFICATIONS before instrumentation starts. */
@RunWith(AndroidJUnit4::class)
class AgentNotificationDeniedPermissionBehaviorTest {
    @Before fun requireRevokedPermissionOnDisposableEmulator() {
        require(InstrumentationRegistry.getArguments().getString("kompiDisposableEmulator") == "true") {
            "Requires explicit -e kompiDisposableEmulator true on a disposable emulator"
        }
        require(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")) {
            "Notification permission denial test must not run on a physical device"
        }
    }

    @Test fun optedInAgentCannotPostWhenRuntimePermissionWasRevokedBeforeLaunch() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(
            "Host must revoke permission before starting this separate instrumentation pass",
            PackageManager.PERMISSION_DENIED,
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS),
        )

        val namespace = "notification-denied-${UUID.randomUUID()}"
        val context = object : ContextWrapper(appContext) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                baseContext.getSharedPreferences("$namespace-$name", mode)
        }
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val manager = context.getSystemService(NotificationManager::class.java)
        val requestId = "denied-${UUID.randomUUID()}"
        try {
            prefs.edit().putBoolean(AgentNotifications.PREFERENCE, true).commit()
            assertTrue(AgentNotifications.enabled(context))
            val result = AgentNotifications.post(
                context,
                requestId,
                JSONObject().put("title", "Permission denial probe").put("body", "Must not be posted"),
            )
            assertEquals("permission_required", result.getString("status"))
            assertFalse(manager.activeNotifications.any { it.tag == "agent:$requestId" })
        } finally {
            manager.cancel("agent:$requestId", 1)
            prefs.edit().clear().commit()
        }
    }
}
