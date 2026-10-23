package app.southbound.core.states

/**
 * Notices a state line crossed while driving, from the GPS fixes in order. Pure and in memory only: the current state
 * is held for as long as the object lives and nothing is written anywhere, so no crossing, time or place is kept.
 *
 * A crossing is reported when the fixes show a state other than the current one twice in a row, the two at least
 * [minSeparationMeters] apart and the second at least [minDepthMeters] inside the new state (the outlines are only
 * good to about 2 km, so a road running along a border does not count), so a fix that jitters over the line and back,
 * or a phone parked just across it, reports nothing. A fix with no accuracy, or one worse than
 * [MAX_ACCURACY_METERS], is not used at all. A fix in no state (more than about 2 km out to sea) is ignored: the last known state holds.
 */
class LiveState(
    private val outlines: StateOutlines,
    private val minSeparationMeters: Double = MIN_SEPARATION_METERS,
    private val minDepthMeters: Double = MIN_DEPTH_METERS,
) {
    /** The line crossed: [from] the state held before, [to] the one now. */
    data class Entered(val from: String, val to: String)

    /** The state the phone is in as far as the fixes say, or null before the first fix in a state. */
    var current: String? = null
        private set

    private var candidate: String? = null
    private var candidateLat = 0.0
    private var candidateLon = 0.0

    /**
     * The crossing this fix completes, if any, for a fix reported with [accuracyMeters] (null when the receiver gave
     * none). A fix without accuracy, or worse than [MAX_ACCURACY_METERS], is skipped: it neither sets nor changes
     * anything.
     */
    fun onFix(lat: Double, lon: Double, accuracyMeters: Double?): Entered? {
        if (accuracyMeters == null || !accuracyMeters.isFinite() || accuracyMeters < 0 || accuracyMeters > MAX_ACCURACY_METERS) return null
        return onFix(lat, lon)
    }

    /** The crossing this fix completes, if any (the fix is taken as accurate). */
    fun onFix(lat: Double, lon: Double): Entered? {
        val here = current
        val state = outlines.stateAt(lat, lon, here) ?: return null
        if (here == null) {
            current = state
            return null
        }
        if (state == here) {
            candidate = null
            return null
        }
        if (candidate != state) {
            candidate = state
            candidateLat = lat
            candidateLon = lon
            return null
        }
        if (Geo.distanceMeters(candidateLat, candidateLon, lat, lon) < minSeparationMeters) return null
        if (!outlines.isClearOf(here, lat, lon, minDepthMeters)) return null
        candidate = null
        current = state
        return Entered(here, state)
    }

    /** Forgets the current state (the next fix starts afresh). */
    fun reset() {
        current = null
        candidate = null
    }

    companion object {
        const val MIN_SEPARATION_METERS = 500.0

        /** How far inside the new state the confirming fix must be. */
        const val MIN_DEPTH_METERS = 500.0

        /** Fixes less accurate than this are not used. */
        const val MAX_ACCURACY_METERS = 100.0
    }
}
