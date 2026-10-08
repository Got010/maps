package app.southbound.core.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchRankingTest {

    private val unionSquare = LatLon(40.7359, -73.9911)

    private fun candidate(
        id: Long,
        name: String,
        lat: Double,
        lon: Double,
        importance: Int = 30,
        extra: String = "",
    ): Candidate {
        val key = SearchText.normalize(name)
        return Candidate(
            place = Place(id, PlaceKind.PLACE, name, null, null, lat, lon),
            importance = importance,
            nameKey = key,
            searchText = " ${SearchText.normalize("$name $extra")} ",
        )
    }

    @Test
    fun closerWinsBetweenEqualNames() {
        val far = candidate(1, "Joe's Pizza", 40.8500, -73.9300)
        val near = candidate(2, "Joe's Pizza", 40.7340, -73.9890)
        val results = SearchRanking.rank(SearchQuery.parse("joes pizza"), listOf(far, near), unionSquare, 10)
        assertEquals(listOf(2L, 1L), results.map { it.place.id })
        assertEquals(290.0, results.first().distanceMeters!!, 50.0)
    }

    @Test
    fun nameMatchBeatsCategoryMatch() {
        val byCategory = candidate(1, "Ray's", 40.7360, -73.9910, extra = "pizza")
        val byName = candidate(2, "Pizza Suprema", 40.7500, -73.9950)
        val results = SearchRanking.rank(SearchQuery.parse("pizza"), listOf(byCategory, byName), unionSquare, 10)
        assertEquals(2L, results.first().place.id)
    }

    @Test
    fun notablePlacesFloatUp() {
        val station = candidate(1, "Union Square", 40.7350, -73.9900, importance = 60)
        val shop = candidate(2, "Union Square Wines", 40.7360, -73.9910, importance = 30)
        val results = SearchRanking.rank(SearchQuery.parse("union sq"), listOf(shop, station), unionSquare, 10)
        assertEquals(listOf(1L, 2L), results.map { it.place.id })
    }

    @Test
    fun rowsNotContainingEveryWordAreDropped() {
        val lookalike = candidate(1, "Grumpy Joe", 40.73, -73.99)
        val results = SearchRanking.rank(SearchQuery.parse("rumpy"), listOf(lookalike), null, 10)
        assertEquals(emptyList<SearchResult>(), results)
    }

    @Test
    fun limitAndNoDistanceWithoutLocation() {
        val many = (1L..30L).map { candidate(it, "Deli $it", 40.73, -73.99) }
        val results = SearchRanking.rank(SearchQuery.parse("deli"), many, null, 5)
        assertEquals(5, results.size)
        assertEquals(null, results.first().distanceMeters)
    }

    @Test
    fun softWordsRaiseButDontFilter() {
        val brooklyn = candidate(1, "Joe's Pizza", 40.73, -73.99, extra = "brooklyn ny 11201")
        val plain = candidate(2, "Joe's Pizza", 40.73, -73.99)
        val results = SearchRanking.rank(SearchQuery.parse("joes pizza ny 11201"), listOf(plain, brooklyn), null, 10)
        assertEquals(listOf(1L, 2L), results.map { it.place.id })
    }

    @Test
    fun olderPacksAddressBeatsAPlaceOnItsStreet() {
        // Schema 1 packs keep addresses in `feature`: "123 Court Street" with
        // its town in the text, next to a famous place on the same street.
        val address = Candidate(
            place = Place(1, PlaceKind.ADDRESS, "123 Court Street", null, "Brooklyn 11201", 40.69, -73.99),
            importance = 10,
            nameKey = "123 ct st",
            searchText = " 123 ct st brooklyn 11201 ",
        )
        val place = candidate(2, "123 Court Street Deli", 40.69, -73.99, importance = 60, extra = "brooklyn")
        val results = SearchRanking.rank(SearchQuery.parse("123 Court St Brooklyn"), listOf(place, address), null, 10)
        assertEquals(listOf(1L, 2L), results.map { it.place.id })
    }

    @Test
    fun extraResultsMergeByScore() {
        val deli = candidate(1, "Main Street Deli", 41.09, -73.92)
        val address = Place(1L shl 40, PlaceKind.ADDRESS, "123 Main Street", null, "123 Main Street, Nyack, NY", 41.09, -73.92)
        val extra = listOf(ScoredResult(SearchResult(address, null), AddressLookup.EXACT))
        val results = SearchRanking.rank(SearchQuery.parse("123 main st"), listOf(deli), null, 10, extra)
        // The deli doesn't contain "123"; the address comes through the extra list.
        assertEquals(listOf(1L shl 40), results.map { it.place.id })
    }

    @Test
    fun distanceIsGreatCircle() {
        // Union Square to Times Square is about 2.4 km.
        val d = SearchRanking.distanceMeters(unionSquare, 40.7580, -73.9855)
        assertEquals(2500.0, d, 150.0)
    }

    @Test
    fun aWeatherSearchPutsTheTownFirstHoweverFar() {
        // From Peekskill: the city of Albany is about 150 km away; "Albany Post Road" runs past the door.
        val peekskill = LatLon(41.29, -73.92)
        val query = SearchQuery.parse("weather albany")
        val city = candidate(1, "Albany", 42.6526, -73.7562, importance = 60)
        val hamlet = candidate(2, "South Albany", 41.30, -73.90, importance = 10)
        // With the usual distance weight the city loses to anything nearby...
        val road = candidate(3, "Albany Post Road", 41.30, -73.91)
        assertEquals(3L, SearchRanking.rank(query, listOf(city, road), peekskill, 10).first().place.id)
        // ...among towns, by name and size, distance only a tie-break: the city first.
        val towns = SearchRanking.rank(query, listOf(hamlet, city), peekskill, 10, pointsPerKm = SearchRanking.TOWN_POINTS_PER_KM)
        assertEquals(listOf(1L, 2L), towns.map { it.place.id })
        assertEquals(152_000.0, towns.first().distanceMeters!!, 5_000.0)
    }
}
