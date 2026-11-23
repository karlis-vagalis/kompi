package com.kompi.app.audio

/** Original read-along phrase plus bounded, language-neutral input for the native phonemizer. */
internal data class SynthesisText private constructor(val source: String, val input: String) {
    companion object {
        private const val CLOCK = "(?:[01]?\\d|2[0-3]):[0-5]\\d"
        // Leave the second clock in lookahead, so chained ranges can normalize each separator.
        // Exclude identifiers, URLs, seconds and other colon expressions at either edge.
        private val clockRange = Regex("(?<![\\p{L}\\p{N}_/:])($CLOCK)[ \\t]*([–—-])[ \\t]*(?=$CLOCK(?![\\p{L}\\p{N}_:]))")

        private fun format(source: String): String {
            // Kokoro's English PreprocessMarkdownLinks advances its cursor past an unmatched
            // '[' and drops the preceding words. Send plain bracket contents, never its markup
            // syntax; retain the exact source separately for display and read-along.
            val plain = source.replace('[', ' ').replace(']', ' ')
            return clockRange.replace(plain) { match ->
                "${match.groupValues[1]} ${match.groupValues[2]} "
            }.trim()
        }

        fun parse(text: String, maxCharacters: Int): SynthesisText? {
            val source = text.trim()
            if (source.isEmpty() || source.length > maxCharacters) return null
            val input = format(source)
            if (input.length > maxCharacters) return null
            return SynthesisText(source, input)
        }

        fun fits(text: String, maxCharacters: Int): Boolean = parse(text, maxCharacters) != null
    }
}
