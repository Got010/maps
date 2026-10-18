package app.southbound.core.packs

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Where data packs live on the device. MapLibre reads PMTiles through
 * `pmtiles://file://`, so packs must be plain files, not APK assets.
 *
 * - [packsDir], `<filesDir>/packs/`: private storage, where imports and the
 *   pack manager install packs.
 * - [importPacksDir], `Android/data/app.southbound/files/packs/`: app-specific
 *   external storage. Other apps can't read it, but `adb push` can write to it,
 *   so a pack can be loaded onto a release build over USB.
 *
 * A valid pack in private storage wins over one in the import folder.
 */
class PackStore(filesDir: File, externalFilesDir: File?) {

    val packsDir: File = File(filesDir, PACKS_DIR)
    val importPacksDir: File? = externalFilesDir?.let { File(it, PACKS_DIR) }

    fun status(kind: PackKind, region: String): PackStatus {
        val name = kind.fileName(region)
        val candidates = listOfNotNull(File(packsDir, name), importPacksDir?.let { File(it, name) })
        candidates.firstOrNull { kind.matches(it) }?.let { return PackStatus.Installed(it) }
        candidates.firstOrNull { it.isFile }?.let { return PackStatus.Invalid(it) }
        // Point the user at the folder adb can reach.
        return PackStatus.Missing(candidates.last())
    }

    fun basemapStatus(region: String): PackStatus = status(PackKind.BASEMAP, region)

    /**
     * Every valid pack on the phone, in private storage or the import folder,
     * whatever its region: `basemap-hudson.pmtiles` is the hudson basemap.
     * For a pack present in both places, the private copy counts.
     */
    fun installed(): List<ImportedPack> {
        val found = LinkedHashMap<Pair<PackKind, String>, ImportedPack>()
        for (dir in listOfNotNull(packsDir, importPacksDir)) {
            val files = dir.listFiles() ?: continue
            for (file in files.sortedBy { it.name }) {
                val kind = PackKind.entries.firstOrNull { file.extension == it.extension && file.name.startsWith("${it.prefix}-") }
                    ?: continue
                val region = PackKind.regionFromName(kind, file.name) ?: continue
                if (found.containsKey(kind to region) || !kind.matches(file)) continue
                found[kind to region] = ImportedPack(kind, region, file)
            }
        }
        return found.values.toList()
    }

    /** The installed basemaps, one per region, in the order of [Regions.basemaps] then any others. */
    fun installedBasemaps(): List<ImportedPack> {
        val order = Regions.basemaps.map { it.id }
        return installed().filter { it.kind == PackKind.BASEMAP }
            .sortedBy { order.indexOf(it.region).let { i -> if (i < 0) order.size else i } }
    }

    /**
     * The one pack of [kind] the app should open: the statewide pack when
     * installed, else NYC's, else whichever is there. Routing, search and
     * transit each use a single pack.
     */
    fun installedAny(kind: PackKind): ImportedPack? {
        val candidates = installed().filter { it.kind == kind }
        return candidates.firstOrNull { it.region == Regions.STATEWIDE }
            ?: candidates.firstOrNull { it.region == DEFAULT_REGION }
            ?: candidates.firstOrNull()
    }

    /** Deletes the pack (and its checksum) from private storage and the import folder. */
    fun delete(kind: PackKind, region: String) {
        val name = kind.fileName(region)
        for (dir in listOfNotNull(packsDir, importPacksDir)) {
            File(dir, name).delete()
            File(dir, "$name.sha256").delete()
        }
    }

    /**
     * Deletes every pack of [kind] whatever its region, with its checksum, from private storage and the import
     * folder, including a file named like one that isn't a valid pack (a Wi-Fi switch-off leaves none behind).
     */
    fun deleteAll(kind: PackKind) {
        for (dir in listOfNotNull(packsDir, importPacksDir)) {
            for (file in dir.listFiles().orEmpty()) {
                if (file.isFile && PackKind.regionFromName(kind, file.name.removeSuffix(".sha256")) != null) file.delete()
            }
        }
    }

    /**
     * After installing a pack of a kind the app uses one of (routing, search,
     * transit), drops the other regions' copies: a phone that moved from
     * routing-nyc to routing-nys shouldn't keep both. Basemaps and voices
     * are one per region or voice, and never pruned here.
     */
    fun deleteOtherRegions(kind: PackKind, keep: String): List<String> {
        if (kind == PackKind.BASEMAP || kind == PackKind.VOICE) return emptyList()
        val others = installed().filter { it.kind == kind && it.region != keep }.map { it.region }
        others.forEach { delete(kind, it) }
        return others
    }

