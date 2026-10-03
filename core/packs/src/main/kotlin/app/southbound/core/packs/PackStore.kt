package app.southbound.core.packs

import java.io.File
import java.io.IOException
import java.io.InputStream

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
     * Copies any data pack from [source] into private storage. The kind comes
     * from the file's contents; the region from [displayName] when it follows
     * the `<kind>-<region>.<ext>` pattern, else [fallbackRegion].
     *
     * The copy lands under a temp name and is renamed only once it's
     * recognised, so a failed or cancelled import never replaces a working pack.
     *
     * @throws UnrecognizedPackException if [source] isn't a Southbound pack.
     */
    fun importPack(source: InputStream, displayName: String?, fallbackRegion: String = DEFAULT_REGION): ImportedPack {
        packsDir.mkdirs()
        val temp = File(packsDir, "import.part")
        try {
            copy(source, temp)
            val kind = PackKind.detect(temp) ?: throw UnrecognizedPackException()
            val region = PackKind.regionFromName(kind, displayName) ?: fallbackRegion
            val target = File(packsDir, kind.fileName(region))
            moveIntoPlace(temp, target)
            return ImportedPack(kind, region, target)
        } finally {
            temp.delete()
        }
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
        val temp = File(packsDir, "${target.name}.part")
        try {
            copy(source, temp)
            if (!PackKind.BASEMAP.matches(temp)) throw NotAPmtilesFileException()
            moveIntoPlace(temp, target)
            return PackStatus.Installed(target)
        } finally {
            temp.delete()
        }
    }

    private fun copy(source: InputStream, target: File) {
        source.use { input -> target.outputStream().use { input.copyTo(it, COPY_BUFFER_BYTES) } }
    }

    private fun moveIntoPlace(temp: File, target: File) {
        if (!temp.renameTo(target)) throw IOException("Couldn't move the pack into place")
    }

    companion object {
        const val PACKS_DIR = "packs"
        const val DEFAULT_REGION = "nyc"
        private const val COPY_BUFFER_BYTES = 1 shl 20

        fun basemapFileName(region: String): String = PackKind.BASEMAP.fileName(region)
    }
}

data class ImportedPack(val kind: PackKind, val region: String, val file: File)

open class UnrecognizedPackException(message: String = "Not a Southbound data pack") : IOException(message)

class NotAPmtilesFileException : UnrecognizedPackException("Not a PMTiles map file")

sealed interface PackStatus {
    val file: File

    data class Installed(override val file: File) : PackStatus
    data class Missing(override val file: File) : PackStatus

    /** A file is there but isn't the pack it's named as, e.g. a truncated push. */
    data class Invalid(override val file: File) : PackStatus
}
