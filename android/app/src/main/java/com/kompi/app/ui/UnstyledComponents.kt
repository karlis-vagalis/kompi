package com.kompi.app.ui

import androidx.activity.compose.BackHandler
import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import android.graphics.Rect
import android.os.Build
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.snapshotFlow
import com.composeunstyled.TextInput
import com.composeunstyled.UnstyledButton
import com.composeunstyled.UnstyledTextField
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlinx.coroutines.flow.collect

@Immutable
data class KompiColors(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val surfaceContainerLow: Color,
    val surfaceContainerLowest: Color,
    val outline: Color,
    val outlineVariant: Color,
    val tertiary: Color,
    val error: Color,
    val composerSurface: Color,
    val onComposer: Color,
    val composerMuted: Color,
)

@Immutable
data class KompiTypography(
    val headlineLarge: TextStyle,
    val headlineSmall: TextStyle,
    val titleLarge: TextStyle,
    val titleMedium: TextStyle,
    val titleSmall: TextStyle,
    val bodyLarge: TextStyle,
    val bodyMedium: TextStyle,
    val bodySmall: TextStyle,
    val labelLarge: TextStyle,
    val labelMedium: TextStyle,
    val labelSmall: TextStyle,
)

@Immutable
data class KompiShapes(
    val extraSmall: RoundedCornerShape,
    val small: RoundedCornerShape,
    val medium: RoundedCornerShape,
    val large: RoundedCornerShape,
    val extraLarge: RoundedCornerShape,
)

@Immutable
data class KompiStyle(val colors: KompiColors, val typography: KompiTypography, val shapes: KompiShapes)

private val LocalKompiStyle = staticCompositionLocalOf {
    KompiStyle(
        colors = KompiColors(
            primary = Color(0xFF0066CC), onPrimary = Color.White, primaryContainer = Color(0xFFDCEBFF), onPrimaryContainer = Color(0xFF002A5C),
            secondary = Color(0xFF6E6E73), secondaryContainer = Color(0xFFE5E5EA), onSecondaryContainer = Color(0xFF1C1C1E),
            background = Color(0xFFF5F5F7), onBackground = Color(0xFF1C1C1E), surface = Color.White, onSurface = Color(0xFF1C1C1E),
            surfaceVariant = Color(0xFFE5E5EA), onSurfaceVariant = Color(0xFF636366), surfaceContainerLow = Color.White,
            surfaceContainerLowest = Color.White, outline = Color(0xFF8E8E93), outlineVariant = Color(0xFFD1D1D6),
            tertiary = Color(0xFF286528), error = Color(0xFFB3261E),
            composerSurface = Color(0xFF202020), onComposer = Color(0xFFF5F5F5), composerMuted = Color(0xFFBDBDBD),
        ),
        typography = defaultKompiTypography(),
        shapes = KompiShapes(RoundedCornerShape(8.dp), RoundedCornerShape(12.dp), RoundedCornerShape(16.dp), RoundedCornerShape(24.dp), RoundedCornerShape(32.dp)),
    )
}

val LocalKompiContentColor = staticCompositionLocalOf { Color.Unspecified }

@Composable
fun currentKompiContentColor(): Color = LocalKompiContentColor.current.takeUnless { it == Color.Unspecified } ?: KompiUi.colors.onSurface

object KompiUi {
    val colors: KompiColors @Composable get() = LocalKompiStyle.current.colors
    val colorScheme: KompiColors @Composable get() = LocalKompiStyle.current.colors
    val typography: KompiTypography @Composable get() = LocalKompiStyle.current.typography
    val shapes: KompiShapes @Composable get() = LocalKompiStyle.current.shapes
}

@Composable
fun KompiStyleProvider(style: KompiStyle, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalKompiStyle provides style, LocalKompiContentColor provides style.colors.onSurface, content = content)
}

fun defaultKompiTypography() = KompiTypography(
    headlineLarge = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Normal),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Normal),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Normal),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun KompiText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = KompiUi.typography.bodyMedium,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
) {
    val resolvedColor = color.takeUnless { it == Color.Unspecified } ?: LocalKompiContentColor.current.takeUnless { it == Color.Unspecified } ?: KompiUi.colors.onSurface
    BasicText(text = text, modifier = modifier, style = style.copy(color = resolvedColor, fontWeight = fontWeight ?: style.fontWeight, textAlign = textAlign ?: style.textAlign), softWrap = softWrap, maxLines = maxLines, overflow = overflow)
}

