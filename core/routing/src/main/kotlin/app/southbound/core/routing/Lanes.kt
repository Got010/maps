package app.southbound.core.routing

/** An arrow painted on a lane (OSM `turn:lanes`), as Valhalla names it in its OSRM output. */
enum class LaneDirection {
    UTURN, SHARP_LEFT, LEFT, SLIGHT_LEFT, STRAIGHT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT;

    companion object {
        /**
         * "sharp left" -> [SHARP_LEFT]. Valhalla writes "uturn", "sharp left",
         * "left", "slight left", "straight", "slight right", "right" and
         * "sharp right"; underscores, dashes and "through" are accepted too.
         * Null for "none", "" and anything else.
         */
        fun parse(text: String?): LaneDirection? =
            when (text?.trim()?.lowercase()?.replace('_', ' ')?.replace('-', ' ')) {
                "uturn", "u turn", "reverse" -> UTURN
                "sharp left" -> SHARP_LEFT
                "left" -> LEFT
                "slight left" -> SLIGHT_LEFT
                "straight", "through" -> STRAIGHT
                "slight right" -> SLIGHT_RIGHT
                "right" -> RIGHT
                "sharp right" -> SHARP_RIGHT
                else -> null
            }
    }
}

/**
 * One lane at the approach to a maneuver, left to right in its list.
 *
 * [active]: Valhalla recommends it for this maneuver (and, when the next one
 * comes soon, for that one too). [valid]: it can be used for this maneuver;
 * every active lane is valid. [use]: which of its arrows the maneuver
 * follows, for valid lanes.
 */
data class Lane(
    val directions: List<LaneDirection>,
    val active: Boolean,
    val valid: Boolean,
    val use: LaneDirection? = null,
)

/**
 * Lane guidance for a maneuver.
 *
 * Where it comes from: Valhalla works out which of the approach road's
 * `turn:lanes` serve each maneuver (odin `ProcessTurnLanes`) and writes them
 * into its OSRM response twice: per intersection (`lanes[]` with
 * `indications`, `valid`, `active`, `valid_indication`) and as a banner
 * instruction's `sub` (components of type "lane" with `directions`,
 * `active`, `active_direction`). Ferrostar 0.57 reads the second into
 * `VisualInstruction.subContent.laneInfo` (never into `primaryContent`), on
 * the step whose banners announce the maneuver, i.e. the step ending at it.
 * [StepLanes] reads it from there.
 */
object Lanes {

    /**
     * A lane from Valhalla's banner `sub` component (Ferrostar's `LaneInfo`):
     * active lanes have `active`; valid ones that aren't active still carry
     * `active_direction` (Valhalla leaves it off invalid lanes only).
     */
    fun fromBanner(active: Boolean, directions: List<String>, activeDirection: String?): Lane {
        val use = LaneDirection.parse(activeDirection)
        return Lane(
            directions = directions.mapNotNull { LaneDirection.parse(it) },
            active = active,
            valid = active || !activeDirection.isNullOrBlank(),
            use = use,
        )
    }

    /** A lane from an OSRM intersection's `lanes[]` entry. */
    fun fromIntersection(indications: List<String>, valid: Boolean, active: Boolean?, validIndication: String?): Lane =
        Lane(
            directions = indications.mapNotNull { LaneDirection.parse(it) },
            active = active == true,
            valid = valid || active == true,
            use = LaneDirection.parse(validIndication),
        )

    /**
     * The lanes to use, left to right by index: the active ones when any
     * lane is active (Valhalla's pick for this maneuver), else the valid ones.
     */
    fun toUse(lanes: List<Lane>): List<Int> {
        val active = lanes.indices.filter { lanes[it].active }
        return active.ifEmpty { lanes.indices.filter { lanes[it].valid } }
    }

    /** True when there is something to draw: two or more lanes, some of them to use. */
    fun worthShowing(lanes: List<Lane>): Boolean = lanes.size >= 2 && toUse(lanes).isNotEmpty()
}
