package app.southbound.core.routing

import android.content.Context
import com.stadiamaps.ferrostar.composeui.notification.DefaultForegroundNotificationBuilder
import com.stadiamaps.ferrostar.core.CustomRouteProvider
import com.stadiamaps.ferrostar.core.FerrostarCore
import com.stadiamaps.ferrostar.core.NavigationState
import com.stadiamaps.ferrostar.core.SpokenInstructionObserver
import com.stadiamaps.ferrostar.core.http.HttpClientProvider
import com.stadiamaps.ferrostar.core.http.IResponse
import com.stadiamaps.ferrostar.core.service.FerrostarForegroundServiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import uniffi.ferrostar.CourseFiltering
import uniffi.ferrostar.NavigationControllerConfig
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteDeviationTracking
import uniffi.ferrostar.RouteRequest
import uniffi.ferrostar.TripState
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointAdvanceMode
import uniffi.ferrostar.stepAdvanceDistanceEntryAndExit
import uniffi.ferrostar.stepAdvanceDistanceToEndOfStep

/**
 * Turn-by-turn navigation: Ferrostar's engine, fed by GPS and routed on the
 * phone by [router]. When the user strays, Ferrostar asks [router] for a new
 * route, so rerouting works offline too.
 *
 * A foreground service keeps GPS and guidance running with the screen off,
 * and its notification shows the next turn.
 */
class Navigator(context: Context, private val router: OfflineRouter) {

    /** The mode new routes and reroutes use. */
    @Volatile
    var mode: TravelMode = TravelMode.DRIVE

    /**
     * Polygons (rings of lon, lat) every route and reroute steers around:
     * the closures the traffic layer knows about. Set by the app.
     */
    @Volatile
    var avoid: List<List<Pair<Double, Double>>> = emptyList()

    /**
     * The roads past plate readers the trip steers clear of ("Avoid plate readers", see [CameraAvoidance]): a spot on
     * each, sent as Valhalla `exclude_locations`, kept apart from the closures in [avoid] so a closure reroute doesn't
     * drop them. Every route request gets both.
     */
    @Volatile
    var cameraAvoid: List<CameraAvoidance.Spot> = emptyList()

    // Phase 6: route options, used by previews, reroutes and skip-stop alike. Set by the app.
    @Volatile
    var prefs: RoutePrefs = RoutePrefs.DEFAULT

    private val core = FerrostarCore(
        customRouteProvider = object : CustomRouteProvider {
            // A preview, or Ferrostar rerouting a trip the driver strayed from: that one starts where the car is.
            override suspend fun getRoutes(userLocation: UserLocation, waypoints: List<Waypoint>): List<Route> =
                withContext(Dispatchers.Default) {
                    if (state.value.tripState is TripState.Navigating) {
                        routesFromCar(userLocation, waypoints, avoid)
                    } else {
                        router.routes(userLocation, waypoints, mode, avoid, cameraAvoid, prefs)
                    }
                }
        },
        httpClient = NoNetworkHttpClient,
        locationProvider = GpsLocationProvider(context.applicationContext),
        navigationControllerConfig = controllerConfig(),
        foregroundServiceManager = FerrostarForegroundServiceManager(
            context.applicationContext,
            DefaultForegroundNotificationBuilder(context.applicationContext),
        ),
    )

    val state: StateFlow<NavigationState> get() = core.state

    /** Speaks instructions; set by the voice module. */
    var spokenInstructionObserver: SpokenInstructionObserver?
        get() = core.spokenInstructionObserver
        set(value) {
            core.spokenInstructionObserver = value
        }

    /**
     * Routes for preview, best first, around [avoid] (closures) and
     * [cameraAvoid] (plate readers), both kept for the trip's reroutes.
     */
    suspend fun routes(
        from: UserLocation,
        waypoints: List<Waypoint>,
        mode: TravelMode,
        avoid: List<List<Pair<Double, Double>>> = this.avoid,
        cameraAvoid: List<CameraAvoidance.Spot> = emptyList(),
    ): List<Route> {
        this.mode = mode
        this.avoid = avoid
        this.cameraAvoid = cameraAvoid
        return core.getRoutes(from, waypoints)
    }

    /**
     * The driving route with distance weighed in ([ShorterRoute]), around the same closures and plate readers as
     * the last [routes] call, for the preview to offer beside those when it is faster and shorter. Null when not
     * driving or there is none.
     */
    suspend fun shorterRoute(from: UserLocation, waypoints: List<Waypoint>): Route? {
        if (mode != TravelMode.DRIVE) return null
        return withContext(Dispatchers.Default) {
            try {
                router.routes(from, waypoints, mode, avoid, cameraAvoid, prefs, useDistance = ShorterRoute.USE_DISTANCE, alternates = false).firstOrNull()
            } catch (e: RoutingException) {
                null
            }
        }
    }

    fun start(route: Route, mode: TravelMode) {
        this.mode = mode
        core.startNavigation(route, controllerConfig(mode))
    }

