package app.southbound.core.export

import android.content.Context
import app.southbound.core.network.NetworkFeature
import app.southbound.core.network.PreferenceNetworkToggles
import app.southbound.core.network.SecretStore
import app.southbound.core.network.SecretUnreadableException
import app.southbound.core.packs.RegionSelection
import app.southbound.core.packs.Regions
import app.southbound.core.packs.VoiceSelection
import app.southbound.core.routing.DrivingPrefs
import app.southbound.core.routing.FuelPrefs
import app.southbound.core.routing.RouteOptionsStore
import app.southbound.core.updates.UpdateScheduler
import app.southbound.core.userdata.LoadState
import app.southbound.core.userdata.UserDataStore
import app.southbound.core.voice.VoiceSettingsStore
import kotlinx.coroutines.flow.first

/**
 * The phone's stores, as [BackupService] reads and replaces them. They are the live instances (the app holds one of
 * each), so the screens that show them update as soon as an import lands.
 */
class AndroidBackupStores(
    private val context: Context,
    private val userData: UserDataStore,
    private val secrets: SecretStore,
    private val network: PreferenceNetworkToggles,
    private val route: RouteOptionsStore,
    private val driving: DrivingPrefs,
    private val fuel: FuelPrefs,
    private val voice: VoiceSettingsStore,
    private val regions: RegionSelection,
    private val voiceSelection: VoiceSelection,
) : BackupStores {

    override fun blocker(): String? =
        if (userData.loadState.value is LoadState.Unreadable) UNREADABLE_PLACES else null

    override suspend fun ensureReadable() {
        blocker()?.let { throw BackupRefusedException(it) }
        try {
            secrets.readAll()
        } catch (e: SecretUnreadableException) {
            throw BackupRefusedException(UNREADABLE_KEY)
        }
    }

    override suspend fun read(): BackupData {
        blocker()?.let { throw BackupRefusedException(it) }
        val fuelNow = fuel.settings.value
        val settings = AppSettings(
            network = NetworkFeature.entries.associateWith { network.isEnabled(it) },
            route = route.options.value,
            nightBySunset = driving.nightBySunset.value,
            fuel = FuelChoices(fuelNow.mpg, fuelNow.pricePerGallon, fuelNow.priceSource, fuelNow.region),
            voice = voice.settings.first(),
            // The user's own regions: one got for a trip goes after it, so it isn't one to restore. A region a later
            // version withdrew stays picked here but can't be exported (no import could use it), and nothing in
            // Settings unticks it: it is left out rather than refusing every export.
            regions = regions.own intersect Regions.basemaps.map { it.id }.toSet(),
            allowMobileData = UpdateScheduler.allowMobileData(context),
            searchAtBottom = driving.searchAtBottom.value,
        )
        // readAll throws if a saved key can't be decrypted, rather than leaving it out of the export.
        return BackupData(userData.data.value, secrets.readAll(), settings)
    }

    override fun networkToggles(): Map<NetworkFeature, Boolean> = NetworkFeature.entries.associateWith { network.isEnabled(it) }

    /**
     * Every write here is durable when it returns (a synchronous commit, a fsynced file replace, or DataStore's
     * completed edit) and throws if it fails, so [BackupService] deletes the kept import only after all of them
     * have landed. A queued write would let the process die with the import forgotten and the data half applied.
     */
    override suspend fun apply(data: BackupData) {
        // Throws, changing nothing, if the saved places file is unreadable (the store refuses to overwrite it).
        userData.update(explicit = false) { data.userData }
        secrets.replaceAll(data.apiKeys)
        val s = data.settings
        network.replaceAll(s.network)
        route.replace(s.route)
        driving.replace(nightBySunset = s.nightBySunset, searchAtBottom = s.searchAtBottom)
        fuel.setChoices(s.fuel.mpg, s.fuel.pricePerGallon, s.fuel.source, s.fuel.region)
        voice.update { s.voice }
        // The updater downloads a voice pack only for the voice in this list; Settings keeps it in step when open.
        voiceSelection.replace(s.voice.builtInVoice.takeUnless { it.bundled }?.id)
        regions.replaceBasemaps(s.regions)
        UpdateScheduler.replaceAllowMobileData(context, s.allowMobileData)
    }

    companion object {
        const val UNREADABLE_PLACES =
            "Your saved places could not be read, and Southbound will not replace them. Close Settings and choose " +
                "Keep trying or Start fresh in the Saved places notice first."
        const val UNREADABLE_KEY =
            "A saved API key could not be read from the phone's Keystore, and Southbound will not replace it. " +
                "Remove that key in Settings first."
    }
}
