package com.kompi.app.chat

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import com.kompi.app.ui.KompiText
import com.kompi.app.ui.KompiUi
import org.commonmark.Extension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow

private object AssistantMarkdownParser {
    private val extensions: List<Extension> = listOf(
        org.commonmark.ext.gfm.strikethrough.StrikethroughExtension.create(),
        org.commonmark.ext.gfm.tables.TablesExtension.create(),
    )
    private val parser = Parser.builder().extensions(extensions).build()

    fun parse(source: String): Node = parser.parse(source)
}

private val LocalParagraphStyle = staticCompositionLocalOf<TextStyle?> { null }
private val LocalReadAlong = staticCompositionLocalOf<Map<Node, IntRange>> { emptyMap() }
private val LocalReadAlongBounds = staticCompositionLocalOf<((Node, Rect) -> Unit)?> { null }
private data class MarkdownDocument(val source: String, val root: Node)

/** A presentation-only CommonMark/GFM view. The source remains the canonical assistant text. */
@Composable
fun AssistantMarkdown(messageKey: String, source: String, animateArrivals: Boolean = false, modifier: Modifier = Modifier, paragraphStyle: TextStyle? = null, spokenPhrase: String? = null, spokenSequence: Long? = null, onSpokenBounds: ((Rect?) -> Unit)? = null) {
    var document by remember(messageKey) { mutableStateOf<MarkdownDocument?>(null) }
    val revealedSource = rememberAdaptiveTextReveal(messageKey, source, streaming = animateArrivals)
    val latestSource = rememberUpdatedState(revealedSource)
    LaunchedEffect(messageKey) {
        snapshotFlow { latestSource.value }
            .distinctUntilChanged()
            .collect {
                // Coalesce at the next frame, parse off-main, and let in-flight work finish.
                // A newer append can use this prefix; a canonical rewrite must discard it.
                if (!com.kompi.app.BuildConfig.PI_EAGER_FIRST_PARSE || document != null) withFrameNanos { }
                val content = latestSource.value
                if (document?.source == content) return@collect
                val parsed = withContext(Dispatchers.Default) { AssistantMarkdownParser.parse(content) }
                if (latestSource.value.startsWith(content)) {
                    document = MarkdownDocument(content, parsed)
                    com.kompi.app.diagnostics.ResponsePerformance.requestFor(messageKey)?.let {
                        com.kompi.app.diagnostics.LocalDiagnostics.recordResponseRenderUpdate(it)
                    }
                }
            }
    }
    val parsedDocument = document?.takeIf { revealedSource.startsWith(it.source) }?.root ?: return
    val readAlongDocument = remember(parsedDocument) { ReadAlongDocument(parsedDocument) }
    val readAlongTracker = remember(messageKey) { ReadAlongTracker() }
    val readAlongRanges = remember(readAlongDocument, spokenPhrase, spokenSequence) {
        readAlongTracker.ranges(readAlongDocument, spokenPhrase, spokenSequence)
    }
    val currentSpokenBounds = rememberUpdatedState(onSpokenBounds)
    val boundsByBlock = remember(readAlongRanges) { mutableMapOf<Node, Rect>() }
    val reportBounds: ((Node, Rect) -> Unit)? = if (onSpokenBounds == null) null else remember(boundsByBlock) {
        { node, bounds ->
            boundsByBlock[node] = bounds
            val union = boundsByBlock.values.reduce { left, right ->
                Rect(minOf(left.left, right.left), minOf(left.top, right.top), maxOf(left.right, right.right), maxOf(left.bottom, right.bottom))
            }
            currentSpokenBounds.value?.invoke(union)
        }
    }
    LaunchedEffect(readAlongRanges) {
        if (readAlongRanges.isEmpty()) currentSpokenBounds.value?.invoke(null)
    }
    CompositionLocalProvider(LocalParagraphStyle provides paragraphStyle, LocalReadAlong provides readAlongRanges,
        LocalReadAlongBounds provides reportBounds) {
    SelectionContainer {
        Column(modifier.fillMaxWidth().drawWithContent {
            drawContent()
            if (document?.source?.isNotBlank() == true) com.kompi.app.diagnostics.ResponsePerformance.firstDraw(messageKey)
        }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            var child = parsedDocument.firstChild
            var blockIndex = 0
            while (child != null) {
                MarkdownBlock(child, messageKey, blockIndex.toString())
                child = child.next
                blockIndex++
            }
        }
    }
    }
}

