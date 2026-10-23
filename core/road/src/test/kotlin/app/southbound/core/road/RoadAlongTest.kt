package app.southbound.core.road

import app.southbound.core.search.LatLon
import app.southbound.core.search.NearbyBox
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class RoadAlongTest {

    // A synthetic route due east along latitude 39.0 from longitude -97.0, a point every 0.01 degree (about 865 m).
    private val lat0 = 39.0
    private val lon0 = -97.0
    private val metersPerLonDegree = 111_195.0 * cos(Math.toRadians(39.0))
    private val east = Route(500)

    private fun Route(points: Int) = RoadAlong.Route((0 until points).map { LatLon(lat0, lon0 + it * 0.01) })

    private var nextId = 1L
    private fun spot(
        lat: Double,
        lon: Double,
        category: RoadCategory = RoadCategory.FUEL,
        name: String? = "Spot ${nextId}",
        brand: String? = null,
        extra: RoadExtra = RoadExtra.EMPTY,
    ) = RoadSpot(nextId++, lat, lon, category, name, brand, extra)

    /** A spot [km] north of the route at [lonDegrees] east of the start. */
    private fun beside(lonDegrees: Double, km: Double, category: RoadCategory = RoadCategory.FUEL) =
        spot(lat0 + km / 111.195, lon0 + lonDegrees, category)

    /** The pack, as a list of spots with a box read and a count of reads. */
    private class FakePack(val spots: List<RoadSpot>) {
        var reads = 0
        val boxes = ArrayList<NearbyBox>()
        fun inBox(box: NearbyBox): List<RoadSpot> {
            reads++
            boxes += box
            return spots.filter { box.contains(it.lat, it.lon) }
        }
    }

    private fun search(route: RoadAlong.Route, pack: FakePack, corridorKm: Double = 5.0, limit: Int = 100, aheadMeters: Double = 0.0) =
        RoadAlong.search(route, corridorKm * 1000, aheadMeters, limit, pack::inBox)

    @Test
    fun `the route has a length and pieces that join up`() {
        assertEquals(499 * 0.01 * metersPerLonDegree, east.length, 5.0)
        val chunks = east.chunks(5_000.0)
        assertTrue(chunks.size in 25..35)
        assertEquals(0, chunks.first().first)
        assertEquals(499, chunks.last().last)
        for ((a, b) in chunks.zipWithNext()) assertEquals(a.last, b.first)
        assertEquals(0.0, chunks.first().startMeters, 0.0)
        assertEquals(east.length, chunks.last().endMeters, 1e-6)
        // Each box holds its piece's points with the corridor to spare on every side.
        for (c in chunks) {
            for (i in c.first..c.last) assertTrue(c.box.contains(east.points[i].lat, east.points[i].lon))
            assertTrue(c.box.north - lat0 >= 5_000.0 / 111_195.0 - 1e-9)
            assertTrue(lat0 - c.box.south >= 5_000.0 / 111_195.0 - 1e-9)
            assertTrue(c.box.west <= east.points[c.first].lon - 5_000.0 / metersPerLonDegree * 0.99)
        }
    }

    @Test
    fun `a spot beside the route is located along it and off it`() {
        val chunk = east.chunks(5_000.0).first()
        val at = east.locate(lat0 + 1.0 / 111.195, lon0 + 0.05, chunk)!!
        assertEquals(0.05 * metersPerLonDegree, at.along, 5.0)
        assertEquals(1_000.0, at.offset, 5.0)
        // Past the ends, the nearest end.
        val before = east.locate(lat0, lon0 - 0.02, chunk)!!
        assertEquals(0.0, before.along, 1e-6)
        assertEquals(0.02 * metersPerLonDegree, before.offset, 5.0)
    }

    @Test
    fun `spots in the corridor come in the order the driver meets them`() {
        val spots = listOf(beside(3.0, 1.0), beside(1.0, 0.5), beside(4.5, 4.0), beside(2.0, 2.0))
        val got = search(east, FakePack(spots))
        assertEquals(listOf(spots[1], spots[3], spots[0], spots[2]).map { it.id }, got.map { it.spot.id })
        assertTrue(got.zipWithNext().all { (a, b) -> a.alongMeters <= b.alongMeters })
        assertEquals(1.0 * metersPerLonDegree, got[0].alongMeters, 10.0)
        assertEquals(500.0, got[0].offsetMeters, 5.0)
        // For a route not yet started, ahead is along.
        assertEquals(got[0].alongMeters, got[0].aheadMeters, 0.0)
    }

    @Test
    fun `a spot outside the corridor is left out, and one just inside is kept`() {
        val inside = beside(2.0, 4.9)
        val outside = beside(2.0, 5.2)
        val got = search(east, FakePack(listOf(outside, inside)))
        assertEquals(listOf(inside.id), got.map { it.spot.id })
    }

    @Test
    fun `the corridor is a distance to the line, not to the box of its piece`() {
        // A diagonal route: the box of a piece is a square round it, so its far corner is in the box and far from the line.
        val diagonal = RoadAlong.Route((0 until 200).map { LatLon(lat0 + it * 0.005, lon0 + it * 0.005) })
        val farInBox = spot(lat0 + 0.10, lon0 + 0.01)
        assertTrue(diagonal.chunks(3_000.0).first().box.contains(farInBox.lat, farInBox.lon))
        val near = spot(lat0 + 0.5, lon0 + 0.5 + 0.02)
        val pack = FakePack(listOf(farInBox, near))
        val got = search(diagonal, pack, corridorKm = 3.0)
        assertEquals(listOf(near.id), got.map { it.spot.id })
        // And the far one really was handed over by the box read, then measured and dropped.
        assertTrue(pack.boxes.first().contains(farInBox.lat, farInBox.lon))
    }

    @Test
    fun `the longitude padding holds at a high latitude`() {
        // At 60 degrees a degree of longitude is half as long: a spot 4.9 km off a north-south route is still inside.
        val north = RoadAlong.Route((0 until 100).map { LatLon(60.0 + it * 0.01, -100.0) })
        val dLon = 4.9 / (111.195 * cos(Math.toRadians(60.5)))
        val beside = spot(60.5, -100.0 + dLon)
        assertEquals(listOf(beside.id), search(north, FakePack(listOf(beside))).map { it.spot.id })
    }

    @Test
    fun `spots already passed are left out and the distance ahead counts from the driver`() {
        val behind = beside(1.0, 0.5)
        val ahead = beside(3.0, 0.5)
        val far = beside(4.0, 0.5)
        val driver = 2.0 * metersPerLonDegree
        val got = search(east, FakePack(listOf(behind, ahead, far)), aheadMeters = driver)
        assertEquals(listOf(ahead.id, far.id), got.map { it.spot.id })
        assertEquals(1.0 * metersPerLonDegree, got[0].aheadMeters, 10.0)
        assertEquals(3.0 * metersPerLonDegree, got[0].alongMeters, 10.0)
        // Only the pieces from the driver on are read.
        val pack = FakePack(listOf(behind, ahead))
        search(east, pack, aheadMeters = driver)
        assertTrue(pack.boxes.none { it.east < lon0 + 1.9 })
    }

    @Test
    fun `at most the limit, the first ones along the route`() {
        val spots = (1..40).map { beside(it * 0.1, 0.2) }
        val got = search(east, FakePack(spots), limit = 5)
        assertEquals(spots.take(5).map { it.id }, got.map { it.spot.id })
        assertEquals(emptyList<RoadAlongResult>(), search(east, FakePack(spots), limit = 0))
    }

    @Test
    fun `the search stops reading once the first ones are settled`() {
        val spots = (1..400).map { beside(it * 0.012, 0.2) }
        val few = FakePack(spots)
        search(east, few, limit = 3)
        val every = FakePack(spots)
        search(east, every, limit = 100_000)
        assertEquals(east.chunks(5_000.0).size, every.reads)
        assertTrue("${few.reads} reads of ${every.reads}", few.reads <= 3)
    }

    @Test
    fun `a place filed twice is one result, the truck stop with both rows' facts`() {
        val pumps = spot(lat0 + 0.002, lon0 + 1.0, RoadCategory.FUEL, "Mile 100", "Pilot", RoadExtra(diesel = true))
        val truck = spot(lat0 + 0.002, lon0 + 1.0, RoadCategory.TRUCK, "Mile 100", "Pilot", RoadExtra(shower = RoadFlag.YES))
        val got = search(east, FakePack(listOf(pumps, truck)))
        assertEquals(1, got.size)
        assertEquals(RoadCategory.TRUCK, got.single().spot.category)
        assertTrue(got.single().spot.extra.diesel)
        assertEquals(RoadFlag.YES, got.single().spot.extra.shower)
    }

    @Test
    fun `a spot the route passes twice is listed at its first pass`() {
        // Out east and back west along a parallel line 2 km north: the spot sits between the two legs.
        val there = (0..100).map { LatLon(lat0, lon0 + it * 0.01) }
        val back = (100 downTo 0).map { LatLon(lat0 + 2.0 / 111.195, lon0 + it * 0.01) }
        val loop = RoadAlong.Route(there + back)
        val s = spot(lat0 + 1.0 / 111.195, lon0 + 0.5)
        val got = search(loop, FakePack(listOf(s)), corridorKm = 3.0)
        assertEquals(1, got.size)
        assertEquals(0.5 * metersPerLonDegree, got.single().alongMeters, 20.0)
    }

    @Test
    fun `a route with no length lists nothing`() {
        val pack = FakePack(listOf(spot(lat0, lon0)))
        assertEquals(emptyList<RoadAlongResult>(), search(RoadAlong.Route(emptyList()), pack))
        assertEquals(emptyList<RoadAlongResult>(), search(RoadAlong.Route(listOf(LatLon(lat0, lon0))), pack))
        assertEquals(emptyList<RoadAlongResult>(), search(RoadAlong.Route(listOf(LatLon(lat0, lon0), LatLon(lat0, lon0))), pack))
        assertEquals(0, pack.reads)
        assertEquals(emptyList<RoadAlongResult>(), search(east, FakePack(listOf(beside(1.0, 0.1))), corridorKm = 0.0))
    }

    @Test
    fun `points that are not numbers or repeat are dropped`() {
        val route = RoadAlong.Route(
            listOf(LatLon(lat0, lon0), LatLon(lat0, lon0), LatLon(Double.NaN, lon0), LatLon(lat0, lon0 + 0.1), LatLon(lat0, Double.POSITIVE_INFINITY), LatLon(lat0, lon0 + 0.2)),
        )
        assertEquals(3, route.points.size)
        assertEquals(0.2 * metersPerLonDegree, route.length, 5.0)
    }

    @Test
    fun `a long route is read in small boxes`() {
        // 20,000 points, about 170 km a thousand: a 1,700 km route.
        val long = RoadAlong.Route((0 until 20_000).map { LatLon(lat0 + 0.0001 * it * 0.1, lon0 + it * 0.001) })
        val pack = FakePack(emptyList())
        search(long, pack, limit = 5)
        val chunks = long.chunks(5_000.0)
        assertTrue(chunks.size > 50)
        assertEquals(chunks.size, pack.reads)
        // No box is bigger than a piece and its corridor: about 15 km and 10 km round it.
        for (b in pack.boxes) {
            assertTrue((b.east - b.west) * metersPerLonDegree < RoadAlong.CHUNK_METERS + 40_000)
            assertTrue((b.north - b.south) * 111_195.0 < RoadAlong.CHUNK_METERS + 40_000)
        }
    }

    @Test
    fun `measuring a few thousand spots against a long route takes a moment, not minutes`() {
        // 30,000 points, about 1,000 km, and 6,000 spots a kilometre north of it.
        val long = RoadAlong.Route((0 until 30_000).map { LatLon(lat0 + 0.00003 * it, lon0 + 0.0004 * it) })
        val spots = (0 until 6_000).map { i -> spot(lat0 + 0.00003 * (i * 5) + 0.01, lon0 + 0.0004 * (i * 5)) }
        val pack = FakePack(spots)
        val started = System.nanoTime()
        val got = search(long, pack, limit = 100_000)
        val millis = (System.nanoTime() - started) / 1_000_000
        assertEquals(6_000, got.size)
        assertFalse("took $millis ms", millis > 20_000)
    }
}
