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
        assertEquals(listOf(PackKind.BASEMAP, PackKind.TRANSIT), m.packs.map { it.kind })
        assertEquals("nyc", m.packs[0].region)
        assertEquals("1d34761baf6b2ddf969d913d7f3fd59d3b4d46ef9fefec80a565e7f1ebeebc8e", m.pack(PackKind.BASEMAP, "nyc")!!.file.sha256)
        assertNull(m.pack(PackKind.ROUTING, "nyc"))
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
}
