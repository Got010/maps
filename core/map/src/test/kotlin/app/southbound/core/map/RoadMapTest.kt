package app.southbound.core.map

import app.southbound.core.network.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadMapTest {

    @Test
    fun `the keys are the pipeline's sixteen categories, in its order`() {
        assertEquals(
            listOf(
                "camp", "rv", "rest", "dump", "water", "toilet", "shower", "fuel", "truck", "laundry", "library",
                "pharmacy", "hospital", "clinic", "grocery", "park",
            ),
            RoadColors.KEYS,
        )
        assertEquals(16, RoadColors.KEYS.toSet().size)
    }

    @Test
    fun `every category has its own opaque colour, apart from the cluster slate and the Wi-Fi tiers`() {
        val hexes = RoadColors.KEYS.map { RoadColors.hex(it) }
        assertEquals(16, hexes.toSet().size)
        for (key in RoadColors.KEYS) {
            assertTrue(key, RoadColors.hex(key).matches(Regex("#[0-9A-F]{6}")))
            assertEquals(0xFF, (RoadColors.argb(key) ushr 24) and 0xFF)
            assertNotEquals(RoadColors.CLUSTER, RoadColors.hex(key))
            for (tier in 1..4) assertNotEquals(WifiColors.hex(tier), RoadColors.hex(key))
        }
        assertEquals(0xFFE65100.toInt(), RoadColors.argb("fuel"))
        // A code this app doesn't know falls back to the slate, never throws.
        assertEquals(RoadColors.CLUSTER, RoadColors.hex("ev_charging"))
        assertEquals(0xFF37474F.toInt(), RoadColors.clusterArgb)
    }

    @Test
    fun `every category has a glyph inside the grid and with something white in it`() {
        assertEquals(RoadColors.KEYS.toSet(), RoadGlyphs.keys)
        for (key in RoadColors.KEYS) {
            val shapes = RoadGlyphs.of(key)
            assertTrue(key, shapes.any { !it.hole })
            for (shape in shapes) {
                val points = when (shape) {
                    is RoadGlyphs.Circle -> listOf(shape.cx - shape.r to shape.cy - shape.r, shape.cx + shape.r to shape.cy + shape.r)
                    is RoadGlyphs.Rect -> listOf(shape.left to shape.top, shape.right to shape.bottom)
                    is RoadGlyphs.Polygon -> shape.xy.chunked(2).map { it[0] to it[1] }.also { assertTrue(key, shape.xy.size >= 6 && shape.xy.size % 2 == 0) }
                    is RoadGlyphs.Line -> shape.xy.chunked(2).map { it[0] to it[1] }.also { assertTrue(key, shape.xy.size >= 4 && shape.xy.size % 2 == 0) }
                }
                // Lines are as thick as they are, round at the ends: the grid has to hold the stroke too.
                val pad = (shape as? RoadGlyphs.Line)?.width?.div(2) ?: 0f
                for ((x, y) in points) {
                    assertTrue("$key $shape", x - pad >= 1f && x + pad <= RoadGlyphs.GRID - 1f && y - pad >= 1f && y + pad <= RoadGlyphs.GRID - 1f)
                }
            }
        }
        // An unknown code is a dot, not nothing.
        assertEquals(1, RoadGlyphs.of("ev_charging").size)
    }

    @Test
    fun `road pins name their badge and draw the rarer kinds on top`() {
        val json = OverlayGeoJson.roadPins(
            listOf(
                RoadPin(11, MapPoint(39.5, -95.5), "fuel"),
                RoadPin(12, MapPoint(39.51, -95.51), "park"),
                RoadPin(13, MapPoint(39.52, -95.52), "camp"),
            ),
        )
        // The last in the table first, so a campsite draws over a pump under it.
        assertEquals(
            """{"type":"FeatureCollection","features":[""" +
                """{"type":"Feature","properties":{"id":12,"category":"park","icon":"southbound-road-park"},"geometry":{"type":"Point","coordinates":[-95.510000,39.510000]}},""" +
                """{"type":"Feature","properties":{"id":11,"category":"fuel","icon":"southbound-road-fuel"},"geometry":{"type":"Point","coordinates":[-95.500000,39.500000]}},""" +
                """{"type":"Feature","properties":{"id":13,"category":"camp","icon":"southbound-road-camp"},"geometry":{"type":"Point","coordinates":[-95.520000,39.520000]}}]}""",
            json,
        )
        assertEquals("""{"type":"FeatureCollection","features":[]}""", OverlayGeoJson.roadPins(emptyList()))
    }

    @Test
    fun `road pins leave out what cannot be drawn`() {
        val good = MapPoint(39.5, -95.5)
        val json = OverlayGeoJson.roadPins(
            listOf(
                RoadPin(1, MapPoint(Double.NaN, -95.5), "fuel"),
                RoadPin(2, good, "ev_charging"),
                RoadPin(3, good, "\"},{\"x\":\""),
                RoadPin(4, good, "water"),
                RoadPin(5, MapPoint(39.5, Double.POSITIVE_INFINITY), "water"),
            ),
        )
        val features = (MiniJson.parse(json) as Map<*, *>)["features"] as List<*>
        assertEquals(listOf(4L), features.map { ((it as Map<*, *>)["properties"] as Map<*, *>)["id"] })
    }

    @Test
    fun `road badge names are one per category`() {
        assertEquals(16, RoadColors.KEYS.map { RoadPin.imageName(it) }.toSet().size)
        assertEquals("southbound-road-truck", RoadPin(1, MapPoint(0.0, 0.0), "truck").imageName)
        // Not the Wi-Fi badges' names, which share one style.
        assertTrue(RoadColors.KEYS.none { RoadPin.imageName(it).startsWith("southbound-wifi") })
    }

    @Test
    fun `road clusters carry the count and its label`() {
        val json = OverlayGeoJson.roadClusters(
            listOf(
                RoadClusterPin("9/120/194", MapPoint(39.49, -95.51), count = 5),
                RoadClusterPin("9/121/194", MapPoint(39.4, -95.0), count = 12_345),
                RoadClusterPin("9/1/1", MapPoint(39.4, -95.0), count = 0),
                RoadClusterPin("9/2/2", MapPoint(Double.NaN, -95.0), count = 3),
            ),
        )
        assertEquals(
            """{"type":"FeatureCollection","features":[""" +
                """{"type":"Feature","properties":{"id":"9/120/194","count":5,"label":"5"},"geometry":{"type":"Point","coordinates":[-95.510000,39.490000]}},""" +
                """{"type":"Feature","properties":{"id":"9/121/194","count":12345,"label":"12k"},"geometry":{"type":"Point","coordinates":[-95.000000,39.400000]}}]}""",
            json,
        )
    }

    @Test
    fun `the road overlay is empty by default and rides in the map overlays`() {
        assertEquals(RoadOverlay.NONE, MapOverlays().road)
        assertTrue(RoadOverlay.NONE.pins.isEmpty() && RoadOverlay.NONE.clusters.isEmpty())
        assertEquals(10.0, RoadPin.MIN_ZOOM, 0.0)
        val overlays = MapOverlays(road = RoadOverlay(pins = listOf(RoadPin(1, MapPoint(1.0, 2.0), "rv"))))
        assertEquals(1, overlays.road.pins.size)
    }
}
