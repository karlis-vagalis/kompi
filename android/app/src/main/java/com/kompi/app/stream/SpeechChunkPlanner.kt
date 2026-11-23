package com.kompi.app.stream

import android.icu.text.BreakIterator
import android.icu.util.ULocale
import com.kompi.app.audio.SynthesisText
import java.util.Locale

/** Queue pressure changes latency policy, never whether an unfinished token is safe to speak. */
internal enum class SpeechBufferPressure { NeedsAudio, HasAudio }

internal sealed interface SpeechChunkDecision {
    data class Emit(val end: Int) : SpeechChunkDecision
    data object AwaitText : SpeechChunkDecision
    data object OversizedToken : SpeechChunkDecision
}

/** ICU/CLDR supply language rules; transport-delta edges are never end-of-text boundaries. */
internal class SpeechChunkPlanner(locale: Locale) {
    private val sentences = BreakIterator.getSentenceInstance(ULocale.forLocale(locale).setKeywordValue("ss", "standard"))
    private val words = BreakIterator.getWordInstance(locale)

    fun next(text: String, complete: Boolean, first: Boolean, pressure: SpeechBufferPressure, ageMillis: Long): SpeechChunkDecision {
        if (text.isBlank()) return SpeechChunkDecision.AwaitText
        val spans = protectedSpans.findAll(text).map { it.range }.toList()
        fun outsideSpan(end: Int): Boolean = spans.none { end > it.first && end <= it.last }

        // Whitespace-delimited expressions stay atomic, including their punctuation. ICU additionally
        // supplies dictionary word boundaries for scripts which do not normally separate words.
        val lexicalEnds = sortedSetOf<Int>()
        for (index in 1 until text.length) {
            if (text[index].isWhitespace() && !text[index - 1].isWhitespace()) lexicalEnds += index
        }
        words.setText(text)
        var wordEnd = words.first()
        while (wordEnd != BreakIterator.DONE) {
            if (wordEnd in 1 until text.length && words.ruleStatus >= BreakIterator.WORD_NUMBER &&
                (dictionaryScript(text.codePointBefore(wordEnd)) || dictionaryScript(text.codePointAt(wordEnd)))) {
                lexicalEnds += wordEnd
            }
            wordEnd = words.next()
        }
        if (complete) lexicalEnds += text.trimEnd().length
        val confirmedEnds = lexicalEnds.filter(::outsideSpan)
        fun safe(end: Int): Boolean = end <= MAX_CHARACTERS && outsideSpan(end) &&
            SynthesisText.fits(text.substring(0, end), MAX_CHARACTERS) &&
            (complete || confirmedEnds.any { it > end })

        // ICU's final boundary is provisional until the producer explicitly completes the reply.
        // Locale abbreviation filters suppress false boundaries such as honorifics before names.
        sentences.setText(text)
        val sentenceEnds = mutableListOf<Int>()
        var end = sentences.first()
        while (end != BreakIterator.DONE) {
            if (end > 0 && (complete || end < text.length)) {
                val trimmed = text.substring(0, end).trimEnd().length
                if (trimmed in lexicalEnds && safe(trimmed)) sentenceEnds += trimmed
            }
            end = sentences.next()
        }
        if (sentenceEnds.isNotEmpty()) {
            return SpeechChunkDecision.Emit(if (first) sentenceEnds.first() else sentenceEnds.last())
        }
        if (complete && SynthesisText.fits(text, MAX_CHARACTERS)) return SpeechChunkDecision.Emit(text.length)

        val safeEnds = confirmedEnds.filter(::safe)
        val clauses = safeEnds.filter { boundary ->
            val last = text.substring(0, boundary).trimEnd('"', '\'', '”', '’', ')', ']', '*', '_').lastOrNull()
            last in clausePunctuation
        }
        // While queued audio covers latency, preserve longer sentences rather than every comma.
        val needsAudio = pressure == SpeechBufferPressure.NeedsAudio
        val minimum = if (complete) 1 else if (first) 12 else 40
        val target = if (needsAudio) 80 else 180
        val deadline = if (first) 250L else if (needsAudio) 450L else 1_500L
        val eligibleClauses = clauses.filter { it >= minimum }
        // An idle first reply does not need 80 characters or a timer after a real, confirmed
        // clause already exists. Start that short phrase immediately; preserve longer prosody
        // when earlier audio covers the wait. All lexical/notation/lookahead guards still apply.
        if (eligibleClauses.isNotEmpty() && (complete || (first && needsAudio) || text.length >= target || ageMillis >= deadline)) {
            return SpeechChunkDecision.Emit(if (first && needsAudio) eligibleClauses.first() else eligibleClauses.last())
        }
        // A soft startup target permits a natural clause, not an arbitrary word cut. Short
        // replies often finish a few deltas later; fragmenting them can strand a numeric tail.
        if (safeEnds.isNotEmpty() && (complete || text.length >= MAX_CHARACTERS || ageMillis >= if (needsAudio) 900L else 2_000L)) {
            val candidate = safeEnds.lastOrNull { boundary ->
                val last = text[boundary - 1]
                boundary >= minimum && last !in ".:：/" && Character.getType(last) != Character.CURRENCY_SYMBOL.toInt()
            }
            if (candidate != null) return SpeechChunkDecision.Emit(candidate)
        }

        // Do not force an overlong URL/identifier into arbitrary sub-word audio. Speech fails
        // explicitly; its canonical text remains on screen. The stream buffer is bounded too.
        val firstEnd = confirmedEnds.firstOrNull()
        if (firstEnd != null && !SynthesisText.fits(text.substring(0, firstEnd), MAX_CHARACTERS)) return SpeechChunkDecision.OversizedToken
        if (text.length > MAX_CHARACTERS && lexicalEnds.isEmpty()) return SpeechChunkDecision.OversizedToken
        return SpeechChunkDecision.AwaitText
    }

