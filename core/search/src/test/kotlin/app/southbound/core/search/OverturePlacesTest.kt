package app.southbound.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverturePlacesTest {

    private val unionSquare = LatLon(40.7359, -73.9911)

    private fun osm(
        id: Long,
        name: String,
        lat: Double,
        lon: Double,
        kind: PlaceKind = PlaceKind.PLACE,
        phone: String? = null,
        website: String? = null,
    ): Candidate {
        val key = SearchText.normalize(name)
        return Candidate(
            place = Place(id, kind, name, "cafe", null, lat, lon, phone = phone, website = website, osmRef = "n$id"),
            importance = 30,
            nameKey = key,
            searchText = " $key ",
        )
    }

    private fun overture(
        rowid: Long,
        name: String,
        lat: Double,
        lon: Double,
        confidence: Double = 0.9,
        category: String? = "cafe",
        phone: String? = null,
        website: String? = null,
    ): Candidate {
        val key = SearchText.normalize(name)
        return Candidate(
            place = Place(
                SearchIndex.OVERTURE_ID_BASE + rowid, PlaceKind.PLACE, name, category, null, lat, lon,
                phone = phone, website = website, source = PlaceSource.OVERTURE,
            ),
            importance = OverturePlaces.importance(category, confidence),
            nameKey = key,
            searchText = " $key ",
        )
    }

    @Test
    fun importanceSitsBelowOsmAndFollowsConfidence() {
        assertEquals(25, OverturePlaces.importance("cafe", 1.0))
        assertEquals(24, OverturePlaces.importance("cafe", 0.9))
        assertEquals(20, OverturePlaces.importance(null, 0.5))
        assertEquals(35, OverturePlaces.importance("museum", 1.0))
        // Out-of-range confidences are clamped.
        assertEquals(25, OverturePlaces.importance(null, 3.0))
        assertEquals(15, OverturePlaces.importance(null, -1.0))
    }

    @Test
    fun sameNameCloseByIsDropped() {
        val grumpy = osm(1, "Café Grumpy", 40.73510, -73.99100)
        // About 20 m away, accents and case aside the same name.
        val twin = overture(1, "Cafe Grumpy", 40.73525, -73.99110)
        val merged = OverturePlaces.merge(listOf(grumpy), listOf(twin))
        assertEquals(listOf(1L), merged.map { it.place.id })
    }

    @Test
    fun sameNameFarAwayIsKept() {
        val grumpy = osm(1, "Café Grumpy", 40.73510, -73.99100)
        val otherBranch = overture(2, "Café Grumpy", 40.74200, -74.00050) // about 1 km
        val merged = OverturePlaces.merge(listOf(grumpy), listOf(otherBranch))
        assertEquals(listOf(1L, SearchIndex.OVERTURE_ID_BASE + 2), merged.map { it.place.id })
    }

    @Test
    fun justOverTheLimitIsKept() {
        val a = osm(1, "Joe's Pizza", 40.7306, -74.0020)
        val b = overture(1, "Joe's Pizza", 40.7306 + 70.0 / 111_195.0, -74.0020) // 70 m north
        assertFalse(OverturePlaces.isDuplicate(a, b))
        val c = overture(2, "Joe's Pizza", 40.7306 + 50.0 / 111_195.0, -74.0020) // 50 m north
        assertTrue(OverturePlaces.isDuplicate(a, c))
    }

    @Test
    fun aNameStartingTheOtherCountsOnlyVeryClose() {
        val starbucks = osm(1, "Starbucks", 40.7500, -73.9900)
        val near = overture(1, "Starbucks Coffee", 40.7500 + 20.0 / 111_195.0, -73.9900) // 20 m
        val far = overture(2, "Starbucks Coffee", 40.7500 + 45.0 / 111_195.0, -73.9900) // 45 m
        assertTrue(OverturePlaces.isDuplicate(starbucks, near))
        assertFalse(OverturePlaces.isDuplicate(starbucks, far))
        // A shared first word is not enough.
        val other = overture(3, "Starbuckses Bar", 40.7500, -73.9900)
        assertFalse(OverturePlaces.isDuplicate(starbucks, other))
    }

    @Test
    fun differentNamesAtOneSpotAreBothKept() {
        // Overture often puts every business in a building on one point.
        val deli = osm(1, "Carmine Deli", 40.7306, -74.0020)
        val salon = overture(1, "Carmine Nails", 40.7306, -74.0020)
        assertEquals(2, OverturePlaces.merge(listOf(deli), listOf(salon)).size)
    }

    @Test
    fun streetsAreNotPlaces() {
        val street = osm(1, "Broadway", 40.7500, -73.9870, kind = PlaceKind.STREET)
        val theatre = overture(1, "Broadway", 40.7501, -73.9871)
        assertEquals(2, OverturePlaces.merge(listOf(street), listOf(theatre)).size)
    }

    @Test
    fun osmPlaceTakesMissingPhoneAndWebsite() {
        val grumpy = osm(1, "Café Grumpy", 40.7351, -73.9910, website = "https://osm.example")
        val twin = overture(1, "Café Grumpy", 40.7351, -73.9910, phone = "+12122555511", website = "https://ovt.example")
        val merged = OverturePlaces.merge(listOf(grumpy), listOf(twin)).single().place
        assertEquals("+12122555511", merged.phone)
        assertEquals("https://osm.example", merged.website) // OSM's own stays
        assertEquals(PlaceSource.OSM, merged.source)
        assertEquals("n1", merged.osmRef)
    }

    @Test
    fun withoutOvertureNothingChanges() {
        val list = listOf(osm(1, "Café Grumpy", 40.7351, -73.9910))
        assertEquals(list, OverturePlaces.merge(list, emptyList()))
    }

    @Test
    fun osmWinsATieInRanking() {
        // Same name, same spot as each other but not deduped here (ranked
        // directly): OSM's importance is higher, so it comes first.
        val a = osm(1, "Joe's Pizza", 40.7306, -74.0020)
        val b = overture(1, "Joe's Pizza", 40.7306, -74.0020, confidence = 1.0)
        val results = SearchRanking.rank(SearchQuery.parse("joes pizza"), listOf(b, a), unionSquare, 10)
        assertEquals(listOf(1L, SearchIndex.OVERTURE_ID_BASE + 1), results.map { it.place.id })
    }

    @Test
    fun confidentOvertureRowsRankFirst() {
        val sure = overture(1, "Pizza Place", 40.7400, -73.9900, confidence = 0.98)
        val unsure = overture(2, "Pizza Palace", 40.7400, -73.9900, confidence = 0.55)
        val results = SearchRanking.rank(SearchQuery.parse("pizza"), listOf(unsure, sure), unionSquare, 10)
        assertEquals(listOf(sure.place.id, unsure.place.id), results.map { it.place.id })
        assertEquals(PlaceSource.OVERTURE, results.first().place.source)
    }

    @Test
    fun nearbyOvertureBeatsFarOsm() {
        // A small penalty, not a wall: a close Overture shop beats a far OSM one.
        val far = osm(1, "Joe's Pizza", 40.8500, -73.9300) // ~14 km
        val near = overture(1, "Joe's Pizza", 40.7340, -73.9890)
        val results = SearchRanking.rank(SearchQuery.parse("joes pizza"), listOf(far, near), unionSquare, 10)
        assertEquals(near.place.id, results.first().place.id)
    }

    @Test
    fun placeDefaultsToOsm() {
        assertEquals(PlaceSource.OSM, Place.pin(40.0, -74.0, "Pin").source)
        assertNull(overture(1, "X Y", 40.0, -74.0).place.osmRef)
    }
}
