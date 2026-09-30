package app.southbound.core.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
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
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private val NYC_CENTER = LatLng(40.7128, -74.0060)
private const val START_ZOOM = 11.0
private const val MIN_ZOOM = 8.0
private const val FOLLOW_ZOOM = 16.0

/**
 * The basemap, drawn from a local PMTiles pack, with the user's position.
 *
 * Location is pushed in from [location] rather than read by MapLibre, so the
 * app controls exactly which provider is used. While [following] is true the
 * camera tracks the puck; a pan by the user calls [onFollowingDismissed].
 */
@Composable
fun SouthboundMap(
    basemap: File,
    darkTheme: Boolean,
    location: Location?,
    following: Boolean,
    onFollowingDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val context = LocalContext.current
    val mapView = remember {
        val options = MapLibreMapOptions.createFromAttributes(context)
            .foregroundLoadColor(BasemapStyle.loadingColor(darkTheme))
            .camera(CameraPosition.Builder().target(NYC_CENTER).zoom(START_ZOOM).build())
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

    LaunchedEffect(map, darkTheme, basemap) {
        val loadedMap = map ?: return@LaunchedEffect
        style = null
        val json = withContext(Dispatchers.IO) { BasemapStyle.load(context, darkTheme, basemap) }
        loadedMap.setStyle(Style.Builder().fromJson(json)) { loaded ->
            activateLocationPuck(context, loadedMap, loaded) { currentOnFollowingDismissed() }
            style = loaded
        }
    }

    // Push each GPS fix to the puck. Enabling waits for the first fix, which
    // also means location permission has been granted.
    LaunchedEffect(style, location) {
        val loadedMap = map ?: return@LaunchedEffect
        if (style == null || location == null) return@LaunchedEffect
        showFix(loadedMap.locationComponent, location)
    }

    LaunchedEffect(style, following, location != null) {
        val loadedMap = map ?: return@LaunchedEffect
        val puck = loadedMap.locationComponent
        if (style == null || !puck.isLocationComponentEnabled) return@LaunchedEffect
        if (following) {
            val zoom = if (loadedMap.cameraPosition.zoom < FOLLOW_ZOOM - 2) FOLLOW_ZOOM else null
            puck.setCameraMode(CameraMode.TRACKING, 750L, zoom, null, null, null)
        } else if (puck.cameraMode != CameraMode.NONE) {
            puck.cameraMode = CameraMode.NONE
        }
    }

    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    LaunchedEffect(map, contentPadding) {
        val ui = map?.uiSettings ?: return@LaunchedEffect
        with(density) {
            val start = (contentPadding.calculateStartPadding(layoutDirection) + 8.dp).roundToPx()
            val end = (contentPadding.calculateEndPadding(layoutDirection) + 8.dp).roundToPx()
            val top = (contentPadding.calculateTopPadding() + 8.dp).roundToPx()
            val bottom = (contentPadding.calculateBottomPadding() + 8.dp).roundToPx()
            ui.setLogoMargins(start, 0, 0, bottom)
            ui.setAttributionMargins(start + 88.dp.roundToPx(), 0, 0, bottom)
            ui.setCompassMargins(0, top, end, 0)
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
            .build(),
    )
    puck.renderMode = RenderMode.NORMAL
    if (firstActivation) {
        puck.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
            override fun onCameraTrackingDismissed() = onTrackingDismissed()
            override fun onCameraTrackingChanged(currentMode: Int) = Unit
        })
    }
}

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
