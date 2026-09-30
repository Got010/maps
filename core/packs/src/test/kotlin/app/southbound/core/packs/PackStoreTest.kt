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
}
