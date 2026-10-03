package app.southbound.core.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.match
import org.maplibre.android.style.expressions.Expression.step
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
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/** Adds the route, pin and traffic layers to a freshly loaded style and fills them. */
internal object OverlayLayers {
    private const val ROUTES = "southbound-routes"
    private const val PINS = "southbound-pins"
    private const val SPEEDS = "southbound-speeds"
    const val INCIDENTS = "southbound-incidents"

    private const val ROUTE_BLUE = "#1A73E8"
    private const val ROUTE_BLUE_CASING = "#0D47A1"
    private const val ROUTE_ALT = "#9AA0A6"
    private const val ROUTE_ALT_CASING = "#5F6368"
    private const val PIN_RED = "#D93025"
    private const val DESTINATION = "#202124"

    // Google's traffic palette: stopped, slow, moving, free-flowing.
    private const val SPEED_STOPPED = "#A52714"
    private const val SPEED_SLOW = "#E8710A"
    private const val SPEED_MOVING = "#F9AB00"
    private const val SPEED_FREE = "#1E8E3E"

    private const val INCIDENT_CLOSURE = "#C5221F"
    private const val INCIDENT_ACCIDENT = "#E8710A"
    private const val INCIDENT_ROADWORK = "#F29900"
    private const val INCIDENT_OTHER = "#5F6368"

    fun install(style: Style) {
        if (style.getSource(ROUTES) != null) return
        style.addSource(GeoJsonSource(ROUTES, OverlayGeoJson.routes(emptyList())))
        style.addSource(GeoJsonSource(PINS, OverlayGeoJson.pins(null, null)))
        style.addSource(GeoJsonSource(SPEEDS, OverlayGeoJson.speeds(emptyList())))
        style.addSource(GeoJsonSource(INCIDENTS, OverlayGeoJson.incidents(emptyList())))

        // Live speeds colour the road; they sit under the routes.
        val speeds = LineLayer("$SPEEDS-line", SPEEDS).withProperties(
            lineColor(
                step(
                    get("speed"), literal(SPEED_STOPPED),
                    stop(10, literal(SPEED_SLOW)), stop(25, literal(SPEED_MOVING)), stop(40, literal(SPEED_FREE)),
                ),
            ),
            lineWidth(4f),
            lineOpacity(0.9f),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND),
        )
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
            style.addLayerBelow(speeds, firstLabel)
            style.addLayerBelow(casing, firstLabel)
            style.addLayerBelow(line, firstLabel)
        } else {
            style.addLayer(speeds)
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
        style.addLayer(
            CircleLayer(INCIDENTS, INCIDENTS).withProperties(
                circleRadius(match(get("closure"), literal(6f), stop(true, literal(8f)))),
                circleColor(
                    match(
                        get("kind"), literal(INCIDENT_OTHER),
                        stop("CLOSURE", literal(INCIDENT_CLOSURE)),
                        stop("ACCIDENT", literal(INCIDENT_ACCIDENT)),
                        stop("ROADWORK", literal(INCIDENT_ROADWORK)),
                    ),
                ),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(2f),
            ),
        )
    }

    fun update(style: Style, overlays: MapOverlays) {
        style.getSourceAs<GeoJsonSource>(ROUTES)?.setGeoJson(OverlayGeoJson.routes(overlays.routes))
        style.getSourceAs<GeoJsonSource>(PINS)?.setGeoJson(OverlayGeoJson.pins(overlays.pin, overlays.destination))
        style.getSourceAs<GeoJsonSource>(SPEEDS)?.setGeoJson(OverlayGeoJson.speeds(overlays.traffic.speeds))
        style.getSourceAs<GeoJsonSource>(INCIDENTS)?.setGeoJson(OverlayGeoJson.incidents(overlays.traffic.incidents))
    }
}
