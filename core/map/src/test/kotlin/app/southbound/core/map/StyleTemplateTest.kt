package app.southbound.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StyleTemplateTest {

    private val basemap = File("/data/user/0/app.southbound/files/packs/basemap-nyc.pmtiles")

    @Test
    fun urlUsesPmtilesFileScheme() {
        assertEquals(
            "pmtiles://file:///data/user/0/app.southbound/files/packs/basemap-nyc.pmtiles",
            StyleTemplate.pmtilesUrl(basemap),
        )
    }

    @Test
    fun resolveReplacesPlaceholder() {
        val resolved = StyleTemplate.resolve("""{"url":"{{PMTILES_URL}}"}""", basemap)
        assertEquals("""{"url":"${StyleTemplate.pmtilesUrl(basemap)}"}""", resolved)
    }

    @Test
    fun severalBasemapsGetASourceAndLayersEach() {
        val template = """{"version":8,"sources":{"openmaptiles":{"type":"vector","url":"{{PMTILES_URL}}"}},
            "layers":[{"id":"background","type":"background"},{"id":"water","type":"fill","source":"openmaptiles","source-layer":"water"},
            {"id":"road","type":"line","source":"openmaptiles","source-layer":"transportation"}]}"""
        val li = File("/packs/basemap-li.pmtiles")
        val resolved = StyleTemplate.resolve(template, listOf("nyc" to basemap, "li" to li))
        assertEquals(
            """{"version":8,"sources":{"openmaptiles@nyc":{"type":"vector","url":"${StyleTemplate.pmtilesUrl(basemap)}"},""" +
                """"openmaptiles@li":{"type":"vector","url":"${StyleTemplate.pmtilesUrl(li)}"}},""" +
                """"layers":[{"id":"background","type":"background"},""" +
                """{"id":"water@nyc","type":"fill","source":"openmaptiles@nyc","source-layer":"water"},""" +
                """{"id":"water@li","type":"fill","source":"openmaptiles@li","source-layer":"water"},""" +
                """{"id":"road@nyc","type":"line","source":"openmaptiles@nyc","source-layer":"transportation"},""" +
                """{"id":"road@li","type":"line","source":"openmaptiles@li","source-layer":"transportation"}]}""",
            resolved,
        )
        // One pack: the style is left as it is.
        assertEquals(StyleTemplate.resolve(template, basemap), StyleTemplate.resolve(template, listOf("nyc" to basemap)))
    }

    @Test
    fun bundledStylesSurviveTheRewrite() {
        for (name in listOf("light.json", "dark.json")) {
            val style = stylesDir().resolve(name).readText()
            val two = StyleTemplate.resolve(style, listOf("nyc" to basemap, "western" to File("/packs/basemap-western.pmtiles")))
            assertFalse(StyleTemplate.PMTILES_URL_PLACEHOLDER in two)
            assertTrue("@western" in two && "@nyc" in two)
            val layers = (app.southbound.core.network.MiniJson.parse(two) as Map<*, *>)["layers"] as List<*>
            val ids = layers.map { (it as Map<*, *>)["id"] }
            assertEquals(ids.size, ids.toSet().size)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun resolveRejectsStyleWithoutPlaceholder() {
        StyleTemplate.resolve("""{"url":"https://tiles.example.com"}""", basemap)
    }

    @Test
    fun bundledStylesAreLocalOnly() {
        for (name in listOf("light.json", "dark.json")) {
            val style = stylesDir().resolve(name).readText()
            assertTrue("$name lacks the pack placeholder", StyleTemplate.PMTILES_URL_PLACEHOLDER in style)
            assertFalse("$name references http", "http://" in style)
            assertFalse("$name references https", "https://" in style)
            assertTrue("$name must load glyphs from assets", "\"asset://glyphs/" in style)
        }
    }

    @Test
    fun bundledGlyphsCoverEveryFontInTheStyles() {
        val fonts = Regex(""""(NotoSans-[A-Za-z]+)"""")
        for (name in listOf("light.json", "dark.json")) {
            val used = fonts.findAll(stylesDir().resolve(name).readText()).map { it.groupValues[1] }.toSet()
            assertTrue(used.isNotEmpty())
            for (font in used) {
                assertTrue("missing glyphs for $font", stylesDir().resolve("glyphs/$font/0-255.pbf").isFile)
            }
        }
    }

    private fun stylesDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = dir.resolve("styles")
            if (candidate.resolve("light.json").isFile) return candidate
            dir = dir.parentFile
        }
        error("styles/ not found above ${System.getProperty("user.dir")}")
    }
}
