package app.southbound.core.export

import app.southbound.core.network.NetworkFeature

import app.southbound.core.export.fwx.FwxError
import app.southbound.core.export.fwx.FwxException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SettingsEntryTest {
    /** The network toggles schema 6 added, as an export with every toggle on writes them (last in the group). */
    private val NEWER_6 = ""","TRAFFIC_511WI":true,"TRAFFIC_TRAVEL_MIDWEST":true,"TRAFFIC_NDDOT":true,"TRAFFIC_SD511":true,"TRAFFIC_511IN":true,"TRAFFIC_511MN":true,"TRAFFIC_511IA":true,"TRAFFIC_511NE":true,"TRAFFIC_KANDRIVE":true,"TRAFFIC_MIDRIVE":true,"TRAFFIC_OHGO":true"""

    /** The network toggles schemas 5 and 6 added. */
    private val NEWER_5 = ""","TRAFFIC_CHART_MD":true,"TRAFFIC_DELDOT":true""" + NEWER_6

    /** The network toggles schemas 4 to 6 added. */
    private val NEWER = ""","TRAFFIC_511PA":true,"TRAFFIC_CTROADS":true""" + NEWER_5

    /** The driving setting schema 7 added, as [Samples.settings] writes it. */
    private val NEWER_7 = ""","search_at_bottom":false"""

    /** What schemas 1 to 6 wrote for [s]: today's text without schema 7's field. */
    private fun upTo6(s: AppSettings): String = String(SettingsEntry.encode(s), Charsets.UTF_8).replace(NEWER_7, "")

    private val SINCE_4 = listOf(
        NetworkFeature.TRAFFIC_511PA, NetworkFeature.TRAFFIC_CTROADS, NetworkFeature.TRAFFIC_CHART_MD, NetworkFeature.TRAFFIC_DELDOT,
        NetworkFeature.TRAFFIC_511WI, NetworkFeature.TRAFFIC_TRAVEL_MIDWEST, NetworkFeature.TRAFFIC_NDDOT, NetworkFeature.TRAFFIC_SD511,
        NetworkFeature.TRAFFIC_511IN, NetworkFeature.TRAFFIC_511MN, NetworkFeature.TRAFFIC_511IA, NetworkFeature.TRAFFIC_511NE, NetworkFeature.TRAFFIC_KANDRIVE, NetworkFeature.TRAFFIC_MIDRIVE, NetworkFeature.TRAFFIC_OHGO,
    )

    /** The toggles newer than schema 3, plus [also], all off. */
    private fun off(vararg also: NetworkFeature): Map<NetworkFeature, Boolean> = (SINCE_4 + also).associateWith { false }

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
        assertEquals(72, SettingsEntry.count(Samples.defaultSettings()))
    }

    @Test
    fun `a schema 1 bundle reads with DOT cameras not avoided`() {
        val s = Samples.settings()
        // What schema 1 wrote: the same groups without the route group's avoid_dot_cameras or the weather, 511PA and
        // CTroads toggles.
        val one = upTo6(s).replace(""""avoid_dot_cameras":true,""", "")
            .replace(""","WEATHER":true""" + NEWER, "")
        assertTrue("avoid_dot_cameras" !in one)
        val back = SettingsEntry.decode(one.toByteArray(), schema = 1)
        assertEquals(s.copy(searchAtBottom = true, route = s.route.copy(avoidDotCameras = false), network = s.network + off(NetworkFeature.WEATHER)), back)
        // Schema 2 requires the field, and schema 1 refuses it.
        for ((text, schema) in listOf(one to 2L, String(SettingsEntry.encode(s), Charsets.UTF_8) to 1L)) {
            try {
                SettingsEntry.decode(text.toByteArray(), schema)
                fail("accepted schema $schema")
            } catch (e: FwxException) {
                assertEquals(FwxError.MALFORMED_PAYLOAD, e.code)
            }
        }
    }

    @Test
    fun `a schema 2 bundle reads with weather off`() {
        val s = Samples.settings()
        assertTrue(s.network.getValue(NetworkFeature.WEATHER))
        // What schema 2 wrote: the network group without the weather, 511PA and CTroads toggles.
        val two = upTo6(s).replace(""","WEATHER":true""" + NEWER, "")
        assertTrue("WEATHER" !in two)
        assertEquals(s.copy(searchAtBottom = true, network = s.network + off(NetworkFeature.WEATHER)), SettingsEntry.decode(two.toByteArray(), schema = 2))
        assertEquals(55, SettingsEntry.count(s, 2))
        assertEquals(54, SettingsEntry.count(s, 1))
        // Schema 3 requires it, and schema 2 refuses it.
        for ((text, schema) in listOf(two to 3L, String(SettingsEntry.encode(s), Charsets.UTF_8) to 2L)) {
            try {
                SettingsEntry.decode(text.toByteArray(), schema)
                fail("accepted schema $schema")
            } catch (e: FwxException) {
                assertEquals(FwxError.MALFORMED_PAYLOAD, e.code)
            }
        }
    }

    @Test
    fun `a schema 3 bundle reads with 511PA, CTroads, CHART and DelDOT off`() {
        val s = Samples.settings()
        assertTrue(s.network.getValue(NetworkFeature.TRAFFIC_511PA) && s.network.getValue(NetworkFeature.TRAFFIC_CTROADS))
        // What schema 3 wrote: the network group without the four newer toggles.
        val three = upTo6(s).replace(NEWER, "")
        assertTrue("TRAFFIC_511PA" !in three && "TRAFFIC_CTROADS" !in three && "TRAFFIC_DELDOT" !in three)
        assertEquals(s.copy(searchAtBottom = true, network = s.network + off()), SettingsEntry.decode(three.toByteArray(), schema = 3))
        assertEquals(56, SettingsEntry.count(s, 3))
        // Schema 4 requires them, and schema 3 refuses them.
        for ((text, schema) in listOf(three to 4L, String(SettingsEntry.encode(s), Charsets.UTF_8) to 3L)) {
            try {
                SettingsEntry.decode(text.toByteArray(), schema)
                fail("accepted schema $schema")
            } catch (e: FwxException) {
                assertEquals(FwxError.MALFORMED_PAYLOAD, e.code)
            }
        }
    }

    @Test
    fun `a schema 4 bundle reads with CHART and DelDOT off`() {
        val s = Samples.settings()
        assertTrue(s.network.getValue(NetworkFeature.TRAFFIC_CHART_MD) && s.network.getValue(NetworkFeature.TRAFFIC_DELDOT))
        // What schema 4 wrote: the network group without the two newest toggles.
        val four = upTo6(s).replace(NEWER_5, "")
        assertTrue("TRAFFIC_511PA" in four && "TRAFFIC_CHART_MD" !in four && "TRAFFIC_DELDOT" !in four && "TRAFFIC_511WI" !in four)
        val newest = SINCE_4.drop(2).associateWith { false }
        assertEquals(s.copy(searchAtBottom = true, network = s.network + newest), SettingsEntry.decode(four.toByteArray(), schema = 4))
        assertEquals(58, SettingsEntry.count(s, 4))
        // Schema 5 requires them, and schema 4 refuses them.
        for ((text, schema) in listOf(four to 5L, String(SettingsEntry.encode(s), Charsets.UTF_8) to 4L)) {
            try {
                SettingsEntry.decode(text.toByteArray(), schema)
                fail("accepted schema $schema")
            } catch (e: FwxException) {
                assertEquals(FwxError.MALFORMED_PAYLOAD, e.code)
            }
        }
    }

    @Test
    fun `a schema 5 bundle reads with schema 6's eleven toggles off`() {
        val s = Samples.settings()
        assertTrue(s.network.getValue(NetworkFeature.TRAFFIC_511WI) && s.network.getValue(NetworkFeature.TRAFFIC_TRAVEL_MIDWEST))
        val five = upTo6(s).replace(NEWER_6, "")
        assertTrue("TRAFFIC_DELDOT" in five && "TRAFFIC_511WI" !in five && "TRAFFIC_TRAVEL_MIDWEST" !in five && "TRAFFIC_SD511" !in five && "TRAFFIC_KANDRIVE" !in five && "TRAFFIC_MIDRIVE" !in five && "TRAFFIC_OHGO" !in five)
        val newest = SINCE_4.drop(4).associateWith { false }
        assertEquals(s.copy(searchAtBottom = true, network = s.network + newest), SettingsEntry.decode(five.toByteArray(), schema = 5))
        assertEquals(60, SettingsEntry.count(s, 5))
        for ((text, schema) in listOf(five to 6L, String(SettingsEntry.encode(s), Charsets.UTF_8) to 5L)) {
            try {
                SettingsEntry.decode(text.toByteArray(), schema)
                fail("accepted schema $schema")
            } catch (e: FwxException) {
                assertEquals(FwxError.MALFORMED_PAYLOAD, e.code)
            }
        }
    }

    @Test
    fun `a schema 6 bundle reads with the search bar at the bottom`() {
        val s = Samples.settings()
        assertTrue(!s.searchAtBottom)
        // What schema 6 wrote: the driving group with night_by_sunset alone.
        val six = upTo6(s)
        assertTrue("search_at_bottom" !in six && "TRAFFIC_OHGO" in six && "night_by_sunset" in six)
        assertEquals(s.copy(searchAtBottom = true), SettingsEntry.decode(six.toByteArray(), schema = 6))
        assertEquals(71, SettingsEntry.count(s, 6))
        assertEquals(72, SettingsEntry.count(s, 7))
        // Schema 7 requires it, and schema 6 refuses it.
        for ((text, schema) in listOf(six to 7L, String(SettingsEntry.encode(s), Charsets.UTF_8) to 6L)) {
            try {
                SettingsEntry.decode(text.toByteArray(), schema)
                fail("accepted schema $schema")
            } catch (e: FwxException) {
                assertEquals(FwxError.MALFORMED_PAYLOAD, e.code)
            }
        }
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
