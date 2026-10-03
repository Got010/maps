package app.southbound.core.transit

/** Builds small transit networks for tests, with the pack's encoding. */
internal class TestNetwork {
    private val stops = ArrayList<TransitStop>()
    private val routes = ArrayList<TransitRoute>()
    private val patterns = ArrayList<TransitPattern>()
    private val transfers = ArrayList<Transfer>()
    val trips = ArrayList<TripRow>()
    val services = HashMap<Int, Service>()

    init {
        services[1] = Service(1, days = 0b1111111, startDate = 20260101, endDate = 20261231)
    }

    /** Stops on a north-south line, ~1.1 km apart, so walking access stays explicit. */
    fun stop(id: Int, name: String = "Stop $id", changeSeconds: Int = 0, lat: Double = 40.0 + id * 0.01, lon: Double = -74.0) {
        stops.add(TransitStop(id, TransitSystem.SUBWAY, "$id", name, lat, lon, null, false, changeSeconds))
    }

    fun walk(from: Int, to: Int, seconds: Int) {
        transfers.add(Transfer(from, to, seconds))
    }

    /** A route with one pattern over [stopIds]; each trip lists its time at every stop. */
    fun route(id: Int, stopIds: List<Int>, vararg times: List<Int>, service: Int = 1) {
        routes.add(TransitRoute(id, TransitSystem.SUBWAY, "R$id", "R$id", null, 1, null, null, null))
        val n = stopIds.size
        patterns.add(
            TransitPattern(id, id, "To ${stopIds.last()}", 0, stopIds.toIntArray(),
                BooleanArray(n) { it < n - 1 }, BooleanArray(n) { it > 0 }),
        )
        for (t in times) {
            trips.add(TripRow(trips.size + 1, id, service, "T${trips.size + 1}", null, null, t.first(), encode(t, t)))
        }
    }

    fun build() = TransitNetwork(stops, routes, patterns, services, transfers)

    fun source() = TripSource { services, from, to -> trips.filter { it.service in services && it.start in from..to } }

    companion object {
        fun hm(h: Int, m: Int) = h * 3600 + m * 60

        fun encode(arrivals: List<Int>, departures: List<Int>): ByteArray {
            val out = ArrayList<Byte>()
            fun varint(v: Int) {
                var n = v
                while (n >= 0x80) {
                    out.add(((n and 0x7F) or 0x80).toByte())
                    n = n ushr 7
                }
                out.add(n.toByte())
            }
            fun zigzag(n: Int) = if (n >= 0) n shl 1 else ((-n) shl 1) - 1
            var previous = departures.first()
            for (i in arrivals.indices) {
                varint(zigzag(arrivals[i] - previous))
                varint(zigzag(departures[i] - arrivals[i]))
                previous = departures[i]
            }
            return out.toByteArray()
        }
    }
}
