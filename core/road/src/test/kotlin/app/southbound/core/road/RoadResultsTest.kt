package app.southbound.core.road

import app.southbound.core.search.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadResultsTest {

    // Kansas, far from anything of anyone's.
    private val center = LatLon(39.5, -95.5)

    private fun spot(
        id: Long,
        category: RoadCategory = RoadCategory.FUEL,
        lat: Double = 39.5,
        lon: Double = -95.5,
        name: String? = "Place $id",
        brand: String? = null,
        extra: RoadExtra = RoadExtra.EMPTY,
    ) = RoadSpot(id, lat, lon, category, name, brand, extra)

    @Test
    fun `nearest first, inside the circle, at most the limit`() {
        val spots = listOf(
            spot(1, lat = 39.52),
            spot(2, lat = 39.505),
            spot(3, lat = 39.9),
            spot(4, lat = 39.51),
        )
        val got = RoadResults.select(spots, center, 10_000.0, 10)
        assertEquals(listOf(2L, 4L, 1L), got.map { it.spot.id })
        assertTrue(got.zipWithNext().all { (a, b) -> a.distanceMeters <= b.distanceMeters })
        assertEquals(listOf(2L), RoadResults.select(spots, center, 10_000.0, 1).map { it.spot.id })
        assertEquals(emptyList<RoadResult>(), RoadResults.select(spots, center, 10_000.0, 0))
        assertEquals(emptyList<RoadResult>(), RoadResults.select(spots, center, 10.0, 10))
    }

    @Test
    fun `ties go to the table's order, then the id, so the list never shuffles`() {
        val spots = listOf(
            spot(9, RoadCategory.PARK, name = "A"),
            spot(5, RoadCategory.CAMP, name = "B"),
            spot(4, RoadCategory.CAMP, name = "C"),
        )
        assertEquals(listOf(4L, 5L, 9L), RoadResults.select(spots, center, 1000.0, 10).map { it.spot.id })
    }

    @Test
    fun `a place filed under two categories is listed once, as the truck stop, with both rows' facts`() {
        val pumps = spot(10, RoadCategory.FUEL, name = "Mile 40", brand = "Pilot", extra = RoadExtra(diesel = true))
        val truck = spot(11, RoadCategory.TRUCK, name = "Mile 40", brand = "Pilot", extra = RoadExtra(shower = RoadFlag.YES, hours = "24/7"))
        val other = spot(12, RoadCategory.FUEL, name = "Corner Gas", lat = 39.501)
        val got = RoadPlaces.distinct(listOf(pumps, other, truck))
        assertEquals(listOf(11L, 12L), got.map { it.id })
        val merged = got.first()
        assertEquals(RoadCategory.TRUCK, merged.category)
        assertEquals(setOf(RoadCategory.FUEL), merged.alsoCategories)
        assertTrue(merged.extra.diesel)
        assertEquals(RoadFlag.YES, merged.extra.shower)
        assertEquals("24/7", merged.extra.hours)
        // The order of the list is the first row's: the pumps came first.
        assertEquals(listOf(11L, 12L), RoadPlaces.distinct(listOf(truck, pumps, other)).map { it.id })
    }

    @Test
    fun `a services area with hgv is one place, the truck stop`() {
        val rest = spot(1, RoadCategory.REST, name = "Plains Services", extra = RoadExtra(toilets = RoadFlag.YES))
        val truck = spot(2, RoadCategory.TRUCK, name = "Plains Services")
        val folded = RoadPlaces.fold(listOf(rest, truck))
        assertEquals(RoadCategory.TRUCK, folded.category)
        assertEquals(setOf(RoadCategory.REST), folded.alsoCategories)
        assertEquals(RoadFlag.YES, folded.extra.toilets)
    }

    @Test
    fun `different places at one point are never folded`() {
        val a = spot(1, RoadCategory.WATER, name = "Spigot")
        val b = spot(2, RoadCategory.TOILET, name = "Restroom")
        val c = spot(3, RoadCategory.TOILET, name = null)
        assertEquals(listOf(1L, 2L, 3L), RoadPlaces.distinct(listOf(a, b, c)).map { it.id })
        // Same name, different place.
        assertEquals(2, RoadPlaces.distinct(listOf(spot(1, name = "X"), spot(2, name = "X", lat = 39.6))).size)
        // Same position and name, different brand.
        assertEquals(2, RoadPlaces.distinct(listOf(spot(1, name = "X", brand = "A"), spot(2, name = "X", brand = "B"))).size)
    }

    @Test
    fun `two campsite rows keep the lower category and the lower id when nothing is a truck stop`() {
        val camp = spot(7, RoadCategory.CAMP, name = "Lakeside")
        val park = spot(3, RoadCategory.PARK, name = "Lakeside")
        val folded = RoadPlaces.fold(listOf(park, camp))
        assertEquals(RoadCategory.CAMP, folded.category)
        assertEquals(7L, folded.id)
        assertEquals(setOf(RoadCategory.PARK), folded.alsoCategories)
    }

    @Test
    fun `a list of one or none comes back as it is`() {
        val one = listOf(spot(1))
        assertSame(one, RoadPlaces.distinct(one))
        assertEquals(emptyList<RoadSpot>(), RoadPlaces.distinct(emptyList()))
    }

    @Test
    fun `distinct runs before the limit so folded rows do not eat it`() {
        val spots = (1L..6L).flatMap { i ->
            listOf(spot(i * 2, RoadCategory.FUEL, lat = 39.5 + i * 0.001, name = "S$i"), spot(i * 2 + 1, RoadCategory.TRUCK, lat = 39.5 + i * 0.001, name = "S$i"))
        }
        val got = RoadResults.select(spots, center, 50_000.0, 4)
        assertEquals(4, got.size)
        assertEquals(4, got.map { it.spot.title }.toSet().size)
    }

    @Test
    fun `candidate rows leave room for the corners and the folds`() {
        assertEquals(120, RoadResults.candidateRows(40))
    }
}
