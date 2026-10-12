package app.southbound.feature.navigation

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.southbound.core.map.ArrivalSide
import app.southbound.core.routing.SpeedLimits
import app.southbound.core.voice.VoiceMode
import com.stadiamaps.ferrostar.composeui.theme.InstructionRowTheme
import com.stadiamaps.ferrostar.composeui.views.components.InstructionsView
import com.stadiamaps.ferrostar.core.NavigationState
import uniffi.ferrostar.TripState

// Phase 8: the landscape driving layout (the fallback for Android Auto). The same pieces as
// NavigationOverlay, arranged for a phone on its side: the turn, the road and the time left in a
// column on the left, the map on the right with the speed limit and voice button over it.

/** When the landscape layout applies and how wide its left column is. Pure, so it is unit tested. */
object DrivingLandscape {
    /** The left column's share of the screen width. */
    const val PANEL_FRACTION = 0.4f

    /** Narrower windows (split screen) keep the portrait layout: a 40% column would be too thin. */
    const val MIN_WIDTH_DP = 560

    fun isActive(landscape: Boolean, widthDp: Int): Boolean = landscape && widthDp >= MIN_WIDTH_DP

    fun panelWidthDp(widthDp: Int): Float = widthDp * PANEL_FRACTION
}

/** True while the window is on its side and wide enough for the driving column. Follows rotation. */
@Composable
fun rememberDrivingLandscape(): Boolean {
    val configuration = LocalConfiguration.current
    return DrivingLandscape.isActive(configuration.orientation == Configuration.ORIENTATION_LANDSCAPE, configuration.screenWidthDp)
}

/** Width of the left column; the map is inset by the same amount so nothing sits under it. */
@Composable
fun rememberDrivingPanelWidth(): Dp = DrivingLandscape.panelWidthDp(LocalConfiguration.current.screenWidthDp).dp

/** Bottom centre of the map area (right of the column), for a pill such as Re-center. */
@Composable
fun BoxScope.drivingMapBottomCenter(panelWidth: Dp, bottom: Dp = 16.dp): Modifier =
    Modifier
        .align(Alignment.BottomStart)
        .padding(start = panelWidth)
        .fillMaxWidth()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom))
        .padding(bottom = bottom)
        .wrapContentWidth(Alignment.CenterHorizontally)

/** Ferrostar's instruction banner, a size down from [DrivingInstructionRowTheme] to fit a 40% column. */
object LandscapeInstructionRowTheme : InstructionRowTheme {
    override val distanceTextStyle: TextStyle
        @Composable get() = MaterialTheme.typography.headlineLarge.merge(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)

    override val instructionTextStyle: TextStyle
        @Composable get() = MaterialTheme.typography.titleLarge.merge(color = MaterialTheme.colorScheme.onSurfaceVariant)

    override val iconTintColor: Color
        @Composable get() = MaterialTheme.colorScheme.onSurface

    override val backgroundColor: Color
        @Composable get() = MaterialTheme.colorScheme.surface
}

/**
 * NavigationOverlay's landscape variant (same parameters, same pieces). The left column holds the
 * banner, highway sign, road name and the faster-route offer at the top and the arrival time and
 * trip progress at the bottom; the speed limit, rerouting notice and voice button sit over the map
 * on the right. The caller insets the map by [rememberDrivingPanelWidth] so the column covers nothing.
 */
@Composable
internal fun NavigationLandscape(
    state: NavigationState,
    voiceMode: VoiceMode,
    onVoiceModeChange: (VoiceMode) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier,
    speedMph: Double?,
    onSkipStop: (() -> Unit)?,
    delaySeconds: Double,
    fasterRoute: FasterRouteOffer?,
    arrivalSide: ArrivalSide?,
    parkingNearby: Boolean,
    mutedUntilNextTurn: Boolean,
    onVoiceLongPress: () -> Unit,
    onPause: (() -> Unit)?,
    arrived: Boolean,
) {
    val panelWidth = rememberDrivingPanelWidth()
    val trip = state.tripState
    // NavigationOverlay's [arrived]: the arrival card, as on Ferrostar's own arrival.
    val arrivedNow = trip is TripState.Complete || (arrived && trip is TripState.Navigating)
    Box(modifier.fillMaxSize()) {
        // The column's background runs under the system bars; its content stays clear of them.
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(panelWidth)
                .background(MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                arrivedNow -> {
                    Box(Modifier.weight(1f))
                    ArrivedCard(onEnd, arrivalSide, parkingNearby, Modifier)
                }
                trip is TripState.Navigating -> {
                    // What doesn't fit on a short screen is cut off at the bottom (the road name goes
                    // first), never drawn over the arrival time below.
                    Column(Modifier.weight(1f).fillMaxWidth().clipToBounds(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        trip.visualInstruction?.let { instruction ->
                            InstructionsView(
                                instructions = instruction,
                                distanceToNextManeuver = trip.progress.distanceToNextManeuver,
                                remainingSteps = trip.remainingSteps,
                                theme = LandscapeInstructionRowTheme,
                            )
                            HighwaySign(instruction.primaryContent)
                            CurrentLanes(trip)
                        }
                        CurrentRoadName(trip.remainingSteps.firstOrNull()?.roadName)
                        fasterRoute?.let { FasterRouteBanner(it) }
                    }
                    // Skip stop above the bar, then the same minutes-left bar as portrait (one clock).
                    if (onSkipStop != null && trip.remainingWaypoints.size > 1) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            OutlinedButton(onClick = onSkipStop) { Text(stringResource(R.string.navigation_skip_stop)) }
                        }
                    }
                    EtaLine(trip.progress.durationRemaining, delaySeconds, trip.progress.distanceRemaining, onEnd, onPause)
                }
                else -> Unit
            }
        }

        if (trip is TripState.Navigating && !arrivedNow) {
            // Over the map: speed limit top-left (the attribution button owns the bottom-left corner),
            // voice button bottom-right, the rerouting notice top-centre (the compass is top-right).
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(start = panelWidth)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical))
                    .padding(8.dp),
            ) {
                val limit = remember(state) { SpeedLimits.currentMph(state) }
                if (limit != null) {
                    Box(Modifier.align(Alignment.TopStart)) {
                        SpeedLimitSign(limit, over = speedMph != null && speedMph > limit + SpeedLimits.TOLERANCE_MPH)
                    }
                }
                if (state.isCalculatingNewRoute) {
                    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, modifier = Modifier.align(Alignment.TopCenter)) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(2.dp))
                            Text(stringResource(R.string.navigation_rerouting))
                        }
                    }
                }
                Box(Modifier.align(Alignment.BottomEnd)) {
                    VoiceButton(voiceMode, onVoiceModeChange, mutedUntilNextTurn, onVoiceLongPress)
                }
            }
        }
    }
}
