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

    // Phase 6: route options, used by previews, reroutes and skip-stop alike. Set by the app.
    @Volatile
    var prefs: RoutePrefs = RoutePrefs.DEFAULT

    private val core = FerrostarCore(
        customRouteProvider = object : CustomRouteProvider {
            override suspend fun getRoutes(userLocation: UserLocation, waypoints: List<Waypoint>): List<Route> =
                withContext(Dispatchers.Default) { router.routes(userLocation, waypoints, mode, avoid, prefs) }
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

    /** Routes for preview, best first, around [avoid] (kept for the trip's reroutes). */
    suspend fun routes(
        from: UserLocation,
        waypoints: List<Waypoint>,
        mode: TravelMode,
        avoid: List<List<Pair<Double, Double>>> = this.avoid,
    ): List<Route> {
        this.mode = mode
        this.avoid = avoid
        return core.getRoutes(from, waypoints)
    }

    fun start(route: Route, mode: TravelMode) {
        this.mode = mode
        core.startNavigation(route, controllerConfig(mode))
    }

    /**
     * Mid-trip, routes again from where the user is to the remaining waypoints
     * around [avoid] (a closure that just appeared on the route) and switches
     * to the new route. False when not navigating or no route was found.
     */
    suspend fun rerouteAround(avoid: List<List<Pair<Double, Double>>>): Boolean {
        val trip = state.value.tripState as? TripState.Navigating ?: return false
        this.avoid = avoid
        val routes = try {
            withContext(Dispatchers.Default) { router.routes(trip.snappedUserLocation, trip.remainingWaypoints, mode, avoid, prefs) }
        } catch (e: RoutingException) {
            return false
        }
        core.replaceRoute(routes.first(), controllerConfig(mode))
        return true
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
            withContext(Dispatchers.Default) { router.routes(trip.snappedUserLocation, remaining, mode, avoid, prefs) }
        } catch (e: RoutingException) {
            return false
        }
        core.replaceRoute(routes.first(), controllerConfig(mode))
        return true
    }

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
