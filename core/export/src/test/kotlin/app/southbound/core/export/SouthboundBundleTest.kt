package app.southbound.core.export

import app.southbound.core.export.fwx.FwxError
import app.southbound.core.export.fwx.FwxException
import app.southbound.core.export.fwx.FwxWriter
import app.southbound.core.network.ApiKey
import app.southbound.core.network.NetworkFeature
import app.southbound.core.routing.RoutePrefs
import app.southbound.core.userdata.PlaceRef
import app.southbound.core.userdata.UserData
import app.southbound.core.userdata.UserDataCodec
import app.southbound.core.voice.VoiceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SouthboundBundleTest {

    private val pass = Samples.PASSPHRASE.toCharArray()

    private fun file(entries: Map<String, ByteArray> = SouthboundBundle.encode(Samples.data(), "0.1.14")): ByteArray {
        val out = ByteArrayOutputStream()
        SouthboundBundle.write(out, pass, entries, 1_791_028_800_000L)
        return out.toByteArray()
    }

    /** A valid FWX file with the given entries, whatever they hold: for contents the importer must refuse. */
    private fun raw(vararg entries: Pair<String, ByteArray>, appId: String = SouthboundBundle.APP_ID, schema: Long = 1): ByteArray {
        val out = ByteArrayOutputStream()
        val w = FwxWriter(out, appId, schema, 1_791_028_800_000L, pass)
        for ((name, bytes) in entries) w.entry(name, bytes)
        w.finish()
        return out.toByteArray()
    }

    private fun code(block: () -> Unit): FwxError {
        try {
            block()
        } catch (e: FwxException) {
            return e.code
        }
        fail("accepted")
        error("unreachable")
    }

    private fun read(bytes: ByteArray) = SouthboundBundle.readEntries(ByteArrayInputStream(bytes), pass)

    private fun text(entries: Map<String, ByteArray>, name: String) = String(entries.getValue(name), Charsets.UTF_8)

    /** The good entries with [old] replaced by [new] in [entry]. */
    private fun mutated(entry: String, old: String, new: String): Map<String, ByteArray> {
        val entries = SouthboundBundle.encode(Samples.data(), "0.1.14")
        val t = text(entries, entry)
        assertTrue("$old is not in $entry", old in t)
        entries[entry] = t.replace(old, new).toByteArray(Charsets.UTF_8)
        return entries
    }

    private fun refuses(entries: Map<String, ByteArray>) = assertEquals(FwxError.MALFORMED_PAYLOAD, code { SouthboundBundle.decode(entries) })

    // ---- The format ----------------------------------------------------------------------------

    @Test
    fun `entries are the four documented ones, manifest first`() {
        val entries = SouthboundBundle.encode(Samples.data(), "0.1.14")
        assertEquals(listOf("manifest.json", "places.json", "api-keys.json", "settings.json"), entries.keys.toList())
        assertEquals(
            """{"app":"southbound","schema":9,"app_version":"0.1.14","lists":3,"saved_places":3,"recents":2,"home":1,"work":1,""" +
                """"api_keys":3,"settings":76,"wifi_marks":2}""",
            text(entries, "manifest.json"),
        )
        assertTrue(text(entries, "places.json").startsWith("""{"version":1,"""))
        // Only the keys that are set, under their names.
        assertEquals(
            """{"MTA_BUS_TIME":"${Samples.BUS_KEY}","NY_511":"511-key-abcdef","GITHUB_RELEASES":"${Samples.GH_TOKEN}"}""",
            text(entries, "api-keys.json"),
        )
    }

    @Test
    fun `entries decode back to the same data`() {
        val data = Samples.data()
        val back = SouthboundBundle.decode(SouthboundBundle.encode(data, "0.1.14"))
        assertEquals(data.userData, back.userData)
        assertEquals(data.apiKeys, back.apiKeys)
        assertEquals(data.settings, back.settings)
        assertEquals(data.counts(), back.counts())
        // Encoding is deterministic, so the bytes are the same too.
        assertEquals(
            SouthboundBundle.encode(data, "0.1.14").mapValues { it.value.toList() },
            SouthboundBundle.encode(back, "0.1.14").mapValues { it.value.toList() },
        )
    }

    @Test
    fun `marks and the Wi-Fi switch round trip through the bundle`() {
        val data = Samples.data()
        assertEquals(2, data.userData.wifiMarks.size)
        assertTrue(data.settings.wifi)
        val entries = SouthboundBundle.encode(data, "0.1.61")
        assertTrue(
            text(entries, "places.json").contains(
                """"wifiMarks":[{"osmRef":"${Samples.WIFI_WORKED}","worked":true,"markedAt":600},{"osmRef":"${Samples.WIFI_FAILED}","worked":false,"markedAt":700}]""",
            ),
        )
        assertTrue(text(entries, "settings.json").contains(""""packs":{"regions":["li","njn","nyc"],"wifi":true}"""))
        // Through the file and the reader, the way an import goes.
        val back = SouthboundBundle.decode(read(file(entries)))
        assertEquals(data.userData.wifiMarks, back.userData.wifiMarks)
        assertEquals(data.userData, back.userData)
        assertTrue(back.settings.wifi)
        assertEquals(2, back.counts().wifiMarks)
        // None and off round trip too: the field is always written, as an empty list.
        val none = SouthboundBundle.encode(Samples.emptyData(), "x")
        assertTrue(text(none, "places.json").contains(""""wifiMarks":[]"""))
        assertTrue(text(none, "manifest.json").endsWith(""""wifi_marks":0}"""))
        assertEquals(emptyList<Any>(), SouthboundBundle.decode(none).userData.wifiMarks)
        // A mark is only the spot's reference, an answer and a time: nothing else about where it was is in the file.
        assertFalse(Samples.contains(file(entries), Samples.WIFI_WORKED))
    }

    @Test
    fun `empty data and defaults round trip`() {
        val back = SouthboundBundle.decode(SouthboundBundle.encode(Samples.emptyData(), "x"))
        assertEquals(UserData(), back.userData)
        assertTrue(back.apiKeys.isEmpty())
        assertEquals(Samples.defaultSettings(), back.settings)
    }

    @Test
    fun `a file round trips through the header and the reader`() {
        val entries = SouthboundBundle.encode(Samples.data(), "0.1.14")
        val bytes = file(entries)
        val header = SouthboundBundle.readHeader(ByteArrayInputStream(bytes))
        assertEquals("southbound", header.appId)
        assertEquals(9L, header.schemaVersion)
        assertEquals(1_791_028_800_000L, header.createdMs)
        assertFalse(header.verified)
        assertEquals(entries.mapValues { it.value.toList() }, read(bytes).mapValues { it.value.toList() })
        assertTrue(SouthboundBundle.verify(ByteArrayInputStream(bytes), pass, entries))
        // Nothing readable in the file: the places, the names and the keys are all sealed.
        assertFalse(Samples.contains(bytes, Samples.CAFE_NAME))
        assertFalse(Samples.contains(bytes, Samples.BUS_KEY))
        assertFalse(Samples.contains(bytes, "MTA_BUS_TIME"))
    }

    @Test
    fun `file name is the UTC time`() {
        assertEquals("southbound-20261003-120000Z.fwx", SouthboundBundle.fileName(1_791_028_800_000L))
    }

    @Test
    fun `verify is false for a file that opens but differs`() {
        val bytes = file()
        val other = SouthboundBundle.encode(Samples.emptyData(), "0.1.14")
        assertFalse(SouthboundBundle.verify(ByteArrayInputStream(bytes), pass, other))
    }

    // ---- Files that must be refused, each before anything could change ------------------------------

    @Test
    fun `another app's bundle is refused by name before the passphrase`() {
        val bytes = raw("manifest.json" to "{}".toByteArray(), appId = "prikey")
        val e = try {
            SouthboundBundle.readHeader(ByteArrayInputStream(bytes))
            null
        } catch (e: FwxException) {
            e
        }
        assertEquals(FwxError.WRONG_APP, e?.code)
        assertEquals("Prikey", SouthboundBundle.appName(e?.otherAppId.orEmpty()))
        assertEquals(FwxError.WRONG_APP, code { read(bytes) })
        for ((id, name) in listOf("tunnels" to "Tunnels", "lumen" to "Lumen", "mardigras" to "Mardi Gras", "pusher" to "Pusher", "pusher-server" to "Pusher server")) {
            assertEquals(name, SouthboundBundle.appName(id))
        }
    }

    /** Samples.data() without its marks: what any bundle before schema 9 imports as. */
    private val NO_MARKS = Samples.data().userData.copy(wifiMarks = emptyList())

    /**
     * The entries as the schema 8 writer wrote them (v0.1.5x): schema 8 in the manifest, no `wifi_marks` count, no
     * `wifiMarks` in places.json, no `wifi` in the packs group, 75 settings. Derived from today's writer by taking
     * out exactly what schema 9 added; the older-schema tests below strip further from here.
     */
    private fun schema8(version: String): LinkedHashMap<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        for ((name, bytes) in SouthboundBundle.encode(Samples.data(), version)) {
            val t = String(bytes, Charsets.UTF_8)
                .replace("\"schema\":9", "\"schema\":8")
                .replace("\"settings\":76", "\"settings\":75")
                .replace(",\"wifi_marks\":2", "")
                .replace(",\"wifi\":true", "")
                .replace(""","wifiMarks":[{"osmRef":"${Samples.WIFI_WORKED}","worked":true,"markedAt":600},{"osmRef":"${Samples.WIFI_FAILED}","worked":false,"markedAt":700}]""", "")
            out[name] = t.toByteArray()
        }
        // The derivation took out all of it.
        for ((name, bytes) in out) {
            assertFalse(name, Samples.contains(bytes, "wifi"))
        }
        return out
    }

    @Test
    fun `a schema 8 file still imports, with no marks and the Wi-Fi switch off`() {
        val eight = schema8("0.1.60")
        // Exactly what the schema 8 writer produced for the manifest and the packs group.
        assertEquals(
            """{"app":"southbound","schema":8,"app_version":"0.1.60","lists":3,"saved_places":3,"recents":2,"home":1,"work":1,""" +
                """"api_keys":3,"settings":75}""",
            text(eight, "manifest.json"),
        )
        assertTrue(text(eight, "settings.json").contains("\"packs\":{\"regions\":[\"li\",\"njn\",\"nyc\"]}"))
        val bytes = raw(*eight.map { it.key to it.value }.toTypedArray(), schema = 8)
        assertEquals(8L, SouthboundBundle.readHeader(ByteArrayInputStream(bytes)).schemaVersion)
        val data = SouthboundBundle.decode(read(bytes))
        assertEquals(Samples.data().settings.copy(wifi = false), data.settings)
        assertEquals(NO_MARKS, data.userData)
        assertEquals(0, data.counts().wifiMarks)
        assertEquals(Samples.data().apiKeys, data.apiKeys)
        // The settings count a schema 8 manifest holds is one fewer than today's.
        assertEquals(76, SettingsEntry.count(Samples.settings()))
        assertEquals(75, SettingsEntry.count(data.settings, 8))
    }

    @Test
    fun `schema 8 is read with its own fields exactly`() {
        val nine = SouthboundBundle.encode(Samples.data(), "x")
        // Each schema 9 addition, alone in an otherwise schema 8 bundle, is refused.
        fun with(entry: String, text: String = String(nine.getValue(entry), Charsets.UTF_8)) =
            schema8("x").also { it[entry] = text.toByteArray() }
        refuses(with("places.json"))
        refuses(with("settings.json"))
        refuses(with("manifest.json", text(nine, "manifest.json").replace("\"schema\":9", "\"schema\":8")))
        // The reverse: a schema 9 bundle must have all three.
        refuses(mutated("places.json", ",\"wifiMarks\":[", ",\"wifiMarksX\":["))
        refuses(mutated("settings.json", ",\"wifi\":true", ""))
        refuses(mutated("manifest.json", ",\"wifi_marks\":2", ""))
        refuses(mutated("manifest.json", "\"wifi_marks\":2", "\"wifi_marks\":1"))
        refuses(mutated("manifest.json", "\"wifi_marks\":2", "\"wifi_marks\":true"))
    }

    @Test
    fun `a schema 8 header over a schema 9 body is refused`() {
        val nine = SouthboundBundle.encode(Samples.data(), "x")
        assertEquals(FwxError.MALFORMED_PAYLOAD, code { read(raw(*nine.map { it.key to it.value }.toTypedArray(), schema = 8)) })
    }

    @Test
    fun `a schema 1 file still imports, with DOT cameras not avoided`() {
        // A schema 1 export as v0.1.21 wrote it: schema 1 in the header and the manifest, 54 settings, the route
        // group without avoid_dot_cameras and the network group without the weather, 511PA and CTroads toggles.
        val four = schema8("0.1.21")
        val one = linkedMapOf<String, ByteArray>()
        for ((name, bytes) in four) {
            val t = String(bytes, Charsets.UTF_8)
                .replace(",\"search_at_bottom\":false", "")
                .replace("\"schema\":8", "\"schema\":1")
                .replace("\"settings\":75", "\"settings\":54")
                .replace("\"avoid_dot_cameras\":true,", "")
                .replace(",\"WEATHER\":true,\"TRAFFIC_511PA\":true,\"TRAFFIC_CTROADS\":true,\"TRAFFIC_CHART_MD\":true,\"TRAFFIC_DELDOT\":true,\"TRAFFIC_511WI\":true,\"TRAFFIC_TRAVEL_MIDWEST\":true,\"TRAFFIC_NDDOT\":true,\"TRAFFIC_SD511\":true,\"TRAFFIC_511IN\":true,\"TRAFFIC_511MN\":true,\"TRAFFIC_511IA\":true,\"TRAFFIC_511NE\":true,\"TRAFFIC_KANDRIVE\":true,\"TRAFFIC_MIDRIVE\":true,\"TRAFFIC_OHGO\":true,\"TRAFFIC_511VA\":true,\"TRAFFIC_WV511\":true,\"TRAFFIC_KYTC\":true", "")
            one[name] = t.toByteArray()
        }
        val bytes = raw(*one.map { it.key to it.value }.toTypedArray(), schema = 1)
        assertEquals(1L, SouthboundBundle.readHeader(ByteArrayInputStream(bytes)).schemaVersion)
        val data = SouthboundBundle.decode(read(bytes))
        val expected = Samples.data().settings.copy(wifi = false)
        assertEquals(
            expected.copy(searchAtBottom = true, route = expected.route.copy(avoidDotCameras = false), network = expected.network + newerOff(NetworkFeature.WEATHER)),
            data.settings,
        )
        assertEquals(NO_MARKS, data.userData)
    }

    @Test
    fun `a schema 2 file still imports, with weather off`() {
        // A schema 2 export as v0.1.22 wrote it: 55 settings and the network group without the weather, 511PA and
        // CTroads toggles.
        val four = schema8("0.1.22")
        val two = linkedMapOf<String, ByteArray>()
        for ((name, bytes) in four) {
            val t = String(bytes, Charsets.UTF_8)
                .replace(",\"search_at_bottom\":false", "")
                .replace("\"schema\":8", "\"schema\":2")
                .replace("\"settings\":75", "\"settings\":55")
                .replace(",\"WEATHER\":true,\"TRAFFIC_511PA\":true,\"TRAFFIC_CTROADS\":true,\"TRAFFIC_CHART_MD\":true,\"TRAFFIC_DELDOT\":true,\"TRAFFIC_511WI\":true,\"TRAFFIC_TRAVEL_MIDWEST\":true,\"TRAFFIC_NDDOT\":true,\"TRAFFIC_SD511\":true,\"TRAFFIC_511IN\":true,\"TRAFFIC_511MN\":true,\"TRAFFIC_511IA\":true,\"TRAFFIC_511NE\":true,\"TRAFFIC_KANDRIVE\":true,\"TRAFFIC_MIDRIVE\":true,\"TRAFFIC_OHGO\":true,\"TRAFFIC_511VA\":true,\"TRAFFIC_WV511\":true,\"TRAFFIC_KYTC\":true", "")
            two[name] = t.toByteArray()
        }
        val bytes = raw(*two.map { it.key to it.value }.toTypedArray(), schema = 2)
        val data = SouthboundBundle.decode(read(bytes))
        val expected = Samples.data().settings.copy(wifi = false)
        assertEquals(expected.copy(searchAtBottom = true, network = expected.network + newerOff(NetworkFeature.WEATHER)), data.settings)
    }

    @Test
    fun `a schema 3 file still imports, with 511PA and CTroads off`() {
        // A schema 3 export as v0.1.33 wrote it: 56 settings and the network group without the 511PA and CTroads toggles.
        val four = schema8("0.1.33")
        val three = linkedMapOf<String, ByteArray>()
        for ((name, bytes) in four) {
            val t = String(bytes, Charsets.UTF_8)
                .replace(",\"search_at_bottom\":false", "")
                .replace("\"schema\":8", "\"schema\":3")
                .replace("\"settings\":75", "\"settings\":56")
                .replace(",\"TRAFFIC_511PA\":true,\"TRAFFIC_CTROADS\":true,\"TRAFFIC_CHART_MD\":true,\"TRAFFIC_DELDOT\":true,\"TRAFFIC_511WI\":true,\"TRAFFIC_TRAVEL_MIDWEST\":true,\"TRAFFIC_NDDOT\":true,\"TRAFFIC_SD511\":true,\"TRAFFIC_511IN\":true,\"TRAFFIC_511MN\":true,\"TRAFFIC_511IA\":true,\"TRAFFIC_511NE\":true,\"TRAFFIC_KANDRIVE\":true,\"TRAFFIC_MIDRIVE\":true,\"TRAFFIC_OHGO\":true,\"TRAFFIC_511VA\":true,\"TRAFFIC_WV511\":true,\"TRAFFIC_KYTC\":true", "")
            three[name] = t.toByteArray()
        }
        val bytes = raw(*three.map { it.key to it.value }.toTypedArray(), schema = 3)
        assertEquals(3L, SouthboundBundle.readHeader(ByteArrayInputStream(bytes)).schemaVersion)
        val data = SouthboundBundle.decode(read(bytes))
        val expected = Samples.data().settings.copy(wifi = false)
        assertEquals(expected.copy(searchAtBottom = true, network = expected.network + newerOff()), data.settings)
        assertEquals(NO_MARKS, data.userData)
    }

    @Test
    fun `a schema 4 file still imports, with CHART and DelDOT off`() {
        // A schema 4 export as v0.1.34 wrote it: 58 settings and the network group without the CHART and DelDOT toggles.
        val five = schema8("0.1.34")
        val four = linkedMapOf<String, ByteArray>()
        for ((name, bytes) in five) {
            val t = String(bytes, Charsets.UTF_8)
                .replace(",\"search_at_bottom\":false", "")
                .replace("\"schema\":8", "\"schema\":4")
                .replace("\"settings\":75", "\"settings\":58")
                .replace(",\"TRAFFIC_CHART_MD\":true,\"TRAFFIC_DELDOT\":true,\"TRAFFIC_511WI\":true,\"TRAFFIC_TRAVEL_MIDWEST\":true,\"TRAFFIC_NDDOT\":true,\"TRAFFIC_SD511\":true,\"TRAFFIC_511IN\":true,\"TRAFFIC_511MN\":true,\"TRAFFIC_511IA\":true,\"TRAFFIC_511NE\":true,\"TRAFFIC_KANDRIVE\":true,\"TRAFFIC_MIDRIVE\":true,\"TRAFFIC_OHGO\":true,\"TRAFFIC_511VA\":true,\"TRAFFIC_WV511\":true,\"TRAFFIC_KYTC\":true", "")
            four[name] = t.toByteArray()
        }
        val bytes = raw(*four.map { it.key to it.value }.toTypedArray(), schema = 4)
        assertEquals(4L, SouthboundBundle.readHeader(ByteArrayInputStream(bytes)).schemaVersion)
        val data = SouthboundBundle.decode(read(bytes))
        val expected = Samples.data().settings.copy(wifi = false)
        val newest = (listOf(NetworkFeature.TRAFFIC_CHART_MD, NetworkFeature.TRAFFIC_DELDOT, NetworkFeature.TRAFFIC_511WI, NetworkFeature.TRAFFIC_TRAVEL_MIDWEST,
            NetworkFeature.TRAFFIC_NDDOT, NetworkFeature.TRAFFIC_SD511,
            NetworkFeature.TRAFFIC_511IN, NetworkFeature.TRAFFIC_511MN, NetworkFeature.TRAFFIC_511IA, NetworkFeature.TRAFFIC_511NE, NetworkFeature.TRAFFIC_KANDRIVE, NetworkFeature.TRAFFIC_MIDRIVE, NetworkFeature.TRAFFIC_OHGO) + SINCE_8)
            .associateWith { false }
        assertEquals(expected.copy(searchAtBottom = true, network = expected.network + newest), data.settings)
        assertEquals(NO_MARKS, data.userData)
    }

    @Test
    fun `a schema 5 file still imports, with 511WI and Travel Midwest off`() {
        // A schema 5 export as v0.1.35 wrote it: 60 settings and the network group without the 511WI and Travel Midwest toggles.
        val six = schema8("0.1.35")
        val five = linkedMapOf<String, ByteArray>()
        for ((name, bytes) in six) {
            val t = String(bytes, Charsets.UTF_8)
                .replace(",\"search_at_bottom\":false", "")
                .replace("\"schema\":8", "\"schema\":5")
                .replace("\"settings\":75", "\"settings\":60")
                .replace(",\"TRAFFIC_511WI\":true,\"TRAFFIC_TRAVEL_MIDWEST\":true,\"TRAFFIC_NDDOT\":true,\"TRAFFIC_SD511\":true,\"TRAFFIC_511IN\":true,\"TRAFFIC_511MN\":true,\"TRAFFIC_511IA\":true,\"TRAFFIC_511NE\":true,\"TRAFFIC_KANDRIVE\":true,\"TRAFFIC_MIDRIVE\":true,\"TRAFFIC_OHGO\":true,\"TRAFFIC_511VA\":true,\"TRAFFIC_WV511\":true,\"TRAFFIC_KYTC\":true", "")
            five[name] = t.toByteArray()
        }
        val bytes = raw(*five.map { it.key to it.value }.toTypedArray(), schema = 5)
        assertEquals(5L, SouthboundBundle.readHeader(ByteArrayInputStream(bytes)).schemaVersion)
        val data = SouthboundBundle.decode(read(bytes))
        val expected = Samples.data().settings.copy(wifi = false)
        val newest = (listOf(NetworkFeature.TRAFFIC_511WI, NetworkFeature.TRAFFIC_TRAVEL_MIDWEST, NetworkFeature.TRAFFIC_NDDOT, NetworkFeature.TRAFFIC_SD511,
            NetworkFeature.TRAFFIC_511IN, NetworkFeature.TRAFFIC_511MN, NetworkFeature.TRAFFIC_511IA, NetworkFeature.TRAFFIC_511NE, NetworkFeature.TRAFFIC_KANDRIVE, NetworkFeature.TRAFFIC_MIDRIVE, NetworkFeature.TRAFFIC_OHGO) + SINCE_8)
            .associateWith { false }
        assertEquals(expected.copy(searchAtBottom = true, network = expected.network + newest), data.settings)
        assertEquals(NO_MARKS, data.userData)
    }

    @Test
    fun `a schema 6 file still imports, with the search bar at the bottom`() {
        // A schema 6 export as v0.1.42 wrote it: 71 settings and the driving group without search_at_bottom.
        val seven = schema8("0.1.42")
        val six = linkedMapOf<String, ByteArray>()
        for ((name, bytes) in seven) {
            val t = String(bytes, Charsets.UTF_8)
                .replace(",\"search_at_bottom\":false", "")
                .replace("\"schema\":8", "\"schema\":6")
                .replace("\"settings\":75", "\"settings\":71")
                .replace(",\"TRAFFIC_511VA\":true,\"TRAFFIC_WV511\":true,\"TRAFFIC_KYTC\":true", "")
            six[name] = t.toByteArray()
        }
        val bytes = raw(*six.map { it.key to it.value }.toTypedArray(), schema = 6)
        assertEquals(6L, SouthboundBundle.readHeader(ByteArrayInputStream(bytes)).schemaVersion)
        val data = SouthboundBundle.decode(read(bytes))
        val expected = Samples.data().settings.copy(wifi = false)
        assertEquals(expected.copy(searchAtBottom = true, network = expected.network + SINCE_8.associateWith { false }), data.settings)
        assertEquals(NO_MARKS, data.userData)
    }

    @Test
    fun `a schema 7 file still imports, with Virginia, West Virginia and Kentucky off`() {
        // A schema 7 export as v0.1.51 wrote it: 72 settings and the network group without the three toggles schema 8 added.
        val eight = schema8("0.1.51")
        val seven = linkedMapOf<String, ByteArray>()
        for ((name, bytes) in eight) {
            val t = String(bytes, Charsets.UTF_8)
                .replace("\"schema\":8", "\"schema\":7")
                .replace("\"settings\":75", "\"settings\":72")
                .replace(",\"TRAFFIC_511VA\":true,\"TRAFFIC_WV511\":true,\"TRAFFIC_KYTC\":true", "")
            seven[name] = t.toByteArray()
        }
        val bytes = raw(*seven.map { it.key to it.value }.toTypedArray(), schema = 7)
        assertEquals(7L, SouthboundBundle.readHeader(ByteArrayInputStream(bytes)).schemaVersion)
        val data = SouthboundBundle.decode(read(bytes))
        val expected = Samples.data().settings.copy(wifi = false)
        assertTrue(expected.network.getValue(NetworkFeature.TRAFFIC_511VA))
        assertEquals(expected.copy(network = expected.network + SINCE_8.associateWith { false }), data.settings)
        assertEquals(NO_MARKS, data.userData)
    }

    @Test
    fun `a key newer than the bundle's schema is refused`() {
        val bytes = "{\"OHGO\":\"ohgo-key\"}".toByteArray()
        assertEquals(FwxError.MALFORMED_PAYLOAD, code { KeysEntry.decode(bytes, 5) })
        assertEquals(mapOf(ApiKey.OHGO to "ohgo-key"), KeysEntry.decode(bytes, 6))
        assertEquals(mapOf(ApiKey.EIA to "eia-key"), KeysEntry.decode("{\"EIA\":\"eia-key\"}".toByteArray(), 1))
    }

    @Test
    fun `a file whose header and manifest name different schemas is refused`() {
        // A schema 8 manifest in a file whose header says 7: the dialog would show one schema and the body be read as another.
        val eight = schema8("0.1.52")
        val bytes = raw(*eight.map { it.key to it.value }.toTypedArray(), schema = 7)
        assertEquals(FwxError.MALFORMED_PAYLOAD, code { read(bytes) })
    }

    /** The toggles newer than a schema 3 bundle, plus [also], all off. */
    private fun newerOff(vararg also: NetworkFeature): Map<NetworkFeature, Boolean> =
        (listOf(NetworkFeature.TRAFFIC_511PA, NetworkFeature.TRAFFIC_CTROADS, NetworkFeature.TRAFFIC_CHART_MD, NetworkFeature.TRAFFIC_DELDOT,
                NetworkFeature.TRAFFIC_511WI, NetworkFeature.TRAFFIC_TRAVEL_MIDWEST, NetworkFeature.TRAFFIC_NDDOT, NetworkFeature.TRAFFIC_SD511,
            NetworkFeature.TRAFFIC_511IN, NetworkFeature.TRAFFIC_511MN, NetworkFeature.TRAFFIC_511IA, NetworkFeature.TRAFFIC_511NE, NetworkFeature.TRAFFIC_KANDRIVE, NetworkFeature.TRAFFIC_MIDRIVE, NetworkFeature.TRAFFIC_OHGO) + SINCE_8 + also)
            .associateWith { false }

    /** The toggles schema 8 added. */
    private val SINCE_8 = listOf(NetworkFeature.TRAFFIC_511VA, NetworkFeature.TRAFFIC_WV511, NetworkFeature.TRAFFIC_KYTC)

    @Test
    fun `a newer schema is refused from the header alone`() {
        val bytes = raw("manifest.json" to "{}".toByteArray(), schema = 10)
        assertEquals(FwxError.SCHEMA_TOO_NEW, code { SouthboundBundle.readHeader(ByteArrayInputStream(bytes)) })
    }

    @Test
    fun `a wrong passphrase is refused`() {
        assertEquals(
            FwxError.WRONG_PASSPHRASE,
            code { SouthboundBundle.readEntries(ByteArrayInputStream(file()), "correct horse battery stapl".toCharArray()) },
        )
    }

    @Test
    fun `a damaged or cut file is refused`() {
        val bytes = file()
        val flipped = bytes.copyOf().also { it[it.size - 40] = (it[it.size - 40].toInt() xor 1).toByte() }
        assertEquals(FwxError.DAMAGED, code { read(flipped) })
        assertEquals(FwxError.DAMAGED, code { read(bytes.copyOf(bytes.size - 1)) })
        assertEquals(FwxError.DAMAGED, code { read(bytes.copyOf(bytes.size / 2)) })
        assertEquals(FwxError.DAMAGED, code { read(bytes + byteArrayOf(0)) })
        assertEquals(FwxError.NOT_AN_EXPORT, code { read("not an export at all, just text".toByteArray()) })
    }

    @Test
    fun `entries out of order, missing, extra or too big are refused`() {
        val good = SouthboundBundle.encode(Samples.data(), "x")
        fun bundle(vararg names: String) = raw(*names.map { it to good.getValue(it) }.toTypedArray())
        assertEquals(FwxError.MALFORMED_PAYLOAD, code { read(bundle("manifest.json", "places.json", "api-keys.json")) })
        assertEquals(FwxError.MALFORMED_PAYLOAD, code { read(bundle("places.json", "manifest.json", "api-keys.json", "settings.json")) })
        assertEquals(
            FwxError.MALFORMED_PAYLOAD,
            code { read(raw(*(good.map { it.key to it.value } + ("extra.bin" to byteArrayOf(1))).toTypedArray())) },
        )
        assertEquals(FwxError.MALFORMED_PAYLOAD, code { read(raw("other.json" to "{}".toByteArray())) })
        // 17 KiB where api-keys.json may hold 16.
        val big = raw(
            "manifest.json" to good.getValue("manifest.json"),
            "places.json" to good.getValue("places.json"),
            "api-keys.json" to ByteArray(17 * 1024) { ' '.code.toByte() },
            "settings.json" to good.getValue("settings.json"),
        )
        assertEquals(FwxError.TOO_LARGE, code { read(big) })
    }

    @Test
    fun `a bundle that is not valid UTF-8 or JSON is refused`() {
        val e = SouthboundBundle.encode(Samples.data(), "x")
        e["settings.json"] = byteArrayOf(0x7b, 0xC3.toByte(), 0x28, 0x7d)
        refuses(e)
        val f = SouthboundBundle.encode(Samples.data(), "x")
        f["places.json"] = "{\"version\":1,".toByteArray()
        refuses(f)
    }

    // ---- Contents the importer must refuse (MALFORMED_PAYLOAD) ----------------------------------------

    @Test
    fun `manifest must match the contents`() {
        refuses(mutated("manifest.json", "\"saved_places\":3", "\"saved_places\":4"))
        refuses(mutated("manifest.json", "\"api_keys\":3", "\"api_keys\":2"))
        refuses(mutated("manifest.json", "\"settings\":76", "\"settings\":61"))
        refuses(mutated("manifest.json", "\"app\":\"southbound\"", "\"app\":\"prikey\""))
        refuses(mutated("manifest.json", "\"schema\":9", "\"schema\":10"))
        // Older settings have no Wi-Fi switch, Virginia, West Virginia or Kentucky toggle, search_at_bottom, avoid_dot_cameras, weather, 511PA, CTroads, CHART, DelDOT, 511WI or Travel Midwest toggle, so these settings don't fit an older manifest.
        refuses(mutated("manifest.json", "\"schema\":9", "\"schema\":8"))
        refuses(mutated("manifest.json", "\"schema\":9", "\"schema\":7"))
        refuses(mutated("manifest.json", "\"schema\":9", "\"schema\":6"))
        refuses(mutated("manifest.json", "\"schema\":9", "\"schema\":5"))
        refuses(mutated("manifest.json", "\"schema\":9", "\"schema\":4"))
        refuses(mutated("manifest.json", "\"schema\":9", "\"schema\":3"))
        refuses(mutated("manifest.json", "\"schema\":9", "\"schema\":2"))
        refuses(mutated("manifest.json", "\"schema\":9", "\"schema\":1"))
        refuses(mutated("manifest.json", "\"home\":1", "\"home\":true"))
        refuses(mutated("manifest.json", ",\"settings\":76", ""))
        refuses(mutated("manifest.json", "}", ",\"extra\":1}"))
    }

    @Test
    fun `places are checked strictly`() {
        refuses(mutated("places.json", "\"version\":1", "\"version\":2"))
        val cafeAt = "\"lat\":40.7,\"lon\":-74,\"address\":\"1 Main St\""
        refuses(mutated("places.json", cafeAt, "\"lat\":91,\"lon\":-74,\"address\":\"1 Main St\""))
        refuses(mutated("places.json", cafeAt, "\"lat\":40.7,\"lon\":-181,\"address\":\"1 Main St\""))
        refuses(mutated("places.json", cafeAt, "\"lat\":1e999,\"lon\":-74,\"address\":\"1 Main St\""))
        refuses(mutated("places.json", cafeAt, "\"lat\":\"40.7\",\"lon\":-74,\"address\":\"1 Main St\""))
        // A saved place in a list that does not exist.
        refuses(mutated("places.json", "\"list\":5", "\"list\":77"))
        // lastId below an id in use would hand out an id twice.
        refuses(mutated("places.json", "\"lastId\":6", "\"lastId\":2"))
        // Two lists with one id; a third built-in list; no built-in list.
        refuses(mutated("places.json", "{\"id\":5,\"name\":\"Pizza\"", "{\"id\":2,\"name\":\"Pizza\""))
        refuses(mutated("places.json", "\"name\":\"Pizza\",\"builtIn\":false", "\"name\":\"Pizza\",\"builtIn\":true"))
        refuses(mutated("places.json", "{\"id\":1,\"name\":\"Favorites\"", "{\"id\":9,\"name\":\"Favorites\""))
        refuses(mutated("places.json", "\"savedAt\":100", "\"savedAt\":-1"))
        refuses(mutated("places.json", "\"seenAt\":400", "\"seenAt\":\"later\""))
        refuses(mutated("places.json", "\"name\":\"Home\"", "\"name\":5"))
        // More recents than the app keeps.
        val many = (0 until 51).joinToString(",") { """{"seenAt":$it,"place":{"name":"p$it","lat":40.0,"lon":-74.0}}""" }
        val text = text(SouthboundBundle.encode(Samples.data(), "x"), "places.json")
        val start = text.indexOf("\"recents\":[") + "\"recents\":[".length
        val end = text.indexOf("]", start)
        val withMany = SouthboundBundle.encode(Samples.data(), "x")
        withMany["places.json"] = (text.substring(0, start) + many + text.substring(end)).toByteArray()
        refuses(withMany)
    }

    @Test
    fun `wifi marks are checked strictly`() {
        val worked = Samples.WIFI_WORKED
        refuses(mutated("places.json", "\"worked\":true", "\"worked\":\"true\""))
        refuses(mutated("places.json", "\"worked\":true", "\"worked\":1"))
        refuses(mutated("places.json", "\"markedAt\":600", "\"markedAt\":-1"))
        refuses(mutated("places.json", "\"markedAt\":600", "\"markedAt\":6.5"))
        refuses(mutated("places.json", "\"markedAt\":600", "\"markedAt\":\"yesterday\""))
        refuses(mutated("places.json", "\"osmRef\":\"$worked\"", "\"osmRef\":5"))
        refuses(mutated("places.json", "\"osmRef\":\"$worked\"", "\"osmRef\":\"\""))
        refuses(mutated("places.json", "\"osmRef\":\"$worked\"", "\"osmRef\":\"" + "n".repeat(PlacesEntry.MAX_OSM_REF + 1) + "\""))
        refuses(mutated("places.json", "\"osmRef\":\"$worked\"", "\"osmRef\":\"lone\\ud800surrogate\""))
        // Two marks for one spot (the app keeps one).
        refuses(mutated("places.json", "\"osmRef\":\"${Samples.WIFI_FAILED}\"", "\"osmRef\":\"$worked\""))
        // A missing field in a mark, the marks not a list, or the wrong count in the manifest.
        refuses(mutated("places.json", "\"worked\":true,", ""))
        refuses(mutated("places.json", "\"osmRef\":\"$worked\",", ""))
        refuses(mutated("places.json", "\"markedAt\":600", "\"markedAt\":null"))
        refuses(mutated("places.json", "\"wifiMarks\":[", "\"wifiMarks\":{\"a\":["))
        refuses(mutated("manifest.json", "\"wifi_marks\":2", "\"wifi_marks\":3"))
        // Export refuses the same: two marks for one spot, a negative time, an empty or over-long reference.
        fun refused(marks: List<app.southbound.core.userdata.WifiMark>) = assertEquals(
            FwxError.MALFORMED_PAYLOAD,
            code { SouthboundBundle.encode(BackupData(UserData(wifiMarks = marks), emptyMap(), Samples.defaultSettings()), "v") },
        )
        fun mark(ref: String = "n1", at: Long = 1) = app.southbound.core.userdata.WifiMark(ref, true, at)
        refused(listOf(mark(), mark()))
        refused(listOf(mark(at = -1)))
        refused(listOf(mark(ref = "")))
        refused(listOf(mark(ref = "n".repeat(PlacesEntry.MAX_OSM_REF + 1))))
        refused(listOf(mark(ref = "lone \ud800 surrogate")))
        refused((0..PlacesEntry.MAX_WIFI_MARKS).map { mark(ref = "n$it") })
    }

    @Test
    fun `places are read strictly, never defaulted or silently dropped`() {
        // An unknown field, anywhere.
        refuses(mutated("places.json", "\"lastId\":6", "\"lastId\":6,\"extra\":1"))
        refuses(mutated("places.json", "\"osmRef\":\"n1\"", "\"osmRef\":\"n1\",\"x\":1"))
        // A schema 9 file with an unknown field in a mark, or beside the marks.
        refuses(mutated("places.json", "\"markedAt\":600", "\"markedAt\":600,\"x\":1"))
        refuses(mutated("places.json", "\"wifiMarks\":[", "\"wifiMarks2\":1,\"wifiMarks\":["))
        refuses(mutated("places.json", "\"lastId\":6", "\"lastId\":6,\"notes\":[]"))
        refuses(mutated("places.json", "\"builtIn\":true", "\"builtIn\":true,\"x\":1"))
        refuses(mutated("places.json", "\"savedAt\":100", "\"savedAt\":100,\"x\":1"))
        // A value of the wrong type is not nulled, zeroed or coerced.
        refuses(mutated("places.json", "\"address\":\"1 Main St\"", "\"address\":42"))
        refuses(mutated("places.json", "\"category\":\"cafe\"", "\"category\":[]"))
        refuses(mutated("places.json", "\"builtIn\":true", "\"builtIn\":1"))
        refuses(mutated("places.json", "\"name\":\"Favorites\"", "\"name\":null"))
        refuses(mutated("places.json", "\"home\":{", "\"home\":null,\"x\":{"))
        // Versions and numbers.
        refuses(mutated("places.json", "\"version\":1", "\"version\":-7"))
        refuses(mutated("places.json", "\"version\":1", "\"version\":0"))
        refuses(mutated("places.json", "\"version\":1", "\"version\":1.0"))
        refuses(mutated("places.json", "\"version\":1", "\"version\":\"1\""))
        refuses(mutated("places.json", "\"savedAt\":100", "\"savedAt\":100.5"))
        refuses(mutated("places.json", "\"id\":3,", "\"id\":3.5,"))
        refuses(mutated("places.json", "\"lastId\":6", "\"lastId\":6.5"))
        // A missing field is not defaulted.
        refuses(mutated("places.json", "\"lastId\":6,", ""))
        refuses(mutated("places.json", "\"lists\":[", "\"xlists\":["))
        refuses(mutated("places.json", "\"recents\":[", "\"xrecents\":["))
        refuses(mutated("places.json", "\"address\":\"1 Main St\",", ""))
        refuses(mutated("places.json", "\"list\":1,", ""))
        // The same key twice.
        refuses(mutated("places.json", "\"lastId\":6", "\"lastId\":6,\"lastId\":6"))
        refuses(mutated("places.json", "\"name\":\"Pizza\"", "\"name\":\"Pizza\",\"name\":\"Other\""))
        // The unmodified text still reads.
        SouthboundBundle.decode(SouthboundBundle.encode(Samples.data(), "x"))
    }

    @Test
    fun `a key named twice is refused in every entry`() {
        refuses(mutated("settings.json", "\"driving\":{\"night_by_sunset\":false,\"search_at_bottom\":false}", "\"driving\":{\"night_by_sunset\":false,\"search_at_bottom\":false},\"driving\":{\"night_by_sunset\":true,\"search_at_bottom\":false}"))
        refuses(mutated("settings.json", "\"search_at_bottom\":false", "\"search_at_bottom\":false,\"search_at_bottom\":true"))
        refuses(mutated("settings.json", "\"night_by_sunset\":false", "\"night_by_sunset\":false,\"night_by_sunset\":true"))
        refuses(mutated("api-keys.json", "\"NY_511\":\"511-key-abcdef\"", "\"NY_511\":\"511-key-abcdef\",\"NY_511\":\"other\""))
        refuses(mutated("manifest.json", "\"home\":1", "\"home\":1,\"home\":1"))
    }

    @Test
    fun `api keys are checked strictly`() {
        refuses(mutated("api-keys.json", "\"NY_511\"", "\"NY_5111\""))
        refuses(mutated("api-keys.json", "\"511-key-abcdef\"", "\"\""))
        refuses(mutated("api-keys.json", "\"511-key-abcdef\"", "\" padded \""))
        refuses(mutated("api-keys.json", "\"511-key-abcdef\"", "\"has\\ttab\""))
        refuses(mutated("api-keys.json", "\"511-key-abcdef\"", "12"))
        refuses(mutated("api-keys.json", "\"511-key-abcdef\"", "\"" + "k".repeat(1025) + "\""))
        refuses(mutated("api-keys.json", "\"511-key-abcdef\"", "\"lone\\ud800surrogate\""))
        val entries = SouthboundBundle.encode(Samples.data(), "x")
        entries["api-keys.json"] = "[]".toByteArray()
        refuses(entries)
    }

    @Test
    fun `settings are checked strictly`() {
        refuses(mutated("settings.json", "\"updates\":{\"allow_mobile_data\":true}", "\"updates\":{}"))
        refuses(mutated("settings.json", ",\"updates\":{\"allow_mobile_data\":true}", ""))
        refuses(mutated("settings.json", "\"updates\":{\"allow_mobile_data\":true}", "\"updates\":{\"allow_mobile_data\":true},\"extra\":{}"))
        refuses(mutated("settings.json", "\"allow_mobile_data\":true", "\"allow_mobile_data\":true,\"x\":1"))
        refuses(mutated("settings.json", "\"allow_mobile_data\":true", "\"allow_mobile_data\":\"yes\""))
        refuses(mutated("settings.json", "\"vehicle\":\"TRUCK\"", "\"vehicle\":\"BOAT\""))
        refuses(mutated("settings.json", "\"height_ft\":11.5", "\"height_ft\":17"))
        refuses(mutated("settings.json", "\"height_ft\":11.5", "\"height_ft\":1e999"))
        refuses(mutated("settings.json", "\"mpg\":31.5", "\"mpg\":4"))
        refuses(mutated("settings.json", "\"price_per_gallon\":4.25", "\"price_per_gallon\":15.5"))
        refuses(mutated("settings.json", "\"speech_rate\":1.1", "\"speech_rate\":2.5"))
        refuses(mutated("settings.json", "\"speech_rate\":1.1", "\"speech_rate\":\"fast\""))
        refuses(mutated("settings.json", "\"engine\":\"BUILT_IN\"", "\"engine\":\"BOTH\""))
        refuses(mutated("settings.json", "\"regions\":[\"li\",\"njn\",\"nyc\"]", "\"regions\":[\"li\",\"li\"]"))
        refuses(mutated("settings.json", "\"regions\":[\"li\",\"njn\",\"nyc\"]", "\"regions\":[\"nys\"]"))
        refuses(mutated("settings.json", "\"regions\":[\"li\",\"njn\",\"nyc\"]", "\"regions\":[\"../x\"]"))
        refuses(mutated("settings.json", "\"regions\":[\"li\",\"njn\",\"nyc\"]", "\"regions\":\"nyc\""))
        refuses(mutated("settings.json", "\"PACK_DOWNLOADS\":false", "\"PACK_DOWNLOAD\":false"))
    }

    // ---- Export refuses what import refuses --------------------------------------------------------

    private fun place(name: String = "p", lat: Double = 40.0, lon: Double = -74.0, address: String? = null, category: String? = null, osmRef: String? = null) =
        PlaceRef(name, lat, lon, address, category, osmRef)

    private fun withSaved(p: PlaceRef) = BackupData(UserData().save(p, UserData.FAVORITES_ID, 1), emptyMap(), Samples.defaultSettings())

    /** Every field's limit: the longest value goes through export and import, one more is refused by export. */
    @Test
    fun `export enforces the same limits import does`() {
        val limits = listOf<Triple<String, (String) -> BackupData, Int>>(
            Triple("name", { withSaved(place(name = it)) }, PlacesEntry.MAX_NAME),
            Triple("address", { withSaved(place(address = it)) }, PlacesEntry.MAX_ADDRESS),
            Triple("category", { withSaved(place(category = it)) }, PlacesEntry.MAX_CATEGORY),
            Triple("osmRef", { withSaved(place(osmRef = it)) }, PlacesEntry.MAX_OSM_REF),
            Triple("list name", { BackupData(UserData().createList(it), emptyMap(), Samples.defaultSettings()) }, PlacesEntry.MAX_LIST_NAME),
            Triple("home", { BackupData(UserData(home = place(name = it)), emptyMap(), Samples.defaultSettings()) }, PlacesEntry.MAX_NAME),
            Triple("api key", { BackupData(UserData(), mapOf(ApiKey.EIA to it), Samples.defaultSettings()) }, KeysEntry.MAX_VALUE),
        )
        for ((what, make, max) in limits) {
            val longest = SouthboundBundle.encode(make("x".repeat(max)), "v")
            val back = SouthboundBundle.decode(longest)
            assertEquals("$what at its limit", make("x".repeat(max)).counts(), back.counts())
            val e = try {
                SouthboundBundle.encode(make("x".repeat(max + 1)), "v")
                null
            } catch (e: FwxException) {
                e
            }
            assertEquals("$what over its limit", FwxError.MALFORMED_PAYLOAD, e?.code)
            // The note names the rule and holds none of the contents.
            assertFalse(e!!.detail.contains("xxxx"))
        }
    }

    @Test
    fun `export refuses data import would refuse`() {
        fun refused(data: BackupData) = assertEquals(FwxError.MALFORMED_PAYLOAD, code { SouthboundBundle.encode(data, "v") })
        val s = Samples.defaultSettings()
        refused(withSaved(place(lat = 90.5)))
        refused(withSaved(place(lat = Double.NaN)))
        refused(withSaved(place(lon = Double.POSITIVE_INFINITY)))
        refused(withSaved(place(lon = -180.5)))
        refused(withSaved(place(name = "lone \ud800 surrogate")))
        refused(BackupData(UserData(), mapOf(ApiKey.EIA to ""), s))
        refused(BackupData(UserData(), mapOf(ApiKey.EIA to " padded"), s))
        refused(BackupData(UserData(), mapOf(ApiKey.EIA to "new\nline"), s))
        refused(BackupData(UserData(), emptyMap(), s.copy(regions = setOf("atlantis"))))
        refused(BackupData(UserData(), emptyMap(), s.copy(route = RoutePrefs(heightFt = 99.0))))
        refused(BackupData(UserData(), emptyMap(), s.copy(route = RoutePrefs(widthFt = Double.NaN))))
        refused(BackupData(UserData(), emptyMap(), s.copy(fuel = s.fuel.copy(mpg = 500.0))))
        refused(BackupData(UserData(), emptyMap(), s.copy(fuel = s.fuel.copy(pricePerGallon = Double.NaN))))
        refused(BackupData(UserData(), emptyMap(), s.copy(voice = VoiceSettings(speechRate = 3f))))
        refused(BackupData(UserData(), emptyMap(), s.copy(voice = VoiceSettings(speechRate = Float.NaN))))
        refused(BackupData(UserData(), emptyMap(), s.copy(network = emptyMap())))
        // lastId below an id in use, a saved place in no list, a built-in list gone.
        refused(BackupData(UserData(lastId = 1, saved = UserData().save(Samples.cafe, 1, 1).saved), emptyMap(), s))
        refused(BackupData(UserData(saved = UserData().save(Samples.cafe, 1, 1).saved.map { it.copy(listId = 9) }), emptyMap(), s))
        refused(BackupData(UserData(lists = UserData.DEFAULT_LISTS.take(1)), emptyMap(), s))
        // Too many recents or lists.
        refused(BackupData(UserData(recents = (0..UserData.MAX_RECENTS).map { app.southbound.core.userdata.RecentPlace(place("r$it"), it.toLong()) }), emptyMap(), s))
    }

    @Test
    fun `entries over their size limit are refused before writing`() {
        // 3,000 places with 4,096-character addresses is about 12 MB where places.json may hold 8 MiB.
        val address = "a".repeat(PlacesEntry.MAX_ADDRESS)
        var d = UserData()
        for (i in 0 until 3_000) d = d.save(place(name = "p$i", address = address, lat = 40.0 + i * 0.0001), UserData.FAVORITES_ID, i.toLong())
        val entries = SouthboundBundle.encode(BackupData(d, emptyMap(), Samples.defaultSettings()), "v")
        assertEquals(FwxError.TOO_LARGE, code { SouthboundBundle.checkSizes(entries) })
        assertEquals(FwxError.TOO_LARGE, code { SouthboundBundle.write(ByteArrayOutputStream(), pass, entries, 1L) })
    }

    @Test
    fun `a short passphrase is refused by the writer`() {
        val entries = SouthboundBundle.encode(Samples.emptyData(), "v")
        assertEquals(FwxError.BAD_PASSPHRASE, code { SouthboundBundle.write(ByteArrayOutputStream(), "eleven char".toCharArray(), entries, 1L) })
    }

    // ---- Things that must be bumped together with the schema ------------------------------------------

    /** If one of these fails, the bundle's contents changed: update docs/EXPORT.md and bump the schema (with a migration). */
    @Test
    fun `schema 9 pins what the stores hold`() {
        assertEquals(1, UserDataCodec.VERSION)
        assertEquals(50, UserData.MAX_RECENTS)
        assertEquals(listOf("MTA_BUS_TIME", "NY_511", "GITHUB_RELEASES", "EIA", "OHGO"), ApiKey.entries.map { it.name })
        assertEquals(
            listOf(
                "PACK_DOWNLOADS", "APP_UPDATES", "TRAFFIC_511NY", "TRAFFIC_NYC_DOT", "TRAFFIC_TOMTOM", "TRANSIT_REALTIME",
                "TRANSIT_BUS_TIME", "GAS_PRICES", "WEATHER", "TRAFFIC_511PA", "TRAFFIC_CTROADS", "TRAFFIC_CHART_MD", "TRAFFIC_DELDOT", "TRAFFIC_511WI", "TRAFFIC_TRAVEL_MIDWEST",
                "TRAFFIC_NDDOT", "TRAFFIC_SD511", "TRAFFIC_511IN", "TRAFFIC_511MN", "TRAFFIC_511IA", "TRAFFIC_511NE", "TRAFFIC_KANDRIVE", "TRAFFIC_MIDRIVE", "TRAFFIC_OHGO",
                "TRAFFIC_511VA", "TRAFFIC_WV511", "TRAFFIC_KYTC",
            ),
            NetworkFeature.entries.map { it.name },
        )
        fun fields(c: Class<*>) = c.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic }.size
        assertEquals(24, fields(VoiceSettings::class.java))
        // RoutePrefs: 12 of its own (schema 2 added avoidDotCameras), plus WalkPrefs (3) and BikePrefs (3) as 2 of the 12.
        assertEquals(12, fields(RoutePrefs::class.java))
        assertEquals(3, fields(app.southbound.core.routing.WalkPrefs::class.java))
        assertEquals(3, fields(app.southbound.core.routing.BikePrefs::class.java))
        assertEquals(76, SettingsEntry.count(Samples.settings()))
        // AppSettings: one field per group or setting the bundle carries (schema 7 added searchAtBottom, schema 9 wifi).
        assertEquals(9, fields(AppSettings::class.java))
        // UserData: lists, saved, recents, lastId, home, work, and from schema 9 wifiMarks; a WifiMark is osmRef, worked, markedAt.
        assertEquals(7, fields(UserData::class.java))
        assertEquals(3, fields(app.southbound.core.userdata.WifiMark::class.java))
        // BackupCounts: one count per manifest field after app_version (schema 9 added wifi_marks).
        assertEquals(8, fields(BackupCounts::class.java))
        assertEquals(2, UserData.DEFAULT_LISTS.size)
        assertEquals(1L..9L, SouthboundBundle.SCHEMA_RANGE)
    }

    @Test
    fun `toString never shows keys or places`() {
        val text = Samples.data().toString()
        assertFalse(text.contains(Samples.BUS_KEY))
        assertFalse(text.contains(Samples.GH_TOKEN))
        assertFalse(text.contains(Samples.CAFE_NAME))
        assertTrue(text.contains("apiKeys=3"))
    }

    @Test
    fun `wipe zeroes the buffers`() {
        val entries = SouthboundBundle.encode(Samples.data(), "v")
        SouthboundBundle.wipe(entries)
        assertTrue(entries.values.all { b -> b.all { it == 0.toByte() } })
    }
}
