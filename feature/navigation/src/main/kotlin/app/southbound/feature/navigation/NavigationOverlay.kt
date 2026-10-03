package app.southbound.feature.navigation

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.southbound.core.voice.VoiceMode
import com.stadiamaps.ferrostar.composeui.runtime.KeepScreenOnDisposableEffect
import com.stadiamaps.ferrostar.composeui.views.components.InstructionsView
import com.stadiamaps.ferrostar.composeui.views.components.TripProgressView
import com.stadiamaps.ferrostar.core.NavigationState
import uniffi.ferrostar.TripState

/**
 * Drawn over the map while navigating: the next turn at the top, time and
 * distance left at the bottom, the voice button and End. Keeps the screen on.
 */
@Composable
fun NavigationOverlay(
    state: NavigationState,
    voiceMode: VoiceMode,
    onVoiceModeChange: (VoiceMode) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    KeepScreenOnDisposableEffect()
    Box(modifier.fillMaxSize().safeDrawingPadding()) {
        when (val trip = state.tripState) {
            is TripState.Navigating -> {
                trip.visualInstruction?.let { instruction ->
                    InstructionsView(
                        instructions = instruction,
                        distanceToNextManeuver = trip.progress.distanceToNextManeuver,
                        remainingSteps = trip.remainingSteps,
                        modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                    )
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
                    VoiceButton(voiceMode, onVoiceModeChange)
                    TripProgressView(progress = trip.progress, onTapExit = onEnd, modifier = Modifier.fillMaxWidth())
                }
            }
            is TripState.Complete -> ArrivedCard(onEnd, Modifier.align(Alignment.BottomCenter).padding(16.dp))
            is TripState.Idle -> Unit
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
