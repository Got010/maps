package app.southbound.core.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.match
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/** Adds the route and pin layers to a freshly loaded style and fills them. */
internal object OverlayLayers {
    private const val ROUTES = "southbound-routes"
    private const val PINS = "southbound-pins"

    private const val ROUTE_BLUE = "#1A73E8"
    private const val ROUTE_BLUE_CASING = "#0D47A1"
    private const val ROUTE_ALT = "#9AA0A6"
    private const val ROUTE_ALT_CASING = "#5F6368"
    private const val PIN_RED = "#D93025"
    private const val DESTINATION = "#202124"

    fun install(style: Style) {
        if (style.getSource(ROUTES) != null) return
        style.addSource(GeoJsonSource(ROUTES, OverlayGeoJson.routes(emptyList())))
        style.addSource(GeoJsonSource(PINS, OverlayGeoJson.pins(null, null)))

        // The GeoJSON lists the selected route last, so it draws over alternatives.
        val casing = LineLayer("$ROUTES-casing", ROUTES).withProperties(
            lineColor(match(get("selected"), literal(ROUTE_ALT_CASING), stop(true, literal(ROUTE_BLUE_CASING)))),
            lineWidth(9f),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND),
        )
        val line = LineLayer("$ROUTES-line", ROUTES).withProperties(
            lineColor(match(get("selected"), literal(ROUTE_ALT), stop(true, literal(ROUTE_BLUE)))),
            lineWidth(6f),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND),
        )
        // Routes sit under the basemap's labels so street names stay readable.
        val firstLabel = style.layers.firstOrNull { it is SymbolLayer }?.id
        if (firstLabel != null) {
            style.addLayerBelow(casing, firstLabel)
            style.addLayerBelow(line, firstLabel)
        } else {
            style.addLayer(casing)
            style.addLayer(line)
        }
        style.addLayer(
            CircleLayer(PINS, PINS).withProperties(
                circleRadius(9f),
                circleColor(match(get("role"), literal(PIN_RED), stop("destination", literal(DESTINATION)))),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(3f),
            ),
        )
    }

    fun update(style: Style, overlays: MapOverlays) {
        style.getSourceAs<GeoJsonSource>(ROUTES)?.setGeoJson(OverlayGeoJson.routes(overlays.routes))
        style.getSourceAs<GeoJsonSource>(PINS)?.setGeoJson(OverlayGeoJson.pins(overlays.pin, overlays.destination))
    }
}
