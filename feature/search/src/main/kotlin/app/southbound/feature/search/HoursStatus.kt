package app.southbound.feature.search

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.southbound.core.search.OpeningHours
import java.time.LocalDateTime
import kotlinx.coroutines.delay

/**
 * The phone's local time, refreshed at the top of every minute so "closes
 * soon" and "Open" flip without the user doing anything. Call it once per
 * screen and pass the value down, not once per list row.
 */
@Composable
internal fun rememberNow(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(MINUTE_MS - System.currentTimeMillis() % MINUTE_MS)
            now = LocalDateTime.now()
        }
    }
    return now
}

/** The parsed hours for a raw OSM tag, parsed once per distinct tag. */
@Composable
internal fun rememberOpeningHours(raw: String?): OpeningHours? = remember(raw) { OpeningHours.parse(raw) }

/**
 * The place card's hours: "Open · closes 5 PM" in the theme's colours with the raw OSM tag
 * under it (tap to show all of it), or just the raw tag when it can't be understood.
 */
@Composable
internal fun HoursRow(raw: String, modifier: Modifier = Modifier) {
    val now = rememberNow()
    val hours = rememberOpeningHours(raw)
    val summary = hours?.summary(now)
    val text = summary?.let { hoursText(it) }
    if (summary == null || text == null) {
        DetailRow(Icons.Filled.Schedule, raw)
        return
    }
    var expanded by rememberSaveable(raw) { mutableStateOf(false) }
    Row(
        modifier.clickable { expanded = !expanded },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Filled.Schedule,
            contentDescription = null,
            Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = hoursColor(summary))
            Text(
                raw,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Open in the primary colour, closes-soon in tertiary, closed in error: all from the theme. */
@Composable
internal fun hoursColor(summary: OpeningHours.Summary): Color = when (summary) {
    OpeningHours.Summary.Open24Hours, is OpeningHours.Summary.OpenUntil -> MaterialTheme.colorScheme.primary
    is OpeningHours.Summary.ClosesSoon -> MaterialTheme.colorScheme.tertiary
    OpeningHours.Summary.Closed,
    is OpeningHours.Summary.ClosedOpensToday,
    is OpeningHours.Summary.ClosedOpensTomorrow,
    is OpeningHours.Summary.ClosedOpensLater,
    -> MaterialTheme.colorScheme.error
    OpeningHours.Summary.Unparsed -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** The line for [summary] in the phone's 12/24-hour style, or null for [OpeningHours.Summary.Unparsed]. */
@Composable
internal fun hoursText(summary: OpeningHours.Summary): String? {
    val use24h = DateFormat.is24HourFormat(LocalContext.current)
    val locale = LocalConfiguration.current.locales[0]
    fun time(t: java.time.LocalTime) = OpeningHours.formatTime(t, use24h, locale)
    return when (summary) {
        OpeningHours.Summary.Open24Hours -> stringResource(R.string.hours_open_24)
        is OpeningHours.Summary.OpenUntil -> stringResource(R.string.hours_open_until, time(summary.closesAt))
        is OpeningHours.Summary.ClosesSoon -> stringResource(R.string.hours_closes_soon, time(summary.closesAt))
        is OpeningHours.Summary.ClosedOpensToday -> stringResource(R.string.hours_closed_opens_today, time(summary.opensAt))
        is OpeningHours.Summary.ClosedOpensTomorrow -> stringResource(R.string.hours_closed_opens_tomorrow, time(summary.opensAt))
        is OpeningHours.Summary.ClosedOpensLater -> stringResource(
            R.string.hours_closed_opens_later,
            OpeningHours.formatDay(summary.day, locale),
            time(summary.opensAt),
        )
        OpeningHours.Summary.Closed -> stringResource(R.string.hours_closed)
        OpeningHours.Summary.Unparsed -> null
    }
}

/** " · Open" or " · Closed" for a result row, or null when the hours are missing or can't be understood. */
@Composable
internal fun rowOpenLabel(raw: String?, now: LocalDateTime): String? {
    val hours = rememberOpeningHours(raw)
    val status = remember(hours, now) { hours?.status(now) }
    return when (status) {
        is OpeningHours.Status.Open -> stringResource(R.string.search_row_open)
        is OpeningHours.Status.Closed -> stringResource(R.string.search_row_closed)
        OpeningHours.Status.Unknown, null -> null
    }
}

private const val MINUTE_MS = 60_000L
