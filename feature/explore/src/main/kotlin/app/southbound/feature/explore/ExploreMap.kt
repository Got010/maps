package app.southbound.feature.explore

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.southbound.core.map.ArrivalSide
import app.southbound.core.map.Arrival
import app.southbound.core.map.CameraRequest
import app.southbound.core.map.MapLine
import app.southbound.core.map.IncidentPin
import app.southbound.core.map.MapOverlays
import app.southbound.core.map.MapPoint
import app.southbound.core.map.SpeedLine
import app.southbound.core.map.TrafficOverlay
import app.southbound.core.map.NYC_CENTER
import app.southbound.core.map.SouthboundMap
import app.southbound.core.map.gpsUpdates
import app.southbound.core.packs.ImportedPack
import app.southbound.core.packs.PackKind
import app.southbound.core.packs.Regions
import app.southbound.core.routing.Navigator
import app.southbound.core.routing.RouteSummary
import app.southbound.core.routing.SpeedLimits
import app.southbound.core.routing.TravelMode
import app.southbound.core.search.LatLon
import app.southbound.core.search.Place
import app.southbound.core.search.SearchIndex
import app.southbound.core.search.SearchRanking
import app.southbound.core.traffic.ClosureAvoidance
import app.southbound.core.traffic.FasterRoute
import app.southbound.core.traffic.Geo
import app.southbound.core.traffic.GeoPoint
import app.southbound.core.traffic.Incident
import app.southbound.core.traffic.RouteSlowdowns
import app.southbound.core.traffic.RouteTraffic
import app.southbound.core.traffic.SpeedBand
import app.southbound.core.traffic.TrafficSnapshot
import app.southbound.core.transit.TransitPack
import app.southbound.core.voice.GuidanceMode
import app.southbound.core.voice.UtteranceKind
import app.southbound.feature.directions.RoutePreviewSheet
import app.southbound.feature.directions.RoutePreviewState
import app.southbound.feature.navigation.FasterRouteOffer
import app.southbound.feature.navigation.NavigationOverlay
import app.southbound.feature.search.PlaceCard
import app.southbound.feature.search.SearchPanel
import app.southbound.feature.transit.StationBoard
import app.southbound.feature.transit.TransitDepartures
import app.southbound.feature.transit.TransitPlanSheet
import app.southbound.feature.transit.TransitPlanState
import app.southbound.feature.transit.TransitTime
import com.stadiamaps.ferrostar.core.NavigationState
import com.stadiamaps.ferrostar.core.location.toAndroidLocation
import com.stadiamaps.ferrostar.core.location.toUserLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.Route
import uniffi.ferrostar.TripState
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointKind
import kotlin.math.log2

/** What is open over the map, outside of a trip. */
private sealed interface Panel {
    data object Browse : Panel
    data object Search : Panel
    data class PlaceShown(val place: Place) : Panel
    data class Directions(val place: Place, val mode: TravelMode, val transit: Boolean = false) : Panel
}

private const val PLACE_ZOOM = 16.0

/** Space the bottom sheets take, so camera moves keep places and routes above them. */
private val SHEET_HEIGHT = 300.dp

/**
 * The map with everything on it: search, the place card, route preview and
 * turn-by-turn navigation. All of it runs from packs on the phone.
 */
