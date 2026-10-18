package app.southbound.core.wifi

import app.southbound.core.search.Place
import app.southbound.core.search.PlaceKind

/**
 * How sure the pack is that a spot has free Wi-Fi, from best to weakest. The numbers are the pack's `tier` column.
 * Only [TAGGED_FREE] is [confirmed]: the others are never shown as if they were.
 */
enum class WifiTier(val code: Int, val confirmed: Boolean) {
    /** OSM says the Wi-Fi is free (`internet_access:fee=no`, or `fee=no`), or free for customers. */
    TAGGED_FREE(1, true),

    /** OSM says there is Wi-Fi and says nothing about a fee. */
    TAGGED_UNSTATED(2, false),

    /** A chain (matched by its Wikidata brand) that usually offers free Wi-Fi. */
    CHAIN(3, false),

    /** A kind of place that usually has it: libraries, hotel lobbies, rest areas, visitor centers. */
    LIKELY(4, false),
    ;

    companion object {
        /** The tier with this pack code, or null for a number this app doesn't know (a newer pack's). */
        fun of(code: Int): WifiTier? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Which tiers a list shows: the "Tier" chips above the nearest-Wi-Fi list. [TAGGED] is the two tiers OSM itself
 * tags with Wi-Fi.
 */
enum class WifiTierFilter(val tiers: Set<WifiTier>) {
    ALL(WifiTier.entries.toSet()),
    TAGGED(setOf(WifiTier.TAGGED_FREE, WifiTier.TAGGED_UNSTATED)),
    CHAIN(setOf(WifiTier.CHAIN)),
    LIKELY(setOf(WifiTier.LIKELY)),
}

/** The pack's `fee` column. */
enum class WifiFee(val code: String) {
    NO("no"),

    /** Free for customers (`internet_access:fee=customers`): shown with its own badge. */
    CUSTOMERS("customers"),
    UNKNOWN("unknown"),
    ;

    companion object {
        /** The fee with this pack value; anything else (or null) is [UNKNOWN]. */
        fun of(code: String?): WifiFee = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** One row of the pack's `spot` table. */
data class WifiSpot(
    val id: Long,
    /** "n", "w" or "r". */
    val osmType: String,
    val osmId: Long,
    /** The name, else the brand; null when the object has neither. */
    val name: String?,
    /** The chain's Wikidata id, when tagged. */
    val brandWikidata: String?,
    val lat: Double,
    val lon: Double,
    /** The OSM value as the search pack stores it: "cafe", "library", "hotel", ... */
    val category: String,
    val tier: WifiTier,
    val fee: WifiFee,
    val openingHours: String?,
    val website: String?,
) {
    /**
     * Free for customers as OpenStreetMap tags it: fee "customers" on a tier 1 spot. A chain's customers-only policy
     * (tier 3, from the owner's own list) is not a tag and is not this.
     */
    val freeForCustomers: Boolean
        get() = fee == WifiFee.CUSTOMERS && tier == WifiTier.TAGGED_FREE

    /** "n123", "w456" or "r789": the OSM object, as [Place.osmRef] and saved places keep it. */
    val osmRef: String
        get() = osmType + osmId

    /**
     * The spot as a place for the card, saving and directions. A spot without a name takes its category as one
     * ("Library"), so the card never has a blank title.
     */
    fun toPlace(): Place = Place(
        id = id,
        kind = PlaceKind.PLACE,
        name = name?.takeIf { it.isNotBlank() } ?: categoryName(category),
        category = category,
        address = null,
        lat = lat,
        lon = lon,
        openingHours = openingHours,
        website = website,
        osmRef = osmRef,
    )

    companion object {
        /** "Fast food" from "fast_food". */
        fun categoryName(category: String): String = category.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

/** A spot with its distance from where the list was asked for. */
data class WifiResult(val spot: WifiSpot, val distanceMeters: Double)

/**
 * One cell of the pack's precomputed grid for the map's low zooms: [count] spots whose mean position is
 * ([lat], [lon]), the best tier among them [tierMin]. [x] and [y] are the Web Mercator tile of the cell at [zoom].
 */
data class WifiCluster(
    val zoom: Int,
    val x: Int,
    val y: Int,
    val count: Int,
    val lat: Double,
    val lon: Double,
    val tierMin: WifiTier,
)

/**
 * What a "Likely" spot is, for the sheet's sentence ("most libraries have it"): the pack's category values the
 * pipeline gives tier 4 (library, hotel and motel, rest_area, welcome_center and visitor_centre).
 */
enum class WifiLikelyGroup {
    LIBRARIES,
    HOTEL_LOBBIES,
    REST_AREAS,
    VISITOR_CENTERS,

    /** A category the pipeline gives tier 4 that this app has no wording for. */
    OTHER,
    ;

    companion object {
        fun of(category: String): WifiLikelyGroup = when (category) {
            "library" -> LIBRARIES
            "hotel", "motel" -> HOTEL_LOBBIES
            "rest_area" -> REST_AREAS
            "welcome_center", "visitor_centre", "information" -> VISITOR_CENTERS
            else -> OTHER
        }
    }
}
