package app.southbound.core.states

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StateOutlinesTest {

    private val outlines get() = TestData.outlines

    @Test
    fun `the file holds fifty states and DC, each once`() {
        assertEquals(51, outlines.codes.size)
        assertEquals(51, outlines.codes.toSet().size)
        assertTrue("DC" in outlines.codes)
    }

    @Test
    fun `every state's capital is in its own outline`() {
        assertEquals(51, TestData.capitals.size)
        for ((code, at) in TestData.capitals) {
            assertEquals("capital of $code", code, outlines.stateAt(at.first, at.second))
        }
    }

    @Test
    fun `a point either side of a straight border is in the state on that side`() {
        assertEquals("KS", outlines.stateAt(38.5, TestData.KS_CO_LON + 0.001))
        assertEquals("CO", outlines.stateAt(38.5, TestData.KS_CO_LON - 0.001))
    }

    @Test
    fun `Washington DC is its own state, with Virginia across the river and Maryland beyond`() {
        assertEquals("DC", outlines.stateAt(38.8895, -77.0353))
        assertEquals("VA", outlines.stateAt(38.85, -77.20))
        assertEquals("MD", outlines.stateAt(38.89, -76.85))
    }

    @Test
    fun `a point in the open sea is in no state`() {
        assertNull(outlines.stateAt(35.0, -60.0))
        assertNull(outlines.stateAt(30.0, -70.0))
        assertNull(outlines.stateAt(Double.NaN, -100.0))
    }

    @Test
    fun `the hint is tried first and never changes the answer`() {
        assertEquals("CO", outlines.stateAt(38.5, TestData.KS_CO_LON - 0.001, "KS"))
        assertEquals("KS", outlines.stateAt(38.5, TestData.KS_CO_LON + 0.001, "KS"))
        assertEquals("KS", outlines.stateAt(38.5, TestData.KS_CO_LON + 0.001, "ZZ"))
        assertNull(outlines.stateAt(35.0, -60.0, "KS"))
    }

    @Test
    fun `isClearOf is true only beyond the distance from a border`() {
        val oneKmInDegrees = 1000.0 / TestData.M_PER_DEG_LON_385
        assertFalse(outlines.isClearOf("KS", 38.5, TestData.KS_CO_LON + 0.001, 1000.0)) // inside Kansas
        assertFalse(outlines.isClearOf("KS", 38.5, TestData.KS_CO_LON - oneKmInDegrees * 0.5, 1000.0)) // 500 m over
        assertTrue(outlines.isClearOf("KS", 38.5, TestData.KS_CO_LON - oneKmInDegrees * 1.5, 1000.0)) // 1.5 km over
        assertTrue(outlines.isClearOf("ZZ", 38.5, TestData.KS_CO_LON, 1000.0))
    }

    @Test
    fun `a polygon with a hole and a multipolygon are read`() {
        val json = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","properties":{"postal":"AA"},"geometry":{"type":"Polygon","coordinates":[
                [[0,0],[10,0],[10,10],[0,10],[0,0]],
                [[4,4],[6,4],[6,6],[4,6],[4,4]]]}},
              {"type":"Feature","properties":{"postal":"BB"},"geometry":{"type":"MultiPolygon","coordinates":[
                [[[20,0],[30,0],[30,10],[20,10],[20,0]]],
                [[[40,0],[50,0],[50,10],[40,10],[40,0]]]]}}
            ]}
        """.trimIndent()
        val o = StateOutlines.parse(json)
        assertEquals("AA", o.stateAt(2.0, 2.0))
        assertNull("the hole", o.stateAt(5.0, 5.0))
        assertEquals("BB", o.stateAt(5.0, 25.0))
        assertEquals("BB", o.stateAt(5.0, 45.0))
        assertNull(o.stateAt(5.0, 35.0))
        assertTrue(o.isClearOf("AA", 5.0, 25.0, 1000.0))
        assertTrue("the middle of the hole is far from its rim", o.isClearOf("AA", 5.0, 5.0, 1000.0))
        assertFalse("the hole's rim counts as the border", o.isClearOf("AA", 5.0, 4.001, 1000.0))
    }

    @Test
    fun `malformed outlines are refused`() {
        for (bad in listOf(
            "not json",
            "[]",
            """{"features":[]}""",
            """{"features":[{"properties":{},"geometry":{"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,0]]]}}]}""",
            """{"features":[{"properties":{"postal":"AA"},"geometry":{"type":"Point","coordinates":[0,0]}}]}""",
            """{"features":[{"properties":{"postal":"AA"},"geometry":{"type":"Polygon","coordinates":[[[0,0],[1,0],[0,0]]]}}]}""",
            """{"features":[{"properties":{"postal":"AA"},"geometry":{"type":"Polygon","coordinates":[[[0,0],[1,0],[1,99],[0,0]]]}}]}""",
        )) {
            try {
                StateOutlines.parse(bad)
                fail("accepted: $bad")
            } catch (expected: IllegalArgumentException) {
                // refused
            }
        }
    }

    @Test
    fun `parsing and looking up all fifty-one states is quick`() {
        val text = StateData.read("state-outlines.geojson")
        val t0 = System.nanoTime()
        val parsed = StateOutlines.parse(text)
        val parseMs = (System.nanoTime() - t0) / 1e6
        val t1 = System.nanoTime()
        var found = 0
        repeat(100) { TestData.capitals.forEach { (_, at) -> if (parsed.stateAt(at.first, at.second) != null) found++ } }
        val lookupMs = (System.nanoTime() - t1) / 1e6
        println("state-outlines.geojson: ${text.length / 1024} KB, parse ${"%.1f".format(parseMs)} ms, " +
            "${found} lookups (all 51 states x 100) ${"%.1f".format(lookupMs)} ms")
        assertEquals(5100, found)
        assertTrue("parse took ${parseMs} ms", parseMs < 5_000)
    }
}
