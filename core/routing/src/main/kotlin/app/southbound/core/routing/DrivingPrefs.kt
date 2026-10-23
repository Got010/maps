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

    private val _searchAtBottom = MutableStateFlow(prefs.getBoolean(KEY_SEARCH_AT_BOTTOM, true))

    /**
     * The map's search bar, chips and banners at the bottom of the screen, within the thumb's reach; off puts them at
     * the top, as before October 2026 (docs/bottom-search.md).
     */
    val searchAtBottom: StateFlow<Boolean> = _searchAtBottom.asStateFlow()

    fun setSearchAtBottom(on: Boolean) {
        prefs.edit().putBoolean(KEY_SEARCH_AT_BOTTOM, on).apply()
        _searchAtBottom.value = on
    }

    private val _stateLineCards = MutableStateFlow(prefs.getBoolean(KEY_STATE_LINE_CARDS, true))

    /**
     * State-line cards (core/states): a card of what changes when the route, or the phone, crosses into another state.
     * On by default; off hides the route sheet's "Crosses into" lines and the card that opens on a crossing.
     */
    val stateLineCards: StateFlow<Boolean> = _stateLineCards.asStateFlow()

    fun setStateLineCards(on: Boolean) {
        prefs.edit().putBoolean(KEY_STATE_LINE_CARDS, on).apply()
        _stateLineCards.value = on
    }

    /** All the settings at once, with a synchronous commit (an import). Throws [java.io.IOException] if the write fails. */
    fun replace(nightBySunset: Boolean, searchAtBottom: Boolean, stateLineCards: Boolean) {
        val saved = prefs.edit()
            .putBoolean(KEY_NIGHT, nightBySunset)
            .putBoolean(KEY_SEARCH_AT_BOTTOM, searchAtBottom)
            .putBoolean(KEY_STATE_LINE_CARDS, stateLineCards)
            .commit()
        if (!saved) throw java.io.IOException("Couldn't save the driving options")
        _nightBySunset.value = nightBySunset
        _searchAtBottom.value = searchAtBottom
        _stateLineCards.value = stateLineCards
    }

    companion object {
        private const val KEY_NIGHT = "night_by_sunset"
        private const val KEY_SEARCH_AT_BOTTOM = "search_at_bottom"
        private const val KEY_STATE_LINE_CARDS = "state_line_cards"

        @Volatile
        private var instance: DrivingPrefs? = null

        fun get(context: Context): DrivingPrefs =
            instance ?: synchronized(this) { instance ?: DrivingPrefs(context.applicationContext).also { instance = it } }
    }
}
