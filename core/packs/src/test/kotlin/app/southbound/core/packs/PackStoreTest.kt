package app.southbound.core.packs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PackStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val validPack = "PMTiles".toByteArray() + byteArrayOf(3) + ByteArray(120)

    private fun store() = PackStore(tmp.root.resolve("files"), tmp.root.resolve("external"))

    private fun write(dir: File, bytes: ByteArray): File {
        dir.mkdirs()
        return dir.resolve("basemap-nyc.pmtiles").apply { writeBytes(bytes) }
    }

    @Test
    fun missingPointsAtImportFolder() {
        val status = store().basemapStatus("nyc")
        assertTrue(status is PackStatus.Missing)
        assertEquals(tmp.root.resolve("external/packs/basemap-nyc.pmtiles"), status.file)
    }

    @Test
    fun missingFallsBackToPrivateFolderWithoutExternalStorage() {
        val status = PackStore(tmp.root.resolve("files"), null).basemapStatus("nyc")
        assertEquals(tmp.root.resolve("files/packs/basemap-nyc.pmtiles"), status.file)
    }

    @Test
    fun installedFromImportFolder() {
        val store = store()
        val file = write(store.importPacksDir!!, validPack)
        assertEquals(PackStatus.Installed(file), store.basemapStatus("nyc"))
    }

    @Test
    fun privateFolderWinsOverImportFolder() {
        val store = store()
        write(store.importPacksDir!!, validPack)
        val private = write(store.packsDir, validPack)
        assertEquals(PackStatus.Installed(private), store.basemapStatus("nyc"))
    }

    @Test
    fun validImportBeatsInvalidPrivateCopy() {
        val store = store()
        write(store.packsDir, "junk".toByteArray())
        val imported = write(store.importPacksDir!!, validPack)
        assertEquals(PackStatus.Installed(imported), store.basemapStatus("nyc"))
    }

    @Test
    fun invalidWhenWrongVersion() {
        val store = store()
        write(store.importPacksDir!!, "PMTiles".toByteArray() + byteArrayOf(2) + ByteArray(120))
        assertTrue(store.basemapStatus("nyc") is PackStatus.Invalid)
    }

    @Test
    fun invalidWhenNotPmtiles() {
        val store = store()
        write(store.importPacksDir!!, "SQLite format 3".toByteArray())
        assertTrue(store.basemapStatus("nyc") is PackStatus.Invalid)
    }

    @Test
    fun invalidWhenTruncated() {
        val store = store()
        write(store.importPacksDir!!, "PMT".toByteArray())
        assertTrue(store.basemapStatus("nyc") is PackStatus.Invalid)
    }

    @Test
    fun importCopiesIntoPrivateStorage() {
        val store = store()
        val status = store.importBasemap("nyc", validPack.inputStream())
        assertEquals(store.packsDir.resolve("basemap-nyc.pmtiles"), status.file)
        assertTrue(validPack.contentEquals(status.file.readBytes()))
        assertEquals(status, store.basemapStatus("nyc"))
        assertEquals(listOf("basemap-nyc.pmtiles"), store.packsDir.list()!!.toList())
    }

    @Test
    fun importReplacesAnOlderPack() {
        val store = store()
        write(store.packsDir, validPack)
        val newer = validPack + ByteArray(10)
        store.importBasemap("nyc", newer.inputStream())
        assertTrue(newer.contentEquals(store.packsDir.resolve("basemap-nyc.pmtiles").readBytes()))
    }

    @Test
    fun importRejectsNonPmtilesAndKeepsExistingPack() {
        val store = store()
        write(store.packsDir, validPack)
        try {
            store.importBasemap("nyc", "<html>not a map</html>".byteInputStream())
            fail("expected NotAPmtilesFileException")
        } catch (expected: NotAPmtilesFileException) {
        }
        assertTrue(validPack.contentEquals(store.packsDir.resolve("basemap-nyc.pmtiles").readBytes()))
        assertEquals(listOf("basemap-nyc.pmtiles"), store.packsDir.list()!!.toList())
    }

    // A pack shared from a browser, renamed or not, lands under its canonical name.
    @Test
    fun importPackRecognisesKindFromContents() {
        val store = store()
        val pack = store.importPack(validPack.inputStream(), "basemap-nyc (1).pmtiles")
        assertEquals(ImportedPack(PackKind.BASEMAP, "nyc", store.packsDir.resolve("basemap-nyc.pmtiles")), pack)
        assertEquals(PackStatus.Installed(pack.file), store.basemapStatus("nyc"))
        assertEquals(listOf("basemap-nyc.pmtiles"), store.packsDir.list()!!.toList())
    }

    @Test
    fun importPackFallsBackToDefaultRegionForUnknownNames() {
        val store = store()
        val pack = store.importPack(validPack.inputStream(), "download.bin")
        assertEquals(store.packsDir.resolve("basemap-nyc.pmtiles"), pack.file)
    }

    @Test
    fun importPackDetectsRoutingSearchAndTransit() {
        val store = store()
        assertEquals(PackKind.ROUTING, store.importPack(tar().inputStream(), "routing-nyc.tar").kind)
        val search = store.importPack(sqlite(PackKind.SEARCH_APPLICATION_ID).inputStream(), "search-nyc.sqlite")
        assertEquals(PackKind.SEARCH, search.kind)
        val transit = store.importPack(sqlite(PackKind.TRANSIT_APPLICATION_ID).inputStream(), null)
        assertEquals(store.packsDir.resolve("transit-nyc.sqlite"), transit.file)
        assertEquals(PackStatus.Installed(transit.file), store.status(PackKind.TRANSIT, "nyc"))
    }

    @Test
    fun importPackRejectsOtherFilesAndLeavesNothingBehind() {
        val store = store()
        for (bytes in listOf("<html>".toByteArray(), sqlite(applicationId = 0), ByteArray(0))) {
            try {
                store.importPack(bytes.inputStream(), "basemap-nyc.pmtiles")
                fail("expected UnrecognizedPackException")
            } catch (expected: UnrecognizedPackException) {
            }
        }
        assertEquals(emptyList<String>(), store.packsDir.list()!!.toList())
    }

    @Test
    fun regionFromName() {
        assertEquals("nyc", PackKind.regionFromName(PackKind.BASEMAP, "basemap-nyc.pmtiles"))
        assertEquals("li", PackKind.regionFromName(PackKind.BASEMAP, "Basemap-LI (2).pmtiles"))
        assertEquals("nyc", PackKind.regionFromName(PackKind.ROUTING, "routing-nyc-1.tar"))
        assertEquals(null, PackKind.regionFromName(PackKind.BASEMAP, "routing-nyc.tar"))
        assertEquals(null, PackKind.regionFromName(PackKind.SEARCH, null))
    }

    private fun tar(): ByteArray = ByteArray(1024).also {
        "ustar".toByteArray().copyInto(it, destinationOffset = 257)
    }

    private fun sqlite(applicationId: Int): ByteArray = ByteArray(100).also {
        "SQLite format 3\u0000".toByteArray().copyInto(it)
        for (i in 0 until 4) it[68 + i] = (applicationId ushr (24 - 8 * i)).toByte()
    }
}
