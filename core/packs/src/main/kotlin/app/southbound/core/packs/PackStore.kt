package app.southbound.core.packs

import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Where data packs live on the device. MapLibre reads PMTiles through
 * `pmtiles://file://`, so packs must be plain files, not APK assets.
 *
 * - [packsDir], `<filesDir>/packs/`: private storage, where the pack manager
 *   will install downloads.
 * - [importPacksDir], `Android/data/app.southbound/files/packs/`: app-specific
 *   external storage. Other apps can't read it, but `adb push` can write to it,
 *   so a pack can be loaded onto a release build over USB.
 *
 * A valid pack in private storage wins over one in the import folder.
 */
class PackStore(filesDir: File, externalFilesDir: File?) {

    val packsDir: File = File(filesDir, PACKS_DIR)
    val importPacksDir: File? = externalFilesDir?.let { File(it, PACKS_DIR) }

    fun basemapStatus(region: String): PackStatus {
        val name = basemapFileName(region)
        val candidates = listOfNotNull(File(packsDir, name), importPacksDir?.let { File(it, name) })
        candidates.firstOrNull { it.isFile && hasPmtilesV3Header(it) }?.let { return PackStatus.Installed(it) }
        candidates.firstOrNull { it.isFile }?.let { return PackStatus.Invalid(it) }
        // Point the user at the folder adb can reach.
        return PackStatus.Missing(candidates.last())
    }

    /**
     * Copies a basemap from [source] (e.g. a file the user picked) into private
     * storage. The copy lands under a temp name and is renamed only once its
     * header checks out, so a failed or cancelled import never replaces a
     * working pack.
     *
     * @throws NotAPmtilesFileException if [source] isn't a PMTiles v3 archive.
     */
    fun importBasemap(region: String, source: InputStream): PackStatus.Installed {
        packsDir.mkdirs()
        val target = File(packsDir, basemapFileName(region))
        val temp = File(packsDir, "${target.name}.part")
        try {
            source.use { input -> temp.outputStream().use { input.copyTo(it, COPY_BUFFER_BYTES) } }
            if (!hasPmtilesV3Header(temp)) throw NotAPmtilesFileException()
            if (!temp.renameTo(target)) throw IOException("Couldn't move the map into place")
            return PackStatus.Installed(target)
        } finally {
            temp.delete()
        }
    }

    companion object {
        const val PACKS_DIR = "packs"
        private const val COPY_BUFFER_BYTES = 1 shl 20

        fun basemapFileName(region: String): String = "basemap-$region.pmtiles"

        // A PMTiles v3 archive starts with the ASCII magic "PMTiles" and version byte 3.
        private val PMTILES_MAGIC = "PMTiles".toByteArray(Charsets.US_ASCII)
        private const val PMTILES_VERSION: Byte = 3

        internal fun hasPmtilesV3Header(file: File): Boolean {
            val header = ByteArray(PMTILES_MAGIC.size + 1)
            val read = file.inputStream().use { it.read(header) }
            return read == header.size &&
                header.copyOf(PMTILES_MAGIC.size).contentEquals(PMTILES_MAGIC) &&
                header.last() == PMTILES_VERSION
        }
    }
}

class NotAPmtilesFileException : IOException("Not a PMTiles map file")

sealed interface PackStatus {
    val file: File

    data class Installed(override val file: File) : PackStatus
    data class Missing(override val file: File) : PackStatus

    /** A file is there but is not a PMTiles v3 archive, e.g. a truncated push. */
    data class Invalid(override val file: File) : PackStatus
}
