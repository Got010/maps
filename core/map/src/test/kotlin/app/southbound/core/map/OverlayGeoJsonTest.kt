package app.southbound.core.map

import app.southbound.core.network.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
                """{"type":"Feature","properties":{"rank":1,"role":"leader","color":"#3B82F6","casing":"#0E1013"},"geometry":{"type":"LineString","coordinates":[[-74.000000,40.700000],[-73.990000,40.720000]]}},""" +
                """{"type":"Feature","properties":{"rank":2,"role":"thread","color":"#3347E0","casing":"#0E1013"},"geometry":{"type":"LineString","coordinates":[[-74.000000,40.700000],[-73.980000,40.730000]]}},""" +
                """{"type":"Feature","properties":{"rank":0,"role":"thread","color":"#7DD3FC","casing":"#0E1013"},"geometry":{"type":"LineString","coordinates":[[-74.000000,40.700000],[-74.010000,40.710000]]}}]}""",
            json,
        )
    }

    @Test
    fun lightMapUsesDeeperColoursAndWalksStayGrey() {
        val ride = MapLine(listOf(MapPoint(40.7, -74.0), MapPoint(40.71, -74.01)), MapLine.Role.LEADER)
        val walk = MapLine(listOf(MapPoint(40.71, -74.01), MapPoint(40.72, -74.02)), MapLine.Role.WALK)
        assertEquals(
            """{"type":"FeatureCollection","features":[""" +
                """{"type":"Feature","properties":{"rank":0,"role":"leader","color":"#0284C7","casing":"#FFFFFF"},"geometry":{"type":"LineString","coordinates":[[-74.000000,40.700000],[-74.010000,40.710000]]}},""" +
                """{"type":"Feature","properties":{"rank":0,"role":"walk","color":"#9AA0A6","casing":"#FFFFFF"},"geometry":{"type":"LineString","coordinates":[[-74.010000,40.710000],[-74.020000,40.720000]]}}]}""",
            OverlayGeoJson.routes(listOf(walk, ride), darkTheme = false),
        )
    }

    @Test
    fun routeColoursRepeatPastTheThirdRoute() {
        assertEquals("#7DD3FC", RouteColors.hex(0, darkTheme = true))
        assertEquals("#2563EB", RouteColors.hex(1, darkTheme = false))
        assertEquals(RouteColors.hex(0, darkTheme = true), RouteColors.hex(3, darkTheme = true))
        assertEquals(0xFF1E3A8A.toInt(), RouteColors.argb(2, darkTheme = false))
    }

    @Test
    fun routeColoursAreBluesThatDarkenWithRank() {
        for (dark in listOf(true, false)) {
            val colours = (0..2).map { RouteColors.argb(it, dark) }
            assertEquals(3, colours.toSet().size)
            for (c in colours) {
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // Blue dominates: no pink, violet or green sneaks back in.
                assertTrue("blue should lead in $c", b > r && b > g)
            }
            // The fastest route is the lightest, and each next one is darker.
            val lightness = colours.map { c ->
                0.2126 * ((c shr 16) and 0xFF) + 0.7152 * ((c shr 8) and 0xFF) + 0.0722 * (c and 0xFF)
            }
            assertTrue(lightness[0] > lightness[1] + 10)
            assertTrue(lightness[1] > lightness[2] + 10)
        }
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
                IncidentPin("a\"b", MapPoint(43.55, -73.60), "CLOSURE", closure = true),
                IncidentPin("c", MapPoint(40.8, -73.97), "ROADWORK", closure = false),
            ),
        )
        assertEquals(
            """{"type":"FeatureCollection","features":[""" +
                """{"type":"Feature","properties":{"id":"c","kind":"ROADWORK","closure":false},"geometry":{"type":"Point","coordinates":[-73.970000,40.800000]}},""" +
                """{"type":"Feature","properties":{"id":"a\"b","kind":"CLOSURE","closure":true},"geometry":{"type":"Point","coordinates":[-73.600000,43.550000]}}]}""",
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

    @Test
    fun camerasCarryTheWayTheyFaceWhenMapped() {
        val json = OverlayGeoJson.cameras(
            listOf(
                CameraPin("alpr:7", MapPoint(40.744, -73.99), direction = 90),
                CameraPin("alpr:8", MapPoint(40.7445, -73.99), direction = -45),
                CameraPin("alpr:9", MapPoint(40.745, -73.99), direction = 360),
            ),
        )
        assertTrue(json.contains(""""id":"alpr:7","direction":90}"""))
        // The wedge layer rotates by it, so it is always 0-359.
        assertTrue(json.contains(""""id":"alpr:8","direction":315}"""))
        assertTrue(json.contains(""""id":"alpr:9","direction":0}"""))
    }

    @Test
    fun trafficCamerasCarryIdKindAndOfflineWithLiveOnesOnTop() {
        val json = OverlayGeoJson.trafficCameras(
            listOf(
                TrafficCameraPin("NYSDOT_9022", MapPoint(43.53, -73.62)),
                TrafficCameraPin("1204", MapPoint(40.7835, -73.9431), offline = true),
            ),
        )
        assertEquals(
            """{"type":"FeatureCollection","features":[""" +
                """{"type":"Feature","properties":{"id":"1204","kind":"camera","offline":true},"geometry":{"type":"Point","coordinates":[-73.943100,40.783500]}},""" +
                """{"type":"Feature","properties":{"id":"NYSDOT_9022","kind":"camera","offline":false},"geometry":{"type":"Point","coordinates":[-73.620000,43.530000]}}]}""",
            json,
        )
        assertEquals("""{"type":"FeatureCollection","features":[]}""", OverlayGeoJson.trafficCameras(emptyList()))
    }

    @Test
    fun messageSignsCarryTheirIdAndKind() {
        val json = OverlayGeoJson.messageSigns(listOf(MessageSignPin("VMS \"87\"", MapPoint(43.5, -73.64))))
        assertEquals(
            """{"type":"FeatureCollection","features":[""" +
                """{"type":"Feature","properties":{"id":"VMS \"87\"","kind":"sign"},"geometry":{"type":"Point","coordinates":[-73.640000,43.500000]}}]}""",
            json,
        )
        assertEquals("""{"type":"FeatureCollection","features":[]}""", OverlayGeoJson.messageSigns(emptyList()))
    }

    @Test
    fun weatherAreasAreClosedPolygonsWorstOnTop() {
        val ring = listOf(MapPoint(42.0, -74.0), MapPoint(42.0, -73.0), MapPoint(43.0, -73.0))
        val json = OverlayGeoJson.weatherAreas(
            listOf(
                WeatherArea("warn", listOf(ring), "SEVERE"),
                WeatherArea("adv", listOf(ring, listOf(MapPoint(1.0, 1.0))), "MINOR"),
            ),
        )
        // The minor advisory first so the warning draws over it; a ring of fewer than 3 points is left out;
        // each ring is closed.
        val closed = """[[[-74.000000,42.000000],[-73.000000,42.000000],[-73.000000,43.000000],[-74.000000,42.000000]]]"""
        assertEquals(
            """{"type":"FeatureCollection","features":[""" +
                """{"type":"Feature","properties":{"id":"adv","severity":"MINOR"},"geometry":{"type":"Polygon","coordinates":$closed}},""" +
                """{"type":"Feature","properties":{"id":"warn","severity":"SEVERE"},"geometry":{"type":"Polygon","coordinates":$closed}}]}""",
            json,
        )
    }

    @Test
    fun weatherStationsNameTheirBadge() {
        val json = OverlayGeoJson.weatherStations(
            listOf(
                WeatherStationPin("KALB", MapPoint(42.75, -73.80), "RAIN", night = false, tempF = 57),
                WeatherStationPin("KART", MapPoint(43.99, -76.03), "CLEAR", night = true, tempF = null),
            ),
        )
        assertEquals(
            """{"type":"FeatureCollection","features":[""" +
                """{"type":"Feature","properties":{"id":"KALB","kind":"weather","icon":"southbound-wx-RAIN-d-57"},"geometry":{"type":"Point","coordinates":[-73.800000,42.750000]}},""" +
                """{"type":"Feature","properties":{"id":"KART","kind":"weather","icon":"southbound-wx-CLEAR-n-x"},"geometry":{"type":"Point","coordinates":[-76.030000,43.990000]}}]}""",
            json,
        )
    }

    @Test
    fun controlCharactersInNamesAreEscaped() {
        val name = "Acme\r\n\tCo\u0001"
        val json = OverlayGeoJson.cameras(listOf(CameraPin("alpr:9", MapPoint(40.744, -73.99), name)))
        assertEquals(
            """{"type":"FeatureCollection","features":[""" +
                """{"type":"Feature","properties":{"id":"alpr:9","name":"Acme\r\n\tCo\u0001"},"geometry":{"type":"Point","coordinates":[-73.990000,40.744000]}}]}""",
            json,
        )
        // It reads back, with the name as it was.
        val feature = ((MiniJson.parse(json) as Map<*, *>)["features"] as List<*>).single() as Map<*, *>
        assertEquals(name, (feature["properties"] as Map<*, *>)["name"])
    }

    @Test
    fun featuresWithoutFiniteCoordinatesAreLeftOut() {
        val good = MapPoint(40.7, -74.0)
        val bad = MapPoint(Double.NaN, -74.0)
        val empty = """{"type":"FeatureCollection","features":[]}"""
        assertEquals(empty, OverlayGeoJson.routes(listOf(MapLine(listOf(good, bad), MapLine.Role.LEADER)), darkTheme = true))
        assertEquals(empty, OverlayGeoJson.speeds(listOf(SpeedLine(listOf(good, good.copy(lat = 40.8)), Double.NaN))))
        assertEquals(empty, OverlayGeoJson.pins(bad, MapPoint(41.0, Double.POSITIVE_INFINITY)))
        assertEquals(empty, OverlayGeoJson.incidents(listOf(IncidentPin("i", bad, "CLOSURE", closure = true))))
        assertEquals(empty, OverlayGeoJson.weatherAreas(listOf(WeatherArea("w", listOf(listOf(good, bad, good.copy(lon = -73.0))), "SEVERE"))))
        // The rest of the list still goes.
        val cameras = MiniJson.parse(OverlayGeoJson.cameras(listOf(CameraPin("a", bad), CameraPin("b", good)))) as Map<*, *>
        assertEquals(listOf("b"), (cameras["features"] as List<*>).map { ((it as Map<*, *>)["properties"] as Map<*, *>)["id"] })
    }
}
