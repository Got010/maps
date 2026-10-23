package app.southbound.feature.explore

import app.southbound.core.search.LatLon
import app.southbound.core.search.RouteAlerts
import app.southbound.core.search.RouteLine
import app.southbound.core.states.Crossing
import app.southbound.core.states.StateOutlines
import uniffi.ferrostar.Route

/**
 * The state lines each route option crosses, for the route sheet's "Crosses into Ohio in 29 mi" (core/states). Worked
 * out from the route's own points and the outlines in the app: no pack and no network, and nothing is kept.
 */
internal object StateLines {
    /** One list per route, in the routes' order. Blocking (a few tens of milliseconds): call off the main thread. */
    fun crossings(routes: List<Route>, outlines: StateOutlines): List<List<Crossing>> = routes.map { route ->
        RouteAlerts.crossings(RouteLine(route.geometry.map { LatLon(it.lat, it.lng) }), outlines)
    }
}
