package com.kompi.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kompi.app.stream.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Real public-delta/chunk planner → real bounded reply queue → controlled audio-admission sink.
 * Completion here proves admission/sealing, not actual AudioTrack drain or acoustic intelligibility. */
@RunWith(AndroidJUnit4::class)
class LiveSpeechPipelineBehaviorTest {
    private data class Phrase(val request: String, val sequence: Long, val text: String)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val admitted = CopyOnWriteArrayList<Phrase>()
    private val stopped = CopyOnWriteArrayList<String?>()
    private val failed = CopyOnWriteArrayList<String>()
    private val diagnostics = CopyOnWriteArrayList<Pair<String, SpeechStreamSignal>>()
    private val displayed = ConcurrentHashMap<String, String>()
    private val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val entered = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val finished = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private var rejectRequest: String? = null
    private lateinit var queue: ReplySpeechQueue
    private lateinit var stream: AssistantSpeechStream
    @Before fun pipeline() {
        queue = ReplySpeechQueue(scope,
            enqueue = { id, sequence, text ->
                entered.getOrPut(id) { CompletableDeferred() }.complete(Unit)
                gates[id]?.await()
                if (id == rejectRequest) false else { admitted.add(Phrase(id, sequence, text)); true }
            },
            finish = { id -> finished.getOrPut(id) { CompletableDeferred() }.complete(Unit) },
            stop = { stopped.add(it) }, failed = { failed.add(it) },
        )
        stream = AssistantSpeechStream(
            speak = queue::offer, stop = queue::cancel,
            onTextUpdate = { id, _, text -> displayed[id] = text },
            onDiagnostic = { id, signal -> diagnostics.add(id to signal); if (signal == SpeechStreamSignal.Finished) queue.complete(id) },
            speechLocale = { Locale.US }, hasQueuedAudio = queue::hasPendingSpeech,
        )
    }
    @After fun cleanup() { gates.values.forEach { it.complete(Unit) }; queue.cancel(); scope.cancel() }
    private suspend fun finish(id: String) { withTimeout(5_000) { finished.getOrPut(id) { CompletableDeferred() }.await() } }
    private suspend fun joinChildren() { withTimeout(5_000) { scope.coroutineContext[Job]!!.children.toList().forEach { it.join() } } }
    private fun text(id: String) = admitted.filter { it.request == id }.joinToString(" ") { it.text }

    @Test fun l38_streamedClausesAndFinalSettlementSpeakFullAnswerExactlyOnce() = runBlocking {
        val full = "The first complete sentence. The second complete sentence. Final words."
        stream.accept("a", "conversation", 0, "The first complete sentence. ")
        stream.accept("a", "conversation", 1, "The second complete sentence. ")
        stream.accept("a", "conversation", 2, "Final words.")
        stream.finish("a", "conversation", full); finish("a")
        assertEquals(full, displayed["a"]); assertEquals(full, text("a"))
        assertEquals(admitted.indices.map(Int::toLong), admitted.map { it.sequence })
        val count = admitted.size
        stream.finish("a", "conversation", full); joinChildren()
        assertEquals(count, admitted.size)
    }

    @Test fun l39_duplicateDeltaCannotDuplicateSpeech() = runBlocking {
        assertTrue(stream.accept("a", "conversation", 0, "Keep this sentence once. "))
        assertFalse(stream.accept("a", "conversation", 0, "Keep this sentence once. "))
        stream.finish("a", "conversation", "Keep this sentence once."); finish("a")
        assertEquals("Keep this sentence once.", text("a"))
    }

    @Test fun l40_gapStopsUnsafeAudioButCanonicalTextRepairsWithoutReplay() = runBlocking {
        stream.accept("a", "conversation", 0, "Known prefix without a full sentence")
        assertFalse(stream.accept("a", "conversation", 2, "Missing middle must not be guessed"))
        stream.finish("a", "conversation", "Complete authoritative answer")
        joinChildren()
        assertEquals("Complete authoritative answer", displayed["a"])
        assertTrue("a" in stopped)
        assertTrue(admitted.isEmpty())
        assertTrue(("a" to SpeechStreamSignal.SequenceGap) in diagnostics)
    }

