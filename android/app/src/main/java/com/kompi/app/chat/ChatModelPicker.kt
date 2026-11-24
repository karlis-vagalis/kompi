package com.kompi.app.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kompi.app.protocol.generated.ChatModelConfiguration
import com.kompi.app.protocol.generated.ModelCatalog
import com.kompi.app.ui.*
import kotlin.math.roundToInt

@Composable
fun ChatModelPicker(
    catalog: ModelCatalog?,
    selected: ChatModelConfiguration?,
    confirmed: ChatModelConfiguration?,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onApply: (ChatModelConfiguration) -> Unit,
    onDismiss: () -> Unit,
) {
    var choice by remember(selected, catalog) { mutableStateOf(selected ?: catalog?.defaultConfiguration) }
    var expanded by remember { mutableStateOf(false) }
    var applyError by remember { mutableStateOf<String?>(null) }
    val model = catalog?.models?.firstOrNull { it.provider == choice?.provider && it.modelId == choice?.modelId }
    val valid = model != null && choice?.effort in model.efforts
    KompiAlertDialog(
        onDismissRequest = onDismiss,
        title = { KompiText("Model and effort", style = KompiUi.typography.titleLarge) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                KompiText("Applies to your next message. A running reply and already queued messages keep their original selection.", style = KompiUi.typography.bodySmall)
                if (loading) KompiText("Loading configured models…", style = KompiUi.typography.bodySmall)
                Box(Modifier.fillMaxWidth()) {
                    KompiOutlinedButton(onClick = { expanded = true }, enabled = !catalog?.models.isNullOrEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "Choose chat model" }) {
                        Column(Modifier.fillMaxWidth()) {
                            KompiText(model?.name ?: choice?.modelId ?: "Choose model", maxLines = 2, overflow = TextOverflow.Ellipsis)
                            (model?.provider ?: choice?.provider)?.let { KompiText(it, style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant) }
                        }
                    }
                    KompiDropdownMenu(expanded, { expanded = false }, Modifier.widthIn(max = 320.dp)) {
                        catalog?.models?.forEach { item ->
                            KompiDropdownMenuItem(
                                text = { Column { KompiText(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis); KompiText(item.provider, style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant) } },
                                onClick = {
                                    val effort = choice?.effort?.takeIf { it in item.efforts }
                                        ?: catalog.defaultConfiguration?.takeIf { it.provider == item.provider && it.modelId == item.modelId }?.effort?.takeIf { it in item.efforts }
                                        ?: item.efforts.first()
                                    choice = ChatModelConfiguration(effort = effort, modelId = item.modelId, provider = item.provider)
                                    applyError = null
                                    expanded = false
                                },
                            )
                        }
                    }
                }
                if (model != null) {
                    KompiText("Effort: ${choice?.effort?.replaceFirstChar { it.uppercase() }}", style = KompiUi.typography.bodyMedium)
                    if (model.efforts.size > 1) {
                        EffortSlider(model.efforts, model.efforts.indexOf(choice?.effort).coerceAtLeast(0)) { index ->
                            choice = choice?.copy(effort = model.efforts[index])
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            KompiText(model.efforts.first(), style = KompiUi.typography.bodySmall)
                            KompiText(model.efforts.last(), style = KompiUi.typography.bodySmall)
                        }
                    } else KompiText("This model has no adjustable effort.", style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant)
                }
                if (selected != null) KompiText(
                    if (selected == confirmed) "Current selection confirmed by your computer." else "Selection saved on this phone; pending your next message.",
                    style = KompiUi.typography.bodySmall, color = KompiUi.colors.onSurfaceVariant,
                )
                if (catalog?.models?.isEmpty() == true) KompiText("No configured models. Set up a provider in Pi on your computer, then reload.")
                (applyError ?: error)?.let { KompiText(it, style = KompiUi.typography.bodySmall, color = KompiUi.colors.error) }
                KompiTextButton(onClick = onRefresh, enabled = !loading) { KompiText("Reload models") }
            }
        },
        confirmButton = {
            KompiTextButton(onClick = {
                try { onApply(checkNotNull(choice)); onDismiss() }
                catch (failure: IllegalArgumentException) { applyError = failure.message }
                catch (failure: IllegalStateException) { applyError = failure.message }
            }, enabled = valid) { KompiText("Apply") }
        },
        dismissButton = { KompiTextButton(onClick = onDismiss) { KompiText("Cancel") } },
    )
}

/** A discrete, accessible slider whose stops come only from the host's supported efforts. */
@Composable
private fun EffortSlider(levels: List<String>, index: Int, onChange: (Int) -> Unit) {
    val colors = KompiUi.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val inset = with(LocalDensity.current) { 12.dp.toPx() }
    var width by remember { mutableFloatStateOf(0f) }
    val latestChange by rememberUpdatedState(onChange)
    fun select(x: Float) {
        val fraction = ((x - inset) / (width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
        latestChange(((if (rtl) 1f - fraction else fraction) * levels.lastIndex).roundToInt())
    }
    Canvas(Modifier.fillMaxWidth().height(48.dp).onSizeChanged { width = it.width.toFloat() }
        .semantics {
            contentDescription = "Reasoning effort"
            stateDescription = levels[index]
            progressBarRangeInfo = ProgressBarRangeInfo(index.toFloat(), 0f..levels.lastIndex.toFloat(), levels.size - 2)
            setProgress { value -> latestChange(value.roundToInt().coerceIn(levels.indices)); true }
        }
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false
            else when (event.key) {
                Key.DirectionLeft, Key.DirectionRight -> {
                    val forward = (event.key == Key.DirectionRight) != rtl
                    latestChange((index + if (forward) 1 else -1).coerceIn(levels.indices)); true
                }
                else -> false
            }
        }.focusable()
        .pointerInput(levels, rtl, width) { detectTapGestures { select(it.x) } }
        .pointerInput(levels, rtl, width) { detectHorizontalDragGestures { change, _ -> change.consume(); select(change.position.x) } }
    ) {
        val start = Offset(inset, size.height / 2)
        val end = Offset(size.width - inset, size.height / 2)
        val fraction = index.toFloat() / levels.lastIndex
        val thumb = Offset(inset + (size.width - 2 * inset) * if (rtl) 1f - fraction else fraction, start.y)
        drawLine(colors.outlineVariant, start, end, 4.dp.toPx())
        drawLine(colors.primary, if (rtl) end else start, thumb, 4.dp.toPx())
        levels.indices.forEach { stop ->
            drawCircle(colors.onSurfaceVariant, 2.dp.toPx(), Offset(inset + (size.width - 2 * inset) * stop / levels.lastIndex, start.y))
        }
        drawCircle(colors.primary, 10.dp.toPx(), thumb)
    }
}
