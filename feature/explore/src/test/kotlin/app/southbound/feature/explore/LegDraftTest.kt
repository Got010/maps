package app.southbound.feature.explore

import app.southbound.core.userdata.Leg
import app.southbound.core.userdata.LegLog
import app.southbound.core.userdata.LegMode
import app.southbound.core.userdata.UserData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LegDraftTest {
    private val today = LocalDate.of(2026, 10, 24)

    private val log = UserData()
        .addLeg("Fall trip", "2026-10-20", 1_000, LegMode.DRIVE, "Motel A", "Site B", edited = false)
        .addLeg("Fall trip", "2026-10-21", 1_000, LegMode.DRIVE, "Site B", "Site C", edited = false)
        .addLeg("Work run", "2026-10-01", 1_000, LegMode.DRIVE, "Office", "Depot", edited = false)
        .legLog()

    @Test
    fun aNewLegStartsFromTheChosenOptionAndNothingFromTheMap() {
        val d = LegDraft.forRoute(341_824.7, LegMode.DRIVE, today, log)
        assertNull(d.legId)
        assertEquals("212.4", d.miles)
        assertEquals(341_825L, d.plannedMeters)
        assertEquals("2026-10-24", d.day)
        // The last trip used is preselected; the labels start empty.
        assertEquals("Work run", d.trip)
        assertEquals("", d.from)
        assertEquals("", d.to)
        assertEquals("", LegDraft.forRoute(1_000.0, LegMode.WALK, today, LegLog(emptyList())).trip)
    }

    @Test
    fun untouchedMilesKeepTheExactMetresAndATypedChangeIsEdited() {
        val d = LegDraft.forRoute(341_824.7, LegMode.DRIVE, today, log)
        assertEquals(341_825L, d.meters())
        assertFalse(d.distanceChanged)
        assertEquals(341_825L, d.copy(miles = " 212.4 ").meters())
        val typed = d.copy(miles = "215")
        assertEquals(346_009L, typed.meters())
        assertTrue(typed.distanceChanged)
        assertEquals(346_009L, d.copy(miles = "215.0").meters())
        assertEquals(1_950_605L, d.copy(miles = "1,212.05").meters())
        assertEquals(805L, d.copy(miles = ".5").meters())
        for (bad in listOf("", "abc", "12.3.4", "-5", "1e3", "12 mi")) assertNull(bad, d.copy(miles = bad).meters())
        assertEquals(LegDraft.MILES_PROBLEM, d.copy(miles = "abc", trip = "t", from = "a", to = "b").problem())
    }

    @Test
    fun problemFollowsTheLegRuleOnCleanedFields() {
        val d = LegDraft.forRoute(1_000.0, LegMode.DRIVE, today, log).copy(trip = "  Fall trip ", from = "Motel\nA", to = "Site B")
        assertNull(d.problem())
        assertEquals("Motel A", d.cleanFrom)
        assertEquals("Fall trip", d.cleanTrip)
        assertTrue(d.copy(from = "  ").problem()!!.startsWith("From"))
        assertTrue(d.copy(to = "").problem()!!.startsWith("To"))
        assertTrue(d.copy(trip = "").problem()!!.startsWith("The trip name"))
        assertTrue(d.copy(day = "2026-02-30").problem()!!.startsWith("The date"))
        assertTrue(d.copy(miles = "0").problem()!!.startsWith("The distance"))
        assertTrue(d.copy(miles = "7000").problem()!!.startsWith("The distance"))
        // A long label is cut to the cap rather than refused.
        assertNull(d.copy(to = "x".repeat(200)).problem())
        assertEquals(Leg.MAX_LABEL, d.copy(to = "x".repeat(200)).cleanTo.length)
        // A route of nothing can't be logged until a distance is typed.
        assertTrue(LegDraft.forRoute(0.0, LegMode.DRIVE, today, log).copy(trip = "t", from = "a", to = "b").problem()!!.startsWith("The distance"))
    }

    @Test
    fun anExistingLegOpensAsItIs() {
        val leg = Leg(9, "Fall trip", "2026-10-21", 341_825, LegMode.BIKE, "Site B", "Site C", edited = true)
        val d = LegDraft.forLeg(leg)
        assertEquals(9L, d.legId)
        assertEquals("212.4", d.miles)
        assertEquals(341_825L, d.meters())
        assertFalse(d.distanceChanged)
        assertEquals(LegMode.BIKE, d.mode)
        assertEquals(listOf("Fall trip", "2026-10-21", "Site B", "Site C"), listOf(d.trip, d.day, d.from, d.to))
    }

    @Test
    fun chips() {
        // From: the trip's previous To first.
        assertEquals("Site C", LegDraft.fromChips(log, "Fall trip").first())
        assertEquals("Depot", LegDraft.fromChips(log, " Work run ").first())
        assertEquals(log.labelChips(), LegDraft.fromChips(log, "New trip"))
        assertTrue(LegDraft.fromChips(log, "Fall trip").size <= LegLog.MAX_CHIPS)
        assertEquals(log.labelChips(), LegDraft.toChips(log))
        assertEquals(listOf("Work run", "Fall trip"), LegDraft.tripChips(log))
        // What I searched: cleaned to one label; nothing when there was no typed search.
        assertEquals("pizza near the lake", LegDraft.searchedChip("  pizza near\nthe lake "))
        assertNull(LegDraft.searchedChip(null))
        assertNull(LegDraft.searchedChip("   "))
        assertEquals(Leg.MAX_LABEL, LegDraft.searchedChip("q".repeat(300))!!.length)
    }

    @Test
    fun dayButtons() {
        assertEquals("2026-10-23", LegDraft.shiftDay("2026-10-24", -1))
        assertEquals("2026-03-01", LegDraft.shiftDay("2026-02-28", 1))
        assertNull(LegDraft.shiftDay("2026-02-30", 1))
        assertNull(LegDraft.shiftDay("soon", 1))
    }

    @Test
    fun everyTravelModeIsALegModeOfTheSameName() {
        for (mode in app.southbound.core.routing.TravelMode.entries) assertEquals(mode.name, LegDraft.legMode(mode).name)
        assertEquals(app.southbound.core.routing.TravelMode.entries.size, LegMode.entries.size)
    }
}
