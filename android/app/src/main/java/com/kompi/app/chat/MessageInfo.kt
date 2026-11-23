package com.kompi.app.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.composables.icons.lucide.R as LucideR
import com.kompi.app.diagnostics.ResponsePerformance
import com.kompi.app.transport.PiSessionStore
import com.kompi.app.ui.KompiText
import androidx.compose.ui.text.font.FontFamily
import com.kompi.app.ui.KompiUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun MessagePerformanceInfo(messageKey: String) {
    var open by remember(messageKey) { mutableStateOf(false) }
    Box {
        ChatActionButton(label = "Message performance", onClick = { open = !open }) {
            ChatLucideIcon(LucideR.drawable.lucide_ic_info, null, Modifier.size(17.dp))
        }
        if (open) InfoPopover(onDismiss = { open = false }) {
            val flow = remember(messageKey) { ResponsePerformance.observe(messageKey) }
            val measurement = flow?.collectAsState()?.value
            KompiText("Response performance", style = KompiUi.typography.titleSmall)
            if (measurement == null) {
                KompiText("Timing metrics unavailable.", style = KompiUi.typography.bodySmall)
            } else {
                measurement.pi?.assistantMessages?.takeIf { it > 1 }?.let {
                    KompiText("Entire request · $it assistant messages", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
                }
                fun seconds(ms: Number?): String = ms?.let { String.format(Locale.ROOT, "%.3f s", it.toDouble() / 1000.0) } ?: "—"
                InfoValue("First token", seconds(measurement.firstTextMs))
                InfoValue("Total", seconds(measurement.totalMs))
                InfoValue("Speed", measurement.tokensPerSecond?.let { String.format(Locale.ROOT, "%.1f tok/s", it) } ?: "—")
                InfoValue("Chunks", measurement.chunks.toString())
                InfoValue("Acknowledged", seconds(measurement.ackMs))
                InfoValue("First draw", seconds(measurement.firstDrawMs))
                InfoValue("Receipt → draw", seconds(measurement.firstDrawMs?.let { draw -> measurement.firstTextMs?.let { draw - it } }))
                measurement.pi?.let { pi ->
                    InfoValue("Pi first text", seconds(pi.firstTextMs))
                    InfoValue("Pi execution", seconds(pi.totalMs))
                    InfoValue("Daemon dispatch", seconds(pi.daemonTotalMs))
                    InfoValue("Output tokens", pi.outputTokens?.toString() ?: "Not reported")
                    InfoValue("Reasoning tokens", pi.reasoningTokens?.toString() ?: "Not reported")
                    pi.model?.let { InfoValue("Model", it) }
                    pi.provider?.let { InfoValue("Provider", it) }
                }
                KompiText("First token means first received text chunk. Speed uses Pi-reported output tokens over the whole Pi execution, including tools and thinking. Output tokens may include reasoning. Phone and daemon timings use their own clocks.",
                    style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun PiEntryInfo(details: PiSessionStore.Details, anchor: (@Composable (() -> Unit) -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    Box {
        if (anchor != null) anchor { open = !open }
        else ChatActionButton(label = "View Pi entry", onClick = { open = !open }) {
            ChatLucideIcon(LucideR.drawable.lucide_ic_braces, null, Modifier.size(17.dp))
        }
        if (open) InfoPopover(onDismiss = { open = false }) {
            KompiText("Pi entry", style = KompiUi.typography.titleSmall)
            val preview by produceState<String?>(null, details) {
                value = withContext(Dispatchers.Default) {
                    val full = details.pretty()
                    if (full.length > 64_000) full.take(64_000) + "\n\nPreview limited to 64,000 characters. The original Pi entry is unchanged."
                    else full
                }
            }
            val display = preview ?: "Loading entry…"
            SelectionContainer {
                Column(Modifier.fillMaxWidth()) {
                    KompiText(display, Modifier.fillMaxWidth().semantics { contentDescription = display },
                        style = KompiUi.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
            }
        }
    }
}

@Composable
private fun InfoValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KompiText(label, Modifier.weight(1f), style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
        KompiText(value, Modifier.weight(1f), style = KompiUi.typography.bodySmall)
    }
}

@Composable
private fun InfoPopover(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val offset = with(LocalDensity.current) { 48.dp.roundToPx() }
    Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, offset), onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)) {
        Column(Modifier.width(296.dp).heightIn(max = 440.dp)
            .background(KompiUi.colors.surfaceContainerLow, KompiUi.shapes.medium)
            .border(1.dp, KompiUi.colors.outlineVariant, KompiUi.shapes.medium)
            .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}
