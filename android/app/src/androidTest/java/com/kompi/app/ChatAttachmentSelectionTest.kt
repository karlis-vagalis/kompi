package com.kompi.app

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.kompi.app.attachments.AttachmentDraftStore
import com.kompi.app.attachments.DraftAttachment
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real picker contracts/results, MediaStore files, private copies and composer. Only the OS
 * picker selection and final remote submission are controlled; no production host is contacted.
 */
class ChatAttachmentSelectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val nonce = UUID.randomUUID().toString()
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var store: AttachmentDraftStore
    private val sources = mutableListOf<Uri>()
    private var selection = emptyList<Uri>()
    private var appearance by mutableStateOf(AppTheme.System)
    private val launched = mutableListOf<Pair<String, Intent>>()
    private val submissions = mutableListOf<Pair<String, List<DraftAttachment>>>()
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launched.add(contract.javaClass.simpleName to contract.createIntent(context, input))
            val result = if (selection.isEmpty()) null else Intent().apply {
                clipData = ClipData.newUri(context.contentResolver, "Selected attachments", selection.first()).also { clip ->
                    selection.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            dispatchResult(code, if (result == null) Activity.RESULT_CANCELED else Activity.RESULT_OK, result)
        }
    }

    @Before fun mount() {
        val base = compose.activity.applicationContext
        root = File(base.cacheDir, "attachment-selection-$nonce").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("attachment-selection-$nonce-$name", mode)
        }
        store = AttachmentDraftStore(context)
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context,
                LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                    override val activityResultRegistry = registry
                }) {
                ChatBehaviorSurface(modifier = Modifier.imePadding(), appearance = appearance, onSend = { text, attachments ->
                    submissions.add(text to attachments.toList()); "attachment-request"
                })
            }
        }
    }
    @After fun cleanUp() {
        sources.forEach { context.contentResolver.delete(it, null, null) }
        root.deleteRecursively()
    }
    private fun source(name: String, image: Boolean = false, empty: Boolean = false): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (image) "image/png" else "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Kompi-acceptance-$nonce")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        sources.add(uri)
        context.contentResolver.openOutputStream(uri)!!.use { output ->
            if (!empty) {
                if (image) {
                    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.GREEN)
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    bitmap.recycle()
                } else output.write("Content of $name".toByteArray())
            }
        }
        return uri
    }
    private fun click(label: String) = compose.onNodeWithContentDescription(label).assertIsEnabled()
        .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
    private fun pick(label: String, uris: List<Uri>) {
        selection = uris
        compose.onNodeWithContentDescription("Start dictation")
            .performSemanticsAction(SemanticsActions.OnLongClick) { assertTrue(it()) }
        click(label)
    }
    private fun count(value: Int) {
        val label = "$value ${if (value == 1) "attachment" else "attachments"} · Live voice paused"
        compose.waitUntil(10_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(label).assertIsDisplayed()
    }
    private fun capture(name: String = "multiple-attachments") {
        compose.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = File(compose.activity.getExternalFilesDir(null), "chat-attachments").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test fun a1_c2_multiplePhotosAndFilesUseOneOrderedMessageAndPrivateCopies() {
        val photos = listOf(source("first.png", image = true), source("second.png", image = true))
        val files = listOf(source("notes.txt"), source("report.txt"))
        pick("Choose photos", photos)
        count(2)
        pick("Choose files", files)
        count(4)
        compose.onNodeWithTag("prompt-input").performTextInput("Review these together")
        for (name in listOf("first.png", "second.png", "notes.txt", "report.txt")) {
            compose.onNodeWithTag("attachment-drafts").performScrollToNode(hasText(name))
            compose.onNodeWithText(name).assertIsDisplayed()
        }
        compose.onNodeWithTag("attachment-drafts").performScrollToNode(hasText("first.png"))
        capture()
        compose.runOnIdle { appearance = AppTheme.Dark }
        capture("multiple-attachments-dark")
        compose.runOnIdle {
            assertEquals(listOf("PickMultipleVisualMedia", "OpenMultipleDocuments"), launched.map { it.first })
            val photoIntent = launched.first().second
            assertTrue(photoIntent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) ||
                photoIntent.getIntExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 0) > 1)
            assertTrue(launched.last().second.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        }
        click("Send message")
        compose.runOnIdle {
            val (text, attachments) = submissions.single()
            assertEquals("Review these together", text)
            assertEquals(listOf("first.png", "second.png", "notes.txt", "report.txt"), attachments.map { it.fileName })
            assertEquals(4, attachments.map { it.uploadId }.toSet().size)
            assertEquals(listOf(true, true, false, false), attachments.map { it.isImage })
            assertTrue(attachments.all { it.cachedFile.isFile && it.cachedFile.length() == it.length })
            assertEquals(attachments.map { it.uploadId }, AttachmentDraftStore(context).load().map { it.uploadId })
        }
        compose.onNodeWithTag("attachment-drafts").assertDoesNotExist()
    }

    @Test fun a1_c3_removeOneAttachmentAndCancelPickerPreserveRemainingFilesAndDraft() {
        pick("Choose files", listOf(source("keep-a.txt"), source("remove-b.txt"), source("keep-c.txt")))
        count(3)
        compose.onNodeWithTag("prompt-input").performTextInput("Keep this draft")
        compose.onNodeWithTag("attachment-drafts").performScrollToNode(hasText("remove-b.txt"))
        click("Remove remove-b.txt")
        count(2)
        pick("Choose photos", emptyList())
        count(2)
        compose.onNodeWithTag("prompt-input").assertTextEquals("Keep this draft")
        click("Send message")
        compose.runOnIdle { assertEquals(listOf("keep-a.txt", "keep-c.txt"), submissions.single().second.map { it.fileName }) }
    }

    @Test fun a1_a2_failedSelectionIsVisibleAndDoesNotLoseSuccessfulFiles() {
        pick("Choose files", listOf(source("good-a.txt"), source("empty.txt", empty = true), source("good-b.txt")))
        count(2)
        compose.onNode(hasText("1 file couldn't be added. This item is empty.") and hasAnyAncestor(hasTestTag("conversation-composer"))).assertIsDisplayed()
        click("Send message")
        compose.runOnIdle { assertEquals(listOf("good-a.txt", "good-b.txt"), submissions.single().second.map { it.fileName }) }
    }

    @Test fun a1_q2_limitsArePerMessageAndBatchFailuresDoNotConsumeSlots() {
        val files = (1..18).map { source("file-$it.txt") }
        val first = store.importSelection(files + files.first(), 0)
        assertEquals(16, first.added.size)
        assertEquals(2, first.skipped)
        assertTrue(first.message!!.contains("up to 16 files per message"))
        // Those private files can belong to an offline queued message, not the current composer.
        val second = store.importSelection(files.take(2), 0)
        assertEquals(2, second.added.size)
        assertEquals(18, AttachmentDraftStore(context).load().size)
        val remaining = store.importSelection(listOf(source("empty-slot.txt", empty = true)) + files.take(2), 15)
        assertEquals(1, remaining.failures.size)
        assertEquals(1, remaining.added.size)
        assertEquals(1, remaining.skipped)
        // Acknowledging an older message may race with import through a different store instance.
        val threads = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val importing = threads.submit<com.kompi.app.attachments.AttachmentSelectionResult> {
                start.await(); AttachmentDraftStore(context).importSelection(files.take(8), 0)
            }
            val accepting = threads.submit { start.await(); AttachmentDraftStore(context).markSent(first.added) }
            start.countDown()
            val imported = importing.get(10, TimeUnit.SECONDS)
            accepting.get(10, TimeUnit.SECONDS)
            assertEquals((second.added + remaining.added + imported.added).map { it.uploadId }.toSet(),
                store.load().map { it.uploadId }.toSet())
        } finally { threads.shutdownNow() }
    }
}
