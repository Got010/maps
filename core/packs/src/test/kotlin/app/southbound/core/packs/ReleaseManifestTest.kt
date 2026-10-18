package app.southbound.core.packs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** The fixture is written by scripts/release-manifest.py, so the two stay in step. */
class ReleaseManifestTest {

    private val fixture = File("src/test/resources/southbound.json").readText()
    private val sha = "a".repeat(64)

    private fun parseFails(json: String, message: String) {
        try {
            ReleaseManifest.parse(json)
            fail("expected a ManifestException containing '$message'")
        } catch (e: ManifestException) {
            assertTrue(e.message, e.message!!.contains(message))
        }
    }

    private fun doc(packs: String = "[]", app: String = "null", version: String = "0.1.1", code: Int = 101, schema: Int = 1) =
        """{"schema":$schema,"version":"$version","versionCode":$code,"app":$app,"packs":$packs}"""

    @Test
    fun `reads the pipeline's manifest`() {
        val m = ReleaseManifest.parse(fixture)
        assertEquals("0.1.1", m.version)
        assertEquals(101L, m.versionCode)
        assertEquals("southbound-0.1.1.apk", m.app!!.name)
        assertEquals(3L, m.app!!.size)
        assertEquals(listOf(PackKind.BASEMAP, PackKind.TRANSIT, PackKind.VOICE), m.packs.map { it.kind })
        assertEquals("norman", m.pack(PackKind.VOICE, "norman")!!.region)
        assertEquals("nyc", m.packs[0].region)
        assertEquals("1d34761baf6b2ddf969d913d7f3fd59d3b4d46ef9fefec80a565e7f1ebeebc8e", m.pack(PackKind.BASEMAP, "nyc")!!.file.sha256)
        assertNull(m.pack(PackKind.ROUTING, "nyc"))
    }

    /** southbound-v2.json, written by scripts/release-manifest.py --v2 from a fake release (see the commit). */
    @Test
    fun `reads the v2 manifest with the regions each pack serves`() {
        val m = ReleaseManifest.parseV2(File("src/test/resources/southbound-v2.json").readText())
        assertNull(m.pack(PackKind.ROUTING, "nys")) // the one-graph pack isn't for a tile folder
        assertNull(m.pack(PackKind.SEARCH, "nys")) // New York's regions have their own, cut from it
        assertEquals(emptyList<String>(), m.pack(PackKind.ROUTING, "main")!!.regions)
        assertEquals(listOf("ct", "hudson", "li", "njn", "njs", "nyc"), m.pack(PackKind.ROUTING, "ct-hudson-li-njn-njs-nyc")!!.regions)
        assertEquals(listOf("hudson"), m.pack(PackKind.SEARCH, "hudson")!!.regions)
        assertEquals(listOf("ct"), m.pack(PackKind.SEARCH, "ct")!!.regions)
        assertNull(m.pack(PackKind.BASEMAP, "ct")!!.regions)
        assertNull(m.pack(PackKind.TRANSIT, "nyc")!!.regions)
        // southbound.json and southbound-v2.json aren't read as each other.
        parseFails(File("src/test/resources/southbound-v2.json").readText(), "schema 2")
        try {
            ReleaseManifest.parseV2(fixture)
            fail("read a schema 1 file as v2")
        } catch (e: ManifestException) {
            assertTrue(e.message!!.contains("schema 1"))
        }
    }

    @Test
    fun `v2 plan takes the packs naming a picked region and the shared one`() {
        val m = ReleaseManifest.parseV2(File("src/test/resources/southbound-v2.json").readText())
        fun wanted(vararg regions: String) =
            UpdatePlan.wanted(m, regions.toSet()).map { it.file.name }.sorted()
        assertEquals(
            listOf("basemap-ct.pmtiles", "routing-ct-hudson-li-njn-njs-nyc.tar", "routing-ct.tar", "routing-main.tar",
                "search-ct.sqlite", "transit-nyc.sqlite"),
            wanted("ct"),
        )
        assertEquals(
            listOf("routing-ct-hudson-li-njn-njs-nyc.tar", "routing-main.tar", "search-hudson.sqlite", "transit-nyc.sqlite"),
            wanted("hudson"),
        )
        assertEquals(
            listOf("basemap-nyc.pmtiles", "routing-ct-hudson-li-njn-njs-nyc.tar", "routing-main.tar", "search-hudson.sqlite",
                "search-nyc.sqlite", "transit-nyc.sqlite"),
            wanted("hudson", "nyc"),
        )
        // A bad region in a pack's list is refused.
        try {
            ReleaseManifest.parseV2(doc(schema = 2, packs = """[{"kind":"search","region":"ct","file":"search-ct.sqlite","size":5,"sha256":"$sha","regions":["../x"]}]"""))
            fail("accepted a bad region")
        } catch (e: ManifestException) {
            assertTrue(e.message!!.contains("bad region"))
        }
    }

