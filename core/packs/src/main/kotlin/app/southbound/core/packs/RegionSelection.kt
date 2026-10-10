package app.southbound.core.packs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which basemap regions the user wants on the phone, saved in preferences.
 * The updater downloads a basemap for each, plus the routing and search
 * packs that serve them and NYC transit. One per process ([get]), so a change
 * in Settings reaches a running update round.
 *
 * Some may be trip regions ([tripRegions], see [TripRegions]): fetched from
 * the route preview for one trip, and removed again after it. The rest are the
 * user's own ([own]), ticked in Settings; only those go into an export.
 */
class RegionSelection private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("packs", Context.MODE_PRIVATE)
    private val _regions = MutableStateFlow(read())
    private val _trip = MutableStateFlow(readSet(KEY_TRIP) intersect _regions.value)

    /** Region ids of the wanted basemaps: the user's own and the trip regions. */
    val regions: StateFlow<Set<String>> = _regions.asStateFlow()

    /** The wanted regions fetched for a trip, which go again once it's over. */
    val tripRegions: StateFlow<Set<String>> = _trip.asStateFlow()

    /** The user's own regions: the wanted ones that aren't trip regions. */
    val own: Set<String> get() = _regions.value - _trip.value

    /** The trip regions the phone has been in since they came. */
    val tripVisited: Set<String> get() = readSet(KEY_VISITED) intersect _trip.value

    fun isSelected(region: String): Boolean = region in _regions.value

    /** A tick or untick in Settings: a ticked region is the user's own, even if it came for a trip. */
    @Synchronized
    fun set(region: String, selected: Boolean) {
        val next = if (selected) _regions.value + region else _regions.value - region
        save(next, _trip.value - region, readSet(KEY_VISITED) - region)
    }

    /** Fetches [ids] for a trip: the ones not wanted yet become trip regions. */
    @Synchronized
    fun addForTrip(ids: Collection<String>) {
        val added = ids.toSet() - _regions.value
        if (added.isEmpty()) return
        save(_regions.value + added, _trip.value + added, readSet(KEY_VISITED) - added)
    }

    /** Notes that the phone has been in these trip regions. */
    @Synchronized
    fun markVisited(ids: Set<String>) {
        val next = readSet(KEY_VISITED) + (ids intersect _trip.value)
        prefs.edit().putStringSet(KEY_VISITED, next).apply()
    }

    /** Removes these trip regions (the trip is over); the caller deletes their maps and starts an update round. */
    @Synchronized
    fun dropTrip(ids: Set<String>) {
        val gone = ids intersect _trip.value
        if (gone.isEmpty()) return
        save(_regions.value - gone, _trip.value - gone, readSet(KEY_VISITED) - gone)
    }

    /**
     * Makes [selected] the wanted basemap regions among the ones Southbound knows, in one synchronous commit (an
     * import); ids it does not know are left as they are. Every region it keeps is the user's own: trip regions
     * not in [selected] go. Throws [java.io.IOException] if the write fails.
     */
    @Synchronized
    fun replaceBasemaps(selected: Set<String>) {
        val known = Regions.basemaps.map { it.id }.toSet()
        val next = (_regions.value - known) + (selected intersect known)
        val trip = _trip.value - known
        val ok = prefs.edit()
            .putStringSet(KEY, next)
            .putStringSet(KEY_TRIP, trip)
            .putStringSet(KEY_VISITED, readSet(KEY_VISITED) intersect trip)
            .commit()
        if (!ok) throw java.io.IOException("Couldn't save the map regions")
        _regions.value = next
        _trip.value = trip
    }

    private fun save(regions: Set<String>, trip: Set<String>, visited: Set<String>) {
        prefs.edit()
            .putStringSet(KEY, regions)
            .putStringSet(KEY_TRIP, trip)
            .putStringSet(KEY_VISITED, visited intersect trip)
            .apply()
        _regions.value = regions
        _trip.value = trip intersect regions
    }

    private fun read(): Set<String> = prefs.getStringSet(KEY, null)?.toSet() ?: DEFAULT

    private fun readSet(key: String): Set<String> = prefs.getStringSet(key, null)?.toSet().orEmpty()

    companion object {
        private const val KEY = "regions"
        private const val KEY_TRIP = "trip_regions"
        private const val KEY_VISITED = "trip_visited"

        /** Phones from before regions existed have the NYC packs, so keep them current. */
        val DEFAULT: Set<String> = setOf(PackStore.DEFAULT_REGION)

        @Volatile
        private var instance: RegionSelection? = null

        fun get(context: Context): RegionSelection =
            instance ?: synchronized(this) { instance ?: RegionSelection(context.applicationContext).also { instance = it } }
    }
}
