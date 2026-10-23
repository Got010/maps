package app.southbound.core.packs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the user wants the road pack (road-us.sqlite, [PackKind.ROAD]) on the phone, saved in preferences
 * beside [VoiceSelection]. Off until Settings > Maps turns it on, so no phone downloads the pack unasked; the
 * updater fetches it only while this is on, and drops an installed copy while it is off.
 */
class RoadSelection private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("packs", Context.MODE_PRIVATE)
    private val _wanted = MutableStateFlow(prefs.getBoolean(KEY, false))

    /** True when the Road pack should be on the phone. */
    val wanted: StateFlow<Boolean> = _wanted.asStateFlow()

    fun choose(wanted: Boolean) {
        prefs.edit().putBoolean(KEY, wanted).apply()
        _wanted.value = wanted
    }

    /** [choose] with a synchronous commit. Throws [java.io.IOException] if the write fails. */
    fun replace(wanted: Boolean) {
        if (!prefs.edit().putBoolean(KEY, wanted).commit()) throw java.io.IOException("Couldn't save the Road choice")
        _wanted.value = wanted
    }

    companion object {
        private const val KEY = "road"

        @Volatile
        private var instance: RoadSelection? = null

        fun get(context: Context): RoadSelection =
            instance ?: synchronized(this) { instance ?: RoadSelection(context.applicationContext).also { instance = it } }
    }
}
