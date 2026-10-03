package app.southbound.core.transit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Fixtures are written by src/test/resources/gtfsrt/make_fixtures.py with the official encoder. */
class GtfsRealtimeTest {

    private fun fixture(name: String) = File("src/test/resources/gtfsrt/$name").readBytes()
    private val now = 1_790_985_600L

    @Test
    fun `parses trip updates`() {
        val feed = GtfsRealtime.parse(fixture("trip_updates.pb"))
        assertEquals(now, feed.timestamp)
        // The vehicle position and the deleted entity are left out.
        assertEquals(listOf("050950_R..N93R", "GO202_26_1916", "GONE"), feed.tripUpdates.map { it.trip.tripId })

        val subway = feed.tripUpdates[0]
        assertEquals("R", subway.trip.routeId)
        assertEquals("20261006", subway.trip.startDate)
        assertEquals(0, subway.trip.directionId)
        val (first, second, third) = subway.stopTimeUpdates
        assertEquals("R31N", first.stopId)
        assertEquals(now + 100, first.arrival?.time)
        assertEquals(now + 130, first.departure?.time)
        assertEquals(5, second.stopSequence)
        assertEquals(-45, second.arrival?.delay)
        assertNull(second.departure)
        assertTrue(third.skipped)
        assertFalse(first.skipped)
    }

    @Test
    fun `trip-level delay and cancellation`() {
        val feed = GtfsRealtime.parse(fixture("trip_updates.pb"))
        val lirr = feed.tripUpdates[1]
        assertEquals(300, lirr.delay)
        assertEquals(now - 20, lirr.timestamp)
        assertTrue(feed.tripUpdates[2].trip.canceled)
        assertFalse(lirr.trip.canceled)
    }

    @Test
    fun `parses alerts`() {
        val alert = GtfsRealtime.parse(fixture("alerts.pb")).alerts.single()
        assertEquals("lmm:planned_work:1", alert.id)
        assertEquals(setOf(AlertRoute("MTASBWY", "A"), AlertRoute(null, "C")), alert.routes)
        assertEquals(setOf("A27"), alert.stopIds)
        assertEquals("A trains run local", alert.header)
        assertEquals("Café détour ✓", alert.description)
        assertEquals(2, alert.effect) // REDUCED_SERVICE
        assertTrue(alert.isActive(now))
        assertFalse(alert.isActive(now + 7200))
        assertTrue(alert.isActive(now + 90_000)) // the open-ended second period
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects truncated data`() {
        val bytes = fixture("trip_updates.pb")
        GtfsRealtime.parse(bytes.copyOf(bytes.size / 2))
    }
}
