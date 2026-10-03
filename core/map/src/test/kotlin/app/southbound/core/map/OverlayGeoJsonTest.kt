package app.southbound.core.map

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayGeoJsonTest {

    @Test
    fun routesCarryRankRoleAndColour() {
        val a = listOf(MapPoint(40.7, -74.0), MapPoint(40.71, -74.01))
        val b = listOf(MapPoint(40.7, -74.0), MapPoint(40.72, -73.99))
        val c = listOf(MapPoint(40.7, -74.0), MapPoint(40.73, -73.98))
        // The second route is chosen; the fastest and the third become threads.
        val json = OverlayGeoJson.routes(
            listOf(
                MapLine(a, MapLine.Role.THREAD, rank = 0),
                MapLine(b, MapLine.Role.LEADER, rank = 1),
                MapLine(c, MapLine.Role.THREAD, rank = 2),
            ),
            darkTheme = true,
        )
        // Leader first; threads after it, the faster one last so it draws on top.
        assertEquals(
            """{"type":"FeatureCollection","features":[""" +
                """{"type":"Feature","properties":{"rank":1,"role":"leader","color":"#F472B6","casing":"#0E1013"},"geometry":{"type":"LineString","coordinates":[[-74.000000,40.700000],[-73.990000,40.720000]]}},""" +
                """{"type":"Feature","properties":{"rank":2,"role":"thread","color":"#A78BFA","casing":"#0E1013"},"geometry":{"type":"LineString","coordinates":[[-74.000000,40.700000],[-73.980000,40.730000]]}},""" +
                """{"type":"Feature","properties":{"rank":0,"role":"thread","color":"#22D3EE","casing":"#0E1013"},"geometry":{"type":"LineString","coordinates":[[-74.000000,40.700000],[-74.010000,40.710000]]}}]}""",
            json,
        )
    }

    @Test
    fun lightMapUsesDeeperColoursAndWalksStayGrey() {
        val ride = MapLine(listOf(MapPoint(40.7, -74.0), MapPoint(40.71, -74.01)), MapLine.Role.LEADER)
        val walk = MapLine(listOf(MapPoint(40.71, -74.01), MapPoint(40.72, -74.02)), MapLine.Role.WALK)
        assertEquals(
            """{"type":"FeatureCollection","features":[""" +
                """{"type":"Feature","properties":{"rank":0,"role":"leader","color":"#0891B2","casing":"#FFFFFF"},"geometry":{"type":"LineString","coordinates":[[-74.000000,40.700000],[-74.010000,40.710000]]}},""" +
                """{"type":"Feature","properties":{"rank":0,"role":"walk","color":"#9AA0A6","casing":"#FFFFFF"},"geometry":{"type":"LineString","coordinates":[[-74.010000,40.710000],[-74.020000,40.720000]]}}]}""",
            OverlayGeoJson.routes(listOf(walk, ride), darkTheme = false),
        )
    }

    @Test
    fun routeColoursRepeatPastTheThirdRoute() {
        assertEquals("#22D3EE", RouteColors.hex(0, darkTheme = true))
        assertEquals("#DB2777", RouteColors.hex(1, darkTheme = false))
        assertEquals(RouteColors.hex(0, darkTheme = true), RouteColors.hex(3, darkTheme = true))
        assertEquals(0xFF7C3AED.toInt(), RouteColors.argb(2, darkTheme = false))
    }

    @Test
    fun linesNeedTwoPoints() {
        val json = OverlayGeoJson.routes(listOf(MapLine(listOf(MapPoint(1.0, 2.0)), MapLine.Role.LEADER)), darkTheme = true)
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
}
