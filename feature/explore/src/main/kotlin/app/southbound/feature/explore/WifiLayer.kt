package app.southbound.feature.explore

import app.southbound.core.map.MapPoint
import app.southbound.core.map.MapViewport
import app.southbound.core.map.WifiClusterPin
import app.southbound.core.map.WifiOverlay
import app.southbound.core.map.WifiPin
import app.southbound.core.search.NearbyBox
import app.southbound.core.wifi.WifiClusterView
import app.southbound.core.wifi.WifiClusters
import app.southbound.core.wifi.WifiFilter
import app.southbound.core.wifi.WifiPack
import app.southbound.core.wifi.WifiTiles

/** The free Wi-Fi layer's data for what the map shows. Everything reads the pack on the phone; nothing is fetched. */
internal object WifiLayer {

    /** Spots drawn at most in one view; the better tiers come first when a dense city has more. */
    const val SPOT_LIMIT = 2000

    /**
     * The pack's grid cells below the zoom where single spots start, those spots from it, cut by [filter]. Blocking:
     * call off the main thread.
     *
     * With the default filter this is as it always was: the pack's own `cluster` rows below zoom 10 and the best
     * [SPOT_LIMIT] spots from it. Under any other filter the tiers and the customers fee go into the pack's query, the
     * kinds are applied here to what it returns, and the circles are worked out from the spots (the pack's cells only
     * carry totals).
     */
    fun overlay(pack: WifiPack, view: MapViewport, filter: WifiFilter = WifiFilter.DEFAULT): WifiOverlay {
        if (filter.showsNothing) return WifiOverlay.NONE
        val box = NearbyBox(view.south, view.north, view.west, view.east)
        return if (view.zoom >= WifiPin.MIN_ZOOM) {
            // A kind is cut after the query, so it reads the whole view and keeps the best SPOT_LIMIT of what is left.
            val spots = if (filter.kinds == null) {
                pack.inBox(box, filter.tiers, SPOT_LIMIT, filter.customersOnly)
            } else {
                filter.applyKinds(pack.inBox(box, filter.tiers, WifiPack.SCAN_LIMIT, filter.customersOnly)).take(SPOT_LIMIT)
            }
            WifiOverlay(pins = spots.map { WifiPin(it.id, MapPoint(it.lat, it.lon), it.tier.code, it.fee.code) })
        } else {
            // A pack without cells draws nothing below the zoom of single spots.
            val zoom = pack.clusterZoomFor(view.zoom) ?: return WifiOverlay.NONE
            val cells = if (filter.isDefault) {
                WifiClusterView(pack.clusters(zoom, box), partial = false)
            } else {
                // The cells' whole tiles are read, so a cell cut by the screen's edge still counts all its spots; the
                // read is the lean one (no names, no sort), as it can be most of the pack.
                val points = pack.points(WifiTiles.cellsBox(box, zoom), filter.tiers, filter.customersOnly)
                WifiClusters.inView(points, filter, box, zoom)
            }
            WifiOverlay(clusters = cells.clusters.map {
                WifiClusterPin("${it.zoom}/${it.x}/${it.y}", MapPoint(it.lat, it.lon), it.count, it.tierMin.code)
            }, partial = cells.partial)
        }
    }

    /** A tap on a grid cell zooms in two levels on it, from where the map is now. */
    fun zoomAfterClusterTap(currentZoom: Double?): Double = (currentZoom ?: WifiPin.MIN_ZOOM - 2) + 2
}
