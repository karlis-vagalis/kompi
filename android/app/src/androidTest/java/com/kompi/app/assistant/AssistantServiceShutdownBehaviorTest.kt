package com.kompi.app.assistant

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Stop ordering and stale-generation fencing; never starts an assistant service or microphone. */
@RunWith(AndroidJUnit4::class)
class AssistantServiceShutdownBehaviorTest {
    @Before fun requireDisposableEmulator() {
        require(InstrumentationRegistry.getArguments().getString("kompiDisposableEmulator") == "true") {
            "Requires explicit -e kompiDisposableEmulator true on a disposable emulator"
        }
        require(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")) {
            "Assistant lifecycle behavior tests must not run on a physical device"
        }
    }

    @Test fun retiringOwnerBarrierSurvivesBlankServiceAndOnlyExactOwnerCompletionReleasesIt() = onMain {
        assertFalse("No prior test/service may leave a retiring owner", AssistantServiceRetirementBarrier.isRetiring())
        val retiring = AssistantServiceRetirementBarrier.Owner()
        val blankService = AssistantServiceRetirementBarrier.Owner()
        try {
            assertTrue(AssistantServiceRetirementBarrier.begin(retiring))
            assertTrue(AssistantServiceRetirementBarrier.cleanupPending.value)
            assertFalse(AssistantServiceRetirementBarrier.admitsStart())

            // A replacement service created for a null/unknown intent has no native resources and
            // cannot replace, complete, or otherwise erase the still-blocked old owner's barrier.
            assertFalse(AssistantServiceRetirementBarrier.begin(blankService))
            assertFalse(AssistantServiceRetirementBarrier.complete(blankService, cleanupSucceeded = true))
            assertTrue(AssistantServiceRetirementBarrier.isRetiring())
            assertFalse(AssistantServiceRetirementBarrier.admitsStart())

            // A stale/foreign completion cannot release it either; only the exact old owner can.
            assertFalse(AssistantServiceRetirementBarrier.complete(AssistantServiceRetirementBarrier.Owner(), cleanupSucceeded = true))
            assertTrue(AssistantServiceRetirementBarrier.complete(retiring, cleanupSucceeded = true))
            assertFalse(AssistantServiceRetirementBarrier.cleanupPending.value)
            assertTrue(AssistantServiceRetirementBarrier.admitsStart())
        } finally {
            AssistantServiceRetirementBarrier.complete(retiring, cleanupSucceeded = true)
        }
    }

    @Test fun failedRetirementCleanupKeepsStartBarrierUntilExactOwnerCompletes() = onMain {
        val retiring = AssistantServiceRetirementBarrier.Owner()
        val unrelated = AssistantServiceRetirementBarrier.Owner()
        try {
            assertTrue(AssistantServiceRetirementBarrier.begin(retiring))
            assertFalse(AssistantServiceRetirementBarrier.complete(retiring, cleanupSucceeded = false))
            assertTrue(AssistantServiceRetirementBarrier.cleanupPending.value)
            assertFalse(AssistantServiceRetirementBarrier.admitsStart())
            assertFalse(AssistantServiceRetirementBarrier.complete(unrelated, cleanupSucceeded = true))
            assertTrue(AssistantServiceRetirementBarrier.isRetiring())
        } finally {
            // Restore global test state only after asserting that failure itself never releases it.
            AssistantServiceRetirementBarrier.complete(retiring, cleanupSucceeded = true)
        }
    }

    @Test fun oldCleanupCannotOverwriteNewSessionAfterStopReleasesLogicalOwner() = onMain {
        val old = AssistantCoordinator.claim().token
        val cleanupGate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        val cleanup = scheduleAssistantServiceCleanup(
            scope = scope,
            fenceOwners = listOf({ events += "capture-fenced" }),
            releaseForeground = { events += "foreground-released" },
            releaseLogicalSession = { failure ->
                events += "logical-released"
                assertEquals(null, failure)
                assertTrue(AssistantCoordinator.released(old, null))
            },
            onStopFailure = { throw AssertionError("unexpected stop failure", it) },
        ) { _ ->
            events += "native-cleanup-waiting"
            cleanupGate.await()
            // A late observer/cleanup from the retired generation must not mutate the replacement.
            assertFalse(AssistantCoordinator.publish(old) {
                AssistantCoordinator.snapshot.value = com.kompi.app.live.LiveConversationSnapshot()
            })
            assertFalse(AssistantCoordinator.released(old, "late cleanup"))
        }

        assertEquals(
            listOf("capture-fenced", "foreground-released", "logical-released", "native-cleanup-waiting"),
            events,
        )
        assertFalse(AssistantCoordinator.mode.value.assistant)

        val replacement = AssistantCoordinator.claim().token
        assertTrue(AssistantCoordinator.isCurrent(replacement))
        assertFalse(AssistantCoordinator.publish(old) { AssistantCoordinator.failure.value = "stale" })
        assertEquals("new generation still owns the UI", replacement.generation, AssistantCoordinator.mode.value.generation)
        assertTrue(AssistantCoordinator.mode.value.assistant)

        cleanupGate.complete(Unit)
        assertTrue("scheduled cleanup resumes after the barrier", cleanup.isCompleted)
        assertTrue("late cleanup leaves the replacement current", AssistantCoordinator.isCurrent(replacement))
        AssistantCoordinator.released(replacement, null)
        scope.cancel()
    }

    @Test fun fenceFailureStillReleasesForegroundAndSchedulesCleanup() = onMain {
        val owner = AssistantCoordinator.claim().token
        val events = mutableListOf<String>()
        var cleanupFailure: Throwable? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        val cleanup = scheduleAssistantServiceCleanup(
            scope = scope,
            fenceOwners = listOf(
                { events += "fence-began"; error("simulated owner fence failure") },
                { events += "remaining-owner-fenced" },
            ),
            releaseForeground = { events += "foreground-released" },
            releaseLogicalSession = { failure ->
                events += "logical-released"
                cleanupFailure = failure
                AssistantCoordinator.released(owner, failure?.message)
            },
            onStopFailure = { events += "stop-failure-reported" },
        ) { failure ->
            events += "cleanup-scheduled"
            assertNotNull(failure)
        }

        assertEquals(
            listOf("fence-began", "stop-failure-reported", "remaining-owner-fenced", "foreground-released", "logical-released", "cleanup-scheduled"),
            events,
        )
        assertNotNull(cleanupFailure)
        assertFalse(AssistantCoordinator.mode.value.assistant)
        assertTrue(cleanup.isCompleted)
        scope.cancel()
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
