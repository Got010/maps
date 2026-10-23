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
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.ElevatedAssistChip
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
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.southbound.core.map.ArrivalSide
import app.southbound.core.map.Arrival
import app.southbound.core.map.CameraPin
import app.southbound.core.map.CameraRequest
import app.southbound.core.map.MapLine
import app.southbound.core.map.IncidentPin
import app.southbound.core.map.MapOverlays
import app.southbound.core.map.MapPoint
import app.southbound.core.map.MessageSignPin
import app.southbound.core.map.TrafficCameraPin
import app.southbound.core.map.SpeedLine
import app.southbound.core.map.TrafficOverlay
import app.southbound.core.map.WeatherOverlay
import app.southbound.core.map.MapViewport
import app.southbound.core.map.WifiOverlay
import app.southbound.core.map.WifiTap
import app.southbound.core.map.NYC_CENTER
import app.southbound.core.map.NightMode
import app.southbound.core.map.SouthboundMap
import app.southbound.core.map.gpsUpdates
import app.southbound.core.map.HeadingFilter
import app.southbound.core.map.withHeading
import app.southbound.core.network.NetworkFeature
import app.southbound.core.packs.ImportedPack
import app.southbound.core.packs.PackEvents
import app.southbound.core.packs.PackKind
import app.southbound.core.packs.RegionSelection
import app.southbound.core.packs.ReleaseManifest
import app.southbound.core.packs.RouteRegions
import app.southbound.core.packs.TripRegions
import app.southbound.core.packs.UpdatePlan
import app.southbound.core.packs.Regions
import app.southbound.core.routing.DrivingPrefs
import app.southbound.core.routing.FuelModel
import app.southbound.core.routing.Navigator
import app.southbound.core.routing.RouteOptionsStore
import app.southbound.core.routing.RoutePrefs
import app.southbound.core.routing.RouteSummary
import app.southbound.core.routing.SpeedLimits
import app.southbound.core.routing.ShorterRoute
import app.southbound.core.routing.SpeedWarningPolicy
import app.southbound.core.routing.TravelMode
import app.southbound.core.routing.TripStops
import app.southbound.core.updates.ManifestCache
import app.southbound.core.updates.UpdateScheduler
import app.southbound.core.updates.UpdateState
import app.southbound.core.updates.UpdateStatus
import app.southbound.core.userdata.LoadState
import app.southbound.core.userdata.Shortcut
import app.southbound.core.search.AlertKind
import app.southbound.core.search.Landmark
import app.southbound.core.search.LatLon
import app.southbound.core.search.Place
import app.southbound.core.search.PlaceSearch
import app.southbound.core.search.RouteExposure
import app.southbound.core.search.RouteLine
import app.southbound.core.search.SearchRanking
import app.southbound.core.states.Crossing
import app.southbound.core.states.LiveState
import app.southbound.core.states.StateCards
import app.southbound.core.states.StateData
import app.southbound.core.wifi.WifiFilter
import app.southbound.core.wifi.WifiPack
import app.southbound.core.wifi.WifiSpot
import app.southbound.core.traffic.ClosureAvoidance
import app.southbound.core.traffic.FasterRoute
import app.southbound.core.traffic.Geo
import app.southbound.core.traffic.GeoPoint
import app.southbound.core.traffic.Incident
import app.southbound.core.traffic.IncidentAlertKind
import app.southbound.core.traffic.IncidentAlerts
import app.southbound.core.traffic.IncidentKind
import app.southbound.core.traffic.PoliceAlerts
import app.southbound.core.traffic.RouteSlowdowns
import app.southbound.core.traffic.RouteTraffic
import app.southbound.core.traffic.SpeedBand
import app.southbound.core.traffic.TrafficCamera
import app.southbound.core.traffic.TrafficRanking
import app.southbound.core.traffic.TrafficSnapshot
import app.southbound.core.traffic.TripTraffic
import app.southbound.core.transit.TransitPack
import app.southbound.core.voice.GuidanceMode
import app.southbound.core.voice.SpeedAlertMode
import app.southbound.core.voice.SpokenUnits
import app.southbound.core.weather.AlertOnRoute
import app.southbound.core.weather.Forecasts
import app.southbound.core.weather.Metar
import app.southbound.core.weather.RouteWeather
import app.southbound.core.voice.UtteranceKind
import app.southbound.feature.directions.RouteOptionsChip
import app.southbound.feature.directions.RouteOptionsSheet
import app.southbound.feature.directions.RoutePreviewSheet
import app.southbound.feature.directions.RoutePreviewState
import app.southbound.feature.directions.StopsEditor
import app.southbound.feature.directions.TripFetch
import app.southbound.feature.directions.TripRegionOffer
import app.southbound.feature.navigation.FasterRouteOffer
import app.southbound.feature.navigation.NavigationOverlay
import app.southbound.feature.navigation.drivingMapBottomCenter
import app.southbound.feature.navigation.rememberDrivingLandscape
import app.southbound.feature.navigation.rememberDrivingPanelWidth
import app.southbound.feature.search.PlaceCard
import app.southbound.feature.search.WifiDetails
import app.southbound.feature.search.WifiMarkInfo
import app.southbound.feature.search.SearchPanel
import app.southbound.feature.search.ShortcutSlot
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.Route
import uniffi.ferrostar.TripState
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointKind
import kotlin.math.log2

/** What is open over the map, outside of a trip. */
internal sealed interface Panel {
    data object Browse : Panel
    data object Search : Panel
    data class PlaceShown(val place: Place) : Panel
    // Phase 6: [stops] are the places between here and [place], in order.
    data class Directions(val place: Place, val mode: TravelMode, val transit: Boolean = false, val stops: List<Place> = emptyList()) : Panel
}

/**
 * The one tap-to-read card open over the map, if any: an incident pin, a
 * plate reader, or (Phase 8) a 511NY traffic camera or message sign. One
 * state, so opening one card closes the other.
 */
private sealed interface ShownThing {
    data class IncidentShown(val incident: Incident) : ShownThing
    data class PlateReader(val pin: CameraPin) : ShownThing

    /** Looked up by id in the latest snapshot, so the card follows the feed. */
    data class TrafficCam(val id: String) : ShownThing
    data class Sign(val id: String) : ShownThing

    /** A weather alert, looked up by id in the latest weather snapshot. */
    data class WeatherAlertShown(val id: String) : ShownThing

    /** A weather station's current conditions (its badge on the weather layer), by station id. */
    data class WeatherStation(val id: String) : ShownThing

    /**
     * A DOT traffic camera pinned on a route option (the pack's name and place, no feed id): the traffic feed's
     * camera at that spot when it lists one, so the card shows its picture, else what the pack knows.
     */
    data class DotCamera(val camera: Landmark) : ShownThing
}

/** A paused trip: guidance stopped, what was left of it kept so Resume can route there again from here. */
internal data class PausedTrip(val place: Place, val stops: List<Place>, val mode: TravelMode)

private const val PLACE_ZOOM = 16.0

