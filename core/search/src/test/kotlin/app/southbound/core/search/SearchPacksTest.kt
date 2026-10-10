package app.southbound.core.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchPacksTest {
    private fun result(id: Long, name: String, lat: Double, lon: Double, kind: PlaceKind = PlaceKind.PLACE, distance: Double? = null) =
        SearchResult(Place(id, kind, name, null, null, lat, lon), distance)

    private fun scored(score: Double, r: SearchResult) = ScoredResult(r, score)

    @Test
    fun `results from several packs merge by score, ids moved per pack`() {
        val ny = listOf(
            scored(50.0, result(7, "Easton Diner", 40.69, -75.21)),
            scored(30.0, result(8, "Easton Avenue", 40.50, -74.45, PlaceKind.STREET)),
        )
        val pa = listOf(
            scored(45.0, result(7, "Easton", 40.6884, -75.2207, PlaceKind.LOCALITY)),
            scored(10.0, result(9, "Easton Area High School", 40.70, -75.25)),
        )
        val merged = SearchPacks.merge(listOf(ny, pa), limit = 10)
        assertEquals(listOf("Easton Diner", "Easton", "Easton Avenue", "Easton Area High School"), merged.map { it.place.name })
        // The first pack keeps its ids; the second pack's move up, so the two "7"s don't clash.
        assertEquals(listOf(7L, 7L + SearchPacks.PACK_ID_STRIDE, 8L, 9L + SearchPacks.PACK_ID_STRIDE), merged.map { it.place.id })
    }

    @Test
    fun `a place both packs hold comes once`() {
        // Milford, PA, on the Delaware: in Pennsylvania's pack and, at the edge, in New Jersey's.
        val a = listOf(scored(60.0, result(1, "Milford", 41.3223, -74.8024, PlaceKind.LOCALITY)))
        val b = listOf(scored(58.0, result(5, "Milford", 41.3226, -74.8020, PlaceKind.LOCALITY)))
        val merged = SearchPacks.merge(listOf(a, b), limit = 10)
        assertEquals(1, merged.size)
        assertEquals(1L, merged[0].place.id)
        // A Milford elsewhere (Connecticut) is another place.
        val c = listOf(scored(55.0, result(3, "Milford", 41.2223, -73.0565, PlaceKind.LOCALITY)))
        assertEquals(2, SearchPacks.merge(listOf(a, b, c), limit = 10).size)
    }

    @Test
    fun `the limit counts after merging`() {
        val a = (1..5).map { scored(100.0 - it, result(it.toLong(), "A$it", 40.0 + it, -74.0)) }
        val b = (1..5).map { scored(99.5 - it, result(it.toLong(), "B$it", 41.0 + it, -75.0)) }
        assertEquals(listOf("A1", "B1", "A2", "B2"), SearchPacks.merge(listOf(a, b), limit = 4).map { it.place.name })
    }
}
