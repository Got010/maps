package app.southbound.feature.explore

import android.content.Context
import app.southbound.core.packs.PackKind
import app.southbound.core.packs.PackStatus
import app.southbound.core.packs.PackStore
import app.southbound.core.routing.Navigator
import app.southbound.core.routing.OfflineRouter
import app.southbound.core.search.SearchIndex
import app.southbound.core.voice.GuidanceVoice
import app.southbound.core.voice.VoiceSettings
import app.southbound.core.voice.VoiceSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.io.File

/**
 * The app's long-lived pieces, one per process: packs, search, routing,
 * navigation and voice. Navigation outlives any one screen (and keeps going
 * in a foreground service), so it lives here rather than in a composable.
 *
 * The open* functions do file and native work; call them off the main thread.
 */
class ExploreServices private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val packStore = PackStore(appContext.filesDir, appContext.getExternalFilesDir(null))
    val voiceStore = VoiceSettingsStore(appContext)
    val voiceSettings: StateFlow<VoiceSettings> =
        voiceStore.settings.stateIn(scope, SharingStarted.Eagerly, VoiceSettings())
    val voice = GuidanceVoice(appContext, voiceSettings)

    private var search: Opened<SearchIndex>? = null
    private var routing: Opened<Pair<OfflineRouter, Navigator>>? = null

    /** The search index, reopened if a newer search pack was imported. Null without one. */
    @Synchronized
    fun openSearch(region: String = PackStore.DEFAULT_REGION): SearchIndex? {
        val file = installed(PackKind.SEARCH, region) ?: return null
        search?.takeIf { it.matches(file) }?.let { return it.value }
        search?.value?.close()
        search = null
        return runCatching { SearchIndex.open(file) }.getOrNull()
            ?.also { search = Opened(file, it) }
    }

    /**
     * The navigator for the installed routing pack. A newer pack takes over
     * only between trips, never mid-navigation.
     */
    @Synchronized
    fun openNavigator(region: String = PackStore.DEFAULT_REGION, navigating: Boolean = false): Navigator? {
        val file = installed(PackKind.ROUTING, region) ?: return routing?.value?.second
        val current = routing
        if (current != null && (current.matches(file) || navigating)) return current.value.second
        current?.value?.first?.close()
        routing = null
        val router = runCatching { OfflineRouter(appContext, file) }.getOrNull() ?: return null
        val navigator = Navigator(appContext, router).also { it.spokenInstructionObserver = voice }
        routing = Opened(file, router to navigator)
        return navigator
    }

    fun hasPack(kind: PackKind, region: String = PackStore.DEFAULT_REGION): Boolean = installed(kind, region) != null

    private fun installed(kind: PackKind, region: String): File? =
        (packStore.status(kind, region) as? PackStatus.Installed)?.file

    /** Remembers which file (and version of it) an open resource came from. */
    private class Opened<T>(file: File, val value: T) {
        private val path = file.path
        private val modified = file.lastModified()
        private val length = file.length()
        fun matches(file: File) = file.path == path && file.lastModified() == modified && file.length() == length
    }

    companion object {
        @Volatile
        private var instance: ExploreServices? = null

        fun get(context: Context): ExploreServices =
            instance ?: synchronized(this) { instance ?: ExploreServices(context).also { instance = it } }
    }
}
