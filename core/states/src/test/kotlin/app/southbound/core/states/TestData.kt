package app.southbound.core.states

/** Synthetic routes and the shared data for the tests. Every coordinate is a public landmark or a made-up point on a line. */
internal object TestData {
    val outlines: StateOutlines by lazy { StateData.outlines() }
    val cards: StateCards by lazy { StateData.cards() }

    /** The Kansas / Colorado line in the outlines at 38.5 N (the outlines run it within a kilometre of the real one). */
    const val KS_CO_LON = -102.0454

    /** Metres per degree of longitude at 38.5 N. */
    const val M_PER_DEG_LON_385 = 87_022.0

    /** A line of points along latitude [lat] from [fromLon] to [toLon], one every [step] degrees. */
    fun along(lat: Double, fromLon: Double, toLon: Double, step: Double): List<StatePoint> {
        val n = Math.round(Math.abs(toLon - fromLon) / step).toInt()
        return (0..n).map { StatePoint(lat, fromLon + (toLon - fromLon) * it / n) }
    }

    /** The 50 state capitals and Washington, DC (public coordinates), by postal code. */
    val capitals: Map<String, Pair<Double, Double>> = mapOf(
        "AL" to (32.3792 to -86.3077), "AK" to (58.3019 to -134.4197), "AZ" to (33.4484 to -112.0740),
        "AR" to (34.7465 to -92.2896), "CA" to (38.5816 to -121.4944), "CO" to (39.7392 to -104.9903),
        "CT" to (41.7658 to -72.6734), "DE" to (39.1582 to -75.5244), "DC" to (38.8951 to -77.0364),
        "FL" to (30.4383 to -84.2807), "GA" to (33.7490 to -84.3880), "HI" to (21.3069 to -157.8583),
        "ID" to (43.6150 to -116.2023), "IL" to (39.7817 to -89.6501), "IN" to (39.7684 to -86.1581),
        "IA" to (41.5868 to -93.6250), "KS" to (39.0473 to -95.6752), "KY" to (38.2009 to -84.8733),
        "LA" to (30.4515 to -91.1871), "ME" to (44.3106 to -69.7795), "MD" to (38.9784 to -76.4922),
        "MA" to (42.3601 to -71.0589), "MI" to (42.7325 to -84.5555), "MN" to (44.9537 to -93.0900),
        "MS" to (32.2988 to -90.1848), "MO" to (38.5767 to -92.1735), "MT" to (46.5891 to -112.0391),
        "NE" to (40.8136 to -96.7026), "NV" to (39.1638 to -119.7674), "NH" to (43.2081 to -71.5376),
        "NJ" to (40.2171 to -74.7429), "NM" to (35.6870 to -105.9378), "NY" to (42.6526 to -73.7562),
        "NC" to (35.7796 to -78.6382), "ND" to (46.8083 to -100.7837), "OH" to (39.9612 to -82.9988),
        "OK" to (35.4676 to -97.5164), "OR" to (44.9429 to -123.0351), "PA" to (40.2732 to -76.8867),
        "RI" to (41.8240 to -71.4128), "SC" to (34.0007 to -81.0348), "SD" to (44.3683 to -100.3510),
        "TN" to (36.1627 to -86.7816), "TX" to (30.2672 to -97.7431), "UT" to (40.7608 to -111.8910),
        "VT" to (44.2601 to -72.5754), "VA" to (37.5407 to -77.4360), "WA" to (47.0379 to -122.9007),
        "WV" to (38.3498 to -81.6326), "WI" to (43.0731 to -89.4012), "WY" to (41.1400 to -104.8202),
    )
}
