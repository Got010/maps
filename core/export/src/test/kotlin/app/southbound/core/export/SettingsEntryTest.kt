package app.southbound.core.export

import app.southbound.core.export.fwx.FwxError
import app.southbound.core.export.fwx.FwxException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SettingsEntryTest {

    @Test
    fun `defaults and changed settings round trip`() {
        for (s in listOf(Samples.defaultSettings(), Samples.settings())) {
            assertEquals(s, SettingsEntry.decode(SettingsEntry.encode(s)))
        }
    }

    @Test
    fun `groups come in the documented order`() {
        val text = String(SettingsEntry.encode(Samples.defaultSettings()), Charsets.UTF_8)
        val groups = listOf("network", "route", "driving", "fuel", "voice", "packs", "updates").map { "\"$it\":{" }
        assertEquals(groups.map { text.indexOf(it) }.sorted(), groups.map { text.indexOf(it) })
        assertTrue(groups.all { it in text })
        assertTrue(text.startsWith("""{"network":{"PACK_DOWNLOADS":true,"APP_UPDATES":false,"""))
        assertTrue(""""packs":{"regions":["nyc"]},"updates":{"allow_mobile_data":false}}""" in text)
    }

    @Test
    fun `floats written by the stores survive the text`() {
        // The stores keep floats and hand out their doubles; the text must give the same float back.
        val route = Samples.settings().route.copy(heightFt = 3.7f.toDouble(), weightTons = 24.1f.toDouble())
        val s = Samples.settings().copy(route = route, voice = Samples.settings().voice.copy(speechRate = 0.7f))
        val back = SettingsEntry.decode(SettingsEntry.encode(s))
        assertEquals(3.7f, back.route.heightFt.toFloat(), 0f)
        assertEquals(24.1f, back.route.weightTons.toFloat(), 0f)
        assertEquals(0.7f, back.voice.speechRate, 0f)
    }

    @Test
    fun `the value count is the manifest's`() {
        assertEquals(54, SettingsEntry.count(Samples.defaultSettings()))
    }

    @Test
    fun `not an object is refused`() {
        for (bad in listOf("[]", "null", "5", "{}", """{"network":{}}""")) {
            try {
                SettingsEntry.decode(bad.toByteArray())
                fail("accepted $bad")
            } catch (e: FwxException) {
                assertEquals(FwxError.MALFORMED_PAYLOAD, e.code)
            }
        }
    }
}
