package app.southbound.core.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.view.MotionEvent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.gestures.RotateGestureDetector
import org.maplibre.android.gestures.ShoveGestureDetector
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentConstants
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.iconPitchAlignment
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Where the map opens without a better idea: Manhattan. */
val NYC_CENTER = MapPoint(40.7128, -74.0060)
private const val START_ZOOM = 11.0
private const val MIN_ZOOM = 6.0
private const val FOLLOW_ZOOM = 16.0
private const val NAVIGATION_ZOOM = 17.0
private const val NAVIGATION_TILT = 45.0
private const val CAMERA_ANIMATION_MS = 800
private const val TAP_SLOP_PX = 24f
private val FIT_PADDING = 64.dp
private val NAVIGATION_COMPASS_OFFSET = 120.dp

/**
 * The basemap, drawn from local PMTiles packs (one per installed region),
 * with the user's position.
 *
 * Location is pushed in from [location] rather than read by MapLibre, so the
 * app controls exactly which provider is used. While [following] is true the
 * camera tracks the puck; a pan by the user calls [onFollowingDismissed].
 *
 * [overlays] draws routes and pins; [cameraRequest] moves the camera once per
 * new request. While [navigating], the camera follows the puck heading-up and
 * tilted, as turn-by-turn apps do. A long press reports the spot to
 * [onLongPress], e.g. to drop a pin; a tap on an incident pin reports its id
 * to [onIncidentTap].
 *
 * Two-finger rotate and tilt, double-tap zoom and one-finger "double tap and
 * drag" zoom are on, with MapLibre's compass (it fades when facing north and
 * snaps back to north when tapped). Rotating, tilting or panning while
 * following calls [onFollowingDismissed], so the screen can offer Re-center;
 * [onTouchChanged] lets it time an automatic return while navigating.
 */
