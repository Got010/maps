package app.southbound.core.routing

import java.util.Locale

/** What the driver is in: a car, or something tall, wide, long or heavy. */
enum class VehicleKind { CAR, TRUCK }

/** Which kind of bicycle the rider is on; Valhalla's `bicycle_type` tunes speed and surface tolerance. */
enum class BikeType(val valhalla: String) { ROAD("road"), HYBRID("hybrid"), MOUNTAIN("mountain") }

/**
 * Walking preferences (Phase 7), sent as `costing_options.pedestrian`.
 *
 * - [avoidStairs]: `step_penalty` 600 s (Valhalla's default is 30 s) on every
 *   transition onto steps. A penalty, not a ban: a flight of stairs that saves
 *   more than ten minutes of detour can still win.
 * - [stepFree]: Valhalla's wheelchair pedestrian type (`"type":"wheelchair"`,
 *   in the 3.6 source but missing from its API reference): wheelchair access
 *   only, compacted surfaces or better, 4 km/h, a 12 percent grade cap and a
 *   600 s step penalty by default. We send the step penalty and `use_hills` 0.1
 *   (flat streets) explicitly and `use_tracks` 0 as well. The grade cap and
 *   `use_hills` need elevation in the routing pack and do nothing until it has
 *   it (docs/elevation-spike.md).
 * - [preferPaths]: `walkway_factor` 0.5 and `sidewalk_factor` 0.7 (below 1 is
 *   favoured) so footways and streets with sidewalks beat plain roads.
 */
