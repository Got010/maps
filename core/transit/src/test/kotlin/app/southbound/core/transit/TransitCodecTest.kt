package app.southbound.core.transit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Byte strings come from pipeline/transit/build_transit.py, so both sides agree. */
class TransitCodecTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `decodes trip times from the pipeline encoder`() {
        val blob = bytes(0, 0, 200, 1, 60, 60, 0, 152, 250, 10, 0)
        val arrivals = IntArray(4)
        val departures = IntArray(4)
        assertEquals(4, TransitCodec.times(100, blob, arrivals, departures))
        assertArrayEquals(intArrayOf(100, 200, 260, 90_000), arrivals)
        assertArrayEquals(intArrayOf(100, 230, 260, 90_000), departures)
    }

    @Test
    fun `negative deltas and shifts`() {
        val arrivals = IntArray(3)
        val departures = IntArray(3)
        TransitCodec.times(500, bytes(0, 0, 199, 1, 100), arrivals, departures, offset = 1, shift = -86_400)
        assertArrayEquals(intArrayOf(0, 500 - 86_400, 400 - 86_400), arrivals)
        assertArrayEquals(intArrayOf(0, 500 - 86_400, 450 - 86_400), departures)
    }

    @Test
    fun `decodes stop lists`() {
        assertArrayEquals(intArrayOf(1, 300, 16_384), TransitCodec.uints(bytes(1, 172, 2, 128, 128, 1)))
    }

    @Test
    fun `test encoder matches the pipeline`() {
        assertArrayEquals(
            bytes(0, 0, 200, 1, 60, 60, 0, 152, 250, 10, 0),
            TestNetwork.encode(listOf(100, 200, 260, 90_000), listOf(100, 230, 260, 90_000)),
        )
    }
}
