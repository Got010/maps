package app.southbound.core.search

/** What a search row is. The numbers match the pipeline's `kind` column. */
enum class PlaceKind(val code: Int) {
    LOCALITY(0),
    PLACE(1),
    STREET(2),
    ADDRESS(3),
    ;

    companion object {
        fun of(code: Int): PlaceKind = entries.firstOrNull { it.code == code } ?: PLACE
    }
}

/**
 * Which data a [Place] came from. Overture Places rows live in their own
 * tables of the search pack and stay out of guidance landmarks and "nearby"
 * (see [OverturePlaces]); their credit is on the About screen.
 */
enum class PlaceSource { OSM, OVERTURE }

/** A point on the map with a name, from the search pack or a long press. */
data class Place(
    val id: Long,
    val kind: PlaceKind,
    val name: String,
    /** OSM value such as "cafe" or "neighbourhood"; see [categoryLabel]. */
    val category: String?,
    val address: String?,
    val lat: Double,
    val lon: Double,
    val openingHours: String? = null,
    val phone: String? = null,
    val website: String? = null,
    val cuisine: String? = null,
    /** "n123", "w456" or "r789": the OSM object this came from. */
    val osmRef: String? = null,
    /**
     * An address whose number isn't in the data: the spot is worked out from
     * the nearest numbers on the same street ([AddressLookup.nearestHouse]).
     */
    val approximate: Boolean = false,
    /** [PlaceSource.OVERTURE] for a row of the pack's overture_place table; OSM for everything else. */
    val source: PlaceSource = PlaceSource.OSM,
) {
    /** "Coffee shop" from "coffee_shop", for the place card. */
    val categoryLabel: String?
        get() = category?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }

    companion object {
        /** A dropped pin, which has no search row. */
        fun pin(lat: Double, lon: Double, name: String): Place =
            Place(id = -1, kind = PlaceKind.PLACE, name = name, category = null, address = null, lat = lat, lon = lon)
    }
}

data class LatLon(val lat: Double, val lon: Double)
