package com.kompi.app.attachments

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import com.kompi.app.protocol.generated.ATTACHMENT_MAX_FILE_BYTES
import com.kompi.app.protocol.generated.ATTACHMENT_MAX_FILES_PER_MESSAGE
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** A picked file copied into durable private storage; the original URI/file is never modified. */
data class DraftAttachment(
    val uploadId: String,
    val fileName: String,
    val mediaType: String,
    val length: Long,
    val sha256: String,
    val cachedPath: String,
    val isImage: Boolean,
    val draftScope: String? = null,
) {
    val cachedFile: File get() = File(cachedPath)
}

sealed interface AttachmentImport {
    data class Added(val attachment: DraftAttachment) : AttachmentImport
    data class Failed(val message: String) : AttachmentImport
}

data class AttachmentSelectionResult(
    val added: List<DraftAttachment>,
    val failures: List<String>,
    val skipped: Int,
) {
    val message: String? get() = buildList {
        if (failures.isNotEmpty()) add("${failures.size} ${if (failures.size == 1) "file couldn't" else "files couldn't"} be added. ${failures.first()}")
        if (skipped > 0) add("$skipped ${if (skipped == 1) "file wasn't" else "files weren't"} added. You can attach up to ${AttachmentDraftStore.MAX_DRAFT_ATTACHMENTS} files per message.")
    }.joinToString(" ").ifBlank { null }
}

/**
 * Persists selected content independently of Activity/Compose lifetime. We copy every selected URI
 * before exposing it as a draft, so rotation, process death, and providers without persistable grants
 * cannot invalidate a pending send.
 */
