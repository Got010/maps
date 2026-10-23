package app.southbound.core.states

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StateCardsTest {

    private val cards get() = TestData.cards

    @Test
    fun `there are fifty-one cards, each with all twelve fields and no empty value`() {
        assertEquals(51, cards.all.size)
        assertEquals(12, StateCards.FIELDS.size)
        for (card in cards.all) {
            assertTrue("${card.code} has a name", card.name.isNotBlank())
            assertEquals(StateCards.FIELDS.map { it.first }, card.fields.map { it.key })
            for (f in card.fields) {
                assertTrue("${card.code} ${f.key} has a value", f.value.isNotBlank())
                assertTrue("${card.code} ${f.key} has a label", f.label.isNotBlank())
                assertTrue("${card.code} ${f.key} has sources", f.sources.isNotEmpty())
                assertTrue("${card.code} ${f.key} date ${f.asOf}", Regex("\\d{4}-\\d{2}-\\d{2}").matches(f.asOf))
            }
        }
    }

    @Test
    fun `the cards and the outlines cover the same fifty-one codes`() {
        assertEquals(TestData.outlines.codes.toSet(), cards.all.map { it.code }.toSet())
    }

    @Test
    fun `a card is found by postal code`() {
        assertEquals("Ohio", cards["OH"]?.name)
        assertEquals("District of Columbia", cards["DC"]?.name)
        assertEquals(null, cards["ZZ"])
        assertEquals(null, cards[null])
    }

    @Test
    fun `a value that says verify carries the verify tag`() {
        val tagged = cards.all.flatMap { it.fields }.filter { it.needsVerify }
        assertTrue(tagged.isNotEmpty())
        assertTrue(tagged.all { it.value.contains("verify", ignoreCase = true) })
        assertFalse(cards["OH"]!!.field("speed_limits")!!.needsVerify)
        assertTrue(Field("k", "K", "Retired (verify).", listOf("s"), "2026-01-01").needsVerify)
        assertFalse(Field("k", "K", "Verified by the state.", listOf("s"), "2026-01-01").needsVerify)
    }

    @Test
    fun `diff gives the fields that differ and none that match`() {
        val oh = cards["OH"]!!
        val ny = cards["NY"]!!
        val changed = StateCards.diff(ny, oh)
        assertTrue(changed.isNotEmpty())
        for (f in changed) assertTrue(f.value != ny.field(f.key)!!.value)
        // New York and Ohio say the same about handheld phones and daylight time, and differ on speed limits.
        assertTrue(changed.none { it.key == "handheld_phone" })
        assertTrue(changed.any { it.key == "speed_limits" })
        assertTrue(StateCards.diff(oh, oh).isEmpty())
        assertEquals(changed.map { it.value }, StateCards.diff(ny, oh).map { it.value })
    }

    @Test
    fun `diff is taken from the card entered`() {
        val va = cards["VA"]!!
        val md = cards["MD"]!!
        assertEquals("Maryland's wording", md.name, StateCards.diff(va, md).first().let { md.name })
        val radar = StateCards.diff(va, md).firstOrNull { it.key == "radar_detector" }
        assertNotNull("Virginia bans radar detectors, Maryland does not", radar)
        assertEquals(md.field("radar_detector")!!.value, radar!!.value)
    }

    @Test
    fun `the spoken line reads the first clause of the handheld and speed fields that changed`() {
        val oh = cards["OH"]!!
        val ny = cards["NY"]!!
        assertEquals("Entering Ohio. 70 interstate rural, 65 urban.", StateCards.spoken(oh, StateCards.diff(ny, oh)))
        val md = cards["MD"]!!
        assertEquals("Entering Maryland.", StateCards.spoken(md, emptyList()))
    }

    @Test
    fun `the spoken line leaves out a clause that still needs checking and never runs long`() {
        val card = StateCard("XX", "Testland", emptyList())
        val verify = Field("handheld_phone", "Handheld phone", "Banned (verify details).", listOf("s"), "2026-01-01")
        assertEquals("Entering Testland.", StateCards.spoken(card, listOf(verify)))
        val long = Field("speed_limits", "Speed limits", "word ".repeat(60).trim(), listOf("s"), "2026-01-01")
        val said = StateCards.spoken(card, listOf(long))
        assertTrue(said.length < 130)
        assertTrue(said.endsWith("word."))
    }

    @Test
    fun `malformed cards are refused`() {
        val good = StateData.read("state-cards.json")
        for (bad in listOf(
            "not json",
            "[]",
            "{}",
            good.replaceFirst("\"time_zone\"", "\"time_zone_\""),
            good.replaceFirst(Regex("\"sales_tax\": \"[^\"]*\""), "\"sales_tax\": \"\""),
            good.replaceFirst(Regex("\"as_of\": \"[^\"]*\""), "\"as_of\": 5"),
        )) {
            try {
                StateCards.parse(bad)
                fail("accepted a bad file")
            } catch (expected: IllegalArgumentException) {
                // refused
            }
        }
    }

    @Test
    fun `reading the cards is quick`() {
        val text = StateData.read("state-cards.json")
        val t0 = System.nanoTime()
        val parsed = StateCards.parse(text)
        val ms = (System.nanoTime() - t0) / 1e6
        println("state-cards.json: ${text.length / 1024} KB, parse ${"%.1f".format(ms)} ms")
        assertEquals(51, parsed.all.size)
        assertTrue("took $ms ms", ms < 5_000)
    }
}
