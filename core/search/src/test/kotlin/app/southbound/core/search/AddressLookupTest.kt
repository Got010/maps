package app.southbound.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressLookupTest {

    private fun query(raw: String) = AddressQuery.parse(raw)!!

    private fun house(key: Long, lat: Double, lon: Double = -73.92, postcode: String? = "10960") =
        HouseNumber(id = key, number = key.toString(), numberKey = key, postcode = postcode, lat = lat, lon = lon)

    @Test
    fun exactStreetAndTown() {
        val fit = AddressLookup.streetFit(query("123 Main St Nyack"), "main st", " main st nyack orangetown ny ")!!
        assertTrue(fit.exact)
        assertEquals(listOf("nyack"), fit.townWords)
        assertEquals(1.0, fit.townFraction, 0.0)
    }

    @Test
    fun municipalityCountsAsTheTown() {
        val fit = AddressLookup.streetFit(query("123 Main St Orangetown"), "main st", " main st nyack orangetown ny ")!!
        assertEquals(1, fit.townMatched)
    }

    @Test
    fun otherTownStillFitsButUnmatched() {
        val fit = AddressLookup.streetFit(query("123 Main St Nyack"), "main st", " main st sayville ny ")!!
        assertTrue(fit.exact)
        assertEquals(0.0, fit.townFraction, 0.0)
    }

    @Test
    fun partialWhileTyping() {
        val fit = AddressLookup.streetFit(query("123 Main S"), "main st", " main st nyack ny ")!!
        assertFalse(fit.exact)
        assertEquals(2, fit.streetWords)
    }

    @Test
    fun unknownStreetEndSplitsAtTheName() {
        val fit = AddressLookup.streetFit(query("123 Broadway Nyack"), "broadway", " broadway nyack ny ")!!
        assertTrue(fit.exact)
        assertEquals(listOf("nyack"), fit.townWords)
    }

    @Test
    fun otherStreet() {
        assertNull(AddressLookup.streetFit(query("123 Main St"), "elm st", " elm st nyack ny "))
    }

    @Test
    fun interpolatesBetweenNeighboursOnTheSameSide() {
        // 127 between 125 and 131 (odd side), ignoring 126 across the street.
        val below = listOf(house(126, 41.0), house(125, 41.0012))
        val above = listOf(house(131, 41.0018))
        val guess = AddressLookup.nearestHouse(127, below, above)!!
        assertEquals(127L, guess.numberKey)
        assertEquals(41.0014, guess.lat, 1e-9) // a third of the way from 125 to 131
        assertEquals(125L, guess.id) // the nearer neighbour
    }

    @Test
    fun farApartNeighboursGiveTheNearerOne() {
        val guess = AddressLookup.nearestHouse(127, listOf(house(125, 41.0)), listOf(house(131, 41.1)))!!
        assertEquals(41.0, guess.lat, 0.0)
    }

    @Test
    fun pastTheEndOnlyIfClose() {
        assertNotNull(AddressLookup.nearestHouse(150, listOf(house(131, 41.0)), emptyList()))
        assertNull(AddressLookup.nearestHouse(900, listOf(house(131, 41.0)), emptyList()))
        assertNull(AddressLookup.nearestHouse(5, emptyList(), emptyList()))
    }

    @Test
    fun townOutranksDistance() {
        // "123 Main St Nyack" typed in Sayville: Nyack's 123, 80 km away, still wins.
        val q = query("123 Main St Nyack")
        val nyackFit = AddressLookup.streetFit(q, "main st", " main st nyack ny ")!!
        val sayvilleFit = AddressLookup.streetFit(q, "main st", " main st sayville ny ")!!
        val nyack = AddressLookup.score(AddressLookup.EXACT, nyackFit, q, "10960", "NY", 80_000.0)
        val sayville = AddressLookup.score(AddressLookup.EXACT, sayvilleFit, q, "11782", "NY", 100.0)
        assertTrue(nyack > sayville)
    }

    @Test
    fun withoutATownTheCloserWins() {
        val q = query("123 Main St")
        val fit = AddressLookup.streetFit(q, "main st", " main st nyack ny ")!!
        val far = AddressLookup.score(AddressLookup.EXACT, fit, q, null, "NY", 80_000.0)
        val near = AddressLookup.score(AddressLookup.EXACT, fit, q, null, "NY", 100.0)
        assertTrue(near > far)
    }

    @Test
    fun zipCounts() {
        val q = query("123 Main St 10960")
        val fit = AddressLookup.streetFit(q, "main st", " main st nyack ny ")!!
        assertTrue(
            AddressLookup.score(AddressLookup.EXACT, fit, q, "10960", "NY", 50_000.0) >
                AddressLookup.score(AddressLookup.EXACT, fit, q, "11782", "NY", 0.0),
        )
    }

    @Test
    fun exactBeatsGuessBeatsStreetBeatsAPlace() {
        val q = query("123 Main St")
        val fit = AddressLookup.streetFit(q, "main st", " main st nyack ny ")!!
        val exact = AddressLookup.score(AddressLookup.EXACT, fit, q, null, null, 0.0)
        val guess = AddressLookup.score(AddressLookup.APPROXIMATE, fit, q, null, null, 0.0)
        val street = AddressLookup.score(AddressLookup.STREET, fit, q, null, null, 0.0)
        assertTrue(exact > guess && guess > street)
        // The best a place can do: an exact name (60) and high importance (100).
        assertTrue(exact > 160.0)
    }

    @Test
    fun exactness() {
        val q = query("123 Main St")
        assertEquals(AddressLookup.EXACT, AddressLookup.exactness(q, house(123, 41.0)), 0.0)
        val lettered = HouseNumber(1, "123A", 123, null, 41.0, -73.9)
        assertEquals(AddressLookup.SAME_KEY, AddressLookup.exactness(q, lettered), 0.0)
    }
}
