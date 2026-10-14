package app.southbound.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {

    @Test
    fun trigramsOfEachLongWordAreAnded() {
        val query = SearchQuery.parse("Joe's Pizza")
        assertEquals(listOf("joes", "pizza"), query.tokens)
        assertEquals("\"joe\" AND \"oes\" AND \"piz\" AND \"izz\" AND \"zza\"", query.ftsMatch)
    }

    @Test
    fun shortWordsHaveNoTrigrams() {
        val query = SearchQuery.parse("123 Court St")
        assertEquals(listOf("123", "ct", "st"), query.tokens)
        assertEquals("\"123\"", query.ftsMatch)
    }

    @Test
    fun onlyShortWordsFallBackToPrefix() {
        val query = SearchQuery.parse("E 4")
        assertNull(query.ftsMatch)
        assertEquals("e 4", query.prefix)
    }

    @Test
    fun everyCoveredStateIsSoft() {
        // Place text never carries the state: "Hartford CT" used to look for streets containing "ct".
        assertEquals(listOf("hartford") to listOf("ct"), SearchQuery.parse("Hartford CT").let { it.tokens to it.soft })
        assertEquals(listOf("joes", "pizza", "scranton") to listOf("pa"), SearchQuery.parse("Joe's Pizza Scranton PA").let { it.tokens to it.soft })
        assertEquals(listOf("columbus") to listOf("oh", "43215"), SearchQuery.parse("Columbus OH 43215").let { it.tokens to it.soft })
        // A name after a comma, as an address writes it.
        assertEquals(listOf("hartford") to listOf("connecticut"), SearchQuery.parse("Hartford, Connecticut").let { it.tokens to it.soft })
        assertEquals(listOf("newark") to listOf("new", "jersey", "07102"), SearchQuery.parse("Newark, New Jersey 07102").let { it.tokens to it.soft })
        // Names with a compass word are matched as the query's words are normalised ("north" is "n").
        assertEquals(listOf("fargo") to listOf("n", "dakota"), SearchQuery.parse("Fargo, North Dakota").let { it.tokens to it.soft })
        assertEquals(listOf("charleston") to listOf("w", "virginia"), SearchQuery.parse("Charleston, West Virginia").let { it.tokens to it.soft })
        assertEquals(listOf("richmond") to listOf("virginia"), SearchQuery.parse("Richmond, Virginia").let { it.tokens to it.soft })
        assertEquals(listOf("louisville") to listOf("ky"), SearchQuery.parse("Louisville KY").let { it.tokens to it.soft })
        assertEquals(listOf("wheeling") to listOf("wv", "26003"), SearchQuery.parse("Wheeling WV 26003").let { it.tokens to it.soft })
        // A town named for a state stays a required word after a comma: "Virginia Beach" is not Virginia.
        assertEquals(listOf("atlantic", "ave", "virginia", "beach"), SearchQuery.parse("Atlantic Ave, Virginia Beach").tokens)
        // Without one, a state's name may be the place's own.
        assertEquals(listOf("lake", "michigan") to emptyList<String>(), SearchQuery.parse("Lake Michigan").let { it.tokens to it.soft })
        assertEquals(listOf("indiana") to emptyList<String>(), SearchQuery.parse("Indiana").let { it.tokens to it.soft })
        assertEquals(listOf("pa") to emptyList<String>(), SearchQuery.parse("PA").let { it.tokens to it.soft })
    }

    @Test
    fun repeatedTrigramsAppearOnce() {
        assertEquals("\"aaa\"", SearchQuery.parse("aaaaa").ftsMatch)
    }

    @Test
    fun weatherWordsAskForThePlacesWeather() {
        for (raw in listOf("weather albany", "Albany weather", "weather in Albany", "forecast for albany")) {
            val query = SearchQuery.parse(raw)
            assertEquals(raw, listOf("albany"), query.tokens)
            assertTrue(raw, query.asksWeather)
        }
        assertEquals(listOf("nyack"), SearchQuery.parse("weather nyack ny").tokens)
        // On its own, or inside a name, it is just a word.
        assertEquals(listOf("weather"), SearchQuery.parse("weather").tokens)
        assertFalse(SearchQuery.parse("weather").asksWeather)
        assertEquals(listOf("weathersfield"), SearchQuery.parse("Weathersfield").tokens)
        assertFalse(SearchQuery.parse("pizza").asksWeather)
    }

    @Test
    fun emptyQuery() {
        assertTrue(SearchQuery.parse("  ,. ").isEmpty)
    }

    @Test
    fun trailingStateAndZipAreSoft() {
        val query = SearchQuery.parse("123 Court St, Brooklyn, NY 11201-1234")
        assertEquals(listOf("123", "ct", "st", "brooklyn"), query.tokens)
        assertEquals(listOf("ny", "11201"), query.soft)
        // A row without them still matches; one with them scores the matches.
        assertTrue(query.matchesText(" 123 ct st brooklyn "))
        assertEquals(2, query.softMatches(" 123 ct st brooklyn ny 11201 "))
        assertEquals(0, query.softMatches(" 123 ct st brooklyn "))
    }

    @Test
    fun aLoneStateWordIsStillSearched() {
        val query = SearchQuery.parse("NY")
        assertEquals(listOf("ny"), query.tokens)
        assertTrue(query.soft.isEmpty())
    }

    @Test
    fun wordsMustStartAWord() {
        val query = SearchQuery.parse("grump")
        assertTrue(query.matchesText(" cafe grumpy 224 w 20 st "))
        assertFalse(SearchQuery.parse("rumpy").matchesText(" cafe grumpy "))
        assertTrue(SearchQuery.parse("ct st").matchesText(" 123 ct st brooklyn "))
    }
}