@Composable
internal fun ExploreMap(
    basemaps: List<ImportedPack>,
    services: ExploreServices,
    packGeneration: Int,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // Location permission and GPS, as in Phase 0.
    var hasPermission by remember { mutableStateOf(context.hasFineLocation()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(RequestMultiplePermissions()) {
        hasPermission = context.hasFineLocation()
    }
    fun requestPermission() {
        asked = true
        permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }
    LaunchedEffect(Unit) {
        if (!hasPermission && !asked) requestPermission()
    }
    // Resuming also re-checks the packs, in case one was imported meanwhile.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        hasPermission = context.hasFineLocation()
        resumes++
        onPauseOrDispose { }
    }

    var location by remember { mutableStateOf<Location?>(null) }
    var following by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        val locationManager = context.getSystemService(LocationManager::class.java)
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            locationManager.collectGps { location = it }
        }
    }

    // Packs: search, routing and transit open off the main thread.
    var searchIndex by remember { mutableStateOf<SearchIndex?>(null) }
    var navigator by remember { mutableStateOf<Navigator?>(null) }
    var hasRouting by remember { mutableStateOf(false) }
    var transitPack by remember { mutableStateOf<TransitPack?>(null) }
    LaunchedEffect(packGeneration, resumes) {
        val inTrip = navigator?.state?.value?.tripState.let { it != null && it !is TripState.Idle }
        withContext(Dispatchers.IO) {
            searchIndex = services.openSearch()
            navigator = services.openNavigator(navigating = inTrip)
            hasRouting = services.hasPack(PackKind.ROUTING)
            transitPack = services.openTransit()
        }
    }

    // Departure countdowns tick on New York time.
    var nowSeconds by remember { mutableIntStateOf(secondsOfDay()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            nowSeconds = secondsOfDay()
        }
    }

    val idle = remember { MutableStateFlow(NavigationState()) }
    val navState by (navigator?.state ?: idle).collectAsState()
    val trip = navState.tripState
    val inTrip = trip !is TripState.Idle
    val voiceSettings by services.voiceSettings.collectAsState()

    var panel by remember { mutableStateOf<Panel>(Panel.Browse) }
    var routes by remember { mutableStateOf<List<Route>>(emptyList()) }
    var preview by remember { mutableStateOf<RoutePreviewState>(RoutePreviewState.Loading) }
    var cameraRequest by remember { mutableStateOf<CameraRequest?>(null) }
    val near = location?.let { LatLon(it.latitude, it.longitude) }
    var transitTime by remember { mutableStateOf(TransitTime()) }
    var transitPlan by remember { mutableStateOf<TransitPlanState>(TransitPlanState.Loading) }
    var transitSelected by remember { mutableIntStateOf(0) }
    var boards by remember { mutableStateOf<List<StationBoard>>(emptyList()) }
    // A start point picked on the map ("Start here"), e.g. to plan from outside NYC. Null: from GPS.
    var startPlace by remember { mutableStateOf<Place?>(null) }

    // Saved places and recents, kept encrypted on the phone.
    val userData by services.userData.data.collectAsState()
    var saveDialogFor by remember { mutableStateOf<Place?>(null) }

    // Live traffic: shown on request, and watched for closures during a trip.
    var trafficVisible by rememberSaveable { mutableStateOf(false) }
    var trafficOn by remember { mutableStateOf(services.traffic.isEnabled) }
    val traffic by services.traffic.snapshot.collectAsState()
    var shownIncident by remember { mutableStateOf<Incident?>(null) }
    LaunchedEffect(resumes) { trafficOn = services.traffic.isEnabled }

    // --- Phase 5 (map): touch, traffic on the route, faster route, arrival details ---
    // Re-center: while navigating, 10 s without a touch after leaving the puck snaps back to following.
    var touching by remember { mutableStateOf(false) }
    var touchSerial by remember { mutableIntStateOf(0) }
    // Slowdowns and incidents along the selected route (preview) or what's left of the trip.
    var previewTraffic by remember { mutableStateOf<List<RouteSlowdowns>>(emptyList()) }
    var tripTraffic by remember { mutableStateOf(RouteSlowdowns.NONE) }
    var routeIncidents by remember { mutableStateOf<List<Incident>>(emptyList()) }
    var fasterOffer by remember { mutableStateOf<FasterOffer?>(null) }
    val dismissedRoutes = remember { FasterRoute.Dismissals() }
    var arrivalSide by remember { mutableStateOf<ArrivalSide?>(null) }
    var arrivalPoint by remember { mutableStateOf<MapPoint?>(null) }
    var parkingNearby by remember { mutableStateOf(false) }
    // --- end Phase 5 (map) state ---
    fun origin(): Location? = startPlace?.let { place ->
        Location("start").apply {
            latitude = place.lat
            longitude = place.lon
            accuracy = 5f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
    } ?: location

    fun showPlace(place: Place) {
        panel = Panel.PlaceShown(place)
        following = false
        cameraRequest = CameraRequest.Show(MapPoint(place.lat, place.lon), PLACE_ZOOM)
        scope.launch(Dispatchers.IO) { attempt { services.userData.recordRecent(place.toRef()) } }
    }

    // Route preview: recompute when the destination or mode changes, or when
    // the first GPS fix arrives after "no location".
    val directions = panel as? Panel.Directions
    val hasFix = location != null || startPlace != null
    LaunchedEffect(directions, hasFix, navigator, startPlace) {
        val target = directions?.takeIf { !it.transit } ?: return@LaunchedEffect
        routes = emptyList()
        previewTraffic = emptyList()
        val nav = navigator ?: run {
            preview = RoutePreviewState.Failed(RoutePreviewState.Reason.NO_ROUTE)
            return@LaunchedEffect
        }
        val from = origin() ?: run {
            preview = RoutePreviewState.Failed(RoutePreviewState.Reason.NO_LOCATION)
            return@LaunchedEffect
        }
        preview = RoutePreviewState.Loading
        val destination = Waypoint(GeographicCoordinate(target.place.lat, target.place.lon), WaypointKind.BREAK)
        // Steer around 511NY closures near the trip, when that feed is on.
        // Any traffic feed that is on is fetched (cached for its interval) so options can show their delay.
        val snapshot = if (services.traffic.isEnabled) withContext(Dispatchers.IO) { attempt { services.traffic.refresh() } } else null
        val avoid = if (services.traffic.hasIncidents) {
            ClosureAvoidance.polygons(snapshot?.closures.orEmpty(), GeoPoint(from.latitude, from.longitude), GeoPoint(target.place.lat, target.place.lon))
        } else {
            emptyList()
        }
        val found = try {
            withContext(Dispatchers.Default) { nav.routes(from.toUserLocation(), listOf(destination), target.mode, avoid) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        routes = found
        // Live speeds laid over each option: coloured stretches and the delay they add.
        val speeds = snapshot?.speeds.orEmpty()
        val slow = withContext(Dispatchers.Default) {
            found.map { RouteTraffic.slowdowns(it.geometry.map { p -> GeoPoint(p.lat, p.lng) }, speeds) }
        }
        previewTraffic = slow
        if (found.isEmpty()) {
            preview = RoutePreviewState.Failed(RoutePreviewState.Reason.NO_ROUTE)
        } else {
            preview = RoutePreviewState.Ready(found.map(RouteSummary::of), selected = 0, delaySeconds = slow.map { it.delaySeconds })
            cameraRequest = CameraRequest.Fit(
                found.flatMap { route -> route.geometry.map { MapPoint(it.lat, it.lng) } } +
                    MapPoint(from.latitude, from.longitude),
            )
        }
    }

    // Transit: plan from the pack, then layer realtime on top if it's on.
    LaunchedEffect(directions, transitTime, hasFix, transitPack, startPlace) {
        val target = directions?.takeIf { it.transit } ?: return@LaunchedEffect
        val pack = transitPack ?: return@LaunchedEffect
        val from = origin() ?: run {
            transitPlan = TransitPlanState.Failed(TransitPlanState.Reason.NO_LOCATION)
            return@LaunchedEffect
        }
        transitPlan = TransitPlanState.Loading
        transitSelected = 0
        val plan = withContext(Dispatchers.Default) {
            attempt { planTransit(pack, from.latitude, from.longitude, target.place.lat, target.place.lon, transitTime) }
        } ?: TransitPlanState.Failed(TransitPlanState.Reason.NO_TRIPS)
        transitPlan = plan
        val ready = plan as? TransitPlanState.Ready ?: return@LaunchedEffect
        cameraRequest = CameraRequest.Fit(
            ready.journeys.first().mapLines(MapPoint(from.latitude, from.longitude), MapPoint(target.place.lat, target.place.lon))
                .flatMap { it.points },
        )
        val snapshot = withContext(Dispatchers.IO) {
            attempt { services.realtime.snapshot(includeBus = ready.journeys.any { it.usesBus() }) }
        } ?: return@LaunchedEffect
        transitPlan = ready.copy(live = liveFor(snapshot, ready.journeys))
    }

    // Departure boards for the place on the card, refreshed with the clock.
    val shownPlace = (panel as? Panel.PlaceShown)?.place
    LaunchedEffect(shownPlace, transitPack, nowSeconds / 60) {
        val place = shownPlace ?: run {
            boards = emptyList()
            return@LaunchedEffect
        }
        val pack = transitPack ?: return@LaunchedEffect
        val scheduled = withContext(Dispatchers.Default) { attempt { boardsNear(pack, place.lat, place.lon, null) } }.orEmpty()
        boards = scheduled
        if (scheduled.isEmpty()) return@LaunchedEffect
        val snapshot = withContext(Dispatchers.IO) {
            attempt { services.realtime.snapshot(includeBus = pack.hasBusNear(place.lat, place.lon)) }
        } ?: return@LaunchedEffect
        boards = withContext(Dispatchers.Default) { attempt { boardsNear(pack, place.lat, place.lon, snapshot) } } ?: scheduled
    }

    // A trip takes over the map and follows the user; ending it returns to browsing.
    LaunchedEffect(inTrip) {
        if (inTrip) {
            following = true
        } else if (panel is Panel.Directions) {
            panel = Panel.Browse
            following = true
        }
    }
    // Poll the feeds while the layer is shown or a trip is on; each feed keeps its own interval.
    LaunchedEffect(trafficVisible, inTrip, trafficOn) {
        if (!trafficOn || !(trafficVisible || inTrip)) return@LaunchedEffect
        while (true) {
            withContext(Dispatchers.IO) { attempt { services.traffic.refresh() } }
            delay(TRAFFIC_POLL_MS)
        }
    }
    // A closure that appears on the route ahead: say so and route around it.
    val closureSpoken = stringResource(R.string.explore_closure_spoken)
    val announcedClosures = remember { mutableSetOf<String>() }
    LaunchedEffect(inTrip) { if (!inTrip) announcedClosures.clear() }
    LaunchedEffect(traffic, inTrip) {
        val nav = navigator ?: return@LaunchedEffect
        if (!inTrip || traffic.closures.isEmpty()) return@LaunchedEffect
        val ahead = ClosureAvoidance.onRoute(traffic.closures, navState.routeGeometry.map { GeoPoint(it.lat, it.lng) })
            .filter { it.id !in announcedClosures }
        if (ahead.isEmpty()) return@LaunchedEffect
        announcedClosures += ahead.map { it.id }
        services.voice.announce(UtteranceKind.TRAFFIC_ALERT, closureSpoken.format(ahead.first().title))
        nav.rerouteAround(ClosureAvoidance.polygons(traffic.closures))
    }

    // --- Phase 5 (map): re-center timer, route traffic, faster route, arrival ---
    LaunchedEffect(inTrip, following, touching, touchSerial) {
        if (!inTrip || following || touching) return@LaunchedEffect
        delay(AUTO_RECENTER_MS)
        following = true
    }

    // Slow stretches and incidents ahead on the trip, redone as traffic or progress changes.
    val tripProgressBucket = ((trip as? TripState.Navigating)?.progress?.distanceRemaining ?: 0.0).toInt() / 250
    LaunchedEffect(inTrip, navState.routeGeometry.size, traffic, tripProgressBucket) {
        val navigating = trip as? TripState.Navigating
        if (navigating == null) {
            tripTraffic = RouteSlowdowns.NONE
            routeIncidents = emptyList()
            return@LaunchedEffect
        }
        val here = navigating.snappedUserLocation.coordinates
        val speeds = traffic.speeds
        val incidents = traffic.incidents
        val geometry = navState.routeGeometry
        withContext(Dispatchers.Default) {
            val ahead = RouteTraffic.remaining(geometry.map { GeoPoint(it.lat, it.lng) }, GeoPoint(here.lat, here.lng))
            tripTraffic = RouteTraffic.slowdowns(ahead, speeds)
            routeIncidents = RouteTraffic.incidentsOnRoute(ahead, incidents)
        }
    }
    // Preview: incidents along the selected option.
    val previewSelected = (preview as? RoutePreviewState.Ready)?.selected ?: 0
    LaunchedEffect(directions, routes, previewSelected, traffic.incidents) {
        if (directions == null || directions.transit || inTrip) return@LaunchedEffect
        val route = routes.getOrNull(previewSelected) ?: run {
            routeIncidents = emptyList()
            return@LaunchedEffect
        }
        val incidents = traffic.incidents
        routeIncidents = withContext(Dispatchers.Default) {
            RouteTraffic.incidentsOnRoute(route.geometry.map { GeoPoint(it.lat, it.lng) }, incidents)
        }
    }
    LaunchedEffect(directions, inTrip) { if (directions == null && !inTrip) routeIncidents = emptyList() }

    // Every few minutes of a trip with traffic on: is there a route that now saves 5+ minutes?
    val latestNav by rememberUpdatedState(navState)
    val latestTraffic by rememberUpdatedState(traffic)
    LaunchedEffect(inTrip, trafficOn, navigator) {
        if (!inTrip) {
            fasterOffer = null
            return@LaunchedEffect
        }
        val nav = navigator
        if (!trafficOn || nav == null) return@LaunchedEffect
        while (true) {
            delay(FasterRoute.CHECK_MILLIS)
            val snapshot = latestNav
            val tripNow = snapshot.tripState as? TripState.Navigating ?: continue
            if (snapshot.isCalculatingNewRoute || fasterOffer != null) continue
            val speeds = latestTraffic.speeds
            val here = tripNow.snappedUserLocation.coordinates.let { GeoPoint(it.lat, it.lng) }
            val current = RouteTraffic.remaining(snapshot.routeGeometry.map { GeoPoint(it.lat, it.lng) }, here)
            val currentSlow = withContext(Dispatchers.Default) { RouteTraffic.slowdowns(current, speeds) }
            // Valhalla on the phone doesn't know live speeds, so a plain fresh route is usually the same road.
            // When the route ahead crawls, also try one that steers around the worst stretches.
            val detours = if (currentSlow.delaySeconds >= FASTER_ROUTE_TRY_DETOUR_SECONDS) {
                currentSlow.segments
                    .filter { it.band != SpeedBand.FREE && it.points.size >= 2 }
                    .map { it.points[it.points.size / 2] }
                    .filter { Geo.distanceMeters(here, it) > DETOUR_MIN_DISTANCE_M }
                    .take(DETOUR_MAX_SPOTS)
                    .map { ClosureAvoidance.square(it) }
            } else {
                emptyList()
            }
            var best: FasterOffer? = null
            val attempts: List<List<List<Pair<Double, Double>>>> = if (detours.isEmpty()) listOf(emptyList()) else listOf(emptyList(), detours)
            for (extra in attempts) {
                val candidate = attempt { withContext(Dispatchers.Default) { nav.freshRoute(extra) } } ?: continue
                val points = candidate.geometry.map { GeoPoint(it.lat, it.lng) }
                if (dismissedRoutes.isDismissed(points, System.currentTimeMillis())) continue
                val candidateDelay = withContext(Dispatchers.Default) { RouteTraffic.slowdowns(points, speeds).delaySeconds }
                val saving = FasterRoute.savingSeconds(
                    tripNow.progress.durationRemaining,
                    currentSlow.delaySeconds,
                    RouteSummary.of(candidate).durationSeconds,
                    candidateDelay,
                )
                val minutes = FasterRoute.offerMinutes(saving) ?: continue
                if (best == null || minutes > best.savesMinutes) best = FasterOffer(candidate, points, minutes)
            }
            fasterOffer = best
        }
    }
    // An offer nobody answers goes away on its own, and isn't counted as dismissed.
    LaunchedEffect(fasterOffer) {
        if (fasterOffer == null) return@LaunchedEffect
        delay(FASTER_ROUTE_OFFER_MS)
        fasterOffer = null
    }

    // Which side of the street the destination is on, from the route's end, and parking near it on arrival.
    val navigatingNow = trip as? TripState.Navigating
    val lastWaypoint = navigatingNow?.remainingWaypoints?.lastOrNull()
    LaunchedEffect(lastWaypoint, navState.routeGeometry.size) {
        if (!inTrip || lastWaypoint == null) return@LaunchedEffect
        val destination = MapPoint(lastWaypoint.coordinate.lat, lastWaypoint.coordinate.lng)
        arrivalPoint = destination
        arrivalSide = Arrival.side(navState.routeGeometry.takeLast(30).map { MapPoint(it.lat, it.lng) }, destination)
    }
    LaunchedEffect(trip is TripState.Complete) {
        if (trip !is TripState.Complete) {
            parkingNearby = false
            if (!inTrip) arrivalSide = null
            return@LaunchedEffect
        }
        val point = arrivalPoint ?: return@LaunchedEffect
        val index = searchIndex ?: return@LaunchedEffect
        parkingNearby = withContext(Dispatchers.IO) {
            attempt { index.nearby("parking", point.lat, point.lon, PARKING_RADIUS_M, limit = 1).isNotEmpty() }
        } == true
    }
    // --- end Phase 5 (map) effects ---

    // Over the limit for a while: one warning, then quiet for a minute (Settings > Speed limit warnings).
    val speedMph = location?.takeIf { inTrip && it.hasSpeed() }?.let { SpeedLimits.mph(it.speed) }
    val speedLimit = remember(navState) { if (inTrip) SpeedLimits.currentMph(navState) else null }
    val speedingSpoken = stringResource(R.string.explore_speeding_spoken)
    var speedingSince by remember { mutableStateOf(0L) }
    var lastSpeedWarning by remember { mutableStateOf(0L) }
    LaunchedEffect(speedMph, speedLimit) {
        val now = SystemClock.elapsedRealtime()
        if (speedMph == null || speedLimit == null || speedMph <= speedLimit + SpeedLimits.TOLERANCE_MPH) {
            speedingSince = 0L
            return@LaunchedEffect
        }
        if (speedingSince == 0L) speedingSince = now
        if (now - speedingSince >= SPEEDING_GRACE_MS && now - lastSpeedWarning >= SPEEDING_REPEAT_MS) {
            lastSpeedWarning = now
            services.voice.announce(UtteranceKind.SPEED_LIMIT, speedingSpoken.format(speedLimit))
        }
    }

    val reroutingSpoken = stringResource(R.string.explore_rerouting_spoken)
    LaunchedEffect(navState.isCalculatingNewRoute) {
        if (navState.isCalculatingNewRoute && inTrip) {
            services.voice.announce(UtteranceKind.REROUTE, reroutingSpoken)
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(RequestPermission()) { }
    fun startTrip() {
        val target = directions ?: return
        val ready = preview as? RoutePreviewState.Ready ?: return
        val route = routes.getOrNull(ready.selected) ?: return
        val nav = navigator ?: return
        // The trip notification shows the next turn; navigation works without it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        services.voice.guidanceMode = target.mode.guidanceMode()
        nav.start(route, target.mode)
    }

    BackHandler(enabled = !inTrip && panel != Panel.Browse) {
        panel = when (val current = panel) {
            is Panel.Directions -> Panel.PlaceShown(current.place)
            else -> Panel.Browse
        }
    }

    val baseTrafficOverlay = if (trafficVisible && trafficOn) traffic.toOverlay() else TrafficOverlay.NONE
    // Incidents along the route are pinned even when the traffic layer itself is hidden.
    val trafficOverlay = baseTrafficOverlay.copy(
        incidents = (baseTrafficOverlay.incidents + routeIncidents.map { it.toPin() }).distinctBy { it.id },
    )
    val overlays = when {
        inTrip -> MapOverlays(
            routes = listOf(MapLine(navState.routeGeometry.map { MapPoint(it.lat, it.lng) }, selected = true)),
            destination = (trip as? TripState.Navigating)?.remainingWaypoints?.lastOrNull()
                ?.let { MapPoint(it.coordinate.lat, it.coordinate.lng) },
            traffic = trafficOverlay,
            slowdowns = tripTraffic.toSpeedLines(),
        )
        else -> when (val current = panel) {
            is Panel.PlaceShown -> MapOverlays(pin = MapPoint(current.place.lat, current.place.lon))
            is Panel.Directions -> if (current.transit) {
                val journey = (transitPlan as? TransitPlanState.Ready)?.journeys?.getOrNull(transitSelected)
                val origin = origin()?.let { MapPoint(it.latitude, it.longitude) }
                val end = MapPoint(current.place.lat, current.place.lon)
                MapOverlays(
                    routes = if (journey != null && origin != null) journey.mapLines(origin, end) else emptyList(),
                    pin = end,
                    traffic = trafficOverlay,
                )
            } else {
                val selected = (preview as? RoutePreviewState.Ready)?.selected ?: 0
                MapOverlays(
                    routes = routes.mapIndexed { i, route ->
                        MapLine(route.geometry.map { MapPoint(it.lat, it.lng) }, selected = i == selected)
                    },
                    pin = MapPoint(current.place.lat, current.place.lon),
                    traffic = trafficOverlay,
                    slowdowns = previewTraffic.getOrNull(selected)?.toSpeedLines().orEmpty(),
                )
            }
            else -> MapOverlays(traffic = trafficOverlay)
        }
    }
    // While on route, the puck sits on the road rather than wandering with GPS noise.
    val shownLocation = (trip as? TripState.Navigating)?.snappedUserLocation?.toAndroidLocation() ?: location
    val sheetOpen = !inTrip && (panel is Panel.PlaceShown || panel is Panel.Directions)
    val droppedPin = stringResource(R.string.explore_dropped_pin)

    // Open on the installed regions: Manhattan for NYC alone, the whole state zoomed out for more.
    val home = remember(basemaps) { Regions.boundsOf(basemaps.map { it.region }) }
    Box(Modifier.fillMaxSize()) {
        SouthboundMap(
            basemaps = basemaps.map { it.region to it.file },
            initialCenter = home?.let { MapPoint(it.centerLat, it.centerLon) } ?: NYC_CENTER,
            initialZoom = home?.let { (log2(360.0 / maxOf(it.maxLon - it.minLon, (it.maxLat - it.minLat) * 1.4)) + 1.5).coerceIn(6.5, 11.0) } ?: 11.0,
            darkTheme = isSystemInDarkTheme(),
            location = shownLocation,
            following = following,
            onFollowingDismissed = { following = false },
            modifier = Modifier.fillMaxSize(),
            contentPadding = WindowInsets.systemBars.asPaddingValues(),
            overlays = overlays,
            cameraRequest = cameraRequest,
            navigating = inTrip,
            onLongPress = if (inTrip) {
                null
            } else {
                { point ->
                    showPlace(Place.pin(point.lat, point.lon, droppedPin))
                }
            },
            obscured = PaddingValues(bottom = if (sheetOpen) SHEET_HEIGHT else 0.dp),
            onIncidentTap = { id -> shownIncident = traffic.incidents.firstOrNull { it.id == id } },
            onTouchChanged = { down ->
                touching = down
                touchSerial++
            },
        )

        saveDialogFor?.let { place ->
            SaveDialog(
                place = place,
                data = userData,
                onToggle = { listId, on ->
                    scope.launch(Dispatchers.IO) {
                        attempt { if (on) services.userData.save(place.toRef(), listId) else services.userData.unsave(place.toRef(), listId) }
                    }
                },
                onCreateList = { name -> scope.launch(Dispatchers.IO) { attempt { services.userData.update { it.createList(name) } } } },
                onDismiss = { saveDialogFor = null },
            )
        }

        if (inTrip) {
            NavigationOverlay(
                state = navState,
                voiceMode = voiceSettings.mode,
                onVoiceModeChange = { mode -> scope.launch { services.voiceStore.update { it.copy(mode = mode) } } },
                onEnd = { navigator?.stop() },
                speedMph = speedMph,
                delaySeconds = tripTraffic.delaySeconds,
                fasterRoute = fasterOffer?.let { offer ->
                    FasterRouteOffer(
                        savesMinutes = offer.savesMinutes,
                        onAccept = {
                            navigator?.switchTo(offer.route)
                            fasterOffer = null
                        },
                        onDismiss = {
                            dismissedRoutes.dismiss(offer.points, System.currentTimeMillis())
                            fasterOffer = null
                        },
                    )
                },
                arrivalSide = arrivalSide,
                parkingNearby = parkingNearby,
            )
            if (!following && location != null) {
                // Above the ETA and trip progress at the bottom.
                RecenterPill({ following = true }, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 150.dp))
            }
        } else {
            when (val current = panel) {
                Panel.Browse -> {
                    Column(
                        Modifier
                            .align(Alignment.TopCenter)
                            .safeDrawingPadding()
                            .padding(top = 12.dp, start = 16.dp, end = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SearchBar(onSearch = { panel = Panel.Search }, onOpenSettings = onOpenSettings)
                        startPlace?.let { StartChip(it.name, onClear = { startPlace = null }) }
                        LocationStatus(hasPermission, location, asked, ::requestPermission)
                    }
                    Column(Modifier.align(Alignment.BottomEnd), horizontalAlignment = Alignment.End) {
                        if (trafficOn) TrafficButton(trafficVisible, { trafficVisible = !trafficVisible })
                        if (location != null) RecenterButton(following, { following = true }, Modifier)
                    }
                    if (!following && location != null) {
                        RecenterPill(
                            { following = true },
                            Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = if (shownIncident != null) 190.dp else 28.dp),
                        )
                    }
                    if (trafficVisible && traffic.errors.isNotEmpty()) {
                        Text(
                            traffic.errors.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(start = 16.dp, bottom = 96.dp, end = 96.dp),
                        )
                    }
                }
                Panel.Search -> SearchPanel(
                    index = searchIndex,
                    near = near,
                    onPick = { showPlace(it.place) },
                    onClose = { panel = Panel.Browse },
                    groups = userData.placeGroups(),
                    onClearRecents = { scope.launch(Dispatchers.IO) { attempt { services.userData.update { it.clearRecents() } } } },
                )
                is Panel.PlaceShown -> PlaceCard(
                    place = current.place,
                    distanceMeters = near?.let { SearchRanking.distanceMeters(it, current.place.lat, current.place.lon) },
                    onDirections = if (hasRouting || transitPack != null) {
                        { panel = Panel.Directions(current.place, TravelMode.DRIVE, transit = !hasRouting) }
                    } else {
                        null
                    },
                    onClose = { panel = Panel.Browse },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .safeDrawingPadding()
                        .padding(8.dp),
                    onStartHere = if (hasRouting || transitPack != null) {
                        {
                            startPlace = current.place
                            panel = Panel.Browse
                        }
                    } else {
                        null
                    },
                    extra = if (boards.isNotEmpty()) {
                        { TransitDepartures(boards, nowSeconds) }
                    } else {
                        null
                    },
                    onSave = { saveDialogFor = current.place },
                    saved = userData.isSaved(current.place.toRef()),
                )
                is Panel.Directions -> if (current.transit) {
                    TransitPlanSheet(
                        destinationName = current.place.name,
                        onModeChange = { mode -> panel = current.copy(mode = mode, transit = false) },
                        time = transitTime,
                        onTimeChange = { transitTime = it },
                        state = transitPlan,
                        selected = transitSelected,
                        onSelect = { transitSelected = it },
                        onClose = { panel = Panel.PlaceShown(current.place) },
                        startName = startPlace?.name,
                        nowSeconds = nowSeconds,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .safeDrawingPadding()
                            .padding(8.dp),
                    )
                } else {
                    RoutePreviewSheet(
                        destinationName = current.place.name,
                        mode = current.mode,
                        state = preview,
                        onModeChange = { mode -> panel = current.copy(mode = mode) },
                        onSelect = { index ->
                            (preview as? RoutePreviewState.Ready)?.let { preview = it.copy(selected = index) }
                        },
                        onStart = ::startTrip,
                        onClose = { panel = Panel.PlaceShown(current.place) },
                        startName = startPlace?.name,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .safeDrawingPadding()
                            .padding(8.dp),
                        onTransit = if (transitPack != null) {
                            { panel = current.copy(transit = true) }
                        } else {
                            null
                        },
                    )
                }
            }
        }

        // --- Phase 5 (map): the tap-to-read card for incident pins, wherever the pin was tapped ---
        shownIncident?.let { incident ->
            IncidentCard(
                incident,
                onClose = { shownIncident = null },
                modifier = when {
                    inTrip -> Modifier.align(Alignment.Center).safeDrawingPadding().padding(horizontal = 16.dp)
                    else -> Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(start = 8.dp, end = 88.dp, bottom = if (panel == Panel.Browse) 8.dp else SHEET_HEIGHT)
                },
            )
        }
    }
}

/** The chosen start point, while one is set instead of GPS. */
@Composable
private fun StartChip(name: String, onClear: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.explore_start_from, name),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f, fill = false),
            )
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.explore_start_clear))
            }
        }
    }
}

