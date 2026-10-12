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
import java.io.IOException

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
    fun `a generation being filled survives a prune from anywhere`() {
        val files = tmp.newFolder()
        val updater = TileFolder(files)
        val first = updater.begin().apply { unpack("routing-main.tar", "a".repeat(64), sample); commit() }
        val second = updater.begin()
        assertTrue(second.keep("routing-main.tar", "a".repeat(64)))
        // Mid-round, the map opens a router: its own TileFolder prunes what no router reads. That used to take
        // the unfinished generation, and the commit then switched in one without the main roads.
        TileFolder(files).prune(inUse = first.dir)
        assertTrue(File(second.dir, "0/002/906.gph").isFile)
        second.unpack("routing-ct.tar", "b".repeat(64), tar("2/000/700/001.gph", "ct".toByteArray()))
        second.commit()
        assertEquals(second.dir, updater.current())
        assertEquals(setOf("routing-main.tar", "routing-ct.tar"), updater.installed().keys)

        // Once in, pruning works as before; so does an abandoned round's, and one a killed process left behind.
        updater.begin().abandon()
        val leftover = File(updater.root, "g1").apply { mkdirs() }
        TileFolder(files).prune()
        assertFalse(first.dir.exists())
        assertFalse(leftover.exists())
        assertTrue(second.dir.isDirectory)
    }

    @Test
    fun `the generation a trip's router reads survives every round's prune`() {
        val files = tmp.newFolder()
        val updater = TileFolder(files)
        val first = updater.begin().apply { unpack("routing-main.tar", "a".repeat(64), sample); commit() }
        // The map opened its router on the first generation; a trip is on.
        TileFolder(files).reading(first.dir)
        // Two rounds during the trip: each prunes keeping only the generation it replaced.
        val second = updater.begin().apply { unpack("routing-main.tar", "b".repeat(64), sample); commit() }
        updater.prune(inUse = first.dir)
        val third = updater.begin().apply { unpack("routing-main.tar", "c".repeat(64), sample); commit() }
        updater.prune(inUse = second.dir)
        assertTrue(File(first.dir, "0/002/906.gph").isFile)
        // The trip over, the router reopens on the current one, and the old ones can go.
        TileFolder(files).reading(third.dir)
        updater.prune()
        assertFalse(first.dir.exists())
        assertFalse(second.dir.exists())
        assertTrue(third.dir.isDirectory)
        TileFolder(files).reading(null)
    }

    @Test
    fun `a pack half linked then unpacked never writes into the current generation`() {
        val folder = TileFolder(tmp.newFolder())
        val first = folder.begin().apply { unpack("routing-main.tar", "a".repeat(64), sample); commit() }
        // One of the pack's two tiles has gone from the current generation: keep() links the other, then gives up.
        File(first.dir, "2/000/756/425.gph").delete()
        val second = folder.begin()
        assertFalse(second.keep("routing-main.tar", "a".repeat(64)))
        second.unpack("routing-main.tar", "b".repeat(64), tar("0/002/906.gph", "a newer level zero tile".toByteArray()))
        assertEquals("level zero tile", File(first.dir, "0/002/906.gph").readText())
        assertEquals("a newer level zero tile", File(second.dir, "0/002/906.gph").readText())
    }

    @Test
    fun `a generation missing tiles is never switched in`() {
        val folder = TileFolder(tmp.newFolder())
        val first = folder.begin().apply { unpack("routing-main.tar", "a".repeat(64), sample); commit() }
        val second = folder.begin()
        second.unpack("routing-ct.tar", "b".repeat(64), tar("2/000/700/001.gph", "ct".toByteArray()))
        File(second.dir, "2/000/700/001.gph").delete()
        try {
            second.commit()
            fail("switched in a generation missing a tile")
        } catch (e: IOException) {
            // expected: the round fails and the next one tries again
        }
        assertEquals(first.dir, folder.current())
        second.abandon()
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
