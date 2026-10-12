package app.southbound.core.export

import app.southbound.core.network.ApiKey
import app.southbound.core.network.NetworkFeature
import app.southbound.core.routing.GasRegion
import app.southbound.core.routing.PriceSource
import app.southbound.core.routing.RoutePrefs
import app.southbound.core.userdata.UserData
import app.southbound.core.voice.VoiceSettings

/** What the driver chose for the fuel estimate. The price fetched from EIA is a cache and is not exported. */
data class FuelChoices(
    val mpg: Double,
    val pricePerGallon: Double,
    val source: PriceSource,
    val region: GasRegion,
)

/** The preferences an export carries, as typed values (bundle schema 2; docs/EXPORT.md lists them). */
data class AppSettings(
    /** Every [NetworkFeature]'s toggle. */
    val network: Map<NetworkFeature, Boolean>,
    val route: RoutePrefs,
    val nightBySunset: Boolean,
    val fuel: FuelChoices,
    val voice: VoiceSettings,
    /** Basemap region ids the updater keeps on the phone. */
    val regions: Set<String>,
    val allowMobileData: Boolean,
    /** Settings > Driving: the map's search bar at the bottom (schema 7; an older bundle imports with it on). */
    val searchAtBottom: Boolean,
)

/**
 * Everything an export carries. Deliberately not a data class: it holds the API keys, and a generated
 * toString would print them. [toString] gives the counts only.
 */
class BackupData(
    val userData: UserData,
    val apiKeys: Map<ApiKey, String>,
    val settings: AppSettings,
) {
    fun counts(): BackupCounts = BackupCounts(
        lists = userData.lists.size,
        savedPlaces = userData.saved.size,
        recents = userData.recents.size,
        home = if (userData.home != null) 1 else 0,
        work = if (userData.work != null) 1 else 0,
        apiKeys = apiKeys.size,
        settings = SettingsEntry.count(settings),
    )

    override fun toString(): String = "BackupData(${counts()})"
}

/** How much a bundle holds, as its manifest lists it. Counts only: never names, places or keys. */
data class BackupCounts(
    val lists: Int,
    val savedPlaces: Int,
    val recents: Int,
    /** 1 when a Home place is set, else 0. */
    val home: Int,
    /** 1 when a Work place is set, else 0. */
    val work: Int,
    val apiKeys: Int,
    val settings: Int,
)
