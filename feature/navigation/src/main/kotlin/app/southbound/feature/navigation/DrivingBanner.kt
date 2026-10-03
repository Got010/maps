package app.southbound.feature.navigation

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.southbound.core.routing.TripClock
import com.stadiamaps.ferrostar.composeui.theme.InstructionRowTheme
import java.util.Date

// Phase 6: the bigger driving layout and the navigation extras, kept out of NavigationOverlay.kt.

/**
 * Ferrostar's instruction banner with larger type, so the distance and the
 * street can be read at a glance on a dashboard mount.
 */
object DrivingInstructionRowTheme : InstructionRowTheme {
    override val distanceTextStyle: TextStyle
        @Composable get() = MaterialTheme.typography.displaySmall.merge(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)

    override val instructionTextStyle: TextStyle
        @Composable get() = MaterialTheme.typography.headlineMedium.merge(color = MaterialTheme.colorScheme.onSurfaceVariant)

    override val iconTintColor: Color
        @Composable get() = MaterialTheme.colorScheme.onSurface

    override val backgroundColor: Color
        @Composable get() = MaterialTheme.colorScheme.surface
}

/** The road you're on, under the instruction banner. Nothing when the step has no name. */
@Composable
fun CurrentRoadName(name: String?, modifier: Modifier = Modifier) {
    if (name.isNullOrBlank()) return
    Surface(modifier, shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Text(
            name,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

/** "Arrive 3:42 PM" in the phone's own 12 or 24 hour format. */
@Composable
fun ArrivalTime(nowMs: Long, durationRemainingSeconds: Double, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val text = DateFormat.getTimeFormat(context).format(Date(TripClock.arrivalMillis(nowMs, durationRemainingSeconds)))
    Surface(modifier, shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.navigation_arrive, text), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}
