package app.southbound.core.map

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayGeoJsonTest {

    @Test
    fun selectedRouteIsDrawnLast() {
        val selected = MapLine(listOf(MapPoint(40.7, -74.0), MapPoint(40.71, -74.01)), selected = true)
        val alternative = MapLine(listOf(MapPoint(40.7, -74.0), MapPoint(40.72, -73.99)), selected = false)
        val json = OverlayGeoJson.routes(listOf(selected, alternative))
        assertEquals(
            """{"type":"FeatureCollection","features":[""" +
                """{"type":"Feature","properties":{"selected":false},"geometry":{"type":"LineString","coordinates":[[-74.000000,40.700000],[-73.990000,40.720000]]}},""" +
                """{"type":"Feature","properties":{"selected":true},"geometry":{"type":"LineString","coordinates":[[-74.000000,40.700000],[-74.010000,40.710000]]}}]}""",
            json,
        )
    }

    @Test
    fun linesNeedTwoPoints() {
        val json = OverlayGeoJson.routes(listOf(MapLine(listOf(MapPoint(1.0, 2.0)), selected = true)))
        assertEquals("""{"type":"FeatureCollection","features":[]}""", json)
    }

    @Test
    fun destinationIsHiddenWhenItIsThePin() {
        val p = MapPoint(40.75, -73.98)
        assertEquals(
            """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"role":"pin"},"geometry":{"type":"Point","coordinates":[-73.980000,40.750000]}}]}""",
            OverlayGeoJson.pins(p, p),
        )
        assertEquals("""{"type":"FeatureCollection","features":[]}""", OverlayGeoJson.pins(null, null))
    }
}
