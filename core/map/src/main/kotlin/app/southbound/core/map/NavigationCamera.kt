package app.southbound.core.map

/** Where the fin sits while navigating: this fraction of the map's height up from its bottom edge. */
internal const val NAVIGATION_PUCK_FROM_BOTTOM = 0.35

/**
 * Top camera padding, in pixels, that puts the tracked puck [fromBottom] of the way up a map
 * [heightPx] tall.
 *
 * MapLibre tracks the puck at the centre of the padded viewport, which spans
 * `top .. heightPx - bottom`. Its middle is `top + (heightPx - top - bottom) / 2`, and putting that
 * `fromBottom * heightPx` above the bottom edge gives `top = heightPx * (1 - 2 * fromBottom) + bottom`.
 * With the default 0.35 and no bottom padding that is 30% of the height. A taller [bottomPx] pushes
 * the puck up with it, so it is only used for space the puck must stay clear of.
 *
 * The result is clamped so the padded viewport keeps at least a quarter of the height.
 */
internal fun navigationTopPadding(
    heightPx: Int,
    bottomPx: Int = 0,
    fromBottom: Double = NAVIGATION_PUCK_FROM_BOTTOM,
): Double {
    if (heightPx <= 0) return 0.0
    val top = heightPx * (1.0 - 2.0 * fromBottom) + bottomPx
    return top.coerceIn(0.0, maxOf(0.0, heightPx * 0.75 - bottomPx))
}
