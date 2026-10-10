package app.southbound.core.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import app.southbound.core.network.ApiKey
import app.southbound.core.network.GitHubReleases
import app.southbound.core.network.MiniJson
import app.southbound.core.network.NetworkFeature
import app.southbound.core.network.NetworkFeatureDisabledException
import app.southbound.core.network.NetworkGate
import app.southbound.core.network.PreferenceNetworkToggles
import app.southbound.core.network.SecretStore
import app.southbound.core.packs.ImportedPack
import app.southbound.core.packs.ManifestException
import app.southbound.core.packs.PackEvents
import app.southbound.core.packs.PackKind
import app.southbound.core.packs.PackStore
import app.southbound.core.packs.RegionSelection
import app.southbound.core.packs.ReleaseManifest
import app.southbound.core.packs.ReleasePack
import app.southbound.core.packs.TileFolder
import app.southbound.core.packs.UnrecognizedPackException
import app.southbound.core.packs.UpdatePlan
import app.southbound.core.packs.VoiceSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * One update round against Southbound's latest GitHub release: read its
 * manifest, download the packs whose SHA-256 differs from the installed ones,
 * then (if app updates are on) a newer APK, checked and handed to Android's
 * installer. Every request goes through NetworkGate: pack downloads are on by
 * default, app updates off until the user turns them on.
 *
 * Which packs: a basemap for each region picked in Settings, NYC transit, and
 * routing and search. A release with southbound-v2.json names the regions each
 * routing and search pack serves: the phone takes routing-main.tar and the
 * routing packs naming its regions, unpacked together into a new generation of
 * the [TileFolder] (made whole before it replaces the current one: packs of
 * two builds don't mix), and the search packs of its regions, and drops the
 * search packs and the one-graph routing-nys.tar it no longer needs. An older
 * release has only southbound.json: the statewide routing and search packs.
 */
class Updater(context: Context) {

    private val app = context.applicationContext
    private val packStore = PackStore(app.filesDir, app.getExternalFilesDir(null))
    private val tiles = TileFolder(app.filesDir)
    private val regions = RegionSelection.get(app)
    private val voices = VoiceSelection.get(app)
    private val gate = NetworkGate(PreferenceNetworkToggles.get(app))
    private val secrets = SecretStore(app)
    private val client = GitHubReleases(gate, { secrets.get(ApiKey.GITHUB_RELEASES) })

    /**
     * Runs one round and returns how it ended (also published to [UpdateStatus]).
     * [beforeDownload] runs once there's something to fetch, so a worker can go
     * to the foreground first.
     */
    suspend fun run(beforeDownload: suspend (UpdatePlan) -> Unit = {}): UpdateState = withContext(Dispatchers.IO) {
        val packsOn = gate.isAllowed(NetworkFeature.PACK_DOWNLOADS)
        val appOn = gate.isAllowed(NetworkFeature.APP_UPDATES)
        if (!packsOn && !appOn) return@withContext publish(UpdateState.Idle)
        val checkFeature = if (packsOn) NetworkFeature.PACK_DOWNLOADS else NetworkFeature.APP_UPDATES
        val hasToken = !secrets.get(ApiKey.GITHUB_RELEASES).isNullOrBlank()
        publish(UpdateState.Checking)
        try {
            val release = client.latest(checkFeature)
            val v2Asset = release.asset(ReleaseManifest.FILE_NAME_V2)
            val manifestAsset = v2Asset ?: release.asset(ReleaseManifest.FILE_NAME)
                ?: return@withContext publish(UpdateState.Failed("The latest release (${release.tag}) has no update manifest yet."))
            val v2 = v2Asset != null
            val manifestText = client.text(checkFeature, manifestAsset)
            val manifest = if (v2) ReleaseManifest.parseV2(manifestText) else ReleaseManifest.parse(manifestText)
            UpdateScheduler.recordCheck(app, manifest.version)

            val wantedKinds = if (packsOn) PackKind.entries.toSet() else emptySet()
            val picked = regions.regions.value
            val installed = packStore.installed().associate { (it.kind to it.region) to packStore.installedSha256(it.kind, it.region) }
            val plan = UpdatePlan.of(
                manifest, picked, installed,
                wanted = wantedKinds,
                installedVersionCode = installedVersionCode(),
                includeApp = appOn,
                // A voice pack only for the voice picked in Settings > Voice.
                voices = voices.voices.value,
            )
            // v2 routing packs go into the tile folder together; see the class comment.
            val routing = if (v2) UpdatePlan.wanted(manifest, picked, wantedKinds).filter { it.kind == PackKind.ROUTING } else emptyList()
            val routingChanged = v2 && packsOn && routing.associate { it.file.name to it.file.sha256 } != tiles.installed()
            val others = plan.packs.filterNot { v2 && it.kind == PackKind.ROUTING }
            if (others.isEmpty() && plan.app == null && !routingChanged) {
                if (v2 && packsOn) dropUnwanted(manifest, picked)
                return@withContext publish(UpdateState.UpToDate(manifest.version))
            }

            var generation: TileFolder.Generation? = null
            var committed = false
            try {
                val routingDownloads = ArrayList<ReleasePack>()
                if (routingChanged) {
                    val g = tiles.begin().also { generation = it }
                    // A pack unchanged since the current generation is linked from it, not downloaded again.
                    for (pack in routing) if (!g.keep(pack.file.name, pack.file.sha256)) routingDownloads += pack
                }
                val downloads = UpdatePlan(others + routingDownloads, plan.app)
                // A routing pack is unpacked beside its download, then the download goes.
                requireSpace(downloads.bytes + (routingDownloads.maxOfOrNull { it.file.size } ?: 0L))
                beforeDownload(downloads)

                var overall = 0L
                val installedNames = ArrayList<String>()
                for (pack in downloads.packs) {
                    val asset = release.asset(pack.file.name) ?: throw IOException("The release is missing ${pack.file.name}")
                    val part = File(packStore.downloadDir.apply { mkdirs() }, "${pack.file.name}.part")
                    try {
                        val sha = client.download(NetworkFeature.PACK_DOWNLOADS, asset, part, onProgress = { done ->
                            progress(pack.file.name, done, pack.file.size, overall + done, downloads.bytes)
                        }, cancelled = { !isActive })
                        if (sha != pack.file.sha256) throw IOException("${pack.file.name} doesn't match its published checksum")
                        val g = generation
                        if (g != null && pack.kind == PackKind.ROUTING) {
                            g.unpack(pack.file.name, sha, part)
                        } else {
                            PackEvents.emit(packStore.installDownloaded(part, pack, sha))
                            // routing-nys replaces routing-nyc, and so on: don't keep both (southbound.json only).
                            if (!v2) packStore.deleteOtherRegions(pack.kind, pack.region)
                        }
                    } finally {
                        part.delete()
                    }
                    overall += pack.file.size
                    installedNames += pack.file.name
                }
                generation?.let { g ->
                    val replaced = tiles.current()
                    g.commit()
                    committed = true
                    // A trip may still read the generation just replaced; anything older goes.
                    tiles.prune(inUse = replaced)
                    PackEvents.emit(ImportedPack(PackKind.ROUTING, TILES_REGION, g.dir))
                }
                if (v2 && packsOn) dropUnwanted(manifest, picked)

                val apk = plan.app ?: return@withContext publish(UpdateState.Updated(installedNames, null))
                val asset = release.asset(apk.name) ?: throw IOException("The release is missing ${apk.name}")
                val dir = File(app.noBackupFilesDir, "updates").apply { deleteRecursively(); mkdirs() }
                val file = File(dir, apk.name)
                val sha = client.download(NetworkFeature.APP_UPDATES, asset, file, onProgress = { done ->
                    progress(apk.name, done, apk.size, overall + done, downloads.bytes)
                }, cancelled = { !isActive })
                when (val verdict = check(file, apk.sha256, sha)) {
                    is ApkCheck.Verdict.Refused -> {
                        file.delete()
                        return@withContext publish(UpdateState.Failed("Update not installed. ${verdict.reason}"))
                    }
                    ApkCheck.Verdict.Ok -> Unit
                }
                publish(UpdateState.AwaitingInstall(manifest.version))
                AppInstaller.install(app, file)
                return@withContext UpdateStatus.state.value
            } finally {
                if (!committed) generation?.abandon()
            }
        } catch (e: NetworkFeatureDisabledException) {
            publish(UpdateState.Idle)
        } catch (e: GitHubReleases.HttpException) {
            publish(UpdateErrors.forHttp(e.code, hasToken))
        } catch (e: ManifestException) {
            publish(UpdateState.Failed(e.message ?: "The release manifest couldn't be read."))
        } catch (e: MiniJson.JsonException) {
            publish(UpdateState.Failed("GitHub's answer couldn't be read."))
        } catch (e: UnrecognizedPackException) {
            publish(UpdateState.Failed(e.message ?: "A download wasn't a Southbound pack."))
        } catch (e: IOException) {
            // Stopped (the job was cancelled): the next round starts over.
            if (!isActive) return@withContext publish(UpdateState.Idle)
            val offline = e is java.net.UnknownHostException || e is java.net.SocketException || e is java.net.SocketTimeoutException
            publish(UpdateState.Failed(e.message?.takeIf { it.isNotBlank() && !offline } ?: UpdateErrors.NO_CONNECTION))
        } finally {
            UpdateNotifications.cancelProgress(app)
        }
    }

    /**
     * After a southbound-v2.json round: search packs no picked region uses go (the user unticked their regions, or
     * search-nyc gave way to search-nys), and so does the one-graph routing-nys.tar once routing reads tiles.
     */
    private fun dropUnwanted(manifest: ReleaseManifest, picked: Set<String>) {
        val searches = UpdatePlan.wanted(manifest, picked).filter { it.kind == PackKind.SEARCH }.map { it.region }.toSet()
        for (pack in packStore.installed()) {
            val unwanted = when (pack.kind) {
                PackKind.SEARCH -> pack.region !in searches
                PackKind.ROUTING -> tiles.current() != null
                else -> false
            }
            if (unwanted) {
                packStore.delete(pack.kind, pack.region)
                PackEvents.emitRemoved(pack.kind, pack.region)
            }
        }
    }

    private var lastFile = ""
    private var lastProgress = 0L

    private fun progress(file: String, done: Long, total: Long, overallDone: Long, overallTotal: Long) {
        // About every 1 MB is plenty for a progress bar.
        if (file == lastFile && done - lastProgress < PROGRESS_STEP && done < total) return
        lastFile = file
        lastProgress = done
        publish(UpdateState.Downloading(file, done, total, overallDone, overallTotal))
        UpdateNotifications.progress(app, overallDone, overallTotal)
    }

    private fun publish(state: UpdateState): UpdateState {
        UpdateStatus.set(state)
        return state
    }

    private fun requireSpace(bytes: Long) {
        val free = packStore.packsDir.apply { mkdirs() }.usableSpace
        if (free < bytes + SPACE_MARGIN) {
            throw IOException("Not enough free space: the update needs ${bytes / MB} MB, the phone has ${free / MB} MB free.")
        }
    }

    private fun installedVersionCode(): Long = packageInfo(app.packageName).longVersionCode

    private fun check(apk: File, expectedSha256: String, actualSha256: String): ApkCheck.Verdict {
        val archive = archiveInfo(apk)
        val installed = packageInfo(app.packageName)
        return ApkCheck.verify(
            packageName = app.packageName,
            installedVersionCode = installed.longVersionCode,
            installedSigners = installed.currentSigners(),
            apkPackage = archive?.packageName,
            apkVersionCode = archive?.longVersionCode ?: -1,
            apkSigners = archive?.currentSigners().orEmpty(),
            apkHistory = archive?.signingHistory().orEmpty(),
            expectedSha256 = expectedSha256,
            actualSha256 = actualSha256,
        )
    }

    private fun packageInfo(name: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.packageManager.getPackageInfo(name, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            app.packageManager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    private fun archiveInfo(apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.packageManager.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            app.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    private fun PackageInfo.currentSigners(): Set<String> {
        val info = signingInfo ?: return emptySet()
        return if (info.hasMultipleSigners()) {
            info.apkContentsSigners.map { it.toByteArray().sha256() }.toSet()
        } else {
            info.signingCertificateHistory.lastOrNull()?.let { setOf(it.toByteArray().sha256()) }.orEmpty()
        }
    }

    private fun PackageInfo.signingHistory(): List<String> {
        val info = signingInfo ?: return emptyList()
        return if (info.hasMultipleSigners()) emptyList() else info.signingCertificateHistory.map { it.toByteArray().sha256() }
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

    private companion object {
        /** The region an installed tile folder is announced under (PackEvents). */
        const val TILES_REGION = "tiles"
        const val MB = 1L shl 20
        const val PROGRESS_STEP = MB
        const val SPACE_MARGIN = 200 * MB
    }
}
