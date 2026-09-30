package org.nysmaps.core.packs

import java.io.File

/**
 * Where data packs live on the device: `<filesDir>/packs/`. App files storage
 * is private to the app, and MapLibre can read PMTiles from it through
 * `pmtiles://file://` (it cannot read them from APK assets).
 */
class PackStore(filesDir: File) {

    val packsDir: File = File(filesDir, PACKS_DIR)

    fun basemapFile(region: String): File = File(packsDir, "basemap-$region.pmtiles")

    fun basemapStatus(region: String): PackStatus {
        val file = basemapFile(region)
        return when {
            !file.isFile -> PackStatus.Missing(file)
            !hasPmtilesV3Header(file) -> PackStatus.Invalid(file)
            else -> PackStatus.Installed(file)
        }
    }

    companion object {
        const val PACKS_DIR = "packs"

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

sealed interface PackStatus {
    val file: File

    data class Installed(override val file: File) : PackStatus
    data class Missing(override val file: File) : PackStatus

    /** A file is there but is not a PMTiles v3 archive, e.g. a truncated push. */
    data class Invalid(override val file: File) : PackStatus
}
