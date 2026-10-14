package app.southbound.core.routing

import java.util.Locale

/**
 * Extra Valhalla request options, merged by Ferrostar into each request.
 *
 * - Two alternatives, so route preview can offer a choice.
 * - US units and English, so spoken and written instructions say "miles".
 */
internal object RouteOptions {
    const val ALTERNATES = 2

    /**
     * Costing options are not here: they depend on the user's preferences and
     * go in per request by [withPrefs]. (Ferrostar merges these options as
     * top-level keys, so a `costing_options` here would collide with that one.)
     */
    @Suppress("UNUSED_PARAMETER")
    fun json(mode: TravelMode): String =
        """{"alternates":$ALTERNATES,"units":"miles","language":"en-US"}"""

    // Phase 6: route preferences (tolls, highways, ferries, truck/RV profile); Phase 7: walking and cycling.
    /**
     * Applies the user's [prefs] to a request [body] Ferrostar built: adds
     * `costing_options` for the [mode] and, for a truck or RV, swaps the
     * costing to "truck". Driving and walking add nothing while their options
     * are at the defaults; cycling always adds its options (bike lanes and quiet
     * streets first, see [BikePrefs]). Only the options for [mode] are sent.
     * Like closures, this goes in per request, not into the adapter, so a
     * change in the options sheet takes effect on the next route and reroute.
     * [useDistance] (driving only) weighs distance into the choice, for the
     * preview's shorter way ([ShorterRoute]); [strictTolls] see [RoutePrefs.costingOptionsJson].
     */
    fun withPrefs(body: String, mode: TravelMode, prefs: RoutePrefs, useDistance: Double? = null, strictTolls: Boolean = true): String {
        val costing = when (mode) {
            TravelMode.DRIVE -> prefs.costing
            TravelMode.WALK -> TravelMode.WALK.valhallaCosting
            TravelMode.BIKE -> TravelMode.BIKE.valhallaCosting
        }
        val options = when (mode) {
            TravelMode.DRIVE -> prefs.costingOptionsJson(useDistance, strictTolls)
            TravelMode.WALK -> prefs.walk.costingOptionsJson()
            TravelMode.BIKE -> prefs.bikeCostingOptionsJson()
        } ?: return body
        val start = body.indexOf('{')
        require(start >= 0) { "Route request isn't a JSON object" }
        var out = body
        if (costing != mode.valhallaCosting) {
            val swapped = COSTING_AUTO.replace(out, "\"costing\":\"$costing\"")
            require(swapped != out) { "Route request has no auto costing to switch" }
            out = swapped
        }
        return out.substring(0, start + 1) + """"costing_options":{"$costing":$options},""" + out.substring(start + 1)
    }

    private val COSTING_AUTO = Regex(""""costing"\s*:\s*"auto"""")

    /** The request [body] without alternatives: only the best route ([ShorterRoute] wants one). */
    fun withoutAlternates(body: String): String = ALTERNATES_KEY.replace(body, """"alternates":0""")

    private val ALTERNATES_KEY = Regex(""""alternates"\s*:\s*\d+""")

    /**
     * Adds Valhalla `exclude_polygons` (rings of (lon, lat) pairs, e.g. around
     * road closures) to a request [body] Ferrostar built. The adapter's options
     * are fixed when it's made, so closures, which change by the minute, go in
     * here instead.
     */
    fun withExcludePolygons(body: String, polygons: List<List<Pair<Double, Double>>>): String {
        val rings = polygons.filter { it.size >= 4 }
        if (rings.isEmpty()) return body
        val start = body.indexOf('{')
        require(start >= 0) { "Route request isn't a JSON object" }
        val json = rings.joinToString(",", prefix = "[", postfix = "]") { ring ->
            ring.joinToString(",", prefix = "[", postfix = "]") { (lon, lat) -> String.format(Locale.ROOT, "[%.6f,%.6f]", lon, lat) }
        }
        return body.substring(0, start + 1) + """"exclude_polygons":$json,""" + body.substring(start + 1)
    }

    /**
     * Adds Valhalla `exclude_locations` to a request [body]: the road at each of [points] (a spot on a route beside a
     * plate reader, [CameraAvoidance]) is left out of the route, that stretch alone, not the streets around it as a
     * polygon would. Valhalla allows `service_limits.max_exclude_locations` of them ([OfflineRouter] raises it).
     */
    fun withExcludeLocations(body: String, points: List<CameraAvoidance.Spot>): String {
        if (points.isEmpty()) return body
        val start = body.indexOf('{')
        require(start >= 0) { "Route request isn't a JSON object" }
        val json = points.joinToString(",", prefix = "[", postfix = "]") { String.format(Locale.ROOT, """{"lat":%.6f,"lon":%.6f}""", it.lat, it.lon) }
        return body.substring(0, start + 1) + """"exclude_locations":$json,""" + body.substring(start + 1)
    }
}
