package app.southbound.core.road

import app.southbound.core.network.MiniJson
import app.southbound.core.search.Place
import app.southbound.core.search.PlaceKind

/**
 * The sixteen kinds of place the Road pack has, in the pipeline's order (`CATEGORIES` in pipeline/road/build_road.py,
 * the contract in pipeline/road/README.md). [code] is the pack's `category` column; [displayName] is what a row and a
 * sheet call it; [iconKey] names the glyph (the map draws it in code, the lists pick a Material icon by the same key).
 * [defaultOn] is the set the map and the list start with: the places a road trip needs every day.
 */
enum class RoadCategory(val code: String, val displayName: String, val iconKey: String, val defaultOn: Boolean) {
    CAMP("camp", "Campsite", "tent", true),
    RV("rv", "RV site", "rv", true),
    REST("rest", "Rest area", "bench", true),
    DUMP("dump", "Dump station", "dump", true),
    WATER("water", "Drinking water", "drop", true),
    TOILET("toilet", "Toilets", "toilet", false),
    SHOWER("shower", "Showers", "shower", false),
    FUEL("fuel", "Fuel", "pump", true),
    TRUCK("truck", "Truck stop", "truck", true),
    LAUNDRY("laundry", "Laundromat", "laundry", false),
    LIBRARY("library", "Library", "book", false),
    PHARMACY("pharmacy", "Pharmacy", "pill", false),
    HOSPITAL("hospital", "Hospital", "hospital", false),
    CLINIC("clinic", "Clinic", "clinic", false),
    GROCERY("grocery", "Grocery", "cart", false),
    PARK("park", "Park", "tree", false),
    ;

    companion object {
        /** The category with this pack code, or null for a code this app doesn't know (a newer pack's). */
        fun of(code: String?): RoadCategory? = entries.firstOrNull { it.code == code }

        /** The categories a fresh filter shows. */
        val DEFAULT_ON: Set<RoadCategory> = entries.filter { it.defaultOn }.toSet()
    }
}

/** What OpenStreetMap says about a yes-or-no fact. A fact the object doesn't carry is [UNKNOWN], never [NO]. */
enum class RoadFlag {
    YES,
    NO,
    UNKNOWN,
    ;

    companion object {
        fun of(word: String?): RoadFlag = when (word?.trim()?.lowercase()) {
            "yes", "true" -> YES
            "no", "false" -> NO
            else -> UNKNOWN
        }
    }
}

/** The small chips on a list row, in the order they are shown. Only what the pack says is yes (or true) gets one. */
enum class RoadChip { DIESEL, FEE, TOILETS, WATER, SHOWERS, OVERNIGHT }

/**
 * A spot's `extra` column (a JSON object of OpenStreetMap's own words, see pipeline/road/README.md) as typed fields.
 * An absent key is [RoadFlag.UNKNOWN] or null: absent means unknown, never false. [overnight] is the one derived
 * fact: the pack says tents or caravans are allowed (`tents=yes` or `caravans=yes`), which only campsites and RV sites
 * carry; no tag says "no", so it is never [RoadFlag.NO] unless both are said no.
 */
