package com.kompi.app.live

import android.animation.ValueAnimator
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.kompi.app.ui.KompiAssistChip as AssistChip
import com.kompi.app.ui.KompiOutlinedButton as OutlinedButton
import com.kompi.app.ui.KompiSurface as Surface
import com.kompi.app.ui.KompiText as Text
import com.kompi.app.ui.KompiTextButton as TextButton
import com.kompi.app.ui.KompiUi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.kompi.app.chat.ChatLucideIcon
import com.kompi.app.chat.KompiAssistantMark
import com.kompi.app.chat.KompiSection

/** Standalone live-mode entry UI; the parent composer owns send, permission and attachment policy. */
@Composable
fun LiveComposerSurface(
    snapshot: LiveConversationSnapshot,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onInterrupt: () -> Unit,
    onRetry: () -> Unit,
    canStartLive: Boolean = true,
    setupMessage: String? = null,
    onSetup: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val active = snapshot.state != LiveConversationState.Idle && snapshot.state != LiveConversationState.Paused && snapshot.state !is LiveConversationState.Failure
    val phase = when (snapshot.state) {
        LiveConversationState.Idle -> SilkPhase.Idle
        LiveConversationState.Preparing -> SilkPhase.Thinking
        LiveConversationState.Listening -> SilkPhase.Listening
        LiveConversationState.Recognizing -> SilkPhase.Recognizing
        is LiveConversationState.Waiting -> SilkPhase.Thinking
        is LiveConversationState.Speaking -> SilkPhase.Speaking
        LiveConversationState.Interrupted -> SilkPhase.Interrupted
        LiveConversationState.Paused -> SilkPhase.Paused
        is LiveConversationState.Failure -> SilkPhase.Paused
    }
    val reducedMotion = !ValueAnimator.areAnimatorsEnabled()
    val canInterrupt = active && (snapshot.state is LiveConversationState.Waiting || snapshot.state is LiveConversationState.Speaking || (snapshot.state == LiveConversationState.Preparing && snapshot.assistantText.isNotBlank()))

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AssistChip(
                onClick = if (active) onEnd else if (canStartLive) onStart else onSetup ?: {},
                enabled = active || canStartLive || onSetup != null,
                label = { Text(if (active) "Live voice" else if (canStartLive) "Start live voice" else "Set up voice") },
                leadingIcon = { ChatLucideIcon(if (active) LucideR.drawable.lucide_ic_square else LucideR.drawable.lucide_ic_audio_lines, contentDescription = null) },
                selected = active,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = if (active) "End live voice" else "Start live voice" },
            )
            Text(statusText(snapshot.state), Modifier.weight(1f), style = KompiUi.typography.labelMedium, color = KompiUi.colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (active) TextButton(onClick = onEnd, modifier = Modifier.heightIn(min = 48.dp)) { Text("End") }
            else if (snapshot.state is LiveConversationState.Failure && snapshot.caption.visibleText.isNotBlank()) {
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") }
            }
        }

        if (canInterrupt) {
            OutlinedButton(onClick = onInterrupt, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                ChatLucideIcon(LucideR.drawable.lucide_ic_mic, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Interrupt · listen")
            }
        }
        if (active || snapshot.caption.visibleText.isNotBlank() || snapshot.assistantText.isNotBlank()) {
            SilkLayersVisualizer(
                level = snapshot.visualizerLevel,
                bands = AudioBands.Empty,
                phase = phase,
                motionEnabled = !reducedMotion,
                reducedMotion = reducedMotion,
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 96.dp),
            )
        }
        if (snapshot.caption.visibleText.isNotBlank()) {
            KompiSection(if (snapshot.caption.pendingText.isNotBlank()) "You · hearing ${snapshot.caption.language?.uppercase() ?: "locally"}" else "You") {
                Text(snapshot.caption.visibleText, Modifier.fillMaxWidth().padding(16.dp), style = KompiUi.typography.bodyLarge, color = KompiUi.colors.onSurface)
            }
        }
        if (snapshot.assistantText.isNotBlank()) {
            KompiSection("Reply") {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(snapshot.assistantText, style = KompiUi.typography.bodyLarge, color = KompiUi.colors.onSurface, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    if (snapshot.playingPhrase.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            KompiAssistantMark(size = 22.dp)
                            Text(snapshot.playingPhrase, style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        setupMessage?.let { Text(it, style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant) }
        if (snapshot.state is LiveConversationState.Failure) {
            Text(snapshot.state.message, style = KompiUi.typography.bodySmall, color = KompiUi.colors.error)
        }
    }
}

private fun statusText(state: LiveConversationState): String = when (state) {
    LiveConversationState.Idle -> "Off"
    LiveConversationState.Preparing -> "Preparing"
    LiveConversationState.Listening -> "Listening"
    LiveConversationState.Recognizing -> "Recognizing"
    is LiveConversationState.Waiting -> "Thinking"
    is LiveConversationState.Speaking -> "Speaking"
    LiveConversationState.Interrupted -> "Interrupted"
    LiveConversationState.Paused -> "Paused"
    is LiveConversationState.Failure -> "Unavailable"
}