@Composable
private fun MarkdownBlock(node: Node, messageKey: String, blockPath: String) {
    val colors = KompiUi.colors
    key(messageKey, blockPath, node.javaClass) {
        when (node) {
            is Paragraph -> InlineContent(node, Modifier.fillMaxWidth())
            is Heading -> {
                val style = when (node.level) {
                    1 -> KompiUi.typography.headlineSmall
                    2 -> KompiUi.typography.titleLarge
                    3 -> KompiUi.typography.titleMedium
                    else -> KompiUi.typography.titleSmall
                }
                InlineContent(node, Modifier.fillMaxWidth(), style = style.copy(fontWeight = FontWeight.SemiBold))
            }
            is FencedCodeBlock -> CodeBlock(node.info.orEmpty(), node.literal)
            is IndentedCodeBlock -> CodeBlock("", node.literal)
            is BlockQuote -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.widthIn(min = 2.dp).heightIn(min = 20.dp).clip(RoundedCornerShape(2.dp)).background(colors.outlineVariant))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    var child = node.firstChild
                    var childIndex = 0
                    while (child != null) {
                        MarkdownBlock(child, messageKey, "$blockPath/q$childIndex")
                        child = child.next
                        childIndex++
                    }
                }
            }
            is BulletList, is OrderedList -> ListContent(node, messageKey, blockPath)
            is TableBlock -> MarkdownTable(node)
            is org.commonmark.node.ThematicBreak -> com.kompi.app.ui.KompiDivider(Modifier.fillMaxWidth())
            else -> {
                if (node.firstChild != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        var child = node.firstChild
                        var childIndex = 0
                        while (child != null) {
                            MarkdownBlock(child, messageKey, "$blockPath/b$childIndex")
                            child = child.next
                            childIndex++
                        }
                    }
                } else {
                    InlineContent(node, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun ListContent(list: ListBlock, messageKey: String, blockPath: String) {
    val ordered = list is OrderedList
    val start = (list as? OrderedList)?.markerStartNumber ?: 1
    Column(
        modifier = if (list.parent is ListItem) Modifier.padding(start = 16.dp) else Modifier,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        var item = list.firstChild
        var index = 0
        while (item != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KompiText(
                    if (ordered) "${start + index}." else "•",
                    modifier = Modifier.padding(start = 2.dp),
                    color = KompiUi.colors.onSurfaceVariant,
                    style = KompiUi.typography.bodyLarge,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (item is ListItem) {
                        var child = item.firstChild
                        var childIndex = 0
                        while (child != null) {
                            MarkdownBlock(child, messageKey, "$blockPath/l$index/b$childIndex")
                            child = child.next
                            childIndex++
                        }
                    }
                }
            }
            item = item.next
            index++
        }
    }
}

@Composable
private fun CodeBlock(language: String, code: String) {
    val colors = KompiUi.colors
    val scroll = rememberScrollState()
    Column(
        Modifier.fillMaxWidth()
            .clip(KompiUi.shapes.medium)
            .background(colors.surfaceContainerLow)
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (language.isNotBlank()) {
            KompiText(
                language,
                Modifier.padding(horizontal = 12.dp),
                color = colors.onSurfaceVariant,
                style = KompiUi.typography.labelSmall,
            )
        }
        BasicText(
            text = AnnotatedString(code),
            modifier = Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 12.dp),
            style = KompiUi.typography.bodyMedium.copy(
                color = colors.onSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            ),
            softWrap = false,
        )
    }
}

