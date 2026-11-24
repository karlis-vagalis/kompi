package com.kompi.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.kompi.app.protocol.generated.PiFrame
import com.kompi.app.transport.PiSessionStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real Pi snapshot/event projection rendered by the real conversation screen, without a model. */
class ChatProjectionBehaviorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var store: PiSessionStore
    private var snapshotRequests = 0
    private val spoken = mutableListOf<String>()
    private val settled = mutableListOf<String>()

    @Before fun mountConversation() {
        compose.runOnUiThread {
            store = PiSessionStore(
                needSnapshot = { snapshotRequests++ },
                publicDelta = { _, _, _, _, text -> spoken.add(text) },
                settled = { _, _, _, text -> settled.add(text) },
            )
            store.bindConversation("conversation-a")
        }
        compose.setContent {
            val view by store.view.collectAsState()
            ChatBehaviorSurface(transcript = view.lines.map {
                TranscriptLine(it.text, it.fromUser, it.key, isActivity = it.activity, animateArrivals = it.streaming, body = it.body)
            })
        }
    }

    private fun message(role: String, text: String) = JSONObject().put("role", role).put("content",
        JSONArray().put(JSONObject().put("type", "text").put("text", text)))
    private fun entry(id: String, parent: String?, role: String, text: String) = JSONObject()
        .put("type", "message").put("id", id).put("parentId", parent ?: JSONObject.NULL).put("message", message(role, text))
    private fun frame(kind: String, sequence: Long, value: JSONObject, request: String? = "request-a", epoch: String = "epoch-a", conversation: String = "conversation-a") = PiFrame(
        conversationId = conversation, epoch = epoch, json = value.toString(), kind = kind,
        last = true, part = 0, requestId = request, sequence = sequence, sessionId = "session-a",
    )
    private fun accept(frame: PiFrame) = compose.runOnIdle { store.accept(frame) }
    private fun snapshot(sequence: Long = 0, entries: List<JSONObject> = emptyList(), leaf: String? = entries.lastOrNull()?.getString("id"), epoch: String = "epoch-a") =
        frame("snapshot", sequence, JSONObject().put("entries", JSONArray(entries)).put("leafId", leaf ?: JSONObject.NULL), epoch = epoch)
    private fun event(sequence: Long, type: String, detail: JSONObject? = null, epoch: String = "epoch-a"): PiFrame {
        val value = JSONObject().put("type", type)
        if (type == "message_start" || type == "message_end") value.put("message", detail)
        else if (detail != null) value.put("assistantMessageEvent", detail)
        return frame("event", sequence, value, epoch = epoch)
    }
    private fun update(sequence: Long, type: String, text: String? = null) = event(sequence, "message_update",
        JSONObject().put("type", type).put("contentIndex", 0).apply { if (text != null) put("delta", text) })
    private fun streamAnswer() {
        accept(snapshot())
        accept(event(1, "message_start", message("assistant", "")))
        accept(update(2, "text_start"))
        accept(update(3, "text_delta", "Canonical answer"))
    }

    @Test fun m3_s1_streamThenCanonicalSettlementProducesOneAnswerAndOneCompletion() {
        streamAnswer()
        compose.onNodeWithText("Canonical answer").assertIsDisplayed()
        accept(event(4, "message_end", message("assistant", "Canonical answer")))
        accept(frame("settled", 4, JSONObject()
            .put("entries", JSONArray().put(entry("assistant-a", null, "assistant", "Canonical answer")))
            .put("leafId", "assistant-a")))
        compose.onAllNodesWithText("Canonical answer").assertCountEquals(1)
        compose.runOnIdle {
            assertEquals(listOf("Canonical answer"), store.view.value.lines.filterNot { it.activity }.map { it.text })
            assertEquals(listOf("Canonical answer"), spoken)
            assertEquals(listOf("Canonical answer"), settled)
            assertFalse(store.view.value.lines.any { it.streaming })
        }
    }

    @Test fun r6_duplicateDeltaCannotDuplicateVisibleTextOrSpeech() {
        streamAnswer()
        accept(update(3, "text_delta", "Canonical answer"))
        compose.onAllNodesWithText("Canonical answer").assertCountEquals(1)
        compose.runOnIdle { assertEquals(listOf("Canonical answer"), spoken) }
    }

    @Test fun r6_sequenceGapRetainsKnownOutputButRequestsFreshSnapshot() {
        streamAnswer()
        val before = snapshotRequests
        accept(update(5, "text_delta", "Must not be appended across a gap"))
        compose.runOnIdle {
            assertTrue(store.view.value.loading)
            assertEquals(before + 1, snapshotRequests)
            assertFalse(store.view.value.lines.any { it.text.contains("Must not") })
        }
        accept(snapshot(5, listOf(entry("answer", null, "assistant", "Recovered complete answer"))))
        compose.onNodeWithText("Recovered complete answer").assertIsDisplayed()
        compose.runOnIdle { assertFalse(store.view.value.loading) }
    }

    @Test fun r3_s1_eventsBufferedBeforeSnapshotApplyOnlyAfterItsBarrier() {
        accept(event(3, "message_start", message("assistant", "")))
        accept(update(4, "text_start"))
        accept(update(5, "text_delta", "Buffered answer"))
        // Promoting a first-turn draft can request its base after text was already buffered.
        // Beginning that read is not a disconnect: keep the suffix and acknowledge the base
        // by installed sequence, even though replay advances the live sequence to 5.
        compose.runOnIdle { store.beginSnapshot() }
        val base = snapshot(2, listOf(entry("user-a", null, "user", "Original instruction")))
        accept(base)
        compose.runOnIdle { assertTrue(store.installedSnapshot(base)) }
        compose.onNodeWithText("Original instruction").assertIsDisplayed()
        compose.onNodeWithText("Buffered answer").assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(store.view.value.loading)
            assertEquals(listOf("Buffered answer"), spoken)
        }
    }

    @Test fun m3_equalTextFromDistinctEntriesIsNotMistakenForDuplicateIdentity() {
        accept(snapshot(entries = listOf(
            entry("user-a", null, "user", "Repeat this"),
            entry("assistant-a", "user-a", "assistant", "Same answer"),
            entry("user-b", "assistant-a", "user", "Repeat this"),
            entry("assistant-b", "user-b", "assistant", "Same answer"),
        )))
        compose.runOnIdle {
            assertEquals(2, store.view.value.lines.count { it.text == "Same answer" })
            assertEquals(4, store.view.value.lines.map { it.key }.toSet().size)
        }
    }

    @Test fun q3_r6_switchConversationRejectsOldConversationOutput() {
        accept(snapshot(entries = listOf(entry("old", null, "assistant", "Old host answer"))))
        compose.runOnIdle { store.bindConversation("conversation-b") }
        accept(frame("event", 1, JSONObject().put("type", "entry_appended")
            .put("entry", entry("stale", "old", "assistant", "Stale output"))))
        compose.runOnIdle {
            assertEquals("conversation-b", store.view.value.conversationId)
            assertTrue(store.view.value.lines.isEmpty())
            assertTrue(store.view.value.loading)
        }
    }

    @Test fun r3_a5_reconnectReplacesCacheWithoutSpeakingHistoricalAnswerAgain() {
        accept(snapshot(entries = listOf(entry("answer", null, "assistant", "Persisted answer"))))
        compose.runOnIdle { store.reconnect() }
        compose.onNodeWithText("Persisted answer").assertIsDisplayed()
        compose.runOnIdle { assertTrue(store.view.value.loading) }
        accept(snapshot(entries = listOf(entry("answer", null, "assistant", "Persisted answer")), epoch = "epoch-b"))
        compose.onAllNodesWithText("Persisted answer").assertCountEquals(1)
        compose.runOnIdle {
            assertFalse(store.view.value.loading)
            assertTrue(spoken.isEmpty())
        }
    }

    @Test fun r6_staleSnapshotCannotReplaceNewerCanonicalBranch() {
        accept(snapshot(10, listOf(entry("new", null, "assistant", "New authoritative answer"))))
        accept(snapshot(4, listOf(entry("old", null, "assistant", "Obsolete answer"))))
        compose.runOnIdle {
            assertEquals(listOf("New authoritative answer"), store.view.value.lines.map { it.text })
        }
    }

    @Test fun s4_resumedSuffixMustBeMarkedPartialUntilCanonicalReplacement() {
        accept(snapshot())
        accept(update(1, "text_delta", "Only the suffix"))
        compose.runOnIdle {
            assertTrue("A suffix without message_start must expose its incomplete origin", store.view.value.lines.any {
                it.text.contains("partial", ignoreCase = true) || it.text.contains("incomplete", ignoreCase = true)
            })
        }
        val canonical = frame("settled", 1, JSONObject()
            .put("entries", JSONArray().put(entry("full-answer", null, "assistant", "Earlier prefix. Only the suffix")))
            .put("leafId", "full-answer"))
        accept(canonical) // Canonical persistence, not a guessed message_end, replaces the suffix.
        accept(canonical) // Repeated canonical publication cannot finish or speak twice.
        compose.runOnIdle {
            assertEquals(listOf("Earlier prefix. Only the suffix"), store.view.value.lines.map { it.text })
            assertEquals(listOf("Earlier prefix. Only the suffix"), settled)
            assertEquals(listOf("Only the suffix"), spoken)
            assertFalse(store.view.value.lines.any { it.streaming })
        }
    }

    @Test fun r6_branchBarrierAndRetiredEpochFenceDelayedCanonicalTransfers() {
        val older = entry("branch-a", null, "assistant", "Old branch")
        val newer = entry("branch-b", null, "assistant", "Selected branch")
        accept(snapshot(10, listOf(older, newer), leaf = "branch-a"))
        accept(frame("event", 11, JSONObject().put("type", "branch_changed").put("leafId", "branch-b")))
        accept(frame("entries", 10, JSONObject().put("entries", JSONArray().put(older)).put("leafId", "branch-a")))
        compose.runOnIdle {
            assertEquals("branch-b", store.view.value.leafId)
            assertEquals(listOf("Selected branch"), store.view.value.lines.map { it.text })
            store.reconnect()
        }
        accept(snapshot(10, listOf(older), leaf = "branch-a"))
        compose.runOnIdle { assertTrue(store.view.value.loading); assertEquals("branch-b", store.view.value.leafId) }
        accept(snapshot(11, listOf(older, newer), leaf = "branch-b"))
        compose.runOnIdle { store.reconnect() }
        accept(snapshot(0, listOf(newer), epoch = "epoch-b")) // Sequence is scoped to the new worker.
        accept(snapshot(100, listOf(older), epoch = "epoch-a"))
        accept(event(12, "message_start", message("assistant", "Retired runtime"), epoch = "epoch-a"))
        compose.runOnIdle {
            assertFalse(store.view.value.loading)
            assertEquals("branch-b", store.view.value.leafId)
            assertEquals(listOf("Selected branch"), store.view.value.lines.map { it.text })
        }
    }
}