    @Test fun l41_blockedAudioAdmissionRetainsAllClausesAndDoesNotSealEarly() = runBlocking {
        val gate = CompletableDeferred<Unit>(); gates["a"] = gate
        val full = "First complete sentence. Second complete sentence. Third complete sentence."
        stream.accept("a", "conversation", 0, full)
        stream.finish("a", "conversation", full)
        withTimeout(5_000) { entered.getOrPut("a") { CompletableDeferred() }.await() }
        assertTrue(admitted.isEmpty()); assertTrue(queue.hasPendingSpeech("a"))
        assertFalse(finished["a"]?.isCompleted == true)
        gate.complete(Unit); finish("a")
        assertEquals(full, text("a")); assertTrue(failed.isEmpty())
    }

    @Test fun l42_cancelDuringBlockedAdmissionPreventsLatePhrasesAndCompletion() = runBlocking {
        val gate = CompletableDeferred<Unit>(); gates["a"] = gate
        stream.accept("a", "conversation", 0, "Old complete sentence. More old words.")
        stream.finish("a", "conversation", "Old complete sentence. More old words.")
        withTimeout(5_000) { entered.getOrPut("a") { CompletableDeferred() }.await() }
        stream.cancel("a"); gate.complete(Unit); joinChildren()
        assertTrue(admitted.isEmpty()); assertTrue("a" in stopped)
        assertFalse(finished["a"]?.isCompleted == true)
        assertFalse(stream.accept("a", "conversation", 1, "Zombie output"))
    }

    @Test fun l43_cancellingOneRequestDoesNotDiscardAnotherRequestsQueuedSpeech() = runBlocking {
        gates["a"] = CompletableDeferred()
        stream.accept("a", "conversation", 0, "Old request sentence."); stream.finish("a", "conversation", "Old request sentence.")
        withTimeout(5_000) { entered.getOrPut("a") { CompletableDeferred() }.await() }
        stream.accept("b", "conversation", 0, "Independent request sentence."); stream.finish("b", "conversation", "Independent request sentence.")
        stream.cancel("a"); gates["a"]!!.complete(Unit); finish("b"); joinChildren()
        assertEquals("Independent request sentence.", text("b")); assertEquals("", text("a"))
        assertFalse("b" in stopped)
    }

    @Test fun l44_canonicalReplacementDoesNotFlushAdmittedAudioOrLoseOriginalTail() = runBlocking {
        val streamed = "Original first sentence. Original buffered tail."
        stream.accept("a", "conversation", 0, streamed)
        stream.finish("a", "conversation", "A different canonical display.")
        finish("a")
        assertEquals("A different canonical display.", displayed["a"])
        assertEquals(streamed, text("a")); assertFalse("a" in stopped)
        assertTrue(("a" to SpeechStreamSignal.CanonicalReplacement) in diagnostics)
    }

    @Test fun l45_fencedCodeIsNotSpokenAndOriginalMarkdownRemainsVisible() = runBlocking {
        val source = "Public introduction.\n```kotlin\nsecretTestCall()\n```\nPublic conclusion."
        stream.accept("a", "conversation", 0, source); stream.finish("a", "conversation", source); finish("a")
        assertEquals(source, displayed["a"])
        assertEquals("Public introduction. Public conclusion.", text("a"))
        assertFalse(text("a").contains("secretTestCall"))
    }

    @Test fun l46_structuredNumbersTimesAndUrlsSurviveArbitraryDeltaEdges() = runBlocking {
        val source = "Meet at 12:30 with 12.50 EUR. Open https://example.org/path?q=1 and keep every word."
        source.chunked(7).forEachIndexed { index, delta -> assertTrue(stream.accept("a", "conversation", index.toLong(), delta)) }
        stream.finish("a", "conversation", source); finish("a")
        assertEquals(source, displayed["a"]); assertEquals(source, text("a"))
        assertTrue(admitted.any { "12:30" in it.text }); assertTrue(admitted.any { "https://example.org/path?q=1" in it.text })
    }

