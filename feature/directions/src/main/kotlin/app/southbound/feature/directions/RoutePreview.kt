package app.southbound.feature.directions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.southbound.core.map.Eta
import app.southbound.core.map.RouteColors
import app.southbound.core.map.Units
import app.southbound.core.routing.FuelModel
import app.southbound.core.routing.FuelSettings
import app.southbound.core.routing.RouteSummary
import app.southbound.core.routing.TravelMode
import app.southbound.core.search.RouteExposure

/** Route preview's progress: computing, routes to choose from, or why there are none. */
sealed interface RoutePreviewState {
    data object Loading : RoutePreviewState
    /**
     * [delaySeconds] is what live traffic adds to each route's duration, in the same order; empty when unknown.
     * [fuelGallons] is the estimated gas each route burns, in the same order (traffic idling included);
     * empty when unknown, which hides the fuel line.
     * [exposure] is how many plate readers and speed cameras each route passes, same order; empty when
     * unknown (no search pack, or not driving). [cameraAvoidIncomplete]: "Avoid plate readers" is on, but the best
     * route still passes some.
     */
    data class Ready(
        val summaries: List<RouteSummary>,
        val selected: Int,
        val delaySeconds: List<Double> = emptyList(),
        val fuelGallons: List<Double> = emptyList(),
        val exposure: List<RouteExposure> = emptyList(),
        val cameraAvoidIncomplete: Boolean = false,
    ) : RoutePreviewState
    data class Failed(val reason: Reason) : RoutePreviewState

    enum class Reason { NO_ROUTE, NO_LOCATION }
}

/**
 * The sheet under the map while choosing a route: travel mode, the route
 * options (each marked with its line colour on the map), and Start.
 */
