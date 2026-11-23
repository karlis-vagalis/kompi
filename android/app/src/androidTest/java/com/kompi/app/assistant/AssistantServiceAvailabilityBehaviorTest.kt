package com.kompi.app.assistant

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Readiness callback ordering only; does not select an assistant or start microphone capture. */
@RunWith(AndroidJUnit4::class)
class AssistantServiceAvailabilityBehaviorTest {
    @Before fun requireDisposableEmulator() {
        require(InstrumentationRegistry.getArguments().getString("kompiDisposableEmulator") == "true") {
            "Requires explicit -e kompiDisposableEmulator true on a disposable emulator"
        }
        require(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")) {
            "Assistant lifecycle behavior tests must not run on a physical device"
        }
    }

    @Test fun destructionBeforeReadinessCannotRepublishTheService() = onMain {
        val services = AssistantServiceAvailability<Any>()
        val registration = services.register(Any())
        assertFalse(registration.retire())
        assertFalse(registration.onReady())
        assertNull(services.current)
        assertFalse(registration.retire())
    }

    @Test fun activeServiceRetirementWithdrawsExactlyOnce() = onMain {
        val services = AssistantServiceAvailability<Any>()
        val service = Any()
        val registration = services.register(service)
        assertTrue(registration.onReady())
        assertSame(service, services.current)
        assertTrue(registration.retire())
        assertNull(services.current)
        assertFalse(registration.retire())
        assertFalse(registration.onReady())
    }

    @Test fun oldReadinessAndShutdownCannotReplaceOrStopTheNewOwner() = onMain {
        val services = AssistantServiceAvailability<Any>()
        val original = Any()
        val old = services.register(original)
        assertTrue(old.onReady())
        assertSame(original, services.current)
        val replacement = Any()
        val current = services.register(replacement)
        assertSame("Registration alone must not publish an unready replacement", original, services.current)
        assertFalse(old.onReady())
        assertTrue(current.onReady())
        assertFalse(old.onReady())
        assertSame(replacement, services.current)
        assertFalse("Stale shutdown must not authorize stopping the replacement", old.retire())
        assertSame(replacement, services.current)
        assertFalse(old.onReady())
    }

    @Test fun oldFirstReadinessCannotOvertakeANewerInstance() = onMain {
        val services = AssistantServiceAvailability<Any>()
        val old = services.register(Any())
        val replacement = Any()
        val current = services.register(replacement)
        assertNull(services.current)
        assertFalse("New registration already fences the older first readiness", old.onReady())
        assertNull(services.current)
        assertTrue(current.onReady())
        assertFalse(old.onReady())
        assertSame(replacement, services.current)
        assertFalse(old.retire())
    }

    @Test fun retiringReplacementDoesNotResurrectAnOlderService() = onMain {
        val services = AssistantServiceAvailability<Any>()
        val original = Any()
        val old = services.register(original)
        assertTrue(old.onReady())
        assertSame(original, services.current)
        val current = services.register(Any())
        assertSame(original, services.current)
        assertTrue(current.onReady())
        assertTrue(current.retire())
        assertNull(services.current)
        assertFalse(old.onReady())
        assertNull(services.current)
        assertFalse(old.retire())
    }

    @Test fun retiringUnreadyReplacementDoesNotReenableOlderFirstReadiness() = onMain {
        val services = AssistantServiceAvailability<Any>()
        val old = services.register(Any())
        val replacement = services.register(Any())
        assertFalse(replacement.retire())
        assertNull(services.current)
        assertFalse("Retiring the latest registration must not roll back service identity", old.onReady())
        assertNull(services.current)
        assertFalse(replacement.onReady())
        assertFalse(old.retire())
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