// Phase 6: a new stop closer than this to an existing one (or the destination) is the same stop.
private const val SAME_STOP_METERS = 30.0

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
        // The saved places file may have been unreadable only briefly (a Keystore error): look again.
        if (services.userData.loadState.value is LoadState.Unreadable) {
            scope.launch(Dispatchers.IO) { attempt { services.userData.reload() } }
        }
        onPauseOrDispose { }
    }

    var location by remember { mutableStateOf<Location?>(null) }
    // Holds the last trustworthy travel direction so the fin never snaps north or spins when parked.
    val headingFilter = remember { HeadingFilter() }
    var following by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        val locationManager = context.getSystemService(LocationManager::class.java)
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            locationManager.collectGps {
                headingFilter.update(it)
                location = it
            }
        }
    }

    // Packs: search, routing and transit open off the main thread.
    var searchIndex by remember { mutableStateOf<PlaceSearch?>(null) }
    var navigator by remember { mutableStateOf<Navigator?>(null) }
    var hasRouting by remember { mutableStateOf(false) }
    var transitPack by remember { mutableStateOf<TransitPack?>(null) }
    LaunchedEffect(packGeneration, resumes) {
        withContext(Dispatchers.IO) {
            searchIndex = services.openSearch()
            // The trip's navigator while one is on, even to a map screen just rebuilt around new regions.
            navigator = services.openNavigator()
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
    // The middle of the map, each time it comes to rest. Search looks there once the map is moved off the user.
    var mapCenter by remember { mutableStateOf<LatLon?>(null) }
    var transitTime by remember { mutableStateOf(TransitTime()) }
    var transitPlan by remember { mutableStateOf<TransitPlanState>(TransitPlanState.Loading) }
    var transitSelected by remember { mutableIntStateOf(0) }
    var boards by remember { mutableStateOf<List<StationBoard>>(emptyList()) }
    // A start point picked on the map ("Start here"), e.g. to plan from outside NYC. Null: from GPS.
    var startPlace by remember { mutableStateOf<Place?>(null) }
    // Back to the route preview that is getting regions for its trip: each new map rebuilds this screen.
    LaunchedEffect(Unit) {
        val wait = services.tripWait ?: return@LaunchedEffect
        if (inTrip || System.currentTimeMillis() - wait.atMs > TRIP_WAIT_MS) {
            services.tripWait = null
            return@LaunchedEffect
        }
        startPlace = wait.start
        panel = Panel.Directions(wait.place, wait.mode, stops = wait.stops)
    }

    // Regions fetched for a trip go once the phone, not navigating, is back in its own regions after being in them
    // (TripRegions). Looked at about every kilometre, and when a trip starts or ends.
    val regionSelection = remember { RegionSelection.get(context) }
    val wantedRegions by regionSelection.regions.collectAsState()
    val tripRegionIds by regionSelection.tripRegions.collectAsState()
    val fixCell = location?.let { (it.latitude * 100).roundToInt() to (it.longitude * 100).roundToInt() }
    LaunchedEffect(fixCell, inTrip, tripRegionIds) {
        val fix = location ?: return@LaunchedEffect
        if (tripRegionIds.isEmpty()) return@LaunchedEffect
        val step = TripRegions.onFix(fix.latitude, fix.longitude, regionSelection.own, tripRegionIds, regionSelection.tripVisited, inTrip)
        if (step.visited.isNotEmpty()) regionSelection.markVisited(step.visited)
        if (step.drop.isEmpty()) return@LaunchedEffect
        regionSelection.dropTrip(step.drop)
        withContext(Dispatchers.IO) { step.drop.forEach { attempt { services.packStore.delete(PackKind.BASEMAP, it) } } }
        step.drop.forEach { PackEvents.emitRemoved(PackKind.BASEMAP, it) }
        // Their search and local roads go in this round: the updater keeps only the wanted regions' packs.
        UpdateScheduler.checkNow(context, anyNetwork = false)
    }

    // Saved places and recents, kept encrypted on the phone.
    val userData by services.userData.data.collectAsState()
    var saveDialogFor by remember { mutableStateOf<Place?>(null) }
    // An unreadable saved-places file is never overwritten; the user decides what happens to it.
    val userDataState by services.userData.loadState.collectAsState()
    var unreadableAcknowledged by remember { mutableStateOf(false) }
    var startFreshFailed by remember { mutableStateOf(false) }
    // A save refused while unreadable brings the dialog back, so it never fails silently.
    val refusedSaves by services.userData.refusedSaves.collectAsState()
    LaunchedEffect(refusedSaves) { if (refusedSaves > 0) unreadableAcknowledged = false }

    // Phase 6: route options, stops being picked, night mode.
    val routeOptions = remember { RouteOptionsStore.get(context) }
    val routePrefs by routeOptions.options.collectAsState()
    var showRouteOptions by remember { mutableStateOf(false) }
    // The route preview that is waiting while the user searches for a stop to add to it.
    var addingStopTo by remember { mutableStateOf<Panel.Directions?>(null) }

    // Pause: the plan the running trip came from, the trip once paused, and a Resume waiting for its new route.
    // Kept by the services, so a map region arriving mid-trip (which rebuilds this screen) keeps Pause and the paused trip.
    var tripPlan by services.tripMemory::plan
    var pausedTrip by services.tripMemory::paused
    var resumeWhenReady by remember { mutableStateOf(false) }
    val drivingPrefs = remember { DrivingPrefs.get(context) }
    val nightBySunset by drivingPrefs.nightBySunset.collectAsState()
    // Settings > Driving: the search bar, chips and banners at the bottom, within the thumb's reach (docs/bottom-search.md).
    val searchAtBottom by drivingPrefs.searchAtBottom.collectAsState()
    // State-line cards (core/states; Settings > Driving): what changes when the route or the phone crosses a state line.
    // The state the phone is in is held here, in memory, for as long as this screen lives: nothing about crossings,
    // times or places is kept, and the card is a view of static data keyed by a state code worked out on the fly.
    val stateCardsOn by drivingPrefs.stateLineCards.collectAsState()
    var stateCardRequest by rememberSaveable { mutableStateOf<String?>(null) }
    // During guidance a live crossing shows a small banner instead of the sheet; a tap on it opens the sheet.
    var stateBanner by remember { mutableStateOf<StateCardRequest?>(null) }
    var currentState by remember { mutableStateOf<String?>(null) }
    var stateCards by remember { mutableStateOf<StateCards?>(null) }
    val inTripNow by rememberUpdatedState(inTrip)
    LaunchedEffect(inTrip) { if (!inTrip) stateBanner = null }
    LaunchedEffect(stateCardsOn) {
        currentState = null
        stateBanner = null
        if (!stateCardsOn) {
            stateCardRequest = null
            return@LaunchedEffect
        }
        // Read and parsed once, off the main thread; kept after that.
        val (outlines, cards) = withContext(Dispatchers.Default) { attempt { StateData.outlines() to StateData.cards() } } ?: return@LaunchedEffect
        stateCards = cards
        val live = LiveState(outlines)
        snapshotFlow { location }.collect { fix ->
            if (fix == null) return@collect
            // A fix without accuracy, or worse than 100 m, is not used; a crossing counts only 500 m inside the new state.
            val accuracy = if (fix.hasAccuracy()) fix.accuracy.toDouble() else null
            val entered = withContext(Dispatchers.Default) { live.onFix(fix.latitude, fix.longitude, accuracy) }
            currentState = live.current
            if (entered == null) return@collect
            val into = cards[entered.to] ?: return@collect
            val from = cards[entered.from]
            if (inTripNow) {
                // Guidance: a small banner over the map, not the full-height sheet, so the maneuvers and the trip
                // controls stay in reach; the line is spoken too, through the usual voice settings (muted or off: nothing spoken).
                stateBanner = StateCardRequest(entered.to, entered.from)
                if (from != null) services.voice.announce(UtteranceKind.HAZARD_ALERT, StateCards.spoken(into, StateCards.diff(from, into)))
            } else {
                stateCardRequest = StateCardRequest(entered.to, entered.from).encode()
            }
        }
    }
    // The state lines each route option crosses, for the route sheet. Worked out from the routes alone, off the main thread.
    val routeCrossings by produceState(emptyList<List<Crossing>>(), routes, stateCardsOn) {
        value = emptyList()
        if (!stateCardsOn || routes.isEmpty()) return@produceState
        value = withContext(Dispatchers.Default) { attempt { StateLines.crossings(routes, StateData.outlines()) } }.orEmpty()
    }
    // Fuel economy and gas price for the cost on route cards; changing them never recomputes routes.
    val fuel by services.fuelPrefs.settings.collectAsState()
    var clockMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            clockMs = System.currentTimeMillis()
        }
    }
    fun addStop(to: Panel.Directions, place: Place) {
        val sameSpot = { a: Place, b: Place -> SearchRanking.distanceMeters(LatLon(a.lat, a.lon), b.lat, b.lon) < SAME_STOP_METERS }
        panel = to.copy(stops = if (sameSpot(place, to.place)) to.stops else TripStops.add(to.stops, place, sameSpot))
        addingStopTo = null
    }

    // Live traffic: shown on request, and watched for closures during a trip.
    var trafficVisible by rememberSaveable { mutableStateOf(false) }
    var trafficOn by remember { mutableStateOf(services.traffic.isEnabled) }
    val traffic by services.traffic.snapshot.collectAsState()
    var shownThing by remember { mutableStateOf<ShownThing?>(null) }
    LaunchedEffect(resumes) { trafficOn = services.traffic.isEnabled }
    // Cameras and signs belong to the traffic layer: hiding it closes their card.
    LaunchedEffect(trafficVisible, trafficOn) {
        if (!(trafficVisible && trafficOn) && (shownThing is ShownThing.TrafficCam || shownThing is ShownThing.Sign)) shownThing = null
    }
    // Gas price from EIA (opt-in, and rate-limited to one request a day): on resume, when directions open,
    // and when the state is changed in Settings.
    val directionsOpen = panel is Panel.Directions

    // Weather: NWS alerts for the state and NOAA radar, behind Settings > Live data > Weather. The layer button
    // shows radar and every alert; without it, only alerts on the route being planned or driven are drawn.
    var weatherVisible by rememberSaveable { mutableStateOf(false) }
    var weatherOn by remember { mutableStateOf(services.weather.isEnabled) }
    val weather by services.weather.snapshot.collectAsState()
    LaunchedEffect(resumes) { weatherOn = services.weather.isEnabled }
    // Search too: a weather search shows the towns' weather in its rows.
    val placeCardOpen = panel is Panel.PlaceShown || panel == Panel.Search
    LaunchedEffect(weatherVisible, inTrip, weatherOn, directionsOpen, placeCardOpen) {
        if (!weatherOn || !(weatherVisible || inTrip || directionsOpen || placeCardOpen)) return@LaunchedEffect
        while (true) {
            // Radar and the stations' temperatures while the layer is shown (on the home map and while driving),
            // the current weather for a place card, the alerts whenever a route needs them.
            withContext(Dispatchers.IO) {
                attempt {
                    services.weather.refresh(
                        withRadar = weatherVisible,
                        withConditions = weatherVisible || placeCardOpen,
                        // The forecast pack: checked at most hourly, downloaded only when it changed.
                        withForecast = true,
                    )
                }
            }
            delay(WEATHER_POLL_MS)
        }
    }
    LaunchedEffect(resumes, directionsOpen, fuel.region) {
        if (services.gasPrices.isReady) attempt { services.gasPrices.refresh() }
    }
    // A new mpg in Settings reprices the routes already on screen.
    LaunchedEffect(fuel.mpg) {
        val ready = preview as? RoutePreviewState.Ready ?: return@LaunchedEffect
        if (routes.size == ready.summaries.size) {
            preview = ready.copy(fuelGallons = fuelGallonsFor(routes, ready.delaySeconds, fuel.mpg))
        }
    }

    // --- Phase 5 (map): touch, traffic on the route, faster route, arrival details ---
    // Re-center: while navigating, 10 s without a touch after leaving the puck snaps back to following.
    var touching by remember { mutableStateOf(false) }
    var touchSerial by remember { mutableIntStateOf(0) }
    // Slowdowns and incidents along the selected route (preview) or what's left of the trip.
    var previewTraffic by remember { mutableStateOf<List<RouteSlowdowns>>(emptyList()) }
    var tripTraffic by remember { mutableStateOf(RouteSlowdowns.NONE) }
    // Live traffic laid on the whole trip route (redone on a reroute or a new snapshot), and how far along it the driver is.
    var tripMatch by remember { mutableStateOf(TripTraffic.NONE) }
    var tripAlong by remember { mutableDoubleStateOf(0.0) }
    var routeIncidents by remember { mutableStateOf<List<Incident>>(emptyList()) }
    var fasterOffer by remember { mutableStateOf<FasterOffer?>(null) }
    val dismissedRoutes = services.tripMemory.dismissedRoutes
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

    // Plate readers (Flock and others) from the search pack: on the map in every state, counted on
    // each driving route option, and steered around with "Avoid plate readers".
    var cameraPins by remember { mutableStateOf<List<CameraPin>>(emptyList()) }
    LaunchedEffect(searchIndex) {
        val index = searchIndex
        cameraPins = if (index == null) emptyList() else withContext(Dispatchers.IO) { attempt { PlateReaders.pins(index) } }.orEmpty()
    }

    // Free Wi-Fi (Settings > Maps): the pack, the layer's button, what the map shows, and the spot whose sheet is open.
    // All of it reads the Wi-Fi pack on the phone.
    var wifiPack by remember { mutableStateOf<WifiPack?>(null) }
    var wifiVisible by rememberSaveable { mutableStateOf(false) }
    // What the long-press chooser leaves on the map. View state like wifiVisible: kept through a rotation, never stored.
    var wifiFilter by rememberSaveable(stateSaver = WifiFilterSaver) { mutableStateOf(WifiFilter.DEFAULT) }
    var wifiChooser by rememberSaveable { mutableStateOf(false) }
    // The sheet closes for good when the pack goes away, so it cannot open by itself when a pack comes back. (A pack
    // that is still opening after a rotation is not "gone": the sheet waits for it.)
    var wifiPackSeen by remember { mutableStateOf(false) }
    LaunchedEffect(wifiPack) {
        if (wifiPack != null) wifiPackSeen = true else if (wifiPackSeen) wifiChooser = false
    }
    var viewport by remember { mutableStateOf<MapViewport?>(null) }
    var wifiOverlay by remember { mutableStateOf(WifiOverlay.NONE) }
    var shownWifi by remember { mutableStateOf<WifiSpot?>(null) }
    // The user's own marks on Wi-Fi spots, from the same sealed store as the saved places.
    val wifiMarks = remember(userData.wifiMarks) { userData.wifiMarks.associate { it.osmRef to WifiMarkInfo(it.worked, it.markedAt) } }
    // The pack installed or removed while the app runs (a download, Settings, an import).
    var wifiChanges by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        launch { PackEvents.installed.collect { if (it.kind == PackKind.WIFI) wifiChanges++ } }
        launch { PackEvents.removed.collect { if (it.first == PackKind.WIFI) wifiChanges++ } }
    }
    LaunchedEffect(packGeneration, resumes, wifiChanges) {
        wifiPack = withContext(Dispatchers.IO) { attempt { services.openWifi() } }
    }
    // Grid cells below zoom 10, the spots in view from it, asked for each time the camera comes to rest.
    LaunchedEffect(wifiPack, wifiVisible, viewport, wifiFilter) {
        val pack = wifiPack
        val view = viewport
        if (pack == null || !wifiVisible || view == null) {
            wifiOverlay = WifiOverlay.NONE
            return@LaunchedEffect
        }
        delay(WIFI_QUERY_DEBOUNCE_MS)
        wifiOverlay = withContext(Dispatchers.IO) { attempt { WifiLayer.overlay(pack, view, wifiFilter) } } ?: WifiOverlay.NONE
    }
    fun showWifiSpot(spot: WifiSpot) {
        shownWifi = spot
        showPlace(spot.toPlace())
    }

    // Route preview: recompute when the destination or mode changes, or when
    // the first GPS fix arrives after "no location".
    val directions = panel as? Panel.Directions
    val hasFix = location != null || startPlace != null
    LaunchedEffect(directions, hasFix, navigator, startPlace, routePrefs) {
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
        // Phase 6: stops first, then the destination; the driver's route options apply to this and later reroutes.
        val waypoints = TripStops.route(target.stops, target.place).map {
            Waypoint(GeographicCoordinate(it.lat, it.lon), WaypointKind.BREAK)
        }
        nav.prefs = routePrefs
        // Steer around the state 511s' closures near the trip, when a 511 feed is on.
        // Any traffic feed that is on is fetched (cached for its interval) so options can show their delay.
        val snapshot = if (services.traffic.isEnabled) withContext(Dispatchers.IO) { attempt { services.traffic.refresh() } } else null
        val avoid = if (services.traffic.hasIncidents) {
            ClosureAvoidance.polygons(snapshot?.closures.orEmpty(), GeoPoint(from.latitude, from.longitude), GeoPoint(target.place.lat, target.place.lon))
        } else {
            emptyList()
        }
        val plainRoutes = try {
            withContext(Dispatchers.Default) { nav.routes(from.toUserLocation(), waypoints, target.mode, avoid) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        // Plate readers: with "Avoid plate readers" on, route again around the ones on the best route.
        // The pack may still be opening on a cold start, so wait for it briefly.
        val driving = target.mode == TravelMode.DRIVE
        val cameraIndex = when {
            !driving -> null
            searchIndex != null -> searchIndex
            withContext(Dispatchers.IO) { services.hasPack(PackKind.SEARCH) } -> withTimeoutOrNull(SEARCH_WAIT_MS) { snapshotFlow { searchIndex }.filterNotNull().first() }
            else -> null
        }
        // "Avoid plate readers" and "Avoid DOT cameras" use the same squares; either or both may be on.
        val avoidCameras = routePrefs.avoidedCameraCategories()
        val avoided = if (driving && avoidCameras.isNotEmpty() && cameraIndex != null && plainRoutes.isNotEmpty()) {
            withContext(Dispatchers.Default) {
                attempt { PlateReaders.avoid(nav, cameraIndex, from.toUserLocation(), waypoints, target.mode, avoid, plainRoutes, avoidCameras).routes }
            } ?: plainRoutes.also { nav.cameraAvoid = emptyList() }
        } else {
            plainRoutes
        }
        // Also the shorter way: Valhalla prefers main roads to residential streets, so a side street that is shorter
        // and faster by its own estimate can be missing from the options (ShorterRoute). Driving only.
        val shorter = if (driving && avoided.isNotEmpty()) {
            withContext(Dispatchers.Default) { attempt { nav.shorterRoute(from.toUserLocation(), waypoints) } }
        } else {
            null
        }
        val found = ShorterRoute.merge(avoided, shorter) {
            ShorterRoute.Option(RouteSummary.of(it).durationSeconds, it.distance, it.geometry.map { p -> p.lat to p.lng })
        }
        val valhallaExposure = if (cameraIndex != null) withContext(Dispatchers.Default) { attempt { PlateReaders.exposure(cameraIndex, found) } }.orEmpty() else emptyList()
        // Live speeds laid over each option: coloured stretches and the delay they add (car traffic: driving only).
        val speeds = if (driving) snapshot?.speeds.orEmpty() else emptyList()
        val valhallaSlow = withContext(Dispatchers.Default) {
            found.map { RouteTraffic.slowdowns(it.geometry.map { p -> GeoPoint(p.lat, p.lng) }, speeds) }
        }
        // Valhalla on the phone ranks without live speeds, so put the fastest option with traffic first
        // (when it gains 2+ min). The driver's avoid options win over a traffic estimate: with tolls, highways
        // or ferries avoided Valhalla's order stands, and no route with more plate readers than its first may lead.
        val byTraffic = if (routePrefs.avoidsTolls || routePrefs.avoidHighways || routePrefs.avoidFerries) {
            found.indices.toList()
        } else {
            TrafficRanking.order(
                durationsSeconds = found.map { RouteSummary.of(it).durationSeconds },
                delaysSeconds = valhallaSlow.map { it.delaySeconds },
                mayLead = { i -> valhallaExposure.getOrNull(i).avoided(routePrefs) <= valhallaExposure.getOrNull(0).avoided(routePrefs) },
            )
        }
        // Avoiding plate readers, tolls or DOT cameras beats any time saved: fewest of them first, however much longer.
        val order = byTraffic.sortedBy { valhallaExposure.getOrNull(it).avoided(routePrefs) }
        val ranked = TrafficRanking.reorder(found, order)
        val slow = TrafficRanking.reorder(valhallaSlow, order)
        val exposure = TrafficRanking.reorder(valhallaExposure, order)
        routes = ranked
        previewTraffic = slow
        if (ranked.isEmpty()) {
            preview = RoutePreviewState.Failed(RoutePreviewState.Reason.NO_ROUTE)
        } else {
            val delays = slow.map { it.delaySeconds }
            val shorterIndex = ShorterRoute.indexIn(ranked, shorter)
            preview = RoutePreviewState.Ready(
                // The shorter way says which road makes it so ("via Elm Street"), not the one it shares.
                summaries = ranked.mapIndexed { i, route ->
                    if (i == shorterIndex) RouteSummary.apartFrom(route, ranked[if (i == 0) 1 else 0]) else RouteSummary.of(route)
                },
                selected = 0,
                shorter = shorterIndex,
                delaySeconds = delays,
                fuelGallons = fuelGallonsFor(ranked, delays, services.fuelPrefs.settings.value.mpg),
                exposure = exposure,
                cameraAvoidIncomplete = driving && exposure.firstOrNull().avoided(routePrefs) > 0,
            )
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
        transitPlan = ready.copy(live = liveFor(snapshot, ready.journeys, pack.network))
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

    // Phase 6: reroutes and skip-stop use the options in force now.
    LaunchedEffect(navigator, routePrefs) { navigator?.prefs = routePrefs }

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
            // With the layer shown, the 511s' cameras (daily) and message signs (every 5 or 10 min) come too.
            withContext(Dispatchers.IO) { attempt { services.traffic.refresh(mapExtras = trafficVisible) } }
            delay(TRAFFIC_POLL_MS)
        }
    }
    // A closure that appears on the route ahead: say so and route around it. Only on what is left of the route and
    // on our side of the road: a closure behind us, or on the other carriageway of a divided highway, is not ours.
    val closureSpoken = stringResource(R.string.explore_closure_spoken)
    val announcedClosures = services.tripMemory.announcedClosures
    LaunchedEffect(inTrip) { if (!inTrip) announcedClosures.clear() }
    LaunchedEffect(traffic, inTrip) {
        val nav = navigator ?: return@LaunchedEffect
        val navigating = trip as? TripState.Navigating ?: return@LaunchedEffect
        if (traffic.closures.isEmpty()) return@LaunchedEffect
        val here = navigating.snappedUserLocation.coordinates
        val geometry = navState.routeGeometry
        val ahead = withContext(Dispatchers.Default) {
            ClosureAvoidance.ahead(traffic.closures, geometry.map { GeoPoint(it.lat, it.lng) }, GeoPoint(here.lat, here.lng))
        }.filter { it.id !in announcedClosures }
        if (ahead.isEmpty()) return@LaunchedEffect
        announcedClosures += ahead.map { it.id }
        services.voice.announce(UtteranceKind.TRAFFIC_ALERT, closureSpoken.format(ahead.first().title))
        // Around the closures just found, keeping the ones the trip already avoids. A failure (a router closed by an
        // update) leaves the trip on its route.
        attempt { nav.rerouteAround(ClosureAvoidance.forReroute(ahead, nav.avoid)) }
    }

    // --- Phase 5 (map): re-center timer, route traffic, faster route, arrival ---
    LaunchedEffect(inTrip, following, touching, touchSerial) {
        if (!inTrip || following || touching) return@LaunchedEffect
        delay(AUTO_RECENTER_MS)
        following = true
    }

    // Slow stretches and incidents ahead on the trip. Matching live speeds onto the route is the costly
    // part (links x route points), so it is done on the whole route only when the route (a reroute) or the
    // traffic snapshot (a poll, TRAFFIC_POLL_MS) changes. Every 250 m of progress the match is just trimmed
    // to what is left: passed stretches drop off the map and the "+X min" shrinks through a slow one.
    val routeGeometry = navState.routeGeometry
    val routeKey = Triple(routeGeometry.size, routeGeometry.firstOrNull(), routeGeometry.lastOrNull())
    val matchedRouteKey = remember { arrayOfNulls<Any>(1) }
    LaunchedEffect(inTrip, routeKey, traffic) {
        if (!inTrip) {
            tripMatch = TripTraffic.NONE
            matchedRouteKey[0] = null
            return@LaunchedEffect
        }
        // Car traffic only changes a driving trip's ETA; incidents are shown on any trip.
        val speeds = if (navigator?.mode == TravelMode.DRIVE) traffic.speeds else emptyList()
        val incidents = traffic.incidents
        val geometry = routeGeometry.map { GeoPoint(it.lat, it.lng) }
        val matched = withContext(Dispatchers.Default) { TripTraffic.match(geometry, speeds, incidents) }
        // A new route starts where the driver is; a new snapshot on the same route keeps their place on it.
        if (matchedRouteKey[0] != routeKey) tripAlong = 0.0
        matchedRouteKey[0] = routeKey
        tripMatch = matched
    }
    val tripProgressBucket = ((trip as? TripState.Navigating)?.progress?.distanceRemaining ?: 0.0).toInt() / 250
    LaunchedEffect(inTrip, tripMatch, tripProgressBucket) {
        val navigating = trip as? TripState.Navigating
        if (navigating == null) {
            tripTraffic = RouteSlowdowns.NONE
            routeIncidents = emptyList()
            return@LaunchedEffect
        }
        val here = navigating.snappedUserLocation.coordinates.let { GeoPoint(it.lat, it.lng) }
        val match = tripMatch
        val along = withContext(Dispatchers.Default) { match.along(here, notBefore = tripAlong) }
        // A reroute's match landed meanwhile: this position is on the old route, so leave it to the next run.
        if (match !== tripMatch) return@LaunchedEffect
        tripAlong = along
        tripTraffic = match.ahead(along)
        routeIncidents = match.incidentsAhead(along)
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

    // Police activity ahead (state 511 events), said once each when it is within about a mile on what is left of
    // the route and on our side of the road. Needs a 511 feed on; the poll above runs during any trip,
    // so this works with the traffic layer hidden. The voice policy applies the traffic-alert toggle and chime.
    val policeSpoken = stringResource(R.string.explore_police_spoken)
    val policeSpokenNoRoad = stringResource(R.string.explore_police_spoken_no_road)
    val announcedPolice = services.tripMemory.announcedPolice
    LaunchedEffect(inTrip) { if (!inTrip) announcedPolice.clear() }
    LaunchedEffect(inTrip, navState.routeGeometry.size, traffic, tripProgressBucket) {
        val navigating = trip as? TripState.Navigating ?: return@LaunchedEffect
        if (!services.traffic.hasIncidents) return@LaunchedEffect
        val police = traffic.incidents.filter { it.kind == IncidentKind.POLICE && it.id !in announcedPolice }
        if (police.isEmpty()) return@LaunchedEffect
        val here = navigating.snappedUserLocation.coordinates
        val geometry = navState.routeGeometry
        val next = withContext(Dispatchers.Default) {
            val ahead = RouteTraffic.remaining(geometry.map { GeoPoint(it.lat, it.lng) }, GeoPoint(here.lat, here.lng))
            PoliceAlerts.ahead(ahead, police).firstOrNull()
        } ?: return@LaunchedEffect
        announcedPolice += next.incident.id
        val road = next.incident.roadway
        services.voice.announce(UtteranceKind.TRAFFIC_ALERT, if (road != null) policeSpoken.format(road) else policeSpokenNoRoad)
    }

    // Crashes, roadwork and lane closures ahead (state 511 events), said once each within about two miles on what
    // is left of a driving route, on our side of the road, with the distance and the lanes when the feed gives
    // them: "Crash ahead on I-87 in 1.8 miles. Right lane closed." A work zone made of several events in a row
    // is said once. Closures and police have their own lines above.
    val crashSpoken = stringResource(R.string.explore_crash_spoken)
    val crashSpokenNoRoad = stringResource(R.string.explore_crash_spoken_no_road)
    val roadworkSpoken = stringResource(R.string.explore_roadwork_spoken)
    val roadworkSpokenNoRoad = stringResource(R.string.explore_roadwork_spoken_no_road)
    val lanesSpoken = stringResource(R.string.explore_lanes_spoken)
    val lanesSpokenNoRoad = stringResource(R.string.explore_lanes_spoken_no_road)
    val announcedIncidents = services.tripMemory.announcedIncidents
    LaunchedEffect(inTrip) { if (!inTrip) announcedIncidents.clear() }
    LaunchedEffect(inTrip, navState.routeGeometry.size, traffic, tripProgressBucket) {
        val navigating = trip as? TripState.Navigating ?: return@LaunchedEffect
        if (!services.traffic.hasIncidents || navigator?.mode != TravelMode.DRIVE) return@LaunchedEffect
        val fresh = traffic.incidents.filter { it.id !in announcedIncidents }
        if (fresh.isEmpty()) return@LaunchedEffect
        val here = navigating.snappedUserLocation.coordinates
        val geometry = navState.routeGeometry
        val found = withContext(Dispatchers.Default) {
            val ahead = RouteTraffic.remaining(geometry.map { GeoPoint(it.lat, it.lng) }, GeoPoint(here.lat, here.lng))
            IncidentAlerts.ahead(ahead, fresh)
        }
        val next = found.firstOrNull() ?: return@LaunchedEffect
        announcedIncidents += next.incident.id
        announcedIncidents += IncidentAlerts.sameZone(next, found).map { it.incident.id }
        val road = next.incident.roadway
        val distance = SpokenUnits.distance(next.metersAhead)
        val lanes = IncidentAlerts.lanesClosed(next.incident)
        val text = when (next.kind) {
            IncidentAlertKind.CRASH -> {
                val crash = if (road != null) crashSpoken.format(road, distance) else crashSpokenNoRoad.format(distance)
                if (lanes != null) "$crash $lanes." else crash
            }
            IncidentAlertKind.ROADWORK -> {
                val work = if (road != null) roadworkSpoken.format(road, distance) else roadworkSpokenNoRoad.format(distance)
                if (lanes != null) "$work $lanes." else work
            }
            IncidentAlertKind.LANES -> if (road != null) lanesSpoken.format(lanes, road, distance) else lanesSpokenNoRoad.format(lanes, distance)
        }
        services.voice.announce(UtteranceKind.TRAFFIC_ALERT, text)
    }

    // Weather alerts the route passes through: listed under the route preview, drawn on the map, and on a trip
    // said once each (warnings, watches and moderate advisories) within about ten miles, or at once if already in
    // one. Only those in effect when the driver should get there, from the route's own time (one that begins later
    // says when). Worked out on the phone from the covered states' alerts; the route is never sent.
    var routeWeather by remember { mutableStateOf<List<AlertOnRoute>>(emptyList()) }
    val previewWeatherRoute = (preview as? RoutePreviewState.Ready)?.let { routes.getOrNull(it.selected) }
    LaunchedEffect(weather, weatherOn, inTrip, navState.routeGeometry.size, tripProgressBucket, previewWeatherRoute) {
        if (!weatherOn || weather.alerts.isEmpty()) {
            routeWeather = emptyList()
            return@LaunchedEffect
        }
        val navigating = trip as? TripState.Navigating
        val geometry = when {
            inTrip -> navState.routeGeometry
            else -> previewWeatherRoute?.geometry.orEmpty()
        }
        val here = navigating?.snappedUserLocation?.coordinates
        val snapshot = weather
        // Seconds a metre: what is left of the trip, or the previewed route's own time over its length.
        val pace = when {
            navigating != null -> navigating.progress.durationRemaining / navigating.progress.distanceRemaining
            previewWeatherRoute != null -> RouteSummary.of(previewWeatherRoute).durationSeconds / previewWeatherRoute.distance
            else -> 0.0
        }
        routeWeather = withContext(Dispatchers.Default) {
            val line = geometry.map { GeoPoint(it.lat, it.lng) }
            val ahead = if (here != null) RouteTraffic.remaining(line, GeoPoint(here.lat, here.lng)) else line
            RouteWeather.alongRoute(ahead, snapshot.alerts, snapshot.areas, System.currentTimeMillis(), pace)
        }
    }
    val weatherHereSpoken = stringResource(R.string.explore_weather_here_spoken)
    val weatherAheadSpoken = stringResource(R.string.explore_weather_ahead_spoken)
    val weatherHereFromSpoken = stringResource(R.string.explore_weather_here_from_spoken)
    val weatherAheadFromSpoken = stringResource(R.string.explore_weather_ahead_from_spoken)
    val spokenContext = LocalContext.current
    val announcedWeather = services.tripMemory.announcedWeather
    LaunchedEffect(inTrip) { if (!inTrip) announcedWeather.clear() }
    LaunchedEffect(routeWeather, inTrip) {
        if (!inTrip) return@LaunchedEffect
        val next = routeWeather.firstOrNull { it.alert.id !in announcedWeather && it.alert.severity.spoken && it.metersAhead <= WEATHER_AHEAD_METERS }
            ?: return@LaunchedEffect
        announcedWeather += next.alert.id
        val from = next.startsAt?.let { android.text.format.DateFormat.getTimeFormat(spokenContext).format(java.util.Date(it)) }
        val text = when {
            next.metersAhead < 100 && from != null -> weatherHereFromSpoken.format(next.alert.event, from)
            next.metersAhead < 100 -> weatherHereSpoken.format(next.alert.event)
            from != null -> weatherAheadFromSpoken.format(next.alert.event, SpokenUnits.distance(next.metersAhead), from)
            else -> weatherAheadSpoken.format(next.alert.event, SpokenUnits.distance(next.metersAhead))
        }
        services.voice.announce(UtteranceKind.TRAFFIC_ALERT, text)
    }

    // Every few minutes of a trip with traffic on: is there a route that now saves 5+ minutes?
    val latestNav by rememberUpdatedState(navState)
    val latestTraffic by rememberUpdatedState(traffic)
    val latestTripMatch by rememberUpdatedState(tripMatch)
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
            val speeds = if (nav.mode == TravelMode.DRIVE) latestTraffic.speeds else emptyList()
            val here = tripNow.snappedUserLocation.coordinates.let { GeoPoint(it.lat, it.lng) }
            // The same remaining-route delay the trip screen shows, from the driver's position now.
            val currentSlow = latestTripMatch.ahead(here, notBefore = tripAlong)
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
                if (best == null || minutes > best.savesMinutes) best = FasterOffer(candidate, points, minutes, extra)
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
    // Arrived: Ferrostar's own arrival, or GuidanceSupport's a few metres from the end with a poor fix.
    val arrivedEarly by services.guidance.arrived.collectAsState()
    val arrivedNow = trip is TripState.Complete || (arrivedEarly && trip is TripState.Navigating)
    LaunchedEffect(arrivedNow) {
        if (!arrivedNow) {
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

    // Over the limit for a while: one alert per stretch of road, not a reminder every minute. SpeedWarningPolicy
    // decides when; GuidancePolicy turns it into speech, a beep or nothing (Settings > Speed limit alerts).
    val speedMph = location?.takeIf { inTrip && it.hasSpeed() }?.let { SpeedLimits.mph(it.speed) }
    val speedLimit = remember(navState) { if (inTrip) SpeedLimits.currentMph(navState) else null }
    val speedingSpoken = stringResource(R.string.explore_speeding_spoken)
    val speedPolicy = remember { SpeedWarningPolicy() }
    val speedAlertsOn = voiceSettings.speedAlerts != SpeedAlertMode.OFF
    // Keyed on the fix too: the policy times its grace period by fixes, and a steady speed doesn't change speedMph.
    LaunchedEffect(location, speedMph, speedLimit, inTrip, speedAlertsOn) {
        if (!inTrip || !speedAlertsOn) {
            // A new trip, or alerts turned on mid-drive, starts fresh.
            speedPolicy.reset()
            return@LaunchedEffect
        }
        val warning = speedPolicy.update(SystemClock.elapsedRealtime(), speedMph, speedLimit) ?: return@LaunchedEffect
        services.voice.announce(UtteranceKind.SPEED_LIMIT, speedingSpoken.format(warning.limitMph))
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
        // A route from a start picked on the map is for looking at: the trip would begin from where the car isn't.
        if (startPlace != null) return
        val ready = preview as? RoutePreviewState.Ready ?: return
        val route = routes.getOrNull(ready.selected) ?: return
        val nav = navigator ?: return
        // The trip notification shows the next turn; navigation works without it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        services.voice.guidanceMode = target.mode.guidanceMode()
        services.tripWait = null
        tripPlan = target
        pausedTrip = null
        // Says "Starting. You'll arrive at about 1:11 PM, 12 miles." before the first turn.
        val summary = ready.summaries.getOrNull(ready.selected) ?: RouteSummary.of(route)
        services.guidance.startTrip(
            navigator = nav,
            route = route,
            mode = target.mode,
            places = (target.stops + target.place).map { it.name },
            durationSeconds = summary.durationSeconds + ready.delaySeconds.getOrElse(ready.selected) { 0.0 },
            distanceMeters = summary.distanceMeters,
        )
    }

    // Resume: the route from here to what was left of the paused trip, started as soon as it is planned.
    LaunchedEffect(resumeWhenReady, preview, directions) {
        if (!resumeWhenReady) return@LaunchedEffect
        when (preview) {
            is RoutePreviewState.Ready -> {
                resumeWhenReady = false
                startTrip()
            }
            is RoutePreviewState.Failed -> resumeWhenReady = false
            else -> Unit
        }
    }

    BackHandler(enabled = !inTrip && panel != Panel.Browse) {
        // Phase 6: backing out of picking a stop returns to the route being planned.
        addingStopTo?.let {
            panel = it
            addingStopTo = null
            return@BackHandler
        }
        panel = when (val current = panel) {
            is Panel.Directions -> {
                // As the preview's close button: a later map arriving doesn't reopen it.
                services.tripWait = null
                Panel.PlaceShown(current.place)
            }
            else -> Panel.Browse
        }
    }

    val baseTrafficOverlay = if (trafficVisible && trafficOn) traffic.toOverlay() else TrafficOverlay.NONE
    // Phase 8: the state 511s' cameras and signs with the traffic layer. Remembered per list, so the map only
    // re-sends them when the feed brings a new one. Blank signs say nothing, so they aren't drawn.
    val trafficCameraPins = remember(traffic.cameras) {
        traffic.cameras.map { TrafficCameraPin(it.id, MapPoint(it.lat, it.lon), offline = !it.hasImage) }
    }
    val messageSignPins = remember(traffic.signs) {
        traffic.signs.filterNot { it.isBlank }.map { MessageSignPin(it.id, MapPoint(it.lat, it.lon)) }
    }
    // Incidents along the route are pinned even when the traffic layer itself is hidden.
    // Cameras and school zones ahead on the trip, from the search pack, drawn as pins too.
    val routeAlerts by services.guidance.routeAlerts.collectAsState()
    // Plate readers are on the always-on camera layer, so only speed cameras and school zones are pinned here.
    val alertPins = if (inTrip) {
        routeAlerts.filter { it.kind != AlertKind.ALPR_CAMERA }
            .map { IncidentPin("alert:${it.id}", MapPoint(it.lat, it.lon), it.kind.name, closure = false) }
    } else {
        emptyList()
    }
    // With the layer shown: radar and every alert. Otherwise only the alerts on the route, so a warning that
    // matters shows up without the layer. Remembered, so the map only gets a new picture or list when one arrives.
    val radarPicture = remember(weather.radar) { weather.radar?.toPicture() }
    val weatherAreas = remember(weather, weatherVisible, routeWeather) {
        val shown = if (weatherVisible) weather.alerts else routeWeather.map { it.alert }
        shown.map { it.toArea(weather.areas[it.id].orEmpty().ifEmpty { it.polygons }) }
    }
    // Each station's badge: the icon (sun or moon by the station's own sunset) and the temperature. Re-made each
    // quarter hour so day turns to night.
    val stationPins = remember(weather.conditions, clockMs / 900_000) {
        val now = System.currentTimeMillis()
        weather.conditions.map { it.toPin(now) }
    }
    val weatherOverlay = when {
        !weatherOn -> WeatherOverlay.NONE
        else -> WeatherOverlay(
            radar = if (weatherVisible) radarPicture else null,
            areas = weatherAreas,
            stations = if (weatherVisible) stationPins else emptyList(),
        )
    }
    val trafficOverlay = baseTrafficOverlay.copy(
        incidents = (baseTrafficOverlay.incidents + routeIncidents.map { it.toPin() } + alertPins).distinctBy { it.id },
    )
    val overlays = when {
        inTrip -> MapOverlays(
            routes = listOf(MapLine(navState.routeGeometry.map { MapPoint(it.lat, it.lng) }, MapLine.Role.LEADER)),
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
                val ready = preview as? RoutePreviewState.Ready
                val selected = ready?.selected ?: 0
                // The cameras the selected option's card counts, pinned at every zoom: the plate-reader layer
                // only starts at zoom 11, where one camera on a long route is a small dot hard to come across.
                val exposurePins = ready?.exposure?.getOrNull(selected)?.marks.orEmpty().map { it.toExposurePin() }
                MapOverlays(
                    routes = routes.mapIndexed { i, route ->
                        val role = if (i == selected) MapLine.Role.LEADER else MapLine.Role.THREAD
                        MapLine(route.geometry.map { MapPoint(it.lat, it.lng) }, role, rank = i)
                    },
                    pin = MapPoint(current.place.lat, current.place.lon),
                    traffic = trafficOverlay.copy(incidents = (trafficOverlay.incidents + exposurePins).distinctBy { it.id }),
                    slowdowns = previewTraffic.getOrNull(selected)?.toSpeedLines().orEmpty(),
                )
            }
            else -> MapOverlays(traffic = trafficOverlay)
        }
    }
    // While on route, the puck sits on the road rather than wandering with GPS noise. Its bearing
    // always comes from the gated raw fix, not from the snapped location's course.
    val shownLocation = ((trip as? TripState.Navigating)?.snappedUserLocation?.toAndroidLocation() ?: location)
        ?.withHeading(headingFilter.heading)
    // Phase 6: night mode by sunset, from the last fix and the clock.
    val systemDark = isSystemInDarkTheme()
    val darkTheme = NightMode.useDark(systemDark, inTrip, nightBySunset, location?.latitude, location?.longitude, clockMs)
    val sheetOpen = !inTrip && (panel is Panel.PlaceShown || panel is Panel.Directions)
    // Phase 8: a phone on its side during a trip gets the driving column on the left (NavigationOverlay),
    // and the map shrinks to the rest of the screen so the column covers nothing.
    val landscape = rememberDrivingLandscape()
    val drivingPanelWidth = rememberDrivingPanelWidth()
    val drivingLandscape = inTrip && landscape
    val systemBars = WindowInsets.systemBars.asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current
    // The column covers the left system inset, so only the other edges still pad the map's controls.
    val mapPadding = if (drivingLandscape) {
        PaddingValues(
            top = systemBars.calculateTopPadding(),
            end = systemBars.calculateEndPadding(layoutDirection),
            bottom = systemBars.calculateBottomPadding(),
        )
    } else {
        systemBars
    }
    // The bottom search bar's dock (docs/bottom-search.md), measured: the map's buttons, the Re-center pill, the traffic
    // notes and the map's attribution sit above it, whatever the font size or the banners in it. A tapped pin's card
    // takes its place, so it shows only while nothing else holds the bottom.
    var dockHeight by remember { mutableStateOf(0.dp) }
    val dockShown = searchAtBottom && !inTrip && panel == Panel.Browse && shownThing == null
    // Wide enough (landscape, a tablet) for the dock to keep to the left, with the buttons beside it rather than above.
    val dockBeside = LocalConfiguration.current.screenWidthDp >= DOCK_BESIDE_MIN_WIDTH_DP
    val density = LocalDensity.current
    val mapContentPadding = if (dockShown) {
        PaddingValues(
            start = mapPadding.calculateStartPadding(layoutDirection),
            top = mapPadding.calculateTopPadding(),
            end = mapPadding.calculateEndPadding(layoutDirection),
            bottom = mapPadding.calculateBottomPadding() + DOCK_MARGIN + dockHeight,
        )
    } else {
        mapPadding
    }
    val droppedPin = stringResource(R.string.explore_dropped_pin)

    // Open on the installed regions: Manhattan for NYC alone, the whole state zoomed out for more.
    val home = remember(basemaps) { Regions.boundsOf(basemaps.map { it.region }) }
    // Regions the trip needs that aren't on the phone. With no route, the ones at its start, stops and destination
    // (directions need their local roads); with one, the ones the chosen route crosses without the map.
    val installedRegions = remember(basemaps) { basemaps.map { it.region }.toSet() }
    val selectedRoute = (preview as? RoutePreviewState.Ready)?.let { routes.getOrNull(it.selected) }
    val noRoute = (preview as? RoutePreviewState.Failed)?.reason == RoutePreviewState.Reason.NO_ROUTE
    // Keyed on having a start too: before the first fix there is none, and its region joins when the fix comes.
    val hasOrigin = startPlace != null || location != null
    val missingRegions = remember(selectedRoute, noRoute, directions, installedRegions, hasOrigin, startPlace) {
        val target = directions?.takeIf { !it.transit }
        when {
            selectedRoute != null -> RouteRegions.missing(selectedRoute.geometry.map { p -> p.lat to p.lng }, installedRegions)
            noRoute && target != null -> RouteRegions.missing(
                listOfNotNull(origin()?.let { it.latitude to it.longitude }) + (target.stops + target.place).map { it.lat to it.lon },
                installedRegions,
                step = 1,
            )
            else -> emptyList()
        }
    }
    // Getting them for the trip ("Get for this trip"): what they add to the download, from the last release list
    // the updater read, then the update round's progress.
    val updateState by UpdateStatus.state.collectAsState()
    val updateQueue by remember { UpdateScheduler.queue(context) }.collectAsState(UpdateScheduler.Queue.IDLE)
    val networkToggles by services.networkToggles.state.collectAsState()
    var lastManifest by remember { mutableStateOf<ReleaseManifest?>(null) }
    LaunchedEffect(missingRegions.isNotEmpty()) {
        if (missingRegions.isNotEmpty() && lastManifest == null) lastManifest = withContext(Dispatchers.IO) { ManifestCache.load(context) }
    }
    val missingIds = missingRegions.map { it.id }.toSet()
    val requested = wantedRegions.containsAll(missingIds)
    val busy = updateQueue != UpdateScheduler.Queue.IDLE || updateState is UpdateState.Checking || updateState is UpdateState.Downloading
    val tripFetch: TripFetch? = when {
        missingRegions.isEmpty() -> null
        networkToggles[NetworkFeature.PACK_DOWNLOADS] != true -> TripFetch.Off
        requested && updateState is UpdateState.Downloading ->
            (updateState as UpdateState.Downloading).let { TripFetch.Downloading(it.overallDone shr 20, it.overallTotal shr 20) }
        requested && updateQueue == UpdateScheduler.Queue.WAITING -> TripFetch.Waiting
        requested && busy -> TripFetch.Working
        requested && updateState is UpdateState.Failed -> TripFetch.Failed((updateState as UpdateState.Failed).message)
        // Not asked yet, or asked while another round was running (it took the regions as they were): offer it.
        else -> TripFetch.Offer(lastManifest?.let { (UpdatePlan.addedBytes(it, wantedRegions - missingIds, missingIds) shr 20).takeIf { mb -> mb > 0 } })
    }
    fun getTripRegions(target: Panel.Directions) {
        regionSelection.addForTrip(missingIds)
        services.tripWait = ExploreServices.TripWait(target.place, target.mode, target.stops, startPlace, System.currentTimeMillis())
        UpdateScheduler.checkNow(context, anyNetwork = false)
    }
    // Done waiting once the trip routes with nothing missing, or the preview moved on to another place.
    LaunchedEffect(preview, missingRegions.isEmpty(), directions?.place) {
        val wait = services.tripWait ?: return@LaunchedEffect
        val place = directions?.place
        if ((preview is RoutePreviewState.Ready && missingRegions.isEmpty()) || (place != null && place != wait.place)) services.tripWait = null
    }
    Box(Modifier.fillMaxSize()) {
        val shownCards = stateCards
        val cardRequest = StateCardRequest.decode(stateCardRequest)
        val banner = stateBanner
        if (stateCardsOn && inTrip && shownCards != null && banner != null && cardRequest == null) {
            val bannerCard = shownCards[banner.code]
            val bannerFrom = shownCards[banner.from]
            if (bannerCard != null && bannerFrom != null) {
                StateCardBanner(
                    card = bannerCard,
                    changed = StateCards.diff(bannerFrom, bannerCard),
                    onOpen = {
                        stateBanner = null
                        stateCardRequest = banner.encode()
                    },
                    onDismiss = { stateBanner = null },
                    modifier = Modifier.align(Alignment.Center).safeDrawingPadding().padding(horizontal = 16.dp),
                )
            }
        }
        if (stateCardsOn && shownCards != null && cardRequest != null) {
            shownCards[cardRequest.code]?.let { card ->
                val from = shownCards[cardRequest.from]
                StateCardSheet(
                    card = card,
                    changed = from?.let { StateCards.diff(it, card) },
                    fromCode = cardRequest.from,
                    onDismiss = { stateCardRequest = null },
                )
            }
        }
        if (showRouteOptions) {
            RouteOptionsSheet(
                prefs = routePrefs,
                onChange = routeOptions::set,
                onDismiss = { showRouteOptions = false },
                mode = (panel as? Panel.Directions)?.mode ?: TravelMode.DRIVE,
            )
        }
        SouthboundMap(
            basemaps = basemaps.map { it.region to it.file },
            initialCenter = home?.let { MapPoint(it.centerLat, it.centerLon) } ?: NYC_CENTER,
            initialZoom = home?.let { (log2(360.0 / maxOf(it.maxLon - it.minLon, (it.maxLat - it.minLat) * 1.4)) + 1.5).coerceIn(6.5, 11.0) } ?: 11.0,
            darkTheme = darkTheme, // Phase 6: system theme, or after sunset while navigating
            location = shownLocation,
            following = following,
            onFollowingDismissed = { following = false },
            modifier = if (drivingLandscape) Modifier.fillMaxSize().padding(start = drivingPanelWidth) else Modifier.fillMaxSize(),
            contentPadding = mapContentPadding,
            overlays = overlays.copy(
                cameras = cameraPins,
                trafficCameras = if (trafficVisible && trafficOn) trafficCameraPins else emptyList(),
                messageSigns = if (trafficVisible && trafficOn) messageSignPins else emptyList(),
                weather = weatherOverlay,
                wifi = if (wifiVisible && wifiPack != null) wifiOverlay else WifiOverlay.NONE,
            ),
            cameraRequest = cameraRequest,
            navigating = inTrip,
            onLongPress = if (inTrip) {
                null
            } else {
                { point ->
                    // Phase 6: a long press while planning a route can become a stop.
                    (panel as? Panel.Directions)?.takeIf { !it.transit }?.let { addingStopTo = it }
                    showPlace(Place.pin(point.lat, point.lon, droppedPin))
                }
            },
            obscured = PaddingValues(bottom = if (sheetOpen) SHEET_HEIGHT else 0.dp),
            onIncidentTap = { id ->
                // A plate reader pinned on a route option opens its card, as its dot does; a DOT camera, its own.
                shownThing = traffic.incidents.firstOrNull { it.id == id }?.let(ShownThing::IncidentShown)
                    ?: cameraPins.firstOrNull { it.id == id }?.let(ShownThing::PlateReader)
                    ?: (preview as? RoutePreviewState.Ready)?.exposure?.flatMap { it.marks }
                        ?.firstOrNull { it.category == Landmark.DOT_CAMERA && it.toExposurePin().id == id }
                        ?.let(ShownThing::DotCamera)
            },
            // A tap on another route's line picks it, as tapping its card does.
            onRouteTap = { rank ->
                val ready = preview as? RoutePreviewState.Ready
                val driving = (panel as? Panel.Directions)?.transit == false
                if (!inTrip && driving && ready != null && rank in ready.summaries.indices) {
                    preview = ready.copy(selected = rank)
                }
            },
            onTouchChanged = { down ->
                touching = down
                touchSerial++
            },
            onCameraTap = { id ->
                shownThing = cameraPins.firstOrNull { it.id == id }?.let(ShownThing::PlateReader)
            },
            onTrafficCameraTap = { id -> shownThing = ShownThing.TrafficCam(id) },
            onMessageSignTap = { id -> shownThing = ShownThing.Sign(id) },
            onWeatherAreaTap = { id -> shownThing = ShownThing.WeatherAlertShown(id) },
            onWeatherStationTap = { id -> shownThing = ShownThing.WeatherStation(id) },
            onCameraIdle = { mapCenter = LatLon(it.lat, it.lon) },
            onViewportIdle = { viewport = it },
            onWifiTap = { tap ->
                when (tap) {
                    is WifiTap.Spot -> {
                        val pack = wifiPack
                        scope.launch {
                            val spot = withContext(Dispatchers.IO) { pack?.let { attempt { it.spot(tap.id) } } }
                            if (spot != null) showWifiSpot(spot)
                        }
                    }
                    is WifiTap.Cluster -> {
                        following = false
                        cameraRequest = CameraRequest.Show(tap.point, WifiLayer.zoomAfterClusterTap(viewport?.zoom))
                    }
                }
            },
        )

        if (userDataState is LoadState.Unreadable && !unreadableAcknowledged) {
            UnreadableDataDialog(
                startFreshFailed = startFreshFailed,
                onKeepTrying = {
                    unreadableAcknowledged = true
                    scope.launch(Dispatchers.IO) { attempt { services.userData.reload() } }
                },
                onStartFresh = {
                    scope.launch {
                        startFreshFailed = withContext(Dispatchers.IO) { attempt { services.userData.startFresh() } } == null
                    }
                },
            )
        }

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
                // Phase 6: Set as Home / Work.
                onSetShortcut = { kind, on ->
                    scope.launch(Dispatchers.IO) { attempt { services.userData.setShortcut(kind, if (on) place.toRef() else null) } }
                },
            )
        }

        // A paused trip: where it was going, End (X) and Resume on the far right, over the browsing map. With the search
        // bar at the bottom it sits in the dock, just above the bar, while browsing (below).
        val resumePausedTrip: (PausedTrip) -> Unit = { paused ->
            pausedTrip = null
            // From here: a start picked on the map can't be driven from (Start is off for it), and Resume starts at once.
            startPlace = null
            // Wait for a new route from here, not the one the trip started with.
            routes = emptyList()
            preview = RoutePreviewState.Loading
            resumeWhenReady = true
            panel = Panel.Directions(paused.place, paused.mode, stops = paused.stops)
        }
        val paused = pausedTrip
        if (!inTrip && paused != null && panel !is Panel.Directions && !(searchAtBottom && panel == Panel.Browse)) {
            PausedTripBar(
                paused.place.name,
                onEnd = { pausedTrip = null },
                onResume = { resumePausedTrip(paused) },
                modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(start = 8.dp, end = 88.dp, bottom = 8.dp),
            )
        }

        val mutedUntilTurn by services.voice.mutedUntilNextTurn.collectAsState()
        if (inTrip) {
            NavigationOverlay(
                state = navState,
                voiceMode = voiceSettings.mode,
                onVoiceModeChange = { mode -> scope.launch { services.voiceStore.update { it.copy(mode = mode) } } },
                onEnd = { navigator?.stop() },
                speedMph = speedMph,
                // Phase 6: skip stop, after-sunset dark overlay, clock for the arrival time.
                onSkipStop = { scope.launch { attempt { navigator?.skipNextStop() } } },
                darkOverlay = darkTheme && !systemDark,
                delaySeconds = tripTraffic.delaySeconds,
                fasterRoute = fasterOffer?.let { offer ->
                    FasterRouteOffer(
                        savesMinutes = offer.savesMinutes,
                        onAccept = {
                            fasterOffer = null
                            val nav = navigator
                            scope.launch {
                                // The offer was routed from where the car was when it was found, up to a minute and a half
                                // ago: route again from here the same way, and fall back on the offer if that finds none.
                                val fresh = attempt { nav?.freshRoute(offer.extra) }
                                nav?.switchTo(fresh ?: offer.route)
                            }
                        },
                        onDismiss = {
                            dismissedRoutes.dismiss(offer.points, System.currentTimeMillis())
                            fasterOffer = null
                        },
                    )
                },
                arrivalSide = arrivalSide,
                parkingNearby = parkingNearby,
                arrived = arrivedEarly,
                mutedUntilNextTurn = mutedUntilTurn,
                onVoiceLongPress = { if (mutedUntilTurn) services.voice.resumeVoice() else services.voice.muteUntilNextTurn() },
                // Pause keeps what is left of the trip (the destination and the stops not yet reached) and ends
                // guidance: no prompts, no rerouting while stopped for gas or food. Resume routes again from there.
                onPause = tripPlan?.takeIf { !it.transit }?.let { plan ->
                    fun() {
                        val all = plan.stops + plan.place
                        val left = (trip as? TripState.Navigating)?.remainingWaypoints?.size ?: all.size
                        val remaining = all.takeLast(left.coerceIn(1, all.size))
                        pausedTrip = PausedTrip(remaining.last(), remaining.dropLast(1), plan.mode)
                        navigator?.stop()
                    }
                },
            )
            if (!following && location != null) {
                // Above the ETA and trip progress at the bottom.
                RecenterPill(
                    { following = true },
                    if (drivingLandscape) {
                        drivingMapBottomCenter(drivingPanelWidth)
                    } else {
                        Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 150.dp)
                    },
                )
            }
        } else {
            when (val current = panel) {
                Panel.Browse -> {
                    val homePlace = userData.home?.toPlace()
                    val workPlace = userData.work?.toPlace()
                    val openShortcut: (Place) -> Unit = { place -> panel = Panel.Directions(place, TravelMode.DRIVE, transit = !hasRouting) }
                    // With the weather layer: now and today where the user is (nearest station, forecast pack).
                    val here = location
                    if (searchAtBottom) {
                        // Only the read-only weather chip stays at the top; everything touched is in the dock.
                        if (weatherOn && weatherVisible && here != null) {
                            val point = GeoPoint(here.latitude, here.longitude)
                            TodayHereChip(
                                Metar.nearest(weather.conditions, point),
                                weather.forecast?.nearest(point)?.let { Forecasts.days(it, clockMs, count = 1).firstOrNull() },
                                Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(top = 12.dp, start = 16.dp, end = 72.dp),
                            )
                        }
                        if (shownThing == null) {
                            Column(
                                Modifier
                                    .align(if (dockBeside) Alignment.BottomStart else Alignment.BottomCenter)
                                    .safeDrawingPadding()
                                    .padding(start = DOCK_MARGIN, end = DOCK_MARGIN, bottom = DOCK_MARGIN)
                                    .then(if (dockBeside) Modifier.width(DOCK_MAX_WIDTH) else Modifier.fillMaxWidth())
                                    .onSizeChanged { dockHeight = with(density) { it.height.toDp() } }
                                    // TalkBack reaches search first, not after the map's buttons.
                                    .semantics {
                                        isTraversalGroup = true
                                        traversalIndex = -1f
                                    },
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                ShortcutChips(startPlace, onClearStart = { startPlace = null }, home = homePlace, work = workPlace, onOpen = openShortcut)
                                LocationStatus(hasPermission, location, asked, ::requestPermission)
                                pausedTrip?.let { pausedNow ->
                                    PausedTripBar(pausedNow.place.name, onEnd = { pausedTrip = null }, onResume = { resumePausedTrip(pausedNow) }, modifier = Modifier)
                                }
                                SearchBar(onSearch = { panel = Panel.Search }, onOpenSettings = onOpenSettings)
                            }
                        }
                    } else {
                        Column(
                            Modifier
                                .align(Alignment.TopCenter)
                                .safeDrawingPadding()
                                .padding(top = 12.dp, start = 16.dp, end = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SearchBar(onSearch = { panel = Panel.Search }, onOpenSettings = onOpenSettings)
                            ShortcutChips(startPlace, onClearStart = { startPlace = null }, home = homePlace, work = workPlace, onOpen = openShortcut)
                            LocationStatus(hasPermission, location, asked, ::requestPermission)
                            if (weatherOn && weatherVisible && here != null) {
                                val point = GeoPoint(here.latitude, here.longitude)
                                TodayHereChip(
                                    Metar.nearest(weather.conditions, point),
                                    weather.forecast?.nearest(point)?.let { Forecasts.days(it, clockMs, count = 1).firstOrNull() },
                                )
                            }
                        }
                    }
                    // One Map options button where the buttons were: above the dock when it spans the width, beside it
                    // when it keeps to the left. Its list holds Re-center, Traffic, Weather and Free Wi-Fi (a long press
                    // there opens the same chooser the Wi-Fi button's did), each under the condition its button had.
                    MapOptions(
                        state = MapOptionsState(
                            hasLocation = location != null,
                            following = following,
                            trafficAvailable = trafficOn,
                            trafficVisible = trafficVisible,
                            weatherAvailable = weatherOn,
                            weatherVisible = weatherVisible,
                            wifiAvailable = wifiPack != null,
                            wifiVisible = wifiVisible,
                            wifiFiltered = !wifiFilter.isDefault,
                            stateAvailable = stateCardsOn && stateCards != null && currentState != null,
                        ),
                        onRecenter = { following = true },
                        onToggleTraffic = { trafficVisible = !trafficVisible },
                        onToggleWeather = { weatherVisible = !weatherVisible },
                        onToggleWifi = { wifiVisible = !wifiVisible },
                        onChooseWifi = { wifiChooser = true },
                        onThisState = { currentState?.let { stateCardRequest = StateCardRequest(it).encode() } },
                        bottomInset = if (dockShown && !dockBeside) dockHeight + 8.dp else 0.dp,
                    )
                    if (wifiChooser && wifiPack != null) {
                        WifiChooserSheet(
                            current = wifiFilter,
                            // Done also turns the layer on: choosing what to see is asking to see it.
                            onDone = { chosen ->
                                wifiFilter = chosen
                                wifiVisible = true
                                wifiChooser = false
                            },
                            onDismiss = { wifiChooser = false },
                        )
                    }
                    // Why the traffic layer may look empty: say so instead of showing nothing.
                    val trafficNote = when {
                        !trafficVisible || !trafficOn -> null
                        traffic.speedsStale -> R.string.explore_traffic_stale
                        traffic.fetchedAt > 0 && traffic.isEmpty && traffic.errors.isEmpty() -> R.string.explore_traffic_empty
                        else -> null
                    }
                    if (trafficVisible && (traffic.errors.isNotEmpty() || trafficNote != null)) {
                        Column(
                            Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(
                                start = 16.dp,
                                bottom = if (dockShown) dockHeight + DOCK_MARGIN + 12.dp else 96.dp,
                                end = 96.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (traffic.errors.isNotEmpty()) {
                                Text(
                                    traffic.errors.joinToString("\n"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (trafficNote != null) {
                                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                                    Text(
                                        stringResource(trafficNote),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                Panel.Search -> SearchPanel(
                    // The field on the keyboard with the best match right above it (docs/bottom-search.md).
                    fieldAtBottom = searchAtBottom,
                    modifier = if (searchAtBottom && dockBeside) Modifier.align(Alignment.TopStart).width(DOCK_MAX_WIDTH) else Modifier,
                    // A weather search ("weather albany") shows each town's weather in its row.
                    weatherFor = if (weatherOn) {
                        { place ->
                            val point = GeoPoint(place.lat, place.lon)
                            WeatherBadge(
                                Metar.nearest(weather.conditions, point),
                                weather.forecast?.nearest(point)?.let { Forecasts.days(it, clockMs, count = 1).firstOrNull() },
                            )
                        }
                    } else {
                        null
                    },
                    index = searchIndex,
                    wifi = wifiPack,
                    onPickWifi = ::showWifiSpot,
                    wifiMarks = wifiMarks,
                    near = near,
                    // Around the user while the map follows them; once it is moved (a pan, a place or a route shown),
                    // around where it is. Before the first fix, around the map too.
                    area = if (following && near != null) null else mapCenter,
                    onPick = { showPlace(it.place) },
                    onClose = {
                        // Phase 6: closing the stop search returns to the route being planned.
                        panel = addingStopTo?.also { addingStopTo = null } ?: Panel.Browse
                    },
                    // Phase 6: Home and Work chips (not while picking a stop).
                    home = userData.home?.toPlace(),
                    work = userData.work?.toPlace(),
                    onOpenShortcut = if (addingStopTo == null) {
                        { place -> panel = Panel.Directions(place, TravelMode.DRIVE, transit = !hasRouting) }
                    } else {
                        null
                    },
                    onSetShortcut = { slot, place ->
                        val kind = if (slot == ShortcutSlot.HOME) Shortcut.HOME else Shortcut.WORK
                        scope.launch(Dispatchers.IO) { attempt { services.userData.setShortcut(kind, place.toRef()) } }
                    },
                    groups = userData.placeGroups(),
                    unreadableNote = if (userDataState is LoadState.Unreadable) stringResource(R.string.explore_unreadable_note) else null,
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
                    onClose = { panel = addingStopTo?.also { addingStopTo = null } ?: Panel.Browse },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .safeDrawingPadding()
                        .padding(8.dp),
                    // Phase 6: Add stop, while a route is being planned.
                    onAddStop = addingStopTo?.let { planned -> { addStop(planned, current.place) } },
                    onStartHere = if (hasRouting || transitPack != null) {
                        {
                            startPlace = current.place
                            panel = Panel.Browse
                        }
                    } else {
                        null
                    },
                    // The place's current weather (the nearest station's report, with the weather toggle on), then
                    // transit departures nearby.
                    extra = run {
                        val placePoint = GeoPoint(current.place.lat, current.place.lon)
                        val here = if (weatherOn) Metar.nearest(weather.conditions, placePoint) else null
                        // The place's 7-day forecast, from the pack's nearest grid point.
                        val forecastDays = if (weatherOn) {
                            weather.forecast?.nearest(placePoint)?.let { Forecasts.days(it, clockMs) }.orEmpty()
                        } else {
                            emptyList()
                        }
                        // Reports came in but none is close: say so rather than show a far station's weather.
                        val noneNear = weatherOn && here == null && weather.conditions.isNotEmpty()
                        // The Wi-Fi spot this sheet was opened for (not any place that shares nothing with it).
                        val wifiSpot = shownWifi?.takeIf { it.osmRef == current.place.osmRef }
                        if (wifiSpot == null && here == null && !noneNear && forecastDays.isEmpty() && boards.isEmpty()) {
                            null
                        } else {
                            {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    wifiSpot?.let { spot ->
                                        WifiDetails(
                                            spot, wifiPack?.extractDate, wifiMarks[spot.osmRef],
                                            onMark = { worked ->
                                                scope.launch(Dispatchers.IO) {
                                                    attempt {
                                                        if (worked == null) {
                                                            services.userData.clearWifiMark(spot.osmRef)
                                                        } else {
                                                            services.userData.markWifi(spot.osmRef, worked)
                                                        }
                                                    }
                                                }
                                            },
                                        )
                                    }
                                    here?.let { WeatherNowRow(it, Modifier.padding(end = 12.dp)) }
                                    if (forecastDays.isNotEmpty()) ForecastStrip(forecastDays, Modifier.padding(end = 12.dp))
                                    if (noneNear) {
                                        Text(
                                            stringResource(R.string.explore_weather_none_near),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (boards.isNotEmpty()) TransitDepartures(boards, nowSeconds)
                                }
                            }
                        }
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
                        fuel = fuel,
                        onModeChange = { mode -> panel = current.copy(mode = mode) },
                        onSelect = { index ->
                            (preview as? RoutePreviewState.Ready)?.let { preview = it.copy(selected = index) }
                        },
                        onStart = ::startTrip,
                        onClose = {
                            services.tripWait = null
                            panel = Panel.PlaceShown(current.place)
                        },
                        startName = startPlace?.name,
                        crossings = routeCrossings,
                        onOpenCrossing = { crossing -> stateCardRequest = StateCardRequest(crossing.to, crossing.from).encode() },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .safeDrawingPadding()
                            .padding(8.dp),
                        onTransit = if (transitPack != null) {
                            { panel = current.copy(transit = true) }
                        } else {
                            null
                        },
                        // Phase 6: options chip and stops under the mode chips; region warning above Start.
                        afterModes = {
                            RouteOptionsChip(active = !routePrefs.isDefaultFor(current.mode), onClick = { showRouteOptions = true })
                            StopsEditor(
                                stops = current.stops.map { it.name },
                                onMove = { i, delta -> panel = current.copy(stops = TripStops.move(current.stops, i, delta)) },
                                onRemove = { i -> panel = current.copy(stops = TripStops.remove(current.stops, i)) },
                                onAdd = {
                                    addingStopTo = current
                                    panel = Panel.Search
                                },
                            )
                        },
                        beforeStart = {
                            // The forecast at the destination for when the chosen route gets there.
                            val ready = preview as? RoutePreviewState.Ready
                            val forecastPoint = if (weatherOn) weather.forecast?.nearest(GeoPoint(current.place.lat, current.place.lon)) else null
                            if (ready != null && forecastPoint != null) {
                                val summary = ready.summaries.getOrNull(ready.selected)
                                if (summary != null) {
                                    val seconds = summary.durationSeconds + ready.delaySeconds.getOrElse(ready.selected) { 0.0 }
                                    val arrival = clockMs + (seconds * 1000).toLong()
                                    ArrivalWeatherNote(current.place.name, arrival, Forecasts.at(forecastPoint, arrival))
                                }
                            }
                            WeatherOnRouteNote(routeWeather, onOpen = { shownThing = ShownThing.WeatherAlertShown(it.id) })
                        },
                        regionOffer = tripFetch?.let { fetch ->
                            {
                                TripRegionOffer(
                                    regionNames = missingRegions.map { it.name },
                                    needed = noRoute,
                                    fetch = fetch,
                                    onGet = { getTripRegions(current) },
                                    onUseMobileData = { UpdateScheduler.checkNow(context, anyNetwork = true) },
                                )
                            }
                        },
                    )
                }
            }
        }

        // --- Phase 5 (map): the tap-to-read card for incident pins, wherever the pin was tapped ---
        val cardModifier = when {
            inTrip -> Modifier.align(Alignment.Center).safeDrawingPadding().padding(horizontal = 16.dp)
            else -> Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(start = 8.dp, end = 88.dp, bottom = if (panel == Panel.Browse) 8.dp else SHEET_HEIGHT)
        }
        when (val shown = shownThing) {
            null -> Unit
            is ShownThing.IncidentShown -> IncidentCard(shown.incident, onClose = { shownThing = null }, modifier = cardModifier)
            // The tap-to-read card for a plate-reader dot.
            is ShownThing.PlateReader -> CameraCard(shown.pin, onClose = { shownThing = null }, modifier = cardModifier)
            // Phase 8: a 511 camera's still, or what a message sign says. Gone from the feed: no card.
            is ShownThing.TrafficCam -> traffic.cameras.firstOrNull { it.id == shown.id }?.let { camera ->
                TrafficCameraCard(camera, services.networkGate, onClose = { shownThing = null }, modifier = cardModifier)
            }
            is ShownThing.Sign -> traffic.signs.firstOrNull { it.id == shown.id }?.let { sign ->
                MessageSignCard(sign, onClose = { shownThing = null }, modifier = cardModifier)
            }
            // A weather alert, from its area on the map or the route note. Expired or gone from the feed: no card.
            is ShownThing.WeatherAlertShown -> weather.alerts.firstOrNull { it.id == shown.id }?.let { alert ->
                WeatherAlertCard(alert, onClose = { shownThing = null }, modifier = cardModifier)
            }
            is ShownThing.WeatherStation -> weather.conditions.firstOrNull { it.station == shown.id }?.let { now ->
                WeatherStationCard(now, onClose = { shownThing = null }, modifier = cardModifier)
            }
            is ShownThing.DotCamera -> {
                val live = traffic.cameras.liveAt(shown.camera.point)
                if (live != null) {
                    TrafficCameraCard(live, services.networkGate, onClose = { shownThing = null }, modifier = cardModifier)
                } else {
                    DotCameraCard(shown.camera.name, trafficOn, onClose = { shownThing = null }, modifier = cardModifier)
                }
            }
        }
    }
}

/** The chosen start point, while one is set instead of GPS. */
@Composable
private fun StartChip(name: String, onClear: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 3.dp) {
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

/** A short wait after the map comes to rest before the Wi-Fi pack is asked, so a fling that settles in two steps asks once. */
private const val WIFI_QUERY_DEBOUNCE_MS = 150L
private const val TRAFFIC_POLL_MS = 60_000L

/** The bottom search bar's dock: its margin from the screen's edges, and its width when it keeps to the left. */
private val DOCK_MARGIN = 12.dp
private val DOCK_MAX_WIDTH = 560.dp

/** The screen width (dp) from which the dock keeps to the left with the map's buttons beside it: dock, margins, buttons. */
private const val DOCK_BESIDE_MIN_WIDTH_DP = 672

/**
 * The chips by the search bar: the start point picked with Start here (with its X), then Home and Work once set, one tap
 * to directions (docs/bottom-search.md). Nothing when none applies.
 */
@Composable
private fun ShortcutChips(start: Place?, onClearStart: () -> Unit, home: Place?, work: Place?, onOpen: (Place) -> Unit) {
    if (start == null && home == null && work == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        start?.let { StartChip(it.name, onClear = onClearStart, modifier = Modifier.weight(1f, fill = false)) }
        home?.let { place -> PlaceChip(stringResource(R.string.explore_home), Icons.Filled.Home) { onOpen(place) } }
        work?.let { place -> PlaceChip(stringResource(R.string.explore_work), Icons.Filled.Work) { onOpen(place) } }
    }
}

@Composable
private fun PlaceChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    ElevatedAssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, Modifier.size(18.dp)) },
    )
}

// The weather feed keeps its own five-minute cache; this only decides how often to ask it.
private const val WEATHER_POLL_MS = 60_000L

/** A weather alert on the route is said once it is within about ten miles. */
private const val WEATHER_AHEAD_METERS = 16_000.0
private const val AUTO_RECENTER_MS = 10_000L
// A route preview waiting on its trip's regions is reopened after a rebuild for this long after the tap.
private const val TRIP_WAIT_MS = 6 * 60 * 60 * 1000L
private const val PARKING_RADIUS_M = 300.0
private const val FASTER_ROUTE_OFFER_MS = 90_000L
private const val FASTER_ROUTE_TRY_DETOUR_SECONDS = 120.0
private const val DETOUR_MIN_DISTANCE_M = 300.0
private const val DETOUR_MAX_SPOTS = 3

// How long a route preview waits for the search pack to open before going without plate readers.
private const val SEARCH_WAIT_MS = 5_000L

/**
 * A faster route found mid-trip, with its points for telling it apart later and [extra], the squares it was routed
 * around (none for a plain fresh route).
 */
private data class FasterOffer(val route: Route, val points: List<GeoPoint>, val savesMinutes: Int, val extra: List<List<Pair<Double, Double>>>)

/**
 * The cameras on a route option that the driver asked to avoid (plate readers, tolls among them; tolls alone with
 * "Avoid tolls"; DOT cameras); none when unknown.
 */
private fun RouteExposure?.avoided(prefs: RoutePrefs): Int {
    if (this == null) return 0
    val readers = when {
        prefs.avoidAlpr -> plateReaders
        prefs.avoidTolls -> tolls
        else -> 0
    }
    return readers + if (prefs.avoidDotCameras) dotCameras else 0
}

/** The landmark categories the avoid options steer around: plate readers with tolls, tolls, DOT cameras. */
private fun RoutePrefs.avoidedCameraCategories(): List<String> = buildList {
    if (avoidAlpr) addAll(Landmark.PLATE_READERS)
    if (avoidTolls && Landmark.TOLL !in this) add(Landmark.TOLL)
    if (avoidDotCameras) add(Landmark.DOT_CAMERA)
}

private fun RouteSlowdowns.toSpeedLines(): List<SpeedLine> =
    segments.map { SpeedLine(it.points.map { p -> MapPoint(p.lat, p.lon) }, it.speedMph) }

private fun Incident.toPin() = IncidentPin(id, MapPoint(location.lat, location.lon), kind.name, isClosure)

/**
 * The traffic feed's camera at [point], within [DOT_CAMERA_MATCH_M] (the pack and the feed read the same 511 list),
 * the nearest when several; null when the feed lists none there (traffic off, or another state's feed).
 */
private fun List<TrafficCamera>.liveAt(point: LatLon): TrafficCamera? =
    map { it to RouteLine.distanceMeters(point, LatLon(it.lat, it.lon)) }
        .filter { it.second <= DOT_CAMERA_MATCH_M }
        .minByOrNull { it.second }?.first

private const val DOT_CAMERA_MATCH_M = 50.0

/** A camera counted on a route option, as a pin; a plate reader's id is its camera dot's, so a tap opens its card. */
private fun Landmark.toExposurePin(): IncidentPin {
    val kind = when (category) {
        Landmark.ALPR_CAMERA, Landmark.TOLL -> AlertKind.ALPR_CAMERA.name
        Landmark.SPEED_CAMERA -> AlertKind.SPEED_CAMERA.name
        else -> IncidentPin.DOT_CAMERA
    }
    val id = if (category in Landmark.PLATE_READERS) PlateReaders.pinId(this.id) else "exposure:$category:${this.id}"
    return IncidentPin(id, MapPoint(lat, lon), kind, closure = false)
}

/** The paused trip's bar: "Paused, to Joe's Pizza", then End (X) and Resume on the far right. */
@Composable
private fun PausedTripBar(destination: String, onEnd: () -> Unit, onResume: () -> Unit, modifier: Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.explore_trip_paused), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.explore_trip_paused_to, destination),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onEnd) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.explore_trip_paused_end))
            }
            IconButton(onClick = onResume) {
                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.explore_trip_resume))
            }
        }
    }
}

/** What a tapped incident pin says: road, direction, what happened, and which 511 updated it when. */
@Composable
private fun IncidentCard(incident: Incident, onClose: () -> Unit, modifier: Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val heading = if (incident.kind == IncidentKind.POLICE) {
                    // "Police activity", then the road when the feed names one (title falls back to the description).
                    if (incident.roadway.isNullOrBlank()) stringResource(R.string.explore_incident_police)
                    else stringResource(R.string.explore_incident_police_on, incident.title)
                } else {
                    incident.title
                }
                Text(heading, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.explore_import_ok))
                }
            }
            Text(incident.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
            val details = listOfNotNull(
                incident.source.label,
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

/** Estimated gallons for each route at [mpg], with the live-traffic delay of each spent idling. */
private fun fuelGallonsFor(routes: List<Route>, delaySeconds: List<Double>, mpg: Double): List<Double> =
    routes.mapIndexed { i, route -> FuelModel.gallons(route, mpg, delaySeconds.getOrElse(i) { 0.0 }) }

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
