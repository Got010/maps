package app.southbound.core.routing

import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteStep

/** What route preview shows for each choice. */
data class RouteSummary(
    val durationSeconds: Double,
    val distanceMeters: Double,
    /** The road the route spends longest on, e.g. "FDR Drive". */
    val via: String?,
) {
    companion object {
        fun of(route: Route): RouteSummary = of(route.steps, route.distance)

        internal fun of(steps: List<RouteStep>, distance: Double): RouteSummary =
            RouteSummary(steps.sumOf { it.duration }, distance, longestRoad(steps.map { it.roadName to it.distance }))

        /**
         * [of], with "via" the road [route] spends longest on that [other] doesn't use, so two options that share
         * the long part tell apart (the shorter way, [ShorterRoute]); the plain one when every road is shared.
         */
        fun apartFrom(route: Route, other: Route): RouteSummary {
            val plain = of(route)
            val own = longestRoad(route.steps.map { it.roadName to it.distance }, except = other.steps.mapNotNull { it.roadName }.toSet())
            return if (own != null) plain.copy(via = own) else plain
        }

        /** The named road with the most distance among (name, metres) pairs, leaving out [except]; null if none. */
        internal fun longestRoad(roads: List<Pair<String?, Double>>, except: Set<String> = emptySet()): String? =
            roads
                .mapNotNull { (name, meters) -> name?.takeIf { it.isNotBlank() && it !in except }?.let { it to meters } }
                .groupBy({ it.first }, { it.second })
                .maxByOrNull { (_, lengths) -> lengths.sum() }
                ?.key
    }
}
