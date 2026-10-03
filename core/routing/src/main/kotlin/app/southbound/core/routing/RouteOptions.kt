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

    fun json(mode: TravelMode): String {
        val costing = when (mode) {
            // Bikes stick to bike lanes and quieter streets where they can.
            TravelMode.BIKE -> ""","costing_options":{"bicycle":{"bicycle_type":"hybrid","use_roads":0.3}}"""
            TravelMode.WALK, TravelMode.DRIVE -> ""
        }
        return """{"alternates":$ALTERNATES,"units":"miles","language":"en-US"$costing}"""
    }

    // Phase 6: route preferences (tolls, highways, ferries, truck/RV profile).
    /**
     * Applies the driver's [prefs] to a request [body] Ferrostar built: adds
     * `costing_options` and, for a truck or RV, swaps the costing to "truck".
     * Only driving has options; walking and cycling bodies come back as they
     * were. Like closures, this goes in per request, not into the adapter, so a
     * change in the options sheet takes effect on the next route and reroute.
     */
    fun withPrefs(body: String, mode: TravelMode, prefs: RoutePrefs): String {
        if (mode != TravelMode.DRIVE) return body
        val costing = prefs.costing
        val options = prefs.costingOptionsJson() ?: return body
        val start = body.indexOf('{')
        require(start >= 0) { "Route request isn't a JSON object" }
        var out = body
        if (costing != TravelMode.DRIVE.valhallaCosting) {
            val swapped = COSTING_AUTO.replace(out, "\"costing\":\"$costing\"")
            require(swapped != out) { "Route request has no auto costing to switch" }
            out = swapped
        }
        return out.substring(0, start + 1) + """"costing_options":{"$costing":$options},""" + out.substring(start + 1)
    }

    private val COSTING_AUTO = Regex(""""costing"\s*:\s*"auto"""")

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
}
