package app.southbound.core.map

import android.location.Location

/**
 * Keeps the fin's heading steady. A GPS fix only carries a trustworthy bearing while the phone is
 * actually moving, so the heading is updated from those fixes alone; every other fix (no bearing,
 * parked, crawling, or a poor bearing estimate) leaves the last good heading in place. Starts
 * facing north.
 */
class HeadingFilter {
    /** Degrees clockwise from north, 0 until the first usable fix. */
    var heading: Float = 0f
        private set

    /** Feeds one raw fix and returns the heading to show. */
    fun update(
        hasBearing: Boolean,
        bearing: Float,
        hasSpeed: Boolean,
        speed: Float,
        hasBearingAccuracy: Boolean,
        bearingAccuracy: Float,
    ): Float {
        if (isUsable(hasBearing, hasSpeed, speed, hasBearingAccuracy, bearingAccuracy)) {
            heading = normalize(bearing)
        }
        return heading
    }

    /** Feeds a raw GPS fix from the location manager. */
    fun update(fix: Location): Float = update(
        hasBearing = fix.hasBearing(),
        bearing = fix.bearing,
        hasSpeed = fix.hasSpeed(),
        speed = fix.speed,
        hasBearingAccuracy = fix.hasBearingAccuracy(),
        bearingAccuracy = fix.bearingAccuracyDegrees,
    )

    companion object {
        /** Below this speed (m/s, about 2 mph) GPS bearing is noise. */
        const val MIN_SPEED_MPS = 1.0f

        /** A reported bearing error above this many degrees is ignored. */
        const val MAX_BEARING_ACCURACY_DEGREES = 45f

        fun isUsable(
            hasBearing: Boolean,
            hasSpeed: Boolean,
            speed: Float,
            hasBearingAccuracy: Boolean,
            bearingAccuracy: Float,
        ): Boolean =
            hasBearing && hasSpeed && speed >= MIN_SPEED_MPS &&
                (!hasBearingAccuracy || bearingAccuracy <= MAX_BEARING_ACCURACY_DEGREES)

        private fun normalize(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f
    }
}

/** A copy of this location whose bearing is [heading], so the fin and the GPS camera agree. */
fun Location.withHeading(heading: Float): Location = Location(this).also { it.bearing = heading }
