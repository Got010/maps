package app.southbound.core.userdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LegLogTest {

    private fun refused(block: () -> Unit): String {
        try {
            block()
        } catch (e: LegRefusedException) {
            return e.message!!
        }
        fail("accepted")
        error("unreachable")
    }

    private fun UserData.log(trip: String, on: String, meters: Long, from: String = "Motel A", to: String = "Site B") =
        addLeg(trip, on, meters, LegMode.DRIVE, from, to, edited = false)

    private fun leg(
        trip: String = "Fall trip",
        on: String = "2026-10-24",
        meters: Long = 1_000,
        mode: LegMode = LegMode.DRIVE,
        from: String = "Motel A",
        to: String = "Site B",
        id: Long = 3,
    ) = Leg(id, trip, on, meters, mode, from, to, edited = false)

    @Test
    fun `add, edit and delete keep ids and order`() {
        var d = UserData()
        val firstId = d.lastId + 1
        d = d.log("Fall trip", "2026-10-25", 100_000)
        d = d.log("Fall trip", "2026-10-24", 50_000, from = "Home base", to = "Motel A")
        assertEquals(listOf(firstId, firstId + 1), d.legs.map { it.id })
        assertEquals(firstId + 1, d.lastId)
        // The log's order is by day, then id, whatever order they were logged in.
        assertEquals(listOf("2026-10-24", "2026-10-25"), d.legLog().trip("Fall trip")!!.legs.map { it.on })

        // A date or a label changed: still as planned.
        d = d.editLeg(firstId, "Fall trip", "2026-10-26", 100_000, LegMode.DRIVE, "Motel A", "Site C")
        assertFalse(d.legs.first { it.id == firstId }.edited)
        assertEquals("Site C", d.legs.first { it.id == firstId }.to)
        assertEquals(firstId, d.legs.first().id) // edited in place, same id
        // The distance changed: edited, and it stays edited even when the distance goes back.
        d = d.editLeg(firstId, "Fall trip", "2026-10-26", 101_000, LegMode.DRIVE, "Motel A", "Site C")
        assertTrue(d.legs.first { it.id == firstId }.edited)
        d = d.editLeg(firstId, "Fall trip", "2026-10-26", 100_000, LegMode.WALK, "Motel A", "Site C")
        assertTrue(d.legs.first { it.id == firstId }.edited)
        assertEquals(LegMode.WALK, d.legs.first { it.id == firstId }.mode)

        d = d.deleteLeg(firstId)
        assertEquals(listOf(firstId + 1), d.legs.map { it.id })
        // Ids are never handed out again, also after a delete.
        d = d.log("Fall trip", "2026-10-27", 1)
        assertEquals(firstId + 2, d.legs.last().id)
        assertEquals("That leg is no longer in the log.", refused { d.editLeg(firstId, "Fall trip", "2026-10-26", 1, LegMode.DRIVE, "a", "b") })
        // A delete of a leg that isn't there changes nothing.
        assertEquals(d, d.deleteLeg(999))
        // A leg is not a saved place, a list or a recent.
        assertEquals(UserData().saved, d.saved)
        assertEquals(UserData().lists, d.lists)
    }

    @Test
    fun `rename and delete a trip`() {
        var d = UserData().log("Fall trip", "2026-10-24", 1_000).log("Work run", "2026-10-25", 2_000).log("Fall trip", "2026-10-26", 3_000)
        d = d.renameTrip("Fall trip", "October claim")
        assertEquals(listOf("October claim", "Work run", "October claim"), d.legs.map { it.trip })
        // Into an existing name, the two become one trip.
        d = d.renameTrip("Work run", "October claim")
        assertEquals(1, d.legLog().trips.size)
        assertEquals(3, d.legLog().trips.single().count)
        assertTrue(refused { d.renameTrip("October claim", "x".repeat(Leg.MAX_TRIP + 1)) }.startsWith("The trip name"))
        assertTrue(refused { d.renameTrip("October claim", "two\nlines") }.startsWith("The trip name"))
        assertTrue(refused { d.renameTrip("October claim", "  ") }.startsWith("The trip name"))
        val lastId = d.lastId
        d = d.deleteTrip("October claim")
        assertTrue(d.legs.isEmpty())
        assertEquals(lastId, d.lastId)
        assertEquals(lastId + 1, d.log("New", "2026-11-01", 5).legs.single().id)
    }

    @Test
    fun `trips newest first, with totals, counts and day range`() {
        val d = UserData()
            .log("Spring", "2026-04-02", 16_093) // 10.0 mi, 16.1 km
            .log("Fall", "2026-10-24", 160_934) // 100.0 mi
            .log("Spring", "2026-04-01", 8_047) // 5.0 mi, 8.0 km
            .log("Summer", "2026-10-24", 1_609) // same latest day as Fall, newer id
        val log = d.legLog()
        assertEquals(listOf("Summer", "Fall", "Spring"), log.trips.map { it.trip })
        val spring = log.trip("Spring")!!
        assertEquals(2, spring.count)
        assertEquals("2026-04-01", spring.firstDay)
        assertEquals("2026-04-02", spring.lastDay)
        assertEquals(150L, spring.tenthsMiles)
        assertEquals(161L + 80L, spring.tenthsKm)
        assertEquals(listOf("2026-04-01", "2026-04-02"), spring.legs.map { it.on })
        assertNull(log.trip("Winter"))
        // An empty log has no trips, no last trip and no chips.
        assertTrue(LegLog(emptyList()).trips.isEmpty())
        assertNull(LegLog(emptyList()).lastTrip)
        assertTrue(LegLog(emptyList()).labelChips().isEmpty())
    }

    @Test
    fun `chips are the log's labels newest first, at most eight, and the trip's previous To`() {
        var d = UserData()
        for (i in 1..6) d = d.log("Fall", "2026-10-${10 + i}", 1_000, from = "Stop ${i - 1}", to = "Stop $i")
        val log = d.legLog()
        // Newest leg first, its To then its From; a label in several legs once.
        assertEquals(listOf("Stop 6", "Stop 5", "Stop 4", "Stop 3", "Stop 2", "Stop 1", "Stop 0"), log.labelChips())
        for (i in 7..12) d = d.log("Fall", "2026-10-${10 + i}", 1_000, from = "Stop ${i - 1}", to = "Stop $i")
        assertEquals(LegLog.MAX_CHIPS, d.legLog().labelChips().size)
        assertEquals("Stop 12", d.legLog().labelChips().first())
        // From offers the trip's previous To: its last leg by day, not the last one logged.
        d = d.log("Fall", "2026-10-01", 1_000, from = "Early A", to = "Early B")
        assertEquals("Stop 12", d.legLog().previousTo("Fall"))
        assertNull(d.legLog().previousTo("Other trip"))
        // The trips as chips, the newest leg's trip first and preselected.
        d = d.log("Work run", "2026-09-01", 1_000)
        assertEquals("Work run", d.legLog().lastTrip)
        assertEquals(listOf("Work run", "Fall"), d.legLog().tripNames)
    }

    @Test
    fun `problem names the field one over each cap`() {
        assertNull(leg().problem())
        assertNull(leg(trip = "t".repeat(Leg.MAX_TRIP), from = "f".repeat(Leg.MAX_LABEL), to = "t".repeat(Leg.MAX_LABEL)).problem())
        assertNull(leg(meters = Leg.MIN_METERS).problem())
        assertNull(leg(meters = Leg.MAX_METERS).problem())
        assertNull(leg(on = "2028-02-29").problem())
        assertTrue(leg(trip = "t".repeat(41)).problem()!!.startsWith("The trip name"))
        assertTrue(leg(trip = "").problem()!!.startsWith("The trip name"))
        assertTrue(leg(trip = "a\nb").problem()!!.startsWith("The trip name"))
        assertTrue(leg(from = "f".repeat(81)).problem()!!.startsWith("From"))
        assertTrue(leg(to = "t".repeat(81)).problem()!!.startsWith("To"))
        assertTrue(leg(from = "   ").problem()!!.startsWith("From"))
        assertTrue(leg(to = "").problem()!!.startsWith("To"))
        assertTrue(leg(to = "tab\there").problem()!!.startsWith("To"))
        assertTrue(leg(to = "lone \ud800").problem()!!.startsWith("To"))
        assertTrue(leg(meters = 0).problem()!!.startsWith("The distance"))
        assertTrue(leg(meters = 10_000_001).problem()!!.startsWith("The distance"))
        assertTrue(leg(meters = -5).problem()!!.startsWith("The distance"))
        for (bad in listOf("2026-02-30", "2026-13-01", "2026-1-01", "26-10-24", "2026-10-24T10:00", "", "2026/10/24")) {
            assertTrue(bad, leg(on = bad).problem()!!.startsWith("The date"))
        }
        assertTrue(leg(id = 0).problem() != null)
        // An unknown mode can't be a Leg at all; the readers refuse it (UserDataTest, the export's strict reader).
        assertEquals(listOf("DRIVE", "WALK", "BIKE"), LegMode.entries.map { it.name })
    }

    @Test
    fun `add and edit refuse a leg out of bounds and change nothing`() {
        val d = UserData().log("Fall", "2026-10-24", 1_000)
        assertTrue(refused { d.log("t".repeat(41), "2026-10-24", 1) }.startsWith("The trip name"))
        assertTrue(refused { d.log("Fall", "2026-02-30", 1) }.startsWith("The date"))
        assertTrue(refused { d.log("Fall", "2026-10-24", 0) }.startsWith("The distance"))
        assertTrue(refused { d.log("Fall", "2026-10-24", 1, from = "f".repeat(81)) }.startsWith("From"))
        val id = d.legs.single().id
        assertTrue(refused { d.editLeg(id, "Fall", "2026-10-24", 10_000_001, LegMode.BIKE, "a", "b") }.startsWith("The distance"))
        assertEquals(1, d.legs.size)
        assertEquals(id, d.lastId)
    }

    @Test
    fun `the 5,001st leg is refused with a sentence`() {
        val full = UserData(legs = (1..LegLog.MAX_LEGS).map { leg(id = it.toLong() + 2) }, lastId = LegLog.MAX_LEGS + 2L)
        val sentence = refused { full.log("Fall", "2026-10-24", 1_000) }
        assertEquals("The trip log holds 5,000 legs at most. Delete a trip you have claimed to log more.", sentence)
        // One fewer, and it fits.
        assertEquals(LegLog.MAX_LEGS, full.deleteLeg(3).log("Fall", "2026-10-24", 1_000).legs.size)
    }

    @Test
    fun `labels are cleaned to one line`() {
        assertEquals("Motel A", Leg.clean("  Motel\n A \t", Leg.MAX_LABEL))
        assertEquals("a b c", Leg.clean("a\r\nb c", Leg.MAX_LABEL))
        assertEquals("", Leg.clean(" \n ", Leg.MAX_LABEL))
        assertEquals("x".repeat(Leg.MAX_TRIP), Leg.clean("x".repeat(60), Leg.MAX_TRIP))
        // Never cut through a surrogate pair.
        val emoji = "🚗"
        assertEquals("x".repeat(39), Leg.clean("x".repeat(39) + emoji, Leg.MAX_TRIP))
        assertTrue(Leg.oneLine(Leg.clean("Site\tB\n", Leg.MAX_LABEL), Leg.MAX_LABEL))
    }

    @Test
    fun `distances round half up to one decimal`() {
        assertEquals(10L, Distance.tenthsMiles(1_609)) // 0.99978 mi
        assertEquals(10L, Distance.tenthsMiles(1_610))
        assertEquals(0L, Distance.tenthsMiles(80)) // 0.0497 mi
        assertEquals(1L, Distance.tenthsMiles(81)) // 0.0503 mi
        assertEquals(62_137L, Distance.tenthsMiles(Leg.MAX_METERS))
        assertEquals(0L, Distance.tenthsKm(49))
        assertEquals(1L, Distance.tenthsKm(50))
        assertEquals("212.4", Distance.format(2124))
        assertEquals("0.0", Distance.format(0))
        assertEquals(341_825L, Distance.metersFromMiles(212.4))
        assertNull(Distance.metersFromMiles(Double.NaN))
        assertNull(Distance.metersFromMiles(-1.0))
    }
}
