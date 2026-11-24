package com.kompi.app

import android.os.Bundle
import android.net.Uri
import android.content.ClipData
import android.content.ClipboardManager
import android.animation.ValueAnimator
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import com.kompi.app.attachments.Offer
import com.kompi.app.attachments.rememberArtifactPicker
import com.kompi.app.protocol.generated.OfferedArtifact
import com.kompi.app.transport.ArtifactDownloadTarget
import com.kompi.app.transport.ArtifactSaveException
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import com.kompi.app.audio.DictationTranscript
import com.kompi.app.audio.OfflineSpeechSessionPhase
import com.kompi.app.audio.dictationSendAllowed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.kompi.app.ui.KompiAlertDialog as AlertDialog
import com.kompi.app.ui.KompiButton as Button
import com.kompi.app.ui.KompiCircularProgress as CircularProgressIndicator
import com.kompi.app.ui.KompiDropdownMenu as DropdownMenu
import com.kompi.app.ui.KompiDropdownMenuItem as DropdownMenuItem
import com.kompi.app.ui.KompiFilterChip as FilterChip
import com.kompi.app.ui.KompiDivider as HorizontalDivider
import com.kompi.app.ui.KompiIconButton as IconButton
import com.kompi.app.ui.KompiLinearProgressIndicator as LinearProgressIndicator
import com.kompi.app.ui.KompiListItem as ListItem
import com.kompi.app.ui.KompiOutlinedButton as OutlinedButton
import com.kompi.app.ui.KompiOutlinedTextField as OutlinedTextField
import com.kompi.app.ui.KompiRadioButton as RadioButton
import com.kompi.app.ui.KompiScaffold as Scaffold
import com.kompi.app.ui.KompiSurface as Surface
import com.kompi.app.ui.KompiSwitch as Switch
import com.kompi.app.ui.KompiText as Text
import com.kompi.app.ui.KompiTextButton as TextButton
import com.kompi.app.ui.KompiUi
import com.kompi.app.ui.ConnectionStatusPill
import com.kompi.app.ui.SessionResetDeadline
import com.kompi.app.ui.ChatAtmosphere
import com.kompi.app.ui.KompiColors
import com.kompi.app.ui.KompiShapes
import com.kompi.app.ui.KompiStyle
import com.kompi.app.ui.KompiStyleProvider
import com.kompi.app.ui.defaultKompiTypography
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.content.ContextCompat
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import com.kompi.app.data.ConversationViewModel
import com.kompi.app.data.LocalDatabase
import com.kompi.app.data.LocalRepository
import com.kompi.app.security.SecureStore
import com.kompi.app.security.SecureStore.PendingPrompt
import com.kompi.app.audio.OfflineSpeechSession
import com.kompi.app.audio.KokoroTtsEngine
import com.kompi.app.audio.KokoroTtsState
import com.kompi.app.audio.KokoroVoice
import com.kompi.app.audio.KokoroVoiceId
import com.kompi.app.audio.KokoroSpeechStatus
import com.kompi.app.audio.KokoroPlaybackPhase
import com.kompi.app.audio.BluetoothAudioRoute
import com.kompi.app.audio.WhistleModelInstaller
import com.kompi.app.audio.WhistleInstallResult
import com.kompi.app.audio.InstalledWhistleModel
import com.kompi.app.audio.ParakeetModelInstaller
import com.kompi.app.audio.SpeechInputEngine
import com.kompi.app.audio.SpeechRecognitionModel
import com.kompi.app.settings.ParakeetInstallState
import com.kompi.app.settings.SpeechInputSettings
import com.kompi.app.audio.OfflineTranscript
import com.kompi.app.audio.whistle.WhistleBackend
import com.kompi.app.audio.whistle.WhistleVocabulary
import com.kompi.app.transport.IrohTransport
import com.kompi.app.transport.AckState
import com.kompi.app.transport.Delivery
import com.kompi.app.transport.ResultState
import com.kompi.app.stream.AssistantSpeechStream
import com.kompi.app.stream.ActivitySpeech.Gate as ActivitySpeechGate
import com.kompi.app.attachments.AttachmentDraftStore
import com.kompi.app.attachments.AttachmentShareIntake
import com.kompi.app.attachments.AttachmentSharePayload
import com.kompi.app.attachments.AttachmentShareReceipt
import com.kompi.app.attachments.AttachmentShareViewModel
import com.kompi.app.attachments.AttachmentDraftRow
import com.kompi.app.attachments.AttachmentUiStatus
import com.kompi.app.attachments.DraftAttachment
import com.kompi.app.attachments.MessageAttachment
import com.kompi.app.attachments.AttachmentDispatchStage
import com.kompi.app.chat.ChatActionButton
import com.kompi.app.chat.ChatActionTone
import com.kompi.app.chat.ChatMessage
import com.kompi.app.chat.OutgoingMessageStatus
import com.kompi.app.chat.ReplyPendingRow
import com.kompi.app.chat.KompiSection
import com.kompi.app.chat.KompiSectionDivider
import com.kompi.app.chat.ChatLucideIcon
import com.kompi.app.chat.KompiIconBadge
import com.kompi.app.peers.PeerDetailsRequest
import com.kompi.app.peers.PeerDetailsScreen
import com.kompi.app.peers.PeerDirectoryHost
import com.kompi.app.peers.PeerDirectoryScreen
import com.kompi.app.peers.PeerSidebarMenu
import com.kompi.app.peers.SidebarSession
import com.kompi.app.chat.ConversationDrafts
import com.kompi.app.transport.ConversationTarget
import com.kompi.app.transport.PhoneConversation
import com.kompi.app.transport.ConversationState
import com.kompi.app.peers.PeerHostSettings
import com.kompi.app.peers.PeerHostStatus
import com.kompi.app.peers.PeerReturnTarget
import com.composables.icons.lucide.R as LucideR
import com.kompi.app.chat.ChatPromptField
import com.kompi.app.live.LiveConversationController
import com.kompi.app.live.LiveComposerSurface
import com.kompi.app.live.LiveTalkScreen
import com.kompi.app.live.LiveConversationState
import com.kompi.app.live.AudioLevel
import com.kompi.app.live.MonoWaveVisualizer
import com.kompi.app.audio.AudioWaveform
import com.kompi.app.assistant.AssistantCoordinator
import com.kompi.app.assistant.AssistantSessionScreen
import com.kompi.app.assistant.AssistantSettings
import com.kompi.app.audio.live.LocalLiveSpeechInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    private lateinit var attachmentShareViewModel: AttachmentShareViewModel
    private var launcherRoute by mutableStateOf<Pair<String, Long>?>(null)

    private fun handleLauncherIntent(incoming: android.content.Intent?) {
        val route = incoming?.getStringExtra("com.kompi.app.shortcut") ?: return
        if (route !in setOf("live", "chat", "computers", "settings")) return
        launcherRoute = route to android.os.SystemClock.elapsedRealtimeNanos()
        // Consume routing, not drafts or conversation state. Recreation must not replay it.
        incoming.removeExtra("com.kompi.app.shortcut")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        attachmentShareViewModel = ViewModelProvider(this)[AttachmentShareViewModel::class.java]
        enableEdgeToEdge()
        val secureStore = SecureStore(this)
        // Provision the wrapped local identity on first launch; raw seed material is never persisted.
        secureStore.identitySeed()
        handleShareIntent(intent, isNewIntent = false, isRecreation = savedInstanceState != null)
        handleLauncherIntent(intent)
        val replacement = AssistantCoordinator.beginUiReplacement()
        lifecycleScope.launch {
            // CLEAR_TASK/relaunch can create this Activity before the old Compose view is disposed.
            // Release its microphone, speech and transport before a replacement can register.
            android.util.Log.i("KompiRuntime", "phase=ui_handoff_wait")
            withContext(NonCancellable) { replacement.releasePrevious() }
            android.util.Log.i("KompiRuntime", "phase=ui_handoff_released")
            coroutineContext.ensureActive()
            if (!AssistantCoordinator.isCurrent(replacement)) {
                finish()
                return@launch
            }
            setContent {
                var theme by remember { mutableStateOf(runCatching { AppTheme.valueOf(getSharedPreferences("settings", 0).getString("theme", "System") ?: "System") }.getOrDefault(AppTheme.System)) }
                val assistantMode by AssistantCoordinator.mode.collectAsState()
                val assistantFailure by AssistantCoordinator.failure.collectAsState()
                val assistantCleanupPending by com.kompi.app.assistant.AssistantServiceRetirementBarrier.cleanupPending.collectAsState()
                androidx.compose.runtime.DisposableEffect(assistantMode.assistant) {
                    if (Build.VERSION.SDK_INT >= 27) {
                        setShowWhenLocked(assistantMode.assistant)
                        setTurnScreenOn(assistantMode.assistant)
                    } else {
                        @Suppress("DEPRECATION")
                        if (assistantMode.assistant) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
                        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
                    }
                    onDispose { }
                }
                KompiTheme(theme) {
                    androidx.compose.runtime.key(assistantMode) {
                        if (assistantMode.assistant) AssistantSessionScreen()
                        else if (assistantCleanupPending || com.kompi.app.assistant.AssistantServiceRetirementBarrier.isRetiring()) Column(
                            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Closing voice session…", style = KompiUi.typography.titleMedium)
                            Text("Chat opens when voice resources have released. Your active reply is not cancelled.", style = KompiUi.typography.bodyMedium)
                        }
                        else KompiApp(secureStore, theme, attachmentShareViewModel.receipt, attachmentShareViewModel.importing, launcherRoute = launcherRoute, onLauncherRouteConsumed = { launcherRoute = null }, onTheme = { theme = it; getSharedPreferences("settings", 0).edit().putString("theme", it.name).apply() })
                    }
                    assistantFailure?.let { message ->
                        AlertDialog(
                            onDismissRequest = { AssistantCoordinator.failure.value = null },
                            title = { Text("Assistant could not start") },
                            text = { Text(message) },
                            confirmButton = { TextButton(onClick = { AssistantCoordinator.failure.value = null }) { Text("OK") } },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        com.kompi.app.assistant.ReplyNotifications.visibilityChanged(this, true)
    }

    override fun onStop() {
        com.kompi.app.assistant.ReplyNotifications.visibilityChanged(this, false)
        super.onStop()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent, isNewIntent = true, isRecreation = false)
        handleLauncherIntent(intent)
    }

    private fun handleShareIntent(incoming: android.content.Intent?, isNewIntent: Boolean, isRecreation: Boolean) {
        val share = AttachmentShareIntake.parse(incoming) as? AttachmentSharePayload.Share ?: return
        if (isNewIntent) attachmentShareViewModel.receiveOnNewIntent(share)
        else attachmentShareViewModel.receiveOnCreate(share, isRecreation)
    }
}

internal enum class AppPage(val label: String) { Sidebar("Menu"), Chat("Chat"), Live("Live") }
private enum class CaptureMode { Dictation, Live }

private enum class Destination(val label: String) { Conversation("Chat"), Peers("Computers"), PeerDetails("Computer settings"), Settings("Settings") }

private fun peerStatus(selected: Boolean, connectionState: String): PeerHostStatus = when {
    !selected -> PeerHostStatus.NotSelected
    connectionState == "Live" -> PeerHostStatus.Live
    connectionState == "Synchronizing" -> PeerHostStatus.Synchronizing
    connectionState == "Connecting" -> PeerHostStatus.Connecting
    else -> PeerHostStatus.Disconnected
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KompiApp(
    store: SecureStore,
    theme: AppTheme,
    attachmentShareReceipt: StateFlow<AttachmentShareReceipt?>,
    attachmentShareImporting: StateFlow<Boolean>,
    launcherRoute: Pair<String, Long>?,
    onLauncherRouteConsumed: () -> Unit,
    onTheme: (AppTheme) -> Unit,
) {
    var destination by remember { mutableStateOf(Destination.Conversation) }
    var peerDetailsRequest by remember { mutableStateOf<PeerDetailsRequest?>(null) }
    var peerRouteSequence by remember { mutableLongStateOf(0L) }
    var pairing by remember { mutableStateOf(store.currentPairing()) }
    val trustedMachines = remember { mutableStateListOf<SecureStore.TrustedMachine>().apply { addAll(store.trustedMachines()) } }
    // A scanned code is transport input, not the selected/trusted computer.
    val activePairing = pairing?.takeIf { it.confirmed }
        ?: trustedMachines.firstOrNull { it.machineId == store.selectedMachineId() }?.pairing
    var pairingAttempt by remember { mutableIntStateOf(0) }
    var pairingFailure by remember(pairing, pairingAttempt) { mutableStateOf<String?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    var showPairingToken by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(initialPage = AppPage.Chat.ordinal) { AppPage.entries.size }
    val context = LocalContext.current
    val settings = remember(context) { context.getSharedPreferences("settings", 0) }
    val selectedMachineKey = activePairing?.machineId.orEmpty()
    val drafts = remember(context) { ConversationDrafts(context) }
    var focusedChat by remember(selectedMachineKey) {
        mutableStateOf(selectedMachineKey.takeIf { it.isNotEmpty() && store.supportsConcurrentChats(it) }?.let(drafts::selected))
    }
    var archivePins by remember(store, selectedMachineKey) {
        mutableStateOf(if (selectedMachineKey.isEmpty()) emptySet() else store.archivePins(selectedMachineKey))
    }
    var historyActionError by remember(selectedMachineKey) { mutableStateOf<String?>(null) }
    var deletingId by remember(selectedMachineKey) { mutableStateOf<String?>(null) }
    var deletedIds by remember(selectedMachineKey) { mutableStateOf(emptySet<String>()) }
    var historyRefresh by remember(selectedMachineKey) { mutableIntStateOf(0) }
    var hostHistory by remember(selectedMachineKey) { mutableStateOf<List<PhoneConversation>?>(null) }
    var historyFailed by remember(selectedMachineKey) { mutableStateOf(false) }
    var historyComplete by remember(selectedMachineKey) { mutableStateOf(false) }
    val localDatabase = remember(context) { LocalDatabase.open(context) }
    val localRepository = remember(localDatabase, store) { LocalRepository(localDatabase, store) }
    val localViewModel: ConversationViewModel = viewModel(
        key = "conversation-$selectedMachineKey",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = ConversationViewModel(localRepository, selectedMachineKey) as T
        },
    )
    val localUiState by localViewModel.state.collectAsStateWithLifecycle()
    val transport = remember(localRepository) { IrohTransport(context, store, localRepository) }
    val draftScope = focusedChat?.let(drafts::scope)
    var sessionRefreshMessage by remember(selectedMachineKey, focusedChat?.id) { mutableStateOf("") }
    var ttsEnabled by remember { mutableStateOf(settings.getBoolean("ttsEnabled", true)) }
    var chatReadAloudEnabled by remember { mutableStateOf(settings.getBoolean("chatReadAloudEnabled", true)) }
    val currentChatReadAloudEnabled by rememberUpdatedState(chatReadAloudEnabled)
    var thinkingSpeechEnabled by remember { mutableStateOf(settings.getBoolean("thinkingSpeechEnabled", true)) }
    var thinkingSpeechPrefix by remember { mutableStateOf(settings.getString("thinkingSpeechPrefix", "One second, I'm").orEmpty()) }
    val currentThinkingSpeechEnabled by rememberUpdatedState(thinkingSpeechEnabled)
    val currentThinkingSpeechPrefix by rememberUpdatedState(thinkingSpeechPrefix)
    val currentDestination by rememberUpdatedState(destination)
    val displayedId = focusedChat?.id ?: localUiState.local.activeConversationId
    val currentActiveConversationId by rememberUpdatedState(displayedId)
    val displayedSession = localUiState.local.sessions.firstOrNull { it.conversationId == displayedId }
    val activeSession = displayedSession?.takeIf { it.state == ConversationState.Active.wire }
    val resetDeadline = remember(activeSession?.conversationId, activeSession?.expiresAt) {
        activeSession?.let { SessionResetDeadline(it.conversationId, java.time.Instant.parse(it.expiresAt)) }
    }
    val tts = remember(context) {
        KokoroTtsEngine(context).also {
            it.restoreVoicePreference(KokoroVoiceId.fromStored(settings.getString("kokoroVoiceId", null)))
            it.setEnabled(ttsEnabled)
        }
    }
    val kokoroState by tts.state.collectAsState()
    val availableKokoroVoices by tts.availableVoices.collectAsState()
    val selectedKokoroVoice by tts.selectedVoice.collectAsState()
    val speechRequestStatuses by tts.requestStatuses.collectAsState()
    var previewRequestId by remember { mutableStateOf<String?>(null) }
    var manualReadRequestId by remember { mutableStateOf<String?>(null) }
    var manualReadMessageKey by remember { mutableStateOf<String?>(null) }
    var manualReadGeneration by remember { mutableIntStateOf(0) }
    val whistleInstaller = remember { WhistleModelInstaller(java.io.File(context.filesDir, "models/whistle")) }
    var whistleModel by remember { mutableStateOf<InstalledWhistleModel?>(null) }
    var whistleModelChecked by remember { mutableStateOf(false) }
    var speechInputEngine by remember { mutableStateOf(SpeechInputEngine.read(settings)) }
    val parakeetInstaller = remember { ParakeetModelInstaller(java.io.File(context.filesDir, "models/parakeet")) }
    var parakeetState by remember { mutableStateOf<ParakeetInstallState>(ParakeetInstallState.Checking) }
    var parakeetDownloadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val selectedSpeechModel = remember(speechInputEngine, whistleModel, parakeetState) {
        when (speechInputEngine) {
            SpeechInputEngine.Whistle -> whistleModel?.let { SpeechRecognitionModel.Whistle(it) }
            SpeechInputEngine.Parakeet -> (parakeetState as? ParakeetInstallState.Ready)?.model?.let { SpeechRecognitionModel.Parakeet(it) }
        }
    }
    LaunchedEffect(parakeetInstaller) {
        val installed = withContext(Dispatchers.IO) { parakeetInstaller.installed() }
        parakeetState = installed?.let { ParakeetInstallState.Ready(it) } ?: ParakeetInstallState.Missing
    }
    var whistleInstallMessage by remember { mutableStateOf("") }
    var whistleDownloading by remember { mutableStateOf(false) }
    var whistleLanguage by remember { mutableStateOf(settings.getString("whistleLanguage", "auto") ?: "auto") }
    var whistleVocabulary by remember {
        mutableStateOf(WhistleVocabulary.parse(settings.getString("whistleVocabulary", "") ?: "").getOrThrow())
    }
    val connectionState by transport.state.collectAsState()
    LaunchedEffect(transport, pairing, connectionState) {
        localViewModel.onSyncPhase(when {
            pairing?.confirmed != true || connectionState == "Disconnected" -> com.kompi.app.data.SyncPhase.Disconnected
            connectionState == "Live" -> com.kompi.app.data.SyncPhase.Live
            connectionState == "Synchronizing" -> com.kompi.app.data.SyncPhase.Synchronizing
            else -> com.kompi.app.data.SyncPhase.Connecting
        })
    }
    val lastConnectionError by transport.lastError.collectAsState()
    val pendingPiDialogs by transport.piDialogs.collectAsState()
    val pendingAndroidTools by transport.androidToolRequests.collectAsState()
    val piArchiveSupported by transport.piArchiveSupported.collectAsState()
    val hostHistorySupported by transport.piHostHistorySupported.collectAsState()
    val concurrentSupported by transport.concurrentConversationsSupported.collectAsState()
    val artifactDownloadsSupported by transport.artifactDownloadsSupported.collectAsState()
    val sidebarOpen = pagerState.currentPage == AppPage.Sidebar.ordinal && destination == Destination.Conversation
    LaunchedEffect(selectedMachineKey, focusedChat?.id) { transport.focusConversation(focusedChat) }
    LaunchedEffect(selectedMachineKey, connectionState, concurrentSupported) {
        if (selectedMachineKey.isEmpty() || connectionState != "Live") return@LaunchedEffect
        if (!concurrentSupported) {
            focusedChat = null
            transport.focusConversation(null)
        } else if (focusedChat == null) {
            val current = drafts.selected(selectedMachineKey)
                ?: localRepository.activeConversationId(selectedMachineKey)?.let { ConversationTarget.saved(selectedMachineKey, it) }
                ?: ConversationTarget.new(selectedMachineKey)
            val scope = drafts.scope(current)
            drafts.migrateLegacyDraft(scope)
            withContext(Dispatchers.IO) { AttachmentDraftStore(context).claimUnscoped(scope) }
            if (focusedChat == null) {
                drafts.select(current)
                transport.focusConversation(current)
                focusedChat = current
            }
        }
    }
    PromoteRecordedChat(focusedChat, displayedSession?.conversationId) { persisted ->
        drafts.select(persisted)
        transport.focusConversation(persisted)
        focusedChat = persisted
    }
    LaunchedEffect(selectedMachineKey, sidebarOpen, connectionState, hostHistorySupported, historyRefresh) {
        if (!sidebarOpen || connectionState != "Live" || !hostHistorySupported) return@LaunchedEffect
        historyFailed = false
        val pages = linkedMapOf<String, PhoneConversation>()
        val cursors = hashSetOf<String>()
        var after: String? = null
        val initial = hostHistory == null
        try {
            do {
                val page = transport.loadHistoryPage(selectedMachineKey, after)
                page.sessions.forEach { pages[it.conversationId] = it }
                after = page.nextCursor
                check(after == null || cursors.add(after)) { "Session history repeated a cursor" }
                if (initial && hostHistory == null) hostHistory = pages.values.toList()
            } while (after != null)
            hostHistory = pages.values.toList()
            historyComplete = true
        } catch (timedOut: kotlinx.coroutines.TimeoutCancellationException) { historyFailed = true }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { historyFailed = true }
    }
    val piDialogError by transport.piDialogError.collectAsState()
    val phoneConversationsSupported by transport.phoneConversationsSupported.collectAsState()
    val attachmentsSupported by transport.attachmentsSupported.collectAsState()
    val modelCatalog by transport.modelCatalog.collectAsState()
    val chatConfiguration by transport.chatConfiguration.collectAsState()
    val confirmedConfiguration by transport.confirmedConfiguration.collectAsState()
    val modelsLoading by transport.modelsLoading.collectAsState()
    val modelError by transport.modelError.collectAsState()
    val transportIdleTimeoutError by transport.idleTimeoutError.collectAsState()
    val effectiveIdleTimeoutSeconds = localUiState.local.settings?.effectiveIdleTimeoutSeconds?.toInt()
    val idleTimeoutPending = localUiState.local.settings?.pendingIdleTimeoutSeconds != null
    val idleTimeoutError = transportIdleTimeoutError ?: localUiState.error
    val uploadRetentionSeconds = localUiState.local.settings?.pendingUploadRetentionSeconds?.toInt()
        ?: localUiState.local.settings?.effectiveUploadRetentionSeconds?.toInt()
        ?: com.kompi.app.protocol.generated.DEFAULT_UPLOAD_RETENTION_SECONDS
    val effectiveUploadRetentionSeconds = localUiState.local.settings?.effectiveUploadRetentionSeconds?.toInt()
    val uploadRetentionPending = localUiState.local.settings?.pendingUploadRetentionSeconds != null
    val selectedTrustedMachineId = activePairing?.machineId
    val selectedTrustedMachine = trustedMachines.firstOrNull { it.machineId == selectedTrustedMachineId }
    val selectedIdleTimeoutSeconds = localUiState.local.settings?.pendingIdleTimeoutSeconds?.toInt()
        ?: localUiState.local.settings?.effectiveIdleTimeoutSeconds?.toInt()
        ?: SecureStore.DEFAULT_IDLE_TIMEOUT_SECONDS
    val requestTargets = remember(localUiState.local.requests) {
        localUiState.local.requests.associate { it.requestId to it.conversationId }
    }
    val latestRequestTargets by rememberUpdatedState(requestTargets)
    val scopedOutbox = if (focusedChat == null) localUiState.local.outbox else
        localUiState.local.outbox.filter { requestTargets[it.requestId] == displayedId }
    val displayedRequests = if (focusedChat == null) localUiState.local.requests else
        localUiState.local.requests.filter { it.conversationId == displayedId }
    val unsentPrompts = scopedOutbox.filter { it.hostId == selectedMachineKey && it.state !in setOf("accepted", "dispatching") }.map { row ->
        SecureStore.PendingPrompt(
            row.requestId,
            row.text,
            Delivery.parse(row.delivery),
            accepted = false,
            machineId = row.hostId,
            attachmentIds = row.attachmentIds,
            waitingForConversation = row.state == "waiting-for-conversation",
        )
    }
    val transientPromptIds = remember { mutableStateSetOf<String>() }
    val unacceptedPromptIds = remember(localUiState.local.outbox, selectedMachineKey) {
        localUiState.local.outbox.filter { it.hostId == selectedMachineKey && it.state != "accepted" }.map { it.requestId }.toSet()
    }
    LaunchedEffect(unacceptedPromptIds) {
        transientPromptIds.retainAll(unacceptedPromptIds)
    }
    val receivedPi by transport.piSession.view.collectAsState()
    val livePi = receivedPi.takeIf { it.hostId == selectedMachineKey } ?: com.kompi.app.transport.PiSessionStore.View()
    val catalogSession = remember(hostHistory, displayedId) { hostHistory?.firstOrNull { it.conversationId == displayedId } }
    val remoteState = displayedSession?.state ?: catalogSession?.state?.wire
    val finishedRequests = remember(displayedRequests, selectedMachineKey) {
        displayedRequests.filter { it.hostId == selectedMachineKey && it.outcome == "completed" }.map { it.requestId }.toSet()
    }
    val historyPane = rememberChatHistory(focusedChat, livePi, transport.piSession.view, remoteState, connectionState,
        concurrentSupported, piArchiveSupported, finishedRequests, transport::requestConversationSnapshot,
        { transport.loadArchivedChat(it.hostId, it.id) },
        displayedRequests.filter { it.hostId == selectedMachineKey && it.outcome in setOf("accepted", "running") }.map { it.requestId }.toSet())
    val piSession = historyPane.view
    val deliveryNotices by localViewModel.notices.collectAsState()
    val pendingPiPrompts = remember(scopedOutbox, localRepository, selectedMachineKey) {
        scopedOutbox.filter { it.hostId == selectedMachineKey }.map { it.requestId to it.text }
    }
    val heldPromptIds = unsentPrompts.filter { it.waitingForConversation }.mapTo(mutableSetOf()) { it.id }
    val observedWrites by transport.observedWrites.collectAsState()
    val attachmentDispatchStages by transport.attachmentDispatchStages.collectAsState()
    val pendingFileIds = remember(localUiState.local.outbox, selectedMachineKey) {
        localUiState.local.outbox.filter { it.hostId == selectedMachineKey }.associate { row ->
            row.requestId to row.attachmentIds
        }
    }
    val pendingAttachments by produceState<Map<String, List<MessageAttachment>>>(emptyMap(), pendingFileIds) {
        val cached = withContext(Dispatchers.IO) { AttachmentDraftStore(context).load().associateBy { it.uploadId } }
        value = pendingFileIds.mapValues { (_, ids) -> ids.mapNotNull { cached[it]?.let(MessageAttachment::Local) } }
    }
    val localEchoes = remember(selectedMachineKey, focusedChat?.id) { LocalMessageEchoes() }
    val transcriptProjection = remember(piSession, historyPane.snapshotCompletions, pendingPiPrompts, displayedRequests, deliveryNotices, pendingAttachments, pendingFileIds, attachmentDispatchStages, heldPromptIds, scopedOutbox, observedWrites) {
        val echoes = localEchoes.reconcile(piSession.conversationId, pendingPiPrompts, displayedRequests)
        buildTranscriptProjection(piSession, echoes.filterNot { it.first in heldPromptIds }, displayedRequests.filterNot { it.requestId in heldPromptIds }, deliveryNotices,
            pendingAttachments, attachmentDispatchStages, scopedOutbox.associate { it.requestId to it.state }, observedWrites.keys, historyPane.snapshotCompletions)
    }
    val transcript = transcriptProjection.all
    val activeTranscript = transcriptProjection.active
    val responseConversationIds = remember { mutableStateMapOf<String, String>() }
    val responseTokens = remember { mutableStateMapOf<String, Long>() }
    val currentTranscript by rememberUpdatedState(transcript)
    val liveControllerRef = remember { mutableStateOf<LiveConversationController?>(null) }
    val captureHandoffRef = remember { mutableStateOf<(suspend (closeLiveController: Boolean) -> Unit)?>(null) }
    var speechQueueMessage by remember { mutableStateOf("") }
    val previewPlaybackMessage by remember(tts, previewRequestId) {
        val requestId = previewRequestId
        // Meter frames must not recompose the entire app or reallocate unrelated UI state.
        tts.playbackPhase.map { phase ->
            when (phase) {
                is KokoroPlaybackPhase.Preparing -> if (phase.requestId == requestId) "Preparing voice preview…" else null
                is KokoroPlaybackPhase.Playing -> if (phase.requestId == requestId) "Playing voice preview…" else null
                is KokoroPlaybackPhase.Draining -> if (phase.requestId == requestId) "Finishing voice preview…" else null
                KokoroPlaybackPhase.Idle -> null
            }
        }.distinctUntilChanged()
    }.collectAsState(initial = null)
    val previewSpeechMessage = previewRequestId?.let { requestId ->
        previewPlaybackMessage ?: speechRequestStatuses[requestId]?.let { status ->
            when (status.state) {
                KokoroSpeechStatus.Queued, KokoroSpeechStatus.Playing -> "Preparing voice preview…"
                KokoroSpeechStatus.Completed -> "Voice preview finished"
                KokoroSpeechStatus.Cancelled -> "Voice preview stopped"
                KokoroSpeechStatus.Failed -> "Voice preview failed · ${status.failureMessage ?: "try again"}"
            }
        }
    } ?: speechQueueMessage
    val currentKokoroState by rememberUpdatedState(kokoroState)
    val currentTtsEnabled by rememberUpdatedState(ttsEnabled)
    val voiceCanRecover = kokoroState is KokoroTtsState.Failed && availableKokoroVoices.isNotEmpty()
    val manualReadAloudReady = ttsEnabled && (kokoroState is KokoroTtsState.Ready || voiceCanRecover)
    val activitySpeech = remember(tts) {
        com.kompi.app.stream.ActivitySpeech(
            speak = { id, phrase ->
                tts.enqueue(id, 0, phrase).also { accepted ->
                    if (accepted) tts.finishRequest(id)
                    android.util.Log.i("KompiActivitySpeech", "Filler enqueue: id=$id accepted=$accepted")
                }
            },
            stop = { id ->
                tts.stop(id)
                android.util.Log.i("KompiActivitySpeech", "Filler interrupted: id=$id")
            },
        )
    }
    LaunchedEffect(pagerState.settledPage) {
        if (pagerState.settledPage == AppPage.Sidebar.ordinal) activitySpeech.invalidate()
    }
    val coroutineScope = rememberCoroutineScope()
    val replySpeech = remember(tts, coroutineScope) {
        com.kompi.app.stream.ReplySpeechQueue(
            scope = coroutineScope,
            enqueue = { requestId, sequence, phrase ->
                if (tts.state.value == KokoroTtsState.Initializing) {
                    kotlinx.coroutines.withTimeoutOrNull(30_000) {
                        tts.state.first { it != KokoroTtsState.Initializing }
                    }
                }
                tts.enqueueWhenAvailable(requestId, sequence, phrase)
            },
            finish = tts::finishRequest,
            stop = tts::stop,
            failed = { requestId ->
                speechQueueMessage = "Spoken reply could not finish. The full reply remains in the transcript."
                liveControllerRef.value?.updateActivity(requestId, "Spoken reply could not finish. The full text remains available.")
                android.util.Log.w("KompiReplySpeech", "phase=enqueue_failed request=$requestId")
            },
        )
    }
    val responseStream = remember(tts, activitySpeech, replySpeech) {
        AssistantSpeechStream(
            speak = { requestId, sequence, phrase ->
                val autoRead = pagerState.currentPage == AppPage.Live.ordinal || currentChatReadAloudEnabled
                val canQueue = currentKokoroState is KokoroTtsState.Ready || currentKokoroState == KokoroTtsState.Initializing
                val enqueued = autoRead && currentTtsEnabled && canQueue && replySpeech.offer(requestId, sequence, phrase)
                speechQueueMessage = when {
                    enqueued && currentKokoroState == KokoroTtsState.Initializing -> "Preparing reply voice…"
                    !autoRead || enqueued -> ""
                    !currentTtsEnabled -> "Kokoro speech is muted; the complete reply is still shown."
                    currentKokoroState is KokoroTtsState.Ready -> "Spoken reply could not finish; the complete reply is still shown."
                    else -> "Kokoro speech is unavailable until its models are downloaded and ready."
                }
                enqueued
            },
            stop = { requestId ->
                if (requestId == null) activitySpeech.invalidate() else activitySpeech.retire(requestId)
                replySpeech.cancel(requestId)
            },
            onTextUpdate = { requestId, _, text ->
                liveControllerRef.value?.updateAssistantText(requestId, text)
            },
            onDiagnostic = { id, signal ->
                android.util.Log.i("KompiSpeechStream", "request=$id signal=${signal.name}")
                if (signal == com.kompi.app.stream.SpeechStreamSignal.UnspeakableToken) {
                    speechQueueMessage = "This reply contains an item too long to read aloud. The full text is still available."
                }
            },
            speechLocale = { tts.selectedVoice.value?.localeTag?.let(java.util.Locale::forLanguageTag) ?: java.util.Locale.getDefault() },
            hasQueuedAudio = { id -> replySpeech.hasPendingSpeech(id) || tts.hasPendingSpeech(id) },
        )
    }
    val appLifecycleOwner = LocalLifecycleOwner.current
    fun activitySpeechGate(requestId: String): ActivitySpeechGate = when {
        !currentThinkingSpeechEnabled || !currentTtsEnabled || responseStream.isOutputMuted() ||
            (pagerState.currentPage != AppPage.Live.ordinal && !currentChatReadAloudEnabled) -> ActivitySpeechGate.Muted
        currentDestination != Destination.Conversation || pagerState.currentPage == AppPage.Sidebar.ordinal ||
            !appLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) -> ActivitySpeechGate.NotForeground
        responseConversationIds[requestId] != currentActiveConversationId -> ActivitySpeechGate.OtherConversation
        currentKokoroState !is KokoroTtsState.Ready -> ActivitySpeechGate.VoiceNotReady
        replySpeech.hasPendingSpeech() -> ActivitySpeechGate.ReplyAudioPending
        tts.requestStatuses.value.keys.any { id -> !id.startsWith("activity-") && tts.hasPendingSpeech(id) } -> ActivitySpeechGate.OtherAudioPending
        else -> ActivitySpeechGate.Open
    }
    LaunchedEffect(responseStream) {
        while (isActive) {
            kotlinx.coroutines.delay(100)
            responseStream.drainBufferedSpeech()
        }
    }
    LaunchedEffect(tts) { withContext(Dispatchers.IO) { tts.initialize() } }
    LaunchedEffect(tts, liveControllerRef) {
        launch {
            tts.playbackPhase.collect { phase -> liveControllerRef.value?.onAssistantPlayback(phase, activitySpeech.playbackOwner(phase)) }
        }
        launch {
            tts.requestStatuses.collect { statuses ->
                statuses.values.filter { it.state in setOf(KokoroSpeechStatus.Completed, KokoroSpeechStatus.Cancelled, KokoroSpeechStatus.Failed) }
                    .forEach {
                        activitySpeech.onPlaybackDrained(it.requestId) { id ->
                            if (it.state == KokoroSpeechStatus.Completed) activitySpeechGate(id) else ActivitySpeechGate.VoiceNotReady
                        }
                        if (it.state == KokoroSpeechStatus.Completed) liveControllerRef.value?.onAssistantPlaybackDrained(it.requestId)
                    }
            }
        }
    }
    LaunchedEffect(whistleInstaller) {
        if (!SpeechInputEngine.Whistle.supportedOnDevice()) return@LaunchedEffect
        val verified = withContext(Dispatchers.IO) {
            whistleInstaller.installed()?.takeIf { WhistleModelInstaller.isVerifiedModel(it.file) }
        }
        whistleModel = verified
        whistleModelChecked = true
        if (verified == null && java.io.File(context.filesDir, "models/whistle/whistle.cact").exists()) {
            whistleInstallMessage = "Stored model checksum is invalid; download a fresh copy."
        }
    }
    DisposableEffect(transport, tts, responseStream) {
        val owner = Any()
        AssistantCoordinator.registerUi(owner) {
            android.util.Log.i("KompiRuntime", "phase=capture_handoff_begin")
            try { captureHandoffRef.value?.invoke(true) }
            finally {
                android.util.Log.i("KompiRuntime", "phase=capture_handoff_finished")
                responseStream.invalidate()
                tts.close()
                transport.close()
                android.util.Log.i("KompiRuntime", "phase=audio_handoff_begin")
                try { tts.closeAndJoin() }
                finally {
                    android.util.Log.i("KompiRuntime", "phase=transport_handoff_begin")
                    transport.closeAndJoin()
                    android.util.Log.i("KompiRuntime", "phase=transport_handoff_finished")
                }
            }
        }
        onDispose { AssistantCoordinator.unregisterUi(owner) }
    }
    DisposableEffect(transport) { onDispose { transport.close() } }
    DisposableEffect(tts) { onDispose { tts.close() } }
    DisposableEffect(responseStream, appLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) responseStream.invalidate() }
        appLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { appLifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(connectionState, pairing?.machineId) {
        if (connectionState == "Live") {
            trustedMachines.clear()
            trustedMachines.addAll(store.trustedMachines())
        }
    }
    val confirmedPairing by transport.confirmedPairing.collectAsState()
    LaunchedEffect(confirmedPairing) {
        val confirmed = confirmedPairing ?: return@LaunchedEffect
        if (pairing?.endpointTicket == confirmed.endpointTicket && pairing?.secret == confirmed.secret) {
            trustedMachines.clear()
            trustedMachines.addAll(store.trustedMachines())
            pairing = confirmed
        }
    }
    LaunchedEffect(pairing, pairingAttempt) {
        val attempt = pairing
        if (attempt == null) transport.stop() else transport.reconnect(attempt)
        if (attempt != null && !attempt.confirmed) {
            kotlinx.coroutines.delay(30_000)
            if (transport.confirmedPairing.value?.let { it.endpointTicket == attempt.endpointTicket && it.secret == attempt.secret } != true) {
                pairingFailure = transport.lastError.value ?: "The computer did not confirm pairing within 30 seconds. Check that its daemon is reachable, then try again."
                transport.stop()
            }
        }
    }
    LaunchedEffect(transport, tts, selectedMachineKey, responseStream) {
        transport.events.collect { event -> when (event) {
            is IrohTransport.Event.ResponseDelta -> {
                if (focusedChat != null && event.conversationId != currentActiveConversationId) return@collect
                activitySpeech.onReplyText(event.id)
                com.kompi.app.diagnostics.LocalDiagnostics.recordStreamChunk(event.id)
                responseConversationIds[event.id] = event.conversationId
                val token = responseTokens[event.id] ?: responseStream.generationToken().also { responseTokens[event.id] = it }
                responseStream.accept(event.id, event.conversationId, event.sequence.toLong(), event.text, token)
            }
            is IrohTransport.Event.Reply -> {
                if (event.conversationId != null && event.conversationId != currentActiveConversationId) return@collect
                if (event.state == ResultState.Completed) activitySpeech.complete(event.id) else activitySpeech.retire(event.id)
                com.kompi.app.diagnostics.LocalDiagnostics.finishStream(event.id, "reply")
                localViewModel.clearDeliveryNotice("${event.id}:error")
                val canonicalText = event.text
                liveControllerRef.value?.updateAssistantText(event.id, canonicalText)
                val targetSessionId = event.sessionId
                val conversationId = event.conversationId ?: responseConversationIds[event.id]
                if (targetSessionId != null) {
                    if (conversationId != null) {
                        responseConversationIds[event.id] = conversationId
                        val token = responseTokens[event.id] ?: responseStream.generationToken()
                        if (responseTokens[event.id] == null) responseTokens[event.id] = token
                        if (event.state == ResultState.Completed) responseStream.finish(event.id, conversationId, canonicalText, token)
                        else responseStream.cancel(event.id)
                    } else if (event.state != ResultState.Completed) {
                        responseStream.cancel(event.id)
                    }
                    if (event.state == ResultState.Completed) replySpeech.complete(event.id)
                    else {
                        tts.stop(event.id)
                        tts.finishRequest(event.id)
                    }
                    if (event.state != ResultState.Completed) {
                        liveControllerRef.value?.onAssistantPlaybackDrained(event.id)
                    }
                }
            }
            is IrohTransport.Event.Delivery -> if (event.state == AckState.Accepted || event.state == AckState.Queued) {
                localViewModel.clearDeliveryNotice("${event.id}:error")
            }
            is IrohTransport.Event.Failure -> {
                activitySpeech.retire(event.id)
                if (event.id.isNotEmpty()) com.kompi.app.diagnostics.LocalDiagnostics.finishStream(event.id, "failure")
                if (event.id.isNotEmpty()) {
                    responseStream.cancel(event.id)
                    tts.stop(event.id)
                    tts.finishRequest(event.id)
                }
                val failureConversationId = event.conversationId ?: responseConversationIds[event.id] ?: latestRequestTargets[event.id]?.takeIf { it.isNotEmpty() }
                val failureText = when {
                    event.code == "response-too-large" -> "Reply too large to display."
                    event.code == "attachment-permanent" -> event.message
                    event.uncertain && event.retryable -> "Delivery not confirmed."
                    event.retryable -> "Waiting for the computer. Retrying automatically."
                    event.uncertain -> "Reply interrupted."
                    else -> "Couldn't complete this request."
                }
                if (event.id.isNotEmpty() && currentTranscript.none { it.key == "${event.id}:error" }) {
                    localViewModel.showDeliveryNotice("${event.id}:error", failureText, failureConversationId)
                }
                if (failureConversationId == currentActiveConversationId &&
                    (event.code.startsWith("conversation-") || event.id.startsWith("conversation-close"))) sessionRefreshMessage = event.message
            }
            is IrohTransport.Event.Progress -> {
                val sessionId = event.sessionId
                val conversationId = event.conversationId
                if (sessionId != null && conversationId != null && conversationId == currentActiveConversationId) {
                    responseConversationIds[event.id] = conversationId
                    com.kompi.app.diagnostics.SpeechAudit.record(event.id, stage = "activity_received", detail = "kind=${when { event.activity == "Thinking" -> "thinking_start"; event.activity.startsWith("Thinking: ") -> "thinking_heading"; event.activity == "Using tools" -> "tools_cue"; else -> "tool" }}")
                    if (event.activity.startsWith("Thinking: ")) liveControllerRef.value?.updateThinkingTitle(event.id, event.activity.removePrefix("Thinking: "))
                    else if (event.activity == "Thinking" || event.activity == "Using tools" || event.activity.startsWith("Tool: ")) liveControllerRef.value?.updateActivity(event.id, event.activity)
                    activitySpeech.offer(
                        event.id, event.activity, currentThinkingSpeechPrefix,
                        allowTools = pagerState.currentPage == AppPage.Live.ordinal,
                        gate = activitySpeechGate(event.id),
                    )
                }
            }
            is IrohTransport.Event.ConversationChanged -> {
                localViewModel.upsertConversation(event.metadata)
            }
            // Upload coroutines surface progress/errors through their correlated callback/exception;
            // these transport events are retained for diagnostics and other observers.
            is IrohTransport.Event.AttachmentProgress -> Unit
            is IrohTransport.Event.AttachmentFailure -> Unit
            is IrohTransport.Event.AttachmentAccepted -> Unit
            is IrohTransport.Event.DurableSyncEvent -> {
                val eventPayload = event.event.event
                if (eventPayload is com.kompi.app.protocol.generated.DurableEvent.ResponseCompleted) {
                    // Pi's settled frame, not the faster delivery ledger, ends live title eligibility.
                    if (eventPayload.payload.outcome != "completed") activitySpeech.retire(eventPayload.payload.requestId)
                    com.kompi.app.diagnostics.LocalDiagnostics.finishStream(
                        eventPayload.payload.requestId,
                        "completed",
                    )
                }
            }
        } }
    }
    suspend fun navigateTo(target: Destination) {
        if (target == destination) return
        var handoffFailure: Throwable? = null
        if (destination == Destination.Conversation) {
            try {
                captureHandoffRef.value?.invoke(true)
            } catch (failure: Throwable) {
                handoffFailure = failure
                // The handoff callback fences callbacks and requests immediate stops before throwing.
                // Navigation is never trapped; the process-wide lease prevents another mic start.
                liveControllerRef.value?.stopLocally(com.kompi.app.live.LiveLocalStopReason.Backgrounded)
            }
        }
        responseStream.invalidate()
        if (destination == Destination.Conversation) {
            manualReadGeneration++
            manualReadRequestId?.let(tts::stop)
            manualReadRequestId = null
            manualReadMessageKey = null
        }
        destination = target
        handoffFailure?.let { failure ->
            sessionRefreshMessage = "Microphone is stopping in the background; voice capture stays disabled until it releases: ${failure.message ?: "try again"}"
        }
    }
    fun openPeerDetails(hostId: String, returnTo: PeerReturnTarget) {
        peerRouteSequence++
        peerDetailsRequest = PeerDetailsRequest(hostId, peerRouteSequence, returnTo)
        coroutineScope.launch { navigateTo(Destination.PeerDetails) }
    }
    suspend fun returnFromPeerDetails() {
        when (peerDetailsRequest?.returnTo) {
            PeerReturnTarget.Directory -> navigateTo(Destination.Peers)
            PeerReturnTarget.Sidebar -> {
                navigateTo(Destination.Conversation)
                pagerState.scrollToPage(AppPage.Sidebar.ordinal)
            }
            PeerReturnTarget.Chat, null -> navigateTo(Destination.Conversation)
        }
    }
    fun focusChat(target: ConversationTarget) {
        if (target == focusedChat || target.hostId != store.selectedMachineId()) return
        val old = focusedChat
        if (old?.createIfMissing == true && drafts.load(drafts.scope(old)).isBlank() &&
            localUiState.local.requests.none { it.conversationId == old.id } &&
            AttachmentDraftStore(context).loadFor(drafts.scope(old)).isEmpty()) {
            drafts.forgetLocal(old.hostId, old.id)
        }
        responseStream.invalidate()
        responseTokens.clear()
        tts.stop()
        speechQueueMessage = ""
        val releaseCapture = captureHandoffRef.value
        coroutineScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { releaseCapture?.invoke(false) }
        drafts.select(target)
        transport.focusConversation(target)
        focusedChat = target
    }
    // Notification targets come from our non-exported Activity. Route by opaque ID,
    // not by searching a potentially incomplete list or activating the daemon's runtime.
    val replyTap by com.kompi.app.assistant.ReplyNoticeNavigation.target.collectAsState()
    LaunchedEffect(replyTap, selectedMachineKey, connectionState, concurrentSupported, localUiState.local.activeConversationId) {
        val tap = replyTap ?: return@LaunchedEffect
        val target = tap.target
        val host = target.hostId.value
        if (trustedMachines.none { it.machineId == host }) {
            com.kompi.app.assistant.ReplyNoticeNavigation.consume(tap)
            return@LaunchedEffect
        }
        if (selectedMachineKey != host) {
            runCatching { store.selectMachine(host) }.onSuccess {
                pairing = store.pairing()
            }.onFailure { com.kompi.app.assistant.ReplyNoticeNavigation.consume(tap) }
            return@LaunchedEffect
        }
        if (concurrentSupported || store.supportsConcurrentChats(host)) {
            focusChat(ConversationTarget.saved(host, target.conversationId))
        } else if (localUiState.local.activeConversationId != target.conversationId) {
            if (connectionState != "Live") return@LaunchedEffect
            try { transport.continueConversation(host, target.conversationId) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { sessionRefreshMessage = "Update the computer to open this notification’s session." }
        }
        navigateTo(Destination.Conversation)
        pagerState.scrollToPage(AppPage.Chat.ordinal)
        com.kompi.app.assistant.ReplyNoticeNavigation.consume(tap)
    }
    LaunchedEffect(launcherRoute) {
        val route = launcherRoute ?: return@LaunchedEffect
        when (route.first) {
            "settings" -> navigateTo(Destination.Settings)
            "computers" -> navigateTo(Destination.Peers)
            "live", "chat" -> {
                navigateTo(Destination.Conversation)
                pagerState.scrollToPage(if (route.first == "live") AppPage.Live.ordinal else AppPage.Chat.ordinal)
            }
        }
        onLauncherRouteConsumed()
    }
    val keyboardVisible = WindowInsets.isImeVisible
    BackHandler(enabled = destination == Destination.PeerDetails) {
        coroutineScope.launch { returnFromPeerDetails() }
    }
    BackHandler(enabled = destination == Destination.Conversation && pagerState.currentPage != AppPage.Chat.ordinal && !keyboardVisible && !showScanner && !showPairingToken) {
        coroutineScope.launch { pagerState.animateScrollToPage(AppPage.Chat.ordinal) }
    }
    BackHandler(enabled = destination == Destination.Peers || destination == Destination.Settings) {
        coroutineScope.launch { navigateTo(Destination.Conversation); pagerState.scrollToPage(AppPage.Sidebar.ordinal) }
    }
    BackHandler(enabled = destination == Destination.Conversation && pagerState.currentPage == AppPage.Chat.ordinal && !keyboardVisible && !showScanner && !showPairingToken) {
        val activity = context as? Activity
        if (activity != null) coroutineScope.launch {
            try { captureHandoffRef.value?.invoke(true) }
            catch (failure: Throwable) {
                android.util.Log.w("Kompi", "Capture teardown failed during Back; leaving with the mic lease retained", failure)
            } finally {
                responseStream.invalidate()
                activity.finish()
            }
        }
    }
      Box(Modifier.fillMaxSize()) {
        ChatAtmosphere(Modifier.matchParentSize())
      Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
        topBar = {
            com.kompi.app.ui.ConversationHeader(
                peerId = selectedTrustedMachineId,
                hostName = selectedTrustedMachine?.displayName ?: activePairing?.name,
                state = connectionState,
                session = resetDeadline,
                onBack = if (destination == Destination.Conversation) null else ({
                    coroutineScope.launch {
                        if (destination == Destination.PeerDetails) returnFromPeerDetails() else navigateTo(Destination.Conversation)
                    }
                    Unit
                }),
            )
        },
      ) { insets ->
        val contentModifier = Modifier.padding(insets).consumeWindowInsets(insets).imePadding()
        when (destination) {
            Destination.Conversation -> key(selectedMachineKey, focusedChat?.id) {
              val target = focusedChat // Freeze the destination before any send coroutine suspends.
              // Only a crash/interrupted submission needs a Room read. Never mount the editor
              // with the stale text and then asynchronously overwrite what the user is typing.
              val interrupted = remember { drafts.submission(draftScope) }
              val initialComposerDraft by produceState<String?>(
                  if (interrupted == null) drafts.load(draftScope) else null,
              ) {
                  if (interrupted != null) {
                      value = withContext(Dispatchers.IO) {
                          drafts.restore(draftScope) { id -> localRepository.requestState(selectedMachineKey, id) != null }
                      }
                  }
              }
              val restoredDraft = initialComposerDraft
              if (restoredDraft != null) ConversationScreen(
                modifier = contentModifier,
                pagerState = pagerState,
                sidebarContent = {
                    val busy = localUiState.local.requests.filter { it.outcome in setOf("running", "accepted", "queued") }
                        .mapTo(hashSetOf()) { it.conversationId }
                    val needsInput = pendingPiDialogs.mapTo(hashSetOf()) { it.conversationId }
                    val localIds = drafts.localIds(selectedMachineKey)
                    val canChooseSession = selectedMachineKey.isNotEmpty() &&
                        (concurrentSupported || store.supportsConcurrentChats(selectedMachineKey))
                    fun status(id: String) = when (id) {
                        in needsInput -> "Needs input"
                        in busy -> "In progress"
                        else -> null
                    }
                    fun deleteBlock(id: String, state: ConversationState): String? = when {
                        id == displayedId -> "This session is selected."
                        state == ConversationState.Active || id in busy -> "This session is active."
                        !hostHistorySupported || connectionState != "Live" -> "Connect to delete."
                        else -> null
                    }
                    val inboxFiles = if (sidebarOpen && localIds.isNotEmpty())
                        AttachmentDraftStore(context).load().mapNotNull { it.draftScope }.toSet() else emptySet()
                    val known = hostHistory.orEmpty().sortedWith(compareByDescending<PhoneConversation> { it.createdAt }.thenBy { it.conversationId })
                    val localRows = localUiState.local.sessions.associateBy { it.conversationId }
                    val history = known.filterNot { it.conversationId == displayedId || it.conversationId in deletedIds }
                        .map { chat ->
                            val id = chat.conversationId
                            val state = localRows[id]?.let { ConversationState.parse(it.state) } ?: chat.state
                            SidebarSession(selectedMachineKey, id, chat.sessionName ?: "Untitled session",
                                sessionTime(chat.createdAt), status(id), deleteBlock(id, state))
                        } + localIds.filter { id ->
                            id != displayedId && id !in localRows && known.none { it.conversationId == id } &&
                                (id in busy || drafts.load("$selectedMachineKey/$id").isNotBlank() || "$selectedMachineKey/$id" in inboxFiles)
                        }.map { id ->
                            SidebarSession(selectedMachineKey, id, draftTitle(drafts.load("$selectedMachineKey/$id")) ?: "New chat",
                                null, status(id) ?: "Draft", canPin = false)
                        }
                    val current = displayedId?.let { id ->
                        val persisted = focusedChat?.createIfMissing != true
                        val title = piSession.sessionName ?: catalogSession?.sessionName
                            ?: draftTitle(piSession.lines.firstOrNull { it.fromUser }?.body)
                            ?: if (persisted) "Untitled session" else "New chat"
                        SidebarSession(selectedMachineKey, id, title,
                            sessionTime(catalogSession?.createdAt ?: displayedSession?.createdAt),
                            status(id), "This session is selected.", canPin = persisted)
                    }
                    PeerSidebarMenu(
                        current = current,
                        history = history,
                        pinnedConversationIds = archivePins,
                        historyActionError = historyActionError,
                        deletingConversationId = deletingId,
                        historyNotice = when {
                            connectionState != "Live" && history.isEmpty() -> "Connect to load sessions from this computer."
                            connectionState == "Live" && !hostHistorySupported -> "Update the computer to browse its Pi sessions."
                            historyFailed -> "Couldn’t load all sessions."
                            sidebarOpen && connectionState == "Live" && !historyComplete -> "Loading sessions…"
                            history.isEmpty() && historyComplete -> "No other sessions."
                            else -> null
                        },
                        onRetryHistory = if (historyFailed && connectionState == "Live") ({ historyRefresh++ }) else null,
                        onSetPinned = { chat, pinned ->
                            runCatching { store.setArchivePinned(chat.hostId, chat.conversationId, pinned) }
                                .onSuccess { archivePins = it; historyActionError = null }
                                .onFailure { historyActionError = "Couldn’t update pinned sessions. Try again." }
                        },
                        onDelete = delete@{ chat ->
                            if (deletingId != null || chat.deleteUnavailable != null || chat.conversationId == displayedId) return@delete
                            deletingId = chat.conversationId
                            historyActionError = null
                            coroutineScope.launch {
                                try {
                                    transport.deleteConversation(ConversationTarget.saved(chat.hostId, chat.conversationId))
                                    deletedIds = deletedIds + chat.conversationId
                                    if (chat.conversationId in archivePins) {
                                        runCatching { store.setArchivePinned(chat.hostId, chat.conversationId, false) }
                                            .onSuccess { archivePins = it }
                                            .onFailure { historyActionError = "Session deleted. Couldn’t clear its pin on this phone." }
                                    }
                                } catch (rejected: IrohTransport.HistoryRequestRejected) {
                                    historyActionError = when (rejected.code) {
                                        "conversation-busy", "conversation-owned" -> "“${chat.title}” is in use. Close it on the computer or wait for its session to end."
                                        "conversation-unavailable" -> "The computer couldn’t find “${chat.title}”."
                                        else -> "The computer couldn’t delete “${chat.title}”."
                                    }
                                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                                    historyActionError = "The computer didn’t confirm deletion. Reopen the sidebar to check."
                                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (_: Exception) {
                                    historyActionError = "Couldn’t confirm deletion of “${chat.title}”."
                                } finally { deletingId = null }
                            }
                        },
                        onNewChat = if (focusedChat != null && (focusedChat?.createIfMissing == false || displayedRequests.isNotEmpty() ||
                            drafts.load(draftScope).isNotBlank() || draftScope in inboxFiles)) ({
                            focusChat(ConversationTarget.new(selectedMachineKey))
                            coroutineScope.launch { pagerState.animateScrollToPage(AppPage.Chat.ordinal) }
                        }) else null,
                        onOpen = open@{ chat ->
                            if (chat != null && chat.conversationId != displayedId) {
                                if (!canChooseSession) {
                                    historyActionError = "Update the computer to switch sessions."
                                    return@open
                                }
                                val target = if (chat.canPin) ConversationTarget.saved(chat.hostId, chat.conversationId)
                                    else ConversationTarget.draft(chat.hostId, chat.conversationId)
                                focusChat(target)
                            }
                            coroutineScope.launch { pagerState.animateScrollToPage(AppPage.Chat.ordinal) }
                        },
                        hosts = trustedMachines.map { machine ->
                            PeerDirectoryHost(machine.machineId, machine.displayName, machine.machineId == selectedTrustedMachineId,
                                peerStatus(machine.machineId == selectedTrustedMachineId, connectionState), machine.lastSeenEpochMillis)
                        },
                        onUseHost = { hostId -> coroutineScope.launch {
                            if (hostId != selectedTrustedMachineId) {
                                responseStream.invalidate()
                                responseTokens.clear()
                                val selection = runCatching { store.selectMachine(hostId) }
                                if (selection.isFailure) {
                                    localViewModel.onError(selection.exceptionOrNull()?.message ?: "Could not select computer")
                                    return@launch
                                }
                                pairing = store.pairing()
                            }
                            pagerState.animateScrollToPage(AppPage.Chat.ordinal)
                        } },
                        onOpenDetails = { hostId -> openPeerDetails(hostId, PeerReturnTarget.Sidebar) },
                        onPairWithQr = { showScanner = true },
                        onEnterPairingCode = { showPairingToken = true },
                        onOpenSettings = { coroutineScope.launch { navigateTo(Destination.Settings) } },
                    )
                },
                modelCatalog = modelCatalog,
                chatConfiguration = chatConfiguration,
                confirmedConfiguration = confirmedConfiguration,
                modelsLoading = modelsLoading,
                modelError = modelError,
                onRefreshModels = { coroutineScope.launch { transport.refreshModels() } },
                onSelectModel = transport::selectChatModel,
                peerName = selectedTrustedMachine?.displayName ?: activePairing?.name,
                connectionState = connectionState,
                transcript = activeTranscript,
                speechMessage = speechQueueMessage,
                speechModel = selectedSpeechModel,
                readAloudReady = manualReadAloudReady,
                readingMessageKey = manualReadMessageKey,
                onReadAloud = { messageKey, spokenText ->
                    activitySpeech.interrupt()
                    manualReadRequestId?.let(tts::stop)
                    val generation = manualReadGeneration + 1
                    manualReadGeneration = generation
                    manualReadRequestId = null
                    manualReadMessageKey = messageKey
                    val baseRequestId = "manual-${java.util.UUID.randomUUID()}"
                    manualReadRequestId = baseRequestId
                    val phrases = com.kompi.app.stream.SpeechChunkPlanner.splitComplete(
                        spokenText, tts.selectedVoice.value?.localeTag?.let(java.util.Locale::forLanguageTag) ?: java.util.Locale.getDefault(),
                    ).orEmpty()
                    coroutineScope.launch {
                        var completed = false
                        var acceptedAll = phrases.isNotEmpty()
                        try {
                            if (voiceCanRecover) withContext(Dispatchers.IO) { tts.initialize().getOrThrow() }
                            for ((index, phrase) in phrases.withIndex()) {
                                if (manualReadGeneration != generation || !manualReadAloudReady ||
                                    !tts.enqueueWhenAvailable(baseRequestId, index.toLong(), phrase)
                                ) {
                                    acceptedAll = false
                                    break
                                }
                            }
                            if (acceptedAll) {
                                tts.finishRequest(baseRequestId)
                                val status = tts.requestStatuses.first { statuses ->
                                    val state = statuses[baseRequestId]?.state
                                    manualReadGeneration != generation || state in setOf(
                                        KokoroSpeechStatus.Completed,
                                        KokoroSpeechStatus.Cancelled,
                                        KokoroSpeechStatus.Failed,
                                    ) || !manualReadAloudReady
                                }[baseRequestId]?.state
                                completed = manualReadGeneration == generation && status == KokoroSpeechStatus.Completed
                            }
                        } finally {
                            if (!completed && phrases.isNotEmpty()) tts.stop(baseRequestId)
                            if (manualReadGeneration == generation) {
                                manualReadRequestId = null
                                manualReadMessageKey = null
                                if (!completed && phrases.isNotEmpty()) speechQueueMessage = "Kokoro could not finish reading this message; check that the voice is ready."
                            }
                        }
                    }
                    if (phrases.isEmpty()) {
                        manualReadRequestId = null
                        manualReadMessageKey = null
                        speechQueueMessage = "No readable text in this message."
                    }
                },
                onStopReadAloud = { messageKey ->
                    if (manualReadMessageKey == messageKey) {
                        manualReadGeneration++
                        manualReadRequestId?.let(tts::stop)
                        manualReadRequestId = null
                        manualReadMessageKey = null
                    }
                },
                speechLanguage = whistleLanguage.takeUnless { it == "auto" },
                speechVocabulary = whistleVocabulary,
                phoneConversationsSupported = phoneConversationsSupported,
                attachmentsSupported = attachmentsSupported,
                keyboardVisible = keyboardVisible,
                liveControllerRef = liveControllerRef,
                onRegisterCaptureHandoff = { handoff -> captureHandoffRef.value = handoff },
                onLiveActiveChanged = { active ->
                    if (active) {
                        transport.prewarmLiveDraft()
                        if (ttsEnabled && thinkingSpeechEnabled) tts.prepareToolCue(thinkingSpeechPrefix)
                    }
                },
                liveContextKey = draftScope ?: selectedMachineKey,
                attachmentDraftScope = draftScope,
                independentChats = focusedChat != null,
                submitScope = coroutineScope,
                unsentPrompts = unsentPrompts,
                transientPromptIds = transientPromptIds.toSet(),
                pendingAttachmentIds = localUiState.local.outbox.flatMap { row -> row.attachmentIds }.toSet(),
                attachmentShareReceipt = attachmentShareReceipt,
                attachmentShareImporting = attachmentShareImporting,
                initialDraft = restoredDraft,
                onDraftChanged = { draft -> drafts.save(draftScope, draft) },
                onDiscardPrompt = { requestId -> transport.discardUnacceptedPrompt(requestId) },
                sessionMessage = sessionRefreshMessage,
                historyLoadError = historyPane.error,
                onRetryHistory = historyPane.retry,
                loadingHistory = historyPane.loading,
                recoveringHistory = historyPane.fetching,
                checkingReply = historyPane.checkingReply,
                canCheckHistory = historyPane.canCheck,
                onNewChat = {
                    if (focusedChat != null) focusChat(ConversationTarget.new(selectedMachineKey))
                    else {
                        transport.startNewChat()
                        responseStream.invalidate()
                        responseTokens.clear()
                        tts.stop()
                    }
                },
                onPair = { showScanner = true },
                onOpenVoiceSettings = { coroutineScope.launch { navigateTo(Destination.Settings) } },
                onStopSpeech = { responseStream.invalidate(); tts.stop() },
                onCancelSpeech = { requestId -> responseStream.cancel(requestId); tts.stop(requestId) },
                onSetLiveOutputMuted = responseStream::setOutputMuted,
                onCancelPrompt = { requestId -> activitySpeech.retire(requestId); transport.cancel(requestId) },
                artifactPeer = selectedTrustedMachineId,
                artifactDownloadsSupported = artifactDownloadsSupported,
                onDownloadArtifact = transport::downloadArtifact,
                onUpload = { attachment, onProgress -> transport.uploadAttachment(attachment, onProgress) },
                onSend = { text, attachments, onProgress, requestId ->
                    val stableRequestId = requestId ?: java.util.UUID.randomUUID().toString()
                    activitySpeech.begin(stableRequestId)
                    com.kompi.app.diagnostics.LocalDiagnostics.begin(
                        com.kompi.app.diagnostics.LocalDiagnostics.Metric.LocalSendToVisible,
                        stableRequestId,
                    )
                    if (connectionState != "Disconnected") transientPromptIds.add(stableRequestId)
                    try {
                        val revision = drafts.revision(draftScope)
                        withContext(Dispatchers.IO) { drafts.markSubmitting(draftScope, revision, stableRequestId) }
                        val sentRequestId = if (target != null) {
                            responseConversationIds[stableRequestId] = target.id
                            transport.sendPromptTo(target, text, attachments, stableRequestId)
                        } else if (attachments.isEmpty()) {
                            transport.sendPromptWithId(text, Delivery.FollowUp, stableRequestId)
                        } else {
                            transport.sendPromptWithAttachments(text, Delivery.FollowUp, attachments, onProgress, stableRequestId)
                        }
                        drafts.finishSubmission(draftScope, stableRequestId)
                        responseTokens[sentRequestId] = responseStream.generationToken()
                        sentRequestId
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        if (localRepository.hasQueuedPrompt(selectedMachineKey, stableRequestId)) stableRequestId
                        else {
                            com.kompi.app.diagnostics.ChatTiming.record(com.kompi.app.diagnostics.ChatTiming.Stage.PhoneSubmitFailed, request = stableRequestId)
                            transientPromptIds.remove(stableRequestId)
                            com.kompi.app.diagnostics.LocalDiagnostics.cancel(
                                com.kompi.app.diagnostics.LocalDiagnostics.Metric.LocalSendToVisible,
                                stableRequestId,
                            )
                            localViewModel.onError("Message was not saved: ${error.message ?: "local storage unavailable"}")
                            null
                        }
                    }
                },
              )
            }
            Destination.Peers -> PeerDirectoryScreen(
                modifier = contentModifier,
                hosts = trustedMachines.map { machine ->
                    PeerDirectoryHost(
                        hostId = machine.machineId,
                        name = machine.displayName,
                        selected = machine.machineId == selectedTrustedMachineId,
                        status = peerStatus(machine.machineId == selectedTrustedMachineId, connectionState),
                        lastSeenEpochMillis = machine.lastSeenEpochMillis,
                    )
                },
                onOpenDetails = { hostId -> openPeerDetails(hostId, PeerReturnTarget.Directory) },
                onUseHost = { hostId ->
                    responseStream.invalidate()
                    responseTokens.clear()
                    runCatching { store.selectMachine(hostId) }.onSuccess { pairing = store.pairing() }
                },
                onRemoveHost = { hostId ->
                    runCatching { store.forgetTrustedMachine(hostId) }
                        .onSuccess {
                            trustedMachines.clear()
                            trustedMachines.addAll(store.trustedMachines())
                            pairing = store.pairing()
                        }
                        .onFailure { localViewModel.onError(it.message ?: "Could not remove computer") }
                },
                onPairWithQr = { showScanner = true },
                onEnterPairingCode = { showPairingToken = true },
            )
            Destination.PeerDetails -> {
                val request = peerDetailsRequest
                val machine = request?.let { details -> trustedMachines.firstOrNull { it.machineId == details.hostId } }
                if (request == null || machine == null) {
                    PeerDirectoryScreen(
                        modifier = contentModifier,
                        hosts = trustedMachines.map { item ->
                            PeerDirectoryHost(item.machineId, item.displayName, item.machineId == selectedTrustedMachineId, peerStatus(item.machineId == selectedTrustedMachineId, connectionState), item.lastSeenEpochMillis)
                        },
                        onOpenDetails = { hostId -> openPeerDetails(hostId, PeerReturnTarget.Directory) },
                        onUseHost = { hostId -> runCatching { store.selectMachine(hostId) }.onSuccess { pairing = store.pairing() } },
                        onRemoveHost = { hostId ->
                            runCatching { store.forgetTrustedMachine(hostId) }
                                .onSuccess { trustedMachines.clear(); trustedMachines.addAll(store.trustedMachines()); pairing = store.pairing() }
                                .onFailure { localViewModel.onError(it.message ?: "Could not remove computer") }
                        },
                        onPairWithQr = { showScanner = true },
                        onEnterPairingCode = { showPairingToken = true },
                    )
                } else {
                    val isSelected = machine.machineId == selectedTrustedMachineId
                    val hostSettings by remember(machine.machineId, localRepository) {
                        localRepository.observeHostSettings(machine.machineId)
                    }.collectAsStateWithLifecycle(initialValue = null)
                    PeerDetailsScreen(
                        modifier = contentModifier,
                        host = PeerHostSettings(
                            hostId = machine.machineId,
                            name = machine.displayName,
                            pairingName = machine.pairing.name,
                            displayNameOverride = machine.displayNameOverride,
                            selected = isSelected,
                            status = peerStatus(isSelected, connectionState),
                            lastSeenEpochMillis = machine.lastSeenEpochMillis,
                            requestedIdleTimeoutSeconds = (hostSettings?.pendingIdleTimeoutSeconds ?: hostSettings?.effectiveIdleTimeoutSeconds)?.toInt() ?: machine.idleTimeoutSeconds,
                            effectiveIdleTimeoutSeconds = hostSettings?.effectiveIdleTimeoutSeconds?.toInt(),
                            idleTimeoutPending = hostSettings?.pendingIdleTimeoutSeconds != null,
                            idleTimeoutError = if (isSelected) idleTimeoutError else null,
                            requestedUploadRetentionSeconds = (hostSettings?.pendingUploadRetentionSeconds ?: hostSettings?.effectiveUploadRetentionSeconds)?.toInt() ?: com.kompi.app.protocol.generated.DEFAULT_UPLOAD_RETENTION_SECONDS,
                            effectiveUploadRetentionSeconds = hostSettings?.effectiveUploadRetentionSeconds?.toInt(),
                            uploadRetentionPending = hostSettings?.pendingUploadRetentionSeconds != null,
                            uploadRetentionError = if (isSelected) idleTimeoutError else null,
                            capabilityDetails = if (isSelected) buildList {
                                if (phoneConversationsSupported) add("Phone conversations")
                                if (attachmentsSupported) add("File uploads")
                            } else null,
                        ),
                        returnTo = request.returnTo,
                        onBack = { coroutineScope.launch { returnFromPeerDetails() } },
                        onUseHost = { hostId ->
                            responseStream.invalidate()
                            responseTokens.clear()
                            runCatching { store.selectMachine(hostId) }.onSuccess { pairing = store.pairing() }
                                .onFailure { localViewModel.onError(it.message ?: "Could not select computer") }
                        },
                        onSaveDisplayName = { hostId, name ->
                            runCatching {
                                store.saveDisplayName(hostId, name)
                                val updated = store.trustedMachines()
                                trustedMachines.clear()
                                trustedMachines.addAll(updated)
                                // Leave pairing and selection untouched: a label edit never reconnects.
                                Unit
                            }
                        },
                        onSaveIdleTimeoutSeconds = { hostId, seconds ->
                            coroutineScope.launch {
                                localRepository.updatePendingSettings(hostId, idleTimeoutSeconds = seconds.toLong(), uploadRetentionSeconds = null)
                                if (hostId == selectedTrustedMachineId) {
                                    runCatching { transport.configureConversationIdleTimeout(seconds) }
                                        .onFailure { localViewModel.onError(it.message ?: "Could not update idle timeout") }
                                }
                            }
                        },
                        onSaveUploadRetentionSeconds = { hostId, seconds ->
                            coroutineScope.launch {
                                localRepository.updatePendingSettings(hostId, idleTimeoutSeconds = null, uploadRetentionSeconds = seconds.toLong())
                                if (hostId == selectedTrustedMachineId) {
                                    runCatching { transport.configureUploadRetention(seconds) }
                                        .onFailure { localViewModel.onError(it.message ?: "Could not update upload retention") }
                                }
                            }
                        },
                        onRemoveHost = { hostId ->
                            runCatching { store.forgetTrustedMachine(hostId) }
                                .onSuccess {
                                    trustedMachines.clear()
                                    trustedMachines.addAll(store.trustedMachines())
                                    pairing = store.pairing()
                                    coroutineScope.launch { returnFromPeerDetails() }
                                }
                                .onFailure { localViewModel.onError(it.message ?: "Could not remove computer") }
                        },
                    )
                }
            }
            Destination.Settings -> SettingsScreen(
                contentModifier, theme, kokoroState, ttsEnabled, whistleModel, whistleModelChecked, whistleDownloading, whistleInstallMessage, whistleLanguage,
                speechInputEngine = speechInputEngine,
                parakeetState = parakeetState,
                onSpeechInputEngine = { selected ->
                    speechInputEngine = selected
                    settings.edit().putString(SpeechInputEngine.PREFERENCE, selected.preference).apply()
                },
                onDownloadParakeet = {
                    if (parakeetState == ParakeetInstallState.Missing || parakeetState is ParakeetInstallState.Failed) {
                        parakeetState = ParakeetInstallState.Downloading(0f)
                        parakeetDownloadJob = coroutineScope.launch {
                            try {
                                parakeetInstaller.download { progress ->
                                    coroutineScope.launch {
                                        if (parakeetState is ParakeetInstallState.Downloading) parakeetState = ParakeetInstallState.Downloading(progress)
                                    }
                                }.fold(
                                    onSuccess = { parakeetState = ParakeetInstallState.Ready(it) },
                                    onFailure = { parakeetState = ParakeetInstallState.Failed(it.message ?: "Parakeet download failed") },
                                )
                            } finally {
                                parakeetDownloadJob = null
                                if (parakeetState is ParakeetInstallState.Downloading || parakeetState == ParakeetInstallState.Cancelling) parakeetState = ParakeetInstallState.Missing
                            }
                        }
                    }
                },
                onCancelParakeetDownload = {
                    parakeetState = ParakeetInstallState.Cancelling
                    parakeetDownloadJob?.cancel()
                },
                whistleVocabulary = whistleVocabulary,
                onWhistleVocabulary = { vocabulary ->
                    whistleVocabulary = vocabulary
                    settings.edit().putString("whistleVocabulary", vocabulary.text).apply()
                },
                chatReadAloudEnabled = chatReadAloudEnabled,
                onChatReadAloudEnabled = { enabled ->
                    chatReadAloudEnabled = enabled
                    settings.edit().putBoolean("chatReadAloudEnabled", enabled).apply()
                    if (!enabled && pagerState.currentPage != AppPage.Live.ordinal) responseStream.invalidate()
                },
                thinkingSpeechEnabled = thinkingSpeechEnabled,
                thinkingSpeechPrefix = thinkingSpeechPrefix,
                onThinkingSpeechEnabled = { enabled ->
                    thinkingSpeechEnabled = enabled
                    if (!enabled) activitySpeech.invalidate()
                    settings.edit().putBoolean("thinkingSpeechEnabled", enabled).apply()
                },
                onThinkingSpeechPrefix = { prefix ->
                    thinkingSpeechPrefix = prefix.take(39)
                    settings.edit().putString("thinkingSpeechPrefix", thinkingSpeechPrefix).apply()
                },
                speechMessage = previewSpeechMessage,
                previewRequestId = previewRequestId,
                playbackPhases = tts.playbackPhase,
                availableKokoroVoices = availableKokoroVoices,
                selectedKokoroVoice = selectedKokoroVoice,
                onSelectKokoroVoice = { voiceId ->
                    if (tts.selectVoice(voiceId)) settings.edit().putString("kokoroVoiceId", voiceId.value).apply()
                },
                onTheme = onTheme,
                onTtsEnabled = { enabled ->
                    ttsEnabled = enabled
                    tts.setEnabled(enabled)
                    if (!enabled) responseStream.invalidate()
                    settings.edit().putBoolean("ttsEnabled", enabled).apply()
                },
                onStopSpeech = { responseStream.invalidate() },
                onPreviewVoice = {
                    previewRequestId?.let(tts::stop)
                    val requestId = "preview-${java.util.UUID.randomUUID()}"
                    val phrases = listOf(
                        "Hello, I'm your on-device voice. Let's take a moment to hear how I sound.",
                        "On a quiet morning, sunlight spills across the kitchen, and fresh coffee warms the air.",
                        "Could you check the forecast, find my next meeting, and remind me to bring an umbrella?",
                        "At nine thirty, the train leaves platform seven; tickets cost twelve euros and fifty cents.",
                        "Some sentences are short. Others flow more slowly, with a thoughtful pause before the final word.",
                        "Whether we're reviewing code or planning the weekend, I'll keep you company, one clear sentence at a time.",
                    )
                    val queued = phrases.withIndex().all { (index, phrase) -> tts.enqueue(requestId, index.toLong(), phrase) }
                    previewRequestId = requestId.takeIf { queued }
                    speechQueueMessage = if (queued) "" else "Voice preview unavailable · check the model or speech queue."
                    if (queued) tts.finishRequest(requestId) else tts.stop(requestId)
                },
                onWhistleLanguage = { language ->
                    whistleLanguage = language
                    settings.edit().putString("whistleLanguage", language).apply()
                },
                onDownloadKokoro = {
                    coroutineScope.launch { withContext(Dispatchers.IO) { tts.downloadModels() } }
                },
                onCancelKokoroDownload = { tts.cancelDownload() },
                onDownloadWhistle = {
                    if (!whistleDownloading && whistleModel == null) coroutineScope.launch {
                        whistleDownloading = true
                        whistleInstallMessage = "Downloading the verified 16.9 MB model… audio is never uploaded."
                        val result = withContext(Dispatchers.IO) { whistleInstaller.download() }
                        whistleDownloading = false
                        when (result) {
                            is WhistleInstallResult.Installed -> { whistleModel = result.model; whistleModelChecked = true; whistleInstallMessage = "Whistle installed privately and checksum-verified. Ready for offline speech input." }
                            is WhistleInstallResult.Rejected -> whistleInstallMessage = "Whistle install failed: ${result.reason}"
                        }
                    }
                },
            )
        }
      }
      }
    if (showScanner) {
        PairScannerDialog(onDismiss = { showScanner = false }, onPairing = { scanned ->
            store.stagePairing(scanned); pairing = store.currentPairing(); showScanner = false
        })
    }
    pairing?.takeUnless { it.confirmed }?.let { pending ->
        PairingConnectionDialog(
            name = pending.name,
            failure = pairingFailure,
            onRetry = { pairingAttempt++ },
            onDismiss = {
                transport.stop()
                store.clearPendingPairing()
                trustedMachines.clear()
                trustedMachines.addAll(store.trustedMachines())
                pairing = store.pairing()
            },
        )
    }
    val showCurrentChatDialogs = destination == Destination.Conversation && pagerState.currentPage != AppPage.Sidebar.ordinal
    pendingAndroidTools.firstOrNull { showCurrentChatDialogs && (focusedChat == null || it.conversationId == displayedId) &&
        it.tool != com.kompi.app.transport.AndroidToolKind.SendNotification }?.let { request ->
        key(request) {
            com.kompi.app.assistant.AndroidToolHandler(request) { activity -> transport.executeForegroundAndroidTool(request, activity) }
        }
    }
    pendingPiDialogs.firstOrNull { showCurrentChatDialogs && (focusedChat == null || it.conversationId == displayedId) }?.let { request ->
        key(request.interactionId) {
            PiRpcDialog(request, piDialogError) { answer -> transport.answerPiDialog(request, answer) }
        }
    }
    if (showPairingToken) {
        PairingTokenDialog(onDismiss = { showPairingToken = false }, onPairing = { pasted ->
            store.stagePairing(pasted); pairing = store.currentPairing(); showPairingToken = false
        })
    }
}

private fun sessionTime(timestamp: String?): String? = timestamp?.let { value ->
    runCatching {
        java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)
            .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse(value))
    }.getOrNull()
}

private val titleWhitespace = Regex("\\s+")
private fun draftTitle(text: String?): String? = text?.trim()?.replace(titleWhitespace, " ")?.take(120)?.takeIf { it.isNotBlank() }

internal data class TranscriptLine(val text: String, val fromUser: Boolean, val key: String, val sessionId: String? = null, val conversationId: String? = null, val isActivity: Boolean = false, val isDivider: Boolean = false, val unlinkedReceipt: Boolean = false, val waitingForReply: Boolean = false, val animateArrivals: Boolean = false, val piDetails: com.kompi.app.transport.PiSessionStore.Details? = null,
    val body: String = text, val attachments: List<MessageAttachment> = emptyList(),
    val attachmentDispatch: AttachmentDispatchStage? = null,
    val replyDetail: String? = null, val deliveryStatus: OutgoingMessageStatus? = null, val artifact: OfferedArtifact? = null)

internal data class TranscriptProjection(val all: List<TranscriptLine>, val active: List<TranscriptLine>) {
    companion object { val Empty = TranscriptProjection(emptyList(), emptyList()) }
}

internal fun buildTranscriptProjection(
    pi: com.kompi.app.transport.PiSessionStore.View,
    pending: List<Pair<String, String>>,
    requests: List<com.kompi.app.data.RequestStateRow>,
    notices: List<com.kompi.app.data.ConversationViewModel.Notice>,
    pendingAttachments: Map<String, List<MessageAttachment>> = emptyMap(),
    attachmentDispatchStages: Map<String, AttachmentDispatchStage> = emptyMap(),
    outboxStates: Map<String, String> = emptyMap(),
    observedWrites: Set<String> = emptySet(),
    snapshotCompletions: Set<String> = emptySet(),
): TranscriptProjection {
    val states = requests.associateBy { it.requestId }
    fun replyDetail(id: String) = when (states[id]?.outcome) {
        "running" -> "Working on reply"
        "completed" -> "Completed"
        "failed" -> "Reply failed"
        "host-cancelled" -> "Reply stopped"
        "uncertain" -> "Reply outcome unknown"
        "accepted" -> "Waiting for reply"
        else -> null
    }
    val lines = pi.lines.filterNot { it.activityKind == com.kompi.app.transport.PiSessionStore.Activity.Thinking }.map { line -> TranscriptLine(line.text, line.fromUser, line.key, pi.sessionId, pi.conversationId,
        isActivity = line.activity, animateArrivals = line.streaming, piDetails = line.details,
        replyDetail = replyDetail(line.key.removePrefix("user:")), body = line.body, attachments = line.attachments, artifact = line.artifact,
        deliveryStatus = OutgoingMessageStatus.Sent.takeIf { line.fromUser && line.key.startsWith("user:") && line.key.removePrefix("user:") in pi.representedRequests }) }.toMutableList()
    val pendingIds = pending.mapTo(mutableSetOf()) { it.first }
    fun belongsHere(conversationId: String?, id: String? = null) = conversationId.isNullOrBlank() || conversationId == pi.conversationId ||
        (pi.conversationId == null && id in pendingIds)
    val renderedKeys = lines.mapTo(mutableSetOf()) { it.key }
    for ((id, body) in pending) {
        if ("user:$id" in renderedKeys) continue
        val state = states[id]
        if (!belongsHere(state?.conversationId, id)) continue
        if (state?.outcome in setOf("cancelled", "discarded")) continue
        if (state?.outcome == "completed" && id in snapshotCompletions) {
            lines.add(TranscriptLine(body, false, "receipt:$id", conversationId = pi.conversationId, unlinkedReceipt = true))
            continue
        }
        val status = when {
            state?.outcome == "local-failure" -> OutgoingMessageStatus.LocalFailure
            state?.outcome == "rejected" -> OutgoingMessageStatus.Failed
            state?.outcome in setOf("accepted", "running", "completed", "uncertain", "failed", "host-cancelled") ||
                outboxStates[id] == "accepted" -> OutgoingMessageStatus.Sent
            outboxStates[id] == "dispatching" -> if (id in observedWrites) OutgoingMessageStatus.Dispatching else OutgoingMessageStatus.Uncertain
            id in attachmentDispatchStages -> OutgoingMessageStatus.Uploading
            else -> OutgoingMessageStatus.Sending
        }
        val attachments = pendingAttachments[id].orEmpty()
        lines.add(TranscriptLine(body, true, "user:$id", conversationId = pi.conversationId,
            body = body.ifBlank { if (attachments.isEmpty()) "[Attached files]" else "" }, attachments = attachments, attachmentDispatch = attachmentDispatchStages[id], replyDetail = replyDetail(id), deliveryStatus = status))
    }
    // Local queue feedback requires an outbox entry; daemon-owned queued work has a conversation.
    val activeRequests = requests.filter { belongsHere(it.conversationId, it.requestId) && (it.outcome != "queued" || it.conversationId.isNotBlank() || it.requestId in pendingIds) }
    val outstanding = activeRequests.lastOrNull { it.outcome in setOf("accepted", "running") || (it.outcome == "queued" && outboxStates[it.requestId] == "accepted") }
    if (outstanding != null) {
        val userIndex = lines.indexOfLast { it.key == "user:${outstanding.requestId}" }
        val hasAnswer = outstanding.requestId in pi.readableReplies ||
            userIndex >= 0 && lines.drop(userIndex + 1).takeWhile { !it.fromUser }.any { !it.isActivity && it.text.isNotBlank() }
        if (!hasAnswer) lines.add(TranscriptLine("", false, "${outstanding.requestId}:pending", conversationId = pi.conversationId, waitingForReply = true))
    }
    activeRequests.forEach { request ->
        val label = when (request.outcome) {
            "failed" -> "Reply failed"
            "host-cancelled" -> "Reply stopped"
            "uncertain" -> "Reply outcome unknown"
            else -> null
        }
        val user = lines.indexOfFirst { it.key == "user:${request.requestId}" }
        if (label != null && user >= 0 && notices.none { it.key == "${request.requestId}:error" }) {
            val next = (user + 1 until lines.size).firstOrNull { lines[it].fromUser } ?: lines.size
            lines.add(next, TranscriptLine(label, false, "${request.requestId}:outcome", conversationId = pi.conversationId, isActivity = true))
        }
    }
    notices.filter { belongsHere(it.conversationId) }.forEach {
        lines.add(TranscriptLine(it.text, false, it.key, conversationId = it.conversationId, isActivity = true))
    }
    return TranscriptProjection(lines, lines)
}

private data class TypedComposerState(val text: String, val revision: Long = 0) {
    fun edit(value: String) = copy(text = value, revision = revision + 1)
    fun snapshot(): SubmittedDraft = SubmittedDraft(text.trim(), text, revision)
    fun clearSubmitted(snapshot: SubmittedDraft): TypedComposerState =
        if (revision == snapshot.revision && text == snapshot.sourceText) edit("") else this
}

private data class SubmittedDraft(val text: String, val sourceText: String, val revision: Long)

private fun formatIdleTimeout(seconds: Int): String = if (seconds % 60 == 0) "${seconds / 60} min" else "$seconds sec"

@Composable
internal fun ConversationScreen(
    modifier: Modifier,
    pagerState: PagerState = rememberPagerState(initialPage = AppPage.Chat.ordinal) { AppPage.entries.size },
    sidebarContent: @Composable () -> Unit = {},
    peerName: String?,
    connectionState: String,
    transcript: List<TranscriptLine>,
    speechMessage: String,
    speechModel: SpeechRecognitionModel?,
    readAloudReady: Boolean,
    readingMessageKey: String?,
    onReadAloud: (String, String) -> Unit,
    onStopReadAloud: (String) -> Unit,
    speechLanguage: String? = null,
    speechVocabulary: WhistleVocabulary = WhistleVocabulary.Empty,
    phoneConversationsSupported: Boolean,
    attachmentsSupported: Boolean,
    keyboardVisible: Boolean,
    liveControllerRef: androidx.compose.runtime.MutableState<LiveConversationController?>,
    onRegisterCaptureHandoff: ((suspend (closeLiveController: Boolean) -> Unit)?) -> Unit,
    onLiveActiveChanged: (Boolean) -> Unit,
    liveContextKey: String,
    attachmentDraftScope: String? = null,
    independentChats: Boolean = false,
    submitScope: kotlinx.coroutines.CoroutineScope? = null,
    unsentPrompts: List<PendingPrompt>,
    transientPromptIds: Set<String>,
    pendingAttachmentIds: Set<String>,
    attachmentShareReceipt: StateFlow<AttachmentShareReceipt?>,
    attachmentShareImporting: StateFlow<Boolean>,
    initialDraft: String,
    onDraftChanged: (String) -> Unit,
    onDiscardPrompt: suspend (String) -> Boolean,
    sessionMessage: String,
    historyLoadError: Boolean = false,
    loadingHistory: Boolean = false,
    recoveringHistory: Boolean = false,
    checkingReply: Boolean = false,
    canCheckHistory: Boolean = false,
    onRetryHistory: () -> Unit = {},
    onPair: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    onStopSpeech: () -> Unit,
    onCancelSpeech: (String) -> Unit,
    onSetLiveOutputMuted: (Boolean) -> Unit = {},
    onCancelPrompt: suspend (String) -> Unit,
    onUpload: suspend (DraftAttachment, (Long, Long) -> Unit) -> Unit,
    onSend: suspend (String, List<DraftAttachment>, (String, Long, Long) -> Unit, String?) -> String?,
    onNewChat: suspend () -> Unit = {},
    modelCatalog: com.kompi.app.protocol.generated.ModelCatalog?,
    chatConfiguration: com.kompi.app.protocol.generated.ChatModelConfiguration?,
    confirmedConfiguration: com.kompi.app.protocol.generated.ChatModelConfiguration?,
    modelsLoading: Boolean,
    modelError: String?,
    onRefreshModels: () -> Unit,
    onSelectModel: (com.kompi.app.protocol.generated.ChatModelConfiguration) -> Unit,
    artifactPeer: String? = null,
    artifactDownloadsSupported: Boolean = false,
    onDownloadArtifact: suspend (ArtifactDownloadTarget, Uri) -> Unit = { _, _ ->
        throw ArtifactSaveException("Downloads unavailable on this computer.")
    },
) {
    val artifactPicker = rememberArtifactPicker(onDownloadArtifact)
    // SharedPreferences is the durable draft source. A saved-instance copy can predate the
    // Room enqueue and resurrect the submitted version after task/process restoration.
    var composer by remember { mutableStateOf(TypedComposerState(initialDraft)) }
    val composerFieldState = remember { androidx.compose.foundation.text.input.TextFieldState(initialText = initialDraft) }
    val currentDraftChanged by rememberUpdatedState(onDraftChanged)
    // TextFieldState owns edits. Feeding an asynchronously observed draft back
    // into it overwrites newer IME edits and loses characters under load.
    LaunchedEffect(composerFieldState) {
        snapshotFlow { composerFieldState.text.toString() }.collect { value ->
            if (value != composer.text) composer = composer.edit(value)
            currentDraftChanged(value)
        }
    }
    fun updateComposer(next: TypedComposerState) {
        composer = next
        composerFieldState.edit { replace(0, length, next.text) }
        currentDraftChanged(next.text)
    }
    val submittingRevisions = remember { mutableStateListOf<Long>() }
    val context = LocalContext.current
    val attachmentStore = remember(context) { AttachmentDraftStore(context) }
    val importingSharedAttachments by attachmentShareImporting.collectAsState()
    val draftAttachments = remember(attachmentDraftScope) { mutableStateListOf<DraftAttachment>() }
    var draftsLoaded by remember(attachmentDraftScope) { mutableStateOf(false) }
    LaunchedEffect(pendingAttachmentIds) {
        // Room may finish loading after the composer mounts. Outbox-owned files are not a new draft.
        draftAttachments.removeAll { it.uploadId in pendingAttachmentIds }
    }
    val attachmentStatus = remember { mutableStateMapOf<String, AttachmentUiStatus>() }
    var attachmentPickerExpanded by remember { mutableStateOf(false) }
    var importingAttachments by remember { mutableStateOf(false) }
    var modelPickerExpanded by remember(liveContextKey) { mutableStateOf(false) }
    if (modelPickerExpanded) com.kompi.app.chat.ChatModelPicker(
        modelCatalog, chatConfiguration, confirmedConfiguration, modelsLoading, modelError,
        onRefresh = onRefreshModels, onApply = onSelectModel, onDismiss = { modelPickerExpanded = false },
    )
    var startingNewChat by remember { mutableStateOf(false) }
    var composerOverlayHeightPx by remember { mutableIntStateOf(0) }
    val composerOverlayHeight = with(LocalDensity.current) { composerOverlayHeightPx.toDp() }
    val composerFocusRequester = remember { FocusRequester() }
    BackHandler(enabled = attachmentPickerExpanded) { attachmentPickerExpanded = false }
    // Text is durably queued for a trusted host even while disconnected. Live voice remains online-only.
    val canSend = peerName != null
    val canStartLive = draftsLoaded && canSend && !importingAttachments && !importingSharedAttachments && connectionState == "Live" && phoneConversationsSupported
    val canSendAttachments = attachmentsSupported || connectionState != "Live"
    val visibleTranscript = transcript
    // Latch recovery controls once eligible so a connection phase change cannot resize the composer
    // or remove a user's discard/review action while the same durable outbox row is still pending.
    val pendingNoticeIds = remember { mutableStateSetOf<String>() }
    LaunchedEffect(unsentPrompts, visibleTranscript, connectionState, transientPromptIds) {
        val pendingIds = unsentPrompts.map { it.id }.toSet()
        pendingNoticeIds.removeAll { it !in pendingIds }
        unsentPrompts.forEach { prompt ->
            val hasDeliveryError = visibleTranscript.any { it.key == "${prompt.id}:error" }
            if (prompt.id !in transientPromptIds || connectionState == "Disconnected" || hasDeliveryError) {
                pendingNoticeIds.add(prompt.id)
            }
        }
    }
    val visibleUnsentPrompts = unsentPrompts.filter { it.waitingForConversation || it.id in pendingNoticeIds }
    val listState = rememberLazyListState()
    val followsLatestMessage = remember { mutableStateOf(true) }
    val userDragActive = remember { mutableStateOf(false) }
    val userScrollSettling = remember { mutableStateOf(false) }
    fun markUserScrollIntent() {
        followsLatestMessage.value = false
        userScrollSettling.value = true
    }
    val userScrollIntent = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) markUserScrollIntent()
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && consumed.y != 0f) markUserScrollIntent()
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(listState) {
        launch {
            listState.interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is DragInteraction.Start -> {
                        userDragActive.value = true
                        userScrollSettling.value = true
                    }
                    is DragInteraction.Stop, is DragInteraction.Cancel -> {
                        userDragActive.value = false
                        userScrollSettling.value = true
                    }
                }
            }
        }
        snapshotFlow {
            val layout = listState.layoutInfo
            val geometry = layout.visibleItemsInfo.map { Triple(it.index, it.offset, it.size) }
            Triple(geometry, listState.isScrollInProgress, userDragActive.value || userScrollSettling.value)
        }.collect { (_, scrolling, userInitiatedScroll) ->
            if (userInitiatedScroll) {
                followsLatestMessage.value = listState.layoutInfo.totalItemsCount == 0 || !listState.canScrollForward
                if (!userDragActive.value && !scrolling) userScrollSettling.value = false
            }
        }
    }
    val latestTranscript = rememberUpdatedState(visibleTranscript)
    LaunchedEffect(listState) {
        snapshotFlow {
            val lines = latestTranscript.value
            val last = lines.lastOrNull()
            val layout = listState.layoutInfo
            Triple(last?.key to last?.text, layout.visibleItemsInfo.map { Triple(it.index, it.offset, it.size) }, listState.canScrollForward)
        }.collect {
            // Room may publish several adjacent deltas in one frame. Scroll once after Compose has
            // measured the newest text rather than forcing a synchronous layout for every delta.
            withFrameNanos { }
            if (latestTranscript.value.isNotEmpty() && followsLatestMessage.value &&
                !userDragActive.value && !userScrollSettling.value && listState.canScrollForward
            ) {
                listState.scrollToItem(latestTranscript.value.lastIndex, Int.MAX_VALUE)
                followsLatestMessage.value = true
            }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    var recording by remember { mutableStateOf(false) }
    var dictationPhase by remember { mutableStateOf(OfflineSpeechSessionPhase.Idle) }
    val dictationStartFence = remember { com.kompi.app.audio.DictationStartFence() }
    val latestLiveContext = rememberUpdatedState(liveContextKey)
    var dictationPartial by remember { mutableStateOf<com.kompi.app.audio.OfflineTranscript?>(null) }
    var dictationOverflow by remember { mutableStateOf(false) }
    var voiceMessage by remember { mutableStateOf("") }
    LaunchedEffect(attachmentDraftScope, attachmentShareReceipt, pendingAttachmentIds) {
        attachmentShareReceipt.collectLatest { receipt ->
            val available = withContext(Dispatchers.IO) {
                if (receipt != null && attachmentDraftScope != null) {
                    val unscopedIds = receipt.selection?.added.orEmpty().mapTo(hashSetOf()) { it.uploadId }
                    attachmentStore.claimUnscoped(attachmentDraftScope, unscopedIds)
                }
                attachmentStore.loadFor(attachmentDraftScope).filterNot { it.uploadId in pendingAttachmentIds }
            }
            draftAttachments.clear()
            draftAttachments.addAll(available)
            draftsLoaded = true
            if (receipt == null) return@collectLatest
            voiceMessage = when {
                draftAttachments.size > AttachmentDraftStore.MAX_DRAFT_ATTACHMENTS ->
                    "You can attach up to ${AttachmentDraftStore.MAX_DRAFT_ATTACHMENTS} files per message. Remove files to continue."
                receipt.message != null -> receipt.message
                else -> ""
            }
        }
    }
    val sendScope = rememberCoroutineScope()
    val activeSpeechSessionToken = remember { mutableStateOf<Any?>(null) }
    val speechSessionToken = remember(speechModel, speechLanguage, speechVocabulary, lifecycleOwner) { Any() }
    val session = remember(speechModel, speechLanguage, speechVocabulary, lifecycleOwner) {
        speechModel?.let { model ->
            OfflineSpeechSession(
                context, lifecycleOwner, model, speechLanguage,
                onPartial = { result ->
                    if (result.text.isBlank()) {
                        dictationPartial = null
                        dictationOverflow = false
                    } else {
                        dictationPartial = result
                        dictationOverflow = false
                    }
                },
                onTranscript = { result ->
                    val dictatedText = result.text
                    var merged = false
                    // Keep interim words out of the durable draft. At finalization merge once at
                    // the user's then-current selection, preserving all unrelated typed edits.
                    composerFieldState.edit {
                        when (val result = com.kompi.app.audio.DictationDraftMerger.merge(
                            this.asCharSequence().toString(), selection.min, selection.max, dictatedText,
                        )) {
                            is com.kompi.app.audio.DictationDraftMerge.Applied -> {
                                replace(0, length, result.text)
                                selection = TextRange(result.cursor)
                                merged = true
                            }
                            com.kompi.app.audio.DictationDraftMerge.DraftLimitExceeded -> Unit
                        }
                    }
                    if (merged) {
                        dictationPartial = null
                        dictationOverflow = false
                        voiceMessage = "${result.language?.let { "${it.uppercase()} · " } ?: ""}Transcript added to draft — review before sending."
                    } else {
                        dictationPartial = result
                        dictationOverflow = true
                        voiceMessage = "Transcript is too long to fit in the draft. Copy it, paste a shorter part, then try again."
                    }
                },
                onMessage = { voiceMessage = it },
                onRecordingChanged = { recording = it },
                vocabulary = speechVocabulary,
                onPhaseChanged = { phase -> if (activeSpeechSessionToken.value === speechSessionToken) dictationPhase = phase },
            )
        }
    }
    val currentSend = rememberUpdatedState(onSend)
    val currentDraftAttachments = rememberUpdatedState(draftAttachments.toList())
    val currentOutputMute = rememberUpdatedState(onSetLiveOutputMuted)
    val liveController = remember(lifecycleOwner, context, speechModel, speechLanguage, speechVocabulary) {
        LiveConversationController(
            lifecycleOwner = lifecycleOwner,
            createSpeechInput = { audioSession ->
                val model = speechModel ?: error("Download the selected speech model in Voice settings before starting Live.")
                check(model.engine.supportedOnDevice()) { "${model.engine.label} is not supported on this device." }
                LocalLiveSpeechInput(context, lifecycleOwner, model, speechLanguage, speechVocabulary, audioSession = audioSession)
            },
            openAudioSession = { onLocalStop -> com.kompi.app.audio.LiveAudioSession.open(context, onLocalStop) },
            setOutputMuted = { currentOutputMute.value(it) },
            sendTurn = { requestId, text ->
                check(currentDraftAttachments.value.isEmpty()) { "Send or remove selected files before using Live voice." }
                val sentId = currentSend.value(text, emptyList(), { _, _, _ -> }, requestId)
                check(sentId == requestId) { "Live turn was not durably queued; retry with the same turn ID." }
                liveControllerRef.value?.onTurnCommitted(sentId)
            },
            stopLocalSpeech = { requestId -> if (requestId == null) onStopSpeech() else onCancelSpeech(requestId) },
            cancelRemote = { requestId -> onCancelPrompt(requestId) },
        )
    }
    val captureHandoff: suspend (closeLiveController: Boolean) -> Unit = remember(session, liveController, onStopSpeech) {
        { closeLiveController ->
            dictationStartFence.invalidate()
            dictationPartial = null
            dictationOverflow = false
            var failure: Throwable? = null
            try { session?.stopMicrophoneAndFence() }
            catch (error: Throwable) { failure = error }
            try {
                if (closeLiveController) liveController.closeAndJoin()
                else liveController.stopMicrophoneAndFence()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure?.addSuppressed(error)
            }
            if (failure == null) {
                recording = false
                dictationPhase = OfflineSpeechSessionPhase.Idle
            }
            if (failure != null) {
                // Privacy stops already fence callbacks; repeat the immediate fences and continue
                // route/context changes while the app-scoped lease prevents any new mic capture.
                runCatching { session?.stopAndFence(flush = false) }
                liveController.stopLocally(com.kompi.app.live.LiveLocalStopReason.Backgrounded)
                onStopSpeech()
                throw failure!!
            }
        }
    }
    val liveSnapshot by liveController.snapshot.collectAsState()
    val liveActive = when (liveSnapshot.state) {
        LiveConversationState.Idle, LiveConversationState.Paused, LiveConversationState.Interrupted, is LiveConversationState.Failure -> false
        else -> true
    }
    LaunchedEffect(liveActive) { onLiveActiveChanged(liveActive) }
    var pendingCapture by remember { mutableStateOf<CaptureMode?>(null) }
    DisposableEffect(liveController, liveControllerRef, captureHandoff) {
        liveControllerRef.value = liveController
        onRegisterCaptureHandoff(captureHandoff)
        onDispose {
            if (liveControllerRef.value === liveController) liveControllerRef.value = null
            onRegisterCaptureHandoff(null)
            liveController.close()
        }
    }
    suspend fun startLiveAfterCaptureHandoff(): Boolean = try {
        dictationStartFence.invalidate()
        dictationPartial = null
        dictationOverflow = false
        session?.stopAndJoin(flush = false)
        liveController.endLiveAndJoin()
        onStopSpeech()
        liveController.startLive()
        true
    } catch (failure: Throwable) {
        runCatching { session?.stopAndFence(flush = false) }
        liveController.stopLocally(com.kompi.app.live.LiveLocalStopReason.Backgrounded) // Never open opposite capture after failed handoff.
        onStopSpeech()
        voiceMessage = "Could not safely hand off microphone capture: ${failure.message ?: "try again"}"
        false
    }
    suspend fun startPttAfterCaptureHandoff(ticket: com.kompi.app.audio.DictationStartFence.Ticket): Boolean {
        val requestedContext = liveContextKey
        if (!dictationStartFence.isCurrent(ticket)) return false
        return try {
            dictationPartial = null
            dictationOverflow = false
            liveController.endLiveAndJoin()
            if (!dictationStartFence.isCurrent(ticket)) return false
            val ptt = session ?: error("Offline speech input is unavailable")
            ptt.stopAndJoin(flush = false)
            if (!dictationStartFence.isCurrent(ticket)) return false
            onStopSpeech()
            if (pagerState.currentPage != AppPage.Chat.ordinal || latestLiveContext.value != requestedContext) {
                dictationPhase = OfflineSpeechSessionPhase.Idle
                return false
            }
            val started = ptt.start()
            dictationStartFence.invalidate()
            if (!started) dictationPhase = OfflineSpeechSessionPhase.Idle
            started
        } catch (failure: Throwable) {
            if (!dictationStartFence.isCurrent(ticket)) return false
            dictationStartFence.invalidate()
            dictationPhase = OfflineSpeechSessionPhase.Idle
            runCatching { session?.stopAndFence(flush = false) }
            liveController.stopLocally(com.kompi.app.live.LiveLocalStopReason.Backgrounded) // Never open PTT after failed teardown.
            onStopSpeech()
            voiceMessage = "Could not safely hand off microphone capture: ${failure.message ?: "try again"}"
            false
        }
    }
    LaunchedEffect(session, liveContextKey) {
        dictationStartFence.invalidate()
        dictationPartial = null
        dictationOverflow = false
        try {
            session?.stopAndJoin(flush = false)
            if (!dictationStartFence.hasRequest) dictationPhase = OfflineSpeechSessionPhase.Idle
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            voiceMessage = "Could not safely stop dictation after changing conversation: ${failure.message ?: "try again"}"
        }
    }
    LaunchedEffect(liveController, liveContextKey) { liveController.onContextChanged(liveContextKey) }
    LaunchedEffect(liveController, connectionState) { if (connectionState != "Live") liveController.onConnectionLost() }
    fun importAttachments(uris: List<Uri>) {
        if (uris.isEmpty() || importingAttachments) return
        importingAttachments = true
        sendScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { attachmentStore.importSelection(uris, pendingAttachmentIds, attachmentDraftScope) }
                draftAttachments.addAll(result.added.filterNot { added -> draftAttachments.any { it.uploadId == added.uploadId } })
                voiceMessage = result.message.orEmpty()
            } finally {
                importingAttachments = false
            }
        }
    }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(AttachmentDraftStore.MAX_DRAFT_ATTACHMENTS),
    ) { uris -> importAttachments(uris) }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        importAttachments(uris)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val requestedCapture = pendingCapture
        pendingCapture = null
        when (requestedCapture) {
            CaptureMode.Live -> if (pagerState.currentPage == AppPage.Live.ordinal) {
                if (granted) sendScope.launch { startLiveAfterCaptureHandoff() }
                else voiceMessage = "Microphone permission is needed for Live voice."
            }
            CaptureMode.Dictation -> if (pagerState.currentPage == AppPage.Chat.ordinal) {
                if (granted) {
                    dictationPhase = OfflineSpeechSessionPhase.Preparing
                    val ticket = dictationStartFence.request()
                    sendScope.launch { startPttAfterCaptureHandoff(ticket) }
                } else voiceMessage = "Microphone permission is needed for offline transcription."
            }
            null -> Unit // A page change cancelled this request; never start a different capture mode.
        }
    }
    fun startLiveVoice() {
        when {
            draftAttachments.isNotEmpty() -> voiceMessage = "Send or remove selected files before starting Live voice."
            speechModel == null -> onOpenVoiceSettings()
            speechModel?.engine?.supportedOnDevice() != true -> voiceMessage = "The selected speech engine is not supported on this device."
            !canStartLive -> voiceMessage = "Connect to a computer before starting Live voice."
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> sendScope.launch { startLiveAfterCaptureHandoff() }
            else -> { pendingCapture = CaptureMode.Live; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
        }
    }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(pagerState.settledPage) {
        // Visiting Live never starts recording. Leaving it fences capture.
        pendingCapture = null
        if (pagerState.settledPage != AppPage.Chat.ordinal) {
            attachmentPickerExpanded = false
            focusManager.clearFocus()
            keyboardController?.hide()
        }
        try {
            if (pagerState.settledPage != AppPage.Live.ordinal) liveController.stopMicrophoneAndFence()
            if (pagerState.settledPage != AppPage.Chat.ordinal) {
                dictationStartFence.invalidate()
                dictationPartial = null
                dictationOverflow = false
                session?.stopMicrophoneAndFence()
                recording = false
                dictationPhase = OfflineSpeechSessionPhase.Idle
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            liveController.stopLocally(com.kompi.app.live.LiveLocalStopReason.Backgrounded)
            runCatching { session?.stopAndFence(flush = false) }
            onStopSpeech()
            voiceMessage = "Microphone is still stopping; capture stays disabled until it releases."
        }
    }
    DisposableEffect(session, lifecycleOwner, speechSessionToken) {
        activeSpeechSessionToken.value = speechSessionToken
        dictationPhase = OfflineSpeechSessionPhase.Idle
        recording = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                dictationStartFence.invalidate()
                session?.stopAndFence(flush = false)
                dictationPartial = null
                dictationOverflow = false
                recording = false
                dictationPhase = OfflineSpeechSessionPhase.Idle
                onStopSpeech()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            dictationStartFence.invalidate()
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (activeSpeechSessionToken.value === speechSessionToken) activeSpeechSessionToken.value = null
            session?.close()
        }
    }
    Column(modifier.fillMaxSize()) {
      HorizontalPager(
          state = pagerState,
          modifier = Modifier.weight(1f).fillMaxWidth(),
          beyondViewportPageCount = 1,
          key = { AppPage.entries[it] },
      ) { page ->
        when (AppPage.entries[page]) {
          AppPage.Sidebar -> sidebarContent()
          AppPage.Live -> com.kompi.app.live.LiveSessionContent(
                  message = voiceMessage,
                  snapshot = liveSnapshot,
                  onStart = { startLiveVoice() },
                  onEnd = { sendScope.launch {
                      try { liveController.detachVoiceAndJoin() }
                      catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                      catch (failure: Exception) { voiceMessage = "Voice capture is stopping. Your reply continues in Chat." }
                      finally { pagerState.animateScrollToPage(AppPage.Chat.ordinal) }
                  } },
                  onInterrupt = { liveController.interruptAndListen() },
                  onRetry = { liveController.retryPendingTurn() },
                  onToggleMicrophoneMute = liveController::toggleMicrophoneMute,
                  onToggleOutputMute = liveController::toggleOutputMute,
                  onOpenVoiceSettings = onOpenVoiceSettings,
                  modifier = Modifier.fillMaxSize(),
              )
          AppPage.Chat -> Box(Modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 760.dp).align(Alignment.CenterHorizontally).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (sessionMessage.isNotBlank()) Text(sessionMessage, style = KompiUi.typography.bodySmall, color = KompiUi.colors.error)
            if (historyLoadError) {
                Text(if (checkingReply) "Couldn’t load the reply." else "Couldn’t load this conversation.", style = KompiUi.typography.bodySmall, color = KompiUi.colors.error)
                TextButton(onClick = onRetryHistory) { Text(if (checkingReply) "Check for reply" else "Retry") }
            } else if (loadingHistory) Text("Loading conversation…", style = KompiUi.typography.bodySmall)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
        if (visibleTranscript.isEmpty()) {
            if (peerName == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = onPair, modifier = Modifier.heightIn(min = 48.dp)) {
                        ChatLucideIcon(LucideR.drawable.lucide_ic_qr_code, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Pair a computer")
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize()
                    .nestedScroll(userScrollIntent)
                    .onRotaryScrollEvent { event ->
                        if (event.verticalScrollPixels != 0f) markUserScrollIntent()
                        false
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key in setOf(Key.DirectionUp, Key.DirectionDown, Key.PageUp, Key.PageDown)) {
                            markUserScrollIntent()
                        }
                        false
                    }
                    .widthIn(max = 760.dp).align(Alignment.TopCenter).padding(horizontal = 20.dp),
                state = listState,
                contentPadding = PaddingValues(top = 16.dp, bottom = maxOf(composerOverlayHeight, 112.dp) + 16.dp),
                verticalArrangement = Arrangement.Top,
            ) {
                itemsIndexed(visibleTranscript, key = { _, line -> line.key }) { index, line ->
                    val topGap = when {
                        index == 0 -> 0.dp
                        line.isActivity && visibleTranscript[index - 1].isActivity -> 2.dp
                        else -> 12.dp
                    }
                    Column(Modifier.fillMaxWidth().padding(top = topGap)) {
                    if (line.isDivider) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            HorizontalDivider(Modifier.weight(1f)); Text(line.text, Modifier.padding(horizontal = 10.dp), style = KompiUi.typography.labelSmall, color = KompiUi.colors.onSurfaceVariant); HorizontalDivider(Modifier.weight(1f))
                        }
                    } else if (line.unlinkedReceipt) {
                        var open by remember(line.key) { mutableStateOf(false) }
                        TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Message details") }
                        if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("Message details") },
                            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(line.text)
                                Text("Delivery: Accepted by your computer")
                                Text("Reply: Reported complete")
                                Text("Kompi couldn’t link this request to a specific message in the conversation.")
                            } }, confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } })
                    } else if (line.waitingForReply) {
                        if (connectionState == "Live" && !historyLoadError && !recoveringHistory && !loadingHistory) ReplyPendingRow()
                    } else if (line.artifact != null) {
                        val target = artifactPeer?.let { peer -> line.conversationId?.let { ArtifactDownloadTarget(peer, it, line.artifact) } }
                        artifactPicker.Offer(line.artifact, target, connectionState == "Live", artifactDownloadsSupported)
                    } else {
                        if (line.key.startsWith("user:")) {
                            val requestId = line.key.removePrefix("user:")
                            LaunchedEffect(line.key, line.deliveryStatus) {
                                withFrameNanos { }
                                val presentation = when (line.deliveryStatus) {
                                    OutgoingMessageStatus.Sent ->
                                        com.kompi.app.diagnostics.ChatTiming.UserPresentation.Acknowledged
                                    null -> com.kompi.app.diagnostics.ChatTiming.UserPresentation.Canonical
                                    else -> com.kompi.app.diagnostics.ChatTiming.UserPresentation.Optimistic
                                }
                                com.kompi.app.diagnostics.ChatTiming.userComposed(requestId, presentation)
                                com.kompi.app.diagnostics.LocalDiagnostics.finish(
                                    com.kompi.app.diagnostics.LocalDiagnostics.Metric.LocalSendToVisible,
                                    requestId,
                                )
                            }
                        }
                        if (line.key.endsWith(":result")) {
                            val requestId = line.key.removeSuffix(":result")
                            LaunchedEffect(line.key) {
                                withFrameNanos { }
                                com.kompi.app.diagnostics.LocalDiagnostics.finish(
                                    com.kompi.app.diagnostics.LocalDiagnostics.Metric.ResponseToRender,
                                    requestId,
                                )
                            }
                        }
                        ChatMessage(
                            text = line.text,
                            displayText = line.body,
                            deliveryStatus = line.deliveryStatus, replyDetail = line.replyDetail, attachmentStage = line.attachmentDispatch,
                            connected = connectionState == "Live",
                            attachments = line.attachments,
                            fromUser = line.fromUser,
                            activity = line.isActivity,
                            messageKey = line.key,
                            animateArrivals = line.animateArrivals,
                            piDetails = line.piDetails,
                            readAloudReady = readAloudReady,
                            readingMessageKey = readingMessageKey,
                            onReadAloud = { key, spokenText ->
                                if (recording || liveActive) voiceMessage = "Stop microphone capture before reading a message aloud."
                                else onReadAloud(key, spokenText)
                            },
                            onStopReadAloud = onStopReadAloud,
                            onOpenVoiceSettings = onOpenVoiceSettings,
                        )
                        if (line.deliveryStatus == OutgoingMessageStatus.Uncertain || (line.deliveryStatus == OutgoingMessageStatus.Dispatching && connectionState != "Live"))
                            TextButton(onClick = onRetryHistory, enabled = connectionState == "Live" && canCheckHistory) { Text("Check conversation") }
                        visibleUnsentPrompts.firstOrNull { line.key == "user:${it.id}" }?.let { prompt ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { sendScope.launch { onDiscardPrompt(prompt.id) } }, enabled = submittingRevisions.isEmpty()) { Text("Discard") }
                            }
                        }
                    }
                    }
                }
            }
        }
        }
      }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().widthIn(max = 760.dp).padding(horizontal = 12.dp, vertical = 8.dp).onSizeChanged { composerOverlayHeightPx = it.height }.testTag("conversation-composer"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (draftAttachments.isNotEmpty()) {
                    Text("${draftAttachments.size} ${if (draftAttachments.size == 1) "attachment" else "attachments"} · Live voice paused", style = KompiUi.typography.labelSmall, color = KompiUi.colors.onSurfaceVariant)
                }
                if (importingAttachments || importingSharedAttachments) Text("Adding attachments…", style = KompiUi.typography.labelSmall, color = KompiUi.colors.onSurfaceVariant)
                if (voiceMessage.isNotBlank()) Text(voiceMessage, style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant, maxLines = 2)
                if (speechMessage.isNotBlank()) Text(speechMessage, style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant, maxLines = 2)
                visibleUnsentPrompts.filter { prompt -> visibleTranscript.none { it.key == "user:${prompt.id}" } }.take(3).forEach { prompt ->
                    PendingPromptNotice(
                        prompt = prompt,
                        enabled = submittingRevisions.isEmpty(),
                        onDiscard = { sendScope.launch { onDiscardPrompt(prompt.id) } },
                    )
                }
                if (visibleUnsentPrompts.size > 3) Text("${visibleUnsentPrompts.size - 3} more saved prompts in the outbox", style = KompiUi.typography.labelSmall, color = KompiUi.colors.onSurfaceVariant)
                if (draftAttachments.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().testTag("attachment-drafts")) {
                        items(draftAttachments, key = { it.uploadId }) { attachment ->
                            AttachmentDraftRow(
                                modifier = Modifier.width(280.dp),
                                attachment = attachment,
                                status = attachmentStatus[attachment.uploadId] ?: AttachmentUiStatus(),
                                onRemove = {
                                    attachmentStore.remove(attachment)
                                    draftAttachments.removeAll { it.uploadId == attachment.uploadId }
                                    attachmentStatus.remove(attachment.uploadId)
                                },
                                onRetry = {
                                    if (attachmentsSupported) sendScope.launch {
                                        attachmentStatus[attachment.uploadId] = AttachmentUiStatus(progress = 0f, working = true)
                                        try {
                                            // Retry is an explicit user action; uploading never starts from selection.
                                            onUpload(attachment) { received, total ->
                                                attachmentStatus[attachment.uploadId] = AttachmentUiStatus(progress = (received.toDouble() / total.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f), working = true)
                                            }
                                            attachmentStatus[attachment.uploadId] = AttachmentUiStatus(progress = 1f, complete = true)
                                        } catch (failure: Exception) {
                                            attachmentStatus[attachment.uploadId] = AttachmentUiStatus(error = failure.message ?: "Upload failed")
                                        }
                                    }
                                },
                                retryEnabled = attachmentsSupported && !importingAttachments && submittingRevisions.isEmpty(),
                                removeEnabled = !importingAttachments && submittingRevisions.isEmpty(),
                            )
                        }
                    }
                }
                fun toggleDictation() {
                    if (pendingCapture != null || dictationStartFence.hasRequest) return
                    when (dictationPhase) {
                        OfflineSpeechSessionPhase.Capturing -> sendScope.launch {
                            try { session?.stopAndJoin(flush = true) }
                            catch (failure: Throwable) {
                                runCatching { session?.stopAndFence(flush = false) }
                                dictationPartial = null
                                dictationOverflow = false
                                voiceMessage = "Could not finish offline speech capture: ${failure.message ?: "try again"}"
                            }
                        }
                        OfflineSpeechSessionPhase.Preparing, OfflineSpeechSessionPhase.Finalizing -> return
                        OfflineSpeechSessionPhase.Idle -> {
                            dictationPartial = null
                            dictationOverflow = false
                            if (speechModel == null) voiceMessage = "Download the selected speech model in Voice settings before using the microphone."
                            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                dictationPhase = OfflineSpeechSessionPhase.Preparing
                                val ticket = dictationStartFence.request()
                                sendScope.launch { startPttAfterCaptureHandoff(ticket) }
                            } else {
                                pendingCapture = CaptureMode.Dictation
                                micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    }
                }
                val sendEnabled = chatConfiguration != null && draftsLoaded && pendingCapture == null && dictationSendAllowed(dictationPhase, dictationPartial != null, dictationStartFence.hasRequest) && !startingNewChat && !importingAttachments && !importingSharedAttachments && (composerFieldState.text.isNotBlank() || draftAttachments.isNotEmpty()) && canSend &&
                    (draftAttachments.isEmpty() || canSendAttachments) && draftAttachments.size <= AttachmentDraftStore.MAX_DRAFT_ATTACHMENTS && composer.revision !in submittingRevisions
                val canAddAttachment = draftsLoaded && peerName != null && !liveActive && !importingAttachments && !importingSharedAttachments && draftAttachments.size < AttachmentDraftStore.MAX_DRAFT_ATTACHMENTS
                val sendAction: @Composable () -> Unit = {
                    ChatActionButton(
                        label = "Send message",
                        onClick = {
                            if (chatConfiguration == null || pendingCapture != null || !dictationSendAllowed(dictationPhase, dictationPartial != null, dictationStartFence.hasRequest)) return@ChatActionButton
                            if (liveActive) liveController.stopLocally(com.kompi.app.live.LiveLocalStopReason.Backgrounded)
                            val submitted = composer.copy(text = composerFieldState.text.toString()).snapshot()
                            val submittedAttachments = draftAttachments.toList()
                            if ((submitted.text.isNotBlank() || submittedAttachments.isNotEmpty()) && canSend && (submittedAttachments.isEmpty() || canSendAttachments) && submitted.revision !in submittingRevisions) {
                                currentDraftChanged(submitted.sourceText) // Flush any unobserved final IME edit before binding its revision.
                                val tappedRequestId = com.kompi.app.diagnostics.ChatTiming.uuid()
                                com.kompi.app.diagnostics.ChatTiming.record(com.kompi.app.diagnostics.ChatTiming.Stage.PhoneTap, request = tappedRequestId)
                                submittingRevisions.add(submitted.revision)
                                (submitScope ?: sendScope).launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                                    try {
                                        val requestId = onSend(submitted.text, submittedAttachments, { uploadId, received, total ->
                                            attachmentStatus[uploadId] = AttachmentUiStatus(progress = (received.toDouble() / total.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f), working = true)
                                        }, tappedRequestId)
                                        if (requestId != null) {
                                            if (submittedAttachments.isNotEmpty()) {
                                                draftAttachments.removeAll { item -> submittedAttachments.any { it.uploadId == item.uploadId } }
                                                submittedAttachments.forEach { attachmentStatus.remove(it.uploadId) }
                                            }
                                            val current = composer.copy(text = composerFieldState.text.toString())
                                            val nextComposer = current.clearSubmitted(submitted)
                                            if (nextComposer != current) updateComposer(nextComposer)
                                        }
                                    } catch (failure: Exception) {
                                        submittedAttachments.forEach { item -> attachmentStatus[item.uploadId] = AttachmentUiStatus(error = failure.message ?: "Upload failed") }
                                        voiceMessage = "Not queued: ${failure.message ?: "submission failed"}"
                                    } finally {
                                        submittingRevisions.remove(submitted.revision)
                                    }
                                }
                            }
                        },
                        enabled = sendEnabled,
                        tone = if (sendEnabled) ChatActionTone.Primary else ChatActionTone.Quiet,
                        visualSize = 40.dp,
                    ) {
                        ChatLucideIcon(LucideR.drawable.lucide_ic_send_horizontal, null, modifier = Modifier.size(20.dp), tint = if (sendEnabled) KompiUi.colors.onPrimary else KompiUi.colors.onSurfaceVariant)
                    }
                }
                com.kompi.app.chat.RadialComposer(
                    expanded = keyboardVisible,
                    menuExpanded = attachmentPickerExpanded,
                    onMenuExpanded = { attachmentPickerExpanded = it },
                    recording = recording,
                    micEnabled = pendingCapture == null && (!liveActive || recording) &&
                        (dictationPhase == OfflineSpeechSessionPhase.Idle || dictationPhase == OfflineSpeechSessionPhase.Capturing),
                    canStartNewChat = canSend && !importingAttachments &&
                        (independentChats || (connectionState == "Live" && phoneConversationsSupported && unsentPrompts.isEmpty())) &&
                        !startingNewChat && !recording && dictationPhase == OfflineSpeechSessionPhase.Idle && pendingCapture == null && submittingRevisions.isEmpty(),
                    canAttach = canAddAttachment,
                    onDictation = { toggleDictation() },
                    onModelSelection = { modelPickerExpanded = true; if (modelCatalog == null && !modelsLoading) onRefreshModels() },
                    modelSelectionEnabled = peerName != null,
                    modelLabel = chatConfiguration?.let { "${it.modelId} · ${it.effort}" } ?: if (peerName != null) "Choose a model to send" else "Model",
                    onNewChat = {
                        startingNewChat = true
                        sendScope.launch {
                            try {
                                onNewChat()
                                voiceMessage = ""
                            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                                voiceMessage = "Could not confirm the new chat. Reconnect and try again."
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                voiceMessage = failure.message ?: "Could not start a new chat. Reconnect and try again."
                            } finally {
                                startingNewChat = false
                            }
                        }
                    },
                    onPhoto = { photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onFile = { documentPicker.launch(arrayOf("*/*")) },
                    prompt = {
                        Column(Modifier.fillMaxWidth()) {
                            dictationPartial?.let { interim ->
                                DictationTranscript(
                                    partial = interim,
                                    copyAvailable = dictationOverflow,
                                    onCopy = {
                                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                                            ClipData.newPlainText("Offline transcript", interim.text),
                                        )
                                        voiceMessage = "Transcript copied. Paste the part you need, then send."
                                    },
                                    dismissEnabled = dictationPhase == OfflineSpeechSessionPhase.Idle,
                                    onDismiss = { dictationPartial = null; dictationOverflow = false },
                                )
                            }
                            ChatPromptField(
                                state = composerFieldState,
                                placeholder = "Message",
                                modifier = Modifier.fillMaxWidth().focusRequester(composerFocusRequester).testTag("prompt-input"),
                                enabled = peerName != null,
                            )
                        }
                    },
                    send = { sendAction() },
                )
        }
        if (!liveActive && visibleTranscript.isNotEmpty() && !followsLatestMessage.value) {
            ChatActionButton(
                label = "Jump to latest message",
                onClick = {
                    followsLatestMessage.value = true
                    sendScope.launch {
                        val lastIndex = latestTranscript.value.lastIndex
                        if (lastIndex >= 0) listState.scrollToItem(lastIndex, Int.MAX_VALUE)
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = composerOverlayHeight + 24.dp),
                tone = ChatActionTone.Soft,
                visualSize = 48.dp,
            ) {
                ChatLucideIcon(LucideR.drawable.lucide_ic_arrow_down, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
          }
        }
      }
    }
}


