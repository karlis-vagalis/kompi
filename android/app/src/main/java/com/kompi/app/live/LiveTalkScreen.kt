package com.kompi.app.live

import android.animation.ValueAnimator
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import com.composables.icons.lucide.R as LucideR
import com.kompi.app.chat.AssistantMarkdown
import com.kompi.app.chat.ChatLucideIcon
import com.kompi.app.ui.KompiButton as Button
import com.kompi.app.ui.KompiOutlinedButton as OutlinedButton
import com.kompi.app.ui.KompiSurface as Surface
import com.kompi.app.ui.KompiText as Text
import com.kompi.app.ui.KompiUi

/** Actual selected-decoder words, with the replaceable tail quieter than committed text. */
@Composable
private fun LiveTranscript(caption: LiveCaption, modifier: Modifier = Modifier) {
    val transcript = buildAnnotatedString {
        withStyle(SpanStyle(color = KompiUi.colors.onSurface)) {
            append(caption.committedText)
        }
        if (caption.pendingText.isNotBlank()) {
            if (caption.committedText.isNotBlank()) append(' ')
            withStyle(SpanStyle(color = KompiUi.colors.onSurfaceVariant, fontWeight = FontWeight.Normal)) {
                append(caption.pendingText)
            }
        }
    }
    Text(
        transcript,
        modifier = modifier,
        style = KompiUi.typography.headlineSmall.copy(
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Normal,
        ),
    )
}

@Composable
private fun ThinkingTitle(title: String, spoken: Boolean, onSpokenBounds: (Rect) -> Unit) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    fun reportBounds() {
        val layout = coordinates?.takeIf { it.isAttached && spoken } ?: return
        val position = layout.positionInRoot()
        onSpokenBounds(Rect(position.x, position.y, position.x + layout.size.width, position.y + layout.size.height))
    }
    LaunchedEffect(spoken, coordinates) { reportBounds() }
    Text(
        "> $title",
        Modifier.fillMaxWidth()
            .background(if (spoken) KompiUi.colors.primary.copy(alpha = 0.20f) else androidx.compose.ui.graphics.Color.Transparent)
            .padding(vertical = 2.dp).onGloballyPositioned { coordinates = it; reportBounds() },
        style = KompiUi.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = KompiUi.colors.onSurfaceVariant,
    )
}

private data class SpokenScrollAnchor(val owner: LiveReadAlong, val contentCenterY: Float)

