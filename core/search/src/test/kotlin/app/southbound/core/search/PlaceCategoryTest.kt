package app.southbound.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceCategoryTest {
    private val flatiron = LatLon(40.7410, -73.9897)

    private fun candidate(id: Long, name: String, category: String, lat: Double, lon: Double, source: PlaceSource = PlaceSource.OSM) =
        Candidate(
            Place(id, PlaceKind.PLACE, name, category, null, lat, lon, source = source),
            importance = 30,
            nameKey = SearchText.normalize(name),
            searchText = " ${SearchText.normalize("$name ${category.replace('_', ' ')}")} ",
        )

    @Test
    fun `a kind is its categories, OSM's and Overture's as the pipeline maps them`() {
        assertTrue(PlaceCategory.GAS.matches("fuel"))
        assertFalse(PlaceCategory.GAS.matches("restaurant"))
        assertTrue(PlaceCategory.HOTELS.matches("motel"))
        assertTrue(PlaceCategory.PHARMACY.matches("chemist"))
        assertTrue(PlaceCategory.COFFEE.matches("cafe"))
        assertFalse(PlaceCategory.COFFEE.matches(null))
        // Overture's own slugs ending in "_restaurant" are food; a word inside another slug is not.
        assertTrue(PlaceCategory.FOOD.matches("pizza_restaurant"))
        assertTrue(PlaceCategory.FOOD.matches("fast_food"))
        assertFalse(PlaceCategory.FOOD.matches("restaurant_supply_store"))
    }

    @Test
    fun `the index lookup asks for any of the kind's words, normalised as the pack's text is`() {
        assertEquals("(\"fue\" AND \"uel\")", PlaceCategory.GAS.ftsMatch)
        // "court" is stored as "ct" (SearchText's abbreviations), too short for a trigram: "food" alone finds it.
        val food = PlaceCategory.FOOD.ftsMatch
        assertEquals(
            "(\"res\" AND \"est\" AND \"sta\" AND \"tau\" AND \"aur\" AND \"ura\" AND \"ran\" AND \"ant\")" +
                " OR (\"fas\" AND \"ast\" AND \"foo\" AND \"ood\") OR (\"foo\" AND \"ood\")",
            food,
        )
        // The suffix's word is a category's already: asked once.
        assertEquals(3, food.split(" OR ").size)
        assertEquals(PlaceCategory.HOTELS.categories.size, PlaceCategory.HOTELS.ftsMatch.split(" OR ").size)
    }

    @Test
    fun `the SQL condition takes the values and the suffixes literally`() {
        val (sql, args) = PlaceCategory.FOOD.sqlCondition("o.category")
        assertEquals("(o.category IN (?, ?, ?) OR o.category LIKE ? ESCAPE '\\')", sql)
        assertEquals(listOf("restaurant", "fast_food", "food_court", "%\\_restaurant"), args)
        val (gas, gasArgs) = PlaceCategory.GAS.sqlCondition("f.category")
        assertEquals("(f.category IN (?))", gas)
        assertEquals(listOf("fuel"), gasArgs)
    }

    @Test
    fun `nearest merges Overture's twins away and keeps the radius, nearest first`() {
        val osm = listOf(
            candidate(1, "Shell", "fuel", 40.7480, -73.9897),
            candidate(2, "Mobil", "fuel", 40.7420, -73.9897),
        )
        val overture = listOf(
            // Shell again, 20 m off: the same station.
            candidate(SearchIndex.OVERTURE_ID_BASE + 1, "Shell", "fuel", 40.7481, -73.9895, PlaceSource.OVERTURE),
            candidate(SearchIndex.OVERTURE_ID_BASE + 2, "Sunoco", "fuel", 40.7450, -73.9897, PlaceSource.OVERTURE),
            // About 70 km north, past the radius though inside a looser box.
            candidate(SearchIndex.OVERTURE_ID_BASE + 3, "Far Gas", "fuel", 41.37, -73.9897, PlaceSource.OVERTURE),
        )
        val found = PlaceCategory.nearest(osm, overture, flatiron, limit = 10)
        assertEquals(listOf("Mobil", "Sunoco", "Shell"), found.map { it.place.name })
        assertEquals(PlaceSource.OSM, found.last().place.source)
        assertEquals(111.0, found.first().distanceMeters!!, 2.0)
        assertEquals(listOf("Mobil"), PlaceCategory.nearest(osm, overture, flatiron, limit = 1).map { it.place.name })
    }
}
