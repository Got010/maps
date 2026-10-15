package app.southbound.feature.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.southbound.core.map.CameraPin

/** What a tapped plate-reader dot says: what it is, and who makes or runs it when that's mapped. */
@Composable
internal fun CameraCard(camera: CameraPin, onClose: () -> Unit, modifier: Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Videocam, contentDescription = null, tint = Color(0xFF7B1FA2), modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.explore_camera_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.explore_camera_close))
                }
            }
            Text(
                camera.name?.let { stringResource(R.string.explore_camera_by, it) } ?: stringResource(R.string.explore_camera_unknown),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                stringResource(R.string.explore_camera_source),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, end = 8.dp),
            )
        }
    }
}

/**
 * A DOT traffic camera pinned on a route option, when the traffic feed has no picture of it: what it is, its name
 * from the pack, and how to see its picture ([trafficOn]: the traffic feed is on, it just doesn't list this one).
 */
@Composable
internal fun DotCameraCard(name: String?, trafficOn: Boolean, onClose: () -> Unit, modifier: Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Videocam, contentDescription = null, tint = Color(0xFF00897B), modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.explore_dot_camera_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.explore_camera_close))
                }
            }
            if (name != null) {
                Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
            }
            Text(
                stringResource(if (trafficOn) R.string.explore_dot_camera_no_picture else R.string.explore_dot_camera_traffic_off),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, end = 8.dp),
            )
        }
    }
}
