package app.southbound.core.search

import java.text.Normalizer
import java.util.Locale

/**
 * Normalises a query the same way the pipeline normalised the index, so
 * "E 14th St" finds "East 14th Street". This is the Kotlin twin of
 * pipeline/search/normalize.py; both pass normalize_cases.json, so change
 * all three together.
 */
object SearchText {

    private val ABBREVIATIONS = mapOf(
        "street" to "st",
        "avenue" to "ave",
        "av" to "ave",
        "boulevard" to "blvd",
        "road" to "rd",
        "place" to "pl",
        "drive" to "dr",
        "lane" to "ln",
        "court" to "ct",
        "parkway" to "pkwy",
        "expressway" to "expy",
        "highway" to "hwy",
        "terrace" to "ter",
        "square" to "sq",
        "plaza" to "plz",
        "turnpike" to "tpke",
        "east" to "e",
        "west" to "w",
        "north" to "n",
        "south" to "s",
        "saint" to "st",
        "mount" to "mt",
        "fort" to "ft",
        "building" to "bldg",
        "center" to "ctr",
        "centre" to "ctr",
    )

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val NOT_WORD = Regex("[^0-9a-z]+")
    private val ORDINAL = Regex("\\b(\\d+)(st|nd|rd|th)\\b")

    /** Space-separated normalised tokens, with no leading or trailing space. */
    fun normalize(text: String): String {
        var s = Normalizer.normalize(text, Normalizer.Form.NFKD)
        s = COMBINING_MARKS.replace(s, "")
        s = s.lowercase(Locale.ROOT).replace("&", " and ").replace("'", "")
        s = NOT_WORD.replace(s, " ")
        s = ORDINAL.replace(s, "$1")
        return s.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { ABBREVIATIONS[it] ?: it }
    }
}
