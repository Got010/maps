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

/** A point of a region's [Region.area] polygon, in WGS84 degrees (longitude first, as in regions.json). */
data class LonLat(val lon: Double, val lat: Double)

/**
 * A data pack region. Basemaps come one per region so a phone installs only
 * the parts of the state it needs; routing and search cover the whole area
 * in one pack each ([Regions.STATEWIDE]), so cross-region trips work.
 *
 * [area] (empty for most regions) is a polygon tighter than [bounds], used to
 * tell which region a point belongs to where boxes overlap: northern New
 * Jersey's box takes in Staten Island and the Bronx, its area does not.
 */
data class Region(
    val id: String,
    val name: String,
    val bounds: Bounds,
    val basemap: Boolean,
    val area: List<LonLat> = emptyList(),
) {
    /** True when the point is in [bounds] and, if the region has an [area], inside it too. */
    fun contains(lat: Double, lon: Double): Boolean =
        bounds.contains(lat, lon) && (area.isEmpty() || insideArea(lat, lon))

    /** Ray casting: count the polygon edges a ray running west from the point crosses. */
    private fun insideArea(lat: Double, lon: Double): Boolean {
        var inside = false
        var previous = area.last()
        for (point in area) {
            if ((point.lat > lat) != (previous.lat > lat) &&
                lon < (previous.lon - point.lon) * (lat - point.lat) / (previous.lat - point.lat) + point.lon
            ) {
                inside = !inside
            }
            previous = point
        }
        return inside
    }
}

/**
 * The regions the pipeline builds. Mirrors pipeline/regions.json, which the
 * pack builders read; a unit test keeps the two in step.
 */
object Regions {
    /**
     * The region id of the routing and search packs. The id predates northern
     * New Jersey and stays "nys" (renaming a pack is a migration); the pack
     * covers New York and northern New Jersey.
     */
    const val STATEWIDE = "nys"

    val all: List<Region> = listOf(
        Region("nyc", "New York City", Bounds(-74.2591, 40.4774, -73.7004, 40.9176), basemap = true),
        Region("li", "Long Island", Bounds(-73.78, 40.53, -71.75, 41.25), basemap = true),
        Region("hudson", "Hudson Valley and Catskills", Bounds(-75.5, 40.9, -73.24, 42.4), basemap = true),
        Region("capital", "Capital Region, Adirondacks and North Country", Bounds(-75.5, 42.4, -73.24, 45.02), basemap = true),
        Region("western", "Central and Western New York", Bounds(-79.77, 41.99, -75.5, 45.02), basemap = true),
        Region(
            "njn", "Northern New Jersey", Bounds(-74.75, 40.3, -73.88, 41.36), basemap = true,
            area = listOf(
                LonLat(-74.75, 40.3),
                LonLat(-73.88, 40.3),
                LonLat(-73.88, 40.46),
                LonLat(-74.255, 40.46),
                LonLat(-74.255, 40.505),
                LonLat(-74.235, 40.54),
                LonLat(-74.21, 40.585),
                LonLat(-74.19, 40.62),
                LonLat(-74.18, 40.645),
                LonLat(-74.07, 40.65),
                LonLat(-74.06, 40.66),
                LonLat(-74.04, 40.7),
                LonLat(-74.02, 40.75),
                LonLat(-73.995, 40.8),
                LonLat(-73.965, 40.85),
                LonLat(-73.935, 40.92),
                LonLat(-73.92, 41.0),
                LonLat(-74.69, 41.357),
                LonLat(-74.75, 41.36),
            ),
        ),
        Region(STATEWIDE, "New York and Northern New Jersey", Bounds(-79.77, 40.3, -71.75, 45.02), basemap = false),
    )

    /** The regions a basemap can be installed for, in the order the picker shows them. */
    val basemaps: List<Region> = all.filter { it.basemap }

    fun byId(id: String): Region? = all.firstOrNull { it.id == id }

    /** The name to show for a region id, falling back to the id for packs built by hand. */
    fun nameOf(id: String): String = byId(id)?.name ?: id

    /**
     * The basemap regions a point belongs to, best match first. A region with
     * an [Region.area] polygon owns the points inside it outright: Newark is
     * northern New Jersey's alone, though New York City's box covers it, so a
     * phone with only the New York City map is told the Newark map is missing.
     * Elsewhere every region whose box holds the point counts (regions
     * overlap), in the order of [basemaps].
     */
    fun basemapsAt(lat: Double, lon: Double): List<Region> {
        val containing = basemaps.filter { it.contains(lat, lon) }
        return containing.filter { it.area.isNotEmpty() }.ifEmpty { containing }
    }

    /** The basemap region a point belongs to, or null outside the mapped area. See [basemapsAt]. */
    fun basemapAt(lat: Double, lon: Double): Region? = basemapsAt(lat, lon).firstOrNull()

    /** One box around every region in [ids], for the map's first view. Null when none is known. */
    fun boundsOf(ids: Collection<String>): Bounds? =
        ids.mapNotNull { byId(it)?.bounds }.reduceOrNull { a, b -> a.union(b) }
}
