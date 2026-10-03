package app.southbound.core.routing

import app.southbound.core.routing.SpeedWarningReason.ESCALATION
import app.southbound.core.routing.SpeedWarningReason.FIRST
import app.southbound.core.routing.SpeedWarningReason.LIMIT_CHANGED
import app.southbound.core.routing.SpeedWarningReason.RESUMED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedWarningPolicyTest {

    private val policy = SpeedWarningPolicy()
    private var now = 0L

    /** One GPS fix a second for [seconds], returning every warning given. */
    private fun drive(seconds: Int, speed: Double?, limit: Int?): List<SpeedWarning> {
        val out = ArrayList<SpeedWarning>()
        repeat(seconds) {
            policy.update(now, speed, limit)?.let(out::add)
            now += 1_000
        }
        return out
    }

    @Test
    fun withinToleranceNeverWarns() {
        assertEquals(emptyList<SpeedWarning>(), drive(600, 60.0, 55)) // exactly limit + 5 is fine
        assertEquals(emptyList<SpeedWarning>(), drive(600, 40.0, 55))
    }

    @Test
    fun warnsOnlyAfterTheGracePeriod() {
        val grace = (SpeedWarningPolicy.GRACE_MS / 1000).toInt()
        assertEquals(emptyList<SpeedWarning>(), drive(grace, 62.0, 55)) // 0..grace-1 s over
        val warnings = drive(1, 62.0, 55) // the fix at exactly grace seconds
        assertEquals(listOf(SpeedWarning(55, 7.0, FIRST)), warnings)
    }

    @Test
    fun aShortBurstOverTheLimitDoesNotWarn() {
        assertEquals(emptyList<SpeedWarning>(), drive(5, 70.0, 55))
        drive(1, 55.0, 55)
        assertEquals(emptyList<SpeedWarning>(), drive(5, 70.0, 55)) // the grace starts over
    }

    @Test
    fun fifteenMilesAtSixtyOneInAFiftyFiveWarnsExactlyOnce() {
        // 15 miles at about 61 mph is 15 minutes of fixes, wobbling a little.
        val warnings = ArrayList<SpeedWarning>()
        repeat(15 * 60) { i ->
            val speed = 61.0 + (i % 7) * 0.3 - 0.4 // 60.6 .. 62.4
            policy.update(now, speed, 55)?.let(warnings::add)
            now += 1_000
        }
        assertEquals(1, warnings.size)
        assertEquals(FIRST, warnings.single().reason)
        assertEquals(55, warnings.single().limitMph)
    }

    @Test
    fun stayingOverForHoursStillWarnsOnce() {
        assertEquals(1, drive(3 * 3600, 63.0, 55).size)
    }

    @Test
    fun wobblingInAndOutOfTheToleranceBandDoesNotRewarn() {
        val all = ArrayList<SpeedWarning>()
        repeat(30) {
            all += drive(20, 62.0, 55) // over
            all += drive(20, 58.0, 55) // back in the band, still above the limit
        }
        assertEquals(1, all.size)
    }

    @Test
    fun warnsAgainWhenTheLimitChangesAndYouAreStillOver() {
        assertEquals(1, drive(60, 62.0, 55).size)
        val warnings = drive(60, 62.0, 45)
        assertEquals(listOf(SpeedWarning(45, 17.0, LIMIT_CHANGED)), warnings)
        // And then quiet again on the new stretch.
        assertEquals(emptyList<SpeedWarning>(), drive(600, 62.0, 45))
    }

    @Test
    fun noWarningWhenTheLimitRisesAndYouAreNoLongerOver() {
        assertEquals(1, drive(60, 62.0, 55).size)
        assertEquals(emptyList<SpeedWarning>(), drive(120, 62.0, 65))
    }

    @Test
    fun aLimitThatChangesWhileYouAreSlowThenWarnsWhenYouSpeedUp() {
        assertEquals(1, drive(60, 62.0, 55).size)
        assertEquals(emptyList<SpeedWarning>(), drive(60, 25.0, 30)) // new limit 30, going slower: nothing
        assertEquals(listOf(LIMIT_CHANGED), drive(60, 45.0, 30).map { it.reason })
    }

    @Test
    fun aLimitThatDisappearsAndComesBackIsNotAChange() {
        assertEquals(1, drive(60, 62.0, 55).size)
        assertEquals(emptyList<SpeedWarning>(), drive(60, 62.0, null))
        assertEquals(emptyList<SpeedWarning>(), drive(600, 62.0, 55))
    }

    @Test
    fun missingSpeedRestartsTheGrace() {
        assertEquals(emptyList<SpeedWarning>(), drive(7, 62.0, 55))
        drive(1, null, 55)
        assertEquals(emptyList<SpeedWarning>(), drive(7, 62.0, 55))
        assertEquals(1, drive(2, 62.0, 55).size)
    }

    @Test
    fun warnsAgainAfterAMinuteUnderTheLimit() {
        assertEquals(1, drive(60, 62.0, 55).size)
        drive(60, 50.0, 55) // a full minute at or under the limit
        assertEquals(listOf(RESUMED), drive(60, 62.0, 55).map { it.reason })
        assertEquals(emptyList<SpeedWarning>(), drive(600, 62.0, 55))
    }

    @Test
    fun underTheLimitForLessThanAMinuteDoesNotRewarn() {
        assertEquals(1, drive(60, 62.0, 55).size)
        drive(59, 50.0, 55)
        assertEquals(emptyList<SpeedWarning>(), drive(600, 62.0, 55))
    }

    @Test
    fun theBandAboveTheLimitDoesNotCountAsUnder() {
        assertEquals(1, drive(60, 62.0, 55).size)
        drive(300, 58.0, 55) // above the limit the whole time, inside the tolerance
        assertEquals(emptyList<SpeedWarning>(), drive(600, 62.0, 55))
    }

    @Test
    fun warnsOnceMoreWhenMuchFurtherOver() {
        assertEquals(listOf(SpeedWarning(55, 6.0, FIRST)), drive(30, 61.0, 55))
        // 11 over a 55 is 20%, and 5 more than at the warning.
        val warnings = drive(60, 66.0, 55)
        assertEquals(listOf(SpeedWarning(55, 11.0, ESCALATION)), warnings)
        // One escalation only, even if it gets worse.
        assertEquals(emptyList<SpeedWarning>(), drive(600, 80.0, 55))
    }

    @Test
    fun escalatesAtFifteenOverOnAFastRoad() {
        assertEquals(listOf(FIRST), drive(30, 71.0, 65).map { it.reason })
        // 11 over a 65 is 17%, under both thresholds.
        assertEquals(emptyList<SpeedWarning>(), drive(60, 76.0, 65))
        assertEquals(listOf(ESCALATION), drive(60, 80.0, 65).map { it.reason })
    }

    @Test
    fun escalationNeedsToBeSustained() {
        assertEquals(1, drive(30, 61.0, 55).size)
        assertEquals(emptyList<SpeedWarning>(), drive(5, 75.0, 55)) // a GPS spike
        drive(1, 61.0, 55)
        assertEquals(emptyList<SpeedWarning>(), drive(5, 75.0, 55))
        assertEquals(1, drive(60, 75.0, 55).size)
    }

    @Test
    fun noEscalationForSmallIncreases() {
        assertEquals(1, drive(30, 61.0, 55).size)
        assertEquals(emptyList<SpeedWarning>(), drive(600, 64.0, 55))
    }

    @Test
    fun aFlickeringLimitCannotChatter() {
        val warnings = ArrayList<SpeedWarning>()
        // The limit alternates between two values every 9 seconds while you hold 70.
        repeat(40) { i ->
            warnings += drive(9, 70.0, if (i % 2 == 0) 55 else 45)
        }
        // Over 360 s, at most one warning per MIN_GAP_MS.
        val maxAllowed = 360_000 / SpeedWarningPolicy.MIN_GAP_MS + 1
        assertTrue("${warnings.size} warnings", warnings.size <= maxAllowed.toInt())
    }

    @Test
    fun resetStartsTheTripOver() {
        assertEquals(1, drive(60, 62.0, 55).size)
        policy.reset()
        assertEquals(listOf(FIRST), drive(60, 62.0, 55).map { it.reason })
    }

    @Test
    fun noLimitMeansNothingToWarnAbout() {
        assertNull(policy.update(0, 90.0, null))
        assertEquals(emptyList<SpeedWarning>(), drive(600, 90.0, null))
    }
}
