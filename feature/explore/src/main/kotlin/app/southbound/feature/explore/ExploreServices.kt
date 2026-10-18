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
import app.southbound.core.packs.Regions
import app.southbound.core.packs.RegionSelection
import app.southbound.core.packs.TileFolder
import app.southbound.core.packs.VoiceSelection
import app.southbound.core.packs.WifiSelection
import app.southbound.core.routing.DrivingPrefs
import app.southbound.core.routing.FuelPrefs
import app.southbound.core.routing.GasPrices
import app.southbound.core.routing.Navigator
import app.southbound.core.routing.OfflineRouter
import app.southbound.core.routing.RouteOptionsStore
import app.southbound.core.routing.TravelMode
import app.southbound.core.routing.TripBroadcast
import app.southbound.core.search.Place
import app.southbound.core.search.PlaceSearch
import app.southbound.core.search.SearchIndex
import app.southbound.core.search.SearchPacks
import app.southbound.core.traffic.TrafficFeed
import app.southbound.core.weather.GitHubForecastSource
import app.southbound.core.weather.WeatherFeed
import app.southbound.core.wifi.WifiPack
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
import uniffi.ferrostar.TripState
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

    /** The routing tiles from southbound-v2.json's packs, when the updater has put them in. */
    val tiles = TileFolder(appContext.filesDir)
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

    init {
        // The first read (the file and a Keystore decrypt) here, off the main thread, before the map asks for it.
        scope.launch(Dispatchers.IO) { userData }
    }

    /** 511NY incidents and NYC DOT speeds, each behind its toggle. */
    val traffic = TrafficFeed(networkGate, apiKey = { secrets.get(it) })

    /**
     * NWS alerts for every covered state, NOAA radar, the stations' current weather and the 7-day forecast pack (from
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
                fuelPrefs, voiceStore, RegionSelection.get(appContext), VoiceSelection.get(appContext), WifiSelection.get(appContext),
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
            // Unasked: not over places saved since it stopped (then Settings > Backup offers it).
            if (backup.resume(automatic = true) is ResumeOutcome.Applied) onApplied()
        }
    }

    /**
     * A route preview getting regions for its trip ("Get for this trip"). Each map that arrives rebuilds the map
     * screen, which would close the preview; the screen opens this one again instead, until it routes with nothing
     * missing, is closed or started, or was asked for more than six hours ago.
     */
    @Volatile
    var tripWait: TripWait? = null

    data class TripWait(val place: Place, val mode: TravelMode, val stops: List<Place>, val start: Place?, val atMs: Long)

    /** What the trip keeps while the map screen is rebuilt under it (a map region arriving mid-trip). */
    internal val tripMemory = TripMemory()

    private var search: Pair<String, PlaceSearch>? = null
    private var transit: Opened<TransitPack>? = null
    private var routing: Opened<Pair<OfflineRouter, Navigator>>? = null
    private var wifi: Opened<WifiPack>? = null

    /**
     * Search over every installed search pack (New York's, and one per region in the other states), reopened when
     * one is added, replaced or removed. Null without one.
     */
    @Synchronized
    fun openSearch(): PlaceSearch? {
        val files = searchFiles()
        if (files.isEmpty()) {
            search?.second?.close()
            search = null
            return null
        }
        val key = files.joinToString("|") { "${it.path}:${it.lastModified()}:${it.length()}" }
        search?.takeIf { it.first == key }?.let { return it.second }
        search?.second?.close()
        search = null
        val opened = files.mapNotNull { runCatching { SearchIndex.open(it) }.getOrNull() }
        if (opened.isEmpty()) return null
        val index: PlaceSearch = opened.singleOrNull() ?: SearchPacks(opened)
        search = key to index
        return index
    }

    /**
     * The installed search packs, the statewide one first (its result ids stay as they were) and then in the
     * regions' order. While search-nys.sqlite is still on the phone (until the update round that brought the
     * per-region packs deletes it), it holds all of New York City's places, so search-nyc is left out beside it.
     */
    private fun searchFiles(): List<File> {
        val packs = packStore.installed().filter { it.kind == PackKind.SEARCH }
        val order = listOf(Regions.STATEWIDE) + Regions.all.map { it.id }
        return packs
            .filterNot { it.region == PackStore.DEFAULT_REGION && packs.any { p -> p.region == Regions.STATEWIDE } }
            .sortedBy { order.indexOf(it.region).let { i -> if (i < 0) order.size else i } }
            .map { it.file }
    }

    /**
     * The navigator for the routing tiles, else the installed one-graph pack. A newer one takes over only
     * between trips, never mid-navigation; the tiles it replaces are deleted then. Whether a trip is on is read
     * from the open navigator itself: a map screen rebuilt mid-trip (a region's map arrived, maybe with new
     * tiles) has no navigator of its own yet, and must get the trip's back, not a fresh idle one beside it while
     * the trip goes on speaking with its router closed.
     */
    @Synchronized
    fun openNavigator(): Navigator? {
        val file = routingSource() ?: return routing?.value?.second
        val current = routing
        val navigating = current?.value?.second?.state?.value?.tripState.let { it != null && it !is TripState.Idle }
        if (current != null && (current.matches(file) || navigating)) return current.value.second
        current?.value?.first?.close()
        routing = null
        val router = runCatching { OfflineRouter(appContext, file) }.getOrNull() ?: return null
        // No router reads an older generation of tiles now; the updater's rounds keep this one while it is open.
        tiles.reading(file.takeIf { it.isDirectory })
        if (file.isDirectory) runCatching { tiles.prune(inUse = file) }
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

    /**
     * The free Wi-Fi pack, reopened when the file is replaced and closed when it is removed. Null without one, or
     * when the file isn't a readable Wi-Fi pack (it stays on the phone either way).
     */
    @Synchronized
    fun openWifi(): WifiPack? {
        // Region "us" first; any other installed Wi-Fi pack (an old import's name) rather than none.
        val packs = packStore.installed().filter { it.kind == PackKind.WIFI }
        val file = (packs.firstOrNull { it.region == PackKind.WIFI_REGION } ?: packs.firstOrNull())?.file
        if (file == null) {
            wifi?.value?.close()
            wifi = null
            return null
        }
        wifi?.takeIf { it.matches(file) }?.let { return it.value }
        wifi?.value?.close()
        wifi = null
        return runCatching { WifiPack.open(file) }.getOrNull()?.also { wifi = Opened(file, it) }
    }

    fun hasPack(kind: PackKind): Boolean =
        if (kind == PackKind.ROUTING) routingSource() != null else installed(kind) != null

    /** The routing tiles' current generation, else the one-graph routing pack (routing-nys.tar). */
    private fun routingSource(): File? = tiles.current() ?: installed(PackKind.ROUTING)

    /** The statewide pack when installed, else NYC's (see PackStore.installedAny). */
    private fun installed(kind: PackKind): File? = packStore.installedAny(kind)?.file

    /**
     * Remembers which file (and version of it) an open resource came from. A tile generation is a folder that
     * never changes once current: its path is enough.
     */
    private class Opened<T>(file: File, val value: T) {
        private val path = file.path
        private val folder = file.isDirectory
        private val modified = file.lastModified()
        private val length = file.length()
        fun matches(file: File) =
            file.path == path && (folder || (file.lastModified() == modified && file.length() == length))
    }

    companion object {
        @Volatile
        private var instance: ExploreServices? = null

        fun get(context: Context): ExploreServices =
            instance ?: synchronized(this) { instance ?: ExploreServices(context).also { instance = it } }
    }
}