data class WalkPrefs(
    val avoidStairs: Boolean = false,
    val stepFree: Boolean = false,
    val preferPaths: Boolean = false,
) {
    val isDefault: Boolean get() = !avoidStairs && !stepFree && !preferPaths

    /** The body of `costing_options.pedestrian`, or null when nothing differs from Valhalla's defaults. */
    fun costingOptionsJson(): String? {
        val parts = buildList {
            if (stepFree) add(""""type":"wheelchair"""")
            if (stepFree || avoidStairs) add(""""step_penalty":$STEP_PENALTY_SECONDS""")
            if (preferPaths) {
                add(""""walkway_factor":0.5""")
                add(""""sidewalk_factor":0.7""")
            }
            if (stepFree) {
                add(""""use_hills":0.1""")
                add(""""use_tracks":0""")
            }
        }
        return if (parts.isEmpty()) null else parts.joinToString(",", prefix = "{", postfix = "}")
    }

    companion object {
        const val STEP_PENALTY_SECONDS = 600
    }
}

/**
 * Cycling preferences (Phase 7), sent as `costing_options.bicycle`. Unlike
 * walking, cycling always sends options: the app's own defaults (a hybrid
 * bike, `use_roads` 0.3 so bike lanes and quiet streets win over Valhalla's
 * 0.5) are part of the request. [avoidHills] sets `use_hills` 0.1 (default
 * 0.5, and it only helps where the routing pack has elevation) and
 * [avoidBusyRoads] drops `use_roads` to 0.1. Ferries follow
 * [RoutePrefs.avoidFerries].
 */
data class BikePrefs(
    val type: BikeType = BikeType.HYBRID,
    val avoidHills: Boolean = false,
    val avoidBusyRoads: Boolean = false,
) {
    val isDefault: Boolean get() = this == BikePrefs()

    fun costingOptionsJson(avoidFerries: Boolean): String {
        val parts = buildList {
            add(""""bicycle_type":"${type.valhalla}"""")
            add(""""use_roads":${if (avoidBusyRoads) "0.1" else DEFAULT_USE_ROADS}""")
            if (avoidHills) add(""""use_hills":0.1""")
            if (avoidFerries) add(""""use_ferry":0""")
        }
        return parts.joinToString(",", prefix = "{", postfix = "}")
    }

    companion object {
        const val DEFAULT_USE_ROADS = "0.3"
    }
}

/**
 * The driver's route preferences (Phase 6), with walking and cycling
 * preferences (Phase 7) alongside. Dimensions are in the units
 * people know for vehicles in the US: feet and short tons. Valhalla wants
 * meters and metric tonnes, so [costingOptionsJson] converts.
 */
data class RoutePrefs(
    val avoidTolls: Boolean = false,
    val avoidHighways: Boolean = false,
    val avoidFerries: Boolean = false,
    /**
     * Steer clear of licence-plate readers (Flock and others, as DeFlock maps
     * them). Not a Valhalla costing option: the app squares off the cameras
     * on each route and routes again (see [CameraAvoidance]).
     */
    val avoidAlpr: Boolean = false,
    /**
     * Steer clear of state DOT traffic cameras (511NY's list, in the search
     * pack), squared off the same way as [avoidAlpr].
     */
    val avoidDotCameras: Boolean = false,
    val vehicle: VehicleKind = VehicleKind.CAR,
    val heightFt: Double = DEFAULT_HEIGHT_FT,
    val widthFt: Double = DEFAULT_WIDTH_FT,
    val lengthFt: Double = DEFAULT_LENGTH_FT,
    val weightTons: Double = DEFAULT_WEIGHT_TONS,
    /** Used only for [TravelMode.WALK]. */
    val walk: WalkPrefs = WalkPrefs(),
    /** Used only for [TravelMode.BIKE]; [avoidFerries] applies to cycling too. */
    val bike: BikePrefs = BikePrefs(),
) {
    /** The Valhalla costing model this profile routes with. */
    val costing: String get() = if (vehicle == VehicleKind.TRUCK) "truck" else TravelMode.DRIVE.valhallaCosting

    /** True when every driving option is at its default, so requests stay as Ferrostar built them. */
    val isDefault: Boolean get() = costingOptionsJson() == null && !avoidAlpr && !avoidDotCameras

    /** [isDefault] for the options that apply to [mode] only, e.g. a walk preference doesn't light up the Drive chip. */
    fun isDefaultFor(mode: TravelMode): Boolean = when (mode) {
        TravelMode.DRIVE -> isDefault
        TravelMode.WALK -> walk.isDefault
        TravelMode.BIKE -> bike.isDefault && !avoidFerries
    }

    /** `costing_options.bicycle`, always present (see [BikePrefs]). */
    fun bikeCostingOptionsJson(): String = bike.costingOptionsJson(avoidFerries)

    /**
     * The body of `costing_options.<costing>`, or null when nothing differs
     * from a plain car trip (driving only; see [WalkPrefs] and [BikePrefs]).
     * Valhalla's 0 for use_tolls, use_highways and
     * use_ferry means avoid as far as possible; unset keeps its own default.
     */
    fun costingOptionsJson(): String? {
        val parts = buildList {
            if (avoidTolls) add(""""use_tolls":0""")
            if (avoidHighways) add(""""use_highways":0""")
            if (avoidFerries) add(""""use_ferry":0""")
            if (vehicle == VehicleKind.TRUCK) {
                add(""""height":${num(heightFt * METERS_PER_FOOT)}""")
                add(""""width":${num(widthFt * METERS_PER_FOOT)}""")
                add(""""length":${num(lengthFt * METERS_PER_FOOT)}""")
                add(""""weight":${num(weightTons * TONNES_PER_SHORT_TON)}""")
            }
        }
        return if (parts.isEmpty()) null else parts.joinToString(",", prefix = "{", postfix = "}")
    }

    /** Keeps the dimensions inside what Valhalla accepts (and what a road can carry). */
    fun sanitized(): RoutePrefs = copy(
        heightFt = heightFt.coerceIn(3.0, 16.0),
        widthFt = widthFt.coerceIn(3.0, 12.0),
        lengthFt = lengthFt.coerceIn(5.0, 80.0),
        weightTons = weightTons.coerceIn(0.5, 80.0),
    )

    private fun num(v: Double) = String.format(Locale.ROOT, "%.2f", v)

    companion object {
        val DEFAULT = RoutePrefs()

        // Typical US semi-trailer-ish starting values for the truck fields (Valhalla's own defaults, in feet and tons).
        const val DEFAULT_HEIGHT_FT = 13.5
        const val DEFAULT_WIDTH_FT = 8.5
        const val DEFAULT_LENGTH_FT = 71.0
        const val DEFAULT_WEIGHT_TONS = 24.0

        const val METERS_PER_FOOT = 0.3048
        const val TONNES_PER_SHORT_TON = 0.90718474
    }
}
