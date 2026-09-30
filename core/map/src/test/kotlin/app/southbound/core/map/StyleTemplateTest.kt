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
