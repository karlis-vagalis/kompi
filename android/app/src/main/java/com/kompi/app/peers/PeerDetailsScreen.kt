package com.kompi.app.peers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.kompi.app.chat.ChatLucideIcon
import com.kompi.app.protocol.generated.MAX_UPLOAD_RETENTION_SECONDS
import com.kompi.app.protocol.generated.MIN_UPLOAD_RETENTION_SECONDS
import com.kompi.app.security.SecureStore
import com.kompi.app.ui.KompiAlertDialog
import com.kompi.app.ui.KompiButton
import com.kompi.app.ui.KompiFilterChip
import com.kompi.app.ui.KompiIconButton
import com.kompi.app.ui.KompiOutlinedButton
import com.kompi.app.ui.KompiOutlinedTextField
import com.kompi.app.ui.KompiSectionSurface
import com.kompi.app.ui.KompiText
import com.kompi.app.ui.KompiTextButton
import com.kompi.app.ui.KompiUi

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PeerDetailsScreen(
    modifier: Modifier = Modifier,
    host: PeerHostSettings,
    returnTo: PeerReturnTarget,
    onBack: () -> Unit,
    onUseHost: (hostId: String) -> Unit,
    onSaveIdleTimeoutSeconds: (hostId: String, seconds: Int) -> Unit,
    onSaveUploadRetentionSeconds: (hostId: String, seconds: Int) -> Unit,
    onRemoveHost: (hostId: String) -> Unit,
    onSaveDisplayName: (hostId: String, name: PeerDisplayName?) -> Result<Unit>,
) {
    var confirmRemoval by remember(host.hostId) { mutableStateOf(false) }
    if (confirmRemoval) {
        KompiAlertDialog(
            onDismissRequest = { confirmRemoval = false },
            title = { KompiText("Remove ${host.name}?", style = KompiUi.typography.titleLarge) },
            text = {
                KompiText(
                    "This removes the computer from this phone’s trusted list. Local chat history stays here; transcripts and files on the computer are untouched.",
                    color = KompiUi.colors.onSurfaceVariant,
                )
            },
            dismissButton = { KompiTextButton(onClick = { confirmRemoval = false }) { KompiText("Cancel") } },
            confirmButton = {
                KompiButton(
                    onClick = {
                        confirmRemoval = false
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
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KompiIconButton(
                onClick = onBack,
                modifier = Modifier.semantics {
                    contentDescription = when (returnTo) {
                        PeerReturnTarget.Chat -> "Back to chat"
                        PeerReturnTarget.Directory -> "Back to computers"
                        PeerReturnTarget.Sidebar -> "Back to peers menu"
                    }
                },
            ) {
                ChatLucideIcon(LucideR.drawable.lucide_ic_arrow_left, contentDescription = null, modifier = Modifier.size(21.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                KompiText("Computer settings", style = KompiUi.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                KompiText(host.name, style = KompiUi.typography.bodyMedium, color = KompiUi.colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            KompiIconButton(
                onClick = { confirmRemoval = true },
                modifier = Modifier.semantics { contentDescription = "Remove ${host.name}" },
            ) {
                ChatLucideIcon(LucideR.drawable.lucide_ic_trash_2, contentDescription = null, modifier = Modifier.size(20.dp), tint = KompiUi.colors.error)
            }
        }

        KompiSectionSurface {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KompiText("Connection", style = KompiUi.typography.titleMedium)
                PeerHostStatusLabel(host.selected, host.status)
                if (host.selected) {
                    KompiText(
                        "This is the currently selected computer. Opening settings does not change or restart its connection.",
                        color = KompiUi.colors.onSurfaceVariant,
                        style = KompiUi.typography.bodySmall,
                    )
                } else {
                    KompiText(
                        "Changes sync when this computer is selected.",
                        color = KompiUi.colors.onSurfaceVariant,
                        style = KompiUi.typography.bodySmall,
                    )
                    KompiOutlinedButton(onClick = { onUseHost(host.hostId) }, modifier = Modifier.fillMaxWidth()) {
                        ChatLucideIcon(LucideR.drawable.lucide_ic_plug_zap, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        KompiText("Use this computer")
                    }
                }
            }
        }

        DisplayNameSettings(
            host = host,
            onSave = { name -> onSaveDisplayName(host.hostId, name) },
        )
        IdleTimeoutSettings(
            host = host,
            onSave = { seconds -> onSaveIdleTimeoutSeconds(host.hostId, seconds) },
        )
        UploadRetentionSettings(
            host = host,
            onSave = { seconds -> onSaveUploadRetentionSeconds(host.hostId, seconds) },
        )

        host.capabilityDetails?.takeIf { it.isNotEmpty() }?.let { capabilities ->
            KompiSectionSurface {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    KompiText("Capabilities reported by this computer", style = KompiUi.typography.titleMedium)
                    capabilities.forEach { capability ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ChatLucideIcon(LucideR.drawable.lucide_ic_circle_check, contentDescription = null, modifier = Modifier.size(16.dp), tint = KompiUi.colors.onSurfaceVariant)
                            KompiText(capability, color = KompiUi.colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        KompiSectionSurface {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KompiText("Host identity", style = KompiUi.typography.titleSmall)
                KompiText(host.hostId, style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
                if (host.lastSeenEpochMillis > 0L) {
                    KompiText(
                        "Last connected ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(host.lastSeenEpochMillis))}",
                        style = KompiUi.typography.bodySmall,
                        color = KompiUi.colors.onSurfaceVariant,
                    )
                } else {
                    KompiText("No authenticated connection yet", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DisplayNameSettings(host: PeerHostSettings, onSave: (PeerDisplayName?) -> Result<Unit>) {
    androidx.compose.runtime.key(host.hostId, host.name, host.displayNameOverride?.value) {
        val field = rememberTextFieldState(host.name)
        val draft = field.text.toString()
        val parsed = remember(draft) { PeerDisplayName.parse(draft) }
        val name = parsed.getOrNull()
        var saveError by remember { mutableStateOf<String?>(null) }
        val error = parsed.exceptionOrNull()?.message ?: saveError
        KompiSectionSurface {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                KompiText("Computer name", style = KompiUi.typography.titleMedium)
                KompiText("Choose a display name for this phone only. This does not rename the computer or change its connection.", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
                KompiOutlinedTextField(
                    state = field,
                    label = { KompiText("Display name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Computer display name" },
                )
                if (error != null) KompiText(error, style = KompiUi.typography.bodySmall, color = KompiUi.colors.error)
                KompiButton(
                    onClick = {
                        name?.let { value ->
                            onSave(value.takeUnless { it.value == host.pairingName })
                                .onFailure { saveError = it.message ?: "Could not save computer name" }
                        }
                    },
                    enabled = name != null && name.value != host.name,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { KompiText("Save name") }
                KompiText("Pairing name: ${host.pairingName}", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
                if (host.displayNameOverride != null) {
                    KompiTextButton(
                        onClick = { onSave(null).onFailure { saveError = it.message ?: "Could not reset computer name" } },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { KompiText("Use pairing name") }
                }
            }
        }
    }
}

@Composable
private fun IdleTimeoutSettings(host: PeerHostSettings, onSave: (Int) -> Unit) {
    val minMinutes = SecureStore.MIN_IDLE_TIMEOUT_SECONDS / 60
    val maxMinutes = SecureStore.MAX_IDLE_TIMEOUT_SECONDS / 60
    var customMinutes by remember(host.hostId, host.requestedIdleTimeoutSeconds) {
        mutableStateOf((host.requestedIdleTimeoutSeconds / 60).toString())
    }
    var showCustom by remember(host.hostId) {
        mutableStateOf(host.requestedIdleTimeoutSeconds / 60 !in listOf(5, 15, 30, 60))
    }
    val parsedCustomSeconds = customMinutes.toIntOrNull()
        ?.takeIf { it in minMinutes..maxMinutes }
        ?.times(60)

    KompiSectionSurface {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChatLucideIcon(LucideR.drawable.lucide_ic_timer, contentDescription = null, modifier = Modifier.size(20.dp), tint = KompiUi.colors.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    KompiText("Conversation timeout", style = KompiUi.typography.titleMedium)
                    KompiText("Close an idle agent session after this interval without a new accepted message.", color = KompiUi.colors.onSurfaceVariant, style = KompiUi.typography.bodySmall)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 15, 30, 60).forEach { minutes ->
                    KompiFilterChip(
                        selected = host.requestedIdleTimeoutSeconds == minutes * 60,
                        onClick = { customMinutes = minutes.toString(); if (host.requestedIdleTimeoutSeconds != minutes * 60) onSave(minutes * 60) },
                        label = { KompiText("$minutes min") },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            KompiTextButton(onClick = { showCustom = !showCustom }) {
                KompiText(if (showCustom) "Hide custom value" else "Set a custom timeout")
            }
            if (showCustom) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KompiOutlinedTextField(
                        value = customMinutes,
                        onValueChange = { customMinutes = it.filter(Char::isDigit).take(maxMinutes.toString().length) },
                        label = { KompiText("Minutes · $minMinutes–$maxMinutes", style = KompiUi.typography.bodySmall) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    KompiButton(
                        onClick = { parsedCustomSeconds?.let(onSave) },
                        enabled = parsedCustomSeconds != null && parsedCustomSeconds != host.requestedIdleTimeoutSeconds,
                    ) { KompiText("Save") }
                }
            }
            SettingConfirmation(
                requested = formatIdleTimeout(host.requestedIdleTimeoutSeconds),
                effective = host.effectiveIdleTimeoutSeconds?.let(::formatIdleTimeout),
                pending = host.idleTimeoutPending,
                error = host.idleTimeoutError,
            )
        }
    }
}

@Composable
private fun UploadRetentionSettings(host: PeerHostSettings, onSave: (Int) -> Unit) {
    val requested = host.requestedUploadRetentionSeconds
    val requestedHours = requested / 3600
    val minHours = MIN_UPLOAD_RETENTION_SECONDS / 3600
    val maxHours = MAX_UPLOAD_RETENTION_SECONDS / 3600
    var customHours by remember(host.hostId, requested) { mutableStateOf(requestedHours.toString()) }
    val parsedCustomSeconds = customHours.toIntOrNull()
        ?.takeIf { it in minHours..maxHours }
        ?.times(3600)

    KompiSectionSurface {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChatLucideIcon(LucideR.drawable.lucide_ic_clock_3, contentDescription = null, modifier = Modifier.size(20.dp), tint = KompiUi.colors.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    KompiText("Uploaded-file retention", style = KompiUi.typography.titleMedium)
                    KompiText(
                        "Changing this affects future uploads only. Existing expiry dates do not change.",
                        color = KompiUi.colors.onSurfaceVariant,
                        style = KompiUi.typography.bodySmall,
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(24, 72, 168, 720).forEach { hours ->
                    KompiFilterChip(
                        selected = requestedHours == hours,
                        onClick = { customHours = hours.toString(); if (requested != hours * 3600) onSave(hours * 3600) },
                        label = { KompiText(if (hours % 24 == 0) "${hours / 24} days" else "$hours hours") },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KompiOutlinedTextField(
                    value = customHours,
                    onValueChange = { customHours = it.filter(Char::isDigit).take(maxHours.toString().length) },
                    label = { KompiText("Hours · $minHours–$maxHours", style = KompiUi.typography.bodySmall) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                KompiButton(
                    onClick = { parsedCustomSeconds?.let(onSave) },
                    enabled = parsedCustomSeconds != null && parsedCustomSeconds != requested,
                ) { KompiText("Save") }
            }
            SettingConfirmation(
                requested = formatUploadRetention(requested),
                effective = host.effectiveUploadRetentionSeconds?.let(::formatUploadRetention),
                pending = host.uploadRetentionPending,
                error = host.uploadRetentionError,
            )
        }
    }
}

@Composable
private fun SettingConfirmation(requested: String, effective: String?, pending: Boolean, error: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        KompiText("Requested · $requested", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
        KompiText(
            "Effective · ${effective ?: "not yet reported by this computer"}",
            style = KompiUi.typography.bodySmall,
            color = KompiUi.colors.onSurfaceVariant,
        )
        val status = when {
            error != null -> "Not saved · $error"
            pending -> "Waiting for computer confirmation"
            effective == null -> "No effective value has been reported by this computer"
            effective != requested -> "Computer still uses $effective"
            else -> "Confirmed by computer"
        }
        KompiText(status, style = KompiUi.typography.bodySmall, color = if (error != null) KompiUi.colors.error else KompiUi.colors.onSurfaceVariant)
    }
}

private fun formatIdleTimeout(seconds: Int): String = when {
    seconds % 3600 == 0 -> "${seconds / 3600} hours"
    seconds % 60 == 0 -> "${seconds / 60} minutes"
    else -> "$seconds seconds"
}

private fun formatUploadRetention(seconds: Int): String = when {
    seconds % 86400 == 0 -> "${seconds / 86400} days"
    seconds % 3600 == 0 -> "${seconds / 3600} hours"
    else -> "$seconds seconds"
}
