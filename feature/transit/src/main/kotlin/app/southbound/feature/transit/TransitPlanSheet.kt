package app.southbound.feature.transit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.southbound.core.routing.TravelMode
import app.southbound.core.transit.Journey
import app.southbound.core.transit.JourneyLeg
import app.southbound.core.transit.Prediction
import app.southbound.core.transit.ServiceAlert
import app.southbound.feature.directions.ModeChips

/** When to travel: now, leaving at a time, or arriving by one. */
data class TransitTime(val mode: Mode = Mode.NOW, val seconds: Int = 0) {
    enum class Mode { NOW, DEPART_AT, ARRIVE_BY }
}

/** Realtime layered over the schedule; empty when realtime is off or offline. */
data class TransitLive(
    /** Trip id -> prediction at the leg's boarding stop. */
    val boarding: Map<Int, Prediction> = emptyMap(),
    /** Route id -> active alerts. */
    val alerts: Map<Int, List<ServiceAlert>> = emptyMap(),
)

sealed interface TransitPlanState {
    data object Loading : TransitPlanState
    data class Ready(val journeys: List<Journey>, val live: TransitLive = TransitLive()) : TransitPlanState
    data class Failed(val reason: Reason) : TransitPlanState

    enum class Reason { NO_LOCATION, NO_TRIPS, OUT_OF_DATE }
}

/**
 * The transit planner under the map: when to go, the options (schedule
 * times, with live delays when realtime is on), and the selected option's
 * steps.
 */