    /**
     * Copies any data pack from [source] into private storage. The kind comes
     * from the file's contents; the region from [displayName] when it follows
     * the `<kind>-<region>.<ext>` pattern, else [fallbackRegion].
     *
     * The copy lands under a temp name and is renamed only once it's
     * recognised, so a failed or cancelled import never replaces a working pack.
     * A pack another app shares in goes through [stagePack] and [installStaged]
     * instead, with the person's confirmation in between.
     *
     * @throws UnrecognizedPackException if [source] isn't a Southbound pack.
     */
    fun importPack(source: InputStream, displayName: String?, fallbackRegion: String = DEFAULT_REGION): ImportedPack =
        installStaged(stagePack(source, displayName, fallbackRegion))

    /**
     * Copies a pack from [source] into private storage under a temp name and
     * recognises it, without installing it: nothing installed changes until
     * [installStaged]. [discardStaged] drops it instead. One left staged (the
     * app stopped while asking) goes at the next start ([sweepStaged]), or with a
     * later import once a day old.
     *
     * @throws UnrecognizedPackException if [source] isn't a Southbound pack (nothing is left behind).
     */
    fun stagePack(source: InputStream, displayName: String?, fallbackRegion: String = DEFAULT_REGION): StagedPack {
        packsDir.mkdirs()
        // A name of its own: two imports at once (two packs shared in a row) must not write into one file. One a
        // stopped import left behind goes once it is a day old.
        packsDir.listFiles()?.filter { STALE_IMPORT.matches(it.name) && System.currentTimeMillis() - it.lastModified() > DAY_MS }
            ?.forEach { it.delete() }
        val temp = File.createTempFile("import-", ".part", packsDir)
        try {
            val sha256 = copy(source, temp)
            val kind = PackKind.detect(temp) ?: throw UnrecognizedPackException()
            // The Wi-Fi pack is one national file: a name that doesn't say so (or none) must not file it under
            // the fallback region, where a switch-off that deletes wifi-us would never find it.
            val region = if (kind == PackKind.WIFI) PackKind.WIFI_REGION else PackKind.regionFromName(kind, displayName) ?: fallbackRegion
            return StagedPack(kind, region, temp.length(), sha256, temp)
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }

    /**
     * Installs a pack [stagePack] staged, replacing the installed one of its
     * kind and region in one rename.
     *
     * @throws IOException if the staged copy is gone (discarded, or removed as a day old) or no longer that kind.
     */
    fun installStaged(staged: StagedPack): ImportedPack {
        try {
            if (!staged.file.isFile) throw IOException("The pack's copy is gone; share or pick the file again")
            if (PackKind.detect(staged.file) != staged.kind) throw UnrecognizedPackException()
            val target = File(packsDir, staged.kind.fileName(staged.region))
            moveIntoPlace(staged.file, target)
            writeChecksum(target, staged.sha256)
            return ImportedPack(staged.kind, staged.region, target)
        } finally {
            staged.file.delete()
        }
    }

    /** Drops a pack [stagePack] staged, leaving every installed pack as it was. */
    fun discardStaged(staged: StagedPack) {
        staged.file.delete()
    }

    /**
     * Deletes every staged or half-copied import ([stagePack]'s and [importBasemap]'s temp files), whatever its
     * age: a share left waiting when the app stopped, or a copy a newer share cut off. Call only when no import can
     * be running, at app start. Returns how many are still there (0 unless a delete failed).
     */
    fun sweepStaged(): Int {
        val left = packsDir.listFiles()?.filter { it.isFile && STALE_IMPORT.matches(it.name) }.orEmpty()
        return left.count { !it.delete() && it.exists() }
    }

    /**
     * Copies a basemap from [source] (e.g. a file the user picked) into private
     * storage, like [importPack] but only accepting a basemap.
     *
     * @throws NotAPmtilesFileException if [source] isn't a PMTiles v3 archive.
     */
    fun importBasemap(region: String, source: InputStream): PackStatus.Installed {
        packsDir.mkdirs()
        val target = File(packsDir, PackKind.BASEMAP.fileName(region))
        val temp = File.createTempFile("${target.name}-", ".part", packsDir)
        try {
            val sha256 = copy(source, temp)
            if (!PackKind.BASEMAP.matches(temp)) throw NotAPmtilesFileException()
            moveIntoPlace(temp, target)
            writeChecksum(target, sha256)
            return PackStatus.Installed(target)
        } finally {
            temp.delete()
        }
    }

    /**
     * Installs a pack the updater downloaded into [downloadDir], already checked
     * against the release manifest ([sha256]). It must still be the kind the
     * manifest says; then it replaces the installed copy in one rename.
     *
     * @throws UnrecognizedPackException if the file isn't a [pack] of that kind.
     */
    fun installDownloaded(download: File, pack: ReleasePack, sha256: String): ImportedPack {
        if (PackKind.detect(download) != pack.kind) throw UnrecognizedPackException("Download isn't a ${pack.kind.prefix} pack")
        packsDir.mkdirs()
        val target = File(packsDir, pack.kind.fileName(pack.region))
        moveIntoPlace(download, target)
        writeChecksum(target, sha256)
        return ImportedPack(pack.kind, pack.region, target)
    }

    /** Where the updater stages downloads: inside [packsDir], so installing is a rename. */
    val downloadDir: File get() = File(packsDir, "download")

    /**
     * The SHA-256 of the installed pack, or null when none is installed. Read
     * from a `.sha256` file next to the pack, written at import; computed (and
     * saved) when that's missing or older than the pack. Can take a second for
     * a large pack: call off the main thread.
     */
    fun installedSha256(kind: PackKind, region: String): String? {
        val file = (status(kind, region) as? PackStatus.Installed)?.file ?: return null
        val sidecar = checksumFile(file)
        if (sidecar.isFile && sidecar.lastModified() >= file.lastModified()) {
            CHECKSUM_LINE.find(sidecar.readText())?.let { return it.value }
        }
        val sha256 = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
            digest.digest().toHex()
        }
        runCatching { writeChecksum(file, sha256) }
        return sha256
    }