@Composable
private fun MarkdownTable(table: Node) {
    val rows = buildList {
        var section = table.firstChild
        while (section != null) {
            var row = section.firstChild
            while (row != null) {
                if (row is TableRow) add(row)
                row = row.next
            }
            section = section.next
        }
    }
    if (rows.isEmpty()) return
    val scroll = rememberScrollState()
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    var cell = row.firstChild
                    while (cell != null) {
                        if (cell is TableCell) {
                            val header = cell.isHeader
                            Box(
                                Modifier.widthIn(min = 112.dp)
                                    .background(if (header) KompiUi.colors.surfaceVariant else KompiUi.colors.surfaceContainerLow)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                InlineContent(
                                    cell,
                                    Modifier,
                                    style = (if (header) KompiUi.typography.labelLarge else KompiUi.typography.bodySmall),
                                )
                            }
                        }
                        cell = cell.next
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineContent(
    parent: Node,
    modifier: Modifier,
    style: androidx.compose.ui.text.TextStyle = LocalParagraphStyle.current ?: KompiUi.typography.bodyLarge,
) {
    val uriHandler = LocalUriHandler.current
    val colors = KompiUi.colors
    val linkListener = remember(uriHandler) {
        LinkInteractionListener { link ->
            val safeUrl = (link as? LinkAnnotation.Url)?.url?.let(::httpUrlOrNull)
            if (safeUrl != null) uriHandler.openUri(safeUrl)
        }
    }
    val annotated = remember(parent, colors, linkListener) {
        buildAnnotatedString {
            fun appendChildren(node: Node, inlineStyle: SpanStyle = SpanStyle()) {
                var child = node.firstChild
                while (child != null) {
                    val current = child
                    when (current) {
                        is Text -> withStyle(inlineStyle) { append(current.literal) }
                        is SoftLineBreak -> withStyle(inlineStyle) { append(' ') }
                        is HardLineBreak -> withStyle(inlineStyle) { append('\n') }
                        is Code -> withStyle(inlineStyle + SpanStyle(fontFamily = FontFamily.Monospace, background = colors.surfaceVariant, color = colors.onSurface)) { append(current.literal) }
                        is Emphasis -> appendStyledChildren(current, inlineStyle + SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), colors, linkListener)
                        is StrongEmphasis -> appendStyledChildren(current, inlineStyle + SpanStyle(fontWeight = FontWeight.Bold), colors, linkListener)
                        is Strikethrough -> appendStyledChildren(current, inlineStyle + SpanStyle(textDecoration = TextDecoration.LineThrough), colors, linkListener)
                        is Link -> {
                            val safeUrl = httpUrlOrNull(current.destination)
                            val linkStyle = inlineStyle + SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)
                            if (safeUrl != null) pushLink(LinkAnnotation.Url(safeUrl, TextLinkStyles(style = linkStyle), linkListener))
                            appendChildren(current, linkStyle)
                            if (safeUrl != null) pop()
                        }
                        is Image -> {
                            withStyle(inlineStyle.copy(color = colors.onSurfaceVariant)) {
                                append("[Image")
                                val alt = buildString { appendPlainText(current, this) }.trim()
                                if (alt.isNotEmpty()) append(": $alt")
                                append(" not loaded]")
                            }
                        }
                        else -> appendChildren(current, inlineStyle)
                    }
                    child = current.next
                }
            }
            appendChildren(parent)
        }
    }
    val spokenRange = LocalReadAlong.current[parent]
    val highlighted = remember(annotated, spokenRange, colors) {
        if (spokenRange == null) annotated else buildAnnotatedString {
            append(annotated)
            addStyle(SpanStyle(background = colors.primary.copy(alpha = 0.20f)), spokenRange.first, spokenRange.last + 1)
        }
    }
    val onBounds = LocalReadAlongBounds.current
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    fun reportSpokenBounds() {
        val range = spokenRange ?: return
        val layout = textLayout ?: return
        val position = coordinates?.takeIf { it.isAttached } ?: return
        if (range.first < 0 || range.last >= layout.layoutInput.text.length) return
        val local = layout.getPathForRange(range.first, range.last + 1).getBounds()
        onBounds?.invoke(parent, Rect(position.localToRoot(local.topLeft), position.localToRoot(local.bottomRight)))
    }
    LaunchedEffect(spokenRange, textLayout, onBounds) { reportSpokenBounds() }
    BasicText(
        highlighted,
        if (spokenRange != null && onBounds != null) modifier.onGloballyPositioned {
            coordinates = it
            reportSpokenBounds()
        } else modifier,
        style.copy(color = style.color.takeUnless { it == androidx.compose.ui.graphics.Color.Unspecified } ?: colors.onSurface),
        onTextLayout = { if (spokenRange != null && onBounds != null) textLayout = it },
    )
}

