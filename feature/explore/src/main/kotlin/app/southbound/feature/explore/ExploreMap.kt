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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import app.southbound.core.map.CameraRequest
import app.southbound.core.map.MapLine
import app.southbound.core.map.MapOverlays
import app.southbound.core.map.MapPoint
import app.southbound.core.map.SouthboundMap
import app.southbound.core.map.gpsUpdates
import app.southbound.core.packs.PackKind
import app.southbound.core.routing.Navigator
import app.southbound.core.routing.RouteSummary
import app.southbound.core.routing.TravelMode
import app.southbound.core.search.LatLon
import app.southbound.core.search.Place
import app.southbound.core.search.SearchIndex
import app.southbound.core.search.SearchRanking
import app.southbound.core.voice.GuidanceMode
import app.southbound.core.voice.UtteranceKind
import app.southbound.feature.directions.RoutePreviewSheet
import app.southbound.feature.directions.RoutePreviewState
import app.southbound.feature.navigation.NavigationOverlay
import app.southbound.feature.search.PlaceCard
import app.southbound.feature.search.SearchPanel
import com.stadiamaps.ferrostar.core.NavigationState
import com.stadiamaps.ferrostar.core.location.toAndroidLocation
import com.stadiamaps.ferrostar.core.location.toUserLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.Route
import uniffi.ferrostar.TripState
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointKind
import java.io.File

/** What is open over the map, outside of a trip. */
private sealed interface Panel {
    data object Browse : Panel
    data object Search : Panel
    data class PlaceShown(val place: Place) : Panel
    data class Directions(val place: Place, val mode: TravelMode) : Panel
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
    basemap: File,
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

    // Packs: search and routing open off the main thread.
    var searchIndex by remember { mutableStateOf<SearchIndex?>(null) }
    var navigator by remember { mutableStateOf<Navigator?>(null) }
    var hasRouting by remember { mutableStateOf(false) }
    LaunchedEffect(packGeneration, resumes) {
        val inTrip = navigator?.state?.value?.tripState.let { it != null && it !is TripState.Idle }
        withContext(Dispatchers.IO) {
            searchIndex = services.openSearch()
            navigator = services.openNavigator(navigating = inTrip)
            hasRouting = services.hasPack(PackKind.ROUTING)
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

    fun showPlace(place: Place) {
        panel = Panel.PlaceShown(place)
        following = false
        cameraRequest = CameraRequest.Show(MapPoint(place.lat, place.lon), PLACE_ZOOM)
    }

    // Route preview: recompute when the destination or mode changes, or when
    // the first GPS fix arrives after "no location".
    val directions = panel as? Panel.Directions
    val hasFix = location != null
    LaunchedEffect(directions, hasFix, navigator) {
        val target = directions ?: return@LaunchedEffect
        val nav = navigator ?: return@LaunchedEffect
        routes = emptyList()
        val from = location ?: run {
            preview = RoutePreviewState.Failed(RoutePreviewState.Reason.NO_LOCATION)
            return@LaunchedEffect
        }
        preview = RoutePreviewState.Loading
        val destination = Waypoint(GeographicCoordinate(target.place.lat, target.place.lon), WaypointKind.BREAK)
        val found = try {
            withContext(Dispatchers.Default) { nav.routes(from.toUserLocation(), listOf(destination), target.mode) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        routes = found
        if (found.isEmpty()) {
            preview = RoutePreviewState.Failed(RoutePreviewState.Reason.NO_ROUTE)
        } else {
            preview = RoutePreviewState.Ready(found.map(RouteSummary::of), selected = 0)
            cameraRequest = CameraRequest.Fit(
                found.flatMap { route -> route.geometry.map { MapPoint(it.lat, it.lng) } } +
                    MapPoint(from.latitude, from.longitude),
            )
        }
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
    LaunchedEffect(navState.isCalculatingNewRoute) {
        if (navState.isCalculatingNewRoute && inTrip) {
            services.voice.announce(UtteranceKind.REROUTE, context.getString(R.string.explore_rerouting_spoken))
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

    val overlays = when {
        inTrip -> MapOverlays(
            routes = listOf(MapLine(navState.routeGeometry.map { MapPoint(it.lat, it.lng) }, selected = true)),
            destination = (trip as? TripState.Navigating)?.remainingWaypoints?.lastOrNull()
                ?.let { MapPoint(it.coordinate.lat, it.coordinate.lng) },
        )
        else -> when (val current = panel) {
            is Panel.PlaceShown -> MapOverlays(pin = MapPoint(current.place.lat, current.place.lon))
            is Panel.Directions -> {
                val selected = (preview as? RoutePreviewState.Ready)?.selected ?: 0
                MapOverlays(
                    routes = routes.mapIndexed { i, route ->
                        MapLine(route.geometry.map { MapPoint(it.lat, it.lng) }, selected = i == selected)
                    },
                    pin = MapPoint(current.place.lat, current.place.lon),
                )
            }
            else -> MapOverlays()
        }
    }
    // While on route, the puck sits on the road rather than wandering with GPS noise.
    val shownLocation = (trip as? TripState.Navigating)?.snappedUserLocation?.toAndroidLocation() ?: location
    val sheetOpen = !inTrip && (panel is Panel.PlaceShown || panel is Panel.Directions)

    Box(Modifier.fillMaxSize()) {
        SouthboundMap(
            basemap = basemap,
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
                    showPlace(Place.pin(point.lat, point.lon, context.getString(R.string.explore_dropped_pin)))
                }
            },
            obscured = PaddingValues(bottom = if (sheetOpen) SHEET_HEIGHT else 0.dp),
        )

        if (inTrip) {
            NavigationOverlay(
                state = navState,
                voiceMode = voiceSettings.mode,
                onVoiceModeChange = { mode -> scope.launch { services.voiceStore.update { it.copy(mode = mode) } } },
                onEnd = { navigator?.stop() },
            )
            if (!following && location != null) {
                RecenterButton(following, { following = true }, Modifier.align(Alignment.CenterEnd))
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
                        LocationStatus(hasPermission, location, asked, ::requestPermission)
                    }
                    if (location != null) {
                        RecenterButton(following, { following = true }, Modifier.align(Alignment.BottomEnd))
                    }
                }
                Panel.Search -> SearchPanel(
                    index = searchIndex,
                    near = near,
                    onPick = { showPlace(it.place) },
                    onClose = { panel = Panel.Browse },
                )
                is Panel.PlaceShown -> PlaceCard(
                    place = current.place,
                    distanceMeters = near?.let { SearchRanking.distanceMeters(it, current.place.lat, current.place.lon) },
                    onDirections = if (hasRouting) {
                        { panel = Panel.Directions(current.place, TravelMode.DRIVE) }
                    } else {
                        null
                    },
                    onClose = { panel = Panel.Browse },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .safeDrawingPadding()
                        .padding(8.dp),
                )
                is Panel.Directions -> RoutePreviewSheet(
                    destinationName = current.place.name,
                    mode = current.mode,
                    state = preview,
                    onModeChange = { mode -> panel = current.copy(mode = mode) },
                    onSelect = { index ->
                        (preview as? RoutePreviewState.Ready)?.let { preview = it.copy(selected = index) }
                    },
                    onStart = ::startTrip,
                    onClose = { panel = Panel.PlaceShown(current.place) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .safeDrawingPadding()
                        .padding(8.dp),
                )
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
