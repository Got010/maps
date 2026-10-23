package app.southbound.core.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.has
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.match
import org.maplibre.android.style.expressions.Expression.step
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.switchCase
import org.maplibre.android.style.expressions.Expression.toColor
import org.maplibre.android.style.expressions.Expression.toNumber
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconPitchAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import java.lang.ref.WeakReference

/** Adds the route, pin and traffic layers to a freshly loaded style and fills them. */
internal object OverlayLayers {
    private const val ROUTES = "southbound-routes"
    private const val PINS = "southbound-pins"
    private const val SPEEDS = "southbound-speeds"
    private const val SLOWDOWNS = "southbound-slowdowns"
    const val INCIDENTS = "southbound-incidents"

    // Route layers, bottom to top: transit walks, the chosen route's casing and line, then the
    // other alternatives as threads (the ones a tap can pick).
    const val ROUTE_WALK = "southbound-routes-walk"
    const val ROUTE_CASING = "southbound-routes-casing"
    const val ROUTE_LEADER = "southbound-routes-line"
    const val ROUTE_THREADS = "southbound-routes-threads"

    /** Plate readers from the search pack; drawn from [CAMERA_MIN_ZOOM], over routes and incidents. */
    const val CAMERAS = "southbound-cameras"
    private const val CAMERA_PURPLE = "#7B1FA2"
    private const val CAMERA_MIN_ZOOM = 11f

    /** Which way each plate reader faces, when mapped: a wedge from its dot, under it. */
    const val CAMERA_WEDGES = "southbound-camera-wedges"
    private const val CAMERA_WEDGE_ICON = "southbound-camera-wedge"

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
    // Navy, apart from the speed cameras' blue (#1A73E8) and the closure red.
    private const val INCIDENT_POLICE = "#3B5BDB"
    private const val INCIDENT_OTHER = "#5F6368"

    /** 511NY traffic cameras, with the traffic layer: a teal camera badge, grey when offline. */
    const val TRAFFIC_CAMERAS = "southbound-traffic-cameras"
    private const val TRAFFIC_CAMERA_ICON = "southbound-traffic-camera"
    private const val TRAFFIC_CAMERA_OFF_ICON = "southbound-traffic-camera-off"
    private const val TRAFFIC_CAMERA_MIN_ZOOM = 12f

    /** 511NY message signs, with the traffic layer: a small amber-on-black board. */
    const val MESSAGE_SIGNS = "southbound-message-signs"
    private const val MESSAGE_SIGN_ICON = "southbound-message-sign"
    private const val MESSAGE_SIGN_MIN_ZOOM = 11f

    /** Weather alert areas (tap for the alert), tinted by severity, under the routes; radar under them. */
    const val WEATHER_AREAS = "southbound-weather-areas"
    private const val WEATHER_OUTLINE = "southbound-weather-areas-outline"
    private const val RADAR = "southbound-radar"

    /** Each weather station's current conditions, a badge drawn in code (WeatherIcons), over everything. */
    const val WEATHER_STATIONS = "southbound-weather-stations"
    private const val WEATHER_EXTREME = "#7B1FA2"
    private const val WEATHER_SEVERE = "#D93025"
    private const val WEATHER_MODERATE = "#E8710A"
    private const val WEATHER_MINOR = "#F9AB00"
    private const val WEATHER_OTHER = "#5F6368"

    /**
     * Free Wi-Fi spots from the Wi-Fi pack: badges drawn in code (WifiIcons) from [WIFI_MIN_ZOOM]; below it the pack's
     * grid cells as circles with their counts. Over the routes, under the pins.
     */
    const val WIFI = "southbound-wifi"
    const val WIFI_CLUSTERS = "southbound-wifi-clusters"
    private const val WIFI_CLUSTER_COUNTS = "southbound-wifi-cluster-counts"
    private val WIFI_MIN_ZOOM = WifiPin.MIN_ZOOM.toFloat()

    /**
     * Road pack spots: badges drawn in code (RoadIcons, one per category) from [ROAD_MIN_ZOOM]; below it the pack's grid
     * cells as circles with their counts. Over the Wi-Fi layer, under the pins.
     */
    const val ROAD = "southbound-road"
    const val ROAD_CLUSTERS = "southbound-road-clusters"
    private const val ROAD_CLUSTER_COUNTS = "southbound-road-cluster-counts"
    private val ROAD_MIN_ZOOM = RoadPin.MIN_ZOOM.toFloat()

