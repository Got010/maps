package app.southbound.feature.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.southbound.core.map.ArrivalSide
import app.southbound.core.map.Eta
import app.southbound.core.map.Units
import app.southbound.core.routing.SpeedLimits
import app.southbound.core.voice.VoiceMode
import com.stadiamaps.ferrostar.composeui.runtime.KeepScreenOnDisposableEffect
import com.stadiamaps.ferrostar.composeui.views.components.InstructionsView
import com.stadiamaps.ferrostar.core.NavigationState
import uniffi.ferrostar.TripState
import uniffi.ferrostar.VisualInstructionContent

/**
 * Drawn over the map while navigating: the next turn at the top, time and
 * distance left at the bottom, the speed limit of the road, the voice button
 * and End. Keeps the screen on.
 */
@Composable
fun NavigationOverlay(
    state: NavigationState,
    voiceMode: VoiceMode,
    onVoiceModeChange: (VoiceMode) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    /** The user's speed from GPS, to colour the sign when they're over the limit. */
    speedMph: Double? = null,
    // Phase 6: skip the next stop (null hides the button) and the after-sunset dark overlay.
    onSkipStop: (() -> Unit)? = null,
    darkOverlay: Boolean = false,
    /** Time live traffic adds to what's left of the trip, in seconds; 0 when unknown or none. */
    delaySeconds: Double = 0.0,
    /** A faster route was found: minutes it saves, with the buttons that act on it. Null when there is none. */
    fasterRoute: FasterRouteOffer? = null,
    /** Which side of the street the destination is on, worked out from the route's end. */
    arrivalSide: ArrivalSide? = null,
    /** True when the search pack lists parking within a short walk of the destination. */
    parkingNearby: Boolean = false,
    /** The voice is quiet until the next turn (a long-press on the voice button); the button shows it. */
    mutedUntilNextTurn: Boolean = false,
    /** Long-press on the voice button: mute until the next turn, or lift that mute. */
    onVoiceLongPress: () -> Unit = {},
) {
    KeepScreenOnDisposableEffect()
    // Phase 6: light phone theme but after sunset: the overlay goes dark with the map.
    val colors = if (darkOverlay) dynamicDarkColorScheme(LocalContext.current) else MaterialTheme.colorScheme
    MaterialTheme(colorScheme = colors, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
    Box(modifier.fillMaxSize().safeDrawingPadding()) {
        when (val trip = state.tripState) {
            is TripState.Navigating -> {
                Column(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    trip.visualInstruction?.let { instruction ->
                        InstructionsView(
                            instructions = instruction,
                            distanceToNextManeuver = trip.progress.distanceToNextManeuver,
                            remainingSteps = trip.remainingSteps,
                            theme = DrivingInstructionRowTheme,
                        )
                        HighwaySign(instruction.primaryContent)
                    }
                    CurrentRoadName(trip.remainingSteps.firstOrNull()?.roadName)
                    fasterRoute?.let { FasterRouteBanner(it) }
                }
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    if (state.isCalculatingNewRoute) {
                        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(2.dp))
                                Text(stringResource(R.string.navigation_rerouting))
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        val limit = remember(state) { SpeedLimits.currentMph(state) }
                        if (limit != null) SpeedLimitSign(limit, over = speedMph != null && speedMph > limit + SpeedLimits.TOLERANCE_MPH)
                        Box(Modifier.weight(1f))
                        VoiceButton(voiceMode, onVoiceModeChange, mutedUntilNextTurn, onVoiceLongPress)
                    }
                    // Skip stop while more stops remain, then minutes left, distance, arrival and End.
                    if (onSkipStop != null && trip.remainingWaypoints.size > 1) {
                        OutlinedButton(onClick = onSkipStop) { Text(stringResource(R.string.navigation_skip_stop)) }
                    }
                    EtaLine(trip.progress.durationRemaining, delaySeconds, trip.progress.distanceRemaining, onEnd)
                }
            }
            is TripState.Complete -> ArrivedCard(onEnd, arrivalSide, parkingNearby, Modifier.align(Alignment.BottomCenter).padding(16.dp))
            is TripState.Idle -> Unit
        }
    }
    }
}

/** A US speed limit sign: white, black border, red when the user is over it. */
@Composable
private fun SpeedLimitSign(limitMph: Int, over: Boolean) {
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(containerColor = Color.White, contentColor = Color.Black),
        border = BorderStroke(2.dp, if (over) Color(0xFFD93025) else Color.Black),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.navigation_speed_limit), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            Text(
                "$limitMph",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = if (over) Color(0xFFD93025) else Color.Black,
            )
        }
    }
}