@Composable
fun KompiText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = KompiUi.typography.bodyMedium,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val resolvedColor = color.takeUnless { it == Color.Unspecified } ?: LocalKompiContentColor.current.takeUnless { it == Color.Unspecified } ?: KompiUi.colors.onSurface
    BasicText(text = text, modifier = modifier, style = style.copy(color = resolvedColor), maxLines = maxLines, overflow = overflow)
}

@Composable
fun KompiSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(0.dp),
    color: Color = KompiUi.colors.surface,
    contentColor: Color = KompiUi.colors.onSurface,
    shadowElevation: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    Box(modifier.shadow(shadowElevation, shape).clip(shape).background(color)) {
        CompositionLocalProvider(LocalKompiContentColor provides contentColor, content = content)
    }
}

@Composable
fun KompiSectionSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    KompiSurface(modifier.fillMaxWidth(), shape = KompiUi.shapes.medium, color = KompiUi.colors.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
fun KompiDivider(modifier: Modifier = Modifier, color: Color = KompiUi.colors.outlineVariant) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

@Composable
fun KompiButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) = StyledButton(onClick, modifier, enabled, KompiUi.colors.primary, KompiUi.colors.onPrimary, null, content)

@Composable
fun KompiOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) = StyledButton(onClick, modifier, enabled, Color.Transparent, KompiUi.colors.primary, KompiUi.colors.outlineVariant, content)

@Composable
fun KompiFilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) = StyledButton(onClick, modifier, enabled, KompiUi.colors.secondaryContainer, KompiUi.colors.onSecondaryContainer, null, content)

@Composable
fun KompiTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = KompiUi.colors.primary,
    content: @Composable () -> Unit,
) = StyledButton(onClick, modifier, enabled, Color.Transparent, contentColor, null, content, horizontalPadding = 10.dp)

@Composable
private fun StyledButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    fill: Color,
    foreground: Color,
    outline: Color?,
    content: @Composable () -> Unit,
    horizontalPadding: Dp = 20.dp,
) {
    val shape = RoundedCornerShape(50)
    UnstyledButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).clip(shape)
            .then(if (outline == null) Modifier else Modifier.border(1.dp, outline, shape))
            .background(fill).alpha(if (enabled) 1f else 0.45f),
        contentAlignment = Alignment.Center,
        indication = LocalIndication.current,
    ) {
        CompositionLocalProvider(LocalKompiContentColor provides foreground) {
            Row(Modifier.padding(horizontal = horizontalPadding, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, content = { content() })
        }
    }
}

@Composable
fun KompiIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    UnstyledButton(onClick = onClick, enabled = enabled, modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).alpha(if (enabled) 1f else 0.45f), contentAlignment = Alignment.Center, indication = LocalIndication.current, content = content)
}

@Composable
fun KompiSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    UnstyledButton(
        onClick = { onCheckedChange(!checked) },
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
            role = Role.Switch
            toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
        },
        contentAlignment = Alignment.Center,
        indication = LocalIndication.current,
    ) {
        Box(Modifier.width(48.dp).height(28.dp).clip(RoundedCornerShape(50)).background(if (checked) KompiUi.colors.primary else KompiUi.colors.surfaceVariant).padding(horizontal = 4.dp), contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(if (checked) 20.dp else 20.dp).clip(CircleShape).background(if (checked) KompiUi.colors.onPrimary else KompiUi.colors.onSurfaceVariant))
        }
    }
}

@Composable
fun KompiRadioButton(selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    KompiIconButton(onClick = { onClick?.invoke() }, modifier = modifier.semantics { role = Role.RadioButton; toggleableState = if (selected) ToggleableState.On else ToggleableState.Off }) {
        Box(Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (selected) KompiUi.colors.primary else KompiUi.colors.outline, CircleShape).padding(4.dp)) {
            if (selected) Box(Modifier.fillMaxSize().clip(CircleShape).background(KompiUi.colors.primary))
        }
    }
}

@Composable
fun KompiFilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier) {
    val fill = if (selected) KompiUi.colors.primaryContainer else Color.Transparent
    val foreground = if (selected) KompiUi.colors.onPrimaryContainer else KompiUi.colors.onSurface
    StyledButton(onClick, modifier, true, fill, foreground, if (selected) null else KompiUi.colors.outlineVariant, label, horizontalPadding = 14.dp)
}

