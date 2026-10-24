package app.southbound.feature.explore

import app.southbound.core.map.MapPoint
import app.southbound.core.map.MapViewport
import app.southbound.core.map.RoadClusterPin
import app.southbound.core.map.RoadOverlay
import app.southbound.core.map.RoadPin
import app.southbound.core.road.RoadFilter
import app.southbound.core.road.RoadPack
import app.southbound.core.search.NearbyBox

/** The Road layer's data for what the map shows. Everything reads the pack on the phone; nothing is fetched. */
internal object RoadLayer {

    /** Spots drawn at most in one view; the rarer kinds (campsites before toilets) come first when a dense city has more. */
    const val SPOT_LIMIT = 2000

    /**
     * The pack's grid cells below the zoom where single spots are drawn (counted over the categories of [filter]), those
     * spots from it. A place the pack filed under two categories is one pin. Blocking: call off the main thread.
     */
    fun overlay(pack: RoadPack, view: MapViewport, filter: RoadFilter): RoadOverlay {
        if (filter.showsNothing) return RoadOverlay.NONE
        val box = NearbyBox(view.south, view.north, view.west, view.east)
        return if (view.zoom >= RoadPin.MIN_ZOOM) {
            RoadOverlay(pins = pack.inBox(box, filter.categories, SPOT_LIMIT).map { RoadPin(it.id, MapPoint(it.lat, it.lon), it.category.code) })
        } else {
            // A pack without cells draws nothing below the zoom of single spots.
            val zoom = pack.clusterZoomFor(view.zoom) ?: return RoadOverlay.NONE
            RoadOverlay(
                clusters = pack.clusters(zoom, box, filter.categories).map {
                    RoadClusterPin("${it.zoom}/${it.x}/${it.y}", MapPoint(it.lat, it.lon), it.count)
                },
            )
        }
    }

    /** A tap on a grid cell zooms in two levels on it, from where the map is now. */
    fun zoomAfterClusterTap(currentZoom: Double?): Double = (currentZoom ?: RoadPin.MIN_ZOOM - 2) + 2
}
