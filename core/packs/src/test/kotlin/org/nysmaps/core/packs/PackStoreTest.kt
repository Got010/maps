package org.nysmaps.core.packs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PackStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = PackStore(tmp.root)

    @Test
    fun basemapLivesInPacksDir() {
        val file = store().basemapFile("nyc")
        assertEquals("basemap-nyc.pmtiles", file.name)
        assertEquals(tmp.root.resolve("packs"), file.parentFile)
    }

    @Test
    fun missingWhenNoFile() {
        assertTrue(store().basemapStatus("nyc") is PackStatus.Missing)
    }

    @Test
    fun installedWhenHeaderIsPmtilesV3() {
        val store = store()
        store.packsDir.mkdirs()
        store.basemapFile("nyc").writeBytes("PMTiles".toByteArray() + byteArrayOf(3) + ByteArray(120))
        assertTrue(store.basemapStatus("nyc") is PackStatus.Installed)
    }

    @Test
    fun invalidWhenWrongVersion() {
        val store = store()
        store.packsDir.mkdirs()
        store.basemapFile("nyc").writeBytes("PMTiles".toByteArray() + byteArrayOf(2) + ByteArray(120))
        assertTrue(store.basemapStatus("nyc") is PackStatus.Invalid)
    }

    @Test
    fun invalidWhenNotPmtiles() {
        val store = store()
        store.packsDir.mkdirs()
        store.basemapFile("nyc").writeText("SQLite format 3")
        assertTrue(store.basemapStatus("nyc") is PackStatus.Invalid)
    }

    @Test
    fun invalidWhenTruncated() {
        val store = store()
        store.packsDir.mkdirs()
        store.basemapFile("nyc").writeText("PMT")
        assertTrue(store.basemapStatus("nyc") is PackStatus.Invalid)
    }
}
