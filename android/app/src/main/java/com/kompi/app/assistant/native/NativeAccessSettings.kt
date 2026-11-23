package com.kompi.app.assistant.native

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kompi.app.ui.KompiButton
import com.kompi.app.ui.KompiSwitch
import com.kompi.app.ui.KompiText
import com.kompi.app.ui.KompiTextButton
import com.kompi.app.ui.KompiUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun NativeAccessSettings() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var revision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        revision++
        message = if(it.values.any { granted -> !granted }) "Some access was not granted. Review Android app permissions. Call history and SMS also depend on default-assistant selection and installer allowlisting." else null
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if(uri != null) {
            try {
                try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                catch (_: SecurityException) { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val old = NativeFiles.tree(context)
                if(old != null && old != uri) NativeFiles.forget(context)
                context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString(NativeFiles.TREE, uri.toString()).apply()
                revision++
            } catch (_: Exception) { message = "This folder could not keep access. Choose another folder." }
        }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if(event == Lifecycle.Event.ON_RESUME) revision++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    fun open(action: String, packageUri: Boolean = false) {
        try { context.startActivity(Intent(action).apply { if(packageUri) data = Uri.parse("package:${context.packageName}") }) }
        catch (_: Exception) { message = "This settings page is unavailable. Open Android Settings → Apps → Kompi." }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        KompiText("Phone access", style = KompiUi.typography.titleMedium)
        PairedComputerGrants(revision)
        KompiText("Android permissions", style = KompiUi.typography.titleSmall)
        KompiText("Android access is separate from what you allow the paired computer to do. Some actions also need a folder or an app selected below.", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
        val assistant = remember(revision) { selectedAssistant(context) }
        val grants = listOf(
            Triple("Contacts", arrayOf(Manifest.permission.READ_CONTACTS), false),
            Triple("Call history", arrayOf(Manifest.permission.READ_CALL_LOG), true),
            Triple("SMS", arrayOf(Manifest.permission.READ_SMS), true),
            Triple("Read calendar", arrayOf(Manifest.permission.READ_CALENDAR), false),
            Triple("Edit calendar", arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), false),
            Triple("Location while using Kompi", arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), false),
            Triple("Flashlight", arrayOf(Manifest.permission.CAMERA), false),
        )
        grants.forEach { (label, requested, restricted) ->
            val granted = remember(revision, label) { if(label.startsWith("Location")) hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) else requested.all { hasPermission(context, it) } }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    KompiText(label)
                    KompiText(if(restricted && !assistant) "Select Kompi as default assistant first" else if(granted) "Allowed" else "Not allowed", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
                }
                KompiTextButton(enabled = !restricted || assistant, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                    if(granted) open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) else permissions.launch(requested)
                }) { KompiText(if(granted) "Manage" else "Allow") }
            }
        }
        if(android.os.Build.VERSION.SDK_INT >= 34) {
            listOf("Steps" to "READ_STEPS", "Sleep" to "READ_SLEEP", "Exercise" to "READ_EXERCISE", "Heart rate" to "READ_HEART_RATE").forEach { (label, permission) ->
                val fullPermission = "android.permission.health.$permission"
                val granted = remember(revision, permission) { hasPermission(context, fullPermission) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    KompiText("Health Connect · $label", Modifier.weight(1f))
                    KompiTextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = {
                        if(granted) {
                            try { context.startActivity(Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS").putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)) }
                            catch (_: Exception) { message = "Open Health Connect in Android settings to manage access." }
                        } else permissions.launch(arrayOf(fullPermission))
                    }) { KompiText(if(granted) "Manage" else "Allow") }
                }
            }
        }
        val selectedFolder = remember(revision) { NativeFiles.tree(context) }
        KompiText("Approved document folder", style = KompiUi.typography.titleSmall)
        KompiText(if(selectedFolder == null) "No folder selected" else "One folder selected. Files outside this folder are not available.", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
        Row(Modifier.fillMaxWidth()) {
            KompiTextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { folderPicker.launch(selectedFolder) }) { KompiText(if(selectedFolder == null) "Choose folder" else "Change folder") }
            if(selectedFolder != null) KompiTextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { NativeFiles.forget(context); revision++ }) { KompiText("Remove access") }
        }
        val access = remember(revision) { listOf(
            Triple("Notification access", NativeNotifications.enabled(context), Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            Triple("App usage", NativePersonalData.usageAllowed(context), Settings.ACTION_USAGE_ACCESS_SETTINGS),
            Triple("Screen brightness", Settings.System.canWrite(context), Settings.ACTION_MANAGE_WRITE_SETTINGS),
            Triple("Do Not Disturb", context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
        ) }
        access.forEach { (label, granted, action) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    KompiText(label)
                    KompiText(if(granted) "Allowed" else "Not allowed", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
                }
                KompiTextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { open(action, action == Settings.ACTION_MANAGE_WRITE_SETTINGS) }) { KompiText("Manage") }
            }
        }
        var showApps by remember { mutableStateOf(false) }
        var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        var selected by remember(revision) { mutableStateOf(NativeNotifications.allowed(context)) }
        LaunchedEffect(showApps, revision) {
            if(showApps) apps = withContext(Dispatchers.IO) {
                val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                context.packageManager.queryIntentActivities(query, 0).distinctBy { it.activityInfo.packageName }
                    .map { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }.sortedBy { it.second.lowercase() }.take(1000)
            }
        }
        KompiButton(onClick = { showApps = !showApps }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { KompiText(if(showApps) "Hide notification apps" else "Notification apps (${selected.size})") }
        if(showApps) {
            KompiText("Limits notification actions to these apps. A separate grant above controls what the computer can do with them. Current notifications only; no archive. Some sensitive text may still be included.", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
            apps.forEach { (packageName, label) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    KompiText(label, Modifier.weight(1f))
                    KompiSwitch(checked = packageName in selected, modifier = Modifier.semantics { contentDescription = "Allow notification access for $label" }, onCheckedChange = { enabled ->
                        selected = if(enabled) selected + packageName else selected - packageName
                        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putStringSet(NativeNotifications.ALLOWED_PACKAGES, selected).apply()
                    })
                }
            }
        }
        message?.let { KompiText(it, style = KompiUi.typography.bodySmall, color = KompiUi.colors.error) }
    }
}
