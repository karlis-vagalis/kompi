package com.kompi.app.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.graphics.ColorFilter
import com.kompi.app.ui.KompiDivider as HorizontalDivider
import com.kompi.app.ui.KompiSurface as Surface
import com.kompi.app.ui.KompiText as Text
import com.kompi.app.ui.KompiUi
import com.kompi.app.ui.currentKompiContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composeunstyled.TextInput
import com.composeunstyled.UnstyledButton
import com.composeunstyled.UnstyledTextField
import com.composables.icons.lucide.R as LucideR
import com.kompi.app.attachments.MessageAttachment
import com.kompi.app.attachments.MessageAttachmentPreviews

/** Renderless accessible action; all visual decisions belong to the Kompi chat surface. */
@Composable
fun ChatLucideIcon(
    resourceId: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    val painter = painterResource(resourceId)
    val iconTint = tint ?: currentKompiContentColor()
    Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = modifier,
        colorFilter = ColorFilter.tint(iconTint),
    )
}

/** Decorative circular badge; center the icon against the full circle, not an icon-sized child. */
@Composable
fun KompiIconBadge(
    resourceId: Int,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 24.dp,
) {
    val colors = KompiUi.colors
    Surface(modifier = modifier.size(size), shape = CircleShape, color = colors.primaryContainer) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ChatLucideIcon(resourceId, contentDescription = null, modifier = Modifier.size(iconSize), tint = colors.primary)
        }
    }
}

@Composable
fun KompiAssistantMark(modifier: Modifier = Modifier, size: Dp = 30.dp) {
    KompiIconBadge(LucideR.drawable.lucide_ic_messages_square, modifier, size, size * 0.52f)
}

@Composable
fun KompiSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = KompiUi.typography.titleSmall, color = KompiUi.colors.onSurfaceVariant)
        Surface(color = KompiUi.colors.surfaceContainerLow, shape = KompiUi.shapes.medium) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
fun KompiSectionDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier.padding(horizontal = 16.dp), color = KompiUi.colors.outlineVariant)
}

enum class ChatActionTone { Quiet, Soft, Primary }

