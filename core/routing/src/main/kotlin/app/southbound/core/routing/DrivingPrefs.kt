package app.southbound.core.routing

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small driving settings kept in preferences (Phase 6). One per process, so Settings and the map agree. */
class DrivingPrefs private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("driving", Context.MODE_PRIVATE)
    private val _nightBySunset = MutableStateFlow(prefs.getBoolean(KEY_NIGHT, true))

    /** While navigating, switch to the dark map and overlay after sunset even when the phone is in light theme. */
    val nightBySunset: StateFlow<Boolean> = _nightBySunset.asStateFlow()

    fun setNightBySunset(on: Boolean) {
        prefs.edit().putBoolean(KEY_NIGHT, on).apply()
        _nightBySunset.value = on
    }

    /** [setNightBySunset] with a synchronous commit (an import). Throws [java.io.IOException] if the write fails. */
    fun replaceNightBySunset(on: Boolean) {
        if (!prefs.edit().putBoolean(KEY_NIGHT, on).commit()) throw java.io.IOException("Couldn't save the driving options")
        _nightBySunset.value = on
    }

    companion object {
        private const val KEY_NIGHT = "night_by_sunset"

        @Volatile
        private var instance: DrivingPrefs? = null

        fun get(context: Context): DrivingPrefs =
            instance ?: synchronized(this) { instance ?: DrivingPrefs(context.applicationContext).also { instance = it } }
    }
}
