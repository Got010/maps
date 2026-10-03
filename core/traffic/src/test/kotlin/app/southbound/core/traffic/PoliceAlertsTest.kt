package app.southbound.core.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoliceAlertsTest {
    // A straight route going east along 40.70N, about 8.4 km (0.1 degree of longitude).
    private val east = (0..100).map { GeoPoint(40.70, -74.00 + it * 0.001) }

    // The same idea going north along 74.0W.
    private val north = (0..100).map { GeoPoint(40.70 + it * 0.001, -74.00) }

    private fun police(id: String, lat: Double, lon: Double, direction: String? = null, kind: IncidentKind = IncidentKind.POLICE) =
        Incident(id, kind, "I-87", direction, "Police activity", GeoPoint(lat, lon), null, null, null)

    @Test
    fun `an incident within a mile on the route is ahead, with its distance along`() {
        // 0.01 degree of longitude at 40.7N is about 844 m: 0.015 is about 1.27 km.
        val found = PoliceAlerts.ahead(east, listOf(police("a", 40.70005, -73.985)))
        assertEquals(listOf("a"), found.map { it.incident.id })
        assertEquals(1266.0, found.single().metersAhead, 15.0)
    }

    @Test
    fun `further than a mile or off the route is not ahead`() {
        val far = police("far", 40.70, -73.97) // about 2.5 km
        val off = police("off", 40.7010, -73.99) // 110 m north of the line
        assertTrue(PoliceAlerts.ahead(east, listOf(far, off)).isEmpty())
        assertEquals(listOf("far"), PoliceAlerts.ahead(east, listOf(far, off), withinMeters = 3000.0).map { it.incident.id })
    }

    @Test
    fun `nearest first, and other kinds are ignored`() {
        val list = listOf(
            police("later", 40.70, -73.985),
            police("sooner", 40.70, -73.995),
            police("crash", 40.70, -73.99, kind = IncidentKind.ACCIDENT),
        )
        assertEquals(listOf("sooner", "later"), PoliceAlerts.ahead(east, list).map { it.incident.id })
    }

    @Test
    fun `the other carriageway is left out, both directions and unknown are kept`() {
        val onThisSide = police("this", 40.70, -73.99, "Eastbound")
        val other = police("other", 40.70, -73.99, "Westbound")
        val both = police("both", 40.70, -73.99, "Both Directions")
        val unknown = police("unknown", 40.70, -73.99, "Inbound")
        val ids = PoliceAlerts.ahead(east, listOf(onThisSide, other, both, unknown)).map { it.incident.id }.toSet()
        assertEquals(setOf("this", "both", "unknown"), ids)

        val southbound = police("s", 40.705, -74.00, "Southbound")
        val northbound = police("n", 40.705, -74.00, "Northbound")
        assertEquals(listOf("n"), PoliceAlerts.ahead(north, listOf(southbound, northbound)).map { it.incident.id })
    }

    @Test
    fun `a short or empty route has nothing ahead`() {
        assertTrue(PoliceAlerts.ahead(emptyList(), listOf(police("a", 40.70, -74.0))).isEmpty())
        assertTrue(PoliceAlerts.ahead(listOf(GeoPoint(40.7, -74.0)), listOf(police("a", 40.70, -74.0))).isEmpty())
        assertTrue(PoliceAlerts.ahead(east, emptyList()).isEmpty())
    }

    @Test
    fun `an incident behind the start of the remaining route is not ahead`() {
        val behind = police("behind", 40.70, -74.01) // 840 m back along the line
        assertTrue(PoliceAlerts.ahead(east, listOf(behind)).isEmpty())
    }
}
