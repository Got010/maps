package app.southbound.feature.transit

import androidx.compose.ui.graphics.Color
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Formatting shared by the planner and departure boards. */
object TransitFormat {
    private val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    /** "8:31 AM" for seconds after midnight; times past midnight wrap. */
    fun time(seconds: Int): String = LocalTime.ofSecondOfDay(Math.floorMod(seconds, 86_400).toLong()).format(clock)

    /** Whole minutes, rounded up so "in 1 min" never shows a train that has left. */
    fun minutesUntil(seconds: Int, now: Int): Int = Math.floorDiv(seconds - now + 59, 60).coerceAtLeast(0)

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
