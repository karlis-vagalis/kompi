package com.kompi.app.chat

import android.animation.ValueAnimator
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.composables.icons.lucide.R as LucideR
import com.kompi.app.ui.KompiUi
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The lower edge curls around the mic rather than painting a fake background over it. */
private class ComposerCradle(private val corner: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = with(density) {
        val w = size.width
        val h = size.height
        val r = minOf(corner.toPx(), h / 2)
        val c = w / 2
        // The button's center is 8dp above this lower edge (28dp radius,
        // with the surface ending 20dp above the composer's bottom).
        // A concentric 38dp circle gives the 28dp button an exact 10dp gap.
        val notch = 38.dp.toPx()
        val centerY = h - 8.dp.toPx()
        val fillet = 14.dp.toPx()
        val filletY = h - fillet
        val dy = filletY - centerY
        val tangentDistance = notch + fillet
        val dx = sqrt(tangentDistance * tangentDistance - dy * dy)
        val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        Outline.Generic(Path().apply {
            moveTo(r, 0f)
            lineTo(w - r, 0f)
            quadraticTo(w, 0f, w, r)
            lineTo(w, h - r)
            quadraticTo(w, h, w - r, h)
            lineTo(c + dx, h)
            // Externally tangent circles join the straight edge and circular
            // cutout without approximating either radius with a Bézier curve.
            arcTo(Rect(c + dx - fillet, filletY - fillet, c + dx + fillet, h), 90f, 90f + angle, false)
            arcTo(Rect(c - notch, centerY - notch, c + notch, centerY + notch), angle, -180f - 2f * angle, false)
            arcTo(Rect(c - dx - fillet, filletY - fillet, c - dx + fillet, h), -angle, 90f + angle, false)
            lineTo(r, h)
            quadraticTo(0f, h, 0f, h - r)
            lineTo(0f, r)
            quadraticTo(0f, 0f, r, 0f)
            close()
        })
    }
}

