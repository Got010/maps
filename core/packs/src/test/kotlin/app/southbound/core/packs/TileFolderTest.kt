package app.southbound.core.packs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class TileFolderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Written by pipeline/routing/split_tiles.py write_tar: two tiles. */
    private val sample = File("src/test/resources/routing-sample.tar")

    /** A one-file ustar archive, for names the pipeline would never write. */
    private fun tar(name: String, body: ByteArray): File {
        val header = ByteArray(512)
        name.toByteArray().copyInto(header)
        "%011o".format(body.size).toByteArray().copyInto(header, 124)
        header[156] = '0'.code.toByte()
        "ustar".toByteArray().copyInto(header, 257)
        val out = ByteArrayOutputStream()
        out.write(header)
        out.write(body)
        out.write(ByteArray((512 - body.size % 512) % 512))
        out.write(ByteArray(1024))
        return tmp.newFile().also { it.writeBytes(out.toByteArray()) }
    }

    @Test
    fun `nothing until a generation is committed`() {
        val folder = TileFolder(tmp.newFolder())
        assertNull(folder.current())
        val g = folder.begin()
        g.unpack("routing-main.tar", "a".repeat(64), sample)
        assertNull(folder.current())
        g.commit()
        assertEquals(g.dir, folder.current())
        assertEquals("a local tile, Manhattan", File(g.dir, "2/000/756/425.gph").readText())
        assertEquals("level zero tile", File(g.dir, "0/002/906.gph").readText())
        assertEquals(mapOf("routing-main.tar" to "a".repeat(64)), folder.installed())
        val summary = folder.summary()!!
        assertEquals(1, summary.packs)
        assertEquals(("level zero tile" + "a local tile, Manhattan").length.toLong(), summary.bytes)
    }

    @Test
    fun `a new generation keeps unchanged packs and drops the rest`() {
        val folder = TileFolder(tmp.newFolder())
        val first = folder.begin()
        first.unpack("routing-main.tar", "a".repeat(64), sample)
        first.unpack("routing-ct.tar", "b".repeat(64), tar("2/000/700/001.gph", "ct".toByteArray()))
        first.commit()

        val second = folder.begin()
        assertTrue(second.keep("routing-main.tar", "a".repeat(64)))
        assertFalse(second.keep("routing-ct.tar", "c".repeat(64))) // changed: download it again
        second.unpack("routing-hudson.tar", "d".repeat(64), tar("2/000/700/002.gph", "hudson".toByteArray()))
        // Until the commit, routing reads the first generation.
        assertEquals(first.dir, folder.current())
        second.commit()
        assertEquals(second.dir, folder.current())
        assertEquals(setOf("routing-main.tar", "routing-hudson.tar"), folder.installed().keys)
        assertTrue(File(second.dir, "0/002/906.gph").isFile)
        assertFalse(File(second.dir, "2/000/700/001.gph").exists()) // Connecticut's tile went with its pack

        // A trip still reads the first generation: it stays until its router closes.
        folder.prune(inUse = first.dir)
        assertTrue(first.dir.isDirectory)
        folder.prune()
        assertFalse(first.dir.exists())
        assertTrue(second.dir.isDirectory)
    }

    @Test
    fun `an abandoned generation leaves the current one`() {
        val folder = TileFolder(tmp.newFolder())
        val first = folder.begin().apply { unpack("routing-main.tar", "a".repeat(64), sample); commit() }
        val second = folder.begin()
        second.unpack("routing-x.tar", "e".repeat(64), sample)
        second.abandon()
        assertEquals(first.dir, folder.current())
        assertNotEquals(first.dir, second.dir)
    }

    @Test
    fun `a pack can't write outside its folder`() {
        val folder = TileFolder(tmp.newFolder())
        for (name in listOf("../evil.gph", "/etc/passwd", "2/000/756/425.gph/../../x", "3/000/000/001.gph", "notes.txt")) {
            val g = folder.begin()
            try {
                g.unpack("routing-bad.tar", "f".repeat(64), tar(name, "x".toByteArray()))
                fail("accepted $name")
            } catch (e: UnrecognizedPackException) {
                // expected
            } finally {
                g.abandon()
            }
        }
        assertFalse(File(folder.root, "evil.gph").exists())
    }

    @Test
    fun `a truncated pack is refused`() {
        val whole = sample.readBytes()
        val cut = tmp.newFile().also { it.writeBytes(whole.copyOf(600)) }
        val g = TileFolder(tmp.newFolder()).begin()
        try {
            g.unpack("routing-main.tar", "a".repeat(64), cut)
            fail("accepted a truncated pack")
        } catch (e: UnrecognizedPackException) {
            // expected
        }
    }
}
