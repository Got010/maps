package app.southbound.feature.explore

import app.southbound.core.map.CameraPin
import app.southbound.core.map.MapPoint
import app.southbound.core.routing.CameraAvoidance
import app.southbound.core.routing.Navigator
import app.southbound.core.routing.TravelMode
import app.southbound.core.search.Landmark
import app.southbound.core.search.LatLon
import app.southbound.core.search.RouteAlerts
import app.southbound.core.search.RouteExposure
import app.southbound.core.search.RouteLine
import app.southbound.core.search.PlaceSearch
import kotlinx.coroutines.CancellationException
import uniffi.ferrostar.Route
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint

/**
 * Licence-plate readers (Flock and others, from the search pack's landmark
 * table) on the map, on each route option, and the "Avoid plate readers"
 * route option. Everything reads the pack on the phone; nothing is fetched.
 */
internal object PlateReaders {

    private const val ID_PREFIX = "alpr:"

    /** Every plate reader in the pack, as map dots. Empty for a pack without landmarks. */
    fun pins(index: PlaceSearch): List<CameraPin> =
        index.alprCameras().map { CameraPin("$ID_PREFIX${it.id}", MapPoint(it.lat, it.lon), it.name) }

    /**
     * The plate readers and speed cameras each route passes, in the routes' order (a camera facing across
     * the road is left out when the pack says which way it faces). Only counted: the "Avoid plate readers"
     * option steers around plate readers alone, and every one met on the route whichever way it faces.
     * Blocking: call off the main thread.
     */
    fun exposure(index: PlaceSearch, routes: List<Route>): List<RouteExposure> = routes.map { route ->
        val line = route.line()
        RouteAlerts.exposure(
            line,
            near(index, line, RouteAlerts.MAX_OFFSET_M) + near(index, line, RouteAlerts.MAX_OFFSET_M, Landmark.SPEED_CAMERA) +
                near(index, line, RouteAlerts.MAX_OFFSET_M, Landmark.DOT_CAMERA),
        )
    }

    /** Routes found around the cameras, and the camera squares they were found with. */
    class Avoided(val routes: List<Route>, val polygons: List<List<Pair<Double, Double>>>)

    /**
     * "Avoid plate readers" and "Avoid DOT cameras": starting from the plain
     * [first] routes, squares off the cameras of [categories] (landmark
     * categories, e.g. [Landmark.ALPR_CAMERA]) on the best route and routes again, up to
     * [CameraAvoidance.MAX_PASSES] times while new cameras turn up on the new
     * best route. Only the cameras actually met are excluded, never a whole
     * corridor, which keeps within Valhalla's polygon limit. When a pass finds
     * no route (a camera on the only way in, say), the last routes that worked
     * are kept, possibly the plain ones. [nav] is left avoiding exactly the
     * squares of the routes returned, so reroutes during the trip match.
     * Blocking: call off the main thread.
     */
    suspend fun avoid(
        nav: Navigator,
        index: PlaceSearch,
        from: UserLocation,
        waypoints: List<Waypoint>,
        mode: TravelMode,
        closures: List<List<Pair<Double, Double>>>,
        first: List<Route>,
        categories: List<String> = listOf(Landmark.ALPR_CAMERA),
    ): Avoided {
        val ends = listOf(CameraAvoidance.Spot(from.coordinates.lat, from.coordinates.lng)) +
            waypoints.map { CameraAvoidance.Spot(it.coordinate.lat, it.coordinate.lng) }
        // Each camera met so far, where its square goes; kept in the order found.
        val spots = LinkedHashMap<Long, CameraAvoidance.Spot>()
        var best = Avoided(first, emptyList())
        repeat(CameraAvoidance.MAX_PASSES) {
            val route = best.routes.firstOrNull() ?: return best
            if (spots.size >= CameraAvoidance.MAX_CAMERAS) return best
            val line = route.line()
            val fresh = categories.flatMap { category ->
                RouteAlerts.alongRoute(line, near(index, line, CameraAvoidance.NEAR_ROUTE_METERS, category), category, CameraAvoidance.NEAR_ROUTE_METERS)
            }.filter { it.id !in spots }
            if (fresh.isEmpty()) return best
            fresh.forEach { spots[it.id] = squareSpot(line, it) }
            val polygons = CameraAvoidance.polygons(spots.values.toList(), ends)
            // Every new camera sits next to a stop (or past the cap): routing again would change nothing.
            if (polygons.size == best.polygons.size) return best
            val next = try {
                nav.routes(from, waypoints, mode, closures, polygons)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (next.isEmpty()) {
                nav.cameraAvoid = best.polygons
                return best
            }
            best = Avoided(next, polygons)
        }
        return best
    }

    /** The camera itself, or, when it stands well off the line, the point on the route beside it. */
    private fun squareSpot(line: RouteLine, camera: Landmark): CameraAvoidance.Spot {
        val loc = line.locate(camera.point)
        val onRoad = loc?.takeIf { it.offset > CameraAvoidance.SNAP_TO_ROUTE_METERS }?.let { line.pointAt(it.along) }
        return onRoad?.let { CameraAvoidance.Spot(it.lat, it.lon) } ?: CameraAvoidance.Spot(camera.lat, camera.lon)
    }

    /** Landmarks of [category] (plate readers by default) in small boxes along [line], grown to reach [withinMeters] off it. */
    private fun near(index: PlaceSearch, line: RouteLine, withinMeters: Double, category: String = Landmark.ALPR_CAMERA): List<Landmark> =
        line.boxes(padMeters = withinMeters + 20.0)
            .flatMap { box -> index.landmarks(box, category) }
            .distinctBy { it.id }

    private fun Route.line() = RouteLine(geometry.map { LatLon(it.lat, it.lng) })
}
