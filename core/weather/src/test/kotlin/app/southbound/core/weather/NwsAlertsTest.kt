package app.southbound.core.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * nws-alerts-us.json holds four real alerts from api.weather.gov/alerts/active, fetched
 * 2026-10-07 by the feed probe (two with a polygon, two with only zones; descriptions cut to
 * 400 characters, `parameters` left out). New York had none that day: nws-alerts-ny-none.json
 * is its answer.
 */
class NwsAlertsTest {
    private fun read(name: String) = File("src/test/resources/$name").readText()

    @Test
    fun `real alerts parse, with a polygon or with zones`() {
        val alerts = NwsAlerts.parse(read("nws-alerts-us.json"))
        assertEquals(4, alerts.size)
        val flood = alerts[0]
        assertEquals("Flood Advisory", flood.event)
        assertEquals(AlertSeverity.MINOR, flood.severity)
        assertEquals("Brevard, FL", flood.areaDesc)
        assertEquals(1, flood.polygons.size)
        assertEquals(6, flood.polygons[0].size)
        assertEquals(28.2, flood.polygons[0][0].lat, 1e-9)
        assertEquals(-80.66, flood.polygons[0][0].lon, 1e-9)
        assertEquals(listOf("https://api.weather.gov/zones/county/FLC009"), flood.zoneUrls)
        assertEquals(NwsAlerts.time("2026-10-07T16:18:00-04:00"), flood.onset)
        assertEquals(NwsAlerts.time("2026-10-07T18:15:00-04:00"), flood.ends)
        assertTrue(flood.headline!!.startsWith("Flood Advisory issued October 7"))
        assertTrue(flood.description!!.startsWith("* WHAT...Flooding"))

        val air = alerts.single { it.event == "Air Quality Alert" }
        assertTrue(air.polygons.isEmpty())
        assertEquals(3, air.zoneUrls.size)
        assertEquals(AlertSeverity.UNKNOWN, air.severity)
        assertEquals(AlertSeverity.MODERATE, alerts.single { it.event == "Special Weather Statement" }.severity)
    }

    @Test
    fun `no alerts in New York is an empty list`() {
        assertTrue(NwsAlerts.parse(read("nws-alerts-ny-none.json")).isEmpty())
    }

    @Test
    fun `tests, cancellations and alerts with no area are dropped`() {
        fun feature(status: String, type: String, zones: String = "\"https://api.weather.gov/zones/forecast/NYZ052\"") =
            """{"type":"Feature","geometry":null,"properties":{"id":"$status-$type","event":"Wind Advisory","status":"$status",
               "messageType":"$type","affectedZones":[$zones]}}"""
        val json = """{"type":"FeatureCollection","features":[${feature("Actual", "Alert")},${feature("Test", "Alert")},
            ${feature("Actual", "Cancel")},${feature("Exercise", "Update")},${feature("Actual", "Update")},
            ${feature("Actual", "Alert", "\"https://example.com/zone\"").replace("Actual-Alert", "elsewhere")}]}"""
        assertEquals(listOf("Actual-Alert", "Actual-Update"), NwsAlerts.parse(json).map { it.id })
    }

    @Test
    fun `not a feature collection is an error`() {
        try {
            NwsAlerts.parse("""{"title":"Not Found"}""")
            fail()
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertNull(NwsAlerts.time("soon"))
    }

    @Test
    fun `a zone's shape, polygon or multipolygon`() {
        // The shape of api.weather.gov/zones/forecast/NYZ052 (a Feature with a Polygon), trimmed to a few points.
        val polygon = """{"id":"https://api.weather.gov/zones/forecast/NYZ052","type":"Feature","geometry":{"type":"Polygon",
            "coordinates":[[[-73.6772,42.783512],[-73.677498,42.781311],[-73.6819,42.771511],[-73.6772,42.783512]]]},
            "properties":{"id":"NYZ052","name":"Albany"}}"""
        assertEquals(1, NwsZones.parse(polygon).size)
        val multi = """{"type":"Feature","geometry":{"type":"MultiPolygon","coordinates":[
            [[[-74,40],[-73,40],[-73,41],[-74,40]]],[[[-72,40],[-71,40],[-71,41],[-72,40]]]]}}"""
        assertEquals(2, NwsZones.parse(multi).size)
        assertTrue(NwsZones.parse("""{"type":"Feature","geometry":null}""").isEmpty())
    }
}
