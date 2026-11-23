package com.kompi.app.ui

import android.animation.ValueAnimator
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Presentation of the daemon's reported lease only; never a local session-expiry decision. */
internal data class SessionResetDeadline(val conversationId: String, val expiresAt: Instant)

@Composable
internal fun ConnectionStatusPill(
    peerId: String?,
    hostName: String?,
    state: String,
    session: SessionResetDeadline?,
    modifier: Modifier = Modifier,
) {
    val statusLabel = when (state) {
        "Live" -> "Live"
        "Synchronizing" -> "Synchronizing"
        "Connecting", "Reconnecting" -> "Connecting"
        "Pairing", "Waiting for pairing confirmation" -> "Pairing"
        else -> "Disconnected"
    }
    val label = hostName?.takeIf(String::isNotBlank) ?: "No host selected"
    val connected = state == "Live" || state == "Synchronizing"
    val statusColor = if (connected) KompiUi.colors.primary else KompiUi.colors.onSurface.copy(alpha = 0.38f)
    val labelColor = if (connected) KompiUi.colors.onSurface else KompiUi.colors.onSurface.copy(alpha = 0.38f)
    val interaction = remember { MutableInteractionSource() }
    var expanded by remember(peerId, session?.conversationId) { mutableStateOf(false) }
    var tapSequence by remember(peerId, session?.conversationId) { mutableIntStateOf(0) }
    var now by remember(session) { mutableStateOf(Instant.now()) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(expanded, tapSequence, peerId, session?.conversationId) {
        if (expanded) { delay(5_000); expanded = false }
    }
    LaunchedEffect(expanded, session, lifecycleOwner) {
        if (expanded) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) { now = Instant.now(); delay(1_000) }
        }
    }
    val seconds = session?.let { Duration.between(now, it.expiresAt).seconds.coerceAtLeast(0) }
    val countdown = when {
        seconds == null -> if (state == "Live") "No active session" else "Unavailable"
        seconds == 0L -> "Reset pending"
        else -> {
            val time = if (seconds >= 3_600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
                else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
            if (state == "Live") time else "~$time"
        }
    }
    val countdownDescription = when {
        seconds == null -> countdown
        seconds == 0L -> "Idle deadline reached; waiting for the computer to confirm reset"
        else -> "${if (state == "Live") "Estimated" else "Last known"} time until idle reset: $countdown"
    }
    Box(
        modifier = modifier.heightIn(min = 48.dp).clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                onClickLabel = "Show session reset countdown", onClick = {
                    now = Instant.now()
                    expanded = true
                    tapSequence++
                })
            .semantics {
                contentDescription = "Selected host: $label"
                stateDescription = "Connection status: $statusLabel" + if (expanded) "; $countdownDescription" else ""
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 32.dp).clip(CircleShape)
                .background(KompiUi.colors.surfaceContainerLowest)
                // Both forms are single-line with identical typography: only width changes.
                .animateContentSize(
                    animationSpec = if (ValueAnimator.areAnimatorsEnabled()) tween(240, easing = FastOutSlowInEasing) else snap(),
                    alignment = Alignment.Center,
                )
                .indication(interaction, LocalIndication.current)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).background(statusColor, CircleShape))
            Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically) {
                KompiText(label, Modifier.weight(1f, fill = false), style = KompiUi.typography.labelMedium,
                    color = labelColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (expanded) KompiText(" · $countdown", style = KompiUi.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = labelColor, maxLines = 1, softWrap = false)
            }
        }
    }
}
