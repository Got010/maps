package app.southbound.core.transit

/**
 * Decodes the varint blobs in the transit pack; the encoder is
 * pipeline/transit/build_transit.py.
 */
object TransitCodec {

    /** A list of unsigned varints, such as a pattern's stop ids. */
    fun uints(blob: ByteArray): IntArray {
        val out = IntArray(countVarints(blob))
        var pos = 0
        for (i in out.indices) {
            var shift = 0
            var value = 0
            while (true) {
                val b = blob[pos++].toInt() and 0xFF
                value = value or ((b and 0x7F) shl shift)
                if (b < 0x80) break
                shift += 7
            }
            out[i] = value
        }
        return out
    }

    /**
     * One trip's times, written into [arrivals] and [departures] from
     * [offset] on, shifted by [shift] seconds. Returns the stop count.
     */
    fun times(start: Int, blob: ByteArray, arrivals: IntArray, departures: IntArray, offset: Int = 0, shift: Int = 0): Int {
        val values = uints(blob)
        var previous = start
        val count = values.size / 2
        for (i in 0 until count) {
            val arrival = previous + unzigzag(values[2 * i])
            val departure = arrival + unzigzag(values[2 * i + 1])
            arrivals[offset + i] = arrival + shift
            departures[offset + i] = departure + shift
            previous = departure
        }
        return count
    }

    fun stopCount(blob: ByteArray): Int = countVarints(blob) / 2

    private fun countVarints(blob: ByteArray): Int = blob.count { it.toInt() and 0x80 == 0 }

    private fun unzigzag(n: Int): Int = (n ushr 1) xor -(n and 1)
}
