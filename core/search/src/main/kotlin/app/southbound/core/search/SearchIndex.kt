package app.southbound.core.search

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.Closeable
import java.io.File

/**
 * Searches a search pack (search-<region>.sqlite) on the device.
 *
 * Uses SQLite bundled with the app rather than Android's, because the pack
 * needs FTS5 with the trigram tokenizer, which older Android versions lack.
 * Nothing here touches the network.
 */
class SearchIndex private constructor(private val connection: SQLiteConnection) : Closeable {

    /**
     * Up to [limit] results for [raw], best first. With [near], closer
     * results rank higher and carry their distance.
     */
    fun search(raw: String, near: LatLon? = null, limit: Int = 20): List<SearchResult> {
        val query = SearchQuery.parse(raw)
        if (query.isEmpty) return emptyList()
        val candidates = synchronized(connection) { candidates(query, near) }
        return SearchRanking.rank(query, candidates, near, limit)
    }

    /** The full row for a place, e.g. to reopen a saved result. */
    fun place(id: Long): Place? = synchronized(connection) {
        connection.prepare("SELECT $COLUMNS FROM feature f WHERE f.id = ?").use { stmt ->
            stmt.bindLong(1, id)
            if (stmt.step()) stmt.readCandidate().place else null
        }
    }

    /**
     * Rows matching every word: the most important ones overall, plus (with
     * [near]) the most important ones close by, so a small local place isn't
     * crowded out by famous ones far away. Distance ranking happens in Kotlin
     * ([SearchRanking]); the SQL uses no math functions, which not every
     * SQLite build has.
     */
    private fun candidates(query: SearchQuery, near: LatLon?): List<Candidate> {
        val overall = select(query, box = null)
        if (near == null) return overall
        val nearby = select(query, box = NearbyBox.around(near, NEARBY_KM))
        return (nearby + overall).distinctBy { it.place.id }
    }

    private fun select(query: SearchQuery, box: NearbyBox?): List<Candidate> {
        val args = mutableListOf<Any>()
        val sql = StringBuilder("SELECT $COLUMNS FROM ")
        val match = query.ftsMatch
        if (match != null) {
            sql.append("feature_fts JOIN feature f ON f.id = feature_fts.rowid WHERE feature_fts MATCH ?")
            args += match
            // Confirm each word (short ones too) in SQL, so the LIMIT applies
            // to real matches rather than trigram look-alikes.
            for (token in query.tokens) {
                sql.append(" AND instr(f.search_text, ?) > 0")
                args += " $token"
            }
        } else {
            sql.append("feature f WHERE f.name_key >= ? AND f.name_key < ?")
            args += query.prefix
            args += query.prefix + '\u007f'
        }
        if (box != null) {
            sql.append(" AND f.lat BETWEEN ? AND ? AND f.lon BETWEEN ? AND ?")
            args.addAll(listOf(box.south, box.north, box.west, box.east))
        }
        sql.append(" ORDER BY f.importance DESC LIMIT $CANDIDATE_LIMIT")

        return connection.prepare(sql.toString()).use { stmt ->
            args.forEachIndexed { i, arg ->
                when (arg) {
                    is String -> stmt.bindText(i + 1, arg)
                    is Double -> stmt.bindDouble(i + 1, arg)
                    else -> error("unexpected argument $arg")
                }
            }
            buildList { while (stmt.step()) add(stmt.readCandidate()) }
        }
    }

    override fun close() = synchronized(connection) { connection.close() }

    companion object {
        private const val CANDIDATE_LIMIT = 300

        /** How far "nearby" reaches when picking local candidates. */
        private const val NEARBY_KM = 15.0
        private const val COLUMNS =
            "f.id, f.kind, f.name, f.category, f.address, f.lat, f.lon, f.importance, f.name_key, " +
                "f.search_text, f.opening_hours, f.phone, f.website, f.cuisine, f.osm_ref"

        /** Opens [file] read-only. Throws if it isn't a search pack. */
        fun open(file: File): SearchIndex {
            val connection = BundledSQLiteDriver().open(file.path, SQLITE_OPEN_READONLY)
            try {
                val appId = connection.prepare("PRAGMA application_id").use { if (it.step()) it.getLong(0) else 0L }
                require(appId == SEARCH_APPLICATION_ID) { "${file.name} is not a search pack" }
                return SearchIndex(connection)
            } catch (e: Exception) {
                connection.close()
                throw e
            }
        }

        /** "SBS1"; the pipeline sets it, and core:packs recognises it. */
        private const val SEARCH_APPLICATION_ID = 0x53425331L

        private fun SQLiteStatement.textOrNull(index: Int): String? = if (isNull(index)) null else getText(index)

        private fun SQLiteStatement.readCandidate(): Candidate = Candidate(
            place = Place(
                id = getLong(0),
                kind = PlaceKind.of(getInt(1)),
                name = getText(2),
                category = textOrNull(3),
                address = textOrNull(4),
                lat = getDouble(5),
                lon = getDouble(6),
                openingHours = textOrNull(10),
                phone = textOrNull(11),
                website = textOrNull(12),
                cuisine = textOrNull(13),
                osmRef = textOrNull(14),
            ),
            importance = getInt(7),
            nameKey = getText(8),
            searchText = getText(9),
        )
    }
}