    /**
     * Mid-trip, routes again from where the user is to the remaining waypoints
     * around [avoid] (a closure that just appeared on the route) and switches
     * to the new route. The plate readers in [cameraAvoid] stay avoided, but
     * for those beside the car ([CameraAvoidance.fromCar]).
     * False when not navigating or no route was found.
     */
    suspend fun rerouteAround(avoid: List<List<Pair<Double, Double>>>): Boolean {
        val trip = state.value.tripState as? TripState.Navigating ?: return false
        this.avoid = avoid
        val routes = try {
            withContext(Dispatchers.Default) { routesFromCar(trip.snappedUserLocation, trip.remainingWaypoints, avoid) }
        } catch (e: RoutingException) {
            return false
        }
        return replaceIfNavigating(routes.first())
    }

    // Phase 6: skip the next stop of a multi-stop trip.
    /**
     * Drops the next waypoint (a stop, never the destination) and routes from
     * where the user is to what remains, switching to that route. False when
     * there is no stop to skip, or no route was found (the trip carries on).
     */
    suspend fun skipNextStop(): Boolean {
        val trip = state.value.tripState as? TripState.Navigating ?: return false
        if (!TripStops.canSkip(trip.remainingWaypoints.size)) return false
        val remaining = TripStops.skipNext(trip.remainingWaypoints)
        val routes = try {
            withContext(Dispatchers.Default) { routesFromCar(trip.snappedUserLocation, remaining, avoid) }
        } catch (e: RoutingException) {
            return false
        }
        return replaceIfNavigating(routes.first())
    }

    /**
     * A fresh route from where the user is to the remaining waypoints around
     * the current [avoid] polygons plus [extraAvoid] (e.g. around a crawl ahead)
     * and [cameraAvoid], with the driver's route options,
     * without switching to it: the mid-trip "faster route" check compares it
     * with the current one. Null when not navigating or no route was found.
     */
    suspend fun freshRoute(extraAvoid: List<List<Pair<Double, Double>>> = emptyList()): Route? {
        val trip = state.value.tripState as? TripState.Navigating ?: return null
        // Only the plate readers clear of the car are left out; a route through the rest is no offer worth making.
        val spots = CameraAvoidance.clearOfCar(cameraAvoid, trip.snappedUserLocation.spot())
        return try {
            withContext(Dispatchers.Default) { router.routes(trip.snappedUserLocation, trip.remainingWaypoints, mode, avoid + extraAvoid, spots, prefs) }.firstOrNull()
        } catch (e: RoutingException) {
            null
        }
    }

    /** Switches the trip to [route] (one from [freshRoute]), keeping the travel mode. False when the trip has ended. */
    fun switchTo(route: Route): Boolean = replaceIfNavigating(route)

    /**
     * Routes from [from] (where the car is) to [waypoints] around [avoid] and the plate readers, dropping the ones the
     * car is on or beside ([CameraAvoidance.fromCar]).
     */
    private fun routesFromCar(from: UserLocation, waypoints: List<Waypoint>, avoid: List<List<Pair<Double, Double>>>): List<Route> =
        CameraAvoidance.fromCar(cameraAvoid, from.spot()) { spots -> router.routes(from, waypoints, mode, avoid, spots, prefs) }

    // A routing takes a second or more: the trip may have been ended or paused meanwhile, and a route put in then
    // would start a trip nobody asked for.
    private fun replaceIfNavigating(route: Route): Boolean {
        if (state.value.tripState !is TripState.Navigating) return false
        core.replaceRoute(route, controllerConfig(mode))
        return true
    }

    private fun UserLocation.spot() = CameraAvoidance.Spot(coordinates.lat, coordinates.lng)

    fun stop() = core.stopNavigation()

    companion object {
        /**
         * When to move to the next step and when the user counts as off route.
         * Walkers get more slack, since phone GPS on foot wanders more.
         */
        fun controllerConfig(mode: TravelMode = TravelMode.DRIVE): NavigationControllerConfig {
            val maxDeviation = if (mode == TravelMode.WALK) 40.0 else 50.0
            return NavigationControllerConfig(
                waypointAdvance = WaypointAdvanceMode.WaypointWithinRange(100.0),
                stepAdvanceCondition = stepAdvanceDistanceEntryAndExit(
                    distanceToEndOfStep = 30.toUShort(),
                    distanceAfterEndOfStep = 5.toUShort(),
                    minimumHorizontalAccuracy = 32.toUShort(),
                ),
                arrivalStepAdvanceCondition = stepAdvanceDistanceToEndOfStep(
                    distance = 30.toUShort(),
                    minimumHorizontalAccuracy = 32.toUShort(),
                ),
                routeDeviationTracking = RouteDeviationTracking.StaticThreshold(
                    minimumHorizontalAccuracy = 25.toUShort(),
                    maxAcceptableDeviation = maxDeviation,
                ),
                snappedLocationCourseFiltering = CourseFiltering.SNAP_TO_ROUTE,
            )
        }
    }
}

/**
 * Ferrostar requires an HTTP client, but routes come from [OfflineRouter], so
 * this one is never called. If it ever were, it refuses rather than reaching
 * the network outside NetworkGate.
 */
internal object NoNetworkHttpClient : HttpClientProvider {
    override suspend fun call(request: RouteRequest): IResponse =
        throw IllegalStateException("Southbound routes offline; no HTTP route requests")
}
