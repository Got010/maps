package app.southbound.core.packs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which downloadable voices (voice-<id>.zip packs) the user wants, saved in
 * preferences beside [RegionSelection]. Settings > Voice writes it when a
 * voice is picked; the updater fetches a pack only for the voices here. The
 * bundled voice needs no pack and is never listed.
 */
class VoiceSelection private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("packs", Context.MODE_PRIVATE)
    private val _voices = MutableStateFlow(prefs.getStringSet(KEY, null)?.toSet() ?: emptySet())

    /** Voice ids whose packs should be on the phone. */
    val voices: StateFlow<Set<String>> = _voices.asStateFlow()

    /** Keeps exactly [voice] (or none): one downloadable voice at a time is plenty. */
    fun choose(voice: String?) {
        val next = setOfNotNull(voice)
        prefs.edit().putStringSet(KEY, next).apply()
        _voices.value = next
    }

    /** [choose] with a synchronous commit (an import). Throws [java.io.IOException] if the write fails. */
    fun replace(voice: String?) {
        val next = setOfNotNull(voice)
        if (!prefs.edit().putStringSet(KEY, next).commit()) throw java.io.IOException("Couldn't save the voice choice")
        _voices.value = next
    }

    companion object {
        private const val KEY = "voices"

        @Volatile
        private var instance: VoiceSelection? = null

        fun get(context: Context): VoiceSelection =
            instance ?: synchronized(this) { instance ?: VoiceSelection(context.applicationContext).also { instance = it } }
    }
}
