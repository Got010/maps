package app.southbound.feature.transit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.southbound.core.transit.Departure
import app.southbound.core.transit.Prediction
import app.southbound.core.transit.TransitStop

/** A departure with what realtime says about it, if anything. */
data class LiveDeparture(val departure: Departure, val prediction: Prediction?) {
    /** The best known time: predicted if available. */
    val time: Int get() = prediction?.time ?: departure.time
}

data class StationBoard(val station: TransitStop, val departures: List<LiveDeparture>)

/**
 * Next departures from the stations near a place, a few per direction.
 * Times in the primary colour are live; the rest are scheduled.
 */
@Composable
fun TransitDepartures(boards: List<StationBoard>, nowSeconds: Int, modifier: Modifier = Modifier) {
    if (boards.isEmpty()) return
    Column(modifier.padding(top = 12.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text(stringResource(R.string.transit_departures), style = MaterialTheme.typography.titleSmall)
        for (board in boards) {
            Text(board.station.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            val shown = board.departures
                .filterNot { it.prediction?.canceled == true || it.prediction?.skipped == true }
                .sortedBy { it.time }
                .groupBy { it.departure.direction to it.departure.headsign }
                .values
                .flatMap { it.take(PER_DIRECTION) }
                .sortedBy { it.time }
                .take(MAX_ROWS)
            if (shown.isEmpty()) {
                Text(
                    stringResource(R.string.transit_no_departures),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (item in shown) DepartureRow(item, nowSeconds)
        }
    }
}

@Composable
private fun DepartureRow(item: LiveDeparture, nowSeconds: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LineBullet(item.departure.route)
        Text(
            item.departure.headsign ?: item.departure.route.label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val minutes = TransitFormat.minutesUntil(item.time, nowSeconds)
        Text(
            if (minutes <= 60) stringResource(R.string.transit_board_minutes, minutes) else TransitFormat.time(item.time),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (item.prediction?.time != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private const val PER_DIRECTION = 3
private const val MAX_ROWS = 8
