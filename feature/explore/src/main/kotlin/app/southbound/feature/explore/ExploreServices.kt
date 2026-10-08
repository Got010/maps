package app.southbound.feature.explore

import android.content.Context
import app.southbound.core.export.AndroidBackupStores
import app.southbound.core.export.BackupService
import app.southbound.core.export.ImportJournal
import app.southbound.core.export.ResumeOutcome
import app.southbound.core.network.ApiKey
import app.southbound.core.network.GitHubReleases
import app.southbound.core.network.NetworkGate
import app.southbound.core.network.PreferenceNetworkToggles
import app.southbound.core.network.SecretStore
import app.southbound.core.packs.PackKind
import app.southbound.core.packs.PackStore
import app.southbound.core.packs.RegionSelection
import app.southbound.core.packs.VoiceSelection
import app.southbound.core.routing.DrivingPrefs
import app.southbound.core.routing.FuelPrefs
import app.southbound.core.routing.GasPrices
import app.southbound.core.routing.Navigator
import app.southbound.core.routing.OfflineRouter
import app.southbound.core.routing.RouteOptionsStore
import app.southbound.core.routing.TripBroadcast
import app.southbound.core.search.SearchIndex
import app.southbound.core.traffic.TrafficFeed
import app.southbound.core.weather.GitHubForecastSource
import app.southbound.core.weather.WeatherFeed
import app.southbound.core.transit.AndroidTransitDb
import app.southbound.core.transit.TransitPack
import app.southbound.core.transit.TransitRealtime
import app.southbound.core.userdata.KeystoreSealer
import app.southbound.core.userdata.UserDataStore
import app.southbound.core.voice.GuidanceVoice
import app.southbound.core.voice.VoiceSettings
import app.southbound.core.voice.VoiceSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    /** Landmarks in turn prompts, camera and school zone alerts, audio made ahead (Phase 5). */
    val guidance = GuidanceSupport(appContext, voice, voiceSettings) { openSearch() }

    /** Network toggles (all off but pack downloads until the user turns one on) and API keys. */
    val networkToggles = PreferenceNetworkToggles.get(appContext)
    val networkGate = NetworkGate(networkToggles)
    val secrets = SecretStore(appContext)
    val realtime = TransitRealtime(networkGate, busKey = { secrets.get(ApiKey.MTA_BUS_TIME) })

    /** Saved places, lists and recents: encrypted, outside backups, never sent anywhere. */
    val userData by lazy { UserDataStore(File(appContext.noBackupFilesDir, UserDataStore.FILE_NAME), KeystoreSealer()) }

    /** 511NY incidents and NYC DOT speeds, each behind its toggle. */
    val traffic = TrafficFeed(networkGate)

    /**
     * NWS alerts for the whole state, NOAA radar, the stations' current weather and the 7-day forecast pack (from
     * the "weather" pre-release on GitHub, kept in files/weather so it reads offline), behind the weather toggle.
     */
    val weather = WeatherFeed(
        networkGate,
        forecastSource = GitHubForecastSource(GitHubReleases(networkGate, { secrets.get(ApiKey.GITHUB_RELEASES) })),
        forecastDir = File(appContext.filesDir, "weather"),
    )

    /** Fuel economy, gas price and the state to look it up for (a Settings choice, never GPS). */
    val fuelPrefs = FuelPrefs.get(appContext)

    /** The state's weekly average gas price from EIA, behind its own toggle and the user's key. */
    val gasPrices = GasPrices(networkGate, apiKey = { secrets.get(ApiKey.EIA) }, store = fuelPrefs)

    /** Encrypted export and import of the places, API keys and preferences above (Settings > Backup). */
    val backup: BackupService by lazy {
        val version = runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName }.getOrNull()
        BackupService(
            AndroidBackupStores(
                appContext, userData, secrets, networkToggles, RouteOptionsStore.get(appContext), DrivingPrefs.get(appContext),
                fuelPrefs, voiceStore, RegionSelection.get(appContext), VoiceSelection.get(appContext),
            ),
            ImportJournal.at(appContext.noBackupFilesDir, KeystoreSealer()),
            appVersion = version ?: "unknown",
        )
    }

    /**
     * Finishes an import that was verified and kept but stopped before it was applied. Does nothing otherwise.
     * [onApplied] is called (on a background thread) once it has landed.
     */
    fun finishPendingImport(onApplied: () -> Unit = {}) {
        if (!ImportJournal.pendingIn(appContext.noBackupFilesDir)) return
        scope.launch(Dispatchers.IO) {
            if (backup.resume() is ResumeOutcome.Applied) onApplied()
        }
    }

    private var search: Opened<SearchIndex>? = null
    private var transit: Opened<TransitPack>? = null
    private var routing: Opened<Pair<OfflineRouter, Navigator>>? = null

    /** The search index, reopened if a newer search pack was imported. Null without one. */
    @Synchronized
    fun openSearch(): SearchIndex? {
        val file = installed(PackKind.SEARCH) ?: return null
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
    fun openNavigator(navigating: Boolean = false): Navigator? {
        val file = installed(PackKind.ROUTING) ?: return routing?.value?.second
        val current = routing
        if (current != null && (current.matches(file) || navigating)) return current.value.second
        current?.value?.first?.close()
        routing = null
        val router = runCatching { OfflineRouter(appContext, file) }.getOrNull() ?: return null
        val navigator = Navigator(appContext, router).also {
            it.spokenInstructionObserver = voice
            guidance.attach(it)
        }
        routing = Opened(file, router to navigator)
        // The car (Android Auto) reads the trip from here, whether or not an Activity is up.
        TripBroadcast.follow(scope, navigator.state) { navigator.stop() }
        return navigator
    }

    /** The transit pack with its network loaded, reopened after a newer import. Null without one. */
    @Synchronized
    fun openTransit(): TransitPack? {
        val file = installed(PackKind.TRANSIT) ?: return null
        transit?.takeIf { it.matches(file) }?.let { return it.value }
        transit?.value?.close()
        transit = null
        return runCatching { AndroidTransitDb.open(file).also { it.network } }.getOrNull()
            ?.also { transit = Opened(file, it) }
    }

    fun hasPack(kind: PackKind): Boolean = installed(kind) != null

    /** The statewide pack when installed, else NYC's (see PackStore.installedAny). */
    private fun installed(kind: PackKind): File? = packStore.installedAny(kind)?.file

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
