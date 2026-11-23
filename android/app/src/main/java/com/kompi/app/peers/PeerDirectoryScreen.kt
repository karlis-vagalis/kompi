package com.kompi.app.peers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.kompi.app.chat.ChatLucideIcon
import com.kompi.app.ui.KompiAlertDialog
import com.kompi.app.ui.KompiButton
import com.kompi.app.ui.KompiClickableItem
import com.kompi.app.ui.KompiDivider
import com.kompi.app.ui.KompiIconButton
import com.kompi.app.ui.KompiOutlinedButton
import com.kompi.app.ui.KompiSectionSurface
import com.kompi.app.ui.KompiText
import com.kompi.app.ui.KompiTextButton
import com.kompi.app.ui.KompiUi

@Composable
fun PeerDirectoryScreen(
    modifier: Modifier = Modifier,
    hosts: List<PeerDirectoryHost>,
    onOpenDetails: (hostId: String) -> Unit,
    onUseHost: (hostId: String) -> Unit,
    onRemoveHost: (hostId: String) -> Unit,
    onPairWithQr: () -> Unit,
    onEnterPairingCode: () -> Unit,
) {
    var hostPendingRemoval by remember { mutableStateOf<PeerDirectoryHost?>(null) }
    hostPendingRemoval?.let { host ->
        KompiAlertDialog(
            onDismissRequest = { hostPendingRemoval = null },
            title = { KompiText("Remove ${host.name}?", style = KompiUi.typography.titleLarge) },
            text = {
                KompiText(
                    "This removes the computer from this phone’s trusted list. Local chat history stays here; transcripts and files on the computer are untouched.",
                    color = KompiUi.colors.onSurfaceVariant,
                )
            },
            dismissButton = {
                KompiTextButton(onClick = { hostPendingRemoval = null }) { KompiText("Cancel") }
            },
            confirmButton = {
                KompiButton(
                    onClick = {
                        hostPendingRemoval = null
                        onRemoveHost(host.hostId)
                    },
                ) {
                    ChatLucideIcon(LucideR.drawable.lucide_ic_trash_2, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    KompiText("Remove")
                }
            },
        )
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            KompiText("Computers", style = KompiUi.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            KompiText("Choose which trusted computer to use, or manage its settings.", color = KompiUi.colors.onSurfaceVariant)
        }

        if (hosts.isEmpty()) {
            KompiSectionSurface {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ChatLucideIcon(LucideR.drawable.lucide_ic_monitor_smartphone, contentDescription = null, modifier = Modifier.size(28.dp), tint = KompiUi.colors.onSurfaceVariant)
                    KompiText("No computers paired yet", style = KompiUi.typography.titleMedium)
                    KompiText("Pair a computer to connect it to this phone.", color = KompiUi.colors.onSurfaceVariant)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                KompiText("Trusted computers", style = KompiUi.typography.titleMedium)
                hosts.forEach { host ->
                    PeerHostCard(
                        host = host,
                        onOpenDetails = { onOpenDetails(host.hostId) },
                        onUseHost = { onUseHost(host.hostId) },
                        onRemoveHost = { hostPendingRemoval = host },
                    )
                }
            }
        }

        KompiSectionSurface {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KompiText("Pair a computer", style = KompiUi.typography.titleMedium)
                KompiText("Scan a QR code or enter a pairing code.", color = KompiUi.colors.onSurfaceVariant)
                KompiButton(onClick = onPairWithQr, modifier = Modifier.fillMaxWidth()) {
                    ChatLucideIcon(LucideR.drawable.lucide_ic_qr_code, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    KompiText("Scan QR code")
                }
                KompiOutlinedButton(onClick = onEnterPairingCode, modifier = Modifier.fillMaxWidth()) {
                    ChatLucideIcon(LucideR.drawable.lucide_ic_keyboard, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    KompiText("Enter pairing code")
                }
            }
        }
    }
}

@Composable
private fun PeerHostCard(
    host: PeerDirectoryHost,
    onOpenDetails: () -> Unit,
    onUseHost: () -> Unit,
    onRemoveHost: () -> Unit,
) {
    KompiSectionSurface {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                KompiClickableItem(
                    onClick = onOpenDetails,
                    modifier = Modifier.weight(1f).heightIn(min = 64.dp).semantics {
                        contentDescription = "Settings for ${host.name}; ${hostStatusDescription(host.selected, host.status)}"
                    },
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ChatLucideIcon(LucideR.drawable.lucide_ic_monitor_smartphone, contentDescription = null, modifier = Modifier.size(24.dp), tint = KompiUi.colors.onSurfaceVariant)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            KompiText(host.name, style = KompiUi.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            PeerHostStatusLabel(host.selected, host.status)
                        }
                        ChatLucideIcon(LucideR.drawable.lucide_ic_chevron_right, contentDescription = null, modifier = Modifier.size(18.dp), tint = KompiUi.colors.onSurfaceVariant)
                    }
                }
                KompiIconButton(
                    onClick = onRemoveHost,
                    modifier = Modifier.semantics { contentDescription = "Remove ${host.name}" },
                ) {
                    ChatLucideIcon(LucideR.drawable.lucide_ic_trash_2, contentDescription = null, modifier = Modifier.size(19.dp), tint = KompiUi.colors.error)
                }
            }
            if (!host.selected && host.lastSeenEpochMillis > 0L) {
                KompiText(
                    "Last connected ${formatLastSeen(host.lastSeenEpochMillis)}",
                    modifier = Modifier.padding(start = 40.dp, end = 8.dp),
                    style = KompiUi.typography.bodySmall,
                    color = KompiUi.colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!host.selected) {
                KompiDivider()
                KompiOutlinedButton(onClick = onUseHost, modifier = Modifier.fillMaxWidth()) {
                    ChatLucideIcon(LucideR.drawable.lucide_ic_plug_zap, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    KompiText("Use ${host.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
internal fun PeerHostStatusLabel(selected: Boolean, status: PeerHostStatus) {
    val icon = when {
        !selected -> LucideR.drawable.lucide_ic_monitor_off
        status == PeerHostStatus.Live -> LucideR.drawable.lucide_ic_circle_check
        status == PeerHostStatus.Connecting -> LucideR.drawable.lucide_ic_loader_circle
        status == PeerHostStatus.Synchronizing -> LucideR.drawable.lucide_ic_refresh_cw
        status == PeerHostStatus.Disconnected -> LucideR.drawable.lucide_ic_wifi_off
        else -> LucideR.drawable.lucide_ic_monitor_off
    }
    val color = when {
        !selected -> KompiUi.colors.onSurfaceVariant
        status == PeerHostStatus.Live -> KompiUi.colors.primary
        status == PeerHostStatus.Disconnected -> KompiUi.colors.error
        else -> KompiUi.colors.tertiary
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ChatLucideIcon(icon, contentDescription = null, modifier = Modifier.size(15.dp), tint = color)
        KompiText(
            hostStatusDescription(selected, status),
            style = KompiUi.typography.bodySmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun hostStatusDescription(selected: Boolean, status: PeerHostStatus): String = when {
    !selected -> "Not selected · connection paused"
    status == PeerHostStatus.Live -> "Selected · Live"
    status == PeerHostStatus.Connecting -> "Selected · Connecting"
    status == PeerHostStatus.Synchronizing -> "Selected · Synchronizing"
    status == PeerHostStatus.Disconnected -> "Selected · Disconnected"
    else -> "Selected"
}

private fun formatLastSeen(epochMillis: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        .format(java.util.Date(epochMillis))
