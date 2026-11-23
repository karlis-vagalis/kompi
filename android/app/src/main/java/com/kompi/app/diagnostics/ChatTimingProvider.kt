package com.kompi.app.diagnostics

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.IOException

/** Local ADB only (DUMP in the manifest). No transcript, token or error text is exported. */
class ChatTimingProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        ChatTiming.initialize(requireNotNull(context))
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        require(projection == null && selection == null && selectionArgs == null && sortOrder == null)
        return ChatTiming.query(when (uri.path) {
            "/status" -> ChatTimingStore.Filter.Status
            "/events" -> filter(uri)
            else -> throw IllegalArgumentException("Unknown chat timing path")
        })
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (uri.path != "/export" || mode != "r") throw FileNotFoundException("Read-only timing export")
        val rows = ChatTiming.query(filter(uri))
        try {
            return openPipeHelper<Cursor>(uri, "application/x-ndjson", null, null) { output, _, _, _, _ ->
                rows.use { data ->
                    try {
                        ParcelFileDescriptor.AutoCloseOutputStream(output).bufferedWriter(Charsets.UTF_8).use { out ->
                            while (data.moveToNext()) {
                                val row = JSONObject().put("schema", 1)
                                data.columnNames.forEachIndexed { i, name ->
                                    row.put(name, when (data.getType(i)) {
                                        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                                        Cursor.FIELD_TYPE_INTEGER -> data.getLong(i)
                                        else -> data.getString(i)
                                    })
                                }
                                out.appendLine(row.toString())
                            }
                        }
                    } catch (_: IOException) { /* Reader closed the pipe. */ }
                }
            }
        } catch (error: Exception) {
            rows.close()
            throw error
        }
    }

    private fun filter(uri: Uri): ChatTimingStore.Filter {
        val values = uri.queryParameterNames
        if (values.isEmpty()) return ChatTimingStore.Filter.All
        val key = values.single()
        val value = uri.getQueryParameters(key).single()
        require(value.length in 1..128)
        return when (key) {
            "request" -> ChatTimingStore.Filter.Request(value)
            "conversation" -> ChatTimingStore.Filter.Conversation(value)
            "connection" -> ChatTimingStore.Filter.Connection(value)
            else -> throw IllegalArgumentException("Unknown chat timing filter")
        }
    }

    override fun getType(uri: Uri): String = if (uri.path == "/export") "application/x-ndjson" else "vnd.android.cursor.dir/vnd.com.kompi.chat-timing"
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("Read-only diagnostics")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only diagnostics")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only diagnostics")
}
