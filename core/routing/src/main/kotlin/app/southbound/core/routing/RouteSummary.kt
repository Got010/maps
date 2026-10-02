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

        internal fun of(steps: List<RouteStep>, distance: Double): RouteSummary {
            val via = steps
                .filter { !it.roadName.isNullOrBlank() }
                .groupBy { it.roadName!! }
                .maxByOrNull { (_, legs) -> legs.sumOf { it.distance } }
                ?.key
            return RouteSummary(steps.sumOf { it.duration }, distance, via)
        }
    }
}
