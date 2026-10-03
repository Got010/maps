package app.southbound.feature.car

import androidx.car.app.navigation.model.Maneuver

/** The arrow drawn for a manoeuvre (res/drawable/car_arrow_*). */
internal enum class Arrow { STRAIGHT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, U_TURN, SHARP_LEFT, LEFT, SLIGHT_LEFT, ARRIVE }

/** A manoeuvre for the car: the host's type (it may redraw its own icon) and our arrow. */
internal data class CarManeuver(val carType: Int, val arrow: Arrow)

/**
 * Ferrostar's manoeuvre (an OSRM type and modifier) as a Car App Library [Maneuver] type.
 *
 * The inputs are the names of Ferrostar's ManeuverType and ManeuverModifier values, compared with
 * underscores dropped so "U_TURN" and "UTURN", "OFF_RAMP" and "OFFRAMP" both match. Anything
 * unknown becomes "straight ahead" rather than an error. Roundabouts are counter-clockwise
 * (US traffic), and U-turns are left U-turns.
 */
internal object CarManeuvers {

    fun of(type: String?, modifier: String?): CarManeuver {
        val kind = normalise(type)
        val mod = normalise(modifier)
        val arrow = arrowFor(mod)
        val left = mod == "LEFT" || mod == "SLIGHTLEFT" || mod == "SHARPLEFT" || mod == "UTURN"
        val right = mod == "RIGHT" || mod == "SLIGHTRIGHT" || mod == "SHARPRIGHT"
        return when (kind) {
            "ARRIVE" -> CarManeuver(
                when {
                    left -> Maneuver.TYPE_DESTINATION_LEFT
                    right -> Maneuver.TYPE_DESTINATION_RIGHT
                    else -> Maneuver.TYPE_DESTINATION
                },
                Arrow.ARRIVE,
            )
            "DEPART" -> CarManeuver(Maneuver.TYPE_DEPART, Arrow.STRAIGHT)
            "ROUNDABOUT", "ROTARY", "ROUNDABOUTTURN" -> CarManeuver(Maneuver.TYPE_ROUNDABOUT_ENTER_CCW, arrow)
            "EXITROUNDABOUT", "EXITROTARY" -> CarManeuver(Maneuver.TYPE_ROUNDABOUT_EXIT_CCW, arrow)
            "FORK" -> CarManeuver(
                when {
                    left -> Maneuver.TYPE_FORK_LEFT
                    right -> Maneuver.TYPE_FORK_RIGHT
                    else -> Maneuver.TYPE_STRAIGHT
                },
                arrow,
            )
            "MERGE" -> CarManeuver(
                when {
                    left -> Maneuver.TYPE_MERGE_LEFT
                    right -> Maneuver.TYPE_MERGE_RIGHT
                    else -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
                },
                arrow,
            )
            "OFFRAMP" -> CarManeuver(
                when {
                    mod == "SLIGHTLEFT" -> Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT
                    mod == "SLIGHTRIGHT" -> Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT
                    left -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
                    right -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
                    else -> Maneuver.TYPE_STRAIGHT
                },
                arrow,
            )
            "ONRAMP" -> CarManeuver(
                when {
                    left -> Maneuver.TYPE_ON_RAMP_NORMAL_LEFT
                    right -> Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
                    else -> Maneuver.TYPE_STRAIGHT
                },
                arrow,
            )
            "NEWNAME" -> CarManeuver(if (left || right) turn(mod) else Maneuver.TYPE_NAME_CHANGE, arrow)
            // "turn", "end of road", "continue", "notification": the modifier says which way.
            else -> CarManeuver(turn(mod), arrow)
        }
    }

    private fun turn(modifier: String?): Int = when (modifier) {
        "UTURN" -> Maneuver.TYPE_U_TURN_LEFT
        "SHARPLEFT" -> Maneuver.TYPE_TURN_SHARP_LEFT
        "LEFT" -> Maneuver.TYPE_TURN_NORMAL_LEFT
        "SLIGHTLEFT" -> Maneuver.TYPE_TURN_SLIGHT_LEFT
        "SHARPRIGHT" -> Maneuver.TYPE_TURN_SHARP_RIGHT
        "RIGHT" -> Maneuver.TYPE_TURN_NORMAL_RIGHT
        "SLIGHTRIGHT" -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
        else -> Maneuver.TYPE_STRAIGHT
    }

    private fun arrowFor(modifier: String?): Arrow = when (modifier) {
        "UTURN" -> Arrow.U_TURN
        "SHARPLEFT" -> Arrow.SHARP_LEFT
        "LEFT" -> Arrow.LEFT
        "SLIGHTLEFT" -> Arrow.SLIGHT_LEFT
        "SHARPRIGHT" -> Arrow.SHARP_RIGHT
        "RIGHT" -> Arrow.RIGHT
        "SLIGHTRIGHT" -> Arrow.SLIGHT_RIGHT
        else -> Arrow.STRAIGHT
    }

    private fun normalise(name: String?): String? = name?.replace("_", "")?.uppercase()
}
