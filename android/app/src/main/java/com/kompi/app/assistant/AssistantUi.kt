package com.kompi.app.assistant

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kompi.app.PiRpcDialog
import com.kompi.app.live.LiveTalkScreen
import com.kompi.app.ui.KompiAlertDialog
import com.kompi.app.ui.KompiButton
import com.kompi.app.ui.KompiSectionSurface
import com.kompi.app.ui.KompiSwitch
import com.kompi.app.ui.KompiText
import com.kompi.app.ui.KompiTextButton
import com.kompi.app.ui.KompiUi
import com.kompi.app.assistant.push.UnifiedPushRepository
import com.kompi.app.assistant.push.UnifiedPushSettings

@Composable
internal fun AssistantSettings() {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("assistantEnabled", false)) }
    var confirmEnable by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var screenContextEnabled by remember { mutableStateOf(AssistantScreenContext.enabled(context)) }
    var notificationsEnabled by remember { mutableStateOf(AgentNotifications.enabled(context)) }
    var replyNotificationsEnabled by remember { mutableStateOf(ReplyNotifications.enabled(context)) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsEnabled = granted
        prefs.edit().putBoolean(AgentNotifications.PREFERENCE, granted).apply()
        if (!granted) {
            UnifiedPushRepository.get(context).disableAll()
            status = "Allow notifications in Android settings to receive agent messages."
        }
    }
    var revision by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val selected = remember(revision) { VoiceInteractionService.isActiveService(context, ComponentName(context, KompiVoiceInteractionService::class.java)) }
    val roleRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        revision++
        status = if (VoiceInteractionService.isActiveService(context, ComponentName(context, KompiVoiceInteractionService::class.java))) null
            else "If Kompi was not offered, use Open Android assistant settings below to choose it."
    }
    fun selectAssistant() {
        val role = if (Build.VERSION.SDK_INT >= 29) context.getSystemService(RoleManager::class.java) else null
        val intent = if (role != null && role.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && !role.isRoleHeld(RoleManager.ROLE_ASSISTANT)) role.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
            else Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
        runCatching { roleRequest.launch(intent) }.onFailure { status = "Open Android Settings → Apps → Default apps → Digital assistant, and choose Kompi." }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) { status = null; selectAssistant() }
        else status = "Microphone, Nearby devices and notifications must be allowed before starting the earbud assistant."
    }
    if (confirmEnable) KompiAlertDialog(
        onDismissRequest = { confirmEnable = false },
        title = { KompiText("Allow locked-phone assistant access?") },
        text = { KompiText("An assistant gesture will start Live with your selected computer, including while locked. It can run with the screen off or in the background until you press Stop. Anyone who can trigger or speak into this session could use your paired coding agent and its tools. There is no always-listening hotword.") },
        confirmButton = { KompiButton(onClick = { prefs.edit().putBoolean("assistantEnabled", true).apply(); enabled = true; confirmEnable = false }) { KompiText("Enable") } },
        dismissButton = { KompiTextButton(onClick = { confirmEnable = false }) { KompiText("Cancel") } },
    )
    KompiSectionSurface {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            KompiText("Earbud assistant", style = KompiUi.typography.titleMedium)
            KompiText("Use Android's assistant gesture to start Kompi Live with the selected computer. Your selected recognition engine and Kokoro voice are reused. Whistle-specific hints apply only when Whistle is selected.", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
            KompiSwitch(checked = enabled, modifier = Modifier.semantics { contentDescription = "Enable earbud assistant, including locked-phone and background access" }, onCheckedChange = { value ->
                if (value) confirmEnable = true
                else { enabled = false; prefs.edit().putBoolean("assistantEnabled", false).apply(); AssistantConversationService.stop(context) }
            })
            KompiText(if (enabled) "Locked-phone and screen-off use enabled. Stop from the ongoing notification or Live screen." else "Off · assistant gestures do not start the microphone.", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
            KompiButton(onClick = {
                val needed = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                if (needed.isEmpty()) selectAssistant() else permissions.launch(needed.toTypedArray())
            }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                KompiText(if (selected) "Manage default assistant" else "Set up default assistant")
            }
            KompiTextButton(onClick = { roleRequest.launch(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }, modifier = Modifier.heightIn(min = 48.dp)) { KompiText("Open Android assistant settings") }
            KompiText(if (selected) "Kompi is the selected Android assistant." else "Kompi is not the selected Android assistant.", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
            KompiText("In Nothing X, map a supported earbud gesture to Voice Assistant. Android/OEM support and the actual gesture still need verification on your phone. Pair a computer and download both voice models first.", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
            KompiText("Screen context", style = KompiUi.typography.titleMedium)
            KompiSwitch(checked = screenContextEnabled, modifier = Modifier.semantics { contentDescription = "Collect screen text when invoking Kompi" }, onCheckedChange = { value ->
                screenContextEnabled = value
                prefs.edit().putBoolean(AssistantScreenContext.PREFERENCE, value).apply()
                AssistantScreenContext.clear()
                KompiVoiceInteractionService.refreshScreenContextPolicy(context)
            })
            KompiText("Collect available screen text when you invoke Kompi while unlocked. To share it without asking each time, also allow it for the selected computer under Phone access. No screenshots or editable fields; other sensitive text may be included.", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
            KompiText("Agent notifications", style = KompiUi.typography.titleMedium)
            KompiSwitch(checked = notificationsEnabled, modifier = Modifier.semantics { contentDescription = "Allow paired agent notifications" }, onCheckedChange = { value ->
                if (value && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    notificationsEnabled = value
                    prefs.edit().putBoolean(AgentNotifications.PREFERENCE, value).apply()
                    if (!value) UnifiedPushRepository.get(context).disableAll()
                }
            })
            KompiText("Agent notifications require this opt-in and Android notification permission. Text is hidden on the lock screen by default.", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
            if (notificationsEnabled) {
                KompiText("Reply notifications", style = KompiUi.typography.titleMedium)
                KompiSwitch(checked = replyNotificationsEnabled, modifier = Modifier.semantics { contentDescription = "Notify when a reply is ready" }, onCheckedChange = { value ->
                    replyNotificationsEnabled = value
                    prefs.edit().putBoolean(ReplyNotifications.PREFERENCE, value).apply()
                })
                KompiText("Get a private alert when a reply finishes while Kompi is closed or in the background. The alert never includes chat text.", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
            }
            UnifiedPushSettings(refreshKey = revision)
            com.kompi.app.assistant.native.NativeAccessSettings()
            status?.let { KompiText(it, style = KompiUi.typography.bodySmall, color = KompiUi.colors.error) }
        }
    }
}

@Composable
internal fun AssistantSessionScreen() {
    val context = LocalContext.current
    val snapshot by AssistantCoordinator.snapshot.collectAsState()
    val host by AssistantCoordinator.hostName.collectAsState()
    val connectionState by AssistantCoordinator.connectionState.collectAsState()
    val dialogs by AssistantCoordinator.dialogs.collectAsState()
    val error by AssistantCoordinator.dialogError.collectAsState()
    val androidTools by AssistantCoordinator.androidTools.collectAsState()
    BackHandler { AssistantConversationService.leaveForApp(context) }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        com.kompi.app.ui.ChatAtmosphere(Modifier.matchParentSize())
        com.kompi.app.ui.KompiScaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            topBar = {
                com.kompi.app.ui.ConversationHeader(
                    peerId = host, hostName = host, state = connectionState, session = null,
                )
            },
        ) { insets ->
        com.kompi.app.live.LiveSessionContent(
            snapshot = snapshot,
            onEnd = { AssistantConversationService.leaveForApp(context) },
            onStart = { AssistantConversationService.startLive() },
            onRetry = { AssistantConversationService.retry() },
            onInterrupt = { AssistantConversationService.interrupt() },
            onToggleMicrophoneMute = AssistantConversationService::toggleMicrophoneMute,
            onToggleOutputMute = AssistantConversationService::toggleOutputMute,
            modifier = Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding(),
            onOpenVoiceSettings = { AssistantConversationService.leaveForApp(context, "settings") },
        )
        }
    }
    androidTools.firstOrNull { it.tool != com.kompi.app.transport.AndroidToolKind.SendNotification }?.let { request ->
        androidx.compose.runtime.key(request) {
            AndroidToolHandler(request) { activity -> AssistantConversationService.executeForegroundAndroidTool(request, activity) }
        }
    }
    dialogs.firstOrNull()?.let { request ->
        PiRpcDialog(request, error) { answer -> AssistantConversationService.answer(request, answer) }
    }
}
