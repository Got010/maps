package app.southbound.core.wifi

import app.southbound.core.search.NearbyBox
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.sinh
import kotlin.math.tan

/**
 * The Web Mercator (slippy map) tile grid the pack's `cluster` table is cut on, for zooms [MIN_ZOOM] to
 * [MAX_ZOOM]. The maths is the pipeline's `tile_of` (pipeline/wifi/build_wifi.py), edge clamping included: a cell
 * is found by the same numbers on both sides.
 */
object WifiTiles {
    /** The zooms a pack has when it doesn't say otherwise (the first builds: 3 to 9); a pack's own come from [WifiPack.clusterZooms]. */
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

    /**
     * The zooms a pack's `cluster` table has from `SELECT MIN(zoom), MAX(zoom)`: null for an empty table (both are
     * NULL) or nonsense. Capped so that `1 shl zoom` stays an Int; no pack has cells anywhere near.
     */
    fun zoomsOf(min: Long?, max: Long?): IntRange? {
        if (min == null || max == null) return null
        val first = min.coerceAtLeast(0).toInt()
        val last = max.coerceAtMost(MAX_CELL_ZOOM.toLong()).toInt()
        return if (first <= last) first..last else null
    }

    private const val MAX_CELL_ZOOM = 24

    /**
     * The cluster zoom to draw at a map zoom of [mapZoom]: [CELL_ZOOM_OFFSET] finer than the map, within the zooms
     * the pack has ([zooms]). A pack cut to zoom 9 stops there, one cut to zoom 11 gives finer circles up to map
     * zoom 10.
     */
    fun clusterZoom(mapZoom: Double, zooms: IntRange = MIN_ZOOM..MAX_ZOOM): Int =
        (floor(mapZoom).toInt() + CELL_ZOOM_OFFSET).coerceIn(zooms.first, zooms.last)

    /** Every tile of [zoom] the [box] touches: x from the west edge to the east, y from the north edge to the south. */
    fun range(box: NearbyBox, zoom: Int): Pair<IntRange, IntRange> {
        val northWest = tileOf(box.north, box.west, zoom)
        val southEast = tileOf(box.south, box.east, zoom)
        return northWest.x..southEast.x to northWest.y..southEast.y
    }

    /** Degrees (about a centimetre) [cellsBox] reaches past the tiles' edges, so rounding never leaves a spot out. */
    private const val EDGE_MARGIN = 1e-7

    /**
     * The box that covers every tile of [zoom] the [box] touches, so the spots read for it fill the edge
     * cells whole (the pack's own cells count every spot of a cell, however little of it is on screen). The edges
     * are the tiles' own (a hair wider), with the north and south clamped at [MAX_LATITUDE] like [tileOf].
     */
    fun cellsBox(box: NearbyBox, zoom: Int): NearbyBox {
        val (xs, ys) = range(box, zoom)
        val n = (1 shl zoom).toDouble()
        fun lat(row: Int) = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * row / n))))
        return NearbyBox(
            south = lat(ys.last + 1).coerceAtLeast(-MAX_LATITUDE) - EDGE_MARGIN,
            north = lat(ys.first).coerceAtMost(MAX_LATITUDE) + EDGE_MARGIN,
            west = xs.first / n * 360.0 - 180.0 - EDGE_MARGIN,
            east = (xs.last + 1) / n * 360.0 - 180.0 + EDGE_MARGIN,
        )
    }
}
