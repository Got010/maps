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
class SearchIndex private constructor(private val connection: SQLiteConnection) : PlaceSearch {

    /**
     * Up to [limit] results for [raw], best first. With [near], closer
     * results rank higher and carry their distance.
     *
     * A query that starts with a house number ([AddressQuery]) also looks
     * the address up: the exact number on the matching streets, else a
     * guess from the nearest numbers (an approximate result), and the
     * streets themselves.
     */
    override fun search(raw: String, near: LatLon?, limit: Int): List<SearchResult> =
        searchScored(raw, near, limit).map { it.result }

    /** [search] with each result's score, so results from several packs can be merged ([SearchPacks]). */
    fun searchScored(raw: String, near: LatLon? = null, limit: Int = 20): List<ScoredResult> {
        val query = SearchQuery.parse(raw)
        if (query.isEmpty) return emptyList()
        val address = AddressQuery.parse(raw)
        val (candidates, addresses) = synchronized(connection) {
            candidates(query, near) to (address?.let { addressResults(it, near) } ?: emptyList())
        }
        val ranked = SearchRanking.rankScored(query, candidates, near, limit, addresses)
        if (!query.asksWeather) return ranked
        // "weather albany": the towns and cities of that name first, however far, then everything else.
        val towns = synchronized(connection) { candidates(query, null, PlaceKind.LOCALITY) }
        val first = SearchRanking.rankScored(query, towns, near, limit, pointsPerKm = SearchRanking.TOWN_POINTS_PER_KM)
            .map { it.copy(score = it.score + TOWN_FIRST) }
        return (first + ranked).distinctBy { it.result.place.id }.take(limit)
    }

    /**
     * The full row for a place, e.g. to reopen a saved result: a `feature`
     * row, or an Overture place (id from [OVERTURE_ID_BASE] up). An address
     * result (id from [ADDRESS_ID_BASE] up) gives null.
     */
    override fun place(id: Long): Place? = when {
        id >= OVERTURE_ID_BASE -> if (!hasOverture) null else synchronized(connection) {
            connection.prepare("SELECT $OVERTURE_COLUMNS FROM overture_place o WHERE o.rowid = ?").use { stmt ->
                stmt.bindLong(1, id - OVERTURE_ID_BASE)
                if (stmt.step()) stmt.readOverture().place else null
            }
        }
        id >= ADDRESS_ID_BASE -> null
        else -> synchronized(connection) {
            connection.prepare("SELECT $COLUMNS FROM feature f WHERE f.id = ?").use { stmt ->
                stmt.bindLong(1, id)
                if (stmt.step()) stmt.readCandidate().place else null
            }
        }
    }

    /**
     * Packs built with Overture Places have them in their own tables
     * (overture_place, overture_fts); older packs and those built without
     * them search OSM alone.
     */
    private val hasOverture: Boolean by lazy {
        synchronized(connection) {
            connection.prepare(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table'" +
                    " AND name IN ('overture_place', 'overture_fts')",
            ).use { it.step() && it.getLong(0) == 2L }
        }
    }

    /** Packs built before landmarks existed lack the table; guidance then simply has none. */
    private val hasLandmarks: Boolean by lazy {
        synchronized(connection) {
            connection.prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'landmark'").use { it.step() }
        }
    }

    /**
     * What to select for a landmark's direction: the `direction` column when the pack has it (packs
     * built from late 2026), else NULL, so an older pack reads as every direction unknown.
     */
    private val directionColumn: String by lazy {
        val columns = try {
            synchronized(connection) {
                connection.prepare("PRAGMA table_info(landmark)").use { stmt ->
                    buildSet { while (stmt.step()) add(stmt.getText(1)) }
                }
            }
        } catch (e: Exception) {
            emptySet()
        }
        LandmarkColumns.directionSelect(columns)
    }

