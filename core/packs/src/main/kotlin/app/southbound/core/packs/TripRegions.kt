package app.southbound.core.packs

/**
 * Regions fetched for one trip from the route preview ("Get for this trip"), as opposed to the user's own regions
 * ticked in Settings. Each brings its map, search and local roads like an own region, and goes again once the trip
 * is over: when the phone, not navigating, is back in one of its own regions after having been in it. A trip region
 * the phone has not been in yet stays (the trip hasn't happened), so fetching the night before is safe.
 *
 * Only regions are ever kept here, never where the phone went: the record is which trip regions it has been in.
 */
object TripRegions {

    /** What one location fix changes: trip regions the phone is in for the first time, and ones to remove now. */
    data class Step(val visited: Set<String>, val drop: Set<String>) {
        val isEmpty: Boolean get() = visited.isEmpty() && drop.isEmpty()
    }

    /**
     * The step for a fix at [lat], [lon]. [own] are the user's regions, [trip] the trip regions, [visited] the trip
     * regions the phone has already been in; [navigating] holds removal until the trip ends (a region behind the
     * car may be the way back).
     */
    fun onFix(lat: Double, lon: Double, own: Set<String>, trip: Set<String>, visited: Set<String>, navigating: Boolean): Step {
        if (trip.isEmpty()) return Step(emptySet(), emptySet())
        val here = Regions.basemapsAt(lat, lon).map { it.id }.toSet()
        val newlyVisited = (here intersect trip) - visited
        val home = !navigating && (here intersect trip).isEmpty() && (here intersect own).isNotEmpty()
        val drop = if (home) (visited + newlyVisited) intersect trip else emptySet()
        return Step(newlyVisited, drop)
    }
}
