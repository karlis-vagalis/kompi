package com.kompi.app.audio.whistle

/** A bounded, normalized list of local decoder hints, not transcript replacement rules. */
class WhistleVocabulary private constructor(val text: String) {
    val nativeKeywords: String? get() = text.takeIf(String::isNotEmpty)
    val size: Int get() = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1

    companion object {
        const val MAX_CHARACTERS = 4_096
        const val MAX_HINTS = 64
        const val MAX_HINT_CHARACTERS = 100
        val Empty = WhistleVocabulary("")

        fun parse(input: String): Result<WhistleVocabulary> = runCatching {
            require(input.length <= MAX_CHARACTERS) { "Use at most $MAX_CHARACTERS characters in total." }
            val hints = input.lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList()
            require(hints.size <= MAX_HINTS) { "Use at most $MAX_HINTS words or phrases." }
            require(hints.all { it.length <= MAX_HINT_CHARACTERS }) { "Keep each word or phrase within $MAX_HINT_CHARACTERS characters." }
            require(hints.none { hint -> hint.any(Char::isISOControl) }) { "Remove control characters from vocabulary hints." }
            if (hints.isEmpty()) Empty else WhistleVocabulary(hints.joinToString("\n"))
        }
    }
}
