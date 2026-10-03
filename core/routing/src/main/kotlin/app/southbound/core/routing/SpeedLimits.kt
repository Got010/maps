package app.southbound.core.routing

import com.stadiamaps.ferrostar.core.NavigationState
import com.stadiamaps.ferrostar.core.annotation.valhalla.valhallaExtendedOSRMAnnotationPublisher
import com.stadiamaps.ferrostar.core.measurement.MeasurementSpeedUnit
import kotlin.math.roundToInt

/**
 * The speed limit of the road the user is on, from the route. Ferrostar asks
 * Valhalla for shape attributes with every route, and Valhalla's OSRM output
 * carries the limit of each segment (from OSM maxspeed tags, in km/h), so no
 * extra data or request is needed. Null where OSM has no limit.
 */
object SpeedLimits {
    /** How far over the limit counts as speeding, for the sign and the spoken warning. */
    const val TOLERANCE_MPH = 5.0

    private val publisher = valhallaExtendedOSRMAnnotationPublisher()

    fun currentMph(state: NavigationState): Int? {
        val speed = runCatching { publisher.map(state).speedLimit }.getOrNull() ?: return null
        // Valhalla gives km/h; OSM's US limits are mph signs converted, so round back to a sign value.
        return roundToSign(speed.value(MeasurementSpeedUnit.MilesPerHour))
    }

    /** 56.3 -> 55: US limits are multiples of 5. */
    fun roundToSign(mph: Double): Int? {
        if (mph <= 0 || mph > 120) return null
        return ((mph / 5).roundToInt() * 5).coerceAtLeast(5)
    }

    /** Metres per second from GPS to mph. */
    fun mph(metersPerSecond: Float): Double = metersPerSecond * 2.23694
}
