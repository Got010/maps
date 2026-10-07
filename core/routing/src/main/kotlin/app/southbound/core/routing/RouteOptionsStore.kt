package app.southbound.core.routing

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The driver's [RoutePrefs], kept in preferences. One per process, so the options sheet and the router agree. */
class RouteOptionsStore private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("route_options", Context.MODE_PRIVATE)
    private val _options = MutableStateFlow(read())

    val options: StateFlow<RoutePrefs> = _options.asStateFlow()

    fun set(value: RoutePrefs) {
        write(value, durable = false)
    }

    /** [set] with a synchronous commit (an import). Throws [java.io.IOException] if the write fails. */
    fun replace(value: RoutePrefs) {
        write(value, durable = true)
    }

    private fun write(value: RoutePrefs, durable: Boolean) {
        val next = value.sanitized()
        val edit = prefs.edit()
        edit
            .putBoolean("avoid_tolls", next.avoidTolls)
            .putBoolean("avoid_highways", next.avoidHighways)
            .putBoolean("avoid_ferries", next.avoidFerries)
            .putBoolean("avoid_alpr", next.avoidAlpr)
            .putBoolean("avoid_dot_cameras", next.avoidDotCameras)
            .putString("vehicle", next.vehicle.name)
            .putFloat("height_ft", next.heightFt.toFloat())
            .putFloat("width_ft", next.widthFt.toFloat())
            .putFloat("length_ft", next.lengthFt.toFloat())
            .putFloat("weight_tons", next.weightTons.toFloat())
            .putBoolean("walk_avoid_stairs", next.walk.avoidStairs)
            .putBoolean("walk_step_free", next.walk.stepFree)
            .putBoolean("walk_prefer_paths", next.walk.preferPaths)
            .putString("bike_type", next.bike.type.name)
            .putBoolean("bike_avoid_hills", next.bike.avoidHills)
            .putBoolean("bike_avoid_busy_roads", next.bike.avoidBusyRoads)
        if (durable) {
            if (!edit.commit()) throw java.io.IOException("Couldn't save the route options")
        } else {
            edit.apply()
        }
        _options.value = next
    }

    private fun read(): RoutePrefs = RoutePrefs(
        avoidTolls = prefs.getBoolean("avoid_tolls", false),
        avoidHighways = prefs.getBoolean("avoid_highways", false),
        avoidFerries = prefs.getBoolean("avoid_ferries", false),
        avoidAlpr = prefs.getBoolean("avoid_alpr", false),
        avoidDotCameras = prefs.getBoolean("avoid_dot_cameras", false),
        vehicle = VehicleKind.entries.firstOrNull { it.name == prefs.getString("vehicle", null) } ?: VehicleKind.CAR,
        heightFt = prefs.getFloat("height_ft", RoutePrefs.DEFAULT_HEIGHT_FT.toFloat()).toDouble(),
        widthFt = prefs.getFloat("width_ft", RoutePrefs.DEFAULT_WIDTH_FT.toFloat()).toDouble(),
        lengthFt = prefs.getFloat("length_ft", RoutePrefs.DEFAULT_LENGTH_FT.toFloat()).toDouble(),
        weightTons = prefs.getFloat("weight_tons", RoutePrefs.DEFAULT_WEIGHT_TONS.toFloat()).toDouble(),
        walk = WalkPrefs(
            avoidStairs = prefs.getBoolean("walk_avoid_stairs", false),
            stepFree = prefs.getBoolean("walk_step_free", false),
            preferPaths = prefs.getBoolean("walk_prefer_paths", false),
        ),
        bike = BikePrefs(
            type = BikeType.entries.firstOrNull { it.name == prefs.getString("bike_type", null) } ?: BikeType.HYBRID,
            avoidHills = prefs.getBoolean("bike_avoid_hills", false),
            avoidBusyRoads = prefs.getBoolean("bike_avoid_busy_roads", false),
        ),
    ).sanitized()

    companion object {
        @Volatile
        private var instance: RouteOptionsStore? = null

        fun get(context: Context): RouteOptionsStore =
            instance ?: synchronized(this) { instance ?: RouteOptionsStore(context.applicationContext).also { instance = it } }
    }
}