@Composable
private fun SearchBar(onSearch: () -> Unit, onOpenSettings: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onSearch)
                    .padding(start = 16.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Filled.Search, contentDescription = null)
                Text(
                    stringResource(R.string.explore_search),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.explore_settings))
            }
        }
    }
}

@Composable
private fun LocationStatus(hasPermission: Boolean, location: Location?, asked: Boolean, onRequest: () -> Unit) {
    if (hasPermission && location != null) return
    val context = LocalContext.current
    // Once Android stops showing the dialog, only app settings can grant it.
    val settingsOnly = asked && context.findActivity()
        ?.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) == false
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        if (!hasPermission) {
            LocationPermissionPrompt(
                settingsOnly = settingsOnly,
                onAllow = { if (settingsOnly) context.openAppSettings() else onRequest() },
            )
        } else {
            Text(stringResource(R.string.explore_finding_location), Modifier.padding(12.dp))
        }
    }
}

private const val TRAFFIC_POLL_MS = 60_000L
private const val AUTO_RECENTER_MS = 10_000L
private const val PARKING_RADIUS_M = 300.0
private const val FASTER_ROUTE_OFFER_MS = 90_000L
private const val FASTER_ROUTE_TRY_DETOUR_SECONDS = 120.0
private const val DETOUR_MIN_DISTANCE_M = 300.0
private const val DETOUR_MAX_SPOTS = 3

