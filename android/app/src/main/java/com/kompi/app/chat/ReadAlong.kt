package com.kompi.app.chat

import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.ext.gfm.tables.TableCell

/** Ranges refer to rendered inline text, not Markdown delimiters or guessed word timings. */
internal class ReadAlongDocument(root: Node) {
    data class Block(val node: Node, val start: Int, val end: Int)
    val text: String
    val blocks: List<Block>
    init {
        val output = StringBuilder()
        val positions = mutableListOf<Block>()
        fun inline(node: Node) {
            when (node) {
                is Text -> output.append(node.literal)
                is Code -> output.append(node.literal)
                is SoftLineBreak -> output.append(' ')
                is HardLineBreak -> output.append('\n')
                is Image -> {
                    output.append("[Image")
                    val before = output.length
                    var child = node.firstChild
                    while (child != null) { inline(child); child = child.next }
                    val alt = output.substring(before).trim()
                    output.setLength(before)
                    if (alt.isNotEmpty()) output.append(": ").append(alt)
                    output.append(" not loaded]")
                }
                else -> {
                    var child = node.firstChild
                    while (child != null) { inline(child); child = child.next }
                }
            }
        }
        fun walk(node: Node) {
            when (node) {
                is FencedCodeBlock, is IndentedCodeBlock -> Unit
                is Paragraph, is Heading, is TableCell -> {
                    if (output.isNotEmpty()) output.append('\n')
                    val start = output.length
                    inline(node)
                    positions.add(Block(node, start, output.length))
                }
                is OrderedList -> {
                    var item = node.firstChild
                    var index = node.markerStartNumber ?: 1
                    while (item != null) {
                        output.append('\n').append(index++).append(". ")
                        walk(item)
                        item = item.next
                    }
                }
                else -> {
                    var child = node.firstChild
                    while (child != null) { walk(child); child = child.next }
                }
            }
        }
        walk(root)
        text = output.toString()
        blocks = positions
    }
}

/** Matches each actual playback sequence once, moving forward through repeated phrases. */
internal class ReadAlongTracker {
    private data class Normalized(val text: String, val offsets: List<Int>)
    private var previousText = ""
    private var sequence: Long? = null
    private var currentStart = 0
    private var nextStart = 0

    fun ranges(document: ReadAlongDocument, phrase: String?, playbackSequence: Long?): Map<Node, IntRange> {
        if (phrase.isNullOrBlank() || playbackSequence == null) return emptyMap()
        val source = normalize(document.text)
        val spoken = normalize(phrase).text
        if (spoken.isEmpty()) return emptyMap()
        val rewritten = !source.text.startsWith(previousText)
        val start = if (rewritten) 0 else if (sequence == playbackSequence) currentStart else nextStart
        val found = source.text.indexOf(spoken, start)
        previousText = source.text
        if (found < 0) return emptyMap() // Never highlight an invented or unrelated span.
        sequence = playbackSequence
        currentStart = found
        nextStart = found + spoken.length
        val from = source.offsets[found]
        val until = source.offsets[nextStart - 1] + 1
        return buildMap {
            for (block in document.blocks) {
                val left = maxOf(from, block.start)
                val right = minOf(until, block.end)
                if (left < right) put(block.node, (left - block.start) until (right - block.start))
            }
        }
    }

    private fun normalize(value: String): Normalized {
        val text = StringBuilder()
        val offsets = mutableListOf<Int>()
        var separator = -1
        value.forEachIndexed { index, character ->
            if (character.isLetterOrDigit()) {
                if (separator >= 0 && text.isNotEmpty()) { text.append(' '); offsets.add(separator) }
                separator = -1
                text.append(character.lowercaseChar()); offsets.add(index)
            } else if (separator < 0) separator = index
        }
        return Normalized(text.toString(), offsets)
    }
}
