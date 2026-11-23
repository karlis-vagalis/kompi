package com.kompi.app.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kompi.app.audio.InstalledParakeetModel
import com.kompi.app.audio.SpeechInputEngine
import com.kompi.app.ui.KompiButton
import com.kompi.app.ui.KompiDropdownMenu
import com.kompi.app.ui.KompiDropdownMenuItem
import com.kompi.app.ui.KompiLinearProgressIndicator
import com.kompi.app.ui.KompiText
import com.kompi.app.ui.KompiTextButton
import com.kompi.app.ui.KompiUi

internal sealed interface ParakeetInstallState {
    data object Checking : ParakeetInstallState
    data object Missing : ParakeetInstallState
    data class Downloading(val progress: Float) : ParakeetInstallState
    data object Cancelling : ParakeetInstallState
    data class Ready(val model: InstalledParakeetModel) : ParakeetInstallState
    data class Failed(val message: String) : ParakeetInstallState
}

@Composable
internal fun SpeechInputSettings(
    engine: SpeechInputEngine,
    parakeet: ParakeetInstallState,
    onSelect: (SpeechInputEngine) -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                KompiText("Recognition engine", style = KompiUi.typography.titleSmall)
                KompiText(engine.label, style = KompiUi.typography.bodyMedium, color = KompiUi.colors.onSurfaceVariant)
            }
            Box {
                KompiTextButton(onClick = { menu = true }, modifier = Modifier.heightIn(min = 48.dp)) { KompiText("Change") }
                KompiDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    SpeechInputEngine.entries.forEach { option ->
                        KompiDropdownMenuItem(text = { KompiText(option.label) }, onClick = { onSelect(option); menu = false })
                    }
                }
            }
        }
        if (engine == SpeechInputEngine.Parakeet) {
            KompiText("25-language auto-detection, including Latvian. Runs entirely on this phone using MediaPipe Audio Transcriber.",
                style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
            KompiText(when (parakeet) {
                ParakeetInstallState.Checking -> "Checking Parakeet model…"
                ParakeetInstallState.Missing -> "Not installed · 615 MB download"
                is ParakeetInstallState.Downloading -> "Downloading · ${(parakeet.progress * 100).toInt()}%"
                ParakeetInstallState.Cancelling -> "Cancelling download…"
                is ParakeetInstallState.Ready -> "Model installed and checksum-verified"
                is ParakeetInstallState.Failed -> parakeet.message
            }, style = KompiUi.typography.bodySmall, color = if (parakeet is ParakeetInstallState.Failed) KompiUi.colors.error else KompiUi.colors.onSurfaceVariant)
            if (parakeet is ParakeetInstallState.Downloading) {
                KompiLinearProgressIndicator(progress = { parakeet.progress }, modifier = Modifier.fillMaxWidth())
                KompiTextButton(onClick = onCancelDownload, modifier = Modifier.heightIn(min = 48.dp)) { KompiText("Cancel download") }
            } else if (parakeet !is ParakeetInstallState.Ready) {
                KompiButton(onClick = onDownload,
                    enabled = (parakeet == ParakeetInstallState.Missing || parakeet is ParakeetInstallState.Failed) && engine.supportedOnDevice(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { KompiText("Download Parakeet · 615 MB") }
            }
            if (!engine.supportedOnDevice()) KompiText("Parakeet requires a supported 64-bit Android device.", color = KompiUi.colors.error)
            KompiText("Language is automatic. Whistle's language choice and vocabulary hints are retained for Whistle; Parakeet does not use them.",
                style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
            KompiText("Parakeet TDT 0.6B v3 by NVIDIA · CC BY 4.0. LiteRT conversion by LiteRT Community.",
                style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
            KompiTextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/litert-community/parakeet-tdt-0.6b-v3")))
            }, modifier = Modifier.heightIn(min = 48.dp)) { KompiText("Model source and license") }
        }
    }
}
