package com.kompi.app.assistant.native

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import com.kompi.app.assistant.actions.phoneExecutionDenial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

internal object NativeFiles {
    const val TREE = "nativeDocumentTree"
    private data class Document(val tree: String, val uri: Uri, val name: String, val mime: String, val size: Long, val modified: Long, val expires: Long)
    private val handles = LinkedHashMap<String, Document>()
    fun tree(context: Context): Uri? {
        val raw = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(TREE, null) ?: return null
        return context.contentResolver.persistedUriPermissions.firstOrNull { it.uri.toString() == raw && it.isReadPermission }?.uri
    }
    fun forget(context: Context) {
        tree(context)?.let { uri ->
            context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }?.let { grant ->
                runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or if(grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0) }
            }
        }
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().remove(TREE).apply()
        synchronized(handles) { handles.clear() }
    }
    private fun lookup(id: String): Document? = synchronized(handles) { handles[id]?.takeIf { it.expires > SystemClock.elapsedRealtime() } }
    private fun register(document: Document): String = synchronized(handles) {
        handles.entries.removeAll { it.value.expires <= SystemClock.elapsedRealtime() }
        UUID.randomUUID().toString().also { handles[it] = document; while(handles.size > 128) handles.remove(handles.keys.first()) }
    }
    private val projection = arrayOf("document_id", "_display_name", "mime_type", "_size", "last_modified")
    private fun document(context: Context, tree: Uri, uri: Uri): Document? = context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
        if(!c.moveToFirst()) null else Document(tree.toString(), uri, c.text("_display_name", 300), c.text("mime_type", 200), c.number("_size"), c.number("last_modified"), SystemClock.elapsedRealtime() + 120_000)
    }
    private fun matches(context: Context, original: Document): Boolean {
        val tree = tree(context) ?: return false
        if(tree.toString() != original.tree || original.expires <= SystemClock.elapsedRealtime()) return false
        val current = document(context, tree, original.uri) ?: return false
        return original.name == current.name && original.mime == current.mime && original.size == current.size && original.modified == current.modified
    }
    private fun parent(context: Context, id: String?): Document? {
        val tree = tree(context) ?: return null
        if(id == null || id == "root") return document(context, tree, DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)))
        return lookup(id)?.takeIf { it.mime == DocumentsContract.Document.MIME_TYPE_DIR && matches(context, it) }
    }
    fun parse(name: String, input: JSONObject): NativeOperation? = when(name) {
        "list_files" -> {
            val a = NativeArguments(input, "directory", "query", "limit", "offset")
            val directory = a.text("directory", 128)
            val query = a.text("query", 200)?.lowercase()
            val limit = a.long("limit", 1, 30, 20).toInt()
            val offset = a.long("offset", 0, 5000, 0).toInt()
            val saved = directory?.takeUnless { it == "root" }?.let(::lookup)
            NativeTask(name, "List up to $limit files in ${saved?.name ?: "the folder selected in Phone access"}${query?.let { " with names containing “$it”" }.orEmpty()} (offset $offset). Only names and metadata will be shared.") { context, _ -> withContext(Dispatchers.IO) {
                val parent = parent(context, directory)
                if(parent == null) nativeError("folder_access_required", "Choose an approved folder in Phone access settings, or list it again for a fresh directory handle.")
                else {
                    val tree = Uri.parse(parent.tree)
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent.uri))
                    context.contentResolver.query(children, projection, null, null, null)?.use { c ->
                        val items = JSONArray()
                        var scanned = 0; var matched = 0; var more = false
                        while(c.moveToNext() && scanned++ < 5000) {
                            val fileName = c.text("_display_name", 300)
                            if(query != null && !fileName.lowercase().contains(query)) continue
                            if(matched++ < offset) continue
                            if(items.length() >= limit) { more = true; break }
                            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(c.getColumnIndexOrThrow("document_id")))
                            val doc = Document(tree.toString(), uri, fileName, c.text("mime_type", 200), c.number("_size"), c.number("last_modified"), SystemClock.elapsedRealtime() + 120_000)
                            val id = register(doc)
                            if(!appendBounded(items, JSONObject().put("id", id).put("name", fileName).put("mimeType", doc.mime).put("directory", doc.mime == DocumentsContract.Document.MIME_TYPE_DIR)
                                .put("sizeBytes", if(c.isNull(c.getColumnIndexOrThrow("_size"))) JSONObject.NULL else doc.size)
                                .put("modifiedAt", if(c.isNull(c.getColumnIndexOrThrow("last_modified"))) JSONObject.NULL else doc.modified))) { more = true; break }
                        }
                        nativePage(items, if(more) offset + items.length() else null).put("scanLimitReached", scanned >= 5000).put("handleLifetimeSeconds", 120)
                    } ?: nativeError("folder_unavailable", "The document provider did not return this folder.")
                }
            } }
        }
        "read_file", "share_file" -> {
            val a = NativeArguments(input, *if(name == "read_file") arrayOf("id", "maxCharacters") else arrayOf("id"))
            val saved = lookup(a.text("id", 128, true)!!)
            val max = if(name == "read_file") a.long("maxCharacters", 1, 8000, 4000).toInt() else 0
            NativeTask(name, if(name == "read_file") "Read and share up to $max characters from ${saved?.name ?: "an expired file handle"}." else "Open Android's share sheet for ${saved?.name ?: "an expired file handle"}. Choose the destination there; the file is not uploaded to Pi.") { context, activity ->
                val valid = withContext(Dispatchers.IO) { saved != null && matches(context, saved) }
                if(!valid || saved == null) nativeError("target_changed", "File access expired or metadata changed. List the approved folder again.")
                else if(name == "share_file") {
                    val send = Intent(Intent.ACTION_SEND).setType(saved.mime).putExtra(Intent.EXTRA_STREAM, saved.uri).apply {
                        clipData = android.content.ClipData.newRawUri(saved.name, saved.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    phoneExecutionDenial()?.let { return@NativeTask it }
                    activity.startActivity(Intent.createChooser(send, null))
                    JSONObject().put("status", "launched").put("message", "Opened the Android share sheet. Sharing is not confirmed complete.")
                } else withContext(Dispatchers.IO) {
                    if(!(saved.mime.startsWith("text/") || saved.mime in setOf("application/json", "application/xml", "application/yaml"))) nativeError("unsupported_file", "Only plain UTF-8 text documents can be read with this tool.")
                    else {
                        phoneExecutionDenial()?.let { return@withContext it }
                        val bytes = context.contentResolver.openInputStream(saved.uri)?.use { stream ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(4096)
                            while(output.size() < 32_001) {
                                val count = stream.read(buffer, 0, minOf(buffer.size, 32_001 - output.size()))
                                if(count < 0) break
                                if(count == 0) throw java.io.IOException("Document provider returned no bytes.")
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        if(bytes == null) nativeError("file_unavailable", "The provider could not open this file.")
                        else if(bytes.size > 32_000) nativeError("file_too_large", "This text file exceeds the 32 KB read limit. Select a smaller document.")
                        else {
                            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                            JSONObject().put("status", "read").put("name", saved.name).put("text", text.take(max)).put("truncated", text.length > max)
                        }
                    }
                }
            }
        }
        "save_text_file" -> {
            val a = NativeArguments(input, "directory", "name", "text")
            val directory = a.text("directory", 128)
            val fileName = a.text("name", 150, true)!!.also { require(it != "." && it != ".." && '/' !in it && '\\' !in it && it.none(Char::isISOControl)) }
            val text = a.text("text", 6000, true)!!
            NativeTask(name, "Create a new text file “$fileName” in the approved folder. Existing files will not be overwritten.\n\nExact contents:\n$text") { context, _ -> withContext(Dispatchers.IO) {
                val parent = parent(context, directory)
                val writable = context.contentResolver.persistedUriPermissions.any { it.uri.toString() == parent?.tree && it.isWritePermission }
                if(parent == null || !writable) nativeError("folder_write_required", "Choose a writable approved folder in Phone access settings.")
                else {
                    phoneExecutionDenial()?.let { return@withContext it }
                    val uri = DocumentsContract.createDocument(context.contentResolver, parent.uri, "text/plain", fileName)
                    if(uri == null) nativeError("not_created", "The provider did not create the document.")
                    else {
                        if (phoneExecutionDenial() != null) return@withContext nativeError("unknown", "Access changed after the document was created. Check the folder before retrying.")
                        val output = context.contentResolver.openOutputStream(uri, "w")
                        if(output == null) nativeError("unknown", "A document may have been created but could not be opened. Check the folder before retrying.")
                        else {
                            try { output.use { it.write(text.toByteArray(Charsets.UTF_8)); it.flush() } }
                            catch(error: Exception) { return@withContext nativeError("unknown", "The new document may be partially written. Check the folder before retrying.") }
                            performed("Created a new UTF-8 text document. Cloud synchronization is not confirmed.").put("name", document(context, Uri.parse(parent.tree), uri)?.name ?: fileName)
                        }
                    }
                }
            } }
        }
        else -> null
    }
}
