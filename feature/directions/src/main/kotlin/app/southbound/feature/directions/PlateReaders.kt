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

/** The purple of the plate-reader dots on the map. */
private val PlateReaderPurple = Color(0xFF7B1FA2)

/** "No plate readers", "1 plate reader", "4 plate readers" on a route option. */
@Composable
internal fun PlateReaderCount(count: Int, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            Icons.Filled.Videocam,
            contentDescription = null,
            tint = if (count > 0) PlateReaderPurple else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Text(
            if (count == 0) {
                stringResource(R.string.directions_plate_readers_none)
            } else {
                pluralStringResource(R.plurals.directions_plate_readers, count, count)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Under the options when "Avoid plate readers" is on but the best route still passes one. */
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
