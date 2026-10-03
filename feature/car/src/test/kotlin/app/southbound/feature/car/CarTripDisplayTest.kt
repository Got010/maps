package app.southbound.feature.car

import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Maneuver
import app.southbound.core.routing.TripSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CarTripDisplayTest {

    private fun snapshot(
        instruction: String = "Turn right onto Atlantic Avenue",
        type: String? = "TURN",
        modifier: String? = "RIGHT",
        toManeuver: Double = 120.0,
        remainingMeters: Double = 8000.0,
        remainingSeconds: Double = 900.0,
        next: String? = null,
        rerouting: Boolean = false,
        arrived: Boolean = false,
    ) = TripSnapshot(
        instruction = instruction,
        detail = null,
        maneuverType = type,
        maneuverModifier = modifier,
        distanceToManeuverMeters = toManeuver,
        roadName = "Flatbush Avenue",
        nextInstruction = next,
        nextManeuverType = "TURN",
        nextManeuverModifier = "LEFT",
        remainingMeters = remainingMeters,
        remainingSeconds = remainingSeconds,
        rerouting = rerouting,
        arrived = arrived,
    )

    @Test
    fun `distances round like the phone banner`() {
        assertEquals(DisplayDistance(50.0, Distance.UNIT_FEET), CarDistance.of(5.0))
        assertEquals(DisplayDistance(350.0, Distance.UNIT_FEET), CarDistance.of(100.0))
        assertEquals(DisplayDistance(0.3, Distance.UNIT_MILES_P1), CarDistance.of(482.0))
        assertEquals(DisplayDistance(2.4, Distance.UNIT_MILES_P1), CarDistance.of(3860.0))
        assertEquals(DisplayDistance(12.0, Distance.UNIT_MILES), CarDistance.of(19312.0))
        assertEquals(DisplayDistance(50.0, Distance.UNIT_FEET), CarDistance.of(-3.0))
    }

    @Test
    fun `turns map to the host's turn types and arrows`() {
        assertEquals(CarManeuver(Maneuver.TYPE_TURN_NORMAL_RIGHT, Arrow.RIGHT), CarManeuvers.of("TURN", "RIGHT"))
        assertEquals(CarManeuver(Maneuver.TYPE_TURN_SHARP_LEFT, Arrow.SHARP_LEFT), CarManeuvers.of("TURN", "SHARP_LEFT"))
        assertEquals(CarManeuver(Maneuver.TYPE_TURN_SLIGHT_LEFT, Arrow.SLIGHT_LEFT), CarManeuvers.of("END_OF_ROAD", "SLIGHT_LEFT"))
        assertEquals(CarManeuver(Maneuver.TYPE_U_TURN_LEFT, Arrow.U_TURN), CarManeuvers.of("TURN", "UTURN"))
        assertEquals(CarManeuver(Maneuver.TYPE_U_TURN_LEFT, Arrow.U_TURN), CarManeuvers.of("TURN", "U_TURN"))
    }

    @Test
    fun `straight, depart and unknown inputs are straight ahead`() {
        assertEquals(CarManeuver(Maneuver.TYPE_STRAIGHT, Arrow.STRAIGHT), CarManeuvers.of("CONTINUE", "STRAIGHT"))
        assertEquals(CarManeuver(Maneuver.TYPE_STRAIGHT, Arrow.STRAIGHT), CarManeuvers.of(null, null))
        assertEquals(CarManeuver(Maneuver.TYPE_STRAIGHT, Arrow.STRAIGHT), CarManeuvers.of("SOMETHING_NEW", "SIDEWAYS"))
        assertEquals(CarManeuver(Maneuver.TYPE_DEPART, Arrow.STRAIGHT), CarManeuvers.of("DEPART", null))
        assertEquals(CarManeuver(Maneuver.TYPE_NAME_CHANGE, Arrow.STRAIGHT), CarManeuvers.of("NEW_NAME", "STRAIGHT"))
    }

    @Test
    fun `ramps, forks and merges keep their side`() {
        assertEquals(Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT, CarManeuvers.of("OFF_RAMP", "RIGHT").carType)
        assertEquals(Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT, CarManeuvers.of("OFF_RAMP", "SLIGHT_LEFT").carType)
        assertEquals(Maneuver.TYPE_ON_RAMP_NORMAL_LEFT, CarManeuvers.of("ON_RAMP", "LEFT").carType)
        assertEquals(Maneuver.TYPE_FORK_LEFT, CarManeuvers.of("FORK", "SLIGHT_LEFT").carType)
        assertEquals(Maneuver.TYPE_MERGE_RIGHT, CarManeuvers.of("MERGE", "SLIGHT_RIGHT").carType)
        assertEquals(Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED, CarManeuvers.of("MERGE", null).carType)
    }

    @Test
    fun `roundabouts are counter-clockwise and arrival shows the destination side`() {
        assertEquals(Maneuver.TYPE_ROUNDABOUT_ENTER_CCW, CarManeuvers.of("ROUNDABOUT", "RIGHT").carType)
        assertEquals(Maneuver.TYPE_ROUNDABOUT_EXIT_CCW, CarManeuvers.of("EXIT_ROUNDABOUT", "STRAIGHT").carType)
        assertEquals(CarManeuver(Maneuver.TYPE_DESTINATION_LEFT, Arrow.ARRIVE), CarManeuvers.of("ARRIVE", "LEFT"))
        assertEquals(CarManeuver(Maneuver.TYPE_DESTINATION, Arrow.ARRIVE), CarManeuvers.of("ARRIVE", null))
    }

    @Test
    fun `no snapshot is idle and a completed trip is arrived`() {
        assertSame(CarModel.Idle, CarModel.of(null, 0))
        assertSame(CarModel.Arrived, CarModel.of(snapshot(arrived = true), 0))
    }

    @Test
    fun `a trip shows the turn, the next turn and the time left in whole minutes`() {
        val model = CarModel.of(snapshot(next = "Turn left onto Bergen Street", remainingSeconds = 905.0), nowMillis = 1_000_000)
        val display = (model as CarModel.Trip).display
        assertEquals("Turn right onto Atlantic Avenue", display.step.cue)
        assertEquals(CarManeuver(Maneuver.TYPE_TURN_NORMAL_RIGHT, Arrow.RIGHT), display.step.maneuver)
        assertEquals(DisplayDistance(400.0, Distance.UNIT_FEET), display.distanceToStep)
        assertEquals("Turn left onto Bergen Street", display.next?.cue)
        assertEquals(CarManeuvers.of("TURN", "LEFT"), display.next?.maneuver)
        assertEquals(900L, display.remainingSeconds)
        assertEquals(DisplayDistance(5.0, Distance.UNIT_MILES_P1), display.remaining)
        // Arrival is now + 905 s, floored to the minute.
        assertEquals((1_000_000L + 905_000L) / 60_000 * 60_000, display.arrivalMillis)
        assertEquals("Flatbush Avenue", display.road)
    }

    @Test
    fun `the last seconds still read as one minute left`() {
        val display = (CarModel.of(snapshot(remainingSeconds = 12.0), 0) as CarModel.Trip).display
        assertEquals(60L, display.remainingSeconds)
    }

    @Test
    fun `a missing banner falls back to the road name and no next step is none`() {
        val display = (CarModel.of(snapshot(instruction = ""), 0) as CarModel.Trip).display
        assertEquals("Flatbush Avenue", display.step.cue)
        assertNull(display.next)
    }

    @Test
    fun `a second of driving does not change the display`() {
        val a = CarModel.of(snapshot(toManeuver = 120.0, remainingMeters = 8000.0, remainingSeconds = 900.0), 0)
        // A few metres and a second later: same rounded distances and minutes.
        val b = CarModel.of(snapshot(toManeuver = 118.0, remainingMeters = 7990.0, remainingSeconds = 899.0), 1_000)
        assertEquals(a, b)
        val c = CarModel.of(snapshot(toManeuver = 50.0), 0)
        assertNotEquals(a, c)
    }

    @Test
    fun `rerouting is carried through`() {
        assertTrue((CarModel.of(snapshot(rerouting = true), 0) as CarModel.Trip).display.rerouting)
    }
}
