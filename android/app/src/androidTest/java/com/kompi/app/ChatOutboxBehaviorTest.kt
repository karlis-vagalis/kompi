package com.kompi.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kompi.app.data.LocalDatabase
import com.kompi.app.data.LocalRepository
import com.kompi.app.protocol.generated.*
import com.kompi.app.security.SecureStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Disk-backed Room + Android Keystore + real repository; reopen simulates repository/process replacement. */
@RunWith(AndroidJUnit4::class)
class ChatOutboxBehaviorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "chat-behavior-${UUID.randomUUID()}.db"
    private lateinit var database: LocalDatabase
    private lateinit var repository: LocalRepository
    private val configuration = ChatModelConfiguration("high", "model-a", "provider-a")

    @Before fun open() { reopen() }
    private fun reopen() {
        if (::database.isInitialized) database.close()
        database = Room.databaseBuilder(context, LocalDatabase::class.java, databaseName).build()
        repository = LocalRepository(database, SecureStore(context))
    }
    @After fun cleanUp() { database.close(); context.deleteDatabase(databaseName) }

    private suspend fun queue(id: String, text: String = "Instruction $id", host: String = "host-a", time: Long = 100) {
        repository.enqueue(host, id, text, "followUp", emptyList(), configuration, createdAtEpochMillis = time)
    }
    private fun outcome(id: String, state: String, conversation: String = "conversation-a") = RequestSummary(
        conversationId = conversation, nextSequence = 0, outcome = state, requestId = id,
    )
    private suspend fun event(cursor: String, id: String, state: String) {
        repository.applyDurableEvent("host-a", cursor, DurableEvent.RequestChanged(RequestChangedPayload(outcome(id, state))))
    }
    private fun snapshot(cursor: String, requests: List<RequestSummary>) = WireMessage.SyncSnapshot(
        cursor, false, false, requests, emptyList(), EffectiveSettings(1800, 604800), "live",
    )

    @Test fun c2_q2_a5_diskReopenPreservesCompositionOrderContentAndConfiguration() = runBlocking {
        queue("a", "First\nLatviešu 🎧", time = 1000)
        queue("b", time = 10) // A wall-clock correction must not reorder intent.
        queue("c", time = 10)
        reopen()
        val pending = repository.pendingPrompts("host-a")
        assertEquals(listOf("a", "b", "c"), pending.map { it.requestId })
        assertEquals("First\nLatviešu 🎧", pending.first().text)
        assertTrue(pending.all { it.configuration == configuration && it.delivery == "followUp" })
        assertFalse(database.localDao().outboxItem("host-a", "a")!!.encryptedBody.contains("Latviešu"))
    }

    @Test fun finalRejectionReopensWithoutBusyReplyAndRetainsIntentBinding() = runBlocking {
        val chat = UUID.randomUUID().toString()
        repository.enqueue("host-a", "a", "original", "followUp", emptyList(), configuration, targetConversationId = chat)
        assertTrue(repository.beginPromptDispatch("host-a", "a"))
        assertTrue(repository.recordPromptRejection("host-a", "a", LocalRepository.RejectionDisposition.Remove))
        reopen()
        repository.enqueue("host-a", "a", "original", "followUp", emptyList(), configuration, targetConversationId = chat)
        assertTrue(runCatching { queue("a", "different") }.isFailure)
        assertTrue(repository.pendingPrompts("host-a").isEmpty())
        val request = repository.requestState("host-a", "a")!!
        assertEquals("rejected", request.outcome)
        assertTrue(buildTranscriptProjection(com.kompi.app.transport.PiSessionStore.View(conversationId = chat),
            emptyList(), listOf(request), emptyList()).active.none { it.waitingForReply })
    }

    @Test fun retryAndConversationHoldAreNotTerminalRejections() = runBlocking {
        queue("retry"); queue("hold")
        assertTrue(repository.beginPromptDispatch("host-a", "retry"))
        assertTrue(repository.beginPromptDispatch("host-a", "hold"))
        assertTrue(repository.recordPromptRejection("host-a", "retry", LocalRepository.RejectionDisposition.Retry))
        assertTrue(repository.recordPromptRejection("host-a", "hold", LocalRepository.RejectionDisposition.Hold))
        reopen()
        val pending = repository.pendingPrompts("host-a")
        assertEquals(listOf("queued", "waiting-for-conversation"), pending.map { it.state })
        pending.forEach { assertEquals("queued", repository.requestState("host-a", it.requestId)!!.outcome) }
    }

    @Test fun corruptLegacyAttachmentIndexStillAllowsDiscard() = runBlocking {
        queue("legacy")
        val row = database.localDao().outboxItem("host-a", "legacy")!!
        database.localDao().putOutbox(row.copy(encryptedBody = SecureStore(context).protect("old text"), attachmentIds = "[false]"))
        val prompt = repository.pendingPrompts("host-a").single()
        assertEquals("old text", prompt.text)
        assertNull(prompt.configuration)
        assertTrue(prompt.attachmentIds.isEmpty())
        assertTrue(repository.discardUnacceptedPrompt("host-a", "legacy"))
    }

    @Test fun corruptAttachmentIndexUsesImmutableEncryptedIntent() = runBlocking {
        val ids = listOf(UUID.randomUUID().toString())
        repository.enqueue("host-a", "a", "file", "followUp", ids, configuration)
        val row = database.localDao().outboxItem("host-a", "a")!!
        database.localDao().putOutbox(row.copy(attachmentIds = "broken"))
        reopen()
        assertEquals(ids, repository.pendingPrompts("host-a").single().attachmentIds)
    }

    @Test fun q3_r3_hostsWithSameRequestIdRemainIsolatedAcrossRecovery() = runBlocking {
        queue("same", "Host A instruction")
        queue("same", "Host B instruction", host = "host-b")
        repository.reconcileOutstandingRequests("host-a", listOf(outcome("same", "completed")))
        reopen()
        assertTrue(repository.pendingPrompts("host-a").isEmpty())
        assertEquals("Host B instruction", repository.pendingPrompts("host-b").single().text)
        assertEquals("queued", repository.requestState("host-b", "same")!!.outcome)
    }

    @Test fun q4_x1_discardMiddleItemPreservesNeighborsAndPersistsCancellation() = runBlocking {
        listOf("a", "b", "c").forEach { queue(it) }
        assertTrue(repository.discardUnacceptedPrompt("host-a", "b"))
        assertFalse(repository.discardUnacceptedPrompt("host-a", "b"))
        reopen()
        assertEquals(listOf("a", "c"), repository.pendingPrompts("host-a").map { it.requestId })
        assertEquals("discarded", repository.requestState("host-a", "b")!!.outcome)
    }

    @Test fun x1_x2_acceptedRequestCannotBeDiscardedAsIfItNeverReachedDaemon() = runBlocking {
        queue("a"); queue("b")
        event("1", "a", "running")
        val result = runCatching { repository.discardUnacceptedPrompt("host-a", "a") }
        assertTrue("Local discard must fail or refuse once remote execution is known", result.isFailure || result.getOrNull() == false)
        reopen()
        assertEquals(listOf("a", "b"), repository.pendingPrompts("host-a").map { it.requestId })
        assertEquals("running", repository.requestState("host-a", "a")!!.outcome)
    }

    @Test fun r3_r4_terminalRecoveryRemovesOnlySettledItemsAndNeverResurrectsThem() = runBlocking {
        listOf("completed", "failed", "cancelled", "uncertain", "rejected", "waiting").forEach { queue(it) }
        repository.applySnapshot("host-a", snapshot("8", listOf(
            outcome("completed", "completed"), outcome("failed", "failed"), outcome("cancelled", "cancelled"),
            outcome("uncertain", "uncertain"), outcome("rejected", "rejected"), outcome("waiting", "queued"),
        )))
        reopen()
        assertEquals(listOf("waiting"), repository.pendingPrompts("host-a").map { it.requestId })
        assertEquals("accepted", repository.pendingPrompts("host-a").single().state)
        event("8", "completed", "running") // Delayed duplicate cursor cannot undo terminal recovery.
        assertEquals("completed", repository.requestState("host-a", "completed")!!.outcome)
        assertEquals("8", repository.currentCursor("host-a"))
    }

    @Test fun r6_duplicateAndOldEventsCannotRewindStateOrCursorAfterReopen() = runBlocking {
        queue("a")
        event("1", "a", "running")
        event("2", "a", "completed")
        reopen()
        event("1", "a", "running")
        event("2", "a", "running")
        assertEquals("completed", repository.requestState("host-a", "a")!!.outcome)
        assertEquals("2", repository.currentCursor("host-a"))
        assertTrue(repository.pendingPrompts("host-a").isEmpty())
    }

    @Test fun r6_gapFailsAtomicallyThenSnapshotAllowsCatchUp() = runBlocking {
        queue("a")
        event("1", "a", "running")
        assertTrue(runCatching { event("3", "a", "completed") }.isFailure)
        assertEquals("1", repository.currentCursor("host-a"))
        assertEquals("running", repository.requestState("host-a", "a")!!.outcome)
        assertTrue(repository.hasQueuedPrompt("host-a", "a"))
        repository.applySnapshot("host-a", snapshot("3", listOf(outcome("a", "completed"))))
        reopen()
        assertEquals("3", repository.currentCursor("host-a"))
        assertFalse(repository.hasQueuedPrompt("host-a", "a"))
    }

    @Test fun r4_lateAcceptedAckCannotResurrectCompletedExecution() = runBlocking {
        queue("a")
        event("1", "a", "completed")
        repository.markPromptAccepted("host-a", "a")
        reopen()
        assertEquals("completed", repository.requestState("host-a", "a")!!.outcome)
        assertTrue(repository.pendingPrompts("host-a").isEmpty())
    }

    @Test fun r1_conflictingLocalRequestReuseCannotOverwriteSavedIntent() = runBlocking {
        queue("same", "Original immutable instruction")
        assertTrue("Conflicting request IDs must be explicitly rejected", runCatching { queue("same", "Different instruction") }.isFailure)
        reopen()
        assertEquals("Original immutable instruction", repository.pendingPrompts("host-a").single().text)
        assertEquals(configuration, repository.pendingPrompts("host-a").single().configuration)
    }

    @Test fun r1_r4_identicalRetriesRetainIntentOrderAndNeverRequeueTerminalWork() = runBlocking {
        queue("a", "Original immutable instruction", time = 10)
        queue("b", time = 20)
        repository.markPromptAccepted("host-a", "b")
        repository.markPromptAccepted("host-a", "b") // Duplicate ACK cannot rewind state.
        assertEquals("accepted", repository.pendingPrompts("host-a").last().state)
        event("1", "a", "running")
        queue("a", "Original immutable instruction", time = 999)
        repository.markPromptAccepted("host-a", "a") // A delayed acknowledgement is not a rewind.
        val original = repository.pendingPrompts("host-a").first()
        assertEquals(listOf("a", "b"), repository.pendingPrompts("host-a").map { it.requestId })
        assertEquals(10L, original.createdAtEpochMillis)
        assertEquals("accepted", original.state)
        assertEquals("running", repository.requestState("host-a", "a")!!.outcome)
        for ((delivery, attachments, model) in listOf(
            Triple("steer", emptyList<String>(), configuration),
            Triple("followUp", listOf("00000000-0000-0000-0000-000000000001"), configuration),
            Triple("followUp", emptyList<String>(), configuration.copy(modelId = "different-model")),
        )) {
            assertTrue("Conflicting intent must be explicitly rejected", runCatching {
                repository.enqueue("host-a", "a", original.text, delivery, attachments, model)
            }.isFailure)
        }
        reopen()
        assertEquals(original, repository.pendingPrompts("host-a").first())
        event("2", "a", "completed")
        reopen()
        queue("a", original.text) // An identical retry cannot become a new local submission.
        assertTrue(runCatching { queue("a", "Conflicting terminal-ID reuse") }.isFailure)
        assertEquals(listOf("b"), repository.pendingPrompts("host-a").map { it.requestId })
        assertEquals("completed", repository.requestState("host-a", "a")!!.outcome)
        repository.reconcileOutstandingRequests("host-a", listOf(outcome("a", "running")))
        assertEquals("completed", repository.requestState("host-a", "a")!!.outcome)
        assertTrue(runCatching { repository.bindPromptToConversation("host-a", "a", "other-conversation") }.isFailure)
    }

    @Test fun r6_staleSnapshotCannotRewindCompletedRequestAndResumeCursor() = runBlocking {
        queue("a")
        event("1", "a", "running")
        event("2", "a", "completed")
        runCatching { repository.applySnapshot("host-a", snapshot("1", listOf(outcome("a", "running")))) }
        reopen()
        assertEquals("2", repository.currentCursor("host-a"))
        assertEquals("completed", repository.requestState("host-a", "a")!!.outcome)
        assertFalse(repository.hasQueuedPrompt("host-a", "a"))
    }
}
