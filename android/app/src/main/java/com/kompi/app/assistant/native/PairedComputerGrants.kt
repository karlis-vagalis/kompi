package com.kompi.app.assistant.native

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kompi.app.assistant.AssistantScreenContext
import com.kompi.app.assistant.actions.PhoneCapability
import com.kompi.app.security.SecureStore
import com.kompi.app.ui.KompiSwitch
import com.kompi.app.ui.KompiText
import com.kompi.app.ui.KompiTextButton
import com.kompi.app.ui.KompiUi

@Composable
internal fun PairedComputerGrants(revision: Int) {
    val context = LocalContext.current
    val store = remember(context) { SecureStore(context) }
    val computer = remember(store, revision) {
        runCatching { store.selectedMachineId()?.let { id -> store.trustedMachines().firstOrNull { it.machineId == id } } }.getOrNull()
    }
    if (computer == null) {
        KompiText("Pair and select a computer before granting it phone access.", style = KompiUi.typography.bodySmall)
        return
    }
    val id = computer.machineId
    var grants by remember(store, id, revision) { mutableStateOf(runCatching { store.phoneGrants(id).orEmpty() }.getOrDefault(emptySet())) }
    var expanded by remember(id) { mutableStateOf<String?>(null) }
    var error by remember(id) { mutableStateOf(false) }
    KompiText("Allowed for ${computer.displayName}", style = KompiUi.typography.titleSmall)
    KompiText("Enabled actions don’t ask again. They only run while Kompi is open and the phone is unlocked. Shared data may be saved in the Pi conversation. To change another computer’s access, select it first.", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
    PhoneCapability.entries.groupBy { it.group }.forEach { (group, capabilities) ->
        Column {
            KompiTextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { expanded = if (expanded == group) null else group }) {
                KompiText("${if (expanded == group) "Hide" else "Show"} $group (${capabilities.count { it in grants }} allowed)")
            }
            if (expanded == group) capabilities.forEach { capability ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    KompiText(capability.label, Modifier.weight(1f), style = KompiUi.typography.bodyMedium)
                    KompiSwitch(checked = capability in grants, modifier = Modifier.semantics { contentDescription = capability.label }, onCheckedChange = { value ->
                        error = runCatching {
                            store.setPhoneGrant(id, capability, value)
                            grants = store.phoneGrants(id).orEmpty()
                            if (capability == PhoneCapability.ScreenText) AssistantScreenContext.clear()
                        }.isFailure
                    })
                }
            }
        }
    }
    if (error) KompiText("Could not change phone access. Reopen settings and try again.", style = KompiUi.typography.bodySmall, color = KompiUi.colors.error)
}