@Composable
fun SouthboundMap(
    basemaps: List<Pair<String, File>>,
    darkTheme: Boolean,
    location: Location?,
    following: Boolean,
    onFollowingDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    overlays: MapOverlays = MapOverlays(),
    cameraRequest: CameraRequest? = null,
    navigating: Boolean = false,
    onLongPress: ((MapPoint) -> Unit)? = null,
    /** Screen edges covered by panels; camera moves keep their subject clear of them. */
    obscured: PaddingValues = PaddingValues(),
    onIncidentTap: ((String) -> Unit)? = null,
    /** Where the map opens before the first fix, e.g. the middle of the installed regions. */
    initialCenter: MapPoint = NYC_CENTER,
    initialZoom: Double = START_ZOOM,
    /** True when a finger goes down on the map, false when the last one lifts; drives auto-recenter. */
    onTouchChanged: ((Boolean) -> Unit)? = null,
    /** A tap on a plate-reader dot ([MapOverlays.cameras]) reports its id. */
    onCameraTap: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val mapView = remember {
        val options = MapLibreMapOptions.createFromAttributes(context)
            .foregroundLoadColor(BasemapStyle.loadingColor(darkTheme))
            .camera(CameraPosition.Builder().target(LatLng(initialCenter.lat, initialCenter.lon)).zoom(initialZoom).build())
            .minZoomPreference(MIN_ZOOM)
        MapView(context, options)
    }
    MapViewLifecycle(mapView)

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val currentOnFollowingDismissed by rememberUpdatedState(onFollowingDismissed)

    LaunchedEffect(mapView) {
        map = mapView.awaitMap()
    }

    LaunchedEffect(map, darkTheme, basemaps) {
        val loadedMap = map ?: return@LaunchedEffect
        if (basemaps.isEmpty()) return@LaunchedEffect
        style = null
        val json = withContext(Dispatchers.IO) { BasemapStyle.load(context, darkTheme, basemaps) }
        loadedMap.setStyle(Style.Builder().fromJson(json)) { loaded ->
            activateLocationPuck(context, loadedMap, loaded) { currentOnFollowingDismissed() }
            OverlayLayers.install(loaded)
            style = loaded
        }
    }

    val currentNavigating by rememberUpdatedState(navigating)
    val currentOnTouchChanged by rememberUpdatedState(onTouchChanged)
    DisposableEffect(mapView) {
        // Watches touches without consuming them; the map still handles every gesture itself.
        mapView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> currentOnTouchChanged?.invoke(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> currentOnTouchChanged?.invoke(false)
            }
            false
        }
        onDispose { mapView.setOnTouchListener(null) }
    }

    // Gestures: rotate, tilt and one-finger zoom are on, with the compass.
    DisposableEffect(map) {
        val loadedMap = map ?: return@DisposableEffect onDispose { }
        with(loadedMap.uiSettings) {
            isRotateGesturesEnabled = true
            isTiltGesturesEnabled = true
            isZoomGesturesEnabled = true
            isDoubleTapGesturesEnabled = true
            isQuickZoomGesturesEnabled = true
            isCompassEnabled = true
            setCompassFadeFacingNorth(true)
        }
        // A user gesture (not an animation or API move) that turns or tilts the map ends following:
        // heading-up and tilted navigation would otherwise fight the user's hands.
        val rotate = object : MapLibreMap.OnRotateListener {
            override fun onRotateBegin(detector: RotateGestureDetector) = currentOnFollowingDismissed()
            override fun onRotate(detector: RotateGestureDetector) = Unit
            override fun onRotateEnd(detector: RotateGestureDetector) = Unit
        }
        val shove = object : MapLibreMap.OnShoveListener {
            override fun onShoveBegin(detector: ShoveGestureDetector) = currentOnFollowingDismissed()
            override fun onShove(detector: ShoveGestureDetector) = Unit
            override fun onShoveEnd(detector: ShoveGestureDetector) = Unit
        }
        val move = object : MapLibreMap.OnMoveListener {
            override fun onMoveBegin(detector: MoveGestureDetector) = currentOnFollowingDismissed()
            override fun onMove(detector: MoveGestureDetector) = Unit
            override fun onMoveEnd(detector: MoveGestureDetector) = Unit
        }
        // While navigating any touch-driven camera move (a pinch too) detaches the camera, and the
        // reason tells it apart from the puck's own animations and from our camera requests.
        val moveStarted = MapLibreMap.OnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE && currentNavigating) {
                currentOnFollowingDismissed()
            }
        }
        loadedMap.addOnCameraMoveStartedListener(moveStarted)
        loadedMap.addOnRotateListener(rotate)
        loadedMap.addOnShoveListener(shove)
        loadedMap.addOnMoveListener(move)
        onDispose {
            loadedMap.removeOnCameraMoveStartedListener(moveStarted)
            loadedMap.removeOnRotateListener(rotate)
            loadedMap.removeOnShoveListener(shove)
            loadedMap.removeOnMoveListener(move)
        }
    }

    LaunchedEffect(style, overlays) {
        val loaded = style ?: return@LaunchedEffect
        OverlayLayers.update(loaded, overlays)
    }

    // Plate-reader taps get their own listener, added before the incident one so a camera
    // sitting on a route line or next to an incident wins the tap.
    val currentOnCameraTap by rememberUpdatedState(onCameraTap)
    DisposableEffect(map) {
        val loadedMap = map ?: return@DisposableEffect onDispose { }
        val listener = MapLibreMap.OnMapClickListener { latLng ->
            val handler = currentOnCameraTap ?: return@OnMapClickListener false
            if (loadedMap.style?.getLayer(OverlayLayers.CAMERAS) == null) return@OnMapClickListener false
            val screen = loadedMap.projection.toScreenLocation(latLng)
            val box = android.graphics.RectF(screen.x - TAP_SLOP_PX, screen.y - TAP_SLOP_PX, screen.x + TAP_SLOP_PX, screen.y + TAP_SLOP_PX)
            val hit = loadedMap.queryRenderedFeatures(box, OverlayLayers.CAMERAS).firstOrNull() ?: return@OnMapClickListener false
            val id = hit.getStringProperty("id") ?: return@OnMapClickListener false
            handler(id)
            true
        }
        loadedMap.addOnMapClickListener(listener)
        onDispose { loadedMap.removeOnMapClickListener(listener) }
    }

    val currentOnLongPress by rememberUpdatedState(onLongPress)
    DisposableEffect(map) {
        val loadedMap = map ?: return@DisposableEffect onDispose { }
        val listener = MapLibreMap.OnMapLongClickListener { latLng ->
            val handler = currentOnLongPress ?: return@OnMapLongClickListener false
            handler(MapPoint(latLng.latitude, latLng.longitude))
            true
        }
        loadedMap.addOnMapLongClickListener(listener)
        onDispose { loadedMap.removeOnMapLongClickListener(listener) }
    }

    val currentOnIncidentTap by rememberUpdatedState(onIncidentTap)
    DisposableEffect(map) {
        val loadedMap = map ?: return@DisposableEffect onDispose { }
        val listener = MapLibreMap.OnMapClickListener { latLng ->
            val handler = currentOnIncidentTap ?: return@OnMapClickListener false
            if (loadedMap.style?.getLayer(OverlayLayers.INCIDENTS) == null) return@OnMapClickListener false
            val screen = loadedMap.projection.toScreenLocation(latLng)
            // A finger is wider than a pin: search a small box around the tap.
            val box = android.graphics.RectF(screen.x - TAP_SLOP_PX, screen.y - TAP_SLOP_PX, screen.x + TAP_SLOP_PX, screen.y + TAP_SLOP_PX)
            val hit = loadedMap.queryRenderedFeatures(box, OverlayLayers.INCIDENTS).firstOrNull() ?: return@OnMapClickListener false
            val id = hit.getStringProperty("id") ?: return@OnMapClickListener false
            handler(id)
            true
        }
        loadedMap.addOnMapClickListener(listener)
        onDispose { loadedMap.removeOnMapClickListener(listener) }
    }

    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    LaunchedEffect(style, cameraRequest) {
        val loadedMap = map ?: return@LaunchedEffect
        val request = cameraRequest ?: return@LaunchedEffect
        if (style == null) return@LaunchedEffect
        // An explicit move ends following; the recenter button brings it back.
        if (loadedMap.locationComponent.isLocationComponentActivated) {
            loadedMap.locationComponent.cameraMode = CameraMode.NONE
        }
        val insets = with(density) {
            val ld = layoutDirection
            listOf(
                obscured.calculateLeftPadding(ld),
                obscured.calculateTopPadding(),
                obscured.calculateRightPadding(ld),
                obscured.calculateBottomPadding(),
            ).map { (it + FIT_PADDING).roundToPx() }
        }
        when (request) {
            is CameraRequest.Show -> loadedMap.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(request.point.lat, request.point.lon))
                        .zoom(request.zoom)
                        .padding(insets[0].toDouble(), insets[1].toDouble(), insets[2].toDouble(), insets[3].toDouble())
                        .build(),
                ),
                CAMERA_ANIMATION_MS,
            )
            is CameraRequest.Fit -> {
                if (request.points.isEmpty()) return@LaunchedEffect
                val bounds = LatLngBounds.Builder()
                    .includes(request.points.map { LatLng(it.lat, it.lon) })
                    .build()
                loadedMap.animateCamera(
                    CameraUpdateFactory.newLatLngBounds(bounds, insets[0], insets[1], insets[2], insets[3]),
                    CAMERA_ANIMATION_MS,
                )
            }
        }
    }

    // Push each GPS fix to the puck. Enabling waits for the first fix, which
    // also means location permission has been granted.
    LaunchedEffect(style, location) {
        val loadedMap = map ?: return@LaunchedEffect
        if (style == null || location == null) return@LaunchedEffect
        showFix(loadedMap.locationComponent, location)
    }

    LaunchedEffect(style, following, navigating, location != null) {
        val loadedMap = map ?: return@LaunchedEffect
        val puck = loadedMap.locationComponent
        if (style == null || !puck.isLocationComponentEnabled) return@LaunchedEffect
        // Padding from a place or route view would otherwise stay and shift the puck.
        if (following) loadedMap.moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0))
        when {
            following && navigating ->
                puck.setCameraMode(CameraMode.TRACKING_GPS, 750L, NAVIGATION_ZOOM, null, NAVIGATION_TILT, null)
            following -> {
                val zoom = if (loadedMap.cameraPosition.zoom < FOLLOW_ZOOM - 2) FOLLOW_ZOOM else null
                // Back to north-up after navigation.
                puck.setCameraMode(CameraMode.TRACKING, 750L, zoom, 0.0, 0.0, null)
            }
            puck.cameraMode != CameraMode.NONE -> puck.cameraMode = CameraMode.NONE
        }
    }

    LaunchedEffect(map, contentPadding, navigating) {
        val ui = map?.uiSettings ?: return@LaunchedEffect
        with(density) {
            val start = (contentPadding.calculateStartPadding(layoutDirection) + 8.dp).roundToPx()
            val end = (contentPadding.calculateEndPadding(layoutDirection) + 8.dp).roundToPx()
            val top = (contentPadding.calculateTopPadding() + 8.dp).roundToPx()
            val bottom = (contentPadding.calculateBottomPadding() + 8.dp).roundToPx()
            ui.setLogoMargins(start, 0, 0, bottom)
            ui.setAttributionMargins(start + 88.dp.roundToPx(), 0, 0, bottom)
            // The turn banner sits across the top while navigating; the compass goes below it.
            ui.setCompassMargins(0, top + if (navigating) NAVIGATION_COMPASS_OFFSET.roundToPx() else 0, end, 0)
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun activateLocationPuck(
    context: Context,
    map: MapLibreMap,
    style: Style,
    onTrackingDismissed: () -> Unit,
) {
    val puck = map.locationComponent
    val firstActivation = !puck.isLocationComponentActivated
    puck.activateLocationComponent(
        LocationComponentActivationOptions.builder(context, style)
            // Fixes come from our own LocationManager GPS flow, never MapLibre's engine.
            .useDefaultLocationEngine(false)
            .locationComponentOptions(finPuckOptions(context))
            .build(),
    )
    // The fin is drawn side-on, so it never turns with the travel direction: NORMAL keeps it
    // upright on screen in every camera mode, navigation included.
    puck.renderMode = RenderMode.NORMAL
    // MapLibre lays the puck flat on the map; on the tilted navigation camera that would squash
    // the fin onto the road. Stand it up facing the screen instead.
    style.getLayer(LocationComponentConstants.FOREGROUND_LAYER)
        ?.setProperties(iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT))
    if (firstActivation) {
        puck.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
            override fun onCameraTrackingDismissed() = onTrackingDismissed()
            override fun onCameraTrackingChanged(currentMode: Int) = Unit
        })
    }
}

