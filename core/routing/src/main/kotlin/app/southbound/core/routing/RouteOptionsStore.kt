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
        val next = value.sanitized()
        prefs.edit()
            .putBoolean("avoid_tolls", next.avoidTolls)
            .putBoolean("avoid_highways", next.avoidHighways)
            .putBoolean("avoid_ferries", next.avoidFerries)
            .putString("vehicle", next.vehicle.name)
            .putFloat("height_ft", next.heightFt.toFloat())
            .putFloat("width_ft", next.widthFt.toFloat())
            .putFloat("length_ft", next.lengthFt.toFloat())
            .putFloat("weight_tons", next.weightTons.toFloat())
            .apply()
        _options.value = next
    }

    private fun read(): RoutePrefs = RoutePrefs(
        avoidTolls = prefs.getBoolean("avoid_tolls", false),
        avoidHighways = prefs.getBoolean("avoid_highways", false),
        avoidFerries = prefs.getBoolean("avoid_ferries", false),
        vehicle = VehicleKind.entries.firstOrNull { it.name == prefs.getString("vehicle", null) } ?: VehicleKind.CAR,
        heightFt = prefs.getFloat("height_ft", RoutePrefs.DEFAULT_HEIGHT_FT.toFloat()).toDouble(),
        widthFt = prefs.getFloat("width_ft", RoutePrefs.DEFAULT_WIDTH_FT.toFloat()).toDouble(),
        lengthFt = prefs.getFloat("length_ft", RoutePrefs.DEFAULT_LENGTH_FT.toFloat()).toDouble(),
        weightTons = prefs.getFloat("weight_tons", RoutePrefs.DEFAULT_WEIGHT_TONS.toFloat()).toDouble(),
    ).sanitized()

    companion object {
        @Volatile
        private var instance: RouteOptionsStore? = null

        fun get(context: Context): RouteOptionsStore =
            instance ?: synchronized(this) { instance ?: RouteOptionsStore(context.applicationContext).also { instance = it } }
    }
}