    @Test fun l47_failedAudioAdmissionStopsOnlyItsRequestAndReportsFailure() = runBlocking {
        rejectRequest = "a"
        stream.accept("a", "conversation", 0, "Rejected audio sentence."); stream.finish("a", "conversation", "Rejected audio sentence.")
        joinChildren()
        assertEquals(listOf("a"), failed); assertTrue("a" in stopped); assertTrue(admitted.isEmpty())
        assertFalse(finished["a"]?.isCompleted == true)
        stream.accept("b", "conversation", 0, "Next request works."); stream.finish("b", "conversation", "Next request works."); finish("b")
        assertEquals("Next request works.", text("b"))
    }

    @Test fun l47_oversizedAtomicSpanFailsSpeechWithoutLosingDisplayedSource() = runBlocking {
        val source = "https://example.org/" + "x".repeat(300)
        stream.accept("a", "conversation", 0, source)
        stream.finish("a", "conversation", source); joinChildren()
        assertEquals(source, displayed["a"]); assertTrue("a" in stopped); assertTrue(admitted.isEmpty())
        assertTrue(("a" to SpeechStreamSignal.UnspeakableToken) in diagnostics)
    }

    @Test fun l48_emojiTailDoesNotInvalidateNormalSpeechOrTheNextRequest() = runBlocking {
        val source = "A normal spoken answer. 🎧✨"
        stream.accept("a", "conversation", 0, source); stream.finish("a", "conversation", source); finish("a")
        assertTrue(text("a").contains("A normal spoken answer.")); assertTrue(failed.isEmpty())
        stream.accept("b", "conversation", 0, "Next answer works."); stream.finish("b", "conversation", "Next answer works."); finish("b")
        assertEquals("Next answer works.", text("b")); assertTrue(failed.isEmpty())
    }

    @Test fun l62_l70_contextInvalidationRejectsOldGenerationWithoutAutoplayingHistory() = runBlocking {
        val old = stream.generationToken()
        gates["a"] = CompletableDeferred()
        stream.accept("a", "old-conversation", 0, "Old answer.", old)
        stream.finish("a", "old-conversation", "Old answer.", old)
        withTimeout(5_000) { entered.getOrPut("a") { CompletableDeferred() }.await() }
        stream.invalidate(); gates["a"]!!.complete(Unit)
        assertFalse(stream.accept("a", "old-conversation", 1, "Old suffix", old))
        stream.finish("a", "old-conversation", "Old answer.", old)
        stream.accept("b", "new-conversation", 0, "New owned answer.", stream.generationToken())
        stream.finish("b", "new-conversation", "New owned answer.", stream.generationToken()); finish("b"); joinChildren()
        assertEquals("", text("a")); assertEquals("New owned answer.", text("b"))
    }

    @Test fun l94_queueBudgetFailureIsExplicitRatherThanSilentlyDroppingSpeech() = runBlocking {
        gates["a"] = CompletableDeferred()
        assertTrue(queue.offer("a", 0, "A valid initial phrase"))
        withTimeout(5_000) { entered.getOrPut("a") { CompletableDeferred() }.await() }
        assertFalse(queue.offer("a", 1, "word ".repeat(10_000)))
        gates["a"]!!.complete(Unit); joinChildren()
        assertEquals(listOf("a"), failed); assertTrue("a" in stopped)
        assertFalse(finished["a"]?.isCompleted == true)
    }

    @Test fun l95_retiredRequestCannotRegainSpeechOwnershipAfterManyLaterTurns() = runBlocking {
        repeat(70) { index ->
            val request = "turn-$index"
            stream.accept(request, "conversation", 0, "Completed answer $index.")
            stream.finish(request, "conversation", "Completed answer $index."); finish(request)
            stream.cancel(request)
        }
        assertFalse("Retiring tombstones must not authorize a very late old turn", stream.accept("turn-0", "conversation", 0, "Old turn resurrected."))
    }
}