/** A quiet, audio-led workspace. The underlying conversation remains mounted and durable. */
@Composable
fun LiveTalkScreen(
    snapshot: LiveConversationSnapshot,
    onEnd: () -> Unit,
    onStart: () -> Unit,
    onInterrupt: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleMicrophoneMute: () -> Unit = {},
    onToggleOutputMute: () -> Unit = {},
    onOpenVoiceSettings: (() -> Unit)? = null,
) {
    val stateLabel = when (snapshot.state) {
        LiveConversationState.Idle -> "Idle"
        LiveConversationState.Preparing -> "Preparing"
        LiveConversationState.Listening -> if (snapshot.microphoneMuted) "Microphone muted" else "Listening"
        LiveConversationState.Recognizing -> "Recognizing speech"
        is LiveConversationState.Waiting -> "Waiting for reply"
        is LiveConversationState.Speaking -> "Speaking"
        LiveConversationState.Interrupted -> "Interrupted"
        LiveConversationState.Paused -> "Paused"
        is LiveConversationState.Failure -> "Voice unavailable"
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val openSettings = onOpenVoiceSettings ?: {
        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:${context.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    val recoverable = snapshot.recoverableTurn
    val canStart = snapshot.state == LiveConversationState.Idle || snapshot.state == LiveConversationState.Paused ||
        snapshot.state == LiveConversationState.Interrupted || (snapshot.state is LiveConversationState.Failure && recoverable == null)
    val hasLiveSession = snapshot.state != LiveConversationState.Idle && snapshot.state != LiveConversationState.Paused &&
        snapshot.state != LiveConversationState.Interrupted && snapshot.state !is LiveConversationState.Failure
    val listening = snapshot.state == LiveConversationState.Listening
    val speaking = snapshot.state is LiveConversationState.Speaking
    val activeAudio = (!snapshot.microphoneMuted && (listening || snapshot.state == LiveConversationState.Recognizing)) || speaking
    val canInterrupt = snapshot.state is LiveConversationState.Waiting || speaking ||
        (snapshot.state == LiveConversationState.Preparing && snapshot.assistantText.isNotBlank())
    val failure = (snapshot.state as? LiveConversationState.Failure)?.message
    val capturingWords = snapshot.inputCaptionActive ||
        ((listening || snapshot.state == LiveConversationState.Recognizing) && snapshot.caption.visibleText.isNotBlank())
    val showAgentReply = snapshot.assistantText.isNotBlank() && !capturingWords && recoverable == null
    val inputStatusText = when (val status = snapshot.transcriptionStatus) {
        is LiveTranscriptionStatus.Preparing -> "Preparing ${status.engine.label}…"
        is LiveTranscriptionStatus.Utterance -> when {
            status.phase == LiveTranscriptionStatus.Phase.Finalizing -> "Finishing transcription…"
            status.engine == com.kompi.app.audio.SpeechInputEngine.Parakeet && !snapshot.inputCaptionActive ->
                "Listening · captions may take a few seconds"
            else -> null
        }
        else -> null
    }
    LaunchedEffect(snapshot.caption, snapshot.inputCaptionActive) {
        if (snapshot.inputCaptionActive) android.util.Log.i("KompiLiveAsr",
            "phase=caption_composed committed_characters=${snapshot.caption.committedText.length} pending_characters=${snapshot.caption.pendingText.length}")
    }
    LaunchedEffect(snapshot.replyRequestId, snapshot.latestActivity) {
        if (snapshot.latestActivity != null) snapshot.replyRequestId?.let {
            com.kompi.app.diagnostics.SpeechAudit.record(it, stage = "live_activity_composed")
        }
    }
    LaunchedEffect(snapshot.replyRequestId, showAgentReply) {
        if (showAgentReply) snapshot.replyRequestId?.let {
            com.kompi.app.diagnostics.SpeechAudit.record(it, stage = "live_reply_composed")
        }
    }
    val scroll = rememberScrollState()
    val dragging by scroll.interactionSource.collectIsDraggedAsState()
    var viewportTop by remember { mutableFloatStateOf(0f) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    var contentHeight by remember { mutableIntStateOf(0) }
    var anchor by remember { mutableStateOf<SpokenScrollAnchor?>(null) }
    var manuallyScrolledPhrase by remember { mutableStateOf<LiveReadAlong?>(null) }
    var followInputTail by remember { mutableStateOf(true) }
    val inputUtterance = (snapshot.transcriptionStatus as? LiveTranscriptionStatus.Utterance)?.id
    val viewportDp = with(LocalDensity.current) { viewportHeight.toDp() }
    val edgePadding = if (contentHeight > viewportHeight) viewportDp / 2 else 0.dp
    fun trackSpoken(owner: LiveReadAlong, bounds: Rect) {
        if (viewportHeight > 0) anchor = SpokenScrollAnchor(owner, scroll.value + bounds.center.y - viewportTop)
    }
    LaunchedEffect(snapshot.replyRequestId) {
        anchor = null
        manuallyScrolledPhrase = null
        scroll.scrollTo(0)
    }
    LaunchedEffect(capturingWords, inputUtterance) {
        if (capturingWords) followInputTail = true
    }
    LaunchedEffect(dragging) {
        if (dragging) {
            manuallyScrolledPhrase = snapshot.readAlong
            if (capturingWords) followInputTail = false
        }
    }
    LaunchedEffect(capturingWords, snapshot.caption, followInputTail, dragging) {
        if (!capturingWords || !followInputTail || dragging) return@LaunchedEffect
        // Follow actual recognition growth, never animate invented words. A deliberate scroll
        // suspends following until the next utterance so earlier words remain readable.
        snapshotFlow { scroll.maxValue }.distinctUntilChanged().collectLatest { scroll.scrollTo(it) }
    }
    LaunchedEffect(snapshot.readAlong, snapshot.replyRequestId, dragging, capturingWords) {
        if (capturingWords) return@LaunchedEffect
        val spoken = snapshot.readAlong ?: return@LaunchedEffect
        if (dragging || manuallyScrolledPhrase == spoken) return@LaunchedEffect
        snapshotFlow {
            anchor?.takeIf { it.owner == spoken && viewportHeight > 0 }?.let {
                (it.contentCenterY - viewportHeight / 2f).roundToInt().coerceIn(0, scroll.maxValue)
            }
        }.filterNotNull().distinctUntilChanged { previous, next -> abs(previous - next) <= 2 }
            .collectLatest { target ->
                withFrameNanos { }
                if (abs(scroll.value - target) > 2) {
                    if (ValueAnimator.areAnimatorsEnabled()) scroll.animateScrollTo(target, tween(220))
                    else scroll.scrollTo(target)
                }
            }
    }

    Surface(
        modifier = modifier.semantics {
            contentDescription = "Live voice"
            stateDescription = stateLabel
        },
        color = androidx.compose.ui.graphics.Color.Transparent,
    ) {
        Column(
            Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned {
                viewportTop = it.positionInRoot().y
                viewportHeight = it.size.height
            }) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Spacer(Modifier.height(edgePadding))
            Column(
                Modifier.fillMaxWidth().heightIn(min = viewportDp).onSizeChanged { contentHeight = it.height },
                verticalArrangement = Arrangement.Center,
            ) {
                MonoWaveVisualizer(
                    level = snapshot.visualizerLevel,
                    waveform = snapshot.visualizerWaveform,
                    active = activeAudio,
                    accessibleState = stateLabel,
                    modifier = Modifier.fillMaxWidth(),
                )
            if (!capturingWords) {
                val spokenTitle = (snapshot.readAlong as? LiveReadAlong.Thinking)?.title
                val activities = listOfNotNull(snapshot.latestActivity, spokenTitle).distinctBy { it.lowercase() }
                if (activities.isNotEmpty()) {
                    Column(Modifier.widthIn(max = 280.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(top = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        activities.forEach { title ->
                            ThinkingTitle(title, spokenTitle?.equals(title, ignoreCase = true) == true) { bounds ->
                                spokenTitle?.let { trackSpoken(LiveReadAlong.Thinking(it), bounds) }
                            }
                        }
                    }
                }
            }
            if (showAgentReply) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AssistantMarkdown(
                    messageKey = "live-agent-response:${snapshot.replyRequestId}",
                    source = snapshot.assistantText,
                    spokenPhrase = (snapshot.readAlong as? LiveReadAlong.Reply)?.phrase,
                    spokenSequence = (snapshot.readAlong as? LiveReadAlong.Reply)?.sequence,
                    onSpokenBounds = if (snapshot.readAlong is LiveReadAlong.Reply) { bounds ->
                        val spoken = snapshot.readAlong
                        if (bounds != null) trackSpoken(spoken, bounds)
                        else if (anchor?.owner == spoken) anchor = null
                    } else null,
                    paragraphStyle = KompiUi.typography.headlineSmall.copy(
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Normal,
                    ),
                    modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth()
                        .padding(vertical = 24.dp)
                        .semantics {
                            contentDescription = "Agent response"
                        },
                )
                }
            } else if (recoverable != null) {
                Column(Modifier.fillMaxWidth().widthIn(max = 480.dp).align(Alignment.CenterHorizontally)
                    .padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Unsent request", style = KompiUi.typography.titleMedium)
                    // Review the immutable intent, not a recognizer-sized preview. Separate placed
                    // paragraphs keep the middle and tail reachable at large font sizes too.
                    recoverable.text.split("\n\n").forEach { paragraph ->
                        Text(paragraph, style = KompiUi.typography.bodyLarge)
                    }
                }
            } else if (snapshot.caption.visibleText.isNotBlank()) {
                LiveTranscript(
                    caption = snapshot.caption,
                    modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth()
                        .align(Alignment.CenterHorizontally).padding(top = 24.dp, bottom = 24.dp),
                )
            } else if (listening || snapshot.state == LiveConversationState.Recognizing) {
                Text(
                    if (snapshot.microphoneMuted) "Microphone muted" else "Listening…",
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    style = KompiUi.typography.bodyMedium,
                    color = KompiUi.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            inputStatusText?.let { message ->
                Text(message,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    style = KompiUi.typography.bodySmall,
                    color = KompiUi.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center)
            }
            failure?.let { reason ->
                Column(
                    Modifier.fillMaxWidth().padding(top = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(reason, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = KompiUi.colors.error, style = KompiUi.typography.bodyMedium)
                }
            }
            }
            Spacer(Modifier.height(edgePadding))
            }
            }
            // Recovery and safety controls are outside transcript scrolling. A long request or
            // answer must never put Retry or End thousands of words below the current viewport.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasLiveSession) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onToggleMicrophoneMute,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                                stateDescription = if (snapshot.microphoneMuted) "Muted" else "Unmuted"
                            }) { Text(if (snapshot.microphoneMuted) "Unmute microphone" else "Mute microphone") }
                        OutlinedButton(onClick = onToggleOutputMute,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                                stateDescription = if (snapshot.outputMuted) "Muted" else "Unmuted"
                            }) { Text(if (snapshot.outputMuted) "Unmute output" else "Mute output") }
                    }
                }
                if (recoverable != null) {
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Retry") }
                }
                if (failure != null && recoverable == null) {
                    OutlinedButton(onClick = openSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (onOpenVoiceSettings == null) "App settings" else "Voice settings")
                    }
                }
                if (canStart) {
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        ChatLucideIcon(LucideR.drawable.lucide_ic_audio_lines, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Start Live")
                    }
                }
                if (canInterrupt) {
                    Button(onClick = onInterrupt, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        ChatLucideIcon(LucideR.drawable.lucide_ic_audio_lines, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Interrupt")
                    }
                }
                if (snapshot.state != LiveConversationState.Idle || recoverable != null) {
                    OutlinedButton(onClick = onEnd, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        ChatLucideIcon(LucideR.drawable.lucide_ic_arrow_left, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Back to Chat")
                    }
                }
            }
        }
    }
}
