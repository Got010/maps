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
 */
data class SearchQuery(val tokens: List<String>) {

    val normalized: String = tokens.joinToString(" ")

    val ftsMatch: String? = tokens
        .flatMap { token -> (0..token.length - 3).map { token.substring(it, it + 3) } }
        .distinct()
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" AND ") { "\"$it\"" }

    val prefix: String get() = normalized

    val isEmpty: Boolean get() = tokens.isEmpty()

    /** True if every token starts a word in [searchText] (" a b c " form). */
    fun matchesText(searchText: String): Boolean = tokens.all { searchText.contains(" $it") }

    companion object {
        fun parse(raw: String): SearchQuery =
            SearchQuery(SearchText.normalize(raw).split(' ').filter { it.isNotEmpty() })
    }
}
