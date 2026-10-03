package app.southbound.core.packs

/** A box on the map, in WGS84 degrees. */
data class Bounds(val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double) {
    val centerLat: Double get() = (minLat + maxLat) / 2
    val centerLon: Double get() = (minLon + maxLon) / 2

    fun contains(lat: Double, lon: Double): Boolean = lat in minLat..maxLat && lon in minLon..maxLon

    fun union(other: Bounds): Bounds = Bounds(
        minOf(minLon, other.minLon), minOf(minLat, other.minLat),
        maxOf(maxLon, other.maxLon), maxOf(maxLat, other.maxLat),
    )
}

/**
 * A data pack region. Basemaps come one per region so a phone installs only
 * the parts of the state it needs; routing and search cover the whole state
 * in one pack each ([Regions.STATEWIDE]), so cross-region trips work.
 */
data class Region(val id: String, val name: String, val bounds: Bounds, val basemap: Boolean)

/**
 * The regions the pipeline builds. Mirrors pipeline/regions.json, which the
 * pack builders read; a unit test keeps the two in step.
 */
object Regions {
    /** The region id of the routing and search packs: all of New York State. */
    const val STATEWIDE = "nys"

    val all: List<Region> = listOf(
        Region("nyc", "New York City", Bounds(-74.2591, 40.4774, -73.7004, 40.9176), basemap = true),
        Region("li", "Long Island", Bounds(-73.78, 40.53, -71.75, 41.25), basemap = true),
        Region("hudson", "Hudson Valley and Catskills", Bounds(-75.5, 40.9, -73.24, 42.4), basemap = true),
        Region("capital", "Capital Region, Adirondacks and North Country", Bounds(-75.5, 42.4, -73.24, 45.02), basemap = true),
        Region("western", "Central and Western New York", Bounds(-79.77, 41.99, -75.5, 45.02), basemap = true),
        Region(STATEWIDE, "All of New York State", Bounds(-79.77, 40.47, -71.75, 45.02), basemap = false),
    )

    /** The regions a basemap can be installed for, in the order the picker shows them. */
    val basemaps: List<Region> = all.filter { it.basemap }

    fun byId(id: String): Region? = all.firstOrNull { it.id == id }

    /** The name to show for a region id, falling back to the id for packs built by hand. */
    fun nameOf(id: String): String = byId(id)?.name ?: id

    /** The basemap region a point falls in, or null outside the state. Overlaps go to the first match. */
    fun basemapAt(lat: Double, lon: Double): Region? = basemaps.firstOrNull { it.bounds.contains(lat, lon) }

    /** One box around every region in [ids], for the map's first view. Null when none is known. */
    fun boundsOf(ids: Collection<String>): Bounds? =
        ids.mapNotNull { byId(it)?.bounds }.reduceOrNull { a, b -> a.union(b) }
}
