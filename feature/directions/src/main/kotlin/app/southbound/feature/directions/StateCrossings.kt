package app.southbound.feature.directions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.southbound.core.map.Units
import app.southbound.core.states.Crossing
import app.southbound.core.states.StateNames

/**
 * "Crosses into Ohio in 29 mi" for each state line a route option crosses, in driving order; a tap opens that state's
 * card ([onOpen]). Nothing when the route stays in one state. The names come from a table, not the card data, so
 * drawing the line never reads a file.
 */
@Composable
internal fun StateCrossingLines(crossings: List<Crossing>, onOpen: (Crossing) -> Unit, modifier: Modifier = Modifier) {
    if (crossings.isEmpty()) return
    Column(modifier) {
        for (crossing in crossings) {
            val name = StateNames.of(crossing.to)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .clickable(onClickLabel = stringResource(R.string.directions_state_open, name)) { onOpen(crossing) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    Icons.Filled.Flag,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    stringResource(R.string.directions_state_crossing, name, Units.distance(crossing.distanceMetersFromStart)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