    /**
     * Signals, signs, roadside places and alert points inside [box], for
     * spoken landmarks and camera alerts along a route (see [Landmarks] and
     * [RouteAlerts]). Empty for an older pack without the table. OSM only:
     * Overture places never become landmarks.
     */
    override fun landmarks(box: NearbyBox, limit: Int): List<Landmark> {
        if (!hasLandmarks) return emptyList()
        return synchronized(connection) {
            connection.prepare(
                // DOT cameras are only for route options; keep them out of the spoken landmarks' limit.
                "SELECT id, category, name, lat, lon FROM landmark" +
                    " WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? AND category != '${Landmark.DOT_CAMERA}' LIMIT $limit",
            ).use { stmt ->
                stmt.bindDouble(1, box.south)
                stmt.bindDouble(2, box.north)
                stmt.bindDouble(3, box.west)
                stmt.bindDouble(4, box.east)
                buildList { while (stmt.step()) add(stmt.readLandmark()) }
            }
        }
    }

    /**
     * Landmarks of one [category] (e.g. [Landmark.ALPR_CAMERA]) inside [box],
     * e.g. the plate readers along a route option. Uses the pack's
     * landmark_category_lat_lon index when it has one, each with its
     * direction when the pack has one (else null). Empty for an older pack
     * without the table.
     */
    override fun landmarks(box: NearbyBox, category: String, limit: Int): List<Landmark> {
        if (!hasLandmarks) return emptyList()
        return synchronized(connection) {
            connection.prepare(
                "SELECT id, category, name, lat, lon, $directionColumn FROM landmark" +
                    " WHERE category = ? AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? LIMIT $limit",
            ).use { stmt ->
                stmt.bindText(1, category)
                stmt.bindDouble(2, box.south)
                stmt.bindDouble(3, box.north)
                stmt.bindDouble(4, box.west)
                stmt.bindDouble(5, box.east)
                buildList { while (stmt.step()) add(stmt.readLandmark(withDirection = true)) }
            }
        }
    }

