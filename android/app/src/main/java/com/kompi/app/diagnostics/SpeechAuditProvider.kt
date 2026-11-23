package com.kompi.app.diagnostics

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/** Read-only local ADB diagnostics. Manifest DUMP permission excludes ordinary applications. */
class SpeechAuditProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        SpeechAudit.initialize(requireNotNull(context))
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        require(projection == null && selection == null && selectionArgs == null && sortOrder == null) {
            "Use the fixed audit projection and optional request URI parameter"
        }
        return when (uri.path) {
            "/events" -> {
                val request = uri.getQueryParameter("request")
                require(request == null || request.length in 1..128) { "Invalid request reference" }
                SpeechAudit.query(request)
            }
            "/status" -> SpeechAudit.status()
            else -> throw IllegalArgumentException("Unknown speech audit path")
        }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.com.kompi.speech-audit"
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("Read-only diagnostics")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only diagnostics")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only diagnostics")
}
