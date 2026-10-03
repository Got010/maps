package app.southbound.core.transit

import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.hypot

/** A walking link between two stops. */
data class Transfer(val from: Int, val to: Int, val seconds: Int)

/** One scheduled trip as stored in the pack; times stay encoded until needed. */
class TripRow(
    val id: Int,
    val pattern: Int,
    val service: Int,
    val gtfsId: String,
    /** Overrides the pattern's headsign when not null. */
    val headsign: String?,
    /** LIRR and Metro-North train numbers. */
    val shortName: String?,
    val start: Int,
    val times: ByteArray,
)

/**
 * The parts of the transit pack that don't depend on the day: stops, lines,
 * patterns, calendars and walking transfers. Loaded once, then each search
 * pulls only the trips it needs.
 */
class TransitNetwork(
    stops: Collection<TransitStop>,
    routes: Collection<TransitRoute>,
    patterns: Collection<TransitPattern>,
    val services: Map<Int, Service>,
    transfers: Collection<Transfer>,
) {
    val stopCount: Int = (stops.maxOfOrNull { it.id } ?: 0) + 1
    private val stopArray = arrayOfNulls<TransitStop>(stopCount).also { a -> stops.forEach { a[it.id] = it } }
    private val routeMap = routes.associateBy { it.id }
    private val patternMap = patterns.associateBy { it.id }
    val allStops: List<TransitStop> = stops.sortedBy { it.id }

    /** Walking transfers out of each stop, as parallel arrays. */
    internal val transferTargets: Array<IntArray>
    internal val transferSeconds: Array<IntArray>

    /** Patterns serving each stop. */
    internal val stopPatterns: Array<IntArray>

    init {
        val byStop = transfers.groupBy { it.from }
        transferTargets = Array(stopCount) { s -> byStop[s]?.map { it.to }?.toIntArray() ?: IntArray(0) }
        transferSeconds = Array(stopCount) { s -> byStop[s]?.map { it.seconds }?.toIntArray() ?: IntArray(0) }
        val serving = Array(stopCount) { ArrayList<Int>() }
        patterns.forEach { p -> p.stops.distinct().forEach { s -> if (s < stopCount) serving[s].add(p.id) } }
        stopPatterns = Array(stopCount) { serving[it].toIntArray() }
    }

    fun stop(id: Int): TransitStop? = stopArray.getOrNull(id)
    fun route(id: Int): TransitRoute? = routeMap[id]
    fun pattern(id: Int): TransitPattern? = patternMap[id]
    fun patternsAt(stop: Int): IntArray = stopPatterns.getOrElse(stop) { IntArray(0) }
    fun transfersFrom(stop: Int): List<Transfer> =
        transferTargets[stop].indices.map { Transfer(stop, transferTargets[stop][it], transferSeconds[stop][it]) }

    fun activeServices(date: LocalDate): Set<Int> = services.values.filter { it.runsOn(date) }.mapTo(HashSet()) { it.id }

    /** The platforms of a station, or the stop itself. */
    fun platforms(stop: Int): List<Int> {
        val children = allStops.filter { it.parent == stop }.map { it.id }
        return children.ifEmpty { listOf(stop) }
    }

    /**
     * Stops a vehicle stops at within [radiusMeters] of a point, nearest
     * first, with the walk there in seconds.
     */
    fun walkableStops(lat: Double, lon: Double, radiusMeters: Double): List<StopAccess> =
        allStops.asSequence()
            .filter { !it.isStation && stopPatterns[it.id].isNotEmpty() }
            .map { it to Walking.distanceMeters(lat, lon, it.lat, it.lon) }
            .filter { it.second <= radiusMeters }
            .sortedBy { it.second }
            .map { StopAccess(it.first.id, Walking.seconds(it.second)) }
            .toList()
}

/** A stop reachable on foot, and how long the walk takes. */
data class StopAccess(val stop: Int, val seconds: Int)

/** Walking times, matching the pipeline's transfer model. */
object Walking {
    const val SPEED_MPS = 1.33
    const val DETOUR = 1.25

    fun seconds(meters: Double): Int = kotlin.math.ceil(meters * DETOUR / SPEED_MPS).toInt()

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val x = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
        val y = Math.toRadians(lat2 - lat1)
        return hypot(x, y) * 6_371_008.8
    }
}