@Composable
fun KompiAssistChip(onClick: () -> Unit, enabled: Boolean, label: @Composable () -> Unit, leadingIcon: @Composable (() -> Unit)? = null, modifier: Modifier = Modifier, selected: Boolean = false) {
    val fill = if (selected) KompiUi.colors.primaryContainer else KompiUi.colors.surfaceVariant
    val foreground = if (selected) KompiUi.colors.onPrimaryContainer else KompiUi.colors.onSurface
    StyledButton(onClick, modifier, enabled, fill, foreground, null, {
        if (leadingIcon != null) { leadingIcon(); Spacer(Modifier.width(8.dp)) }
        label()
    }, horizontalPadding = 12.dp)
}

@Composable
fun KompiHorizontalProgress(progress: Float, modifier: Modifier = Modifier) {
    val value = progress.coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(KompiUi.colors.surfaceVariant)) {
        Box(Modifier.fillMaxWidth(value).fillMaxHeight().background(KompiUi.colors.primary))
    }
}

@Composable
fun KompiLinearProgressIndicator(progress: () -> Float, modifier: Modifier = Modifier) = KompiHorizontalProgress(progress(), modifier)

@Composable
fun KompiCircularProgress(modifier: Modifier = Modifier, strokeWidth: Dp = 3.dp) {
    val transition = rememberInfiniteTransition(label = "progress")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "rotation")
    val color = KompiUi.colors.primary
    Canvas(modifier.size(24.dp)) {
        drawArc(color, startAngle = angle, sweepAngle = 270f, useCenter = false, style = Stroke(strokeWidth.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun KompiDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.TopStart, content: @Composable ColumnScope.() -> Unit,
) {
    if (expanded) {
        val density = LocalDensity.current
        val offset = with(density) { IntOffset(0, 48.dp.roundToPx()) }
        Popup(alignment = alignment, offset = offset, onDismissRequest = onDismissRequest, properties = PopupProperties(focusable = true)) {
            KompiSurface(modifier.widthIn(min = 180.dp).heightIn(max = 420.dp), shape = KompiUi.shapes.medium, color = KompiUi.colors.surface, shadowElevation = 8.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()), content = content)
            }
        }
    }
}

@Composable
fun KompiDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    KompiIconButton(onClick, modifier.fillMaxWidth().heightIn(min = 48.dp).alpha(if (enabled) 1f else 0.45f), enabled) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { text() }
            trailingIcon?.invoke()
        }
    }
}

@Composable
fun KompiAlertDialog(
    onDismissRequest: () -> Unit,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        KompiSurface(Modifier.fillMaxWidth(), shape = KompiUi.shapes.large, color = KompiUi.colors.surface, shadowElevation = 20.dp) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                title?.let { it() }
                text?.let { it() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

@Composable
fun KompiOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = 5,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    isError: Boolean = false,
) {
    val state = rememberTextFieldState(value)
    LaunchedEffect(value) {
        if (state.text.toString() != value) state.edit { replace(0, length, value) }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { if (it != value) onValueChange(it) }
    }
    KompiOutlinedTextField(state, modifier, label, placeholder, singleLine, minLines, maxLines, keyboardOptions, isError)
}

@Composable
fun KompiOutlinedTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = 5,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    isError: Boolean = false,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        label?.let { it() }
        Box(Modifier.fillMaxWidth().clip(KompiUi.shapes.small).background(KompiUi.colors.surface).border(1.dp, if (isError) KompiUi.colors.error else KompiUi.colors.outlineVariant, KompiUi.shapes.small).padding(horizontal = 12.dp, vertical = 10.dp)) {
            UnstyledTextField(
                state = state,
                modifier = Modifier.fillMaxWidth().heightIn(min = if (singleLine) 28.dp else (minLines * 20).dp),
                enabled = true,
                accessibilityLabel = "Text input",
                cursorBrush = SolidColor(KompiUi.colors.primary),
                textStyle = KompiUi.typography.bodyMedium.copy(color = KompiUi.colors.onSurface),
                lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(minHeightInLines = minLines, maxHeightInLines = maxLines),
                keyboardOptions = keyboardOptions,
            ) {
                TextInput(placeholder = { placeholder?.invoke() })
            }
        }
    }
}

@Composable
fun KompiListItem(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        leadingContent?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) { headlineContent(); supportingContent?.invoke() }
        trailingContent?.invoke()
    }
}

@Composable
fun KompiScaffold(
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Transparent,
    contentWindowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    topBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val insets = contentWindowInsets.asPaddingValues()
    Column(modifier.fillMaxSize().background(containerColor)) {
        topBar()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            content(insets)
        }
    }
}

enum class DrawerValue { Open, Closed }

@Stable
class DrawerState(initialValue: DrawerValue) {
    private val initialProgress = if (initialValue == DrawerValue.Open) 1f else 0f
    internal val progress = Animatable(initialProgress)
    var isOpen by mutableStateOf(initialValue == DrawerValue.Open)
        private set

