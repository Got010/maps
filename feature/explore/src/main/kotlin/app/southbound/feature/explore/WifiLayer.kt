package app.southbound.feature.explore

import app.southbound.core.map.MapPoint
import app.southbound.core.map.MapViewport
import app.southbound.core.map.WifiClusterPin
import app.southbound.core.map.WifiOverlay
import app.southbound.core.map.WifiPin
import app.southbound.core.search.NearbyBox
import app.southbound.core.wifi.WifiPack
import app.southbound.core.wifi.WifiTier

/** The free Wi-Fi layer's data for what the map shows. Everything reads the pack on the phone; nothing is fetched. */
internal object WifiLayer {

    /** Spots drawn at most in one view; the better tiers come first when a dense city has more. */
    const val SPOT_LIMIT = 2000

    /** The pack's grid cells below the zoom where single spots start, those spots from it. Blocking: call off the main thread. */
    fun overlay(pack: WifiPack, view: MapViewport): WifiOverlay {
        val box = NearbyBox(view.south, view.north, view.west, view.east)
        return if (view.zoom >= WifiPin.MIN_ZOOM) {
            WifiOverlay(pins = pack.inBox(box, WifiTier.entries.toSet(), SPOT_LIMIT).map {
                WifiPin(it.id, MapPoint(it.lat, it.lon), it.tier.code, it.fee.code)
            })
        } else {
            // A pack without cells draws nothing below the zoom of single spots.
            val zoom = pack.clusterZoomFor(view.zoom) ?: return WifiOverlay.NONE
            WifiOverlay(clusters = pack.clusters(zoom, box).map {
                WifiClusterPin("${it.zoom}/${it.x}/${it.y}", MapPoint(it.lat, it.lon), it.count, it.tierMin.code)
            })
        }
    }

    /** A tap on a grid cell zooms in two levels on it, from where the map is now. */
    fun zoomAfterClusterTap(currentZoom: Double?): Double = (currentZoom ?: WifiPin.MIN_ZOOM - 2) + 2
}
