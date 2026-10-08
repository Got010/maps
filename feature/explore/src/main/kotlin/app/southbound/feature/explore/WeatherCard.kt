package app.southbound.feature.explore

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.southbound.core.map.MapPoint
import app.southbound.core.map.RadarPicture
import app.southbound.core.map.SunTimes
import app.southbound.core.map.WeatherArea
import app.southbound.core.map.WeatherIcons
import app.southbound.core.map.WeatherStationPin
import app.southbound.core.voice.SpokenUnits
import app.southbound.core.weather.AlertOnRoute
import app.southbound.core.weather.AlertSeverity
import app.southbound.core.weather.CurrentConditions
import app.southbound.core.weather.Radar
import app.southbound.core.weather.RadarImage
import app.southbound.core.weather.Sky
import app.southbound.core.weather.Ring
import app.southbound.core.weather.WeatherAlert

/** The colour an alert is drawn and labelled in, as on the map's weather layer. */
internal fun AlertSeverity.color(): Color = when (this) {
    AlertSeverity.EXTREME -> Color(0xFF7B1FA2)
    AlertSeverity.SEVERE -> Color(0xFFD93025)
    AlertSeverity.MODERATE -> Color(0xFFE8710A)
    AlertSeverity.MINOR -> Color(0xFFF9AB00)
    AlertSeverity.UNKNOWN -> Color(0xFF5F6368)
}

/** Only these are spoken on a trip; minor advisories are drawn and listed but not said. */
internal val AlertSeverity.spoken: Boolean
    get() = this == AlertSeverity.EXTREME || this == AlertSeverity.SEVERE || this == AlertSeverity.MODERATE

internal fun WeatherAlert.toArea(rings: List<Ring>) =
    WeatherArea(id, rings.map { ring -> ring.map { MapPoint(it.lat, it.lon) } }, severity.name)

/** Dark at the station now: the icon shows the moon instead of the sun. */
internal fun CurrentConditions.night(now: Long = System.currentTimeMillis()) = SunTimes.isNight(now, location.lat, location.lon)

internal fun CurrentConditions.toPin(now: Long) =
    WeatherStationPin(station, MapPoint(location.lat, location.lon), sky.name, night(now), tempF)

@androidx.annotation.StringRes
internal fun Sky.label(): Int = when (this) {
    Sky.THUNDER -> R.string.explore_sky_thunder
    Sky.SNOW -> R.string.explore_sky_snow
    Sky.SLEET -> R.string.explore_sky_sleet
    Sky.RAIN -> R.string.explore_sky_rain
    Sky.DRIZZLE -> R.string.explore_sky_drizzle
    Sky.FOG -> R.string.explore_sky_fog
    Sky.OVERCAST -> R.string.explore_sky_overcast
    Sky.MOSTLY_CLOUDY -> R.string.explore_sky_mostly_cloudy
    Sky.PARTLY_CLOUDY -> R.string.explore_sky_partly_cloudy
    Sky.CLEAR -> R.string.explore_sky_clear
}

/**
 * Current weather in one row, with the map's icon: "57° · Light rain", then where and when it was
 * observed ("Albany Intl · 12 min ago · wind 8 mph"). For a place card and a tapped station badge.
 */
@Composable
internal fun WeatherNowRow(now: CurrentConditions, modifier: Modifier = Modifier) {
    val night = now.night()
    val icon = remember(now.sky, night) { WeatherIcons.glyph(now.sky.name, night, 36).asImageBitmap() }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Image(icon, contentDescription = null, modifier = Modifier.size(36.dp))
        Column(Modifier.weight(1f)) {
            val sky = stringResource(now.sky.label())
            Text(
                listOfNotNull(now.tempF?.let { "$it\u00B0" }, sky).joinToString(" \u00B7 "),
                style = MaterialTheme.typography.titleMedium,
            )
            val ago = DateUtils.getRelativeTimeSpanString(now.observedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            Text(
                listOfNotNull(now.name, ago.toString(), now.windMph?.let { stringResource(R.string.explore_weather_wind, it) })
                    .joinToString(" \u00B7 "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A tapped station badge: its current weather, with Close. */
@Composable
internal fun WeatherStationCard(now: CurrentConditions, onClose: () -> Unit, modifier: Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            WeatherNowRow(now, Modifier.weight(1f))
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.explore_import_ok))
            }
        }
    }
}

internal fun RadarImage.toPicture() =
    RadarPicture(png, fetchedAt, Radar.BOUNDS[0], Radar.BOUNDS[1], Radar.BOUNDS[2], Radar.BOUNDS[3])

/**
 * A tapped weather alert: what it is, where and until when, then the National Weather
 * Service's own text (what, where, when, impacts) and what to do, scrolling when long.
 */
@Composable
internal fun WeatherAlertCard(alert: WeatherAlert, onClose: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    Surface(modifier, shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = alert.severity.color(), modifier = Modifier.size(22.dp))
                Text(
                    alert.event,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.explore_import_ok))
                }
            }
            Text(alert.areaDesc, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
            val flags = DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY
            val now = System.currentTimeMillis()
            val timing = listOfNotNull(
                alert.onset?.takeIf { it > now }?.let { stringResource(R.string.explore_weather_from, DateUtils.formatDateTime(context, it, flags)) },
                alert.ends?.let { stringResource(R.string.explore_weather_until, DateUtils.formatDateTime(context, it, flags)) },
                alert.senderName,
            )
            if (timing.isNotEmpty()) {
                Text(timing.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(
                Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                alert.description?.let { Text(it.unwrapped(), style = MaterialTheme.typography.bodySmall) }
                alert.instruction?.let {
                    Text(it.unwrapped(), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/**
 * Alerts the route passes through, one line each, under the route summary; a tap opens the
 * alert. Nothing when there are none.
 */
@Composable
internal fun WeatherOnRouteNote(alerts: List<AlertOnRoute>, onOpen: (WeatherAlert) -> Unit, modifier: Modifier = Modifier) {
    if (alerts.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (item in alerts.take(3)) {
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(item.alert) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = item.alert.severity.color(), modifier = Modifier.size(20.dp))
                Text(
                    if (item.metersAhead < 100) {
                        stringResource(R.string.explore_weather_on_route_now, item.alert.event)
                    } else {
                        stringResource(R.string.explore_weather_on_route_ahead, item.alert.event, SpokenUnits.distance(item.metersAhead))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (alerts.size > 3) {
            Text(
                stringResource(R.string.explore_weather_on_route_more, alerts.size - 3),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The NWS wraps its text at about 70 columns: rejoin lines inside a paragraph, keep the "* WHAT..." breaks. */
private fun String.unwrapped(): String =
    split(Regex("""\n\s*\n""")).joinToString("\n\n") { it.replace(Regex("""\s*\n\s*"""), " ").trim() }
