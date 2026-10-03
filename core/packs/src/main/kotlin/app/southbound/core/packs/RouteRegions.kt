package app.southbound.core.packs

/**
 * Phase 6: which basemap regions a route crosses without the map installed.
 * Routing is statewide, so a route can leave the installed regions; the roads
 * still route but the map would be blank there.
 */
object RouteRegions {
    /** Look at every Nth point of the geometry (plus the last): cheap, and Valhalla points are dense. */
    const val DEFAULT_STEP = 8

    /**
     * Basemap regions the route passes through that aren't in [installed], in
     * the order the route meets them. [geometry] is (lat, lon) pairs. A point
     * inside an installed region (regions overlap) counts as covered; a point
     * outside every region (another state) is ignored.
     */
    fun missing(geometry: List<Pair<Double, Double>>, installed: Set<String>, step: Int = DEFAULT_STEP): List<Region> {
        if (geometry.isEmpty()) return emptyList()
        val stride = step.coerceAtLeast(1)
        val sampled = geometry.filterIndexed { i, _ -> i % stride == 0 } + geometry.last()
        val found = LinkedHashMap<String, Region>()
        for ((lat, lon) in sampled) {
            val containing = Regions.basemaps.filter { it.bounds.contains(lat, lon) }
            if (containing.isEmpty() || containing.any { it.id in installed }) continue
            containing.first().let { found.putIfAbsent(it.id, it) }
        }
        return found.values.toList()
    }
}