    suspend fun open() {
        isOpen = true
        if (ValueAnimator.areAnimatorsEnabled()) {
            progress.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow))
        } else {
            progress.snapTo(1f)
        }
    }

    suspend fun close() {
        isOpen = false
        if (ValueAnimator.areAnimatorsEnabled()) {
            progress.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
        } else {
            progress.snapTo(0f)
        }
    }

    internal suspend fun dragBy(fractionDelta: Float) {
        val value = (progress.value + fractionDelta).coerceIn(0f, 1f)
        progress.snapTo(value)
        isOpen = value >= 0.5f
    }

    internal suspend fun settle(velocity: Float, dismissThreshold: Float) {
        val opening = if (abs(velocity) > 0.65f) velocity > 0f else progress.value >= dismissThreshold
        isOpen = opening
        val target = if (opening) 1f else 0f
        if (ValueAnimator.areAnimatorsEnabled()) {
            progress.animateTo(
                target,
                spring(stiffness = Spring.StiffnessMediumLow),
                initialVelocity = velocity,
            )
        } else {
            progress.snapTo(target)
        }
    }
}

private sealed interface DrawerDragCommand {
    data class Delta(val fractionDelta: Float) : DrawerDragCommand
    data class Settle(val velocity: Float) : DrawerDragCommand
}

private fun Modifier.drawerDragGesture(
    drawerState: DrawerState,
    gesturesEnabled: Boolean,
    containerWidth: Float,
    drawerWidth: Float,
    edgeGestureLimit: Float,
    layoutDirection: LayoutDirection,
): Modifier = pointerInput(gesturesEnabled, containerWidth, drawerWidth, edgeGestureLimit, layoutDirection) {
    if (!gesturesEnabled || drawerWidth <= 0f) return@pointerInput
    coroutineScope {
        val dragCommands = Channel<DrawerDragCommand>(Channel.UNLIMITED)
        val dragConsumer = launch {
            var settling: kotlinx.coroutines.Job? = null
            for (command in dragCommands) {
                when (command) {
                    is DrawerDragCommand.Delta -> {
                        settling?.cancelAndJoin()
                        settling = null
                        drawerState.dragBy(command.fractionDelta)
                    }
                    is DrawerDragCommand.Settle -> {
                        settling?.cancelAndJoin()
                        settling = launch { drawerState.settle(command.velocity, 0.5f) }
                    }
                }
            }
            settling?.join()
        }
        try {
            var startX = 0f
            var acceptsDrag = false
            var trackingDrag = false
            val tracker = VelocityTracker()
            val isRtl = layoutDirection == LayoutDirection.Rtl
            detectHorizontalDragGestures(
                onDragStart = { position ->
                    startX = position.x
                    trackingDrag = false
                    val visibleProgress = drawerState.progress.value.coerceIn(0f, 1f)
                    val physicalEdge = if (isRtl) containerWidth - edgeGestureLimit else edgeGestureLimit
                    val withinOpeningEdge = if (isRtl) startX >= physicalEdge else startX <= physicalEdge
                    val visibleSheetWidth = drawerWidth * visibleProgress
                    val withinVisibleSheet = if (isRtl) startX >= containerWidth - visibleSheetWidth else startX <= visibleSheetWidth
                    acceptsDrag = if (visibleProgress > 0.001f) withinVisibleSheet else withinOpeningEdge
                    tracker.resetTracking()
                    tracker.addPosition(android.os.SystemClock.uptimeMillis(), position)
                },
                onHorizontalDrag = { change, amount ->
                    if (!acceptsDrag) return@detectHorizontalDragGestures
                    trackingDrag = true
                    change.consume()
                    tracker.addPosition(android.os.SystemClock.uptimeMillis(), change.position)
                    val fractionDelta = if (isRtl) -amount / drawerWidth else amount / drawerWidth
                    dragCommands.trySend(DrawerDragCommand.Delta(fractionDelta))
                },
                onDragEnd = {
                    if (trackingDrag) {
                        val velocity = tracker.calculateVelocity().x / drawerWidth * if (isRtl) -1f else 1f
                        dragCommands.trySend(DrawerDragCommand.Settle(velocity))
                    }
                },
                onDragCancel = {
                    if (trackingDrag) dragCommands.trySend(DrawerDragCommand.Settle(0f))
                },
            )
        } finally {
            dragCommands.close()
            dragConsumer.join()
        }
    }
}

@Composable
fun rememberDrawerState(initialValue: DrawerValue): DrawerState = remember { DrawerState(initialValue) }