@Composable
private fun PendingPromptNotice(
    prompt: PendingPrompt,
    enabled: Boolean,
    onDiscard: () -> Unit,
) {
    Surface(color = KompiUi.colors.surfaceContainerLow, shape = KompiUi.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (prompt.waitingForConversation) Text("Open the original session to review this message.",
                style = KompiUi.typography.labelMedium, color = KompiUi.colors.onSurfaceVariant)
            Row(Modifier.semantics(mergeDescendants = true) { stateDescription = "Saved on this phone" }, verticalAlignment = Alignment.CenterVertically) {
                Text(prompt.text, Modifier.weight(1f), style = KompiUi.typography.bodySmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp)); ChatLucideIcon(OutgoingMessageStatus.Sending.icon, null, Modifier.size(16.dp))
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDiscard, enabled = enabled) { Text("Discard") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeersScreen(
    modifier: Modifier,
    name: String?,
    connectionState: String,
    trustedMachines: List<SecureStore.TrustedMachine>,
    connectionError: String?,
    selectedMachineId: String?,
    selectedMachine: SecureStore.TrustedMachine?,
    requestedIdleTimeoutSeconds: Int,
    effectiveIdleTimeoutSeconds: Int?,
    idleTimeoutPending: Boolean,
    idleTimeoutError: String?,
    requestedUploadRetentionSeconds: Int,
    effectiveUploadRetentionSeconds: Int?,
    uploadRetentionPending: Boolean,
    onSaveUploadRetentionSeconds: (Int) -> Unit,
    onSaveIdleTimeoutSeconds: (Int) -> Unit,
    onSelectMachine: (String) -> Unit,
    onPair: () -> Unit,
    onPastePairingCode: () -> Unit,
    onUnpair: () -> Unit,
    hostSettingsOnly: Boolean = false,
    onBack: () -> Unit = {},
) {
    var detailsMachine by remember { mutableStateOf<SecureStore.TrustedMachine?>(null) }
    var showTimeoutDetails by remember { mutableStateOf(false) }
    if (detailsMachine != null) {
        val machine = detailsMachine!!
        val idleTimeoutSeconds = if (machine.machineId == selectedMachineId) requestedIdleTimeoutSeconds else machine.idleTimeoutSeconds
        AlertDialog(
            onDismissRequest = { detailsMachine = null },
            title = { Text("Computer details") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Endpoint ID", style = KompiUi.typography.labelLarge)
                Text(machine.machineId, style = KompiUi.typography.bodySmall)
                Text(if (machine.lastSeenEpochMillis > 0) "Last connected ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(machine.lastSeenEpochMillis))}." else "No authenticated connection yet.")
                Text("Idle timeout: ${formatIdleTimeout(idleTimeoutSeconds)}")
            } },
            confirmButton = { TextButton(onClick = { detailsMachine = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done") } },
        )
    }
    if (showTimeoutDetails) AlertDialog(
        onDismissRequest = { showTimeoutDetails = false },
        title = { Text("Idle timeout") },
        text = { Text("A conversation expires after this much time without an accepted message. Running work finishes first. Saving a new value does not reset the current deadline; the computer confirms the effective setting.") },
        confirmButton = { TextButton(onClick = { showTimeoutDetails = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done") } },
    )
    Column(
        modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (hostSettingsOnly) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp).semantics { contentDescription = "Back to chat" }) {
                        ChatLucideIcon(LucideR.drawable.lucide_ic_arrow_left, contentDescription = null)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Computer settings", style = KompiUi.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(name ?: "Selected computer", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            } else {
                Text("Computers", style = KompiUi.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                Text("Manage trusted connections and availability.", style = KompiUi.typography.bodyMedium, color = KompiUi.colors.onSurfaceVariant)
            }
            if (!connectionError.isNullOrBlank()) {
                Text("Connection issue · $connectionError", style = KompiUi.typography.bodySmall, color = KompiUi.colors.error)
            }
        }
        if (!hostSettingsOnly && trustedMachines.isNotEmpty()) {
            KompiSection("Trusted computers") {
                trustedMachines.forEachIndexed { index, machine ->
                    val selected = machine.machineId == selectedMachineId
                    ListItem(
                        headlineContent = { Text(machine.displayName, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    if (selected) "Selected · idle ${formatIdleTimeout(requestedIdleTimeoutSeconds)}" else if (machine.lastSeenEpochMillis > 0) "Last connected ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(machine.lastSeenEpochMillis))}" else "Not connected yet",
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        },
                        leadingContent = { RadioButton(selected = selected, onClick = { onSelectMachine(machine.machineId) }) },
                        trailingContent = { TextButton(onClick = { detailsMachine = machine }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Details for ${machine.displayName}" }) { Text("Details") } },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(enabled = !selected, role = Role.RadioButton) { onSelectMachine(machine.machineId) }.semantics { contentDescription = "${machine.displayName}${if (selected) ", selected" else ""}" },
                    )
                    if (index < trustedMachines.lastIndex) KompiSectionDivider()
                }
            }
        }
        if (selectedMachine != null) {
            val minMinutes = SecureStore.MIN_IDLE_TIMEOUT_SECONDS / 60
            val maxMinutes = SecureStore.MAX_IDLE_TIMEOUT_SECONDS / 60
            var customMinutes by remember(selectedMachine.machineId, requestedIdleTimeoutSeconds) { mutableStateOf((requestedIdleTimeoutSeconds / 60).toString()) }
            val customSeconds = customMinutes.toIntOrNull()?.takeIf { it in minMinutes..maxMinutes }?.times(60)
            var showCustomTimeout by remember(selectedMachine.machineId) { mutableStateOf(requestedIdleTimeoutSeconds / 60 !in listOf(5, 15, 30, 60)) }
            val retentionHours = requestedUploadRetentionSeconds / 3600
            val minRetentionHours = com.kompi.app.protocol.generated.MIN_UPLOAD_RETENTION_SECONDS / 3600
            val maxRetentionHours = com.kompi.app.protocol.generated.MAX_UPLOAD_RETENTION_SECONDS / 3600
            var customRetentionHours by remember(selectedMachine.machineId, requestedUploadRetentionSeconds) { mutableStateOf(retentionHours.toString()) }
            val customRetentionSeconds = customRetentionHours.toIntOrNull()
                ?.takeIf { it in minRetentionHours..maxRetentionHours }?.times(3600)
            KompiSection("Conversation timeout") {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Idle window", style = KompiUi.typography.titleSmall)
                            Text(formatIdleTimeout(requestedIdleTimeoutSeconds), color = KompiUi.colors.onSurfaceVariant, style = KompiUi.typography.bodyMedium)
                        }
                        TextButton(onClick = { showTimeoutDetails = true }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Idle timeout details" }) { Text("Details") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 15, 30, 60).forEach { minutes ->
                            FilterChip(
                                selected = requestedIdleTimeoutSeconds == minutes * 60,
                                onClick = { customMinutes = minutes.toString(); if (requestedIdleTimeoutSeconds != minutes * 60) onSaveIdleTimeoutSeconds(minutes * 60) },
                                label = { Text("$minutes min") },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                    TextButton(onClick = { showCustomTimeout = !showCustomTimeout }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (showCustomTimeout) "Hide custom value" else "Set a custom timeout") }
                    if (showCustomTimeout) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = customMinutes,
                            onValueChange = { customMinutes = it.filter(Char::isDigit).take(maxMinutes.toString().length) },
                            label = { Text("Minutes · $minMinutes–$maxMinutes") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f).testTag("conversation-idle-timeout-minutes"),
                        )
                        Button(onClick = { customSeconds?.let(onSaveIdleTimeoutSeconds) }, enabled = customSeconds != null && customSeconds != requestedIdleTimeoutSeconds, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save") }
                    }
                    val timeoutStatus = when {
                        idleTimeoutError != null -> "Not saved · $idleTimeoutError"
                        idleTimeoutPending -> "Waiting for computer confirmation · ${formatIdleTimeout(requestedIdleTimeoutSeconds)}"
                        effectiveIdleTimeoutSeconds != null && effectiveIdleTimeoutSeconds != requestedIdleTimeoutSeconds -> "Computer still uses ${formatIdleTimeout(effectiveIdleTimeoutSeconds)}."
                        effectiveIdleTimeoutSeconds == null -> "Saved · applies when connected."
                        else -> ""
                    }
                    if (timeoutStatus.isNotBlank()) Text(timeoutStatus, color = if (idleTimeoutError != null) KompiUi.colors.error else KompiUi.colors.onSurfaceVariant, style = KompiUi.typography.bodySmall)
                }
            }
            KompiSection("Uploaded-file retention") {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Keep completed uploads for ${retentionHours} hours. Changing this applies only to future uploads; existing expiry dates do not change.", color = KompiUi.colors.onSurfaceVariant, style = KompiUi.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(24, 72, 168, 720).forEach { hours ->
                            FilterChip(
                                selected = retentionHours == hours,
                                onClick = { customRetentionHours = hours.toString(); if (requestedUploadRetentionSeconds != hours * 3600) onSaveUploadRetentionSeconds(hours * 3600) },
                                label = { Text(if (hours % 24 == 0) "${hours / 24} days" else "$hours hours") },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = customRetentionHours,
                            onValueChange = { customRetentionHours = it.filter(Char::isDigit).take(maxRetentionHours.toString().length) },
                            label = { Text("Hours · $minRetentionHours–$maxRetentionHours") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f).testTag("upload-retention-hours"),
                        )
                        Button(onClick = { customRetentionSeconds?.let(onSaveUploadRetentionSeconds) }, enabled = customRetentionSeconds != null && customRetentionSeconds != requestedUploadRetentionSeconds, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save") }
                    }
                    val retentionStatus = when {
                        idleTimeoutError != null -> "Not saved · $idleTimeoutError"
                        uploadRetentionPending -> "Waiting for computer confirmation · ${retentionHours} hours"
                        effectiveUploadRetentionSeconds != null && effectiveUploadRetentionSeconds != requestedUploadRetentionSeconds -> "Computer still uses ${effectiveUploadRetentionSeconds / 3600} hours."
                        effectiveUploadRetentionSeconds == null -> "Saved · applies when connected."
                        else -> "Confirmed · new uploads only."
                    }
                    Text(retentionStatus, color = if (idleTimeoutError != null) KompiUi.colors.error else KompiUi.colors.onSurfaceVariant, style = KompiUi.typography.bodySmall)
                }
            }
        }
        if (!hostSettingsOnly) KompiSection(if (name == null) "Add a computer" else "Add another computer") {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPair, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    ChatLucideIcon(LucideR.drawable.lucide_ic_qr_code, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Scan QR code")
                }
                OutlinedButton(onClick = onPastePairingCode, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("paste-pairing-code")) { Text("Enter pairing code") }
                if (selectedMachine != null) {
                    TextButton(
                        onClick = onUnpair,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        contentColor = KompiUi.colors.error,
                    ) { Text("Remove selected computer") }
                }
            }
        }
    }
}

private fun formatStorage(bytes: Long): String = if (bytes < 1024L * 1024L) "${bytes / 1024} KiB" else "${bytes / (1024L * 1024L)} MiB"

internal enum class AppTheme { System, Light, Dark }

/** Only this small subtree observes the high-frequency PCM meter. */
@Composable
private fun VoicePreviewVisualizer(phases: kotlinx.coroutines.flow.StateFlow<KokoroPlaybackPhase>, requestId: String?) {
    if (requestId == null) return
    val playbackPhase by phases.collectAsState()
    val playing = (playbackPhase as? KokoroPlaybackPhase.Playing)?.takeIf { it.requestId == requestId }
    val previewState = when (val phase = playbackPhase) {
        is KokoroPlaybackPhase.Preparing -> if (phase.requestId == requestId) "Preparing voice preview" else null
        is KokoroPlaybackPhase.Playing -> if (phase.requestId == requestId) "Playing voice preview" else null
        is KokoroPlaybackPhase.Draining -> if (phase.requestId == requestId) "Finishing voice preview" else null
        KokoroPlaybackPhase.Idle -> null
    } ?: return
    MonoWaveVisualizer(
        level = playing?.let { AudioLevel.normalized(it.pcmRms) } ?: AudioLevel.Quiet,
        waveform = playing?.waveform ?: AudioWaveform.Silence,
        active = playing != null,
        accessibleState = previewState,
        modifier = Modifier.fillMaxWidth().height(96.dp),
    )
}
@Composable
private fun SettingsScreen(modifier: Modifier, theme: AppTheme, kokoroState: KokoroTtsState, ttsEnabled: Boolean, whistleModel: InstalledWhistleModel?, whistleModelChecked: Boolean, whistleDownloading: Boolean, modelStatus: String, whistleLanguage: String, speechMessage: String, previewRequestId: String?, playbackPhases: kotlinx.coroutines.flow.StateFlow<KokoroPlaybackPhase>, availableKokoroVoices: List<KokoroVoice>, selectedKokoroVoice: KokoroVoice?, onSelectKokoroVoice: (KokoroVoiceId) -> Unit, onTheme: (AppTheme) -> Unit, onTtsEnabled: (Boolean) -> Unit, onStopSpeech: () -> Unit, onPreviewVoice: () -> Unit, onWhistleLanguage: (String) -> Unit, onDownloadKokoro: () -> Unit, onCancelKokoroDownload: () -> Unit, onDownloadWhistle: () -> Unit, thinkingSpeechEnabled: Boolean, thinkingSpeechPrefix: String, onThinkingSpeechEnabled: (Boolean) -> Unit, onThinkingSpeechPrefix: (String) -> Unit, whistleVocabulary: WhistleVocabulary, onWhistleVocabulary: (WhistleVocabulary) -> Unit, speechInputEngine: SpeechInputEngine, parakeetState: ParakeetInstallState, onSpeechInputEngine: (SpeechInputEngine) -> Unit, onDownloadParakeet: () -> Unit, onCancelParakeetDownload: () -> Unit, chatReadAloudEnabled: Boolean, onChatReadAloudEnabled: (Boolean) -> Unit) {
    val context = LocalContext.current
    var route by remember { mutableStateOf(BluetoothAudioRoute.current(context)) }
    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> route = if (granted) BluetoothAudioRoute.routeToBluetooth(context) else "Bluetooth permission denied" }
    var languageMenu by remember { mutableStateOf(false) }
    var voiceMenuExpanded by remember { mutableStateOf(false) }
    val vocabularyField = rememberTextFieldState(whistleVocabulary.text)
    val vocabularyDraft = vocabularyField.text.toString()
    val parsedVocabulary = remember(vocabularyDraft) { WhistleVocabulary.parse(vocabularyDraft) }
    val vocabularyError = parsedVocabulary.exceptionOrNull()?.message
    val vocabularyChanged = parsedVocabulary.getOrNull()?.text != whistleVocabulary.text
    // The field owns IME edits; feeding persisted values back can overwrite newer keystrokes.
    val thinkingPrefixField = rememberTextFieldState(thinkingSpeechPrefix)
    val currentPrefixChanged by rememberUpdatedState(onThinkingSpeechPrefix)
    LaunchedEffect(thinkingPrefixField) {
        snapshotFlow { thinkingPrefixField.text.toString() }.collect { prefix ->
            if (prefix.length > 39) thinkingPrefixField.edit { replace(39, length, "") }
            else currentPrefixChanged(prefix)
        }
    }
    val kokoroStatus = when (kokoroState) {
        KokoroTtsState.NotDownloaded -> "Not installed"
        is KokoroTtsState.Downloading -> "Downloading · ${(kokoroState.progress * 100).toInt().coerceIn(0, 100)}% · ${formatStorage(kokoroState.receivedBytes)} of ${formatStorage(kokoroState.totalBytes)}"
        KokoroTtsState.Initializing -> "Setting up…"
        is KokoroTtsState.Ready -> "Ready · ${formatStorage(kokoroState.storageBytes)}"
        is KokoroTtsState.Failed -> kokoroState.message
        KokoroTtsState.Closed -> "Unavailable"
    }
    Column(
        modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Settings", style = KompiUi.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Text("Voice and appearance", style = KompiUi.typography.bodyMedium, color = KompiUi.colors.onSurfaceVariant)
        }
        KompiSection("Chat") {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Read replies aloud", style = KompiUi.typography.titleMedium)
                    Text("Automatically speak replies in Chat. Live voice is unchanged.", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
                }
                Switch(checked = chatReadAloudEnabled, onCheckedChange = onChatReadAloudEnabled,
                    modifier = Modifier.semantics { contentDescription = "Read Chat replies aloud" })
            }
        }
        KompiSection("Reply voice") {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KompiIconBadge(LucideR.drawable.lucide_ic_audio_lines)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Kokoro", style = KompiUi.typography.titleMedium)
                        Text(selectedKokoroVoice?.let { "${it.languageLabel} · ${it.displayName} · about 295 MB" } ?: "Voice selection · about 295 MB", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
                    }
                    Switch(checked = ttsEnabled, onCheckedChange = onTtsEnabled, modifier = Modifier.semantics { contentDescription = "Enable Kokoro reply speech" })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Kokoro voice", style = KompiUi.typography.titleSmall)
                        Text(
                            when {
                                kokoroState !is KokoroTtsState.Ready -> "Available after Kokoro is installed"
                                availableKokoroVoices.size <= 1 -> "Only voice available in this model"
                                else -> "New replies use this voice"
                            },
                            style = KompiUi.typography.bodySmall,
                            color = KompiUi.colors.onSurfaceVariant,
                        )
                    }
                    Box {
                        TextButton(
                            onClick = { voiceMenuExpanded = true },
                            enabled = kokoroState is KokoroTtsState.Ready && availableKokoroVoices.size > 1,
                            modifier = Modifier.heightIn(min = 48.dp).semantics {
                                contentDescription = "Select Kokoro voice"
                                stateDescription = selectedKokoroVoice?.displayName ?: "No Kokoro voice selected"
                            },
                        ) {
                            Text(selectedKokoroVoice?.displayName ?: "Unavailable", maxLines = 1)
                            ChatLucideIcon(LucideR.drawable.lucide_ic_chevron_down, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = voiceMenuExpanded,
                            onDismissRequest = { voiceMenuExpanded = false },
                            modifier = Modifier.heightIn(max = 360.dp),
                        ) {
                            availableKokoroVoices.forEach { voice ->
                                DropdownMenuItem(
                                    text = {
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(voice.displayName, style = KompiUi.typography.bodyMedium)
                                            Text(voice.languageLabel, style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
                                        }
                                    },
                                    trailingIcon = {
                                        if (voice.id == selectedKokoroVoice?.id) {
                                            ChatLucideIcon(LucideR.drawable.lucide_ic_check, contentDescription = null, tint = KompiUi.colors.primary)
                                        }
                                    },
                                    onClick = {
                                        onSelectKokoroVoice(voice.id)
                                        voiceMenuExpanded = false
                                    },
                                    modifier = Modifier.semantics {
                                        contentDescription = "${voice.displayName}, ${voice.languageLabel}"
                                        stateDescription = if (voice.id == selectedKokoroVoice?.id) "Selected" else "Not selected"
                                    },
                                )
                            }
                        }
                    }
                }
                Text(kokoroStatus, color = if (kokoroState is KokoroTtsState.Failed) KompiUi.colors.error else KompiUi.colors.onSurfaceVariant, style = KompiUi.typography.bodySmall)
                Button(
                    onClick = onDownloadKokoro,
                    enabled = kokoroState !is KokoroTtsState.Downloading && kokoroState !is KokoroTtsState.Initializing && kokoroState !is KokoroTtsState.Ready && kokoroState != KokoroTtsState.Closed,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(when (kokoroState) {
                        KokoroTtsState.NotDownloaded -> "Download · 295 MB"
                        is KokoroTtsState.Downloading -> "Downloading…"
                        KokoroTtsState.Initializing -> "Setting up…"
                        is KokoroTtsState.Failed -> if (kokoroState.retryable) "Retry download" else "Unavailable"
                        is KokoroTtsState.Ready -> "Installed"
                        KokoroTtsState.Closed -> "Unavailable"
                    })
                }
                if (kokoroState is KokoroTtsState.Downloading) {
                    LinearProgressIndicator(progress = { kokoroState.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = onCancelKokoroDownload, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel model download") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onPreviewVoice, enabled = ttsEnabled && kokoroState is KokoroTtsState.Ready, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Preview voice") }
                    TextButton(onClick = onStopSpeech, modifier = Modifier.heightIn(min = 48.dp)) { Text("Stop") }
                }
                VoicePreviewVisualizer(playbackPhases, previewRequestId)
                if (speechMessage.isNotBlank()) Text(speechMessage, color = KompiUi.colors.onSurfaceVariant, style = KompiUi.typography.bodySmall)
            }
        }
        KompiSection("While waiting") {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Speak activity titles", style = KompiUi.typography.titleSmall)
                        Text("Short thinking titles play before reply audio.", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
                    }
                    Switch(checked = thinkingSpeechEnabled, onCheckedChange = onThinkingSpeechEnabled,
                        modifier = Modifier.semantics { contentDescription = "Speak activity titles while waiting" })
                }
                OutlinedTextField(
                    state = thinkingPrefixField,
                    label = { Text("Activity speech prefix") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Example: ${thinkingSpeechPrefix.trim()} Reading activities.md", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
            }
        }
        KompiSection("Assistant activation") { AssistantSettings() }
        KompiSection("Offline voice input") {
            SpeechInputSettings(speechInputEngine, parakeetState, onSpeechInputEngine, onDownloadParakeet, onCancelParakeetDownload)
            if (speechInputEngine == SpeechInputEngine.Whistle) Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Whistle", style = KompiUi.typography.titleMedium)
                        Text(if (whistleModel == null) "Not installed · 16.9 MB" else "Ready · ${whistleModel.file.length() / (1024 * 1024)} MB", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
                    }
                    ChatLucideIcon(LucideR.drawable.lucide_ic_mic, contentDescription = null, tint = KompiUi.colors.onSurfaceVariant)
                }
                Button(
                    onClick = onDownloadWhistle,
                    enabled = whistleModelChecked && whistleModel == null && !whistleDownloading && WhistleBackend.isSupportedOnThisDevice(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(when { !whistleModelChecked -> "Checking Whistle model…"; whistleDownloading -> "Downloading Whistle…"; whistleModel != null -> "Whistle installed"; else -> "Download Whistle · 16.9 MB" })
                }
                if (!WhistleBackend.isSupportedOnThisDevice()) Text("Whistle native runtime is unavailable for this device ABI.", color = KompiUi.colors.error, style = KompiUi.typography.bodySmall)
                if (modelStatus.isNotBlank()) Text(modelStatus, color = KompiUi.colors.onSurfaceVariant, style = KompiUi.typography.bodySmall)
                KompiSectionDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Recognition language", style = KompiUi.typography.titleSmall)
                        Text(when (whistleLanguage) {
                            "auto" -> "Auto-detect"
                            "en" -> "English"; "de" -> "German"; "fr" -> "French"; "es" -> "Spanish"
                            "it" -> "Italian"; "nl" -> "Dutch"; "pl" -> "Polish"
                            else -> "Auto-detect"
                        }, style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
                    }
                    Box {
                        TextButton(onClick = { languageMenu = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Change")
                            ChatLucideIcon(LucideR.drawable.lucide_ic_chevron_down, contentDescription = null)
                        }
                        DropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
                            listOf("auto" to "Auto-detect", "en" to "English", "de" to "German", "fr" to "French", "es" to "Spanish", "it" to "Italian", "nl" to "Dutch", "pl" to "Polish").forEach { (code, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { onWhistleLanguage(code); languageMenu = false })
                            }
                        }
                    }
                }
            }
        }
        if (speechInputEngine == SpeechInputEngine.Whistle) KompiSection("Vocabulary hints") {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Help Whistle recognize names, project names and technical terms. Enter one word or phrase per line.", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
                OutlinedTextField(
                    state = vocabularyField,
                    label = { Text("Words and phrases") },
                    placeholder = { Text("Kompi\nProject Atlas\nCompose") },
                    minLines = 3,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    isError = vocabularyError != null,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Whistle vocabulary hints, one word or phrase per line" },
                )
                Text("Up to ${WhistleVocabulary.MAX_HINTS} hints, ${WhistleVocabulary.MAX_HINT_CHARACTERS} characters each and ${WhistleVocabulary.MAX_CHARACTERS} characters total. Blank lines and exact duplicates are ignored.", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
                if (vocabularyError != null) Text(vocabularyError, style = KompiUi.typography.bodySmall, color = KompiUi.colors.error)
                Button(
                    onClick = { parsedVocabulary.getOrNull()?.let(onWhistleVocabulary) },
                    enabled = parsedVocabulary.isSuccess && vocabularyChanged,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Save hints") }
                Text(if (vocabularyChanged) "Unsaved changes" else if (whistleVocabulary.size == 0) "No vocabulary hints saved" else "${whistleVocabulary.size} vocabulary hints saved", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
                Text("Stored only on this phone. Saved hints apply to the next recording in both dictation and Live. They guide recognition, not automatic replacements. Clear the field and save to remove all hints.", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
            }
        }
        KompiSection("Audio route") {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(route, style = KompiUi.typography.bodyMedium, color = KompiUi.colors.onSurfaceVariant)
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    else route = BluetoothAudioRoute.routeToBluetooth(context)
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Use Bluetooth headset") }
                OutlinedButton(onClick = { route = BluetoothAudioRoute.useSystemRoute(context) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Use system audio route") }
            }
        }
        KompiSection("Appearance") {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTheme.entries.forEach { option ->
                    FilterChip(
                        selected = theme == option,
                        onClick = { onTheme(option) },
                        label = { Text(option.name, maxLines = 1) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "${option.name} theme" },
                    )
                }
            }
        }
    }
}

@Composable
internal fun KompiTheme(mode: AppTheme, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = when (mode) { AppTheme.System -> androidx.compose.foundation.isSystemInDarkTheme(); AppTheme.Light -> false; AppTheme.Dark -> true }
    val colors = if (dark) KompiColors(
        primary = androidx.compose.ui.graphics.Color(0xFFEF6408), onPrimary = androidx.compose.ui.graphics.Color(0xFF17100B),
        primaryContainer = androidx.compose.ui.graphics.Color(0xFF3D2518), onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFFFDCC7),
        secondary = androidx.compose.ui.graphics.Color(0xFFB3B3B3), secondaryContainer = androidx.compose.ui.graphics.Color(0xFF252525), onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFF0F0F0),
        background = androidx.compose.ui.graphics.Color(0xFF0B0B0B), onBackground = androidx.compose.ui.graphics.Color(0xFFF5F5F5),
        surface = androidx.compose.ui.graphics.Color(0xFF161616), onSurface = androidx.compose.ui.graphics.Color(0xFFF5F5F5),
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFF252525), onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFB8B8B8),
        surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF161616), surfaceContainerLowest = androidx.compose.ui.graphics.Color(0xFF101010),
        outline = androidx.compose.ui.graphics.Color(0xFF777777), outlineVariant = androidx.compose.ui.graphics.Color(0xFF353535),
        tertiary = androidx.compose.ui.graphics.Color(0xFFBCE29F), error = androidx.compose.ui.graphics.Color(0xFFFFB8AF),
        composerSurface = androidx.compose.ui.graphics.Color(0xFF191919), onComposer = androidx.compose.ui.graphics.Color(0xFFF5F5F5), composerMuted = androidx.compose.ui.graphics.Color(0xFFBDBDBD),
    ) else KompiColors(
        primary = androidx.compose.ui.graphics.Color(0xFF38A414), onPrimary = androidx.compose.ui.graphics.Color(0xFF111B0D),
        primaryContainer = androidx.compose.ui.graphics.Color(0xFFE8F3DB), onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF1B300F),
        secondary = androidx.compose.ui.graphics.Color(0xFF626262), secondaryContainer = androidx.compose.ui.graphics.Color(0xFFECECEC), onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF191919),
        background = androidx.compose.ui.graphics.Color(0xFFF7F7F7), onBackground = androidx.compose.ui.graphics.Color(0xFF191919),
        surface = androidx.compose.ui.graphics.Color.White, onSurface = androidx.compose.ui.graphics.Color(0xFF191919),
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFFECECEC), onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF626262),
        surfaceContainerLow = androidx.compose.ui.graphics.Color.White, surfaceContainerLowest = androidx.compose.ui.graphics.Color(0xFFFDFDFD),
        outline = androidx.compose.ui.graphics.Color(0xFF858585), outlineVariant = androidx.compose.ui.graphics.Color(0xFFD7D7D7),
        tertiary = androidx.compose.ui.graphics.Color(0xFF286528), error = androidx.compose.ui.graphics.Color(0xFFB32B2B),
        composerSurface = androidx.compose.ui.graphics.Color(0xFF202020), onComposer = androidx.compose.ui.graphics.Color(0xFFF5F5F5), composerMuted = androidx.compose.ui.graphics.Color(0xFFBDBDBD),
    )
    val style = KompiStyle(
        colors = colors,
        typography = defaultKompiTypography(),
        shapes = KompiShapes(RoundedCornerShape(8.dp), RoundedCornerShape(12.dp), RoundedCornerShape(16.dp), RoundedCornerShape(24.dp), RoundedCornerShape(32.dp)),
    )
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    KompiStyleProvider(style, content)
}
