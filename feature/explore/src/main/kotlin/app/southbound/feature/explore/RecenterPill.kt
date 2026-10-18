package app.southbound.feature.explore

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * "Re-center" while a trip is on: shown as soon as the map is panned, rotated or tilted away from the puck. Tapping
 * it returns to following (heading-up); that also happens by itself after 10 seconds without a touch. Outside a trip
 * the Re-center row of the Map options list does this job ([MapOptions]); the trip screen has no Map options
 * button, so it keeps this pill.
 */
@Composable
internal fun RecenterPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_my_location), contentDescription = null, Modifier.size(18.dp))
            Text(stringResource(R.string.explore_recenter), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
        }
    }
}
