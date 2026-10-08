package app.southbound.core.weather

import app.southbound.core.network.GitHubReleases
import app.southbound.core.network.NetworkFeature
import app.southbound.core.network.ReleaseAsset

/**
 * The forecast pack from Southbound's own GitHub: the file on the "weather" pre-release, under the weather toggle.
 * The request names only the release and the file, the same for every phone.
 */
class GitHubForecastSource(private val releases: GitHubReleases) : ForecastSource {
    override fun latestId(): Long? = try {
        releases.byTag(NetworkFeature.WEATHER, Forecasts.RELEASE_TAG).asset(Forecasts.ASSET)?.id
    } catch (e: GitHubReleases.HttpException) {
        // No such release yet (the pack workflow hasn't run on main).
        if (e.code == 404) null else throw e
    }

    override fun download(id: Long): ByteArray =
        releases.bytes(NetworkFeature.WEATHER, ReleaseAsset(id, Forecasts.ASSET, -1), MAX_BYTES)

    private companion object {
        /** The statewide pack is well under a megabyte gzip'd. */
        const val MAX_BYTES = 4 * 1024 * 1024
    }
}
