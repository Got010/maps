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
import app.southbound.core.packs.ManifestException
import app.southbound.core.packs.PackEvents
import app.southbound.core.packs.PackKind
import app.southbound.core.packs.PackStore
import app.southbound.core.packs.RegionSelection
import app.southbound.core.packs.ReleaseManifest
import app.southbound.core.packs.UnrecognizedPackException
import app.southbound.core.packs.UpdatePlan
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
 * Which packs: a basemap for each region picked in Settings, and the
 * statewide routing and search packs and NYC transit that every release has.
 */
class Updater(context: Context) {

    private val app = context.applicationContext
    private val packStore = PackStore(app.filesDir, app.getExternalFilesDir(null))
    private val regions = RegionSelection.get(app)
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
            val manifestAsset = release.asset(ReleaseManifest.FILE_NAME)
                ?: return@withContext publish(UpdateState.Failed("The latest release (${release.tag}) has no update manifest yet."))
            val manifest = ReleaseManifest.parse(client.text(checkFeature, manifestAsset))
            UpdateScheduler.recordCheck(app, manifest.version)

            val installed = packStore.installed().associate { (it.kind to it.region) to packStore.installedSha256(it.kind, it.region) }
            val plan = UpdatePlan.of(
                manifest, regions.regions.value, installed,
                wanted = if (packsOn) PackKind.entries.toSet() else emptySet(),
                installedVersionCode = installedVersionCode(),
                includeApp = appOn,
            )
            if (plan.isEmpty) return@withContext publish(UpdateState.UpToDate(manifest.version))
            requireSpace(plan.bytes)
            beforeDownload(plan)

            var overall = 0L
            val installedNames = ArrayList<String>()
            for (pack in plan.packs) {
                val asset = release.asset(pack.file.name) ?: throw IOException("The release is missing ${pack.file.name}")
                val part = File(packStore.downloadDir.apply { mkdirs() }, "${pack.file.name}.part")
                try {
                    val sha = client.download(NetworkFeature.PACK_DOWNLOADS, asset, part, onProgress = { done ->
                        progress(pack.file.name, done, pack.file.size, overall + done, plan.bytes)
                    }, cancelled = { !isActive })
                    if (sha != pack.file.sha256) throw IOException("${pack.file.name} doesn't match its published checksum")
                    PackEvents.emit(packStore.installDownloaded(part, pack, sha))
                    // routing-nys replaces routing-nyc, and so on: don't keep both.
                    packStore.deleteOtherRegions(pack.kind, pack.region)
                } finally {
                    part.delete()
                }
                overall += pack.file.size
                installedNames += pack.file.name
            }

            val apk = plan.app ?: return@withContext publish(UpdateState.Updated(installedNames, null))
            val asset = release.asset(apk.name) ?: throw IOException("The release is missing ${apk.name}")
            val dir = File(app.noBackupFilesDir, "updates").apply { deleteRecursively(); mkdirs() }
            val file = File(dir, apk.name)
            val sha = client.download(NetworkFeature.APP_UPDATES, asset, file, onProgress = { done ->
                progress(apk.name, done, apk.size, overall + done, plan.bytes)
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
            UpdateStatus.state.value
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
        const val MB = 1L shl 20
        const val PROGRESS_STEP = MB
        const val SPACE_MARGIN = 200 * MB
    }
}
