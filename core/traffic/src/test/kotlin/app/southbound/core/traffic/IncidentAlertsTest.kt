package app.southbound.core.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentAlertsTest {
    // A straight route going east along 40.70N, about 8.4 km (0.1 degree of longitude).
    private val east = (0..100).map { GeoPoint(40.70, -74.00 + it * 0.001) }

    private fun event(
        id: String,
        kind: IncidentKind,
        description: String,
        lanes: String? = null,
        lon: Double = -73.985,
        direction: String? = null,
    ) = Incident(id, kind, "I-87", direction, description, GeoPoint(40.70005, lon), null, lanes, null)

    @Test
    fun `crashes are spoken, a disabled vehicle only when it closes lanes`() {
        assertEquals(IncidentAlertKind.CRASH, IncidentAlerts.kindOf(event("a", IncidentKind.ACCIDENT, "I-87 northbound: Minor accident.")))
        assertEquals(IncidentAlertKind.CRASH, IncidentAlerts.kindOf(event("b", IncidentKind.ACCIDENT, "I-87: Crash.", lanes = "Right lane closed")))
        assertNull(IncidentAlerts.kindOf(event("c", IncidentKind.ACCIDENT, "I-190 southbound: Disabled vehicle.")))
        assertEquals(
            IncidentAlertKind.LANES,
            IncidentAlerts.kindOf(event("d", IncidentKind.ACCIDENT, "I-190 southbound: Disabled vehicle.", lanes = "Left lane blocked")),
        )
    }

    @Test
    fun `roadwork is spoken with or without lanes, closures and police are not`() {
        assertEquals(IncidentAlertKind.ROADWORK, IncidentAlerts.kindOf(event("a", IncidentKind.ROADWORK, "Roadwork.", lanes = "Two right lanes of three blocked")))
        assertEquals(IncidentAlertKind.ROADWORK, IncidentAlerts.kindOf(event("b", IncidentKind.ROADWORK, "Roadwork.", lanes = "1 of 3 lanes closed")))
        assertEquals(IncidentAlertKind.ROADWORK, IncidentAlerts.kindOf(event("c", IncidentKind.ROADWORK, "Road construction.")))
        assertEquals(IncidentAlertKind.LANES, IncidentAlerts.kindOf(event("h", IncidentKind.OTHER, "Debris.", lanes = "Left lane blocked")))
        assertNull(IncidentAlerts.kindOf(event("d", IncidentKind.ROADWORK, "Roadwork.", lanes = "All lanes closed")))
        assertNull(IncidentAlerts.kindOf(event("e", IncidentKind.CLOSURE, "Road closed.")))
        assertNull(IncidentAlerts.kindOf(event("f", IncidentKind.POLICE, "Police activity.", lanes = "Right lane closed")))
        assertNull(IncidentAlerts.kindOf(event("g", IncidentKind.OTHER, "Traffic incident reported.")))
    }

    @Test
    fun `within about two miles on the route, nearest first, other carriageway left out`() {
        val near = event("near", IncidentKind.ACCIDENT, "Crash.", lon = -73.985) // about 1.3 km
        val further = event("further", IncidentKind.ROADWORK, "Roadwork.", lanes = "Right lane closed", lon = -73.970) // about 2.5 km
        val tooFar = event("tooFar", IncidentKind.ACCIDENT, "Crash.", lon = -73.950) // about 4.2 km
        val otherSide = event("otherSide", IncidentKind.ACCIDENT, "Crash.", lon = -73.990, direction = "Westbound")
        val plain = event("plain", IncidentKind.OTHER, "Traffic incident reported.", lon = -73.990)
        val found = IncidentAlerts.ahead(east, listOf(tooFar, further, otherSide, plain, near))
        assertEquals(listOf("near", "further"), found.map { it.incident.id })
        assertEquals(listOf(IncidentAlertKind.CRASH, IncidentAlertKind.ROADWORK), found.map { it.kind })
        assertEquals(1266.0, found.first().metersAhead, 15.0)
        assertTrue(IncidentAlerts.ahead(east, listOf(plain)).isEmpty())
    }

    @Test
    fun `lanes closed are said as the feed words them, never for a full closure`() {
        assertEquals("Right lane closed", IncidentAlerts.lanesClosed(event("a", IncidentKind.ACCIDENT, "Crash.", lanes = "Right lane closed")))
        assertNull(IncidentAlerts.lanesClosed(event("b", IncidentKind.ACCIDENT, "Crash.", lanes = "All lanes closed")))
        assertNull(IncidentAlerts.lanesClosed(event("c", IncidentKind.ACCIDENT, "Crash.", lanes = "Shoulder closed")))
        assertNull(IncidentAlerts.lanesClosed(event("d", IncidentKind.ACCIDENT, "Crash.")))
    }

    @Test
    fun `roadwork in a row on the same road is one work zone`() {
        val first = event("w1", IncidentKind.ROADWORK, "Roadwork.", lon = -73.990) // about 0.85 km
        val second = event("w2", IncidentKind.ROADWORK, "Roadwork.", lon = -73.980) // about 1.7 km
        val crash = event("c", IncidentKind.ACCIDENT, "Crash.", lon = -73.985)
        val elsewhere = first.copy(id = "w3", roadway = "NY 9A", location = GeoPoint(40.70005, -73.982))
        val later = event("w4", IncidentKind.ROADWORK, "Roadwork.", lon = -73.965) // about 3 km, past the zone
        val found = IncidentAlerts.ahead(east, listOf(first, second, crash, elsewhere, later))
        val spoken = found.first()
        assertEquals("w1", spoken.incident.id)
        assertEquals(listOf("w2"), IncidentAlerts.sameZone(spoken, found).map { it.incident.id })
        assertTrue(IncidentAlerts.sameZone(found.single { it.incident.id == "c" }, found).isEmpty())
    }
}
