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
 * since most rows carry neither. A state is any the packs cover, by its
 * two-letter code ("Hartford CT", "Scranton PA") or, after a comma as an
 * address writes it, by its name ("Hartford, Connecticut"); without the
 * comma a name may be part of the place's own ("Lake Michigan").
 *
 * "weather albany", "albany weather", "weather in Nyack" search for the place and set [asksWeather], so the
 * app can show that place's current weather (worked out on the phone; the query never leaves it).
 */
data class SearchQuery(val tokens: List<String>, val soft: List<String> = emptyList(), val asksWeather: Boolean = false) {

    val normalized: String = tokens.joinToString(" ")

    val ftsMatch: String? = trigramMatch(tokens)

    val prefix: String get() = normalized

    val isEmpty: Boolean get() = tokens.isEmpty()

    /** True if every token starts a word in [searchText] (" a b c " form). */
    fun matchesText(searchText: String): Boolean = tokens.all { searchText.contains(" $it") }

    /** How many [soft] words start a word in [searchText]. */
    fun softMatches(searchText: String): Int = soft.count { searchText.contains(" $it") }

    companion object {
        /** The states the packs cover (core:packs Regions), and DC. */
        private val STATE_CODES = setOf(
            "ny", "nj", "ct", "pa", "md", "de", "dc", "oh", "in", "il", "mi", "wi", "mn", "ia", "mo", "nd", "sd", "ne", "ks",
        )
        private val STATE_NAMES = listOf(
            "new york", "new jersey", "connecticut", "pennsylvania", "maryland", "delaware", "ohio", "indiana", "illinois",
            "michigan", "wisconsin", "minnesota", "iowa", "missouri", "north dakota", "south dakota", "nebraska", "kansas",
        ).map { it.split(' ') }
        private val SOFT_WORDS = STATE_CODES + setOf("usa", "us")
        private val ZIP = Regex("\\d{5}")
        private val ZIP_PLUS_FOUR = Regex("\\d{4}")

        private val WEATHER_WORDS = setOf("weather", "forecast")
        private val WEATHER_LINKS = setOf("in", "for", "at")

        fun parse(raw: String): SearchQuery {
            val tokens = SearchText.normalize(raw).split(' ').filter { it.isNotEmpty() }.toMutableList()
            // "weather (in) X" or "X weather": a search for X that asks for its weather. "Weather" alone stays a word.
            var asksWeather = false
            if (tokens.size > 1 && tokens.first() in WEATHER_WORDS) {
                tokens.removeAt(0)
                if (tokens.size > 1 && tokens.first() in WEATHER_LINKS) tokens.removeAt(0)
                asksWeather = true
            } else if (tokens.size > 1 && tokens.last() in WEATHER_WORDS) {
                tokens.removeAt(tokens.lastIndex)
                asksWeather = true
            }
            val soft = ArrayList<String>()
            // A state's name right after the last comma ("..., New Jersey 07102").
            val afterComma = if (',' in raw) SearchText.normalize(raw.substringAfterLast(',')).split(' ').filter { it.isNotEmpty() } else emptyList()
            val stateName = STATE_NAMES.firstOrNull { afterComma.take(it.size) == it }
            var nameLeft = stateName?.size ?: 0
            // Keep at least one required word: "NY" alone is still a search for "ny".
            while (tokens.size > 1) {
                val last = tokens[tokens.lastIndex]
                when {
                    last in SOFT_WORDS || ZIP.matches(last) -> soft.add(0, tokens.removeAt(tokens.lastIndex))
                    stateName != null && nameLeft > 0 && last == stateName[nameLeft - 1] -> {
                        soft.add(0, tokens.removeAt(tokens.lastIndex))
                        nameLeft--
                    }
                    ZIP_PLUS_FOUR.matches(last) && tokens.size > 2 && ZIP.matches(tokens[tokens.lastIndex - 1]) -> {
                        tokens.removeAt(tokens.lastIndex)
                        soft.add(0, tokens.removeAt(tokens.lastIndex))
                    }
                    else -> break
                }
            }
            return SearchQuery(tokens.toList(), soft, asksWeather)
        }

        /** "\"mai\" AND \"ain\"": every trigram of the words of 3+ letters, or null if none. */
        fun trigramMatch(words: List<String>): String? = words
            .flatMap { token -> (0..token.length - 3).map { token.substring(it, it + 3) } }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" AND ") { "\"$it\"" }
    }
}
