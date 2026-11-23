package com.kompi.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.kompi.app.live.LiveConversationState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Controlled network/ASR boundaries; real Live actor, UI and encrypted per-host outbox. */
class LiveOwnershipAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var h: LiveBehaviorHarness
    @Before fun mount() { h = LiveBehaviorHarness(compose); h.mount() }
    @After fun cleanup() { h.close() }

    @Test fun l29_lateOldCancelCompletionCannotStopTheReplacementReplyOrNextPoint() {
        h.start(); h.utterance(1, "Review the first change")
        val first = h.attempts.single().requestId
        h.playing(first, "First answer")
        val cancellation = h.gate(); h.cancelGate = cancellation
        h.utterance(2, "Explain the second change instead")
        h.await("old cancellation starts while replacement intent remains owned") { h.cancelled == listOf(first) }
        val second = h.attempts.last().requestId
        h.playing(second, "Second answer remains current")
        h.main { cancellation.complete(Unit) }
        h.main {
            assertEquals(LiveConversationState.Speaking(second), h.view.state)
            assertEquals("Second answer remains current", h.view.assistantText)
            h.controller.onAssistantPlaybackDrained(first)
            h.controller.onAssistantPlaybackDrained(first)
        }
        h.main { assertEquals(LiveConversationState.Speaking(second), h.view.state) }
        h.main { h.controller.onAssistantPlaybackDrained(second) }
        h.utterance(3, "Continue with a third independent point")
        val rows = runBlocking { h.repository.pendingPrompts("host-a") }
        assertEquals(h.attempts.map { it.requestId }.toSet(), rows.map { it.requestId }.toSet())
        assertEquals(3, rows.size)
        assertEquals(1, h.inputs.size)
        assertEquals(listOf(first), h.cancelled)
    }

    @Test fun l35_contextSwapFencesBothDelayedSendAndCancelFromTheOldHost() {
        val send = h.gate(); val cancel = h.gate()
        h.sendGate = send; h.cancelGate = cancel
        h.start(); h.utterance(1, "Only host A may receive this point")
        val first = h.attempts.single()
        val oldInput = h.input
        h.main { h.controller.onContextChanged("host-b:conversation-b") }
        h.await("old context is stopped") { h.view.state == LiveConversationState.Interrupted && oldInput.stops == 1 }
        h.sendGate = null; h.submissionHost = "host-b"
        h.start(); h.utterance(2, "Only host B may receive the replacement")
        val second = h.attempts.last()
        h.playing(second.requestId, "Host B's answer")
        h.main {
            send.complete(Unit); cancel.complete(Unit)
            oldInput.final(99, "Stale host A words")
            oldInput.listener.onLocalStop(com.kompi.app.live.LiveLocalStopReason.EngineFailure("Old host A failure"))
            h.controller.updateAssistantText(first.requestId, "Old host A reply")
            h.controller.onAssistantPlaybackDrained(first.requestId)
        }
        h.main {
            assertEquals(LiveConversationState.Speaking(second.requestId), h.view.state)
            assertEquals("Host B's answer", h.view.assistantText)
            assertEquals(2, h.attempts.size)
        }
        val a = runBlocking { h.repository.pendingPrompts("host-a") }.single()
        val b = runBlocking { h.repository.pendingPrompts("host-b") }.single()
        assertEquals(first.requestId, a.requestId); assertEquals(first.text, a.text)
        assertEquals(second.requestId, b.requestId); assertEquals(second.text, b.text)
        assertEquals(2, h.inputs.size)
    }
}
