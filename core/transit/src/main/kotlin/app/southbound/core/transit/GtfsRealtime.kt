package app.southbound.core.transit

/**
 * The parts of a GTFS-realtime feed Southbound uses: trip updates (delays and
 * predicted times) and service alerts. Vehicle positions and agency
 * extensions such as NYCT's are skipped.
 *
 * Parsed straight from protobuf wire format against gtfs-realtime.proto, so
 * the app needs no protobuf library.
 */
data class RealtimeFeed(
    /** POSIX seconds when the feed was generated. */
    val timestamp: Long,
    val tripUpdates: List<TripUpdate>,
    val alerts: List<ServiceAlert>,
)

data class TripDescriptor(
    val tripId: String?,
    val routeId: String?,
    val directionId: Int?,
    val startTime: String?,
    /** yyyymmdd. */
    val startDate: String?,
    val canceled: Boolean,
)

data class TripUpdate(
    val trip: TripDescriptor,
    val stopTimeUpdates: List<StopTimeUpdate>,
    /** Trip-wide delay in seconds, when the feed gives one. */
    val delay: Int?,
    val timestamp: Long?,
)

data class StopTimeUpdate(
    val stopSequence: Int?,
    val stopId: String?,
    val arrival: StopTimeEvent?,
    val departure: StopTimeEvent?,
    val skipped: Boolean,
)

data class StopTimeEvent(
    val delay: Int?,
    /** POSIX seconds. */
    val time: Long?,
)

/** A route an alert names, with the agency that tells same-named routes apart. */
data class AlertRoute(val agencyId: String?, val routeId: String)

data class ServiceAlert(
    val id: String,
    val activePeriods: List<LongRange>,
    val routes: Set<AlertRoute>,
    val stopIds: Set<String>,
    val header: String?,
    val description: String?,
    val effect: Int?,
) {
    val routeIds: Set<String> get() = routes.mapTo(HashSet()) { it.routeId }

    /** Active at [now] (POSIX seconds); an alert without periods is always active. */
    fun isActive(now: Long): Boolean = activePeriods.isEmpty() || activePeriods.any { now in it }
}

object GtfsRealtime {

    fun parse(bytes: ByteArray): RealtimeFeed {
        var timestamp = 0L
        val updates = ArrayList<TripUpdate>()
        val alerts = ArrayList<ServiceAlert>()
        val reader = ProtoReader(bytes)
        while (reader.next()) {
            when (reader.field) {
                1 -> reader.message { header ->
                    while (header.next()) if (header.field == 3) timestamp = header.long() else header.skip()
                }
                2 -> reader.message { entity -> parseEntity(entity, updates, alerts) }
                else -> reader.skip()
            }
        }
        return RealtimeFeed(timestamp, updates, alerts)
    }

    private fun parseEntity(r: ProtoReader, updates: MutableList<TripUpdate>, alerts: MutableList<ServiceAlert>) {
        var id = ""
        var deleted = false
        var update: TripUpdate? = null
        var alert: ServiceAlert? = null
        while (r.next()) {
            when (r.field) {
                1 -> id = r.string()
                2 -> deleted = r.long() != 0L
                3 -> update = r.message(::parseTripUpdate)
                5 -> alert = r.message { parseAlert(it, id) }
                else -> r.skip()
            }
        }
        if (deleted) return
        update?.let(updates::add)
        alert?.let(alerts::add)
    }

    private fun parseTripUpdate(r: ProtoReader): TripUpdate {
        var trip = TripDescriptor(null, null, null, null, null, false)
        val stops = ArrayList<StopTimeUpdate>()
        var delay: Int? = null
        var timestamp: Long? = null
        while (r.next()) {
            when (r.field) {
                1 -> trip = r.message(::parseTrip)
                2 -> stops.add(r.message(::parseStopTimeUpdate))
                4 -> timestamp = r.long()
                5 -> delay = r.long().toInt()
                else -> r.skip()
            }
        }
        return TripUpdate(trip, stops, delay, timestamp)
    }

    private fun parseTrip(r: ProtoReader): TripDescriptor {
        var tripId: String? = null
        var routeId: String? = null
        var direction: Int? = null
        var startTime: String? = null
        var startDate: String? = null
        var canceled = false
        while (r.next()) {
            when (r.field) {
                1 -> tripId = r.string()
                2 -> startTime = r.string()
                3 -> startDate = r.string()
                4 -> canceled = r.long() == CANCELED
                5 -> routeId = r.string()
                6 -> direction = r.long().toInt()
                else -> r.skip()
            }
        }
        return TripDescriptor(tripId, routeId, direction, startTime, startDate, canceled)
    }

    private fun parseStopTimeUpdate(r: ProtoReader): StopTimeUpdate {
        var sequence: Int? = null
        var stopId: String? = null
        var arrival: StopTimeEvent? = null
        var departure: StopTimeEvent? = null
        var skipped = false
        while (r.next()) {
            when (r.field) {
                1 -> sequence = r.long().toInt()
                2 -> arrival = r.message(::parseEvent)
                3 -> departure = r.message(::parseEvent)
                4 -> stopId = r.string()
                5 -> skipped = r.long() == SKIPPED
                else -> r.skip()
            }
        }
        return StopTimeUpdate(sequence, stopId, arrival, departure, skipped)
    }