    @Test
    fun `only a v2 routing pack may name several regions`() {
        val routing = """{"kind":"routing","region":"ct-hudson","file":"routing-ct-hudson.tar","size":5,"sha256":"$sha","regions":["ct","hudson"]}"""
        val search = """{"kind":"search","region":"ct-hudson","file":"search-ct-hudson.sqlite","size":5,"sha256":"$sha"}"""
        val v2 = ReleaseManifest.parseV2(doc(schema = 2, packs = "[$routing,$search]"))
        // The search pack would be stored as search-ct-hudson.sqlite, which PackStore never reads back: skipped.
        assertEquals(listOf(PackKind.ROUTING to "ct-hudson"), v2.packs.map { it.kind to it.region })
        val v1 = ReleaseManifest.parse(doc(packs = "[$search]"))
        assertTrue(v1.packs.isEmpty())
    }

    @Test
    fun `skips kinds this app doesn't know`() {
        val m = ReleaseManifest.parse(doc(packs = """[{"kind":"terrain","region":"nyc","file":"terrain-nyc.bin","size":5,"sha256":"$sha"}]"""))
        assertTrue(m.packs.isEmpty())
    }

    @Test
    fun `rejects unsafe or inconsistent documents`() {
        parseFails("[]", "not an object")
        parseFails("{", "not JSON")
        parseFails(doc(schema = 2), "schema 2")
        parseFails(doc(code = 102), "versionCode")
        parseFails(doc(version = "1.0"), "bad version")
        parseFails(doc(packs = """[{"kind":"basemap","region":"nyc","file":"../basemap-nyc.pmtiles","size":5,"sha256":"$sha"}]"""), "unsafe name")
        parseFails(doc(packs = """[{"kind":"basemap","region":"nyc","file":"routing-nyc.tar","size":5,"sha256":"$sha"}]"""), "unexpected pack name")
        parseFails(doc(packs = """[{"kind":"basemap","region":"NYC!","file":"basemap-NYC!.pmtiles","size":5,"sha256":"$sha"}]"""), "bad region")
        parseFails(doc(packs = """[{"kind":"basemap","region":"nyc","file":"basemap-nyc.pmtiles","size":5,"sha256":"xyz"}]"""), "sha256")
        parseFails(doc(packs = """[{"kind":"basemap","region":"nyc","file":"basemap-nyc.pmtiles","size":0,"sha256":"$sha"}]"""), "size 0")
        parseFails(doc(app = """{"file":"other.apk","size":5,"sha256":"$sha"}"""), "unexpected APK name")
        val one = """{"kind":"basemap","region":"nyc","file":"basemap-nyc.pmtiles","size":5,"sha256":"$sha"}"""
        parseFails(doc(packs = "[$one,$one]"), "duplicate")
    }

    @Test
    fun `plan fetches only changed packs and a newer app`() {
        val m = ReleaseManifest.parse(fixture)
        val basemapSha = m.pack(PackKind.BASEMAP, "nyc")!!.file.sha256
        val installed = mapOf((PackKind.BASEMAP to "nyc") to basemapSha, (PackKind.TRANSIT to "nyc") to "old")
        val plan = UpdatePlan.of(m, setOf("nyc"), installed, installedVersionCode = 100, includeApp = true)
        assertEquals(listOf(PackKind.TRANSIT), plan.packs.map { it.kind })
        assertEquals("southbound-0.1.1.apk", plan.app?.name)
        assertEquals(1L + 3L, plan.bytes)
    }

    @Test
    fun `plan skips the app when it's current or app updates are off`() {
        val m = ReleaseManifest.parse(fixture)
        assertNull(UpdatePlan.of(m, setOf("nyc"), emptyMap(), installedVersionCode = 101, includeApp = true).app)
        assertNull(UpdatePlan.of(m, setOf("nyc"), emptyMap(), installedVersionCode = 1, includeApp = false).app)
        assertEquals(2, UpdatePlan.of(m, setOf("nyc"), emptyMap(), installedVersionCode = 1, includeApp = false).packs.size)
        // No basemap region wanted: transit (not a basemap) still comes.
        assertEquals(listOf(PackKind.TRANSIT), UpdatePlan.of(m, emptySet(), emptyMap(), installedVersionCode = 101, includeApp = true).packs.map { it.kind })
    }

    @Test
    fun `plan fetches a voice pack only for a picked voice`() {
        val m = ReleaseManifest.parse(fixture)
        val none = UpdatePlan.of(m, setOf("nyc"), emptyMap(), installedVersionCode = 101, includeApp = false)
        assertTrue(none.packs.none { it.kind == PackKind.VOICE })
        val picked = UpdatePlan.of(m, setOf("nyc"), emptyMap(), installedVersionCode = 101, includeApp = false, voices = setOf("norman"))
        assertEquals(listOf("voice-norman.zip"), picked.packs.filter { it.kind == PackKind.VOICE }.map { it.file.name })
        // Another voice id fetches nothing; an installed, current one isn't fetched again.
        assertTrue(UpdatePlan.of(m, emptySet(), emptyMap(), installedVersionCode = 101, includeApp = false, voices = setOf("amy")).packs.none { it.kind == PackKind.VOICE })
        val current = mapOf((PackKind.VOICE to "norman") to m.pack(PackKind.VOICE, "norman")!!.file.sha256)
        assertTrue(UpdatePlan.of(m, emptySet(), current, installedVersionCode = 101, includeApp = false, voices = setOf("norman")).packs.none { it.kind == PackKind.VOICE })
        // Pack downloads off: no voice either.
        assertTrue(UpdatePlan.of(m, emptySet(), emptyMap(), wanted = emptySet(), installedVersionCode = 101, includeApp = false, voices = setOf("norman")).packs.isEmpty())
    }

