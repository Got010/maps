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

    @Test
    fun trafficSpeedsAndIncidents() {
        val speeds = OverlayGeoJson.speeds(listOf(SpeedLine(listOf(MapPoint(40.74, -74.009), MapPoint(40.741, -74.008)), 52.19)))
        assertEquals(
            """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"speed":52.2},""" +
                """"geometry":{"type":"LineString","coordinates":[[-74.009000,40.740000],[-74.008000,40.741000]]}}]}""",
            speeds,
        )
        val incidents = OverlayGeoJson.incidents(
            listOf(
                IncidentPin("a\"b", MapPoint(41.45, -74.05), "CLOSURE", closure = true),
                IncidentPin("c", MapPoint(40.8, -73.97), "ROADWORK", closure = false),
            ),
        )
        assertEquals(
            """{"type":"FeatureCollection","features":[""" +
                """{"type":"Feature","properties":{"id":"c","kind":"ROADWORK","closure":false},"geometry":{"type":"Point","coordinates":[-73.970000,40.800000]}},""" +
                """{"type":"Feature","properties":{"id":"a\"b","kind":"CLOSURE","closure":true},"geometry":{"type":"Point","coordinates":[-74.050000,41.450000]}}]}""",
            incidents,
        )
    }

    @Test
    fun camerasCarryTheirIdAndMaker() {
        val json = OverlayGeoJson.cameras(
            listOf(
                CameraPin("alpr:7", MapPoint(40.744, -73.99), "Flock Safety"),
                CameraPin("alpr:8", MapPoint(40.7445, -73.99)),
            ),
        )
        assertEquals(
            """{"type":"FeatureCollection","features":[""" +
                """{"type":"Feature","properties":{"id":"alpr:7","name":"Flock Safety"},"geometry":{"type":"Point","coordinates":[-73.990000,40.744000]}},""" +
                """{"type":"Feature","properties":{"id":"alpr:8"},"geometry":{"type":"Point","coordinates":[-73.990000,40.744500]}}]}""",
            json,
        )
        assertEquals("""{"type":"FeatureCollection","features":[]}""", OverlayGeoJson.cameras(emptyList()))
    }
}
