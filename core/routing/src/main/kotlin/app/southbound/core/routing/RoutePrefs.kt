package app.southbound.core.routing

import java.util.Locale

/** What the driver is in: a car, or something tall, wide, long or heavy. */
enum class VehicleKind { CAR, TRUCK }

/**
 * The driver's route preferences (Phase 6). Dimensions are in the units
 * people know for vehicles in the US: feet and short tons. Valhalla wants
 * meters and metric tonnes, so [costingOptionsJson] converts.
 */
data class RoutePrefs(
    val avoidTolls: Boolean = false,
    val avoidHighways: Boolean = false,
    val avoidFerries: Boolean = false,
    val vehicle: VehicleKind = VehicleKind.CAR,
    val heightFt: Double = DEFAULT_HEIGHT_FT,
    val widthFt: Double = DEFAULT_WIDTH_FT,
    val lengthFt: Double = DEFAULT_LENGTH_FT,
    val weightTons: Double = DEFAULT_WEIGHT_TONS,
) {
    /** The Valhalla costing model this profile routes with. */
    val costing: String get() = if (vehicle == VehicleKind.TRUCK) "truck" else TravelMode.DRIVE.valhallaCosting

    /** True when every option is at its default, so requests stay as Ferrostar built them. */
    val isDefault: Boolean get() = costingOptionsJson() == null

    /**
     * The body of `costing_options.<costing>`, or null when nothing differs
     * from a plain car trip. Valhalla's 0 for use_tolls, use_highways and
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
