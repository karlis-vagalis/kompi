package com.kompi.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.kompi.app.audio.AudioWaveform
import com.kompi.app.audio.KokoroPlaybackPhase
import com.kompi.app.live.LiveReadAlong
import com.kompi.app.protocol.generated.PiFrame
import com.kompi.app.stream.ActivitySpeech
import com.kompi.app.transport.PiSessionStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real Pi events → heading/tool extraction → activity admission → Live controller/UI ownership.
 * Only the speech engine's admission/playout boundary is scripted; no reasoning body is synthesized. */
class LivePiActivityBehaviorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var h: LiveBehaviorHarness
    private lateinit var activity: ActivitySpeech
    private lateinit var pi: PiSessionStore
    private lateinit var request: String
    private val audio = mutableListOf<Pair<String, String>>()
    private val stopped = mutableListOf<String>()
    private var gate = ActivitySpeech.Gate.Open
    private var sequence = 0L
    private val publicText = StringBuilder()
    private val prefix = "One second, I'm"

    @Before fun mountPipeline() {
        activity = ActivitySpeech(speak = { id, text -> audio.add(id to text); true }, stop = { stopped.add(it) })
        h = LiveBehaviorHarness(compose)
        h.mount(); h.stopObserver = { activity.interrupt(it) }; h.start(); h.utterance(1, "Inspect the change")
        request = h.attempts.single().requestId
        h.main {
            activity.begin(request)
            pi = PiSessionStore(
                needSnapshot = {},
                publicDelta = { id, _, _, _, text ->
                    publicText.append(text); activity.onReplyText(id); h.controller.updateAssistantText(id, publicText.toString())
                },
                settled = { id, _, _, _ -> activity.complete(id) },
                thinkingTitle = { id, _, _, title ->
                    h.controller.updateThinkingTitle(id, title)
                    activity.offer(id, "Thinking: $title", prefix, gate, allowTools = true)
                },
                usingTools = { id, _, _ ->
                    h.controller.updateActivity(id, "Using tools")
                    activity.offer(id, "Using tools", prefix, gate, allowTools = true)
                },
                liveActivity = { id, _, _, label ->
                    h.controller.updateActivity(id, label)
                    activity.offer(id, label, prefix, gate, allowTools = true)
                },
            )
            pi.bindConversation("voice-conversation")
            pi.accept(frame("snapshot", JSONObject().put("entries", JSONArray()).put("leafId", JSONObject.NULL), 0, null))
        }
    }
    @After fun cleanup() { activity.invalidate(); h.close() }
    private fun frame(kind: String, json: JSONObject, at: Long = sequence, owner: String? = request) = PiFrame(
        conversationId = "voice-conversation", epoch = "voice-epoch", json = json.toString(), kind = kind,
        last = true, part = 0, requestId = owner, sequence = at, sessionId = "voice-session",
    )
    private fun event(type: String, payload: JSONObject? = null) {
        h.main {
            val json = JSONObject().put("type", type)
            if (payload != null) json.put(if (type == "message_start") "message" else "assistantMessageEvent", payload)
            pi.accept(frame("event", json, ++sequence))
        }
    }
    private fun begin() = event("message_start", JSONObject().put("role", "assistant").put("content", JSONArray()))
    private fun update(type: String, index: Int = 0, delta: String? = null, content: String? = null) = event("message_update",
        JSONObject().put("type", type).put("contentIndex", index).apply { delta?.let { put("delta", it) }; content?.let { put("content", it) } })
    private fun tool(index: Int) = event("message_update", JSONObject().put("type", "toolcall_start").put("contentIndex", index).put("id", "call-$index").put("toolName", "tool-$index"))
    private fun speakingCue(index: Int): KokoroPlaybackPhase.Playing {
        val (id, phrase) = audio[index]
        return KokoroPlaybackPhase.Playing(id, 0, phrase, 0.2f, AudioWaveform.Silence)
    }

    @Test fun l55_emptyThinkingStartAndCompletionNeverInventSpokenFiller() {
        begin(); update("thinking_start"); update("thinking_end", content = "")
        h.main { assertTrue(audio.isEmpty()); assertTrue(stopped.isEmpty()) }
    }

    @Test fun l55_realHeadingSpeaksOnceWithoutReadingReasoningBody() {
        begin(); update("thinking_start")
        update("thinking_delta", delta = "**Inspecting the change**\nPrivate synthetic reasoning that must not be spoken.\n")
        update("thinking_end", content = "**Inspecting the change**\nPrivate synthetic reasoning that must not be spoken.\n")
        h.main {
            assertEquals(listOf("$prefix Inspecting the change"), audio.map { it.second })
            assertFalse(audio.any { it.second.contains("Private synthetic") })
            assertEquals(listOf("Inspecting the change"), h.view.thinkingTitles)
        }
    }

    @Test fun l55_untitledBlockSpeaksOnlyItsFirstSentence() {
        begin(); update("thinking_start")
        update("thinking_delta", delta = "There is substantive synthetic reasoning here.\n")
        update("thinking_delta", delta = "More private reasoning is not another spoken prompt.\n")
        update("thinking_end", content = "There is substantive synthetic reasoning here.\nMore private reasoning is not another spoken prompt.\n")
        h.main { assertEquals(listOf("There is substantive synthetic reasoning here."), audio.map { it.second }) }
    }

    @Test fun l55_toolAfterPlaybackDrainsSpeaksAgainWithoutInterveningText() {
        begin(); tool(0)
        h.main { assertEquals(listOf("$prefix checking a few things"), audio.map { it.second }) }
        tool(1); tool(2)
        h.main { assertEquals(listOf("$prefix checking a few things"), audio.map { it.second }); activity.onPlaybackDrained(audio.single().first) }
        tool(3); tool(4)
        h.main { assertEquals(listOf("$prefix checking a few things", "$prefix checking a few things"), audio.map { it.second }) }
    }

    @Test fun l56_latestHeadingFollowsFirstLineOnDrain() {
        begin(); update("thinking_start")
        update("thinking_delta", delta = "Substantive reasoning without a heading.\n")
        h.main { assertEquals(1, audio.size) }
        val generic = audio.single().first
        update("thinking_delta", delta = "**A real heading**\n")
        update("thinking_delta", delta = "**Latest heading**\n")
        h.main {
            assertEquals(1, audio.size)
            activity.onPlaybackDrained(generic)
            assertEquals(2, audio.size)
            assertTrue(stopped.isEmpty())
            assertEquals("$prefix Latest heading", audio.last().second)
        }
    }

    @Test fun l56_admittedRealTitleSurvivesPublicTextAndModelCompletionUntilAudioDrain() {
        begin(); update("thinking_start"); update("thinking_delta", delta = "**Inspecting the change**\n")
        val phase = speakingCue(0)
        h.main { h.controller.onAssistantPlayback(phase, activity.playbackOwner(phase)) }
        update("text_start", index = 1); update("text_delta", index = 1, delta = "The public answer is now visible.")
        h.main {
            activity.complete(request)
            assertTrue(stopped.isEmpty())
            assertEquals(request, activity.playbackOwner(phase)!!.requestId)
            assertEquals(LiveReadAlong.Thinking("Inspecting the change"), h.view.readAlong)
            assertEquals("The public answer is now visible.", h.view.assistantText)
            activity.onPlaybackDrained(phase.requestId)
            assertNull(activity.playbackOwner(phase))
        }
    }

    @Test fun l56_publicTextCancelsUnstartedGenericAndFencesLaterGenericFiller() {
        begin(); update("thinking_start"); update("thinking_delta", delta = "Substantive reasoning before reply.\n")
        val generic = audio.single().first
        update("text_start", index = 1); update("text_delta", index = 1, delta = "Public answer. ")
        update("thinking_start", index = 2); update("thinking_delta", index = 2, delta = "A later block must not add filler.\n")
        h.main { assertEquals(listOf(generic), stopped); assertEquals(1, audio.size) }
    }

    @Test fun l62_historicalSnapshotDoesNotReplayHeadingsToolsOrAnswer() {
        val message = JSONObject().put("role", "assistant").put("content", JSONArray()
            .put(JSONObject().put("type", "thinking").put("thinking", "**Old heading**\nOld reasoning"))
            .put(JSONObject().put("type", "text").put("text", "Historical public answer")))
        val entry = JSONObject().put("type", "message").put("id", "history-entry").put("parentId", JSONObject.NULL).put("message", message)
        h.main { pi.accept(frame("snapshot", JSONObject().put("entries", JSONArray().put(entry)).put("leafId", "history-entry"), 0, null)) }
        h.main { assertTrue(audio.isEmpty()); assertEquals("", h.view.assistantText); assertEquals("Inspect the change", h.view.caption.visibleText) }
    }

    @Test fun l28_interruptFlushesActivityAndLateCuePcmCannotRegainOwnership() {
        begin(); update("thinking_start"); update("thinking_delta", delta = "**Inspecting the change**\n")
        val phase = speakingCue(0)
        h.main { h.controller.onAssistantPlayback(phase, activity.playbackOwner(phase)); h.input.onset(2); h.input.partial(2, "New point", "") }
        h.main {
            assertTrue(phase.requestId in stopped)
            assertNull(activity.playbackOwner(phase))
            h.controller.onAssistantPlayback(phase, activity.playbackOwner(phase))
        }
        h.main { assertNull(h.view.readAlong); assertEquals("New point", h.view.caption.visibleText); assertEquals("", h.view.playingPhrase) }
    }

    @Test fun l55_l56_onlyOneToolCueQueuesBehindAnAdmittedHeading() {
        begin(); update("thinking_start"); update("thinking_delta", delta = "**Inspecting the change**\n")
        tool(1); tool(2); tool(3)
        update("thinking_delta", delta = "**A later heading must not build a backlog**\n")
        h.main {
            assertEquals(listOf("$prefix Inspecting the change", "$prefix checking a few things"), audio.map { it.second })
            activity.complete(request)
            assertTrue(stopped.isEmpty())
            audio.indices.forEach { assertEquals(request, activity.playbackOwner(speakingCue(it))!!.requestId) }
        }
    }
}