data class RoadExtra(
    /** `fuel:diesel=yes`, the pack's one non-tag field (true, or no key). */
    val diesel: Boolean = false,
    /** [RoadFlag.YES] is a fee, [RoadFlag.NO] is free. */
    val fee: RoadFlag = RoadFlag.UNKNOWN,
    val toilets: RoadFlag = RoadFlag.UNKNOWN,
    /** `drinking_water`. */
    val water: RoadFlag = RoadFlag.UNKNOWN,
    val shower: RoadFlag = RoadFlag.UNKNOWN,
    val overnight: RoadFlag = RoadFlag.UNKNOWN,
    /** `opening_hours`, as the pack has it (the app's hours reader shows it). */
    val hours: String? = null,
    val operator: String? = null,
) {
    /** The chips for a list row: diesel, fee, toilets, water, showers, overnight, each only when it is a yes. */
    fun chips(): List<RoadChip> = buildList {
        if (diesel) add(RoadChip.DIESEL)
        if (fee == RoadFlag.YES) add(RoadChip.FEE)
        if (toilets == RoadFlag.YES) add(RoadChip.TOILETS)
        if (water == RoadFlag.YES) add(RoadChip.WATER)
        if (shower == RoadFlag.YES) add(RoadChip.SHOWERS)
        if (overnight == RoadFlag.YES) add(RoadChip.OVERNIGHT)
    }

    /** True when the pack says nothing at all about this spot. */
    val isEmpty: Boolean get() = this == EMPTY

    /** This spot's facts, with anything it doesn't say filled in from [other] (the same place under another category). */
    fun orElse(other: RoadExtra): RoadExtra = RoadExtra(
        diesel = diesel || other.diesel,
        fee = fee.orElse(other.fee),
        toilets = toilets.orElse(other.toilets),
        water = water.orElse(other.water),
        shower = shower.orElse(other.shower),
        overnight = overnight.orElse(other.overnight),
        hours = hours ?: other.hours,
        operator = operator ?: other.operator,
    )

    private fun RoadFlag.orElse(other: RoadFlag): RoadFlag = if (this == RoadFlag.UNKNOWN) other else this

    companion object {
        val EMPTY = RoadExtra()

        /** Longest text kept from the pack: OpenStreetMap's own limit for a tag value is 255. */
        const val MAX_TEXT = 255

        /**
         * Reads the column. Anything that is not a JSON object (a NULL, an empty string, damage) is [EMPTY]: a bad
         * row costs its extras, never the list. A value of the wrong type is ignored.
         */
        fun parse(json: String?): RoadExtra {
            if (json.isNullOrBlank()) return EMPTY
            val map = try {
                MiniJson.parse(json) as? Map<*, *>
            } catch (e: MiniJson.JsonException) {
                null
            } ?: return EMPTY
            fun word(key: String): String? = (map[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TEXT)
            fun flag(key: String): RoadFlag = RoadFlag.of(word(key))
            val tents = flag("tents")
            val caravans = flag("caravans")
            val overnight = when {
                tents == RoadFlag.YES || caravans == RoadFlag.YES -> RoadFlag.YES
                tents == RoadFlag.NO && caravans == RoadFlag.NO -> RoadFlag.NO
                else -> RoadFlag.UNKNOWN
            }
            return RoadExtra(
                diesel = map["diesel"] == true,
                fee = flag("fee"),
                toilets = flag("toilets"),
                water = flag("drinking_water"),
                shower = flag("shower"),
                overnight = overnight,
                hours = word("opening_hours"),
                operator = word("operator"),
            )
        }
    }
}

/**
 * One row of the pack's `spot` table. A place that is two categories in the pack (a truck stop's pumps are `fuel` and
 * `truck`) is one row in each; [RoadPlaces.distinct] folds them into one spot that keeps the better category and lists
 * the others in [alsoCategories], with the facts of both.
 */
data class RoadSpot(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val category: RoadCategory,
    val name: String?,
    val brand: String?,
    val extra: RoadExtra = RoadExtra.EMPTY,
    val alsoCategories: Set<RoadCategory> = emptySet(),
) {
    /** What the row and the sheet call it: the name, else the brand, else the kind of place ("Rest area"). */
    val title: String
        get() = name?.takeIf { it.isNotBlank() } ?: brand?.takeIf { it.isNotBlank() } ?: category.displayName

    /**
     * The spot as a place for the card, saving, stops and directions. The pack has no OpenStreetMap reference, so
     * there is none; the category label is the kind of place.
     */
    fun toPlace(): Place = Place(
        id = id,
        kind = PlaceKind.PLACE,
        name = title,
        category = category.displayName,
        address = null,
        lat = lat,
        lon = lon,
        openingHours = extra.hours,
    )
}

/** A spot with its distance from where the list was asked for. */
data class RoadResult(val spot: RoadSpot, val distanceMeters: Double)

/**
 * A spot beside the route: [alongMeters] from the route's first point, [aheadMeters] from where the driver is (the
 * same, for a route not yet started), and [offsetMeters] off the line.
 */
data class RoadAlongResult(val spot: RoadSpot, val alongMeters: Double, val aheadMeters: Double, val offsetMeters: Double)

/**
 * One cell of the pack's precomputed grid for the map's low zooms, summed over the categories the map shows: [count]
 * spots whose mean position is ([lat], [lon]). [x] and [y] are the Web Mercator tile of the cell at [zoom].
 */
data class RoadCluster(val zoom: Int, val x: Int, val y: Int, val count: Int, val lat: Double, val lon: Double)
