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
    fun distanceIsGreatCircle() {
        // Union Square to Times Square is about 2.4 km.
        val d = SearchRanking.distanceMeters(unionSquare, 40.7580, -73.9855)
        assertEquals(2500.0, d, 150.0)
    }
}
