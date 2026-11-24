package com.kompi.app.chat

import android.content.Context
import com.kompi.app.transport.ConversationTarget

/** Selection and unsent composer text are local, per paired host and conversation. */
internal class ConversationDrafts(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun selected(hostId: String): ConversationTarget? {
        val value = prefs.getString(selectionKey(hostId), null) ?: return null
        return runCatching {
            when {
                value.startsWith("new:") -> ConversationTarget.draft(hostId, value.removePrefix("new:"))
                else -> ConversationTarget.saved(hostId, value)
            }
        }.getOrNull()
    }

    fun select(target: ConversationTarget) {
        val value = if (target.createIfMissing) "new:${target.id}" else target.id
        val localDrafts = localIds(target.hostId)
        prefs.edit().putString(selectionKey(target.hostId), value)
            .putStringSet(localIdsKey(target.hostId), if (target.createIfMissing) localDrafts + target.id else localDrafts - target.id)
            .apply()
    }

    fun localIds(hostId: String): Set<String> = prefs.getStringSet(localIdsKey(hostId), emptySet()).orEmpty().toSet()

    fun forgetLocal(hostId: String, id: String) {
        prefs.edit().putStringSet(localIdsKey(hostId), localIds(hostId) - id).apply()
    }

    fun scope(target: ConversationTarget): String = "${target.hostId}/${target.id}"
    fun load(scope: String?) = prefs.getString(draftKey(scope), "").orEmpty()
    fun save(scope: String?, text: String) = synchronized(prefs) {
        if (load(scope) == text) return@synchronized
        // A real edit starts a new draft version, even if a subsequent edit restores the
        // exact submitted text. Duplicate Compose observations are not edits.
        prefs.edit().remove(submissionKey(scope)).putLong(revisionKey(scope), revision(scope) + 1).apply {
            if (text.isBlank()) remove(draftKey(scope)) else putString(draftKey(scope), text)
        }.apply()
    }

    fun submission(scope: String?): String? = prefs.getString(submissionKey(scope), null)

    fun revision(scope: String?) = prefs.getLong(revisionKey(scope), 0L)

    /** Called before enqueue. The request ID names this particular draft version. commit()
     * must finish before Room or the transport can publish the prompt to the host. */
    fun markSubmitting(scope: String?, expectedRevision: Long, requestId: String) = synchronized(prefs) {
        if (revision(scope) != expectedRevision) return@synchronized // Already editing the next draft.
        check(prefs.edit().putString(submissionKey(scope), requestId).commit()) { "Could not save draft submission" }
    }

    suspend fun restore(scope: String?, enqueued: suspend (String) -> Boolean): String {
        val request = submission(scope)
        if (request != null && enqueued(request)) finishSubmission(scope, request)
        return load(scope)
    }

    fun finishSubmission(scope: String?, requestId: String) = synchronized(prefs) {
        if (submission(scope) == requestId) {
            prefs.edit().remove(submissionKey(scope)).remove(draftKey(scope)).apply()
        }
    }

    private fun revisionKey(scope: String?) = "draftRevision/${draftKey(scope)}"
    private fun submissionKey(scope: String?) = "draftSubmission/${draftKey(scope)}"

    fun migrateLegacyDraft(scope: String) {
        val legacy = load(null)
        if (legacy.isNotEmpty() && !prefs.contains(draftKey(scope))) {
            prefs.edit().putString(draftKey(scope), legacy).remove(draftKey(null)).apply()
        }
    }

    private fun localIdsKey(hostId: String) = "localChatIds/$hostId"
    private fun selectionKey(hostId: String) = "selectedChat/$hostId"
    private fun draftKey(scope: String?) = if (scope == null) "composerDraft" else "composerDraft/$scope"
}