@Composable
fun KompiModalNavigationDrawer(
    drawerState: DrawerState,
    gesturesEnabled: Boolean = true,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val view = LocalView.current
    val edgeWidth = with(density) { 24.dp.toPx() }
    val edgeGestureLimit = with(density) { 24.dp.toPx() }
    var containerWidth by remember { mutableStateOf(0f) }
    val drawerWidth = with(density) { 320.dp.toPx() }.coerceAtMost(containerWidth)
    val openProgress = drawerState.progress.value.coerceIn(0f, 1f)
    val mounted = drawerState.isOpen || openProgress > 0f

    fun updateGestureExclusion(height: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val exclusionHeight = with(density) { 160.dp.roundToPx() }
            val top = ((height - exclusionHeight) / 2).coerceAtLeast(0)
            view.systemGestureExclusionRects = if (gesturesEnabled && height > 0) {
                val left = if (layoutDirection == LayoutDirection.Rtl) (view.width - edgeWidth.toInt()).coerceAtLeast(0) else 0
                listOf(Rect(left, top, left + edgeWidth.toInt(), (top + exclusionHeight).coerceAtMost(height)))
            } else emptyList()
        }
    }

    DisposableEffect(view) {
        onDispose { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) view.systemGestureExclusionRects = emptyList() }
    }
    SideEffect { updateGestureExclusion(view.height) }

    BackHandler(enabled = mounted) { scope.launch { drawerState.close() } }

    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { size ->
                containerWidth = size.width.toFloat()
                updateGestureExclusion(size.height)
            }
            .drawerDragGesture(
                drawerState = drawerState,
                gesturesEnabled = gesturesEnabled,
                containerWidth = containerWidth,
                drawerWidth = drawerWidth,
                edgeGestureLimit = edgeGestureLimit,
                layoutDirection = layoutDirection,
            ),
    ) {
        Box(
            Modifier.fillMaxSize().then(
                if (mounted) Modifier.semantics { hideFromAccessibility() } else Modifier,
            ),
        ) { content() }
        if (mounted) {
            Popup(
                alignment = Alignment.TopStart,
                onDismissRequest = { scope.launch { drawerState.close() } },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = false,
                ),
            ) {
                Box(
                    Modifier.fillMaxSize().drawerDragGesture(
                        drawerState = drawerState,
                        gesturesEnabled = gesturesEnabled,
                        containerWidth = containerWidth,
                        drawerWidth = drawerWidth,
                        edgeGestureLimit = edgeGestureLimit,
                        layoutDirection = layoutDirection,
                    ),
                ) {
                    Box(
                        Modifier.fillMaxSize()
                            .graphicsLayer { alpha = 0.40f * openProgress }
                            .background(Color.Black)
                            .clickable(role = Role.Button) { scope.launch { drawerState.close() } }
                            .semantics { contentDescription = "Dismiss navigation drawer" },
                    )
                    Box(
                        Modifier.fillMaxHeight()
                            .widthIn(max = 320.dp)
                            .fillMaxWidth()
                            .graphicsLayer {
                                val direction = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                                translationX = (openProgress - 1f) * drawerWidth * direction
                            }
                            .background(KompiUi.colors.surface)
                            .focusable()
                            .semantics {
                                paneTitle = "Navigation drawer"
                                stateDescription = if (drawerState.isOpen) "Open" else "Closing"
                            },
                    ) {
                        drawerContent()
                    }
                }
            }
        }
    }
}

@Composable
fun KompiModalDrawerSheet(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxHeight().widthIn(max = 320.dp).background(KompiUi.colors.surface).navigationBarsPadding(), content = content)
}

@Composable
fun KompiNavigationDrawerItem(
    label: @Composable () -> Unit,
    icon: @Composable () -> Unit,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    UnstyledButton(onClick = onClick, enabled = true, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp).clip(RoundedCornerShape(50)).background(if (selected) KompiUi.colors.primaryContainer else Color.Transparent), contentAlignment = Alignment.CenterStart, indication = LocalIndication.current) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { icon(); label() }
    }
}

@Composable
fun KompiListItemSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    KompiSurface(modifier, shape = KompiUi.shapes.medium, color = KompiUi.colors.surfaceContainerLow, content = content)
}

@Composable
fun KompiClickableItem(onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    UnstyledButton(onClick = onClick, enabled = enabled, modifier = modifier, contentAlignment = Alignment.Center, indication = LocalIndication.current, content = content)
}

@Composable
fun KompiProgressTrack(progress: Float, modifier: Modifier = Modifier) = KompiHorizontalProgress(progress, modifier)
