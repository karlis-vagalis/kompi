package com.kompi.app.attachments

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kompi.app.data.LocalDatabase
import com.kompi.app.security.SecureStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Retains the URI-grant copy job and its receipt across Activity configuration recreation. */
class AttachmentShareViewModel(application: Application) : AndroidViewModel(application) {
    private val tracker = ShareIntentDeliveryTracker()
    private val mutableReceipt = MutableStateFlow<AttachmentShareReceipt?>(null)
    val receipt: StateFlow<AttachmentShareReceipt?> = mutableReceipt.asStateFlow()
    private val mutableImporting = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = mutableImporting.asStateFlow()
    private var activeImports = 0

    fun receiveOnCreate(share: AttachmentSharePayload.Share, isRecreation: Boolean) {
        if (tracker.shouldHandleOnCreate(share, isRecreation)) import(share)
    }

    fun receiveOnNewIntent(share: AttachmentSharePayload.Share) {
        tracker.handleOnNewIntent(share)
        import(share)
    }

    private fun import(share: AttachmentSharePayload.Share) {
        val context = getApplication<Application>()
        val drafts = com.kompi.app.chat.ConversationDrafts(context)
        val scope = SecureStore(context).selectedMachineId()?.let(drafts::selected)?.let(drafts::scope)
        activeImports++
        mutableImporting.value = true
        viewModelScope.launch {
            try {
                val result = try {
                    withContext(Dispatchers.IO) {
                        if (share.uris.isEmpty()) null else {
                            val pending = pendingAttachmentIds()
                            AttachmentDraftStore(getApplication()).importSelection(share.uris, pending, scope)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    AttachmentSelectionResult(
                        added = emptyList(),
                        failures = listOf("The shared files couldn't be copied. Share them again to retry."),
                        skipped = 0,
                    )
                }
                mutableReceipt.value = share.toReceipt(result)
            } finally {
                activeImports--
                mutableImporting.value = activeImports > 0
            }
        }
    }

    private suspend fun pendingAttachmentIds(): Set<String> {
        val context = getApplication<Application>()
        val trustedHosts = SecureStore(context).trustedMachines().map { it.machineId }
        val dao = LocalDatabase.open(context).localDao()
        val rows = trustedHosts.flatMap { hostId -> dao.observeOutbox(hostId).first() }
        return buildSet {
            rows.forEach { row ->
                val attachmentIds = runCatching { JSONArray(row.attachmentIds) }.getOrNull() ?: return@forEach
                for (index in 0 until attachmentIds.length()) {
                    runCatching { attachmentIds.getString(index) }.getOrNull()?.let(::add)
                }
            }
        }
    }
}
