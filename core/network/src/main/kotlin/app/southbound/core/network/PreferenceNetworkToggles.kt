package app.southbound.core.network

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's network toggles, saved on the phone. Reads are synchronous so
 * [NetworkGate] can check them on any thread. One per process ([get]), so a
 * change in Settings reaches background work at once.
 */
class PreferenceNetworkToggles private constructor(context: Context) : NetworkToggles {

    private val prefs = context.applicationContext.getSharedPreferences("network", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())

    /** Every feature's current setting, for Settings. */
    val state: StateFlow<Map<NetworkFeature, Boolean>> = _state.asStateFlow()

    override fun isEnabled(feature: NetworkFeature): Boolean = _state.value.getValue(feature)

    fun set(feature: NetworkFeature, enabled: Boolean) {
        prefs.edit().putBoolean(feature.name, enabled).apply()
        _state.value = read()
    }

    /**
     * Sets every toggle in one synchronous commit (an import: the caller must know the write reached the disk before
     * it forgets the import). Throws [java.io.IOException] if the commit fails, leaving the toggles as they were.
     */
    @Synchronized
    fun replaceAll(values: Map<NetworkFeature, Boolean>) {
        val edit = prefs.edit()
        for (feature in NetworkFeature.entries) edit.putBoolean(feature.name, values[feature] ?: feature.enabledByDefault)
        if (!edit.commit()) throw java.io.IOException("Couldn't save the network toggles")
        _state.value = read()
    }

    private fun read(): Map<NetworkFeature, Boolean> =
        NetworkFeature.entries.associateWith { prefs.getBoolean(it.name, it.enabledByDefault) }

    companion object {
        @Volatile
        private var instance: PreferenceNetworkToggles? = null

        fun get(context: Context): PreferenceNetworkToggles =
            instance ?: synchronized(this) { instance ?: PreferenceNetworkToggles(context.applicationContext).also { instance = it } }
    }
}
