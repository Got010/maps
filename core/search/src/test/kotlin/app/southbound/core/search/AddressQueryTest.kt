package app.southbound.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AddressQueryTest {

    private fun parse(raw: String) = AddressQuery.parse(raw) ?: error("not an address: $raw")

    @Test
    fun fullAddress() {
        val q = parse("123 Main Street, Nyack, NY 10960")
        assertEquals("123", q.number)
        assertEquals(123L, q.numberKey)
        assertEquals(listOf("main", "st"), q.streetTokens)
        assertEquals(listOf("nyack"), q.cityTokens)
        assertEquals("10960", q.zip)
        assertEquals("ny", q.state)
        assertFalse(q.streetEndUnknown)
    }

    @Test
    fun zipPlusFourAndCountry() {
        val q = parse("125 Main St Nyack NY 10960-1234 USA")
        assertEquals(listOf("nyack"), q.cityTokens)
        assertEquals("10960", q.zip)
        assertEquals("ny", q.state)
    }

    @Test
    fun queensNumbers() {
        val q = parse("31-05 Steinway St Astoria")
        assertEquals("31-05", q.number)
        assertEquals(310005L, q.numberKey)
        assertEquals(listOf("steinway", "st"), q.streetTokens)
        assertEquals(listOf("astoria"), q.cityTokens)
        assertEquals("12-34", parse("12 - 34 31st Ave").number)
    }

    @Test
    fun letterSuffix() {
        val q = parse("5a Elm St")
        assertEquals("5A", q.number)
        assertEquals(5L, q.numberKey)
    }

    @Test
    fun streetEndsAtTheFirstTypeWordNotAtTheStart() {
        // "St" at the start is Saint; the street ends at "Pl".
        assertEquals(listOf("st", "marks", "pl"), parse("10 St Marks Place").streetTokens)
        // A town called St James after the street.
        val q = parse("123 Main St St James")
        assertEquals(listOf("main", "st"), q.streetTokens)
        assertEquals(listOf("st", "james"), q.cityTokens)
        // A direction after the type word belongs to the street.
        val east = parse("4 Main Street East, Victor")
        assertEquals(listOf("main", "st", "e"), east.streetTokens)
        assertEquals(listOf("victor"), east.cityTokens)
    }

    @Test
    fun noTypeWordLeavesTheEndOpen() {
        val q = parse("123 Broadway Nyack")
        assertTrue(q.streetEndUnknown)
        assertEquals(listOf("broadway", "nyack"), q.streetTokens)
        assertEquals(
            listOf(listOf("broadway", "nyack"), listOf("broadway")),
            q.streetWordSets(),
        )
    }

    @Test
    fun streetWordSetsTryStreetAndTownThenStreet() {
        assertEquals(
            listOf(listOf("main", "st", "nyack"), listOf("main", "st")),
            parse("123 Main St Nyack").streetWordSets(),
        )
        assertEquals(listOf(listOf("main", "st")), parse("123 Main St").streetWordSets())
    }

    @Test
    fun newYorkIsTheStateOnlyAfterATown() {
        val upstate = parse("123 Main St, Nyack, New York")
        assertEquals(listOf("nyack"), upstate.cityTokens)
        assertEquals("ny", upstate.state)
        // Manhattan's postal town is "New York".
        val manhattan = parse("224 W 20th St New York")
        assertEquals(listOf("new", "york"), manhattan.cityTokens)
        assertNull(manhattan.state)
        assertEquals("nj", parse("1 Broad St Newark New Jersey").state)
    }

    @Test
    fun unitsAreDropped() {
        val q = parse("123 Main St Apt 4B Nyack")
        assertEquals(listOf("main", "st"), q.streetTokens)
        assertEquals(listOf("nyack"), q.cityTokens)
    }

    @Test
    fun notAddresses() {
        assertNull(AddressQuery.parse("Joe's Pizza"))
        assertNull(AddressQuery.parse("10011"))
        assertNull(AddressQuery.parse("1st Ave")) // an ordinal, not a house number
        assertNull(AddressQuery.parse("42nd St"))
        assertNull(AddressQuery.parse("5 Ave")) // a type word alone is no street
        assertNull(AddressQuery.parse("123"))
    }

    @Test
    fun numberKeyMatchesThePipeline() {
        // pipeline/search/house_number_cases.json, also run by test_addresses.py.
        val json = File("../../pipeline/search/house_number_cases.json").readText()
        val cases = Regex("\\[\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*,\\s*(-?\\d+|null)\\s*]").findAll(json).toList()
        assertTrue("expected the shared cases", cases.size >= 5)
        for (case in cases) {
            val (raw, expected) = case.destructured
            assertEquals(raw, expected.toLongOrNull(), AddressQuery.numberKey(raw))
        }
    }

    @Test
    fun displayMatchesThePipeline() {
        // The same two lines as test_addresses.py test_display_address.
        assertEquals(
            "123 Main Street, Nyack, NY 10960",
            AddressFormat.display("123", "Main Street", "Nyack", "NY", "10960"),
        )
        assertEquals("5 Elm St, NY", AddressFormat.display("5", "Elm St", null, "NY", null))
    }
}