/** The user's position as a tiger shark fin in the water instead of MapLibre's blue dot. */
private fun finPuckOptions(context: Context): LocationComponentOptions =
    LocationComponentOptions.builder(context)
        .foregroundDrawable(R.drawable.location_fin)
        .gpsDrawable(R.drawable.location_fin)
        .foregroundDrawableStale(R.drawable.location_fin_stale)
        // No white ring behind the fin, and no shadow cast from one.
        .backgroundDrawable(R.drawable.location_none)
        .backgroundDrawableStale(R.drawable.location_none)
        .elevation(0f)
        .build()

// Only called with a real fix, which requires location permission.
@SuppressLint("MissingPermission")
private fun showFix(puck: LocationComponent, location: Location) {
    if (!puck.isLocationComponentEnabled) puck.isLocationComponentEnabled = true
    puck.forceLocationUpdate(location)
}

/** Drives the MapView through the host lifecycle and destroys it on dispose. */
@Composable
private fun MapViewLifecycle(mapView: MapView) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        var created = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> if (!created) {
                    mapView.onCreate(null)
                    created = true
                }
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            val state = lifecycle.currentState
            if (state.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (state.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            if (created) mapView.onDestroy()
        }
    }
}

private suspend fun MapView.awaitMap(): MapLibreMap = suspendCoroutine { continuation ->
    getMapAsync { continuation.resume(it) }
}