    /** Copies [source] to [target] and returns its SHA-256. */
    private fun copy(source: InputStream, target: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(source, digest).use { input -> target.outputStream().use { input.copyTo(it, COPY_BUFFER_BYTES) } }
        return digest.digest().toHex()
    }

    private fun checksumFile(pack: File) = File(pack.parentFile, "${pack.name}.sha256")

    /** `sha256sum` format, so the file means something to a person too. */
    private fun writeChecksum(pack: File, sha256: String) {
        checksumFile(pack).writeText("$sha256  ${pack.name}\n")
    }

    private fun moveIntoPlace(temp: File, target: File) {
        if (!temp.renameTo(target)) throw IOException("Couldn't move the pack into place")
    }

    companion object {
        const val PACKS_DIR = "packs"
        const val DEFAULT_REGION = "nyc"
        private const val COPY_BUFFER_BYTES = 1 shl 20
        private val CHECKSUM_LINE = Regex("^[0-9a-f]{64}")

        /** [stagePack]'s and [importBasemap]'s temp files. */
        private val STALE_IMPORT = Regex("(import|basemap-[a-z0-9]+\\.pmtiles)-[0-9]+\\.part")
        private const val DAY_MS = 24L * 60 * 60 * 1000

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        fun basemapFileName(region: String): String = PackKind.BASEMAP.fileName(region)
    }
}

data class ImportedPack(val kind: PackKind, val region: String, val file: File)

/**
 * A pack copied into private storage and recognised, not installed yet ([PackStore.stagePack]): what it is, how big,
 * and its SHA-256, for the person to confirm before [PackStore.installStaged] puts it in place.
 */
class StagedPack internal constructor(
    val kind: PackKind,
    val region: String,
    val sizeBytes: Long,
    val sha256: String,
    internal val file: File,
)

open class UnrecognizedPackException(message: String = "Not a Southbound data pack") : IOException(message)

class NotAPmtilesFileException : UnrecognizedPackException("Not a PMTiles map file")

sealed interface PackStatus {
    val file: File

    data class Installed(override val file: File) : PackStatus
    data class Missing(override val file: File) : PackStatus

    /** A file is there but isn't the pack it's named as, e.g. a truncated push. */
    data class Invalid(override val file: File) : PackStatus
}
