package app.southbound.feature.explore

import app.southbound.core.map.CameraPin
import app.southbound.core.map.MapPoint
import app.southbound.core.routing.CameraAvoidance
import app.southbound.core.routing.Navigator
import app.southbound.core.routing.RouteSummary
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

    /** Routes found around the cameras, and the spots (one on the road beside each camera) they were found avoiding. */
    class Avoided(val routes: List<Route>, val points: List<CameraAvoidance.Spot>)

    /**
     * "Avoid plate readers" and "Avoid DOT cameras" ([CameraAvoidance] says how and why): starting from the plain
     * [first] routes, excludes the road beside each camera of [categories] (landmark categories, e.g.
     * [Landmark.ALPR_CAMERA]) on the best route and routes again, up to [CameraAvoidance.MAX_PASSES] times while new
     * cameras turn up. A pass that finds no route bisects its new cameras, drops the one on the only way and goes on.
     * Of all the passes' routes, the plain ones included, the ones with the fewest cameras on the best route come
     * back (the quickest when tied). [nav] is left avoiding exactly the spots of the routes returned, so reroutes
     * during the trip match. Blocking: call off the main thread.
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
        // Every road excluded so far, in the order found; each camera's own spots; the cameras on the only way.
        val points = ArrayList<CameraAvoidance.Spot>()
        val spotsOf = HashMap<Long, MutableList<CameraAvoidance.Spot>>()
        val unavoidable = HashSet<Long>()
        var routings = 0
        suspend fun routesAvoiding(extra: List<CameraAvoidance.Spot>): List<Route> {
            routings++
            return try {
                nav.routes(from, waypoints, mode, closures, points + extra)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
        val passes = arrayListOf(Avoided(first, emptyList()))
        var current = first
        var passesLeft = CameraAvoidance.MAX_PASSES
        while (passesLeft-- > 0) {
            if (points.size >= CameraAvoidance.MAX_CAMERAS || routings >= CameraAvoidance.MAX_ROUTINGS) break
            val line = current.firstOrNull()?.line() ?: break
            val fresh = onRoute(index, line, categories)
                .filter { it.id !in unavoidable }
                .mapNotNull { camera ->
                    val spot = spotOn(line, camera)
                    val known = spotsOf[camera.id].orEmpty()
                    // Met again on another road within reach: a second spot. Next to a waypoint: left alone.
                    if (known.any { CameraAvoidance.distanceMeters(it, spot) < CameraAvoidance.SAME_ROAD_METERS }) null
                    else if (!CameraAvoidance.clearOfWaypoints(spot, ends)) null
                    else camera.id to spot
                }
                .take(CameraAvoidance.MAX_CAMERAS - points.size)
            if (fresh.isEmpty()) break
            var batch = fresh
            var next = routesAvoiding(batch.map { it.second })
            if (next.isEmpty()) {
                val split = CameraAvoidance.passable(batch) { part ->
                    routings < CameraAvoidance.MAX_ROUTINGS && routesAvoiding(part.map { it.second }).isNotEmpty()
                }
                split.dropped.forEach { unavoidable += it.first }
                batch = split.kept
                // Every new camera is on the only way: the next pass meets whatever else is there.
                if (batch.isEmpty()) continue
                next = routesAvoiding(batch.map { it.second })
                if (next.isEmpty()) break
            }
            batch.forEach { (id, spot) -> spotsOf.getOrPut(id) { ArrayList() } += spot }
            points += batch.map { it.second }
            current = next
            passes += Avoided(next, points.toList())
        }
        val best = passes.minWith(
            compareBy<Avoided>({ onRoute(index, it.routes.first().line(), categories).size }, { RouteSummary.of(it.routes.first()).durationSeconds }),
        )
        nav.cameraAvoid = best.points
        return best
    }

    /** The cameras of [categories] within [CameraAvoidance.NEAR_ROUTE_METERS] of [line], as the route card counts them. */
    private fun onRoute(index: PlaceSearch, line: RouteLine, categories: List<String>): List<Landmark> = categories.flatMap { category ->
        RouteAlerts.alongRoute(line, near(index, line, CameraAvoidance.NEAR_ROUTE_METERS, category), category, CameraAvoidance.NEAR_ROUTE_METERS)
    }.distinctBy { it.id }

    /** The point of the route beside [camera]: the road to exclude. The camera itself when it can't be placed on the line. */
    private fun spotOn(line: RouteLine, camera: Landmark): CameraAvoidance.Spot {
        val onRoad = line.locate(camera.point)?.let { line.pointAt(it.along) }
        return onRoad?.let { CameraAvoidance.Spot(it.lat, it.lon) } ?: CameraAvoidance.Spot(camera.lat, camera.lon)
    }

    /** Landmarks of [category] (plate readers by default) in small boxes along [line], grown to reach [withinMeters] off it. */
    private fun near(index: PlaceSearch, line: RouteLine, withinMeters: Double, category: String = Landmark.ALPR_CAMERA): List<Landmark> =
        line.boxes(padMeters = withinMeters + 20.0)
            .flatMap { box -> index.landmarks(box, category) }
            .distinctBy { it.id }

    private fun Route.line() = RouteLine(geometry.map { LatLon(it.lat, it.lng) })
}
