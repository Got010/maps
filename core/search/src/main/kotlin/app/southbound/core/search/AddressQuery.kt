package app.southbound.core.search

/**
 * A query that reads as a street address: "123 Main St, Nyack NY 10960",
 * Queens' "31-05 Steinway St", "5A Elm St".
 *
 * The house number must come first. What follows is normalised like any
 * query ([SearchText]) and split into the street and, after it, the town:
 * the street ends at its first type word ("st", "ave", "rd", ...) not at
 * the start, plus a direction after it ("Main St E"). With no type word
 * ("123 Broadway Nyack") the end is unknown and [streetWordSets] tries
 * shorter and shorter streets. A trailing ZIP and state ("NY", "NJ", "USA",
 * "New York" after a town) are taken off too.
 *
 * The town, ZIP and state only rank results ([AddressRanking]); a row
 * lacking them still matches.
 */
data class AddressQuery(
    /** As typed, tidied like the pack's numbers: "123", "31-05", "5A". */
    val number: String,
    /** See [numberKey]. */
    val numberKey: Long,
    /** Normalised street words: ["main", "st"]. */
    val streetTokens: List<String>,
    /** Normalised words after the street, taken as the town: ["nyack"]. */
    val cityTokens: List<String>,
    /** Five-digit ZIP, if given. */
    val zip: String?,
    /** Two-letter state in lower case ("ny"), if given. */
    val state: String?,
    /** No type word marked where the street ends. */
    val streetEndUnknown: Boolean,
) {
    /** Street then town words, as typed. */
    val placeTokens: List<String> get() = streetTokens + cityTokens

    /**
     * The word lists to look streets up with, most specific first; the first
     * that finds any street wins. Town words are in a street row's text, so
     * the first try is street and town together, then the street alone; with
     * an unknown street end, trailing words are dropped one at a time.
     */
    fun streetWordSets(): List<List<String>> = buildList {
        add(placeTokens)
        if (cityTokens.isNotEmpty()) add(streetTokens)
        if (streetEndUnknown) {
            for (k in placeTokens.size - 1 downTo 1) add(placeTokens.take(k))
        }
    }.distinct()

    companion object {
        /** A house number first: digits, a Queens "-34" part, a letter; then a space, comma or the end. */
        private val NUMBER = Regex("^\\s*#?(\\d+(?:\\s*-\\s*\\d+)?[a-zA-Z]?)(?=[\\s,]|$)")
        private val HOUSE_NUMBER = Regex("^\\s*(\\d+)(?:\\s*-\\s*(\\d+))?")
        private val ZIP = Regex("\\d{5}")
        private val ZIP_PLUS_FOUR = Regex("\\d{4}")
        private val SPACES_AROUND_HYPHEN = Regex("\\s*-\\s*")

        /** Words that end a street name, after [SearchText] shortening. */
        val STREET_TYPES = setOf(
            "st", "ave", "blvd", "rd", "pl", "dr", "ln", "ct", "pkwy", "expy", "hwy", "ter", "sq",
            "plz", "tpke", "way", "cir", "circle", "trl", "aly", "xing", "loop",
        )
        val DIRECTIONS = setOf("n", "s", "e", "w", "ne", "nw", "se", "sw")
        val STATE_WORDS = setOf("ny", "nj")
        private val COUNTRY = setOf("usa", "us")
        private val UNIT_WORDS = setOf("apt", "apartment", "unit", "ste", "suite", "fl", "floor", "rm", "room")

        /** Null when [raw] doesn't start with a house number followed by a street. */
        fun parse(raw: String): AddressQuery? {
            val match = NUMBER.find(raw) ?: return null
            val number = tidyNumber(match.groupValues[1])
            val key = numberKey(number) ?: return null
            val tokens = SearchText.normalize(raw.substring(match.range.last + 1))
                .split(' ').filter { it.isNotEmpty() }.toMutableList()
            dropUnits(tokens)

            var zip: String? = null
            var state: String? = null
            var sawState = false
            while (tokens.size > 1) {
                val last = tokens[tokens.lastIndex]
                val previous = tokens[tokens.lastIndex - 1]
                when {
                    zip == null && ZIP.matches(last) -> zip = tokens.removeAt(tokens.lastIndex)
                    zip == null && ZIP_PLUS_FOUR.matches(last) && ZIP.matches(previous) -> {
                        tokens.removeAt(tokens.lastIndex)
                        zip = tokens.removeAt(tokens.lastIndex)
                    }
                    last in COUNTRY -> tokens.removeAt(tokens.lastIndex)
                    !sawState && last in STATE_WORDS -> {
                        state = tokens.removeAt(tokens.lastIndex)
                        sawState = true
                    }
                    !sawState && last == "jersey" && previous == "new" && tokens.size > 2 -> {
                        tokens.removeAt(tokens.lastIndex)
                        tokens.removeAt(tokens.lastIndex)
                        state = "nj"
                        sawState = true
                    }
                    else -> break
                }
            }

            val typeAt = (1 until tokens.size).firstOrNull { tokens[it] in STREET_TYPES }
            val streetEnd = when {
                typeAt == null -> tokens.size
                tokens.getOrNull(typeAt + 1) in DIRECTIONS -> typeAt + 2
                else -> typeAt + 1
            }
            val street = tokens.subList(0, streetEnd).toList()
            var city = tokens.subList(streetEnd, tokens.size).toList()
            // "..., Nyack, New York": the state. "123 Main St New York" alone: the town.
            if (!sawState && city.size > 2 && city.takeLast(2) == listOf("new", "york")) {
                city = city.dropLast(2)
                state = "ny"
            }
            if (street.isEmpty() || street.all { it in STREET_TYPES || it in DIRECTIONS }) return null
            return AddressQuery(number, key, street, city, zip, state, streetEndUnknown = typeAt == null)
        }

        /**
         * A sortable number for a house number, or null without leading
         * digits: "123" and "123A" -> 123, Queens' "12-34" -> 120034 (block
         * times 10,000 plus house). The twin of normalize.house_number_key in
         * the pipeline; both pass house_number_cases.json.
         */
        fun numberKey(number: String): Long? {
            val match = HOUSE_NUMBER.find(number) ?: return null
            val first = match.groupValues[1].toLongOrNull() ?: return null
            val second = match.groupValues[2]
            return if (second.isEmpty()) first else first * 10_000 + (second.toLongOrNull() ?: 0) % 10_000
        }

        /** "12 - 34" -> "12-34", "5a" -> "5A", like the pipeline's clean_number. */
        fun tidyNumber(number: String): String = number.trim().replace(SPACES_AROUND_HYPHEN, "-").uppercase()

        /** "apt 4b", "suite 200": the word and what follows it. */
        private fun dropUnits(tokens: MutableList<String>) {
            var i = 0
            while (i < tokens.size) {
                if (tokens[i] in UNIT_WORDS && i > 0) {
                    tokens.removeAt(i)
                    if (i < tokens.size) tokens.removeAt(i)
                } else {
                    i++
                }
            }
        }
    }
}

/** The one-line address the app shows; the pipeline's display_address builds the same. */
object AddressFormat {
    /** "123 Main Street, Nyack, NY 10960" */
    fun display(number: String, street: String, city: String?, state: String?, postcode: String?): String {
        val line = StringBuilder("$number $street")
        if (!city.isNullOrBlank()) line.append(", ").append(city)
        val region = listOfNotNull(state?.takeIf { it.isNotBlank() }, postcode?.takeIf { it.isNotBlank() }).joinToString(" ")
        if (region.isNotEmpty()) line.append(", ").append(region)
        return line.toString()
    }
}