    // Alerts from the map data, pinned along the route while navigating.
    private const val ALERT_SPEED_CAMERA = "#1A73E8"
    private const val ALERT_ALPR_CAMERA = "#7B1FA2"
    private const val ALERT_SCHOOL_ZONE = "#F9AB00"

    fun install(style: Style) {
        if (style.getSource(ROUTES) != null) return
        style.addSource(GeoJsonSource(ROUTES, OverlayGeoJson.routes(emptyList(), darkTheme = false)))
        style.addSource(GeoJsonSource(PINS, OverlayGeoJson.pins(null, null)))
        style.addSource(GeoJsonSource(SPEEDS, OverlayGeoJson.speeds(emptyList())))
        style.addSource(GeoJsonSource(SLOWDOWNS, OverlayGeoJson.speeds(emptyList())))
        style.addSource(GeoJsonSource(INCIDENTS, OverlayGeoJson.incidents(emptyList())))
        style.addSource(GeoJsonSource(CAMERAS, OverlayGeoJson.cameras(emptyList())))
        style.addSource(GeoJsonSource(WEATHER_AREAS, OverlayGeoJson.weatherAreas(emptyList())))
        style.addSource(GeoJsonSource(WIFI, OverlayGeoJson.wifiPins(emptyList())))
        style.addSource(GeoJsonSource(WIFI_CLUSTERS, OverlayGeoJson.wifiClusters(emptyList())))
        style.addSource(GeoJsonSource(ROAD, OverlayGeoJson.roadPins(emptyList())))
        style.addSource(GeoJsonSource(ROAD_CLUSTERS, OverlayGeoJson.roadClusters(emptyList())))

        val speedColor = step(
            get("speed"), literal(SPEED_STOPPED),
            stop(10, literal(SPEED_SLOW)), stop(25, literal(SPEED_MOVING)), stop(40, literal(SPEED_FREE)),
        )
        // Live speeds colour the road; they sit under the routes.
        val speeds = LineLayer("$SPEEDS-line", SPEEDS).withProperties(
            lineColor(speedColor),
            lineWidth(4f),
            lineOpacity(0.9f),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND),
        )
        // "Leader and threads": each feature brings its own colour (see OverlayGeoJson.routes), and
        // each layer picks its role. Transit walks are grey dashes under everything else.
        val walk = LineLayer(ROUTE_WALK, ROUTES).withProperties(
            lineColor(toColor(get("color"))),
            lineWidth(4f),
            lineDasharray(arrayOf(1f, 1.5f)),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND),
        ).withFilter(eq(get("role"), literal("walk")))
        // The chosen route: a casing, then a thick line in its colour.
        val casing = LineLayer(ROUTE_CASING, ROUTES).withProperties(
            lineColor(toColor(get("casing"))),
            lineWidth(11f),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND),
        ).withFilter(eq(get("role"), literal("leader")))
        val line = LineLayer(ROUTE_LEADER, ROUTES).withProperties(
            lineColor(toColor(get("color"))),
            lineWidth(8f),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND),
        ).withFilter(eq(get("role"), literal("leader")))
        // Every other route: a thin thread over the leader, so a shared stretch reads as a stripe
        // down the middle of the chosen route.
        val threads = LineLayer(ROUTE_THREADS, ROUTES).withProperties(
            lineColor(toColor(get("color"))),
            lineWidth(2.4f),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND),
        ).withFilter(eq(get("role"), literal("thread")))
        // The selected route's slow stretches, drawn over its line (same bands as the speed layer).
        val slowdowns = LineLayer("$SLOWDOWNS-line", SLOWDOWNS).withProperties(
            lineColor(speedColor),
            lineWidth(6f),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND),
        )
        // Weather alert areas: a light tint and an outline in the alert's colour, under everything else.
        val weatherColor = match(
            get("severity"), literal(WEATHER_OTHER),
            stop("EXTREME", literal(WEATHER_EXTREME)),
            stop("SEVERE", literal(WEATHER_SEVERE)),
            stop("MODERATE", literal(WEATHER_MODERATE)),
            stop("MINOR", literal(WEATHER_MINOR)),
        )
        val weatherFill = FillLayer(WEATHER_AREAS, WEATHER_AREAS).withProperties(fillColor(weatherColor), fillOpacity(0.16f))
        val weatherOutline = LineLayer(WEATHER_OUTLINE, WEATHER_AREAS).withProperties(
            lineColor(weatherColor),
            lineWidth(1.5f),
            lineOpacity(0.8f),
        )
        // Routes sit under the basemap's labels so street names stay readable.
        val firstLabel = style.layers.firstOrNull { it is SymbolLayer }?.id
        if (firstLabel != null) {
            style.addLayerBelow(weatherFill, firstLabel)
            style.addLayerBelow(weatherOutline, firstLabel)
            style.addLayerBelow(speeds, firstLabel)
            style.addLayerBelow(walk, firstLabel)
            style.addLayerBelow(casing, firstLabel)
            style.addLayerBelow(line, firstLabel)
            style.addLayerBelow(slowdowns, firstLabel)
            style.addLayerBelow(threads, firstLabel)
        } else {
            style.addLayer(weatherFill)
            style.addLayer(weatherOutline)
            style.addLayer(speeds)
            style.addLayer(walk)
            style.addLayer(casing)
            style.addLayer(line)
            style.addLayer(slowdowns)
            style.addLayer(threads)
        }
        installWifi(style)
        installRoad(style)
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
                // match() only takes string or number labels; a boolean one invalidates the expression.
                circleRadius(switchCase(eq(get("closure"), literal(true)), literal(8f), literal(6f))),
                circleColor(
                    match(
                        get("kind"), literal(INCIDENT_OTHER),
                        stop("CLOSURE", literal(INCIDENT_CLOSURE)),
                        stop("ACCIDENT", literal(INCIDENT_ACCIDENT)),
                        stop("ROADWORK", literal(INCIDENT_ROADWORK)),
                        stop("POLICE", literal(INCIDENT_POLICE)),
                        stop("SPEED_CAMERA", literal(ALERT_SPEED_CAMERA)),
                        stop("ALPR_CAMERA", literal(ALERT_ALPR_CAMERA)),
                        stop("SCHOOL_ZONE", literal(ALERT_SCHOOL_ZONE)),
                        stop(IncidentPin.DOT_CAMERA, literal(TrafficIcons.CAMERA_TEAL)),
                    ),
                ),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(2f),
            ),
        )
        // Plate readers facing a mapped way: a see-through wedge aimed that way, turning with the map, under the dot.
        style.addImage(CAMERA_WEDGE_ICON, TrafficIcons.wedge(CAMERA_PURPLE))
        style.addLayer(
            SymbolLayer(CAMERA_WEDGES, CAMERAS).withProperties(
                iconImage(CAMERA_WEDGE_ICON),
                iconRotate(toNumber(get("direction"))),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                iconSize(interpolate(linear(), zoom(), stop(CAMERA_MIN_ZOOM, 0.45f), stop(15, 0.8f), stop(18, 1.1f))),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            ).withFilter(has("direction")).also { it.minZoom = CAMERA_MIN_ZOOM },
        )
        // Plate readers: always on, purple with a white ring, a little bigger as the map zooms in.
        style.addLayer(
            CircleLayer(CAMERAS, CAMERAS).withProperties(
                circleRadius(
                    interpolate(linear(), zoom(), stop(CAMERA_MIN_ZOOM, 3.5f), stop(15, 6f), stop(18, 8f)),
                ),
                circleColor(CAMERA_PURPLE),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(1.5f),
            ).also { it.minZoom = CAMERA_MIN_ZOOM },
        )
        installTrafficCamerasAndSigns(style)
        style.addSource(GeoJsonSource(WEATHER_STATIONS, OverlayGeoJson.weatherStations(emptyList())))
        // Badges that would overlap give way to each other (NYC has several stations close together), never to labels.
        style.addLayer(
            SymbolLayer(WEATHER_STATIONS, WEATHER_STATIONS).withProperties(
                iconImage(get("icon")),
                iconAllowOverlap(false),
                iconIgnorePlacement(true),
            ),
        )
    }

    /**
     * The Wi-Fi layer: the badges (every tier, and its ringed look for free-for-customers) are drawn once into the
     * style; a spot names its own in its "icon" property. Cells below [WIFI_MIN_ZOOM] are circles sized by their
     * count and coloured by their best tier ("color"), with the count over them. Nothing hides another (a hidden spot
     * or cell could not be tapped).
     */
    private fun installWifi(style: Style) {
        for (tier in WifiPin.TIERS) {
            for (ringed in listOf(false, true)) {
                style.addImage(WifiPin.imageName(tier, ringed), WifiIcons.badge(tier, ringed))
            }
        }
        style.addLayer(
            CircleLayer(WIFI_CLUSTERS, WIFI_CLUSTERS).withProperties(
                circleRadius(
                    step(
                        get("count"), literal(12f),
                        stop(10, literal(15f)), stop(100, literal(18f)), stop(1000, literal(22f)), stop(10000, literal(26f)),
                    ),
                ),
                circleColor(toColor(get("color"))),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(2f),
            ).also { it.maxZoom = WIFI_MIN_ZOOM },
        )
        // The basemap's own glyphs (styles/glyphs), so no font is fetched from anywhere.
        style.addLayer(
            SymbolLayer(WIFI_CLUSTER_COUNTS, WIFI_CLUSTERS).withProperties(
                textField(get("label")),
                textFont(arrayOf("NotoSans-Medium")),
                textSize(12f),
                textColor("#FFFFFF"),
                textAllowOverlap(true),
                textIgnorePlacement(true),
            ).also { it.maxZoom = WIFI_MIN_ZOOM },
        )
        style.addLayer(
            SymbolLayer(WIFI, WIFI).withProperties(
                iconImage(get("icon")),
                iconSize(interpolate(linear(), zoom(), stop(WIFI_MIN_ZOOM, 0.7f), stop(14, 0.9f), stop(18, 1.1f))),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            ).also { it.minZoom = WIFI_MIN_ZOOM },
        )
    }

    /**
     * The Road layer: the sixteen badges are drawn once into the style; a spot names its own in its "icon" property.
     * Cells below [ROAD_MIN_ZOOM] are circles sized by their count, with the count over them. Nothing hides another (a
     * hidden spot or cell could not be tapped).
     */
    private fun installRoad(style: Style) {
        for (category in RoadColors.KEYS) {
            style.addImage(RoadPin.imageName(category), RoadIcons.badge(category))
        }
        style.addLayer(
            CircleLayer(ROAD_CLUSTERS, ROAD_CLUSTERS).withProperties(
                circleRadius(
                    step(
                        get("count"), literal(12f),
                        stop(10, literal(15f)), stop(100, literal(18f)), stop(1000, literal(22f)), stop(10000, literal(26f)),
                    ),
                ),
                circleColor(RoadColors.CLUSTER),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(2f),
            ).also { it.maxZoom = ROAD_MIN_ZOOM },
        )
        // The basemap's own glyphs (styles/glyphs), so no font is fetched from anywhere.
        style.addLayer(
            SymbolLayer(ROAD_CLUSTER_COUNTS, ROAD_CLUSTERS).withProperties(
                textField(get("label")),
                textFont(arrayOf("NotoSans-Medium")),
                textSize(12f),
                textColor("#FFFFFF"),
                textAllowOverlap(true),
                textIgnorePlacement(true),
            ).also { it.maxZoom = ROAD_MIN_ZOOM },
        )
        style.addLayer(
            SymbolLayer(ROAD, ROAD).withProperties(
                iconImage(get("icon")),
                iconSize(interpolate(linear(), zoom(), stop(ROAD_MIN_ZOOM, 0.8f), stop(14, 1f), stop(18, 1.15f))),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            ).also { it.minZoom = ROAD_MIN_ZOOM },
        )
    }

    /** 511NY cameras and signs: icons drawn in code, over everything else, so a tap finds them. */
    private fun installTrafficCamerasAndSigns(style: Style) {
        style.addImage(TRAFFIC_CAMERA_ICON, TrafficIcons.camera(TrafficIcons.CAMERA_TEAL))
        style.addImage(TRAFFIC_CAMERA_OFF_ICON, TrafficIcons.camera(TrafficIcons.CAMERA_OFFLINE_GREY))
        style.addImage(MESSAGE_SIGN_ICON, TrafficIcons.sign())
        style.addSource(GeoJsonSource(MESSAGE_SIGNS, OverlayGeoJson.messageSigns(emptyList())))
        style.addSource(GeoJsonSource(TRAFFIC_CAMERAS, OverlayGeoJson.trafficCameras(emptyList())))
        // Every icon shows (no collision culling): a hidden camera couldn't be tapped.
        style.addLayer(
            SymbolLayer(MESSAGE_SIGNS, MESSAGE_SIGNS).withProperties(
                iconImage(MESSAGE_SIGN_ICON),
                iconSize(interpolate(linear(), zoom(), stop(MESSAGE_SIGN_MIN_ZOOM, 0.7f), stop(15, 1f), stop(18, 1.2f))),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            ).also { it.minZoom = MESSAGE_SIGN_MIN_ZOOM },
        )
        style.addLayer(
            SymbolLayer(TRAFFIC_CAMERAS, TRAFFIC_CAMERAS).withProperties(
                iconImage(switchCase(eq(get("offline"), literal(true)), literal(TRAFFIC_CAMERA_OFF_ICON), literal(TRAFFIC_CAMERA_ICON))),
                iconSize(interpolate(linear(), zoom(), stop(TRAFFIC_CAMERA_MIN_ZOOM, 0.75f), stop(15, 1f), stop(18, 1.2f))),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            ).also { it.minZoom = TRAFFIC_CAMERA_MIN_ZOOM },
        )
    }

    /** [darkTheme] is the loaded basemap's theme; it picks the route colours. The radar is [updateRadar]'s. */
    fun update(style: Style, overlays: MapOverlays, darkTheme: Boolean) {
        style.getSourceAs<GeoJsonSource>(ROUTES)?.setGeoJson(OverlayGeoJson.routes(overlays.routes, darkTheme))
        style.getSourceAs<GeoJsonSource>(PINS)?.setGeoJson(OverlayGeoJson.pins(overlays.pin, overlays.destination))
        style.getSourceAs<GeoJsonSource>(SPEEDS)?.setGeoJson(OverlayGeoJson.speeds(overlays.traffic.speeds))
        style.getSourceAs<GeoJsonSource>(SLOWDOWNS)?.setGeoJson(OverlayGeoJson.speeds(overlays.slowdowns))
        style.getSourceAs<GeoJsonSource>(INCIDENTS)?.setGeoJson(OverlayGeoJson.incidents(overlays.traffic.incidents))
        updateCameras(style, overlays.cameras)
        trafficCamerasSource.update(style, overlays.trafficCameras) { OverlayGeoJson.trafficCameras(it) }
        messageSignsSource.update(style, overlays.messageSigns) { OverlayGeoJson.messageSigns(it) }
        weatherAreasSource.update(style, overlays.weather.areas) { OverlayGeoJson.weatherAreas(it) }
        wifiPinsSource.update(style, overlays.wifi.pins) { OverlayGeoJson.wifiPins(it) }
        wifiClustersSource.update(style, overlays.wifi.clusters) { OverlayGeoJson.wifiClusters(it) }
        roadPinsSource.update(style, overlays.road.pins) { OverlayGeoJson.roadPins(it) }
        roadClustersSource.update(style, overlays.road.clusters) { OverlayGeoJson.roadClusters(it) }
        updateBadges(style, overlays.weather.stations)
        weatherStationsSource.update(style, overlays.weather.stations) { OverlayGeoJson.weatherStations(it) }
    }

    private val weatherAreasSource = ListSource<WeatherArea>(WEATHER_AREAS)
    private val weatherStationsSource = ListSource<WeatherStationPin>(WEATHER_STATIONS)
    private val wifiPinsSource = ListSource<WifiPin>(WIFI)
    private val wifiClustersSource = ListSource<WifiClusterPin>(WIFI_CLUSTERS)
    private val roadPinsSource = ListSource<RoadPin>(ROAD)
    private val roadClustersSource = ListSource<RoadClusterPin>(ROAD_CLUSTERS)

    // The station badge images on the style. Each look is drawn once; a look no longer shown is removed, or a long
    // session would keep a bitmap for every sky, day or night and temperature it has seen. The previous list's looks
    // stay one round more, since the map may still be drawing that list until the new one reaches it.
    private val badges = HashSet<String>()
    private var badgesShown: Set<String> = emptySet()
    private var badgesFor: List<WeatherStationPin>? = null
    private var badgesStyle: WeakReference<Style>? = null

    private fun updateBadges(style: Style, pins: List<WeatherStationPin>) {
        if (badgesStyle?.get() !== style) {
            // A new style starts with none of them.
            badges.clear()
            badgesShown = emptySet()
            badgesFor = null
            badgesStyle = WeakReference(style)
        }
        if (badgesFor === pins) return
        val wanted = pins.mapTo(HashSet()) { it.imageName }
        for (pin in pins) {
            if (badges.add(pin.imageName)) style.addImage(pin.imageName, WeatherIcons.badge(pin.sky, pin.night, pin.tempF))
        }
        val unused = badges.filter { it !in wanted && it !in badgesShown }
        for (name in unused) style.removeImage(name)
        badges.removeAll(unused.toSet())
        badgesShown = wanted
        badgesFor = pins
    }

    /** A radar picture decoded for the map, with the picture it came from (for its corners). */
    class RadarBitmap(val picture: RadarPicture, val bitmap: Bitmap)

    /**
     * Decodes the radar PNG, 2400 by 1360 pixels: a few hundred milliseconds, so it is called off the main thread.
     * Null when it isn't an image.
     */
    fun decodeRadar(radar: RadarPicture): RadarBitmap? =
        BitmapFactory.decodeByteArray(radar.png, 0, radar.png.size)?.let { RadarBitmap(radar, it) }

    // The decoded picture last handed to the map, and the style it went to. Weak, so a closed map lets both go.
    private var radarShown: WeakReference<RadarBitmap>? = null
    private var radarStyle: WeakReference<Style>? = null

    /** Shows [radar] under the alert areas, or removes the radar when it is null. On the main thread. */
    fun updateRadar(style: Style, radar: RadarBitmap?) {
        if (radar == null) {
            style.getLayer(RADAR)?.let { style.removeLayer(it) }
            style.getSource(RADAR)?.let { style.removeSource(it) }
            radarShown = null
            return
        }
        if (radarShown?.get() === radar && radarStyle?.get() === style && style.getSource(RADAR) != null) return
        val source = style.getSourceAs<ImageSource>(RADAR)
        if (source != null) {
            source.setImage(radar.bitmap)
        } else {
            val corners = radar.picture
            val quad = LatLngQuad(
                LatLng(corners.north, corners.west), LatLng(corners.north, corners.east),
                LatLng(corners.south, corners.east), LatLng(corners.south, corners.west),
            )
            style.addSource(ImageSource(RADAR, quad, radar.bitmap))
            // Under the alert areas, and so under the routes and labels.
            style.addLayerBelow(RasterLayer(RADAR, RADAR).withProperties(rasterOpacity(0.6f)), WEATHER_AREAS)
        }
        radarShown = WeakReference(radar)
        radarStyle = WeakReference(style)
    }

    // 511NY's cameras and signs are thousands of points that change every few minutes at most:
    // like the plate readers, they're only re-sent when the list itself changes.
    private val trafficCamerasSource = ListSource<TrafficCameraPin>(TRAFFIC_CAMERAS)
    private val messageSignsSource = ListSource<MessageSignPin>(MESSAGE_SIGNS)

    /** A GeoJSON source fed from a list, skipping updates while the same list (by identity) is shown on the same style. */
    private class ListSource<T>(private val id: String) {
        private var shown: List<T>? = null
        private var shownOn: WeakReference<Style>? = null

        fun update(style: Style, items: List<T>, geoJson: (List<T>) -> String) {
            if (shown === items && shownOn?.get() === style) return
            val source = style.getSourceAs<GeoJsonSource>(id) ?: return
            source.setGeoJson(geoJson(items))
            shown = items
            shownOn = WeakReference(style)
        }
    }

    // The camera list only changes when the search pack does, so its few thousand points
    // aren't re-sent on every overlay update. Weak, so a closed map's style can go.
    private var camerasShown: List<CameraPin>? = null
    private var camerasStyle: WeakReference<Style>? = null

    private fun updateCameras(style: Style, cameras: List<CameraPin>) {
        if (camerasShown === cameras && camerasStyle?.get() === style) return
        val source = style.getSourceAs<GeoJsonSource>(CAMERAS) ?: return
        source.setGeoJson(OverlayGeoJson.cameras(cameras))
        camerasShown = cameras
        camerasStyle = WeakReference(style)
    }
}