class AttachmentDraftStore(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "attachment-drafts").apply {
        check(isDirectory || mkdirs()) { "Could not create private attachment cache" }
    }
    private val preferences = appContext.getSharedPreferences("attachment-drafts", Context.MODE_PRIVATE)

    fun load(): List<DraftAttachment> = synchronized(mutationLock) { loadLocked() }

    internal fun loadFor(scope: String?): List<DraftAttachment> = synchronized(mutationLock) {
        loadLocked().filter { it.draftScope == scope }
    }

    internal fun claimUnscoped(scope: String, ids: Set<String>? = null) = synchronized(mutationLock) {
        val current = loadLocked()
        val next = current.map { if (it.draftScope == null && (ids == null || it.uploadId in ids)) it.copy(draftScope = scope) else it }
        if (current != next) save(next)
    }

    private fun loadLocked(): List<DraftAttachment> {
        val encoded = preferences.getString("drafts", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.getJSONObject(index)
                    val item = DraftAttachment(
                        uploadId = obj.getString("uploadId"),
                        fileName = obj.getString("fileName"),
                        mediaType = obj.getString("mediaType"),
                        length = obj.getLong("length"),
                        sha256 = obj.getString("sha256"),
                        cachedPath = obj.getString("cachedPath"),
                        isImage = obj.getBoolean("isImage"),
                        draftScope = obj.optString("draftScope").takeIf { it.isNotBlank() },
                    )
                    val expectedPath = File(root.canonicalFile, "${item.uploadId}.file").canonicalFile
                    if (runCatching { UUID.fromString(item.uploadId) }.isSuccess &&
                        item.cachedFile.canonicalFile == expectedPath &&
                        expectedPath.parentFile == root.canonicalFile &&
                        expectedPath.isFile && expectedPath.length() == item.length &&
                        item.length in 1..ATTACHMENT_MAX_FILE_BYTES &&
                        item.sha256.matches(Regex("[0-9a-f]{64}")) &&
                        item.fileName.isNotBlank() && item.fileName.length <= 255 && item.fileName.none { it == '/' || it == '\\' || it.code < 0x20 } &&
                        item.mediaType.isNotBlank() && item.mediaType.length <= 127 && item.mediaType.none { it.code < 0x20 }
                    ) add(item)
                }
            }
        }.getOrDefault(emptyList()).also { valid ->
            if (valid.size != runCatching { JSONArray(encoded).length() }.getOrDefault(0)) save(valid)
        }
    }

    /** The cache also holds other messages' outbox files; enforce limits per composer, not globally. */
    fun importSelection(uris: List<Uri>, currentDraftCount: Int): AttachmentSelectionResult = synchronized(mutationLock) {
        importSelectionLocked(uris, currentDraftCount)
    }

    /** Count only this composer's persisted drafts; queued files belong to other messages. */
    fun importSelection(uris: List<Uri>, pendingAttachmentIds: Set<String>, scope: String? = null): AttachmentSelectionResult = synchronized(mutationLock) {
        val currentDraftCount = loadLocked().count { it.draftScope == scope && it.uploadId !in pendingAttachmentIds }
        importSelectionLocked(uris, currentDraftCount, scope)
    }

    private fun importSelectionLocked(uris: List<Uri>, currentDraftCount: Int, scope: String? = null): AttachmentSelectionResult {
        require(currentDraftCount in 0..MAX_DRAFT_ATTACHMENTS)
        val added = mutableListOf<DraftAttachment>()
        val failures = mutableListOf<String>()
        var skipped = 0
        for (uri in uris.distinct()) {
            if (currentDraftCount + added.size >= MAX_DRAFT_ATTACHMENTS) { skipped++; continue }
            when (val result = importLocked(uri, scope)) {
                is AttachmentImport.Added -> added.add(result.attachment)
                is AttachmentImport.Failed -> failures.add(result.message)
            }
        }
        return AttachmentSelectionResult(added, failures, skipped)
    }

    fun import(uri: Uri): AttachmentImport = synchronized(mutationLock) { importLocked(uri) }

    private fun importLocked(uri: Uri, scope: String? = null): AttachmentImport {
        var temporary: File? = null
        var target: File? = null
        return try {
        val resolver = appContext.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }.orEmpty().ifBlank { "attachment" }
        val fileName = displayName.replace(Regex("[\\u0000-\\u001f/\\\\]"), "_").trim().take(255).ifBlank { "attachment" }
        val mediaType = resolver.getType(uri)?.take(127)?.takeIf { it.matches(Regex("[A-Za-z0-9.+_-]+/[A-Za-z0-9.+_-]+")) } ?: "application/octet-stream"
        val id = UUID.randomUUID().toString()
        val destination = File(root, "$id.part")
        temporary = destination
        target = File(root, "$id.file")
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        resolver.openInputStream(uri)?.use { input ->
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    require(bytes <= ATTACHMENT_MAX_FILE_BYTES) { "This file exceeds the protocol attachment size limit." }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        } ?: error("This item could not be opened.")
        require(bytes > 0) { "This item is empty." }
        check(destination.renameTo(target!!)) { "The selected file could not be saved for sending." }
        val attachment = DraftAttachment(
            uploadId = id,
            fileName = fileName,
            mediaType = mediaType,
            length = bytes,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            cachedPath = target!!.absolutePath,
            isImage = mediaType.startsWith("image/"),
            draftScope = scope,
        )
        save(load() + attachment)
        AttachmentImport.Added(attachment)
        } catch (failure: Exception) {
            temporary?.delete()
            target?.delete()
            AttachmentImport.Failed(failure.message ?: "This item could not be added.")
        }
    }

    fun remove(attachment: DraftAttachment) = synchronized(mutationLock) {
        save(load().filterNot { it.uploadId == attachment.uploadId })
        if (isOwnedCacheFile(attachment)) attachment.cachedFile.delete()
        Unit
    }

    fun markSent(attachments: List<DraftAttachment>) = synchronized(mutationLock) {
        val sentIds = attachments.mapTo(hashSetOf()) { it.uploadId }
        val remaining = load().filterNot { it.uploadId in sentIds }
        save(remaining)
        attachments.forEach { attachment ->
            if (isOwnedCacheFile(attachment)) attachment.cachedFile.delete()
        }
    }

    fun markSent(uploadIds: Set<String>) = synchronized(mutationLock) {
        if (uploadIds.isNotEmpty()) markSent(load().filter { it.uploadId in uploadIds })
    }

    private fun isOwnedCacheFile(item: DraftAttachment): Boolean = runCatching {
        UUID.fromString(item.uploadId)
        item.cachedFile.canonicalFile == File(root, "${item.uploadId}.file").canonicalFile &&
            item.cachedFile.canonicalFile.parentFile == root.canonicalFile
    }.getOrDefault(false)

    private fun save(items: List<DraftAttachment>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject()
                .put("uploadId", item.uploadId)
                .put("fileName", item.fileName)
                .put("mediaType", item.mediaType)
                .put("length", item.length)
                .put("sha256", item.sha256)
                .put("cachedPath", item.cachedPath)
                .put("isImage", item.isImage)
                .put("draftScope", item.draftScope))
        }
        check(preferences.edit().putString("drafts", array.toString()).commit()) { "Could not persist attachment drafts" }
    }

    companion object {
        // UI imports and transport acknowledgements use separate store instances. Serialize
        // their read/modify/write operations so a long multi-file import cannot restore or lose files.
        private val mutationLock = Any()

        // Use protocol-generated limits so UI, transport, and daemon cannot drift.
        val MAX_ATTACHMENT_BYTES: Long get() = ATTACHMENT_MAX_FILE_BYTES
        val MAX_DRAFT_ATTACHMENTS: Int get() = ATTACHMENT_MAX_FILES_PER_MESSAGE
    }
}
