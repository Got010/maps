package app.southbound.feature.search

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TripOrigin
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.southbound.core.map.Units
import app.southbound.core.search.Place

/**
 * The card for a picked place: what and where it is, its hours and contacts
 * from OSM, and a Directions button. Calling or opening the website hands
 * off to the phone's dialer or browser; Southbound itself sends nothing.
 */
@Composable
fun PlaceCard(
    place: Place,
    distanceMeters: Double?,
    onDirections: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Use this place as the start of directions instead of GPS. */
    onStartHere: (() -> Unit)? = null,
    /** Opens the save-to-list dialog; [saved] fills the bookmark when the place is in a list. */
    onSave: (() -> Unit)? = null,
    saved: Boolean = false,
    /** More rows under the actions, such as transit departures nearby. */
    extra: (@Composable () -> Unit)? = null,
    // Phase 6: while planning a trip, add this place as a stop before the destination.
    onAddStop: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    Text(place.name, style = MaterialTheme.typography.titleLarge)
                    val subtitle = listOfNotNull(place.categoryLabel, distanceMeters?.let(Units::distance))
                        .joinToString(" · ")
                    if (subtitle.isNotEmpty()) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.place_close))
                }
            }
            Column(Modifier.padding(end = 12.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                place.address?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (place.approximate) {
                    Text(
                        stringResource(R.string.search_approximate),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                place.openingHours?.let { DetailRow(Icons.Filled.Schedule, it) }
                place.cuisine?.let { Text(it.replace(';', ',').replace('_', ' '), style = MaterialTheme.typography.bodySmall) }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 12.dp, end = 12.dp).horizontalScroll(rememberScrollState()),
            ) {
                if (onDirections != null) {
                    Button(onClick = onDirections) {
                        Icon(Icons.Filled.Directions, contentDescription = null, Modifier.size(18.dp))
                        Text(stringResource(R.string.place_directions), Modifier.padding(start = 8.dp))
                    }
                }
                // Phase 6: Add stop.
                onAddStop?.let {
                    Button(onClick = it) {
                        Icon(Icons.Filled.AddLocationAlt, contentDescription = null, Modifier.size(18.dp))
                        Text(stringResource(R.string.place_add_stop), Modifier.padding(start = 8.dp))
                    }
                }
                onSave?.let {
                    TextButton(onClick = it) {
                        Icon(if (saved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, contentDescription = null, Modifier.size(18.dp))
                        Text(stringResource(if (saved) R.string.place_saved else R.string.place_save), Modifier.padding(start = 6.dp))
                    }
                }
                onStartHere?.let {
                    TextButton(onClick = it) {
                        Icon(Icons.Filled.TripOrigin, contentDescription = null, Modifier.size(18.dp))
                        Text(stringResource(R.string.place_start_here), Modifier.padding(start = 6.dp))
                    }
                }
                place.phone?.let { phone ->
                    TextButton(onClick = { context.open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) }) {
                        Icon(Icons.Filled.Phone, contentDescription = null, Modifier.size(18.dp))
                        Text(stringResource(R.string.place_call), Modifier.padding(start = 6.dp))
                    }
                }
                place.website?.let { site ->
                    TextButton(onClick = { context.open(Intent(Intent.ACTION_VIEW, Uri.parse(site.withScheme()))) }) {
                        Icon(Icons.Filled.Language, contentDescription = null, Modifier.size(18.dp))
                        Text(stringResource(R.string.place_website), Modifier.padding(start = 6.dp))
                    }
                }
            }
            if (onDirections == null) {
                Text(
                    stringResource(R.string.place_no_routing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, end = 12.dp),
                )
            }
            extra?.invoke()
        }
    }
}

@Composable
private fun DetailRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun String.withScheme(): String = if (startsWith("http://") || startsWith("https://")) this else "https://$this"

private fun Context.open(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No dialer or browser installed; nothing to do.
    }
}
