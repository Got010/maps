package app.southbound.feature.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.southbound.core.routing.SpeedLimits
import app.southbound.core.voice.VoiceMode
import com.stadiamaps.ferrostar.composeui.runtime.KeepScreenOnDisposableEffect
import com.stadiamaps.ferrostar.composeui.views.components.InstructionsView
import com.stadiamaps.ferrostar.composeui.views.components.TripProgressView
import com.stadiamaps.ferrostar.core.NavigationState
import uniffi.ferrostar.TripState

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
    nowMs: Long = System.currentTimeMillis(),
) {
    KeepScreenOnDisposableEffect()
    // Phase 6: light phone theme but after sunset: the overlay goes dark with the map.
    val colors = if (darkOverlay) dynamicDarkColorScheme(LocalContext.current) else MaterialTheme.colorScheme
    MaterialTheme(colorScheme = colors, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
    Box(modifier.fillMaxSize().safeDrawingPadding()) {
        when (val trip = state.tripState) {
            is TripState.Navigating -> {
                // Phase 6: bigger banner, with the road name under it.
                Column(Modifier.align(Alignment.TopCenter).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    trip.visualInstruction?.let { instruction ->
                        InstructionsView(
                            instructions = instruction,
                            distanceToNextManeuver = trip.progress.distanceToNextManeuver,
                            remainingSteps = trip.remainingSteps,
                            theme = DrivingInstructionRowTheme,
                        )
                    }
                    CurrentRoadName(trip.remainingSteps.firstOrNull()?.roadName)
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
                        VoiceButton(voiceMode, onVoiceModeChange)
                    }
                    // Phase 6: arrival clock time and Skip stop.
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        ArrivalTime(nowMs, trip.progress.durationRemaining)
                        if (onSkipStop != null && trip.remainingWaypoints.size > 1) {
                            OutlinedButton(onClick = onSkipStop) { Text(stringResource(R.string.navigation_skip_stop)) }
                        }
                    }
                    TripProgressView(progress = trip.progress, onTapExit = onEnd, modifier = Modifier.fillMaxWidth())
                }
            }
            is TripState.Complete -> ArrivedCard(onEnd, Modifier.align(Alignment.BottomCenter).padding(16.dp))
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

/** Cycles On -> Alerts only -> Muted, like the toggle in Settings. */
@Composable
private fun VoiceButton(mode: VoiceMode, onChange: (VoiceMode) -> Unit) {
    SmallFloatingActionButton(onClick = { onChange(mode.next()) }) {
        Icon(
            when (mode) {
                VoiceMode.ON -> Icons.Filled.VolumeUp
                VoiceMode.ALERTS_ONLY -> Icons.Filled.NotificationsActive
                VoiceMode.MUTED -> Icons.Filled.VolumeOff
            },
            contentDescription = stringResource(
                when (mode) {
                    VoiceMode.ON -> R.string.navigation_voice_on
                    VoiceMode.ALERTS_ONLY -> R.string.navigation_voice_alerts
                    VoiceMode.MUTED -> R.string.navigation_voice_muted
                },
            ),
        )
    }
}

@Composable
private fun ArrivedCard(onDone: () -> Unit, modifier: Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.navigation_arrived), style = MaterialTheme.typography.titleLarge)
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.navigation_done)) }
        }
    }
}
