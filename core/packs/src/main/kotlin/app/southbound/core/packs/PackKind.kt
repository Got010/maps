package app.southbound.core.packs

import java.io.File
import java.io.RandomAccessFile

/**
 * The kinds of data pack, each one file named `<prefix>-<region>.<extension>`,
 * e.g. `basemap-nyc.pmtiles`. A pack is recognised by its contents, so an
 * import still works when a browser renames the download.
 */
enum class PackKind(val prefix: String, val extension: String) {
    /** PMTiles v3 vector tiles for MapLibre. */
    BASEMAP("basemap", "pmtiles"),

    /** A Valhalla tile extract (tar) for on-device routing. */
    ROUTING("routing", "tar"),

    /** SQLite FTS5 database of addresses and places. */
    SEARCH("search", "sqlite"),

    /** SQLite database compiled from GTFS. */
    TRANSIT("transit", "sqlite"),
    ;

    fun fileName(region: String): String = "$prefix-$region.$extension"

    /** True if [file] has this kind's header. */
    fun matches(file: File): Boolean = detect(file) == this

    companion object {
        /**
         * SQLite packs set `PRAGMA application_id` so the app can tell them
         * apart without opening them: "SBS1" for search, "SBT1" for transit.
         * The pipeline writes the same numbers.
         */
        const val SEARCH_APPLICATION_ID = 0x53425331
        const val TRANSIT_APPLICATION_ID = 0x53425431

        private val PMTILES_MAGIC = "PMTiles".toByteArray(Charsets.US_ASCII)
        private const val PMTILES_VERSION: Byte = 3
        private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        private const val SQLITE_APPLICATION_ID_OFFSET = 68
        private val TAR_MAGIC = "ustar".toByteArray(Charsets.US_ASCII)
        private const val TAR_MAGIC_OFFSET = 257
        private const val HEADER_BYTES = 512

        /** The kind whose header [file] has, or null if it's no pack at all. */
        fun detect(file: File): PackKind? {
            if (!file.isFile) return null
            val header = ByteArray(HEADER_BYTES)
            val read = RandomAccessFile(file, "r").use { raf ->
                var total = 0
                while (total < header.size) {
                    val n = raf.read(header, total, header.size - total)
                    if (n < 0) break
                    total += n
                }
                total
            }
            return when {
                read > PMTILES_MAGIC.size &&
                    header.startsWith(PMTILES_MAGIC) &&
                    header[PMTILES_MAGIC.size] == PMTILES_VERSION -> BASEMAP

                read >= SQLITE_APPLICATION_ID_OFFSET + 4 && header.startsWith(SQLITE_MAGIC) ->
                    when (header.readIntBigEndian(SQLITE_APPLICATION_ID_OFFSET)) {
                        SEARCH_APPLICATION_ID -> SEARCH
                        TRANSIT_APPLICATION_ID -> TRANSIT
                        else -> null
                    }

                read >= TAR_MAGIC_OFFSET + TAR_MAGIC.size &&
                    header.copyOfRange(TAR_MAGIC_OFFSET, TAR_MAGIC_OFFSET + TAR_MAGIC.size)
                        .contentEquals(TAR_MAGIC) -> ROUTING

                else -> null
            }
        }

        /**
         * The region in a pack's file name, e.g. "nyc" from `basemap-nyc.pmtiles`
         * or from a browser's `basemap-nyc (1).pmtiles`. Null if the name doesn't
         * follow the pattern for [kind].
         */
        fun regionFromName(kind: PackKind, displayName: String?): String? {
            val name = displayName?.trim()?.lowercase() ?: return null
            val match = Regex("""^${kind.prefix}-([a-z0-9]+)(?:[ _-]?\(\d+\)|-\d+)?\.${kind.extension}$""")
                .matchEntire(name) ?: return null
            return match.groupValues[1]
        }

        private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
            size >= prefix.size && copyOf(prefix.size).contentEquals(prefix)

        private fun ByteArray.readIntBigEndian(offset: Int): Int =
            ((this[offset].toInt() and 0xff) shl 24) or
                ((this[offset + 1].toInt() and 0xff) shl 16) or
                ((this[offset + 2].toInt() and 0xff) shl 8) or
                (this[offset + 3].toInt() and 0xff)
    }
}