    private fun parseEvent(r: ProtoReader): StopTimeEvent {
        var delay: Int? = null
        var time: Long? = null
        while (r.next()) {
            when (r.field) {
                1 -> delay = r.long().toInt()
                2 -> time = r.long()
                else -> r.skip()
            }
        }
        return StopTimeEvent(delay, time)
    }

    private fun parseAlert(r: ProtoReader, id: String): ServiceAlert {
        val periods = ArrayList<LongRange>()
        val routes = HashSet<AlertRoute>()
        val stops = HashSet<String>()
        var header: String? = null
        var description: String? = null
        var effect: Int? = null
        while (r.next()) {
            when (r.field) {
                1 -> periods.add(r.message(::parsePeriod))
                5 -> r.message { e ->
                    var agency: String? = null
                    val named = ArrayList<String>()
                    while (e.next()) {
                        when (e.field) {
                            1 -> agency = e.string()
                            2 -> named.add(e.string())
                            5 -> stops.add(e.string())
                            4 -> e.message(::parseTrip).routeId?.let(named::add)
                            else -> e.skip()
                        }
                    }
                    named.forEach { routes.add(AlertRoute(agency, it)) }
                }
                7 -> effect = r.long().toInt()
                10 -> header = r.message(::parseText)
                11 -> description = r.message(::parseText)
                else -> r.skip()
            }
        }
        return ServiceAlert(id, periods, routes, stops, header, description, effect)
    }

    private fun parsePeriod(r: ProtoReader): LongRange {
        var start = 0L
        var end = Long.MAX_VALUE
        while (r.next()) {
            when (r.field) {
                1 -> start = r.long()
                2 -> end = r.long()
                else -> r.skip()
            }
        }
        return start..end
    }

    /** The plain-text English translation; MTA also sends an "en-html" copy. */
    private fun parseText(r: ProtoReader): String? {
        val translations = ArrayList<Pair<String, String?>>()
        while (r.next()) {
            if (r.field != 1) {
                r.skip()
                continue
            }
            translations.add(
                r.message { t ->
                    var text = ""
                    var language: String? = null
                    while (t.next()) {
                        when (t.field) {
                            1 -> text = t.string()
                            2 -> language = t.string()
                            else -> t.skip()
                        }
                    }
                    text to language
                },
            )
        }
        val plain = translations.filterNot { it.second?.contains("html") == true }
        return (plain.firstOrNull { it.second == null || it.second!!.startsWith("en") } ?: plain.firstOrNull())?.first
            ?.takeIf { it.isNotBlank() }
    }

    private const val CANCELED = 3L
    private const val SKIPPED = 1L
}

/** A minimal protobuf wire-format reader. */
internal class ProtoReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {
    var field = 0
        private set
    private var wireType = 0

    /** Reads the next tag; false at the end of the message. */
    fun next(): Boolean {
        if (pos >= end) return false
        val tag = varint()
        field = (tag ushr 3).toInt()
        wireType = (tag and 7).toInt()
        return true
    }

    /** A varint field as a signed 64-bit value (int32 negatives arrive sign-extended). */
    fun long(): Long = when (wireType) {
        0 -> varint()
        1 -> fixed(8)
        5 -> fixed(4)
        else -> throw IllegalArgumentException("Field $field is not a number")
    }

    fun string(): String {
        val length = lengthPrefix()
        val s = String(buf, pos, length, Charsets.UTF_8)
        pos += length
        return s
    }

    fun <T> message(read: (ProtoReader) -> T): T {
        val length = lengthPrefix()
        val inner = ProtoReader(buf, pos, pos + length)
        pos += length
        return read(inner)
    }

    fun skip() {
        when (wireType) {
            0 -> varint()
            1 -> pos += 8
            2 -> {
                // Not `pos += lengthPrefix()`: that reads pos before the length is consumed.
                val length = lengthPrefix()
                pos += length
            }
            5 -> pos += 4
            else -> throw IllegalArgumentException("Unsupported wire type $wireType")
        }
        require(pos <= end) { "Truncated protobuf" }
    }

    private fun lengthPrefix(): Int {
        require(wireType == 2) { "Field $field is not length-delimited" }
        val length = varint().toInt()
        require(length >= 0 && pos + length <= end) { "Truncated protobuf" }
        return length
    }

    private fun varint(): Long {
        var shift = 0
        var result = 0L
        while (true) {
            require(pos < end) { "Truncated protobuf" }
            val b = buf[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b < 0x80) return result
            shift += 7
            require(shift < 64) { "Malformed varint" }
        }
    }

    private fun fixed(bytes: Int): Long {
        require(pos + bytes <= end) { "Truncated protobuf" }
        var result = 0L
        for (i in 0 until bytes) result = result or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += bytes
        return result
    }
}
