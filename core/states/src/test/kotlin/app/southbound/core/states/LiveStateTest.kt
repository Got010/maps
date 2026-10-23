package app.southbound.core.states

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveStateTest {

    private val live get() = LiveState(TestData.outlines)

    private val lat = 38.5
    private val kansas = TestData.KS_CO_LON + 0.02
    private fun colorado(metersIn: Double) = TestData.KS_CO_LON - metersIn / TestData.M_PER_DEG_LON_385

    @Test
    fun `the first fix sets the state and is not a crossing`() {
        val s = live
        assertNull(s.current)
        assertNull(s.onFix(lat, kansas))
        assertEquals("KS", s.current)
    }

    @Test
    fun `driving across a line reports it once, when the second fix over is 500 m on`() {
        val s = live
        s.onFix(lat, kansas)
        assertNull(s.onFix(lat, colorado(100.0)))
        assertNull("only 260 m from the first fix over", s.onFix(lat, colorado(360.0)))
        assertEquals("KS", s.current)
        assertEquals(LiveState.Entered("KS", "CO"), s.onFix(lat, colorado(700.0)))
        assertEquals("CO", s.current)
        assertNull(s.onFix(lat, colorado(1000.0)))
        assertNull(s.onFix(lat, colorado(1300.0)))
    }

    @Test
    fun `fixes that jitter over the line and back report nothing`() {
        val s = live
        s.onFix(lat, kansas)
        repeat(20) {
            assertNull(s.onFix(lat, colorado(300.0)))
            assertNull(s.onFix(lat, TestData.KS_CO_LON + 0.001))
        }
        assertEquals("KS", s.current)
    }

    @Test
    fun `a phone parked just over the line reports nothing`() {
        val s = live
        s.onFix(lat, kansas)
        repeat(50) { assertNull(s.onFix(lat, colorado(120.0))) }
        assertEquals("KS", s.current)
    }

    @Test
    fun `a fix back in the old state forgets the first fix over`() {
        val s = live
        s.onFix(lat, kansas)
        s.onFix(lat, colorado(100.0))
        s.onFix(lat, kansas)
        // The first fix over starts again here, so this one, 900 m from the earlier one, is not yet two in a row.
        assertNull(s.onFix(lat, colorado(1000.0)))
        assertEquals(LiveState.Entered("KS", "CO"), s.onFix(lat, colorado(1600.0)))
    }

    @Test
    fun `a fix out at sea is ignored, and the last known state holds`() {
        val s = live
        s.onFix(lat, kansas)
        assertNull(s.onFix(35.0, -60.0))
        assertEquals("KS", s.current)
        s.onFix(lat, colorado(100.0))
        assertNull(s.onFix(35.0, -60.0))
        assertEquals(LiveState.Entered("KS", "CO"), s.onFix(lat, colorado(800.0)))
    }

    @Test
    fun `the first fixes at sea set no state`() {
        val s = live
        assertNull(s.onFix(35.0, -60.0))
        assertNull(s.current)
    }

    @Test
    fun `Washington DC is entered and left like any state`() {
        val s = live
        s.onFix(38.89, -77.20)
        assertEquals("VA", s.current)
        // Along 38.89 N, a fix every 0.005 degrees (about 430 m).
        val entered = mutableListOf<LiveState.Entered>()
        var lon = -77.20
        while (lon < -76.80) {
            lon += 0.005
            s.onFix(38.89, lon)?.let { entered += it }
        }
        assertEquals(listOf(LiveState.Entered("VA", "DC"), LiveState.Entered("DC", "MD")), entered)
    }

    @Test
    fun `reset forgets the state`() {
        val s = live
        s.onFix(lat, kansas)
        s.reset()
        assertNull(s.current)
        assertNull(s.onFix(lat, colorado(2000.0)))
        assertEquals("CO", s.current)
    }

    @Test
    fun `fixes with poor or no accuracy are skipped`() {
        val s = live
        s.onFix(lat, kansas, 8.0)
        // Two bad fixes well over the line, far apart: no crossing and no candidate.
        assertNull(s.onFix(lat, colorado(3000.0), 250.0))
        assertNull(s.onFix(lat, colorado(9000.0), 250.0))
        assertNull(s.onFix(lat, colorado(9000.0), null))
        assertEquals("KS", s.current)
        // A bad fix is not half of a crossing: one good fix after two bad ones is still the first fix over.
        assertNull(s.onFix(lat, colorado(1000.0), 20.0))
        assertEquals("KS", s.current)
        assertEquals(LiveState.Entered("KS", "CO"), s.onFix(lat, colorado(1700.0), 100.0))
    }

    @Test
    fun `a bad first fix does not set the state`() {
        val s = live
        assertNull(s.onFix(lat, kansas, 500.0))
        assertNull(s.current)
        assertNull(s.onFix(lat, kansas, 30.0))
        assertEquals("KS", s.current)
    }

    @Test
    fun `the confirming fix must be 500 m inside the new state`() {
        // 1.5 km from the first fix over but only 300 m inside along the line: it runs along the border.
        val along = LiveState(TestData.outlines)
        along.onFix(lat, kansas)
        assertNull(along.onFix(lat, colorado(50.0)))
        assertNull(along.onFix(lat + 0.015, colorado(300.0)))
        assertEquals("KS", along.current)
        // A later fix 600 m in confirms it.
        assertEquals(LiveState.Entered("KS", "CO"), along.onFix(lat + 0.02, colorado(600.0)))
    }
}
