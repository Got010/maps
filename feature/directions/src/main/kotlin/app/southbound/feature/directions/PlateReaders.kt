package app.southbound.feature.directions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.southbound.core.search.RouteExposure

/** The purple of the plate-reader dots on the map. */
private val PlateReaderPurple = Color(0xFF7B1FA2)

/**
 * "3 plate readers · 1 speed camera · 2 DOT cameras" on a route option: what the route is exposed to.
 * A part with none is left out, and so is the whole line when all are none.
 */
@Composable
internal fun RouteExposureLine(exposure: RouteExposure, modifier: Modifier = Modifier) {
    if (exposure.isEmpty) return
    val parts = buildList {
        if (exposure.plateReaders > 0) {
            add(pluralStringResource(R.plurals.directions_plate_readers, exposure.plateReaders, exposure.plateReaders))
        }
        if (exposure.speedCameras > 0) {
            add(pluralStringResource(R.plurals.directions_speed_cameras, exposure.speedCameras, exposure.speedCameras))
        }
        if (exposure.dotCameras > 0) {
            add(pluralStringResource(R.plurals.directions_dot_cameras, exposure.dotCameras, exposure.dotCameras))
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            Icons.Filled.Videocam,
            contentDescription = null,
            tint = if (exposure.plateReaders > 0) PlateReaderPurple else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Text(
            parts.joinToString(" \u00B7 "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Under the options when "Avoid plate readers" or "Avoid DOT cameras" is on but the best route still passes one. */
@Composable
internal fun PlateReaderAvoidNote(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(
            stringResource(R.string.directions_plate_readers_not_avoided),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