@Composable
fun ChatActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    tone: ChatActionTone = ChatActionTone.Quiet,
    visualSize: Dp = 48.dp,
    visualWidth: Dp = visualSize,
    content: @Composable () -> Unit,
) {
    val colors = KompiUi.colors
    val backgroundColor = when {
        selected -> colors.primaryContainer
        tone == ChatActionTone.Quiet -> Color.Transparent
        tone == ChatActionTone.Soft -> colors.secondaryContainer
        else -> colors.primary
    }
    UnstyledButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .alpha(if (enabled) 1f else 0.42f)
            .semantics {
                contentDescription = label
                if (selected) stateDescription = "Current destination"
            },
        contentAlignment = Alignment.Center,
        indication = LocalIndication.current,
    ) {
        Box(
            Modifier.width(visualWidth)
                .heightIn(min = visualSize)
                .clip(RoundedCornerShape(50))
                .background(backgroundColor)
                .padding(horizontal = 8.dp, vertical = if (visualSize < 48.dp) 6.dp else 8.dp),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/** State-based editable field built on Compose Unstyled's renderless text-field behavior. */
@Composable
fun ChatPromptField(
    state: TextFieldState,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = KompiUi.colors
    UnstyledTextField(
        state = state,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        accessibilityLabel = "Message input",
        cursorBrush = SolidColor(colors.primary),
        textStyle = KompiUi.typography.bodyLarge.copy(color = colors.onComposer),
        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
        inputTransformation = InputTransformation {
            if (length > 16_000) revertAllChanges()
        },
    ) {
        TextInput(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
            placeholder = {
                Text(
                    placeholder,
                    style = KompiUi.typography.bodyLarge,
                    color = colors.composerMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
    }
}

enum class OutgoingMessageStatus {
    Sending, Queued, Sent, Running, Failed, Cancelled, Uncertain;

    fun label(connected: Boolean): String = when (this) {
        Sending -> if (connected) "Sending…" else "Waiting for connection"
        Queued -> "Queued on computer"
        Sent -> "Received by computer"
        Running -> "Running"
        Failed -> "Not completed"
        Cancelled -> "Cancelled"
        Uncertain -> "Delivery unconfirmed"
    }
}

/** Quiet reading rhythm: an unboxed assistant answer and a concise user prompt bubble. */
@Composable
fun ChatMessage(
    text: String,
    fromUser: Boolean,
    activity: Boolean = false,
    modifier: Modifier = Modifier,
    messageKey: String = "assistant-message",
    animateArrivals: Boolean = false,
    readAloudReady: Boolean = false,
    readingMessageKey: String? = null,
    onReadAloud: ((messageKey: String, spokenText: String) -> Unit)? = null,
    onStopReadAloud: ((messageKey: String) -> Unit)? = null,
    onOpenVoiceSettings: () -> Unit = {},
    piDetails: com.kompi.app.transport.PiSessionStore.Details? = null,
    displayText: String = text,
    attachments: List<MessageAttachment> = emptyList(),
    deliveryStatus: OutgoingMessageStatus? = null,
    connected: Boolean = true,
) {
    val colors = KompiUi.colors
    var actionsVisible by remember(messageKey) { mutableStateOf(false) }
    if (activity) {
        val rowModifier = modifier.fillMaxWidth().padding(start = 34.dp)
        if (piDetails != null) {
            PiEntryInfo(piDetails, anchor = { showDetails ->
                Box(rowModifier.clickable(onClickLabel = "View Pi entry", onClick = showDetails)
                    .heightIn(min = 32.dp).padding(vertical = 2.dp), contentAlignment = Alignment.CenterStart) {
                    Text(text, color = colors.onSurfaceVariant, style = KompiUi.typography.bodySmall)
                }
            })
        } else {
            Box(rowModifier.heightIn(min = 32.dp).padding(vertical = 2.dp), contentAlignment = Alignment.CenterStart) {
                Text(text, color = colors.onSurfaceVariant, style = KompiUi.typography.bodySmall)
            }
        }
    } else if (fromUser) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.88f)
                    .then(if (deliveryStatus == OutgoingMessageStatus.Sending) Modifier.border(
                        1.dp, colors.outline, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 6.dp, bottomStart = 22.dp),
                    ) else Modifier)
                    .semantics { if (deliveryStatus != null) stateDescription = deliveryStatus.label(connected) }
                    .clickable(
                    interactionSource = remember(messageKey) { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClickLabel = if (actionsVisible) "Hide message actions" else "Show message actions",
                    onClick = { actionsVisible = !actionsVisible },
                ),
                color = colors.surfaceVariant,
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 6.dp, bottomStart = 22.dp),
            ) {
                Column(
                    Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (attachments.isNotEmpty()) MessageAttachmentPreviews(attachments)
                    if (displayText.isNotBlank()) SelectionContainer {
                        Text(displayText, color = colors.onSurface, style = KompiUi.typography.bodyLarge)
                    }
                }
            }
            if (deliveryStatus != null) Text(
                deliveryStatus.label(connected),
                modifier = Modifier.padding(horizontal = 8.dp),
                style = KompiUi.typography.labelSmall,
                color = if (deliveryStatus == OutgoingMessageStatus.Failed || deliveryStatus == OutgoingMessageStatus.Uncertain) colors.error else colors.onSurfaceVariant,
            )
            if (actionsVisible) MessageActions(
                messageKey = messageKey,
                source = text,
                assistantMessage = false,
                piDetails = piDetails,
                readAloudReady = readAloudReady,
                isReading = readingMessageKey == messageKey,
                onReadAloud = onReadAloud,
                onStopReadAloud = onStopReadAloud,
                onOpenVoiceSettings = onOpenVoiceSettings,
            )
            }
        }
    } else {
        Column(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistantMarkdown(
                messageKey = messageKey,
                source = text,
                animateArrivals = animateArrivals,
                modifier = Modifier.fillMaxWidth().clickable(
                    interactionSource = remember(messageKey) { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClickLabel = if (actionsVisible) "Hide message actions" else "Show message actions",
                    onClick = { actionsVisible = !actionsVisible },
                ),
            )
            if (actionsVisible) MessageActions(
                messageKey = messageKey,
                source = text,
                assistantMessage = true,
                piDetails = piDetails,
                modifier = Modifier,
                readAloudReady = readAloudReady,
                isReading = readingMessageKey == messageKey,
                onReadAloud = onReadAloud,
                onStopReadAloud = onStopReadAloud,
                onOpenVoiceSettings = onOpenVoiceSettings,
            )
        }
    }
}
