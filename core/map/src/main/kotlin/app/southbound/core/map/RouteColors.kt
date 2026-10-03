package app.southbound.core.map

import java.util.Locale

/**
 * Route colours by rank (0 = fastest): the map line and the route card use
 * the same one. All three are blues, like the water the fin swims in, and they
 * get darker with rank, so the fastest (and, at first, the chosen) route is the
 * brightest line on the map. Blue alone separates them by lightness, so each
 * step has to be clearly lighter or darker than the next.
 *
 * Dark map (background #1c1f24, water #0f2c42, motorways #7a6337): sky, blue,
 * indigo-leaning royal blue. The last is the hardest to see against the water,
 * which is what [casingHex] is for on the chosen route.
 *
 * Light map (background #f5f3ef, water #9fc9ee, motorways #fbd373): deeper
 * shades, because the fastest route's sky blue would sink into the pale water.
 * Rank 0 is therefore a mid sky blue rather than the lightest possible.
 */
object RouteColors {
    private val DARK = intArrayOf(0xFF7DD3FC.toInt(), 0xFF3B82F6.toInt(), 0xFF3347E0.toInt())
    private val LIGHT = intArrayOf(0xFF0284C7.toInt(), 0xFF2563EB.toInt(), 0xFF1E3A8A.toInt())

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
