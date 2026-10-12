package app.southbound.core.search

import org.junit.Assert.assertEquals
import org.junit.Test

class PackMergeTest {
    private fun result(id: Long, name: String, lat: Double, lon: Double, kind: PlaceKind = PlaceKind.PLACE) =
        SearchResult(Place(id, kind, name, null, null, lat, lon), null)

    private fun scored(score: Double, r: SearchResult) = ScoredResult(r, score)

    private fun mark(id: Long, category: String, east: Double, north: Double): Landmark {
        val p = Hand.at(east, north)
        return Landmark(id, category, null, p.lat, p.lon)
    }

    private val stride = PackMerge.PACK_ID_STRIDE

    @Test
    fun `two alike places in one pack both stay once a second pack is installed`() {
        // Two Starbucks about 100 m apart, both in the first pack.
        val ny = listOf(
            scored(50.0, result(1, "Starbucks", 40.7500, -73.9900)),
            scored(49.0, result(2, "Starbucks", 40.7509, -73.9900)),
        )
        val nj = listOf(scored(10.0, result(1, "Hoboken Terminal", 40.7353, -74.0275)))
        val merged = PackMerge.merge(listOf(ny, nj), limit = 10)
        assertEquals(listOf(1L, 2L, 1L + stride), merged.map { it.place.id })
    }

    @Test
    fun `places both packs hold come once each`() {
        val a = listOf(
            scored(50.0, result(1, "Starbucks", 40.7500, -73.9900)),
            scored(48.0, result(2, "Starbucks", 40.7509, -73.9900)),
        )
        // The second pack's copies of the same two, a hair off.
        val b = listOf(
            scored(49.0, result(7, "Starbucks", 40.75001, -73.99001)),
            scored(47.0, result(8, "Starbucks", 40.75091, -73.99001)),
        )
        assertEquals(listOf(1L, 2L), PackMerge.merge(listOf(a, b), limit = 10).map { it.place.id })
        // A third Starbucks only the second pack holds, next to the first: one copy can't stand for two places.
        val c = b + scored(46.0, result(9, "Starbucks", 40.7503, -73.9900))
        assertEquals(listOf(1L, 2L, 9L + stride), PackMerge.merge(listOf(a, c), limit = 10).map { it.place.id })
    }

    @Test
    fun `a place both packs hold comes once`() {
        val a = listOf(scored(60.0, result(1, "Milford", 41.3223, -74.8024, PlaceKind.LOCALITY)))
        val b = listOf(scored(58.0, result(5, "Milford", 41.3226, -74.8020, PlaceKind.LOCALITY)))
        assertEquals(listOf(1L), PackMerge.merge(listOf(a, b), limit = 10).map { it.place.id })
        val c = listOf(scored(55.0, result(3, "Milford", 41.2223, -73.0565, PlaceKind.LOCALITY)))
        assertEquals(2, PackMerge.merge(listOf(a, b, c), limit = 10).size)
    }

    @Test
    fun `nearby results dedupe the same way`() {
        val near = listOf(
            PackMerge.moved(result(1, "Shell", 40.7500, -73.9900), 0),
            PackMerge.moved(result(4, "Shell", 40.75002, -73.99002), 1),
            PackMerge.moved(result(2, "Shell", 40.7508, -73.9900), 0),
        )
        assertEquals(listOf(1L, 2L), PackMerge.distinctPlaces(near).map { it.place.id })
    }

    @Test
    fun `a landmark both packs hold comes once, with the first pack's id`() {
        val a = listOf(mark(10, Landmark.ALPR_CAMERA, 0.0, 0.0), mark(11, Landmark.TRAFFIC_SIGNALS, 0.0, 300.0))
        val b = listOf(
            mark(20, Landmark.ALPR_CAMERA, 0.5, 0.5), // the same camera, a rounding off
            mark(21, Landmark.ALPR_CAMERA, 0.0, 300.0), // not the light, though in the same place
            mark(22, Landmark.ALPR_CAMERA, 0.0, 50.0), // its own camera
        )
        val merged = PackMerge.landmarks(listOf(a, b))
        assertEquals(listOf(10L, 11L, 21L + stride, 22L + stride), merged.map { it.id })
    }

    @Test
    fun `two landmarks one pack holds a few metres apart both stay`() {
        // Two cameras on one pole, facing each way, in the first pack; the second pack holds the same pair.
        val a = listOf(mark(1, Landmark.ALPR_CAMERA, 0.0, 0.0), mark(2, Landmark.ALPR_CAMERA, 2.0, 0.0))
        val b = listOf(mark(5, Landmark.ALPR_CAMERA, 2.0, 0.0), mark(6, Landmark.ALPR_CAMERA, 0.0, 0.0))
        assertEquals(listOf(1L, 2L), PackMerge.landmarks(listOf(a)).map { it.id })
        assertEquals(listOf(1L, 2L), PackMerge.landmarks(listOf(a, b)).map { it.id })
        assertEquals(listOf(1L, 2L), PackMerge.landmarks(listOf(a, emptyList(), b)).map { it.id })
        // Another category in the same places is another landmark.
        val speed = b.map { it.copy(category = Landmark.SPEED_CAMERA) }
        assertEquals(listOf(1L, 2L, 5L + stride, 6L + stride), PackMerge.landmarks(listOf(a, speed)).map { it.id })
    }
}
