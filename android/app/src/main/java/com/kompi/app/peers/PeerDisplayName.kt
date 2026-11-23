package com.kompi.app.peers

/** A phone-local host label; never an endpoint identity or a pairing credential. */
class PeerDisplayName private constructor(val value: String) {
    companion object {
        const val MAX_CHARACTERS = 128

        fun parse(input: String): Result<PeerDisplayName> = runCatching {
            require(input.none(Char::isISOControl)) { "Use a single line without control characters." }
            val value = input.trim()
            require(value.isNotEmpty()) { "Enter a computer name." }
            require(value.length <= MAX_CHARACTERS) { "Use at most $MAX_CHARACTERS characters." }
            PeerDisplayName(value)
        }
    }
}
