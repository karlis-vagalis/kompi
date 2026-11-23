package com.kompi.app.audio

/** The current editable draft and selection are merged only when the decoder emits a final. */
internal sealed interface DictationDraftMerge {
    data class Applied(val text: String, val cursor: Int) : DictationDraftMerge
    data object DraftLimitExceeded : DictationDraftMerge
}

internal object DictationDraftMerger {
    fun merge(current: String, selectionStart: Int, selectionEnd: Int, recognized: String, maxChars: Int = 16_000): DictationDraftMerge {
        require(selectionStart in 0..current.length && selectionEnd in selectionStart..current.length)
        require(maxChars >= 0)
        val before = current.substring(0, selectionStart)
        val after = current.substring(selectionEnd)
        val insertion = buildString {
            if (before.isNotEmpty() && !before.last().isWhitespace()) append(' ')
            append(recognized)
            if (after.isNotEmpty() && !after.first().isWhitespace()) append(' ')
        }
        val resultingLength = current.length - (selectionEnd - selectionStart) + insertion.length
        if (resultingLength > maxChars) return DictationDraftMerge.DraftLimitExceeded
        return DictationDraftMerge.Applied(
            text = before + insertion + after,
            cursor = selectionStart + insertion.length,
        )
    }
}
