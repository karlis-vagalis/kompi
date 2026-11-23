package com.kompi.app.assistant.push

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.kompi.app.assistant.AgentNotifications
import com.kompi.app.security.SecureStore
import com.kompi.app.ui.KompiButton
import com.kompi.app.ui.KompiSectionSurface
import com.kompi.app.ui.KompiText
import com.kompi.app.ui.KompiUi
import org.unifiedpush.android.connector.UnifiedPush

/** Host-scoped opt-in. This only arranges notification delivery; it never opens an agent session. */
@Composable
internal fun UnifiedPushSettings(refreshKey: Int) {
    val context = LocalContext.current
    val repository = remember(context) { UnifiedPushRepository.get(context) }
    val hosts = remember(context, refreshKey) { runCatching { SecureStore(context).trustedMachines() }.getOrDefault(emptyList()) }
    val notificationsAllowed = AgentNotifications.enabled(context) && (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    var localRefresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(context, refreshKey, notificationsAllowed) {
        if (notificationsAllowed) repository.refreshProviderAvailability() else repository.disableAll()
        localRefresh++
    }
    LaunchedEffect(repository) { repository.changes.collect { localRefresh++ } }
    val providerAvailable = remember(context, refreshKey, localRefresh) {
        runCatching { UnifiedPush.getDistributors(context).isNotEmpty() }.getOrDefault(false)
    }
    val summaries = remember(context, refreshKey, localRefresh) { repository.summaries().associateBy { it.hostId.value } }
    var operationError by remember(refreshKey) { mutableStateOf<String?>(null) }
    val activity = context as? Activity

    KompiSectionSurface {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            KompiText("Background notifications", style = KompiUi.typography.titleMedium)
            KompiText(
                "Receive encrypted notifications from a paired computer while Kompi is closed. Push never opens a computer connection or runs an action.",
                style = KompiUi.typography.bodySmall,
                color = KompiUi.colors.onSurfaceVariant,
            )
            when {
                hosts.isEmpty() -> KompiText("Pair a computer to set up background notifications.", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
                !notificationsAllowed -> KompiText("Allow Agent notifications and Android notification permission above before setting up a computer.", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
                else -> hosts.forEachIndexed { index, trusted ->
                    val hostId = PushHostId.parse(trusted.machineId)
                    val summary = hostId?.let { summaries[it.value] }
                    val storedStatus = summary?.status ?: PushSetupStatus.Off
                    val status = if (!providerAvailable) PushSetupStatus.ProviderUnavailable else storedStatus
                    val error = summary?.detailCode
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                KompiText(trusted.displayName, style = KompiUi.typography.titleSmall)
                                KompiText(statusLabel(status, error), style = KompiUi.typography.bodySmall, color = if (status == PushSetupStatus.NeedsAttention) KompiUi.colors.error else KompiUi.colors.onSurfaceVariant)
                            }
                        }
                        if (providerAvailable && (status == PushSetupStatus.Off || status == PushSetupStatus.ProviderUnavailable || status == PushSetupStatus.NeedsAttention)) {
                            KompiButton(
                                onClick = {
                                    val id = hostId ?: return@KompiButton
                                    val currentActivity = activity ?: run { operationError = "Open Kompi to choose a notification distributor."; return@KompiButton }
                                    operationError = null
                                    UnifiedPush.tryUseCurrentOrDefaultDistributor(currentActivity) { selected ->
                                        if (!selected) {
                                            // No installed provider is a normal unavailable state, not an app error.
                                            operationError = null
                                            localRefresh++
                                        } else {
                                            val instance = repository.startRegistration(id)
                                            if (instance == null) {
                                                operationError = "Could not save this computer's notification setup."
                                            } else {
                                                runCatching {
                                                    UnifiedPush.register(
                                                        context,
                                                        instance = instance,
                                                        messageForDistributor = "Kompi background notifications",
                                                    )
                                                }.onFailure {
                                                    repository.markLocalFailure(id, PushSetupError.RegistrationFailed)
                                                    operationError = "The distributor could not register this computer. Try again."
                                                }
                                                localRefresh++
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .semantics { contentDescription = "Set up background notifications for ${trusted.displayName}" },
                            ) { KompiText("Set up background delivery") }
                        } else if (status != PushSetupStatus.Off && status != PushSetupStatus.ProviderUnavailable) {
                            KompiButton(
                                onClick = {
                                    hostId?.let(repository::disable)
                                    localRefresh++
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .semantics { contentDescription = "Turn off background notifications for ${trusted.displayName}" },
                            ) { KompiText("Turn off for this computer") }
                        }
                    }
                    if (index != hosts.lastIndex) com.kompi.app.ui.KompiDivider()
                }
            }
            operationError?.let { KompiText(it, style = KompiUi.typography.bodySmall, color = KompiUi.colors.error) }
            KompiText(
                "Android may delay delivery. Reply alerts expire after five minutes.",
                style = KompiUi.typography.bodySmall,
                color = KompiUi.colors.onSurfaceVariant,
            )
        }
    }
}

private fun statusLabel(status: PushSetupStatus, error: PushSetupError?): String = when (status) {
    PushSetupStatus.Off -> "Off"
    PushSetupStatus.ChoosingDistributor -> "Choosing a distributor"
    PushSetupStatus.WaitingForEndpoint -> "Waiting for distributor registration"
    PushSetupStatus.WaitingForComputer -> "Waiting for computer confirmation"
    PushSetupStatus.Active -> "Ready for encrypted delivery"
    PushSetupStatus.TemporarilyUnavailable -> "Distributor temporarily unavailable"
    PushSetupStatus.ProviderUnavailable -> "Background delivery unavailable"
    PushSetupStatus.NeedsAttention -> when (error) {
        PushSetupError.NoDistributor -> "Background delivery unavailable"
        PushSetupError.RegistrationFailed -> "Registration needs attention"
        PushSetupError.MissingEncryptionKeys -> "This distributor did not provide encryption keys"
        PushSetupError.EndpointRejected -> "The computer rejected this endpoint"
        PushSetupError.StorageUnavailable -> "Secure setup could not be saved"
        PushSetupError.RegistrationLimit -> "The computer has reached its background delivery limit"
        PushSetupError.ComputerRejected -> "The computer rejected this setup"
        null -> "Setup needs attention"
    }
}