    /**
     * Every licence-plate reader in the pack, for the always-on camera layer.
     * DeFlock maps a few thousand across the state, so one read is cheap.
     * Empty for an older pack without the landmark table; on any read error
     * too, since the map is fine without them.
     */
    override fun alprCameras(limit: Int): List<Landmark> {
        if (!hasLandmarks) return emptyList()
        return try {
            synchronized(connection) {
                connection.prepare(
                    "SELECT id, category, name, lat, lon FROM landmark WHERE category = ? LIMIT $limit",
                ).use { stmt ->
                    stmt.bindText(1, Landmark.ALPR_CAMERA)
                    buildList { while (stmt.step()) add(stmt.readLandmark()) }
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Places of one [category] (an OSM value such as "parking") within
     * [radiusMeters] of ([lat], [lon]), nearest first. For "Parking nearby" on
     * the arrival card. Only named places are in the pack; OSM's only.
     */
    override fun nearby(category: String, lat: Double, lon: Double, radiusMeters: Double, limit: Int): List<SearchResult> {
        val center = LatLon(lat, lon)
        val box = NearbyBox.around(center, radiusMeters / 1000)
        val found = synchronized(connection) {
            connection.prepare(
                "SELECT $COLUMNS FROM feature f WHERE f.category = ? AND f.lat BETWEEN ? AND ? AND f.lon BETWEEN ? AND ? LIMIT $NEARBY_ROW_LIMIT",
            ).use { stmt ->
                stmt.bindText(1, category)
                stmt.bindDouble(2, box.south)
                stmt.bindDouble(3, box.north)
                stmt.bindDouble(4, box.west)
                stmt.bindDouble(5, box.east)
                buildList { while (stmt.step()) add(stmt.readCandidate().place) }
            }
        }
        return NearbyBox.within(found, center, radiusMeters, limit)
    }

    /**
     * Rows matching every word: the most important ones overall, plus (with
     * [near]) the most important ones close by, so a small local place isn't
     * crowded out by famous ones far away. Distance ranking happens in Kotlin
     * ([SearchRanking]); the SQL uses no math functions, which not every
     * SQLite build has. [kind] limits the rows to one kind.
     *
     * Without a [kind], a pack with Overture Places adds their matching rows
     * the same way, and [OverturePlaces.merge] drops those that repeat an
     * OSM place.
     */
    private fun candidates(query: SearchQuery, near: LatLon?, kind: PlaceKind? = null): List<Candidate> {
        val box = near?.let { NearbyBox.around(it, NEARBY_KM) }
        val osm = overallAndNearby(box) { select(query, it, kind) }
        if (kind != null || !hasOverture) return osm
        return OverturePlaces.merge(osm, overallAndNearby(box) { selectOverture(query, it) })
    }

    /** [read] near [box] (when given) and overall, each row once, nearby first. */
    private fun overallAndNearby(box: NearbyBox?, read: (NearbyBox?) -> List<Candidate>): List<Candidate> {
        val overall = read(null)
        if (box == null) return overall
        return (read(box) + overall).distinctBy { it.place.id }
    }

    /**
     * Up to [PER_KIND_LIMIT] Overture places matching [query], as [select]
     * finds `feature` rows: rows with the query's soft words first, then the
     * likeliest by Overture's confidence.
     */
    private fun selectOverture(query: SearchQuery, box: NearbyBox?): List<Candidate> {
        val where = StringBuilder()
        val whereArgs = mutableListOf<Any>()
        val from: String
        val match = query.ftsMatch
        if (match != null) {
            from = "overture_fts JOIN overture_place o ON o.rowid = overture_fts.rowid"
            where.append("overture_fts MATCH ?")
            whereArgs += match
            for (token in query.tokens) {
                where.append(" AND instr(o.search_text, ?) > 0")
                whereArgs += " $token"
            }
        } else {
            from = "overture_place o"
            where.append("o.name_key >= ? AND o.name_key < ?")
            whereArgs += query.prefix
            whereArgs += query.prefix + '\u007f'
        }
        if (box != null) {
            where.append(" AND o.lat BETWEEN ? AND ? AND o.lon BETWEEN ? AND ?")
            whereArgs.addAll(listOf(box.south, box.north, box.west, box.east))
        }
        // Unlike select()'s window ORDER BY, this one comes after the WHERE in
        // the SQL text, so its arguments bind last.
        val order = query.soft.joinToString("") { "(instr(o.search_text, ?) > 0) + " }
            .let { if (it.isEmpty()) "o.confidence DESC" else "${it.removeSuffix(" + ")} DESC, o.confidence DESC" }
        val args = whereArgs + query.soft.map { " $it" }
        val sql = "SELECT $OVERTURE_COLUMNS FROM $from WHERE $where ORDER BY $order LIMIT $PER_KIND_LIMIT"
        return connection.prepare(sql).use { stmt ->
            stmt.bindAll(args)
            buildList { while (stmt.step()) add(stmt.readOverture()) }
        }
    }

    /**
     * Up to [PER_KIND_LIMIT] rows of each kind, so thousands of look-alike
     * rows of one kind (every "123 Main St" in an older pack's addresses,
     * every "Main Street") can't crowd out the others. Within a kind, rows
     * with the query's soft words (state, ZIP) come first, then the most
     * important.
     */
    private fun select(query: SearchQuery, box: NearbyBox?, kind: PlaceKind?): List<Candidate> {
        val where = StringBuilder()
        val whereArgs = mutableListOf<Any>()
        val from: String
        val match = query.ftsMatch
        if (match != null) {
            from = "feature_fts JOIN feature f ON f.id = feature_fts.rowid"
            where.append("feature_fts MATCH ?")
            whereArgs += match
            // Confirm each word (short ones too) in SQL, so the limit applies
            // to real matches rather than trigram look-alikes.
            for (token in query.tokens) {
                where.append(" AND instr(f.search_text, ?) > 0")
                whereArgs += " $token"
            }
        } else {
            from = "feature f"
            where.append("f.name_key >= ? AND f.name_key < ?")
            whereArgs += query.prefix
            whereArgs += query.prefix + '\u007f'
        }
        if (kind != null) {
            where.append(" AND f.kind = ?")
            whereArgs += kind.code.toLong()
        }
        if (box != null) {
            where.append(" AND f.lat BETWEEN ? AND ? AND f.lon BETWEEN ? AND ?")
            whereArgs.addAll(listOf(box.south, box.north, box.west, box.east))
        }
        // The ORDER BY comes first in the SQL text, so its arguments bind first.
        val order = query.soft.joinToString("") { "(instr(f.search_text, ?) > 0) + " }
            .let { if (it.isEmpty()) "f.importance DESC" else "${it.removeSuffix(" + ")} DESC, f.importance DESC" }
        val args = query.soft.map { " $it" } + whereArgs
        val sql = "SELECT * FROM (SELECT $COLUMNS, ROW_NUMBER() OVER (PARTITION BY f.kind ORDER BY $order) AS kind_rank" +
            " FROM $from WHERE $where) WHERE kind_rank <= $PER_KIND_LIMIT"

        return connection.prepare(sql).use { stmt ->
            stmt.bindAll(args)
            buildList { while (stmt.step()) add(stmt.readCandidate()) }
        }
    }

    /** Packs before schema 2 have no address tables; their addresses are `feature` rows. */
    private val hasAddresses: Boolean by lazy {
        synchronized(connection) {
            connection.prepare(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table'" +
                    " AND name IN ('address', 'address_street', 'address_street_fts')",
            ).use { it.step() && it.getLong(0) == 3L }
        }
    }

    /**
     * Address and street results for [q], already scored ([AddressLookup]):
     * on each fitting street, the exact number, or else a guess from the
     * nearest numbers; then the matching streets of `feature`. An older pack
     * has its addresses in `feature` ([legacyAddresses]).
     */
    private fun addressResults(q: AddressQuery, near: LatLon?): List<ScoredResult> {
        val results = ArrayList<ScoredResult>()
        if (!hasAddresses) {
            results += legacyAddresses(q, near)
        } else {
            val streets = addressStreets(q, near)
                .mapNotNull { s -> AddressLookup.streetFit(q, s.streetKey, s.searchText)?.let { s to it } }
                .sortedWith(
                    compareByDescending<Pair<AddressStreet, StreetFit>> { AddressLookup.streetOrder(it.second) }
                        .thenBy { (s, _) -> near?.let { SearchRanking.distanceMeters(it, s.lat, s.lon) } ?: 0.0 },
                )
                .take(STREET_LOOKUPS)
            for ((street, fit) in streets) {
                val exact = houses(street.id, "number_key = ?", q.numberKey, "", EXACT_HOUSE_LIMIT)
                val hits = if (exact.isNotEmpty()) {
                    exact.map { it to AddressLookup.exactness(q, it) }
                } else {
                    val below = houses(street.id, "number_key < ?", q.numberKey, "DESC", NEIGHBOUR_LIMIT)
                    val above = houses(street.id, "number_key > ?", q.numberKey, "ASC", NEIGHBOUR_LIMIT)
                    listOfNotNull(AddressLookup.nearestHouse(q.numberKey, below, above)?.let { it to AddressLookup.APPROXIMATE })
                }
                for ((house, base) in hits) {
                    val approximate = base == AddressLookup.APPROXIMATE
                    val number = if (approximate) q.number else house.number
                    val place = Place(
                        // One guess per street, so its id comes from the street.
                        id = if (approximate) APPROXIMATE_ID_BASE + street.id else ADDRESS_ID_BASE + house.id,
                        kind = PlaceKind.ADDRESS,
                        name = "$number ${street.street}",
                        category = null,
                        address = AddressFormat.display(number, street.street, street.city, street.state, house.postcode),
                        lat = house.lat,
                        lon = house.lon,
                        approximate = approximate,
                    )
                    val distance = near?.let { SearchRanking.distanceMeters(it, house.lat, house.lon) }
                    val score = AddressLookup.score(base, fit, q, house.postcode, street.state, distance)
                    results += ScoredResult(SearchResult(place, distance), score)
                }
            }
        }
        results += streetFeatures(q, near)
        return results
    }

    /**
     * Schema 1 packs: address rows of `feature` ("123 main st", text "123
     * main st brooklyn 11201") with the number and street, the town and ZIP
     * only ranking, since most rows lack them. Exact numbers only.
     */
    private fun legacyAddresses(q: AddressQuery, near: LatLon?): List<ScoredResult> {
        val number = SearchText.normalize(q.number)
        val query = SearchQuery(number.split(' ') + q.streetTokens, soft = q.cityTokens + listOfNotNull(q.zip))
        return candidates(query, near, PlaceKind.ADDRESS)
            .filter { query.matchesText(it.searchText) && it.nameKey.startsWith("$number ") }
            .mapNotNull { c ->
                val fit = AddressLookup.streetFit(q, c.nameKey.removePrefix("$number "), c.searchText)
                    ?: return@mapNotNull null
                val distance = near?.let { SearchRanking.distanceMeters(it, c.place.lat, c.place.lon) }
                val zip = q.zip?.takeIf { c.searchText.contains(" $it ") }
                val score = AddressLookup.score(
                    AddressLookup.EXACT, fit, q, postcode = zip, state = null, distanceMeters = distance,
                )
                ScoredResult(SearchResult(c.place, distance), score)
            }
    }

    /** address_street rows for the first of [AddressQuery.streetWordSets] that finds any. */
    private fun addressStreets(q: AddressQuery, near: LatLon?): List<AddressStreet> {
        for (words in q.streetWordSets()) {
            val overall = streetRows(words, q.cityTokens, box = null)
            val found = if (near == null) {
                overall
            } else {
                (streetRows(words, q.cityTokens, NearbyBox.around(near, ADDRESS_NEARBY_KM)) + overall).distinctBy { it.id }
            }
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    /** Streets whose text has every one of [words]; those with [town] words first. */
    private fun streetRows(words: List<String>, town: List<String>, box: NearbyBox?): List<AddressStreet> {
        val args = mutableListOf<Any>()
        val sql = StringBuilder(
            "SELECT s.id, s.street, s.city, s.state, s.street_key, s.search_text, s.lat_e6, s.lon_e6 FROM ",
        )
        val match = SearchQuery.trigramMatch(words)
        if (match != null) {
            sql.append("address_street_fts JOIN address_street s ON s.id = address_street_fts.rowid")
            sql.append(" WHERE address_street_fts MATCH ?")
            args += match
            for (word in words) {
                sql.append(" AND instr(s.search_text, ?) > 0")
                args += " $word"
            }
        } else {
            // No word of 3+ letters ("e 4 st"): the street name's start.
            val prefix = words.joinToString(" ")
            sql.append("address_street s WHERE s.street_key >= ? AND s.street_key < ?")
            args += prefix
            args += prefix + '\u007f'
        }
        if (box != null) {
            sql.append(" AND s.lat_e6 BETWEEN ? AND ? AND s.lon_e6 BETWEEN ? AND ?")
            args.addAll(listOf(box.south, box.north, box.west, box.east).map { (it * 1e6).toLong() })
        }
        if (town.isNotEmpty()) {
            sql.append(" ORDER BY ").append(town.joinToString(" + ") { "(instr(s.search_text, ?) > 0)" }).append(" DESC")
            args.addAll(town.map { " $it" })
        }
        sql.append(" LIMIT $STREET_ROW_LIMIT")
        return connection.prepare(sql.toString()).use { stmt ->
            stmt.bindAll(args)
            buildList {
                while (stmt.step()) {
                    add(
                        AddressStreet(
                            id = stmt.getLong(0),
                            street = stmt.getText(1),
                            city = stmt.textOrNull(2),
                            state = stmt.getText(3),
                            streetKey = stmt.getText(4),
                            searchText = stmt.getText(5),
                            lat = stmt.getLong(6) / 1e6,
                            lon = stmt.getLong(7) / 1e6,
                        ),
                    )
                }
            }
        }
    }

    /** House numbers on [streetId] where `number_key` meets [condition] against [key]. */
    private fun houses(streetId: Long, condition: String, key: Long, order: String, limit: Int): List<HouseNumber> =
        connection.prepare(
            "SELECT id, number, number_key, postcode, lat_e6, lon_e6 FROM address" +
                " WHERE street_id = ? AND $condition ORDER BY number_key $order LIMIT $limit",
        ).use { stmt ->
            stmt.bindLong(1, streetId)
            stmt.bindLong(2, key)
            buildList {
                while (stmt.step()) {
                    add(
                        HouseNumber(
                            id = stmt.getLong(0),
                            number = stmt.getText(1),
                            numberKey = stmt.getLong(2),
                            postcode = stmt.textOrNull(3),
                            lat = stmt.getLong(4) / 1e6,
                            lon = stmt.getLong(5) / 1e6,
                        ),
                    )
                }
            }
        }

    /**
     * Street rows of `feature` for [q]'s street (and town) words, scored as
     * [AddressLookup.STREET]: where to go when the number isn't known. New
     * packs give streets their town; older ones match on the street alone.
     */
    private fun streetFeatures(q: AddressQuery, near: LatLon?): List<ScoredResult> {
        for (words in q.streetWordSets()) {
            val query = SearchQuery(words)
            val scored = candidates(query, near, PlaceKind.STREET)
                .filter { query.matchesText(it.searchText) }
                .mapNotNull { c ->
                    val fit = AddressLookup.streetFit(q, c.nameKey, c.searchText) ?: return@mapNotNull null
                    val distance = near?.let { SearchRanking.distanceMeters(it, c.place.lat, c.place.lon) }
                    val score = AddressLookup.score(
                        AddressLookup.STREET, fit, q, postcode = null, state = null, distanceMeters = distance,
                    )
                    ScoredResult(SearchResult(c.place, distance), score)
                }
            if (scored.isNotEmpty()) return scored
        }
        return emptyList()
    }

    override fun close() = synchronized(connection) { connection.close() }

    companion object {
        /** Candidate rows per kind and per query (overall, then nearby). */
        private const val PER_KIND_LIMIT = 150

        /** Address streets looked up per address query, and rows read per street query. */
        private const val STREET_LOOKUPS = 30
        private const val STREET_ROW_LIMIT = 200
        private const val EXACT_HOUSE_LIMIT = 10
        private const val NEIGHBOUR_LIMIT = 4

        /** How far "nearby" reaches for address streets: a typical drive's worth. */
        private const val ADDRESS_NEARBY_KM = 30.0

        /**
         * Address results get ids above every `feature` row's: house rows
         * from here, approximate guesses (one per street) from
         * [APPROXIMATE_ID_BASE].
         */
        const val ADDRESS_ID_BASE = 1L shl 40
        private const val APPROXIMATE_ID_BASE = 1L shl 41

        /** Overture places: this plus their overture_place rowid, above every other id. */
        const val OVERTURE_ID_BASE = 1L shl 42
        private const val NEARBY_ROW_LIMIT = 200

        /** A weather search's towns come before everything else, here and merged with other packs'. */
        private const val TOWN_FIRST = 1_000_000.0

        /** How far "nearby" reaches when picking local candidates. */
        private const val NEARBY_KM = 15.0
        private const val COLUMNS =
            "f.id, f.kind, f.name, f.category, f.address, f.lat, f.lon, f.importance, f.name_key, " +
                "f.search_text, f.opening_hours, f.phone, f.website, f.cuisine, f.osm_ref"
        private const val OVERTURE_COLUMNS =
            "o.rowid, o.name, o.category, o.address, o.lat, o.lon, o.confidence, o.name_key, " +
                "o.search_text, o.phone, o.website"

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

        /** A row of "SELECT id, category, name, lat, lon FROM landmark", with `, direction` after it when [withDirection]. */
        private fun SQLiteStatement.readLandmark(withDirection: Boolean = false): Landmark =
            Landmark(
                getLong(0), getText(1), textOrNull(2), getDouble(3), getDouble(4),
                direction = if (withDirection && !isNull(5)) LandmarkColumns.normalDirection(getLong(5)) else null,
            )

        private fun SQLiteStatement.bindAll(args: List<Any>) {
            args.forEachIndexed { i, arg ->
                when (arg) {
                    is String -> bindText(i + 1, arg)
                    is Double -> bindDouble(i + 1, arg)
                    is Long -> bindLong(i + 1, arg)
                    else -> error("unexpected argument $arg")
                }
            }
        }

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

        /** A row of "SELECT $OVERTURE_COLUMNS FROM overture_place o". */
        private fun SQLiteStatement.readOverture(): Candidate {
            val category = textOrNull(2)
            return Candidate(
                place = Place(
                    id = OVERTURE_ID_BASE + getLong(0),
                    kind = PlaceKind.PLACE,
                    name = getText(1),
                    category = category,
                    address = textOrNull(3),
                    lat = getDouble(4),
                    lon = getDouble(5),
                    phone = textOrNull(9),
                    website = textOrNull(10),
                    source = PlaceSource.OVERTURE,
                ),
                importance = OverturePlaces.importance(category, getDouble(6)),
                nameKey = getText(7),
                searchText = getText(8),
            )
        }
    }
}
