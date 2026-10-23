package app.southbound.core.states

import app.southbound.core.network.MiniJson
import java.io.File
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
        assertTrue(Field("k", "K", "Retired (verify).", "2026-01-01").needsVerify)
        assertFalse(Field("k", "K", "Verified by the state.", "2026-01-01").needsVerify)
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
    fun `the spoken line drops parenthesised groups and keeps the second limit`() {
        val card = StateCard("XX", "Testland", emptyList())
        val ca = Field("speed_limits", "Speed limits", "70 interstate rural (55 trucks), 65 urban.", "2026-01-01")
        assertEquals("Entering Testland. 70 interstate rural, 65 urban.", StateCards.spoken(card, listOf(ca)))
        val mo = Field(
            "speed_limits", "Speed limits",
            "75 interstate rural where posted from August 28, 2026 (was 70; Highway Commission sets segments), 60 urban.", "2026-01-01",
        )
        val said = StateCards.spoken(card, listOf(mo))
        assertEquals("Entering Testland. 75 interstate rural where posted from August 28, 2026, 60 urban.", said)
        assertFalse(said.contains("("))
        val handheld = Field("handheld_phone", "Handheld phone", "Handheld phone use banned for all drivers (secondary enforcement); texting banned.", "2026-01-01")
        assertEquals("Entering Testland. Handheld phone use banned for all drivers.", StateCards.spoken(card, listOf(handheld)))
    }

    @Test
    fun `the spoken line for the real CA and MO speed limits keeps both limits`() {
        val ca = StateCards.spoken(cards["CA"]!!, listOf(cards["CA"]!!.field("speed_limits")!!))
        assertEquals("Entering California. 70 interstate rural, 65 urban.", ca)
        val mo = StateCards.spoken(cards["MO"]!!, listOf(cards["MO"]!!.field("speed_limits")!!))
        assertTrue(mo, mo.contains("75 interstate rural") && mo.endsWith("60 urban."))
        assertFalse(mo.contains("("))
    }

    @Test
    fun `the shipped cards are the sourced copy minus the sources, so neither drifts`() {
        val shipped = MiniJson.parse(StateData.read("state-cards.json")) as Map<*, *>
        val sourced = MiniJson.parse(File("data/state-cards.sourced.json").readText()) as Map<*, *>
        assertEquals(sourced.keys.toList(), shipped.keys.toList())
        for ((code, record) in sourced) {
            val r = record as Map<*, *>
            assertTrue("$code has sources in the repo copy", (r["sources"] as? List<*>)?.isNotEmpty() == true)
            val withoutSources = LinkedHashMap<Any?, Any?>(r).also { it.remove("sources") }
            val s = shipped[code] as Map<*, *>
            assertFalse("$code ships sources", s.containsKey("sources"))
            assertEquals("$code", withoutSources.toList(), s.toList())
        }
    }

    @Test
    fun `no host literal ships in the card data`() {
        val text = StateData.read("state-cards.json")
        assertFalse(text.contains("http", ignoreCase = true))
        assertFalse(text.contains("www.", ignoreCase = true))
        assertEquals(null, StateData::class.java.getResource("/app/southbound/core/states/README.md"))
    }

    @Test
    fun `the headline is the two most important changed fields, a verify value after all others`() {
        fun f(key: String, value: String = "v") = Field(key, key, value, "2026-01-01")
        val changed = listOf(f("sales_tax"), f("tolls"), f("speed_limits"), f("handheld_phone", "Banned (verify)."), f("time_zone"))
        assertEquals(listOf("speed_limits", "tolls"), StateCards.headline(changed).map { it.key })
        assertEquals(listOf("speed_limits", "tolls", "sales_tax", "time_zone", "handheld_phone"), StateCards.headline(changed, 5).map { it.key })
        assertEquals(listOf("sales_tax"), StateCards.headline(listOf(f("sales_tax"))).map { it.key })
        assertTrue(StateCards.headline(emptyList()).isEmpty())
        val ny = cards["NY"]!!
        val oh = cards["OH"]!!
        val head = StateCards.headline(StateCards.diff(ny, oh))
        assertTrue(head.size in 1..2)
        for (field in head) assertTrue(StateCards.brief(field)!!.length <= 90)
        assertEquals("70 interstate rural, 65 urban", StateCards.brief(oh.field("speed_limits")!!))
    }

    @Test
    fun `the spoken line leaves out a clause that still needs checking and never runs long`() {
        val card = StateCard("XX", "Testland", emptyList())
        val verify = Field("handheld_phone", "Handheld phone", "Banned (verify details).", "2026-01-01")
        assertEquals("Entering Testland.", StateCards.spoken(card, listOf(verify)))
        val long = Field("speed_limits", "Speed limits", "word ".repeat(60).trim(), "2026-01-01")
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
