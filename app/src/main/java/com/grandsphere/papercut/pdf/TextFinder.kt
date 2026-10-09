package com.grandsphere.papercut.pdf

data class SearchHit(
    val startPage: Int,
    val startIndex: Int,
    val endPage: Int,
    val endIndex: Int,
    val snippet: String
)

object TextFinder {
    fun find(pages: List<List<CharBox>>, query: String): List<SearchHit> {
        val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || pages.isEmpty()) return emptyList()
        val pattern = tokens.joinToString("[^\\s]*\\s?[^\\s]*") { Regex.escape(it) }
            .toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

        val map = ArrayList<Pair<Int, Int>>()
        val sb = StringBuilder()
        pages.forEachIndexed { page, chars ->
            if (page > 0) {
                sb.append('\n')
                map.add(-1 to -1)
            }
            chars.forEachIndexed { i, box ->
                sb.append(box.c)
                map.add(page to i)
            }
        }
        val text = sb.toString()
        if (text.isEmpty()) return emptyList()
        return pattern.findAll(text).mapNotNull { match ->
            val range = match.range
            val start = range.firstOrNull { it in map.indices && map[it].first >= 0 } ?: return@mapNotNull null
            val end = range.lastOrNull { it in map.indices && map[it].first >= 0 } ?: return@mapNotNull null
            val (sp, si) = map[start]
            val (ep, ei) = map[end]
            SearchHit(sp, si, ep, ei, wordSnippet(text, start, end))
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