/**
 * Cycles On -> Alerts only -> Muted, like the toggle in Settings. A long
 * press mutes until the next turn (or lifts that), shown as a dimmed icon.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoiceButton(mode: VoiceMode, onChange: (VoiceMode) -> Unit, mutedUntilNextTurn: Boolean, onLongPress: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        modifier = Modifier.combinedClickable(onClick = { onChange(mode.next()) }, onLongClick = onLongPress),
    ) {
        Icon(
            when {
                mutedUntilNextTurn -> Icons.Filled.VolumeOff
                mode == VoiceMode.ON -> Icons.Filled.VolumeUp
                mode == VoiceMode.ALERTS_ONLY -> Icons.Filled.NotificationsActive
                else -> Icons.Filled.VolumeOff
            },
            contentDescription = stringResource(
                when {
                    mutedUntilNextTurn -> R.string.navigation_voice_until_turn
                    mode == VoiceMode.ON -> R.string.navigation_voice_on
                    mode == VoiceMode.ALERTS_ONLY -> R.string.navigation_voice_alerts
                    else -> R.string.navigation_voice_muted
                },
            ),
            modifier = Modifier.padding(12.dp).alpha(if (mutedUntilNextTurn) 0.5f else 1f),
        )
    }
}

@Composable
private fun ArrivedCard(onDone: () -> Unit, side: ArrivalSide?, parkingNearby: Boolean, modifier: Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.navigation_arrived), style = MaterialTheme.typography.titleLarge)
            side?.let { Text(stringResource(it.sentence()), style = MaterialTheme.typography.bodyLarge) }
            if (parkingNearby) {
                Text(stringResource(R.string.navigation_parking_nearby), style = MaterialTheme.typography.bodyLarge)
            }
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.navigation_done)) }
        }
    }
}

/**
 * "Your destination is on the left": the arrival banner's text, as a string
 * resource so the voice can speak the same sentence.
 */
@StringRes
fun ArrivalSide.sentence(): Int = when (this) {
    ArrivalSide.LEFT -> R.string.navigation_destination_left
    ArrivalSide.RIGHT -> R.string.navigation_destination_right
    ArrivalSide.AHEAD -> R.string.navigation_destination_ahead
}

/** A faster route and what to do with it. */
data class FasterRouteOffer(val savesMinutes: Int, val onAccept: () -> Unit, val onDismiss: () -> Unit)

@Composable
private fun FasterRouteBanner(offer: FasterRouteOffer) {
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(stringResource(R.string.navigation_faster_route, offer.savesMinutes), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = offer.onDismiss) { Text(stringResource(R.string.navigation_faster_dismiss)) }
                TextButton(onClick = offer.onAccept) { Text(stringResource(R.string.navigation_faster_accept)) }
            }
        }
    }
}

/**
 * The bottom bar: time left in minutes as the headline ("12 min", "1 hr 5 min"),
 * then distance left and the arrival clock in smaller type, and End. When live
 * traffic adds more than two minutes the headline turns red with "+X min" and
 * the trip's typical duration follows. This replaces Ferrostar's own progress
 * bar, which led with the arrival clock, so there is one clock on screen.
 * [distanceMeters] and [onEnd] are optional: without them the bar has no distance and no End button.
 */
@Composable
private fun EtaLine(remainingSeconds: Double, delaySeconds: Double, distanceMeters: Double? = null, onEnd: (() -> Unit)? = null) {
    val late = Eta.isLate(delaySeconds)
    val leftSeconds = remainingSeconds + delaySeconds
    // Recomputed whenever Ferrostar updates the remaining time (about once a second).
    val arrival = remember(remainingSeconds, delaySeconds) { Eta.clock(System.currentTimeMillis(), leftSeconds) }
    val color = if (late) Color(0xFFD93025) else MaterialTheme.colorScheme.onSurface
    val arriveText = stringResource(R.string.navigation_arrive_clock, arrival)
    val typicalText = if (delaySeconds >= 60) stringResource(R.string.navigation_typical, Units.duration(remainingSeconds)) else null
    val details = listOfNotNull(distanceMeters?.let { Units.distance(it) }, arriveText, typicalText).joinToString(" \u00B7 ")
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    Text(Units.duration(leftSeconds), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color)
                    if (late) {
                        Text(
                            stringResource(R.string.navigation_eta_plus, Eta.delayMinutes(delaySeconds)),
                            style = MaterialTheme.typography.labelLarge,
                            color = color,
                            modifier = Modifier.padding(bottom = 3.dp),
                        )
                    }
                }
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onEnd != null) {
                IconButton(onClick = onEnd) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.navigation_end))
                }
            }
        }
    }
}

/**
 * A highway guide sign: green, white text, the exit number in a badge. Shown
 * when Valhalla's banner carries exit numbers (Ferrostar passes them on).
 */
@Composable
private fun HighwaySign(content: VisualInstructionContent) {
    if (content.exitNumbers.isEmpty()) return
    Surface(
        color = Color(0xFF0B6B3A),
        contentColor = Color.White,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(2.dp, Color.White),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Color(0xFFFFD100), contentColor = Color.Black, shape = MaterialTheme.shapes.extraSmall) {
                Text(
                    stringResource(R.string.navigation_exit, content.exitNumbers.joinToString(", ")),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Text(content.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
    }
}
