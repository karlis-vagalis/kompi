package com.kompi.app.peers

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.kompi.app.chat.ChatLucideIcon
import com.kompi.app.ui.KompiAlertDialog
import com.kompi.app.ui.KompiButton
import com.kompi.app.ui.KompiClickableItem
import com.kompi.app.ui.KompiDropdownMenu
import com.kompi.app.ui.KompiDropdownMenuItem
import com.kompi.app.ui.KompiIconButton
import com.kompi.app.ui.KompiText
import com.kompi.app.ui.KompiTextButton
import com.kompi.app.ui.KompiUi

/** Peer-first workspace navigation, reusing the owner's selection, pairing and settings actions. */
@Composable
internal fun PeerSidebarMenu(
    hosts: List<PeerDirectoryHost>,
    current: SidebarSession?,
    history: List<SidebarSession>,
    pinnedConversationIds: Set<String>,
    historyActionError: String?,
    historyNotice: String?,
    onRetryHistory: (() -> Unit)?,
    deletingConversationId: String?,
    onSetPinned: (SidebarSession, Boolean) -> Unit,
    onDelete: (SidebarSession) -> Unit,
    onOpen: (SidebarSession?) -> Unit,
    onNewChat: (() -> Unit)?,
    onUseHost: (String) -> Unit,
    onOpenDetails: (String) -> Unit,
    onPairWithQr: () -> Unit,
    onEnterPairingCode: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPairingChoices by remember { mutableStateOf(false) }
    if (showPairingChoices) {
        KompiAlertDialog(
            onDismissRequest = { showPairingChoices = false },
            title = { KompiText("Add peer", style = KompiUi.typography.titleLarge) },
            text = { KompiText("Scan a QR code or enter a pairing code.", color = KompiUi.colors.onSurfaceVariant) },
            confirmButton = {
                KompiButton(onClick = { showPairingChoices = false; onPairWithQr() }) { KompiText("Scan QR code") }
            },
            dismissButton = {
                KompiTextButton(onClick = { showPairingChoices = false; onEnterPairingCode() }) { KompiText("Enter code") }
            },
        )
    }
    val selectedHost = hosts.firstOrNull { it.selected }?.hostId
    var confirmDelete by remember(selectedHost) { mutableStateOf<SidebarSession?>(null) }
    confirmDelete?.let { requested ->
        val target = history.firstOrNull { it.conversationId == requested.conversationId }
        KompiAlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { KompiText("Delete session?", style = KompiUi.typography.titleLarge) },
            text = {
                Column(Modifier.heightIn(max = 216.dp).verticalScroll(rememberScrollState())) {
                    KompiText("“${requested.title}” will be permanently deleted from the computer.")
                }
            },
            confirmButton = {
                KompiTextButton(enabled = target?.deleteUnavailable == null && target != null && deletingConversationId == null,
                    onClick = { confirmDelete = null; target?.let(onDelete) }) {
                    KompiText("Delete", color = KompiUi.colors.error)
                }
            },
            dismissButton = { KompiTextButton(onClick = { confirmDelete = null }) { KompiText("Cancel") } },
        )
    }
    val (pinnedHistory, unpinnedHistory) = remember(history, pinnedConversationIds) {
        history.partition { it.conversationId in pinnedConversationIds }
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    BoxWithConstraints(modifier.fillMaxSize()) {
        val columns = if (landscape) ((maxWidth - 40.dp + 12.dp) / 192.dp).toInt().coerceAtLeast(2) else 2
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selectedHost != null && onNewChat != null) item(key = "new-chat", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    KompiTextButton(onClick = onNewChat) { KompiText("New chat") }
                }
            }
            if (selectedHost != null) item(key = "current-chat", span = { GridItemSpan(maxLineSpan) }) {
                HistoryRow(current, isSelected = true, pinned = current?.conversationId in pinnedConversationIds,
                    deleting = false, onOpen = { onOpen(current) },
                    onSetPinned = { value -> current?.let { onSetPinned(it, value) } }, onDelete = {})
            }
            if (historyActionError != null || deletingConversationId != null) item(key = "history-action", span = { GridItemSpan(maxLineSpan) }) {
                KompiText(historyActionError ?: "Deleting session…", style = KompiUi.typography.bodySmall,
                    color = if (historyActionError == null) KompiUi.colors.onSurfaceVariant else KompiUi.colors.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (pinnedHistory.isNotEmpty()) {
                item(key = "pinned-heading", span = { GridItemSpan(maxLineSpan) }) {
                    KompiText("Pinned", style = KompiUi.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp).semantics { heading() })
                }
                items(pinnedHistory, key = { it.sidebarKey() }, span = { GridItemSpan(maxLineSpan) }) { chat ->
                    HistoryRow(chat, isSelected = false, pinned = true, deleting = deletingConversationId != null,
                        onOpen = { if (deletingConversationId != chat.conversationId) onOpen(chat) },
                        onSetPinned = { onSetPinned(chat, it) }, onDelete = { confirmDelete = chat })
                }
            }
            item(key = "peers-heading", span = { GridItemSpan(maxLineSpan) }) {
                KompiText("Peers", style = KompiUi.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp))
            }
            items(hosts, key = { "peer:${it.hostId}" }) { host ->
                PeerTile(host, onUseHost = { onUseHost(host.hostId) }, onOpenDetails = { onOpenDetails(host.hostId) })
            }
            item(key = "add-peer") {
                KompiClickableItem(
                    onClick = { showPairingChoices = true },
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(KompiUi.shapes.medium)
                        .border(1.dp, KompiUi.colors.outlineVariant, KompiUi.shapes.medium)
                        .background(KompiUi.colors.surfaceContainerLow)
                        .semantics { contentDescription = "Add peer" },
                ) {
                    Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ChatLucideIcon(LucideR.drawable.lucide_ic_plus, null, Modifier.size(28.dp), tint = KompiUi.colors.primary)
                        KompiText("Add peer", style = KompiUi.typography.bodyMedium, textAlign = TextAlign.Center)
                    }
                }
            }
            item(key = "settings-heading", span = { GridItemSpan(maxLineSpan) }) {
                KompiText("Settings", style = KompiUi.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
            }
            item(key = "app-settings", span = { GridItemSpan(maxLineSpan) }) {
                KompiClickableItem(onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(KompiUi.shapes.medium)
                        .background(KompiUi.colors.surfaceContainerLow)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ChatLucideIcon(LucideR.drawable.lucide_ic_settings, null, Modifier.size(20.dp))
                        KompiText("App settings", Modifier.weight(1f), style = KompiUi.typography.bodyMedium)
                        ChatLucideIcon(LucideR.drawable.lucide_ic_chevron_right, null, Modifier.size(18.dp), tint = KompiUi.colors.onSurfaceVariant)
                    }
                }
            }
            if (selectedHost != null) {
                if (historyNotice != null || unpinnedHistory.isNotEmpty()) item(key = "history-heading", span = { GridItemSpan(maxLineSpan) }) {
                    KompiText("Conversation history", style = KompiUi.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp).semantics { heading() })
                }
                if (historyNotice != null) item(key = "history-notice", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        KompiText(historyNotice, style = KompiUi.typography.bodyMedium, color = KompiUi.colors.onSurfaceVariant)
                        if (onRetryHistory != null) KompiTextButton(onClick = onRetryHistory) { KompiText("Retry") }
                    }
                }
                items(unpinnedHistory, key = { it.sidebarKey() }, span = { GridItemSpan(maxLineSpan) }) { chat ->
                    HistoryRow(chat, isSelected = false, pinned = false, deleting = deletingConversationId != null,
                        onOpen = { if (deletingConversationId != chat.conversationId) onOpen(chat) },
                        onSetPinned = { onSetPinned(chat, it) }, onDelete = { confirmDelete = chat })
                }
            }
        }
    }
}

