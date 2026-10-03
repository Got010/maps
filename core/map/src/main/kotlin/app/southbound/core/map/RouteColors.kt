package app.southbound.core.map

import java.util.Locale

/**
 * Route colours by rank (0 = fastest): the map line and the route card use
 * the same one. The light map gets deeper shades so the lines hold up on its
 * pale roads.
 */
object RouteColors {
    private val DARK = intArrayOf(0xFF22D3EE.toInt(), 0xFFF472B6.toInt(), 0xFFA78BFA.toInt())
    private val LIGHT = intArrayOf(0xFF0891B2.toInt(), 0xFFDB2777.toInt(), 0xFF7C3AED.toInt())

    /** Transit walk legs stay neutral grey. */
    const val WALK = "#9AA0A6"

    /** The route's colour as ARGB; past the third route the colours repeat. */
    fun argb(rank: Int, darkTheme: Boolean): Int {
        val palette = if (darkTheme) DARK else LIGHT
        return palette[Math.floorMod(rank, palette.size)]
    }

    /** The route's colour as "#RRGGBB". */
    fun hex(rank: Int, darkTheme: Boolean): String =
        String.format(Locale.ROOT, "#%06X", argb(rank, darkTheme) and 0xFFFFFF)

    /** The edge around the chosen route: near-black on the dark map, white on the light one. */
    fun casingHex(darkTheme: Boolean): String = if (darkTheme) "#0E1013" else "#FFFFFF"
}