    companion object {
        const val MAX_CHARACTERS = 200
        private val clausePunctuation = setOf(',', ';', ':', '—', '–', '，', '；', '：', '、', '،', '؛')
        // These are notation rules, not English pronunciation dictionaries. They protect structured
        // spans across spaces; ICU owns natural-language sentence/word/abbreviation segmentation.
        private val clock = "\\p{Nd}{1,2}[:：]\\p{Nd}{2}"
        private val protectedSpans = Regex(
            "(?i)(?:[\\p{L}][\\p{L}\\p{N}+.-]*://|www\\.)\\S+" +
                "|[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+" +
                "|(?:(?:[\\p{L}]+[ \\t]+)?[`*_]*$clock(?:[ \\t]*[–—-][ \\t]*$clock)?[`*_]*(?:[ \\t]+[\\p{L}.]{1,8})?)" +
                "|(?:\\p{Sc}[ \\t]+[+-]?\\p{Nd}[\\p{Nd}.,]*)" +
                "|(?:\\p{Nd}[\\p{Nd}.,]*[ \\t]+[\\p{L}°%µ]+)" +
                "|(?:\\p{Nd}{1,3}(?:[ \\u00a0\\u202f]\\p{Nd}{3})+(?:[.,]\\p{Nd}+)?)" +
                "|(?:\\p{Nd}+(?:[.,:：/-]\\p{Nd}+)+)"
        )

        private fun dictionaryScript(codePoint: Int): Boolean = when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL,
            Character.UnicodeScript.THAI, Character.UnicodeScript.LAO,
            Character.UnicodeScript.KHMER, Character.UnicodeScript.MYANMAR -> true
            else -> false
        }

        /** Complete read-aloud shares the same rules, without a streaming deadline or rewriting text. */
        fun splitComplete(text: String, locale: Locale): List<String>? {
            val planner = SpeechChunkPlanner(locale)
            val phrases = mutableListOf<String>()
            var remaining = text.trim()
            while (remaining.isNotEmpty()) {
                when (val next = planner.next(remaining, true, false, SpeechBufferPressure.HasAudio, 0)) {
                    is SpeechChunkDecision.Emit -> {
                        val phrase = remaining.substring(0, next.end).trim()
                        if (phrase.any { it.isLetterOrDigit() }) phrases += phrase
                        remaining = remaining.substring(next.end).trimStart()
                    }
                    SpeechChunkDecision.AwaitText, SpeechChunkDecision.OversizedToken -> return null
                }
            }
            return phrases
        }
    }
}
