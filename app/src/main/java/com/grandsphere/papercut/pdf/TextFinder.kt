package com.grandsphere.papercut.pdf

data class SearchHit(
    val startPage: Int,
    val startIndex: Int,
    val endPage: Int,
    val endIndex: Int,
    val snippet: String
)

object TextFinder {
    private val WHITESPACE = Regex("\\s+")

    fun find(pages: List<List<CharBox>>, query: String): List<SearchHit> {
        val tokens = query.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || pages.isEmpty()) return emptyList()
        val pattern = tokens.joinToString("[^\\s]*\\s?[^\\s]*") { Regex.escape(it) }
            .toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

        var total = pages.size - 1
        for (chars in pages) total += chars.size
        if (total <= 0) return emptyList()

        val pageOf = IntArray(total)
        val indexOf = IntArray(total)
        val sb = StringBuilder(total)
        var pos = 0
        pages.forEachIndexed { page, chars ->
            if (page > 0) {
                sb.append('\n')
                pageOf[pos] = -1
                pos++
            }
            for (i in chars.indices) {
                sb.append(chars[i].c)
                pageOf[pos] = page
                indexOf[pos] = i
                pos++
            }
        }
        val text = sb.toString()
        return pattern.findAll(text).mapNotNull { match ->
            val range = match.range
            var start = range.first
            while (start <= range.last && pageOf[start] < 0) start++
            if (start > range.last) return@mapNotNull null
            var end = range.last
            while (end > start && pageOf[end] < 0) end--
            SearchHit(
                pageOf[start], indexOf[start],
                pageOf[end], indexOf[end],
                wordSnippet(text, start, end),
            )
        }.toList()
    }

    private fun wordSnippet(text: String, matchStart: Int, matchEnd: Int): String {
        var start = matchStart.coerceIn(0, text.lastIndex)
        var end = matchEnd.coerceIn(0, text.lastIndex)
        while (start > 0 && !text[start - 1].isWhitespace()) start--
        while (end < text.lastIndex && !text[end + 1].isWhitespace()) end++
        return text.substring(start, end + 1).replace('\n', ' ').trim()
    }
}
