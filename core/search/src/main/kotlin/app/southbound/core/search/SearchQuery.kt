package app.southbound.core.search

/**
 * A parsed search query.
 *
 * The search pack's FTS5 index stores trigrams without positions
 * (detail=none), so it can only answer "which rows contain all of these
 * trigrams". [ftsMatch] asks exactly that for every word of 3+ letters;
 * each candidate is then confirmed by checking that every word, short ones
 * included, starts a word in the row's text ([matchesText]).
 *
 * A query with no word of 3+ letters ("st", "e 4") has no trigrams; it falls
 * back to a name-prefix lookup on [prefix].
 *
 * Trailing state words and a ZIP ("..., Brooklyn NY 11201") are [soft]:
 * they raise the rows that have them but never drop a row that lacks them,
 * since most rows carry neither.
 */
data class SearchQuery(val tokens: List<String>, val soft: List<String> = emptyList()) {

    val normalized: String = tokens.joinToString(" ")

    val ftsMatch: String? = trigramMatch(tokens)

    val prefix: String get() = normalized

    val isEmpty: Boolean get() = tokens.isEmpty()

    /** True if every token starts a word in [searchText] (" a b c " form). */
    fun matchesText(searchText: String): Boolean = tokens.all { searchText.contains(" $it") }

    /** How many [soft] words start a word in [searchText]. */
    fun softMatches(searchText: String): Int = soft.count { searchText.contains(" $it") }

    companion object {
        private val SOFT_WORDS = setOf("ny", "nj", "usa", "us")
        private val ZIP = Regex("\\d{5}")
        private val ZIP_PLUS_FOUR = Regex("\\d{4}")

        fun parse(raw: String): SearchQuery {
            val tokens = SearchText.normalize(raw).split(' ').filter { it.isNotEmpty() }.toMutableList()
            val soft = ArrayList<String>()
            // Keep at least one required word: "NY" alone is still a search for "ny".
            while (tokens.size > 1) {
                val last = tokens[tokens.lastIndex]
                when {
                    last in SOFT_WORDS || ZIP.matches(last) -> soft.add(0, tokens.removeAt(tokens.lastIndex))
                    ZIP_PLUS_FOUR.matches(last) && tokens.size > 2 && ZIP.matches(tokens[tokens.lastIndex - 1]) -> {
                        tokens.removeAt(tokens.lastIndex)
                        soft.add(0, tokens.removeAt(tokens.lastIndex))
                    }
                    else -> break
                }
            }
            return SearchQuery(tokens.toList(), soft)
        }

        /** "\"mai\" AND \"ain\"": every trigram of the words of 3+ letters, or null if none. */
        fun trigramMatch(words: List<String>): String? = words
            .flatMap { token -> (0..token.length - 3).map { token.substring(it, it + 3) } }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" AND ") { "\"$it\"" }
    }
}