@Composable
fun TransitPlanSheet(
    destinationName: String,
    onModeChange: (TravelMode) -> Unit,
    time: TransitTime,
    onTimeChange: (TransitTime) -> Unit,
    state: TransitPlanState,
    selected: Int,
    onSelect: (Int) -> Unit,
    onClose: () -> Unit,
    nowSeconds: Int,
    modifier: Modifier = Modifier,
    /** A chosen start point instead of GPS. */
    startName: String? = null,
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
                    stringResource(R.string.transit_to, destinationName),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.transit_close))
                }
            }
            ModeChips(mode = null, onModeChange = onModeChange, transitSelected = true, onTransit = {})
            startName?.let {
                Text(
                    stringResource(R.string.transit_from, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            TimeRow(time, onTimeChange, nowSeconds)
            when (state) {
                TransitPlanState.Loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(vertical = 16.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.transit_planning))
                }
                is TransitPlanState.Failed -> Text(
                    stringResource(
                        when (state.reason) {
                            TransitPlanState.Reason.NO_LOCATION -> R.string.transit_no_location
                            TransitPlanState.Reason.NO_TRIPS -> R.string.transit_no_trips
                            TransitPlanState.Reason.OUT_OF_DATE -> R.string.transit_out_of_date
                        },
                    ),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 16.dp, horizontal = 4.dp),
                )
                is TransitPlanState.Ready -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 380.dp).padding(top = 8.dp, end = 8.dp),
                ) {
                    itemsIndexed(state.journeys) { i, journey ->
                        JourneyCard(journey, state.live, selected = i == selected, nowSeconds = nowSeconds, onClick = { onSelect(i) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeRow(time: TransitTime, onTimeChange: (TransitTime) -> Unit, nowSeconds: Int) {
    var picking by remember { mutableStateOf<TransitTime.Mode?>(null) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 4.dp),
    ) {
        FilterChip(
            selected = time.mode == TransitTime.Mode.NOW,
            onClick = { onTimeChange(TransitTime()) },
            label = { Text(stringResource(R.string.transit_now)) },
        )
        FilterChip(
            selected = time.mode == TransitTime.Mode.DEPART_AT,
            onClick = { picking = TransitTime.Mode.DEPART_AT },
            label = {
                Text(
                    if (time.mode == TransitTime.Mode.DEPART_AT) {
                        stringResource(R.string.transit_depart_at_time, TransitFormat.time(time.seconds))
                    } else {
                        stringResource(R.string.transit_depart_at)
                    },
                )
            },
        )
        FilterChip(
            selected = time.mode == TransitTime.Mode.ARRIVE_BY,
            onClick = { picking = TransitTime.Mode.ARRIVE_BY },
            label = {
                Text(
                    if (time.mode == TransitTime.Mode.ARRIVE_BY) {
                        stringResource(R.string.transit_arrive_by_time, TransitFormat.time(time.seconds))
                    } else {
                        stringResource(R.string.transit_arrive_by)
                    },
                )
            },
        )
    }
    picking?.let { mode ->
        val initial = if (time.mode == mode) time.seconds else nowSeconds
        TimeDialog(
            initialSeconds = initial,
            onDismiss = { picking = null },
            onPicked = { seconds ->
                picking = null
                onTimeChange(TransitTime(mode, seconds))
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initialSeconds: Int, onDismiss: () -> Unit, onPicked: (Int) -> Unit) {
    val day = Math.floorMod(initialSeconds, 86_400)
    val state = rememberTimePickerState(initialHour = day / 3600, initialMinute = day % 3600 / 60)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPicked(state.hour * 3600 + state.minute * 60) }) {
                Text(stringResource(R.string.transit_time_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.transit_time_cancel)) } },
        text = { TimePicker(state) },
    )
}

@Composable
private fun JourneyCard(journey: Journey, live: TransitLive, selected: Boolean, nowSeconds: Int, onClick: () -> Unit) {
    OutlinedCard(
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${TransitFormat.time(journey.depart)} – ${TransitFormat.time(journey.arrive)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.transit_minutes, TransitFormat.minutes(journey.durationSeconds)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                val rides = journey.rides
                if (rides.isEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null, Modifier.size(20.dp))
                    Text(stringResource(R.string.transit_walk_only), style = MaterialTheme.typography.bodyMedium)
                }
                rides.forEachIndexed { i, ride ->
                    if (i > 0) Icon(Icons.Filled.ChevronRight, contentDescription = null, Modifier.size(16.dp))
                    LineBullet(ride.route)
                    if (live.alerts[ride.route.id].orEmpty().isNotEmpty()) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = stringResource(R.string.transit_alert),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            val first = journey.rides.firstOrNull()
            if (first != null) {
                val prediction = live.boarding[first.trip.id]
                Text(
                    statusLine(first, prediction, journey, nowSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (prediction != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) JourneySteps(journey, live)
        }
    }
}

@Composable
private fun statusLine(first: JourneyLeg.Ride, prediction: Prediction?, journey: Journey, nowSeconds: Int): String {
    val walk = TransitFormat.minutes(journey.walkSeconds)
    val leaves = prediction?.time ?: first.depart
    return when {
        prediction?.canceled == true -> stringResource(R.string.transit_canceled)
        prediction?.skipped == true -> stringResource(R.string.transit_skips_stop)
        else -> {
            val delay = prediction?.delayFrom(first.depart)?.let { TransitFormat.minutes(it) } ?: 0
            val base = stringResource(R.string.transit_leaves_from, TransitFormat.time(leaves), first.from.name)
            val late = if (delay > 0) " · " + stringResource(R.string.transit_late, delay) else ""
            val walking = if (walk > 0) " · " + stringResource(R.string.transit_walk_total, walk) else ""
            val soon = TransitFormat.minutesUntil(leaves, nowSeconds).takeIf { it <= 60 }
                ?.let { " · " + stringResource(R.string.transit_in_minutes, it) } ?: ""
            base + soon + late + walking
        }
    }
}

@Composable
private fun JourneySteps(journey: Journey, live: TransitLive) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (leg in journey.legs) {
            when (leg) {
                is JourneyLeg.Walk -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null, Modifier.size(20.dp))
                    Text(
                        stringResource(
                            R.string.transit_step_walk,
                            TransitFormat.minutes(leg.arrive - leg.depart).coerceAtLeast(1),
                            leg.to?.name ?: stringResource(R.string.transit_destination),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is JourneyLeg.Ride -> Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LineBullet(leg.route)
                        Text(
                            leg.headsign?.let { stringResource(R.string.transit_toward, it) } ?: leg.route.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        stringResource(
                            R.string.transit_step_ride,
                            TransitFormat.time(leg.depart),
                            leg.from.name,
                            TransitFormat.time(leg.arrive),
                            leg.to.name,
                            leg.stops.size - 1,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 32.dp),
                    )
                    live.alerts[leg.route.id].orEmpty().take(2).forEach { alert ->
                        alert.header?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 32.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
