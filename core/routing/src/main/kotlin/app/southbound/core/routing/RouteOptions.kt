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
