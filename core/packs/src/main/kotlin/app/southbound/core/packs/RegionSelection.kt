package app.southbound.core.packs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which basemap regions the user wants on the phone, saved in preferences.
 * The updater downloads a basemap for each, plus the statewide routing and
 * search packs and NYC transit. One per process ([get]), so a change in
 * Settings reaches a running update round.
 */
class RegionSelection private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("packs", Context.MODE_PRIVATE)
    private val _regions = MutableStateFlow(read())

    /** Region ids of the wanted basemaps. */
    val regions: StateFlow<Set<String>> = _regions.asStateFlow()

    fun isSelected(region: String): Boolean = region in _regions.value

    fun set(region: String, selected: Boolean) {
        val next = if (selected) _regions.value + region else _regions.value - region
        prefs.edit().putStringSet(KEY, next).apply()
        _regions.value = next
    }

    /**
     * Makes [selected] the wanted basemap regions among the ones Southbound knows, in one synchronous commit (an
     * import); ids it does not know are left as they are. Throws [java.io.IOException] if the write fails.
     */
    @Synchronized
    fun replaceBasemaps(selected: Set<String>) {
        val known = Regions.basemaps.map { it.id }.toSet()
        val next = (_regions.value - known) + (selected intersect known)
        if (!prefs.edit().putStringSet(KEY, next).commit()) throw java.io.IOException("Couldn't save the map regions")
        _regions.value = next
    }

    private fun read(): Set<String> = prefs.getStringSet(KEY, null)?.toSet() ?: DEFAULT

    companion object {
        private const val KEY = "regions"

        /** Phones from before regions existed have the NYC packs, so keep them current. */
        val DEFAULT: Set<String> = setOf(PackStore.DEFAULT_REGION)

        @Volatile
        private var instance: RegionSelection? = null

        fun get(context: Context): RegionSelection =
            instance ?: synchronized(this) { instance ?: RegionSelection(context.applicationContext).also { instance = it } }
    }
}
