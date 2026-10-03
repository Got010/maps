package app.southbound.core.routing

/** Phase 6: clock arithmetic for the navigation screen. */
object TripClock {
    /** When the trip ends, as epoch milliseconds, [durationRemainingSeconds] after [nowMs]. */
    fun arrivalMillis(nowMs: Long, durationRemainingSeconds: Double): Long =
        nowMs + (durationRemainingSeconds.coerceAtLeast(0.0) * 1000).toLong()
}
