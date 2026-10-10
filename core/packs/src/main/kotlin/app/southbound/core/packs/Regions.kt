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
 *
 * A [planned] region is built by the pipeline but not offered yet: its packs
 * aren't in a release the app reads, so it stays out of [Regions.basemaps]
 * (the picker, the updater, the missing-map check).
 */
data class Region(
    val id: String,
    val name: String,
    val bounds: Bounds,
    val basemap: Boolean,
    val planned: Boolean = false,
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
            "njn", "Northern New Jersey", Bounds(-75.21, 40.3, -73.88, 41.36), basemap = true,
            area = listOf(
                LonLat(-74.87, 40.3),
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
                LonLat(-74.76, 41.33),
                LonLat(-74.82, 41.27),
                LonLat(-74.87, 41.18),
                LonLat(-74.98, 41.09),
                LonLat(-75.13, 40.99),
                LonLat(-75.1, 40.88),
                LonLat(-75.08, 40.82),
                LonLat(-75.205, 40.69),
                LonLat(-75.19, 40.59),
                LonLat(-75.06, 40.53),
                LonLat(-74.98, 40.41),
                LonLat(-74.94, 40.37),
            ),
        ),
        Region(
            "njs", "Southern New Jersey", Bounds(-75.57, 38.88, -73.89, 40.32), basemap = true, planned = true,
            area = listOf(
                LonLat(-74.88, 40.32),
                LonLat(-73.89, 40.32),
                LonLat(-73.89, 38.88),
                LonLat(-74.95, 38.88),
                LonLat(-75.03, 38.88),
                LonLat(-75.15, 39.02),
                LonLat(-75.3, 39.18),
                LonLat(-75.45, 39.35),
                LonLat(-75.55, 39.5),
                LonLat(-75.56, 39.62),
                LonLat(-75.53, 39.7),
                LonLat(-75.46, 39.77),
                LonLat(-75.415, 39.8),
                LonLat(-75.33, 39.84),
                LonLat(-75.23, 39.86),
                LonLat(-75.14, 39.88),
                LonLat(-75.13, 39.95),
                LonLat(-75.05, 39.99),
                LonLat(-74.97, 40.04),
                LonLat(-74.86, 40.08),
                LonLat(-74.72, 40.15),
                LonLat(-74.77, 40.21),
                LonLat(-74.83, 40.27),
            ),
        ),
        Region(
            "ct", "Connecticut", Bounds(-73.73, 40.98, -71.78, 42.05), basemap = true, planned = true,
            area = listOf(
                LonLat(-73.487, 42.05),
                LonLat(-71.8, 42.02),
                LonLat(-71.79, 41.42),
                LonLat(-71.85, 41.32),
                LonLat(-71.91, 41.31),
                LonLat(-72.05, 41.3),
                LonLat(-72.1, 41.23),
                LonLat(-72.4, 41.2),
                LonLat(-72.8, 41.13),
                LonLat(-73.1, 41.07),
                LonLat(-73.4, 41.02),
                LonLat(-73.66, 40.985),
                LonLat(-73.66, 41.0),
                LonLat(-73.72, 41.1),
                LonLat(-73.64, 41.13),
                LonLat(-73.48, 41.21),
                LonLat(-73.55, 41.29),
                LonLat(-73.54, 41.4),
                LonLat(-73.52, 41.6),
                LonLat(-73.505, 41.8),
            ),
        ),
        Region(
            "pae", "Eastern Pennsylvania", Bounds(-77.05, 39.72, -74.68, 42.0), basemap = true, planned = true,
            area = listOf(
                LonLat(-77.0, 39.722),
                LonLat(-77.0, 42.0),
                LonLat(-75.36, 42.0),
                LonLat(-75.28, 41.95),
                LonLat(-75.13, 41.85),
                LonLat(-75.06, 41.77),
                LonLat(-75.06, 41.61),
                LonLat(-74.99, 41.48),
                LonLat(-74.87, 41.43),
                LonLat(-74.75, 41.39),
                LonLat(-74.69, 41.357),
                LonLat(-74.76, 41.33),
                LonLat(-74.82, 41.27),
                LonLat(-74.87, 41.18),
                LonLat(-74.98, 41.09),
                LonLat(-75.13, 40.99),
                LonLat(-75.1, 40.88),
                LonLat(-75.08, 40.82),
                LonLat(-75.205, 40.69),
                LonLat(-75.19, 40.59),
                LonLat(-75.06, 40.53),
                LonLat(-74.98, 40.41),
                LonLat(-74.94, 40.37),
                LonLat(-74.87, 40.3),
                LonLat(-74.83, 40.27),
                LonLat(-74.77, 40.21),
                LonLat(-74.72, 40.15),
                LonLat(-74.86, 40.08),
                LonLat(-74.97, 40.04),
                LonLat(-75.05, 39.99),
                LonLat(-75.13, 39.95),
                LonLat(-75.14, 39.88),
                LonLat(-75.23, 39.86),
                LonLat(-75.33, 39.84),
                LonLat(-75.415, 39.8),
                LonLat(-75.48, 39.83),
                LonLat(-75.57, 39.84),
                LonLat(-75.66, 39.83),
                LonLat(-75.75, 39.79),
                LonLat(-75.788, 39.722),
            ),
        ),
        Region(
            "paw", "Western Pennsylvania", Bounds(-80.52, 39.72, -76.95, 42.27), basemap = true, planned = true,
            area = listOf(
                LonLat(-80.519, 39.721),
                LonLat(-80.519, 41.98),
                LonLat(-79.762, 42.27),
                LonLat(-79.762, 42.0),
                LonLat(-77.0, 42.0),
                LonLat(-77.0, 39.722),
            ),
        ),
        Region(
            "md", "Maryland, Delaware and DC", Bounds(-79.49, 37.88, -74.94, 39.85), basemap = true, planned = true,
            area = listOf(
                LonLat(-79.477, 39.722),
                LonLat(-75.788, 39.722),
                LonLat(-75.75, 39.79),
                LonLat(-75.66, 39.83),
                LonLat(-75.57, 39.84),
                LonLat(-75.48, 39.83),
                LonLat(-75.415, 39.8),
                LonLat(-75.46, 39.77),
                LonLat(-75.53, 39.7),
                LonLat(-75.56, 39.62),
                LonLat(-75.55, 39.5),
                LonLat(-75.45, 39.35),
                LonLat(-75.3, 39.18),
                LonLat(-75.15, 39.02),
                LonLat(-75.03, 38.88),
                LonLat(-74.95, 38.45),
                LonLat(-75.0, 38.0),
                LonLat(-75.24, 38.03),
                LonLat(-75.63, 37.96),
                LonLat(-76.05, 37.95),
                LonLat(-76.24, 37.9),
                LonLat(-76.32, 38.04),
                LonLat(-76.6, 38.15),
                LonLat(-76.92, 38.26),
                LonLat(-77.25, 38.34),
                LonLat(-77.31, 38.48),
                LonLat(-77.2, 38.6),
                LonLat(-77.06, 38.71),
                LonLat(-77.04, 38.79),
                LonLat(-77.06, 38.86),
                LonLat(-77.12, 38.93),
                LonLat(-77.25, 38.98),
                LonLat(-77.43, 39.07),
                LonLat(-77.53, 39.22),
                LonLat(-77.72, 39.32),
                LonLat(-77.82, 39.48),
                LonLat(-77.95, 39.6),
                LonLat(-78.18, 39.69),
                LonLat(-78.35, 39.63),
                LonLat(-78.55, 39.53),
                LonLat(-78.77, 39.62),
                LonLat(-78.95, 39.45),
                LonLat(-79.15, 39.39),
                LonLat(-79.35, 39.3),
                LonLat(-79.487, 39.21),
            ),
        ),
        Region(STATEWIDE, "New York and Northern New Jersey", Bounds(-79.77, 40.3, -71.75, 45.02), basemap = false),
        Region("all", "New York and nearby states", Bounds(-80.52, 37.88, -71.75, 45.02), basemap = false),
    )

    /** The regions a basemap can be installed for, in the order the picker shows them; planned ones are left out. */
    val basemaps: List<Region> = all.filter { it.basemap && !it.planned }

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