    @Test
    fun `plan respects the wanted kinds`() {
        val m = ReleaseManifest.parse(fixture)
        val plan = UpdatePlan.of(m, setOf("nyc"), emptyMap(), wanted = setOf(PackKind.TRANSIT), installedVersionCode = 101, includeApp = false)
        assertEquals(listOf(PackKind.TRANSIT), plan.packs.map { it.kind })
    }

    @Test
    fun `plan takes a basemap per chosen region and the statewide packs`() {
        val one = { kind: String, region: String, ext: String ->
            """{"kind":"$kind","region":"$region","file":"$kind-$region.$ext","size":5,"sha256":"$sha"}"""
        }
        val m = ReleaseManifest.parse(
            doc(
                packs = "[" + listOf(
                    one("basemap", "nyc", "pmtiles"), one("basemap", "li", "pmtiles"), one("basemap", "western", "pmtiles"),
                    one("routing", "nys", "tar"), one("search", "nys", "sqlite"), one("transit", "nyc", "sqlite"),
                ).joinToString(",") + "]",
            ),
        )
        val plan = UpdatePlan.of(m, setOf("li", "western"), emptyMap(), installedVersionCode = 101, includeApp = false)
        assertEquals(
            listOf("basemap-li.pmtiles", "basemap-western.pmtiles", "routing-nys.tar", "search-nys.sqlite", "transit-nyc.sqlite"),
            plan.packs.map { it.file.name },
        )
        val installed = mapOf((PackKind.ROUTING to "nys") to sha, (PackKind.BASEMAP to "li") to sha)
        assertEquals(
            listOf("basemap-western.pmtiles", "search-nys.sqlite", "transit-nyc.sqlite"),
            UpdatePlan.of(m, setOf("li", "western"), installed, installedVersionCode = 101, includeApp = false).packs.map { it.file.name },
        )
    }

    @Test
    fun `plan fetches the Wi-Fi pack only when it is wanted`() {
        val one = { kind: String, region: String, ext: String ->
            """{"kind":"$kind","region":"$region","file":"$kind-$region.$ext","size":5,"sha256":"$sha"}"""
        }
        val m = ReleaseManifest.parseV2(
            doc(
                packs = "[" + listOf(one("basemap", "nyc", "pmtiles"), one("wifi", "us", "sqlite")).joinToString(",") + "]",
                schema = 2,
            ),
        )
        assertEquals("wifi-us.sqlite", m.pack(PackKind.WIFI, "us")!!.file.name)
        assertNull(m.pack(PackKind.WIFI, "us")!!.regions)
        // Off by default: no phone downloads it unasked, whatever regions are picked.
        val off = UpdatePlan.of(m, setOf("nyc"), emptyMap(), installedVersionCode = 101, includeApp = false)
        assertEquals(listOf("basemap-nyc.pmtiles"), off.packs.map { it.file.name })
        assertTrue(UpdatePlan.wanted(m, setOf("nyc")).none { it.kind == PackKind.WIFI })
        // On: fetched with no region picked at all, since the pack is national.
        val on = UpdatePlan.of(m, emptySet(), emptyMap(), installedVersionCode = 101, includeApp = false, wifi = true)
        assertEquals(listOf("wifi-us.sqlite"), on.packs.map { it.file.name })
        // An installed, current copy isn't fetched again; a changed one is.
        assertTrue(UpdatePlan.of(m, emptySet(), mapOf((PackKind.WIFI to "us") to sha), installedVersionCode = 101, includeApp = false, wifi = true).packs.isEmpty())
        assertEquals(1, UpdatePlan.of(m, emptySet(), mapOf((PackKind.WIFI to "us") to "old"), installedVersionCode = 101, includeApp = false, wifi = true).packs.size)
        // Pack downloads off: nothing, even when wanted.
        assertTrue(UpdatePlan.of(m, emptySet(), emptyMap(), wanted = emptySet(), installedVersionCode = 101, includeApp = false, wifi = true).packs.isEmpty())
        // "Get for this trip" sizes don't count the Wi-Fi pack, which no region brings.
        assertEquals(5L, UpdatePlan.addedBytes(m, emptySet(), setOf("nyc")))
    }

    @Test
    fun `a manifest without the Wi-Fi pack is fine`() {
        val m = ReleaseManifest.parse(fixture)
        assertNull(m.pack(PackKind.WIFI, "us"))
        assertTrue(UpdatePlan.of(m, setOf("nyc"), emptyMap(), installedVersionCode = 101, includeApp = false, wifi = true).packs.none { it.kind == PackKind.WIFI })
    }
}
