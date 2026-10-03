package app.southbound.core.routing

/** Why a speed warning fires; the app speaks or beeps the same either way. */
enum class SpeedWarningReason {
    /** First time over the limit on this trip. */
    FIRST,

    /** The posted limit changed (a new road section) and you're still over it. */
    LIMIT_CHANGED,

    /** You were at or under the limit for a while, then went over again. */
    RESUMED,

    /** Much further over than when last warned. Fires once per stretch. */
    ESCALATION,
}

data class SpeedWarning(val limitMph: Int, val overByMph: Double, val reason: SpeedWarningReason)

/**
 * Decides when to warn about speeding, so a long stretch at 61 in a 55 gets
 * one warning instead of a reminder every minute. Pure: the caller passes the
 * clock and feeds it every GPS fix with [update].
 *
 * - Warn once when you've been over limit + tolerance for [GRACE_MS].
 * - Then stay quiet, however long you stay over at the same limit.
 * - Warn again only when (a) the posted limit changes and you're still over,
 *   (b) you were at or under the limit for [COOLDOWN_MS] and go over again, or
 *   (c) you're far more over than when warned: [ESCALATION_MPH] or
 *   [ESCALATION_FRACTION] over the limit, and [ESCALATION_STEP_MPH] more than
 *   at the warning. (c) fires once until (a) or (b) starts a new stretch.
 * - Never twice within [MIN_GAP_MS], so a limit that flickers between two
 *   values along a route can't chatter. A warning held back by this fires as
 *   soon as the gap is over, if you're still over.
 *
 * One instance per trip; call [reset] when a trip ends.
 */
class SpeedWarningPolicy(private val toleranceMph: Double = SpeedLimits.TOLERANCE_MPH) {

    private var lastLimit: Int? = null

    /** Null: this stretch has been warned about. Otherwise the reason the next time over (past the grace) warns. */
    private var pendingReason: SpeedWarningReason? = SpeedWarningReason.FIRST
    private var warnedOverBy = 0.0
    private var escalated = false
    private var lastWarningAt: Long? = null
    private var overSince: Long? = null
    private var severeSince: Long? = null
    private var underSince: Long? = null

    fun reset() {
        lastLimit = null
        pendingReason = SpeedWarningReason.FIRST
        warnedOverBy = 0.0
        escalated = false
        lastWarningAt = null
        overSince = null
        severeSince = null
        underSince = null
    }

    /**
     * One GPS fix. [speedMph] is null without a speed, [limitMph] null where
     * the map has no limit; either way there's nothing to judge, so a running
     * grace period starts over (the last limit is remembered).
     */
    fun update(nowMs: Long, speedMph: Double?, limitMph: Int?): SpeedWarning? {
        if (speedMph == null || limitMph == null) {
            overSince = null
            severeSince = null
            return null
        }

        if (lastLimit != null && lastLimit != limitMph) {
            // A new stretch of road: whatever was said about the old one is over.
            if (pendingReason != SpeedWarningReason.FIRST) pendingReason = SpeedWarningReason.LIMIT_CHANGED
            escalated = false
            overSince = null
            severeSince = null
            underSince = null
        }
        lastLimit = limitMph

        // Under the limit for a full minute, as of this fix, makes the next time over a new warning.
        val under = underSince
        if (pendingReason == null && under != null && nowMs - under >= COOLDOWN_MS) {
            pendingReason = SpeedWarningReason.RESUMED
            escalated = false
        }
        // The tolerance band above the limit isn't "under the limit" either, so it ends the stretch under it.
        underSince = if (speedMph <= limitMph) under ?: nowMs else null

        if (speedMph <= limitMph + toleranceMph) {
            overSince = null
            severeSince = null
            return null
        }

        val overBy = speedMph - limitMph
        val over = overSince ?: nowMs.also { overSince = it }
        if (nowMs - over < GRACE_MS) return null
        val gapOk = lastWarningAt?.let { nowMs - it >= MIN_GAP_MS } ?: true

        val reason = pendingReason
        if (reason != null) return if (gapOk) warn(nowMs, limitMph, overBy, reason) else null

        // Already warned for this stretch: only a much worse speed warns again, once.
        if (!escalated && isSevere(overBy, limitMph) && overBy - warnedOverBy >= ESCALATION_STEP_MPH) {
            val severe = severeSince ?: nowMs.also { severeSince = it }
            if (nowMs - severe >= GRACE_MS && gapOk) {
                escalated = true
                return warn(nowMs, limitMph, overBy, SpeedWarningReason.ESCALATION)
            }
        } else {
            severeSince = null
        }
        return null
    }

    private fun warn(nowMs: Long, limitMph: Int, overBy: Double, reason: SpeedWarningReason): SpeedWarning {
        pendingReason = null
        warnedOverBy = overBy
        lastWarningAt = nowMs
        severeSince = null
        return SpeedWarning(limitMph, overBy, reason)
    }

    private fun isSevere(overBy: Double, limitMph: Int): Boolean =
        overBy >= ESCALATION_MPH || overBy / limitMph >= ESCALATION_FRACTION

    companion object {
        /** Over limit + tolerance this long before a warning (a brief burst of speed isn't worth one). */
        const val GRACE_MS = 8_000L

        /** At or under the limit this long, and going over again is a new warning. */
        const val COOLDOWN_MS = 60_000L

        /** Minimum time between any two warnings. */
        const val MIN_GAP_MS = 20_000L

        /** Escalation: this many mph over the limit... */
        const val ESCALATION_MPH = 15.0

        /** ...or this fraction of the limit over it (20%)... */
        const val ESCALATION_FRACTION = 0.20

        /** ...and at least this much more over than when last warned. */
        const val ESCALATION_STEP_MPH = 5.0
    }
}