internal data class SidebarSession(
    val hostId: String,
    val conversationId: String,
    val title: String,
    val timeLabel: String?,
    val status: String? = null,
    val deleteUnavailable: String? = null,
    val canPin: Boolean = true,
) {
    fun sidebarKey() = "history:$hostId:$conversationId"
}

@Composable
private fun HistoryRow(
    session: SidebarSession?,
    isSelected: Boolean,
    pinned: Boolean,
    deleting: Boolean,
    onOpen: () -> Unit,
    onSetPinned: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val label = session?.title ?: "New chat"
    val detail = listOfNotNull(session?.timeLabel, session?.status).joinToString(" · ")
    var menuExpanded by remember(session?.conversationId) { mutableStateOf(false) }
    val foreground = if (isSelected) KompiUi.colors.onPrimaryContainer else KompiUi.colors.onSurface
    Row(
        Modifier.fillMaxWidth().clip(KompiUi.shapes.medium)
            .background(if (isSelected) KompiUi.colors.primaryContainer else KompiUi.colors.surfaceContainerLow),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KompiClickableItem(onClick = onOpen,
            modifier = Modifier.weight(1f).heightIn(min = 52.dp).semantics { selected = isSelected }) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChatLucideIcon(LucideR.drawable.lucide_ic_message_square, null, Modifier.size(20.dp), tint = foreground)
                Column(Modifier.weight(1f)) {
                    KompiText(label, style = KompiUi.typography.bodyMedium, color = foreground,
                        fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (detail.isNotEmpty()) KompiText(detail, style = KompiUi.typography.bodySmall,
                        color = if (isSelected) foreground else KompiUi.colors.onSurfaceVariant)
                }
            }
        }
        if (session != null && session.canPin) Box(Modifier.padding(end = 4.dp)) {
            KompiIconButton(onClick = { menuExpanded = true }, modifier = Modifier.semantics {
                contentDescription = "Session actions for $label"
                if (pinned) stateDescription = "Pinned"
            }) {
                ChatLucideIcon(LucideR.drawable.lucide_ic_ellipsis_vertical, null, Modifier.size(20.dp), tint = foreground)
            }
            val unavailable = session.deleteUnavailable ?: if (isSelected) "This session is selected." else null
            KompiDropdownMenu(menuExpanded, onDismissRequest = { menuExpanded = false },
                modifier = Modifier.width(208.dp), alignment = Alignment.TopEnd) {
                KompiDropdownMenuItem(
                    text = { KompiText(if (pinned) "Unpin" else "Pin") },
                    onClick = { menuExpanded = false; onSetPinned(!pinned) },
                )
                KompiDropdownMenuItem(
                    text = {
                        Column {
                            KompiText("Delete", color = if (unavailable == null) KompiUi.colors.error else KompiUi.colors.onSurface)
                            unavailable?.let { KompiText(it, style = KompiUi.typography.bodySmall) }
                        }
                    },
                    enabled = unavailable == null && !deleting,
                    onClick = { menuExpanded = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun PeerTile(host: PeerDirectoryHost, onUseHost: () -> Unit, onOpenDetails: () -> Unit) {
    val status = when (host.status) {
        PeerHostStatus.NotSelected -> "Not selected"
        PeerHostStatus.Disconnected -> "Disconnected"
        PeerHostStatus.Connecting -> "Connecting"
        PeerHostStatus.Synchronizing -> "Synchronizing"
        PeerHostStatus.Live -> "Connected"
    }
    val connected = host.selected && host.status == PeerHostStatus.Live
    val foreground = if (host.selected) KompiUi.colors.onPrimaryContainer else KompiUi.colors.onSurface
    KompiClickableItem(
        onClick = onUseHost,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(KompiUi.shapes.medium)
            .border(1.dp, if (host.selected) KompiUi.colors.primary else KompiUi.colors.outlineVariant, KompiUi.shapes.medium)
            .background(if (host.selected) KompiUi.colors.primaryContainer else KompiUi.colors.surfaceContainerLow)
            .semantics {
                contentDescription = "Use ${host.name}"
                selected = host.selected
                stateDescription = "${if (host.selected) "Selected; " else ""}$status"
            },
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ChatLucideIcon(LucideR.drawable.lucide_ic_monitor_smartphone, null, Modifier.size(26.dp), tint = foreground)
                Spacer(Modifier.weight(1f))
                KompiIconButton(onClick = onOpenDetails,
                    modifier = Modifier.semantics { contentDescription = "Settings for ${host.name}" }) {
                    ChatLucideIcon(LucideR.drawable.lucide_ic_settings, null, Modifier.size(19.dp), tint = foreground)
                }
            }
            Spacer(Modifier.weight(1f))
            KompiText(host.name, style = KompiUi.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = foreground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(6.dp).background(if (connected) KompiUi.colors.primary else KompiUi.colors.onSurfaceVariant, CircleShape))
                KompiText(status, style = KompiUi.typography.bodySmall, color = foreground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
