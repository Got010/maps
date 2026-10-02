package app.southbound.core.routing

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
}