@Composable
fun RadialComposer(
    expanded: Boolean,
    menuExpanded: Boolean,
    onMenuExpanded: (Boolean) -> Unit,
    recording: Boolean,
    micEnabled: Boolean,
    canStartNewChat: Boolean,
    canAttach: Boolean,
    onDictation: () -> Unit,
    onNewChat: () -> Unit,
    onPhoto: () -> Unit,
    onFile: () -> Unit,
    onModelSelection: () -> Unit,
    modelLabel: String,
    modelSelectionEnabled: Boolean,
    modifier: Modifier = Modifier,
    prompt: @Composable () -> Unit,
    send: @Composable () -> Unit,
) {
    val motion = ValueAnimator.areAnimatorsEnabled()
    val progress by animateFloatAsState(
        if (expanded) 1f else 0f,
        animationSpec = if (motion) tween(180, easing = FastOutSlowInEasing) else snap(),
        label = "composer-expansion",
    )
    val colors = KompiUi.colors
    Box(modifier.fillMaxWidth()) {
        Layout(
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)
                .background(colors.composerSurface, ComposerCradle(22.dp + 4.dp * progress)),
            content = { Box { prompt() }; Box { send() } },
        ) { measurables, constraints ->
            val inset = 8.dp.roundToPx()
            val control = 48.dp.roundToPx()
            val width = constraints.maxWidth
            // Text never enters the cradle, even in the dense / multiline state.
            val fieldWidth = (width - inset * 2 - (control * (1 - progress)).roundToInt()).coerceAtLeast(1)
            val field = measurables[0].measure(Constraints(maxWidth = fieldWidth, minWidth = fieldWidth))
            val action = measurables[1].measure(Constraints.fixed(control, control))
            val fieldHeight = maxOf(field.height, control)
            val bottomRow = (control * progress).roundToInt()
            val actionY = inset + (((fieldHeight - control) / 2f) * (1 - progress) + fieldHeight * progress).roundToInt()
            val height = inset + fieldHeight + bottomRow + 50.dp.roundToPx()
            layout(width, height) {
                field.placeRelative(inset, inset)
                action.placeRelative(width - inset - control, actionY)
            }
        }
        Box(
            Modifier.align(Alignment.BottomCenter).size(56.dp).clip(CircleShape)
                .graphicsLayer { alpha = if (micEnabled) 1f else 0.42f }
                .background(colors.primary)
                .combinedClickable(
                    onClick = onDictation,
                    enabled = micEnabled,
                    role = Role.Button,
                    onLongClickLabel = "Show chat actions",
                    onLongClick = { onMenuExpanded(true) },
                )
                .semantics {
                    contentDescription = if (recording) "Stop dictation" else "Start dictation"
                    stateDescription = if (recording) "Recording" else if (menuExpanded) "Chat actions expanded" else "Hold for chat actions"
                    customActions = listOf(CustomAccessibilityAction("Show chat actions") { onMenuExpanded(true); true })
                },
            contentAlignment = Alignment.Center,
        ) {
            ChatLucideIcon(
                if (recording) LucideR.drawable.lucide_ic_square else LucideR.drawable.lucide_ic_mic,
                null, Modifier.size(24.dp), tint = colors.onPrimary,
            )
        }
        Box(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 20.dp).fillMaxWidth(0.36f)) {
            com.kompi.app.ui.KompiTextButton(onClick = onModelSelection, enabled = modelSelectionEnabled, modifier = Modifier.heightIn(min = 48.dp)) {
                com.kompi.app.ui.KompiText(modelLabel, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        if (menuExpanded) {
            val offset = with(LocalDensity.current) { 4.dp.roundToPx() }
            Popup(
                alignment = Alignment.BottomCenter,
                offset = IntOffset(0, -offset),
                onDismissRequest = { onMenuExpanded(false) },
                properties = PopupProperties(focusable = true),
            ) {
                var arrived by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { arrived = true }
                val fan by animateFloatAsState(
                    if (arrived) 1f else 0f,
                    animationSpec = if (motion) tween(180, easing = FastOutSlowInEasing) else snap(),
                    label = "composer-actions",
                )
                Box(Modifier.width(278.dp).height(154.dp)
                    // Include the mic's animation origin; empty-area taps dismiss the popup,
                    // rather than trapping a tap there or accidentally starting dictation.
                    .pointerInput(Unit) { detectTapGestures(onTap = { onMenuExpanded(false) }) }
                    .semantics { paneTitle = "Chat actions" }) {
                    data class Action(val label: String, val icon: Int, val enabled: Boolean, val x: Dp, val y: Dp, val click: () -> Unit)
                    val actions = listOf(
                        Action("New chat", LucideR.drawable.lucide_ic_plus, canStartNewChat, 0.dp, 45.dp, onNewChat),
                        Action("Choose photos", LucideR.drawable.lucide_ic_image, canAttach, 74.dp, 0.dp, onPhoto),
                        Action("Choose files", LucideR.drawable.lucide_ic_paperclip, canAttach, 148.dp, 0.dp, onFile),
                        Action("Model and effort", LucideR.drawable.lucide_ic_sliders_horizontal, modelSelectionEnabled, 222.dp, 45.dp, onModelSelection),
                    )
                    actions.forEach { action ->
                        ChatActionButton(
                            label = action.label,
                            onClick = { onMenuExpanded(false); action.click() },
                            enabled = action.enabled,
                            tone = ChatActionTone.Soft,
                            visualSize = 56.dp,
                            modifier = Modifier.offset(action.x, action.y).size(56.dp).graphicsLayer {
                                alpha = fan
                                translationX = (111.dp - action.x).toPx() * (1 - fan)
                                translationY = (102.dp - action.y).toPx() * (1 - fan)
                                scaleX = 0.8f + 0.2f * fan
                                scaleY = scaleX
                            },
                        ) { ChatLucideIcon(action.icon, null, Modifier.size(22.dp), tint = colors.onSecondaryContainer) }
                    }
                }
            }
        }
    }
}
