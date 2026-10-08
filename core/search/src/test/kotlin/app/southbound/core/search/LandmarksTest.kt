package app.southbound.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos

/** Hand-drawn streets: metres east and north of a corner in Chelsea. */
internal object Hand {
    private val origin = LatLon(40.7400, -73.9900)
    fun at(east: Double, north: Double) = LatLon(
        origin.lat + north / RouteLine.METERS_PER_DEGREE,
        origin.lon + east / (RouteLine.METERS_PER_DEGREE * cos(Math.toRadians(origin.lat))),
    )
    private var nextId = 1L
    fun mark(category: String, east: Double, north: Double, name: String? = null, direction: Int? = null): Landmark {
        val p = at(east, north)
        return Landmark(nextId++, category, name, p.lat, p.lon, direction)
    }
}

class LandmarksTest {

    // Driving north 300 m up an avenue; the turn is at (0, 300).
    private val approach = listOf(Hand.at(0.0, 0.0), Hand.at(0.0, 150.0), Hand.at(0.0, 300.0))

    private fun phrase(vararg marks: Landmark, heard: Double = 250.0) =
        Landmarks.phraseFor(approach, marks.toList(), heard)

    @Test
    fun signalAtTheTurnIsTheNextLight() {
        assertEquals("at the next light", phrase(Hand.mark(Landmark.TRAFFIC_SIGNALS, 0.0, 300.0)))
        // Mapped on the far corner of the cross street.
        assertEquals("at the next light", phrase(Hand.mark(Landmark.TRAFFIC_SIGNALS, 8.0, 305.0)))
    }

    @Test
    fun signalJustBeforeTheTurn() {
        assertEquals("after the next light", phrase(Hand.mark(Landmark.TRAFFIC_SIGNALS, 0.0, 270.0)))
        // Mapped on the other carriageway, a few metres off the line.
        assertEquals("after the next light", phrase(Hand.mark(Landmark.TRAFFIC_SIGNALS, -10.0, 275.0)))
    }

    @Test
    fun signalTooFarBackIsNotTheTurns() {
        assertNull(phrase(Hand.mark(Landmark.TRAFFIC_SIGNALS, 0.0, 200.0)))
    }

    @Test
    fun anotherLightOnTheWayMakesNextAmbiguous() {
        val turnLight = Hand.mark(Landmark.TRAFFIC_SIGNALS, 0.0, 300.0)
        val earlier = Hand.mark(Landmark.TRAFFIC_SIGNALS, 0.0, 150.0)
        assertNull(phrase(turnLight, earlier))
        // ...unless the driver hears the instruction after passing it.
        assertEquals("at the next light", phrase(turnLight, earlier, heard = 100.0))
    }

    @Test
    fun aLightJustBehindWhereThePromptTriggersStillCounts() {
        // Heard 250 m out by the trigger, but GPS may run ahead: a light 260 m back may still be ahead of the car.
        val turnLight = Hand.mark(Landmark.TRAFFIC_SIGNALS, 0.0, 300.0)
        assertNull(phrase(turnLight, Hand.mark(Landmark.TRAFFIC_SIGNALS, 0.0, 40.0)))
        assertEquals("at the next light", phrase(turnLight, Hand.mark(Landmark.TRAFFIC_SIGNALS, 0.0, 10.0)))
    }

    @Test
    fun nearKeepsEveryLightTheDriverMayPass() {
        val turn = Hand.at(0.0, 300.0)
        val turnLight = Hand.mark(Landmark.TRAFFIC_SIGNALS, 0.0, 300.0)
        val earlier = Hand.mark(Landmark.TRAFFIC_SIGNALS, 0.0, 120.0) // 180 m before the turn
        val wayBack = Hand.mark(Landmark.TRAFFIC_SIGNALS, 0.0, -400.0) // 700 m before: heard after passing it
        val shell = Hand.mark(Landmark.FUEL, 15.0, 295.0, "Shell")
        val farShell = Hand.mark(Landmark.FUEL, 15.0, 150.0, "Shell")
        val near = Landmarks.near(turn, listOf(turnLight, earlier, wayBack, shell, farShell), 250.0)
        assertEquals(listOf(turnLight, earlier, shell), near)
        // Before, only landmarks within 60 m of the turn were passed on, so the earlier light was never seen and
        // the prompt said "at the next light" with two lights ahead. Now the lights say nothing; the corner's
        // gas station still can.
        assertEquals("at the Shell gas station", Landmarks.phraseFor(approach, near, 250.0))
        assertNull(Landmarks.phraseFor(approach, near - shell, 250.0))
    }

