package com.kompi.app.attachments

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kompi.app.protocol.generated.OfferedArtifact
import com.kompi.app.transport.ArtifactDownloadTarget
import com.kompi.app.transport.ArtifactSaveException
import com.kompi.app.ui.KompiSurface
import com.kompi.app.ui.KompiText as Text
import com.kompi.app.ui.KompiTextButton as TextButton
import com.kompi.app.ui.KompiUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val PickerTargetSaver = listSaver<ArtifactDownloadTarget?, Any>(
    save = { target -> target?.run { listOf(peer, conversation, offer.artifactId, offer.fileName, offer.length, offer.sha256) }.orEmpty() },
    restore = { parts -> ArtifactDownloadTarget(parts[0] as String, parts[1] as String, OfferedArtifact(
        artifactId = parts[2] as String, fileName = parts[3] as String, length = parts[4] as Long, sha256 = parts[5] as String,
    )) },
)

internal data class ArtifactFeedback(val target: ArtifactDownloadTarget, val text: String)
internal data class ArtifactPicker(
    val busy: Boolean,
    val feedback: ArtifactFeedback?,
    val choose: (ArtifactDownloadTarget) -> Unit,
)

@Composable
internal fun rememberArtifactPicker(save: suspend (ArtifactDownloadTarget, Uri) -> Unit): ArtifactPicker {
    val scope = rememberCoroutineScope()
    var pending by rememberSaveable(stateSaver = PickerTargetSaver) { mutableStateOf<ArtifactDownloadTarget?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<ArtifactFeedback?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { destination ->
        val target = pending
        pending = null
        // A cancelled or restored-without-intent picker must never send a fetch.
        if (target != null && destination != null) {
            downloading = true
            feedback = ArtifactFeedback(target, "Downloading…")
            scope.launch {
                try {
                    save(target, destination)
                    feedback = ArtifactFeedback(target, "Saved")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: ArtifactSaveException) {
                    feedback = ArtifactFeedback(target, failure.message.orEmpty())
                } finally {
                    downloading = false
                }
            }
        }
    }
    return ArtifactPicker(downloading || pending != null, feedback) { target ->
        if (!downloading && pending == null) {
            pending = target
            feedback = null
            try {
                launcher.launch(target.offer.fileName)
            } catch (_: android.content.ActivityNotFoundException) {
                pending = null
                feedback = ArtifactFeedback(target, "System file picker unavailable.")
            }
        }
    }
}

@Composable
internal fun ArtifactPicker.Offer(offer: OfferedArtifact, target: ArtifactDownloadTarget?, connected: Boolean, supported: Boolean) {
    val status = feedback?.takeIf { it.target == target }?.text ?: when {
        !connected -> "Reconnect to download."
        !supported -> "Downloads unavailable on this computer."
        else -> null
    }
    ArtifactOfferRow(offer, target != null && connected && supported && !busy, status) {
        target?.let(choose)
    }
}

@Composable
private fun ArtifactOfferRow(offer: OfferedArtifact, enabled: Boolean, status: String?, onDownload: () -> Unit) {
    val size = Formatter.formatShortFileSize(LocalContext.current, offer.length)
    KompiSurface(color = KompiUi.colors.surfaceContainerLow, shape = KompiUi.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(offer.fileName, style = KompiUi.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(size, style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
            if (status != null) Text(status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
            TextButton(onClick = onDownload, enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Download ${offer.fileName}" }) { Text("Download") }
        }
    }
}