/** A faster route found mid-trip, with its points for telling it apart later. */
private data class FasterOffer(val route: Route, val points: List<GeoPoint>, val savesMinutes: Int)

private fun RouteSlowdowns.toSpeedLines(): List<SpeedLine> =
    segments.map { SpeedLine(it.points.map { p -> MapPoint(p.lat, p.lon) }, it.speedMph) }

private fun Incident.toPin() = IncidentPin(id, MapPoint(location.lat, location.lon), kind.name, isClosure)
private const val SPEEDING_GRACE_MS = 8_000L
private const val SPEEDING_REPEAT_MS = 60_000L

@Composable
private fun TrafficButton(shown: Boolean, onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        modifier = Modifier.safeDrawingPadding().padding(start = 16.dp, end = 16.dp, top = 16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = if (shown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Icon(Icons.Filled.Traffic, contentDescription = stringResource(if (shown) R.string.explore_traffic_hide else R.string.explore_traffic_show))
    }
}

/** What a tapped incident pin says: road, direction, what happened, when 511NY last updated it. */
@Composable
private fun IncidentCard(incident: Incident, onClose: () -> Unit, modifier: Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(incident.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.explore_import_ok))
                }
            }
            Text(incident.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
            val details = listOfNotNull(
                incident.lanesStatus,
                incident.severity?.let { stringResource(R.string.explore_incident_severity, it) },
                incident.updatedAt?.let {
                    stringResource(
                        R.string.explore_incident_updated,
                        android.text.format.DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS),
                    )
                },
            )
            if (details.isNotEmpty()) {
                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun TrafficSnapshot.toOverlay() = TrafficOverlay(
    speeds = speeds.map { SpeedLine(it.points.map { p -> MapPoint(p.lat, p.lon) }, it.speedMph) },
    incidents = incidents.map { it.toPin() },
)

@Composable
private fun RecenterButton(following: Boolean, onClick: () -> Unit, modifier: Modifier) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.safeDrawingPadding().padding(16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = if (following) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_my_location),
            contentDescription = stringResource(R.string.explore_my_location),
        )
    }
}

private fun TravelMode.guidanceMode(): GuidanceMode = when (this) {
    TravelMode.DRIVE -> GuidanceMode.DRIVING
    TravelMode.WALK -> GuidanceMode.WALKING
    TravelMode.BIKE -> GuidanceMode.CYCLING
}

// Callers check hasFineLocation() first.
@SuppressLint("MissingPermission")
private suspend fun LocationManager.collectGps(onFix: (Location) -> Unit) {
    gpsUpdates().collect { onFix(it) }
}

private fun Context.hasFineLocation(): Boolean =
    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

@Composable
private fun LocationPermissionPrompt(settingsOnly: Boolean, onAllow: () -> Unit) {
    Row(
        modifier = Modifier.padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.explore_location_needed),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f, fill = false),
        )
        TextButton(onClick = onAllow) {
            Text(
                stringResource(
                    if (settingsOnly) R.string.explore_location_settings else R.string.explore_location_allow,
                ),
            )
        }
    }
}
