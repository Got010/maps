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
        // The same tenth of a mile typed another way is not a change: the planned metres stay and nothing is edited.
        for (same in listOf("212.40", "0212.4", "212.4000", " 212.4")) {
            assertEquals(same, 341_825L, d.copy(miles = same).meters())
            assertFalse(same, d.copy(miles = same).distanceChanged)
        }
        // A planned distance off the tenth (341,900 m prints "212.4", which parses to 341,825): the same tenth typed
        // another way still keeps the planned metres, so it is compared as a distance, never as the printed text.
        val off = LegDraft.forRoute(341_900.0, LegMode.DRIVE, today, log)
        assertEquals("212.4", off.miles)
        assertEquals(341_900L, off.meters())
        for (same in listOf("212.4", "212.40", "0212.4", " 212.4 ")) {
            assertEquals(same, 341_900L, off.copy(miles = same).meters())
            assertFalse(same, off.copy(miles = same).distanceChanged)
        }
        assertTrue(off.copy(miles = "212.5").distanceChanged)
        assertTrue(d.copy(miles = "212.5").distanceChanged)
        assertTrue(d.copy(miles = "212.3").distanceChanged)
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
        // A label at its cap is kept whole; past it, refused and never cut.
        assertNull(d.copy(to = "x".repeat(Leg.MAX_LABEL)).problem())
        assertEquals(Leg.MAX_LABEL, d.copy(to = "x".repeat(Leg.MAX_LABEL)).cleanTo.length)
        assertEquals(Leg.MAX_LABEL + 1, d.copy(to = "x".repeat(Leg.MAX_LABEL + 1)).cleanTo.length)
        assertTrue(d.copy(to = "x".repeat(Leg.MAX_LABEL + 1)).problem()!!.startsWith("To"))
        assertTrue(d.copy(from = "x".repeat(Leg.MAX_LABEL + 1)).problem()!!.startsWith("From"))
        assertTrue(d.copy(trip = "t".repeat(Leg.MAX_TRIP + 1)).problem()!!.startsWith("The trip name"))
        // A route of nothing can't be logged until a distance is typed.
        assertTrue(LegDraft.forRoute(0.0, LegMode.DRIVE, today, log).copy(trip = "t", from = "a", to = "b").problem()!!.startsWith("The distance"))
    }

    @Test
    fun theFieldsStopAtTheirCapsSoSaveNeverCuts() {
        assertEquals("Fall trip", LegDraft.capped("Fall trip", Leg.MAX_TRIP))
        assertEquals("t".repeat(Leg.MAX_TRIP), LegDraft.capped("t".repeat(Leg.MAX_TRIP + 5), Leg.MAX_TRIP))
        // A paste keeps its start, and never ends on half a surrogate pair.
        assertEquals("x".repeat(Leg.MAX_TRIP - 1), LegDraft.capped("x".repeat(Leg.MAX_TRIP - 1) + "\uD83D\uDE97", Leg.MAX_TRIP))
        // Whatever a field keeps, Save keeps all of it: cleaning only shortens.
        val d = LegDraft.forRoute(1_000.0, LegMode.DRIVE, today, log)
        for (typed in listOf("  a  b  ".repeat(20), "x".repeat(500), "Motel\nA\t".repeat(30))) {
            val trip = LegDraft.capped(typed, Leg.MAX_TRIP)
            val label = LegDraft.capped(typed, Leg.MAX_LABEL)
            val kept = d.copy(trip = trip, from = label, to = label)
            assertNull(typed, kept.problem())
            assertEquals(Leg.clean(trip, Leg.MAX_TRIP), kept.cleanTrip)
            assertEquals(Leg.clean(label, Leg.MAX_LABEL), kept.cleanTo)
        }
        // The counter shows from three quarters of the cap.
        assertFalse(LegDraft.nearCap("t".repeat(29), Leg.MAX_TRIP))
        assertTrue(LegDraft.nearCap("t".repeat(30), Leg.MAX_TRIP))
        assertFalse(LegDraft.nearCap("x".repeat(59), Leg.MAX_LABEL))
        assertTrue(LegDraft.nearCap("x".repeat(60), Leg.MAX_LABEL))
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