private fun AnnotatedString.Builder.appendStyledChildren(
    node: Node,
    style: SpanStyle,
    colors: com.kompi.app.ui.KompiColors,
    linkListener: LinkInteractionListener,
) {
    pushStyle(style)
    var child = node.firstChild
    while (child != null) {
        val current = child
        when (current) {
            is Text -> append(current.literal)
            is SoftLineBreak -> withStyle(style) { append(' ') }
            is HardLineBreak -> withStyle(style) { append('\n') }
            is Code -> withStyle(style + SpanStyle(fontFamily = FontFamily.Monospace)) { append(current.literal) }
            else -> appendNested(current, style, colors, linkListener)
        }
        child = current.next
    }
    pop()
}

private fun AnnotatedString.Builder.appendNested(
    node: Node,
    style: SpanStyle,
    colors: com.kompi.app.ui.KompiColors,
    linkListener: LinkInteractionListener,
) {
    var child = node.firstChild
    while (child != null) {
        val current = child
        when (current) {
            is Text -> withStyle(style) { append(current.literal) }
            is SoftLineBreak -> withStyle(style) { append(' ') }
            is HardLineBreak -> withStyle(style) { append('\n') }
            is Code -> withStyle(style + SpanStyle(fontFamily = FontFamily.Monospace)) { append(current.literal) }
            is Emphasis -> appendStyledChildren(current, style + SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), colors, linkListener)
            is StrongEmphasis -> appendStyledChildren(current, style + SpanStyle(fontWeight = FontWeight.Bold), colors, linkListener)
            is Strikethrough -> appendStyledChildren(current, style + SpanStyle(textDecoration = TextDecoration.LineThrough), colors, linkListener)
            is Link -> {
                val safeUrl = httpUrlOrNull(current.destination)
                val linkStyle = style + SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)
                if (safeUrl != null) pushLink(LinkAnnotation.Url(safeUrl, TextLinkStyles(style = linkStyle), linkListener))
                appendNested(current, linkStyle, colors, linkListener)
                if (safeUrl != null) pop()
            }
            else -> appendNested(current, style, colors, linkListener)
        }
        child = current.next
    }
}

private fun appendPlainText(node: Node, output: StringBuilder) {
    var child = node.firstChild
    while (child != null) {
        when (child) {
            is Text -> output.append(child.literal)
            is Code -> output.append(child.literal)
            else -> appendPlainText(child, output)
        }
        child = child.next
    }
}

/** Plain speech projection for explicit read-aloud only; this never changes stored/displayed text. */
fun assistantMarkdownSpeechText(source: String): String {
    val root = AssistantMarkdownParser.parse(source)
    val spoken = StringBuilder()
    fun appendSpeech(node: Node) {
        when (node) {
            is FencedCodeBlock -> return
            is Text -> spoken.append(node.literal)
            is SoftLineBreak, is HardLineBreak -> spoken.append(' ')
            is Code -> spoken.append(node.literal)
            is Image -> {
                val alt = StringBuilder()
                appendPlainText(node, alt)
                spoken.append(alt)
            }
            else -> {
                var child = node.firstChild
                while (child != null) {
                    appendSpeech(child)
                    child = child.next
                }
            }
        }
        if (node is Paragraph || node is Heading || node is ListItem || node is TableCell || node is BlockQuote) spoken.append(' ')
    }
    var child = root.firstChild
    while (child != null) {
        appendSpeech(child)
        child = child.next
    }
    return spoken.toString().replace(Regex("\\s+"), " ").trim()
}

/** Parse the external link boundary once; internal schemes and hostless URLs have no action. */
private fun httpUrlOrNull(destination: String): String? {
    val uri = Uri.parse(destination)
    val scheme = uri.scheme?.lowercase()
    return destination.takeIf { (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank() }
}
