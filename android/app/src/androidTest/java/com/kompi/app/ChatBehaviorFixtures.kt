package com.kompi.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.kompi.app.ui.KompiUi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.Modifier
import com.kompi.app.attachments.AttachmentShareReceipt
import com.kompi.app.attachments.DraftAttachment
import com.kompi.app.live.LiveConversationController
import com.kompi.app.security.SecureStore.PendingPrompt

/** Real conversation surface; only the remote submission boundary is controlled by the scenario. */
@Composable
internal fun ChatBehaviorSurface(
    modifier: Modifier = Modifier,
    transcript: List<TranscriptLine> = emptyList(),
    connection: String = "Live",
    peer: String? = "Behavior test host",
    initialDraft: String = "",
    history: ChatHistory? = null,
    configuration: com.kompi.app.protocol.generated.ChatModelConfiguration? = com.kompi.app.protocol.generated.ChatModelConfiguration("high", "fixture-model", "fixture"),
    catalog: com.kompi.app.protocol.generated.ModelCatalog? = null,
    onRefreshModels: () -> Unit = {},
    onSelectModel: (com.kompi.app.protocol.generated.ChatModelConfiguration) -> Unit = {},
    attachmentShareReceipt: StateFlow<AttachmentShareReceipt?> = MutableStateFlow(null),
    attachmentShareImporting: StateFlow<Boolean> = MutableStateFlow(false),
    appearance: AppTheme = AppTheme.System,
    unsent: List<PendingPrompt> = emptyList(),
    onDraftChanged: (String) -> Unit = {},
    onDiscard: suspend (String) -> Boolean = { false },
    onSend: suspend (String, List<DraftAttachment>) -> String? = { _, _ -> "request-a" },
) {
    KompiTheme(appearance) {
        Box(Modifier.fillMaxSize().background(KompiUi.colors.background)) {
        ConversationScreen(
            modifier = modifier,
            peerName = peer,
            connectionState = connection,
            transcript = transcript,
            speechMessage = "",
            speechModel = null,
            readAloudReady = false,
            readingMessageKey = null,
            onReadAloud = { _, _ -> },
            onStopReadAloud = {},
            phoneConversationsSupported = true,
            attachmentsSupported = true,
            keyboardVisible = true,
            liveControllerRef = remember { mutableStateOf<LiveConversationController?>(null) },
            onRegisterCaptureHandoff = {},
            onLiveActiveChanged = {},
            liveContextKey = "behavior-test-host",
            unsentPrompts = unsent,
            transientPromptIds = emptySet(),
            pendingAttachmentIds = emptySet(),
            attachmentShareReceipt = attachmentShareReceipt,
            attachmentShareImporting = attachmentShareImporting,
            initialDraft = initialDraft,
            historyLoadError = history?.error ?: false,
            loadingHistory = history?.loading ?: false,
            recoveringHistory = history?.fetching ?: false,
            checkingReply = history?.checkingReply ?: false,
            canCheckHistory = history?.canCheck ?: false,
            onRetryHistory = { history?.retry?.invoke() },
            onDraftChanged = onDraftChanged,
            onDiscardPrompt = onDiscard,
            sessionMessage = "",
            onPair = {},
            onOpenVoiceSettings = {},
            onStopSpeech = {},
            onCancelSpeech = {},
            onCancelPrompt = {},
            onUpload = { _, _ -> error("Unexpected upload in text scenario") },
            onSend = { text, attachments, _, _ -> onSend(text, attachments) },
            modelCatalog = catalog,
            chatConfiguration = configuration,
            confirmedConfiguration = null,
            modelsLoading = false,
            modelError = null,
            onRefreshModels = onRefreshModels,
            onSelectModel = onSelectModel,
        )
        }
    }
}
