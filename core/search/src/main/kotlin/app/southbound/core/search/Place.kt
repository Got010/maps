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
