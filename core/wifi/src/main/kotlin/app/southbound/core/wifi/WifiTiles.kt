package app.southbound.core.wifi

import app.southbound.core.search.NearbyBox
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.tan

/**
 * The Web Mercator (slippy map) tile grid the pack's `cluster` table is cut on, for zooms [MIN_ZOOM] to
 * [MAX_ZOOM]. The maths is the pipeline's `tile_of` (pipeline/wifi/build_wifi.py), edge clamping included: a cell
 * is found by the same numbers on both sides.
 */
object WifiTiles {
    const val MIN_ZOOM = 3
    const val MAX_ZOOM = 9

    /** Web Mercator's northern and southern edge. */
    const val MAX_LATITUDE = 85.05112878

    /**
     * A cell of zoom `z` is `512 * 2^(mapZoom - z)` dp wide on the map (MapLibre's tiles are 512 dp), so cells
     * two zooms finer than the map's own are 128 to 256 dp: a handful of clusters on a phone's screen.
     */
    const val CELL_ZOOM_OFFSET = 2

    data class Tile(val x: Int, val y: Int)

    /** The tile of ([lat], [lon]) at [zoom]. */
    fun tileOf(lat: Double, lon: Double, zoom: Int): Tile {
        val n = 1 shl zoom
        val clamped = lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val x = floor((lon + 180.0) / 360.0 * n).toInt()
        val y = floor((1.0 - asinh(tan(Math.toRadians(clamped))) / PI) / 2.0 * n).toInt()
        return Tile(x.coerceIn(0, n - 1), y.coerceIn(0, n - 1))
    }

    /** The cluster zoom to draw at a map zoom of [mapZoom], within what the pack has. */
    fun clusterZoom(mapZoom: Double): Int =
        (floor(mapZoom).toInt() + CELL_ZOOM_OFFSET).coerceIn(MIN_ZOOM, MAX_ZOOM)

    /** Every tile of [zoom] the [box] touches: x from the west edge to the east, y from the north edge to the south. */
    fun range(box: NearbyBox, zoom: Int): Pair<IntRange, IntRange> {
        val northWest = tileOf(box.north, box.west, zoom)
        val southEast = tileOf(box.south, box.east, zoom)
        return northWest.x..southEast.x to northWest.y..southEast.y
    }
}
