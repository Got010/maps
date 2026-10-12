package app.southbound.feature.transit

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.time.LocalTime
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

/** Formatting shared by the planner and departure boards. */
object TransitFormat {
    /**
     * "8:31 PM", or "20:31" when the phone is set to 24-hour time, in the phone's current language; times past
     * midnight wrap. Both settings are read on every call, so a change shows on the next draw.
     */
    @Composable
    fun time(seconds: Int): String =
        time(seconds, DateFormat.is24HourFormat(LocalContext.current), LocalConfiguration.current.locales[0])

    /**
     * [seconds] after midnight as a clock time in [locale]: its own short pattern when that already uses the
     * 12- or 24-hour clock [use24h] asks for, else "20:31" or "8:31 PM". Times past midnight wrap.
     */
    fun time(seconds: Int, use24h: Boolean, locale: Locale): String {
        val local = DateTimeFormatterBuilder.getLocalizedDateTimePattern(null, FormatStyle.SHORT, IsoChronology.INSTANCE, locale)
        // H and k are 24-hour clock letters, h and K 12-hour ones; quoted text is literal, not letters.
        val letters = local.replace(QUOTED, "")
        val pattern = when {
            ('H' in letters || 'k' in letters) == use24h -> local
            use24h -> "HH:mm"
            else -> "h:mm a"
        }
        return LocalTime.ofSecondOfDay(Math.floorMod(seconds, 86_400).toLong()).format(DateTimeFormatter.ofPattern(pattern, locale))
    }

    private val QUOTED = Regex("'[^']*'")

    /** Whole minutes, rounded up so "in 1 min" never shows a train that has left. */
    fun minutesUntil(seconds: Int, now: Int): Int = Math.floorDiv(seconds - now + 59, 60).coerceAtLeast(0)

    /**
     * [minutesUntil] for a countdown to a departure at [seconds]: 0 (leaving now) from the departure time until
     * a minute after it, and null once it is longer gone, so a train that has left never reads "in 0 min".
     */
    fun countdown(seconds: Int, now: Int): Int? = if (seconds - now <= -60) null else minutesUntil(seconds, now)

    fun minutes(seconds: Int): Int = (seconds + 30) / 60

    /** "D82233" -> a colour, or null when missing or malformed. */
    fun color(hex: String?): Color? {
        val clean = hex?.trim()?.removePrefix("#") ?: return null
        if (clean.length != 6) return null
        val rgb = clean.toLongOrNull(16) ?: return null
        return Color(0xFF000000 or rgb)
    }

    /** Black or white, whichever reads better on [background]. */
    fun contrastOn(background: Color): Color {
        val luminance = 0.299 * background.red + 0.587 * background.green + 0.114 * background.blue
        return if (luminance > 0.6) Color.Black else Color.White
    }
}
