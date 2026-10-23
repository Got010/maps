package app.southbound.core.states

/**
 * Where a route passes from one state into another. [index] is the first route point inside [to]; [distanceMetersFromStart]
 * is how far along the route the border is (placed between that point and the one before it).
 */
data class Crossing(val index: Int, val from: String, val to: String, val distanceMetersFromStart: Double)

/**
 * The state lines a planned route crosses. Pure: the route in, the crossings out, nothing kept.
 */
object Crossings {
    /** A change of state counts once the route is this far inside the new state, so a road hugging a border doesn't flap. */
    const val MIN_DEPTH_METERS = 1_000.0

    private const val BORDER_STEPS = 14

    /**
     * The crossings along [polyline], in order. The state changes only when a point is in a new state and at least
     * [minDepthMeters] from the state the route was in (so at least that far inside the new one); a route that dips
     * over the line and back, or hugs it, has no crossing for the dip. Points in no state (out at sea) are skipped, so
     * the last known state holds.
     */
    fun crossingsAlong(
        polyline: List<StatePoint>,
        outlines: StateOutlines,
        minDepthMeters: Double = MIN_DEPTH_METERS,
    ): List<Crossing> {
        if (polyline.size < 2) return emptyList()
        val along = DoubleArray(polyline.size)
        for (i in 1 until polyline.size) {
            val a = polyline[i - 1]
            val b = polyline[i]
            along[i] = along[i - 1] + Geo.distanceMeters(a.lat, a.lon, b.lat, b.lon)
        }
        val out = ArrayList<Crossing>()
        var current: String? = null
        var pendingState: String? = null
        var pendingIndex = -1
        for (k in polyline.indices) {
            val p = polyline[k]
            val state = outlines.stateAt(p.lat, p.lon, current) ?: continue
            if (current == null) {
                current = state
                continue
            }
            if (state == current) {
                pendingState = null
                continue
            }
            if (pendingState != state) {
                pendingState = state
                pendingIndex = k
            }
            if (!outlines.isClearOf(current, p.lat, p.lon, minDepthMeters)) continue
            val before = polyline[pendingIndex - 1]
            val first = polyline[pendingIndex]
            val fraction = borderFraction(before, first, state, outlines)
            val distance = along[pendingIndex - 1] + fraction * (along[pendingIndex] - along[pendingIndex - 1])
            out += Crossing(pendingIndex, current, state, distance)
            current = state
            pendingState = null
        }
        return out
    }

    /** How far from [outside] to [inside] (0 to 1) the line enters [state], by bisection. */
    private fun borderFraction(outside: StatePoint, inside: StatePoint, state: String, outlines: StateOutlines): Double {
        var lo = 0.0
        var hi = 1.0
        repeat(BORDER_STEPS) {
            val mid = (lo + hi) / 2
            val lat = outside.lat + (inside.lat - outside.lat) * mid
            val lon = outside.lon + (inside.lon - outside.lon) * mid
            if (outlines.stateAt(lat, lon, state) == state) hi = mid else lo = mid
        }
        return hi
    }
}
