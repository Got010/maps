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
    fun repeatedTrigramsAppearOnce() {
        assertEquals("\"aaa\"", SearchQuery.parse("aaaaa").ftsMatch)
    }

    @Test
    fun emptyQuery() {
        assertTrue(SearchQuery.parse("  ,. ").isEmpty)
    }

    @Test
    fun wordsMustStartAWord() {
        val query = SearchQuery.parse("grump")
        assertTrue(query.matchesText(" cafe grumpy 224 w 20 st "))
        assertFalse(SearchQuery.parse("rumpy").matchesText(" cafe grumpy "))
        assertTrue(SearchQuery.parse("ct st").matchesText(" 123 ct st brooklyn "))
    }
}
