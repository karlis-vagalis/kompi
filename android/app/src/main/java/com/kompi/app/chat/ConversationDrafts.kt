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
    fun save(scope: String?, text: String) {
        prefs.edit().apply {
            if (text.isBlank()) remove(draftKey(scope)) else putString(draftKey(scope), text)
        }.apply()
    }

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