@Composable
fun RoutePreviewSheet(
    destinationName: String,
    mode: TravelMode,
    state: RoutePreviewState,
    onModeChange: (TravelMode) -> Unit,
    onSelect: (Int) -> Unit,
    onStart: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Fuel economy and gas price for the cost on each option. */
    fuel: FuelSettings = FuelSettings.DEFAULT,
    /** Shows a Transit chip that switches to the transit planner. */
    onTransit: (() -> Unit)? = null,
    /** A chosen start point instead of GPS; routes can be previewed but not navigated from it. */
    startName: String? = null,
    // Phase 6: options chip, stops editor and region warning, composed by the caller.
    afterModes: (@Composable () -> Unit)? = null,
    beforeStart: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.directions_to, destinationName),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.directions_close))
                }
            }
            ModeChips(mode, onModeChange, transitSelected = false, onTransit = onTransit)
            afterModes?.invoke() // Phase 6
            startName?.let {
                Text(
                    stringResource(R.string.directions_from, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp, end = 8.dp),
            ) {
                when (state) {
                    RoutePreviewState.Loading -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(vertical = 16.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.directions_finding))
                    }
                    is RoutePreviewState.Failed -> Text(
                        stringResource(
                            when (state.reason) {
                                RoutePreviewState.Reason.NO_ROUTE -> R.string.directions_no_route
                                RoutePreviewState.Reason.NO_LOCATION -> R.string.directions_no_location
                            },
                        ),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                    is RoutePreviewState.Ready -> {
                        // With only one route there is nothing to compare, so no efficiency badge.
                        val thriftiest = if (state.fuelGallons.size == state.summaries.size && state.summaries.size > 1) {
                            FuelModel.mostEfficient(state.fuelGallons)
                        } else {
                            null
                        }
                        state.summaries.forEachIndexed { i, summary ->
                            RouteOption(
                                summary,
                                delaySeconds = state.delaySeconds.getOrElse(i) { 0.0 },
                                selected = i == state.selected,
                                fastest = i == 0,
                                rank = i,
                                fuelCost = state.fuelGallons.getOrNull(i)?.let { FuelModel.cost(it, fuel.effectivePrice) },
                                leastGas = i == thriftiest,
                                onClick = { onSelect(i) },
                                exposure = state.exposure.getOrNull(i),
                            )
                        }
                        if (state.cameraAvoidIncomplete) PlateReaderAvoidNote()
                        beforeStart?.invoke() // Phase 6
                        Button(onClick = onStart, enabled = startName == null, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Navigation, contentDescription = null, Modifier.size(18.dp))
                            Text(stringResource(R.string.directions_start), Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Drive, walk, bike and (with a transit pack) transit. Shared by route
 * preview and the transit planner so switching feels like one sheet.
 */
@Composable
fun ModeChips(
    mode: TravelMode?,
    onModeChange: (TravelMode) -> Unit,
    transitSelected: Boolean,
    onTransit: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.padding(end = 8.dp).horizontalScroll(rememberScrollState()),
    ) {
        TravelMode.entries.forEach { option ->
            FilterChip(
                selected = !transitSelected && option == mode,
                onClick = { onModeChange(option) },
                label = { Text(stringResource(option.label())) },
                leadingIcon = { Icon(option.icon(), contentDescription = null, Modifier.size(18.dp)) },
            )
        }
        if (onTransit != null) {
            FilterChip(
                selected = transitSelected,
                onClick = onTransit,
                label = { Text(stringResource(R.string.mode_transit)) },
                leadingIcon = { Icon(Icons.Filled.DirectionsTransit, contentDescription = null, Modifier.size(18.dp)) },
            )
        }
    }
}

@Composable
private fun RouteOption(
    summary: RouteSummary,
    delaySeconds: Double,
    selected: Boolean,
    fastest: Boolean,
    rank: Int,
    fuelCost: Double?,
    leastGas: Boolean,
    onClick: () -> Unit,
    /** Plate readers and speed cameras on this route; null (or none of either) hides the line. */
    exposure: RouteExposure? = null,
) {
    // The route's line colour on the map. Outside a trip the map follows the system theme.
    val lineColor = Color(RouteColors.argb(rank, isSystemInDarkTheme()))
    val late = Eta.isLate(delaySeconds)
    val total = summary.durationSeconds + delaySeconds
    val red = MaterialTheme.colorScheme.error
    OutlinedCard(
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(end = 12.dp).size(12.dp).background(lineColor, CircleShape))
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    Text(
                        Units.duration(total),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            late -> red
                            fastest -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                    if (late) {
                        Text(
                            stringResource(R.string.directions_eta_plus, Eta.delayMinutes(delaySeconds)),
                            style = MaterialTheme.typography.labelLarge,
                            color = red,
                        )
                    }
                }
                // The clock time is read when the sheet is composed; it follows the route list as it refreshes.
                val arrival = remember(total) { Eta.clock(System.currentTimeMillis(), total) }
                Text(
                    stringResource(R.string.directions_arrive, arrival),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (late) red else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (delaySeconds >= 60) {
                    Text(
                        stringResource(R.string.directions_delay, Eta.delayMinutes(delaySeconds)) + " · " +
                            stringResource(R.string.directions_typical, Units.duration(summary.durationSeconds)),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (late) red else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                summary.via?.let {
                    Text(
                        stringResource(R.string.directions_via, it),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                exposure?.let { RouteExposureLine(it) }
                if (leastGas) LeastGasBadge(fastest)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Units.distance(summary.distanceMeters), style = MaterialTheme.typography.bodyMedium)
                fuelCost?.let { GasCostLine(it) }
            }
        }
    }
}

/** "≈ $4.10 gas" under the distance. */
@Composable
private fun GasCostLine(dollars: Double) {
    Text(
        stringResource(R.string.directions_gas_cost, FuelModel.formatCost(dollars)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Marks the option that burns the least gas; [alsoFastest] when it is the fastest too. */
@Composable
private fun LeastGasBadge(alsoFastest: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Text(
            stringResource(if (alsoFastest) R.string.directions_fastest_least_gas else R.string.directions_least_gas),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

fun TravelMode.label(): Int = when (this) {
    TravelMode.DRIVE -> R.string.mode_drive
    TravelMode.WALK -> R.string.mode_walk
    TravelMode.BIKE -> R.string.mode_bike
}

fun TravelMode.icon(): ImageVector = when (this) {
    TravelMode.DRIVE -> Icons.Filled.DirectionsCar
    TravelMode.WALK -> Icons.Filled.DirectionsWalk
    TravelMode.BIKE -> Icons.Filled.DirectionsBike
}
