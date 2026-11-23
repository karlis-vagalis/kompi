package com.kompi.app

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.kompi.app.attachments.AttachmentDraftStore
import com.kompi.app.attachments.AttachmentShareIntake
import com.kompi.app.attachments.AttachmentShareViewModel
import com.kompi.app.attachments.AttachmentSharePayload
import com.kompi.app.attachments.AttachmentShareReceipt
import com.kompi.app.attachments.ShareIntentDeliveryTracker
import com.kompi.app.attachments.toReceipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Shares URI parsing, durable draft import and the existing composer; no host or assistant action. */
class AttachmentShareIntakeBehaviorTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val nonce = UUID.randomUUID().toString()
    private lateinit var context: Context
    private lateinit var root: File
    private val sourceUris = mutableListOf<Uri>()

    @Before fun setUp() {
        require(InstrumentationRegistry.getArguments().getString("kompiDisposableEmulator") == "true") {
            "Requires an explicitly marked disposable emulator"
        }
        require(android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish")) {
            "Share behavior must run on an emulator, not a physical device"
        }
        require(!compose.activity.getSystemService(KeyguardManager::class.java).isDeviceSecure) {
            "Requires a disposable emulator without user credentials"
        }
        val base = compose.activity.applicationContext
        root = File(base.cacheDir, "share-intake-$nonce").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("share-intake-$nonce-$name", mode)
        }
    }

    @After fun tearDown() {
        sourceUris.forEach { context.contentResolver.delete(it, null, null) }
        if (::context.isInitialized) {
            AttachmentDraftStore(compose.activity.applicationContext).load()
                .filter { it.fileName == "recreate-$nonce.txt" || it.fileName == "warm-$nonce.txt" }
                .forEach { AttachmentDraftStore(compose.activity.applicationContext).remove(it) }
        }
        root.deleteRecursively()
    }

    private fun contentUri(name: String): Uri = Uri.parse("content://shared.example/$name")

    private fun clip(uris: List<Uri>): ClipData? = uris.firstOrNull()?.let { first ->
        ClipData(ClipDescription("Shared files", arrayOf("application/octet-stream")), ClipData.Item(first)).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
    }

    @Test fun manifestAdvertisesSendAndSendMultipleButNotSendTo() {
        fun resolves(action: String, mimeType: String): Boolean = context.packageManager.queryIntentActivities(
            Intent(action).setType(mimeType), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        ).any { it.activityInfo.packageName == context.packageName && it.activityInfo.name == MainActivity::class.java.name }

        assertTrue(resolves(Intent.ACTION_SEND, "image/jpeg"))
        assertTrue(resolves(Intent.ACTION_SEND_MULTIPLE, "application/pdf"))
        // The wildcard necessarily makes text-only sends visible too; the receiver reports no files.
        assertTrue(resolves(Intent.ACTION_SEND, "text/plain"))
        assertFalse(resolves(Intent.ACTION_SENDTO, "text/plain"))
    }

    @Test fun sendAndSendMultipleCombineClipAndStreamInOrderAndDeduplicate() {
        val first = contentUri("first")
        val second = contentUri("second")
        val third = contentUri("third")
        val single = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, first)
            clipData = clip(listOf(first, second))
        }
        val parsedSingle = AttachmentShareIntake.parse(single) as AttachmentSharePayload.Share
        assertEquals(listOf(first, second), parsedSingle.uris)

        val multiple = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/octet-stream"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(second, third))
            clipData = clip(listOf(first, second))
        }
        val parsedMultiple = AttachmentShareIntake.parse(multiple) as AttachmentSharePayload.Share
        assertEquals(listOf(first, second, third), parsedMultiple.uris)
        assertEquals(0, parsedMultiple.rejectedCount)
        assertEquals(0, parsedMultiple.omittedCount)
    }

    @Test fun rejectsNonContentUrisIgnoresTextOnlyAndReportsActionableEmptyShare() {
        val valid = contentUri("valid")
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///tmp/outside.txt"))
            clipData = clip(listOf(valid, Uri.parse("https://example.test/photo.png"), Uri.parse("content:/missing-authority")))
        }
        val parsed = AttachmentShareIntake.parse(share) as AttachmentSharePayload.Share
        assertEquals(listOf(valid), parsed.uris)
        assertEquals(3, parsed.rejectedCount)

        val textOnly = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://example.test/photo.png")
        }
        val empty = AttachmentShareIntake.parse(textOnly) as AttachmentSharePayload.Share
        assertTrue(empty.uris.isEmpty())
        assertEquals("No files attached. Share a photo or file to add it to the composer.", empty.error)
        assertEquals(AttachmentSharePayload.NotShare, AttachmentShareIntake.parse(Intent(Intent.ACTION_VIEW)))
    }

    @Test fun capsInputAtSixteenAndCountsEveryOmittedItem() {
        val uris = (0 until AttachmentDraftStore.MAX_DRAFT_ATTACHMENTS + 2).map { contentUri("item-$it") }
        val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/octet-stream"
            clipData = clip(uris)
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        val parsed = AttachmentShareIntake.parse(share) as AttachmentSharePayload.Share
        assertEquals(AttachmentDraftStore.MAX_DRAFT_ATTACHMENTS, parsed.uris.size)
        assertEquals(2, parsed.omittedCount)
        assertEquals(uris.take(AttachmentDraftStore.MAX_DRAFT_ATTACHMENTS), parsed.uris)
    }

    @Test fun activityRecreationDeduplicatesCurrentIntentButWarmNewShareIsHandled() {
        val share = AttachmentShareIntake.parse(Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, contentUri("same"))
        }) as AttachmentSharePayload.Share
        val tracker = ShareIntentDeliveryTracker()
        assertTrue(tracker.shouldHandleOnCreate(share, isRecreation = false))
        val recreatedTracker = ShareIntentDeliveryTracker(tracker.lastFingerprint)
        assertFalse(recreatedTracker.shouldHandleOnCreate(share, isRecreation = true))
        assertTrue("A subsequent explicit sharesheet choice is a new import", recreatedTracker.handleOnNewIntent(share))
    }

    @Test fun shareLimitCountsOnlyCurrentComposerDraftsAndSerializesConcurrentImports() {
        val store = AttachmentDraftStore(context)
        val existing = store.importSelection((0 until 15).map { source("existing-$it.txt") }, 0)
        assertEquals(15, existing.added.size)
        val pendingFromOtherMessages = existing.added.take(3).mapTo(mutableSetOf()) { it.uploadId }
        val share = store.importSelection((0 until 5).map { source("shared-$it.txt") }, pendingFromOtherMessages)
        assertEquals(1, share.skipped)
        assertEquals(4, share.added.size)
        assertEquals(19, store.load().size)
        assertTrue(share.message!!.contains("1 file wasn't added"))

        // Two simultaneous picker/share imports observe the persisted count under the store lock.
        store.markSent(store.load())
        val base = store.importSelection((0 until 14).map { source("base-$it.txt") }, 0)
        assertEquals(14, base.added.size)
        val parallelA = (0 until 3).map { source("parallel-a-$it.txt") }
        val parallelB = (0 until 3).map { source("parallel-b-$it.txt") }
        val gate = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<com.kompi.app.attachments.AttachmentSelectionResult> {
                gate.await(); store.importSelection(parallelA, emptySet())
            }
            val second = executor.submit<com.kompi.app.attachments.AttachmentSelectionResult> {
                gate.await(); store.importSelection(parallelB, emptySet())
            }
            gate.countDown()
            val firstResult = first.get()
            val secondResult = second.get()
            assertEquals(2, firstResult.added.size + secondResult.added.size)
            assertEquals(4, firstResult.skipped + secondResult.skipped)
            assertEquals(AttachmentDraftStore.MAX_DRAFT_ATTACHMENTS, store.load().size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun activityRecreationDuringImportRetainsJobReceiptAndDraftExactlyOnce() {
        val uri = source("recreate-$nonce.txt", ByteArray(4 * 1024 * 1024) { (it % 251).toByte() })
        val share = AttachmentShareIntake.parse(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
        }) as AttachmentSharePayload.Share
        val before = ViewModelProvider(compose.activity)[AttachmentShareViewModel::class.java]
        before.receiveOnCreate(share, isRecreation = false)
        assertTrue(before.importing.value)

        compose.activityRule.scenario.recreate()
        val after = ViewModelProvider(compose.activity)[AttachmentShareViewModel::class.java]
        assertSame("The Activity-scoped ViewModel retains the granted-URI copy job", before, after)
        after.receiveOnCreate(share, isRecreation = true)
        compose.waitUntil(30_000) { !after.importing.value && after.receipt.value != null }
        val saved = AttachmentDraftStore(compose.activity.applicationContext).load().filter { it.fileName == "recreate-$nonce.txt" }
        assertEquals("Rotation must not import the same launch intent twice", 1, saved.size)
        assertEquals(4L * 1024 * 1024, saved.single().length)
        assertTrue(saved.single().cachedFile.isFile)
        saved.forEach { AttachmentDraftStore(compose.activity.applicationContext).remove(it) }
    }

    @Test fun warmActivityShareStagesFilesWithoutHostSelectionOrAutomaticSend() {
        val uri = source("warm-$nonce.txt")
        val share = AttachmentShareIntake.parse(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = clip(listOf(uri))
        }) as AttachmentSharePayload.Share
        val viewModel = ViewModelProvider(compose.activity)[AttachmentShareViewModel::class.java]
        var submissions = 0
        compose.setContent {
            ChatBehaviorSurface(
                peer = null,
                attachmentShareReceipt = viewModel.receipt,
                attachmentShareImporting = viewModel.importing,
                onSend = { _, _ -> submissions++; "unexpected" },
            )
        }

        viewModel.receiveOnNewIntent(share)
        compose.waitUntil(30_000) { !viewModel.importing.value && viewModel.receipt.value != null }
        compose.onNodeWithText("warm-$nonce.txt").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        compose.runOnIdle { assertEquals("Receiving a share must never submit it", 0, submissions) }
        val persisted = AttachmentDraftStore(compose.activity.applicationContext).load().single { it.fileName == "warm-$nonce.txt" }
        assertTrue(persisted.cachedFile.isFile)
    }

    @Test fun warmShareAddsPrivateDraftAndColdComposerRestoresIt() {
        val uri = source("shared-note.txt")
        val share = AttachmentShareIntake.parse(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = clip(listOf(uri))
        }) as AttachmentSharePayload.Share
        val receipt = MutableStateFlow<AttachmentShareReceipt?>(null)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalActivityResultRegistryOwner provides compose.activity,
            ) {
                ChatBehaviorSurface(peer = null, attachmentShareReceipt = receipt)
            }
        }

        val selection = kotlinx.coroutines.runBlocking {
            withContext(Dispatchers.IO) { AttachmentDraftStore(context).importSelection(share.uris, emptySet()) }
        }
        assertEquals(1, selection.added.size)
        val persisted = AttachmentDraftStore(context).load().single()
        assertTrue(persisted.cachedFile.isFile)
        assertEquals("shared-note.txt", persisted.fileName)
        assertEquals("Share this file into Kompi".toByteArray().size.toLong(), persisted.length)
        assertNotEquals("The private draft must be a copy, not the source", uri.toString(), persisted.cachedPath)

        compose.runOnIdle { receipt.value = share.toReceipt(selection) }
        compose.onNodeWithText("shared-note.txt").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        // Simulate a cold composer/activity reconstruction: its draft list is rehydrated from disk.
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("shared-note.txt").assertIsDisplayed()
        val afterRecreation = AttachmentDraftStore(context).load().single()
        assertEquals(persisted.uploadId, afterRecreation.uploadId)
        assertEquals(persisted.sha256, afterRecreation.sha256)
    }

    private fun source(name: String, contents: ByteArray = "Share this file into Kompi".toByteArray()): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Kompi-share-$nonce")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        sourceUris.add(uri)
        context.contentResolver.openOutputStream(uri)!!.use { it.write(contents) }
        return uri
    }
}
