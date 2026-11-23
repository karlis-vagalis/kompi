package com.kompi.app.transport

/** Presentation-only standalone bold headings; never reasoning paragraphs or signatures. */
internal class ThinkingTitles {
    private val line = StringBuilder()
    private var discarded = false
    private val emitted = linkedSetOf<String>()
    val titles: List<String> get() = emitted.toList()

    fun append(delta: String): List<String> = buildList {
        for (character in delta) {
            if (character == '\n') finishLine()?.let(::add)
            else if (!discarded) {
                line.append(character)
                if (line.length > 200) { line.clear(); discarded = true }
            }
        }
    }

    fun end(finalContent: String? = null): List<String> = buildList {
        finishLine()?.let(::add)
        if (finalContent != null) {
            val final = ThinkingTitles()
            final.append(finalContent)
            final.end()
            for (title in final.titles) if (emitted.size < 32 && emitted.add(title)) add(title)
        }
    }

    private fun finishLine(): String? {
        val match = if (discarded) null else heading.matchEntire(line)
        val title = match?.groupValues?.get(1)?.replace(controls, " ")?.trim()
        line.clear(); discarded = false
        if (title.isNullOrEmpty() || emitted.size >= 32 || !emitted.add(title)) return null
        return title
    }

    companion object {
        private val heading = Regex("""\s*\*\*([^*\r\n]{1,160})\*\*\s*""")
        private val controls = Regex("""[\u0000-\u001f\u007f]""")
        fun from(content: String): List<String> = ThinkingTitles().apply { append(content); end() }.titles
    }
}