    @Test
    fun bothCarriagewaysOfOneJunctionCountOnce() {
        assertEquals(
            "at the next light",
            phrase(Hand.mark(Landmark.TRAFFIC_SIGNALS, 0.0, 300.0), Hand.mark(Landmark.TRAFFIC_SIGNALS, 0.0, 280.0)),
        )
    }

    @Test
    fun signalOnACrossStreetElsewhereIsIgnored() {
        assertNull(phrase(Hand.mark(Landmark.TRAFFIC_SIGNALS, 80.0, 290.0)))
    }

    @Test
    fun stopSign() {
        assertEquals("at the stop sign", phrase(Hand.mark(Landmark.STOP, 3.0, 290.0)))
        assertNull(phrase(Hand.mark(Landmark.STOP, 0.0, 200.0)))
    }

    @Test
    fun lightBeatsStopSignAndPlace() {
        assertEquals(
            "at the next light",
            phrase(
                Hand.mark(Landmark.FUEL, 15.0, 295.0, "Shell"),
                Hand.mark(Landmark.STOP, 3.0, 290.0),
                Hand.mark(Landmark.TRAFFIC_SIGNALS, 0.0, 300.0),
            ),
        )
    }

    @Test
    fun placeOnTheCorner() {
        assertEquals("at the Walgreens", phrase(Hand.mark(Landmark.PHARMACY, 15.0, 310.0, "Walgreens")))
    }

    @Test
    fun placePassedOnTheWayIn() {
        assertEquals("past the Shell gas station", phrase(Hand.mark(Landmark.FUEL, 20.0, 260.0, "Shell")))
        assertEquals("past the gas station", phrase(Hand.mark(Landmark.FUEL, -20.0, 260.0)))
    }

    @Test
    fun placeBeyondTheTurnIsIgnored() {
        // 40 m further up the avenue, past the corner: the driver never passes it.
        assertNull(phrase(Hand.mark(Landmark.FUEL, 5.0, 340.0, "Shell")))
        // Too far off the road to be seen.
        assertNull(phrase(Hand.mark(Landmark.FUEL, 45.0, 280.0, "Shell")))
    }

    @Test
    fun twoOfAKindSayNothing() {
        assertNull(phrase(Hand.mark(Landmark.FUEL, 15.0, 295.0), Hand.mark(Landmark.FUEL, -15.0, 305.0)))
    }

    @Test
    fun closestPlaceWins() {
        assertEquals(
            "at the McDonald's",
            phrase(Hand.mark(Landmark.FUEL, 20.0, 260.0, "Shell"), Hand.mark(Landmark.FAST_FOOD, 10.0, 305.0, "McDonald's")),
        )
    }

    @Test
    fun labels() {
        fun label(category: String, name: String?) = Landmarks.label(Landmark(0, category, name, 0.0, 0.0))
        assertEquals("the Shell gas station", label(Landmark.FUEL, "Shell"))
        assertEquals("the Speedway Gas", label(Landmark.FUEL, "Speedway Gas"))
        assertEquals("the Chase bank", label(Landmark.BANK, "Chase"))
        assertEquals("the TD Bank", label(Landmark.BANK, "TD Bank"))
        assertEquals("The Home Depot", label(Landmark.SUPERMARKET, "The Home Depot"))
        assertEquals("the church", label(Landmark.CHURCH, "Saint Mary's Roman Catholic Church"))
        assertNull(label(Landmark.FAST_FOOD, null))
        assertNull(label(Landmark.SPEED_CAMERA, null))
    }

    @Test
    fun tooShortApproach() {
        assertNull(Landmarks.phraseFor(listOf(Hand.at(0.0, 0.0)), listOf(Hand.mark(Landmark.STOP, 0.0, 0.0))))
    }
}
