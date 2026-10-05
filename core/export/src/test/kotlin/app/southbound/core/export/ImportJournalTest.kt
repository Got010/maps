package app.southbound.core.export

import app.southbound.core.userdata.PlainSealer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ImportJournalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val entries get() = SouthboundBundle.encode(Samples.data(), "v")

    @Test
    fun `entries come back as written, and clearing removes the file`() {
        val file = File(tmp.root, "import.pending")
        val journal = ImportJournal(file, AesSealer())
        assertFalse(journal.exists())
        assertNull(journal.read())
        journal.write(entries)
        assertTrue(journal.exists())
        assertEquals(entries.mapValues { it.value.toList() }, journal.read()!!.mapValues { it.value.toList() })
        journal.clear()
        assertFalse(journal.exists())
        assertEquals(listOf<String>(), tmp.root.list()!!.toList())
        // A second write replaces the first.
        journal.write(entries)
        journal.write(SouthboundBundle.encode(Samples.emptyData(), "v"))
        assertEquals(SouthboundBundle.encode(Samples.emptyData(), "v").mapValues { it.value.toList() }, journal.read()!!.mapValues { it.value.toList() })
        assertEquals(listOf("import.pending"), tmp.root.list()!!.toList())
    }

    @Test
    fun `the file is the sealed form and the pending marker is found without opening it`() {
        val dir = tmp.newFolder("d")
        assertFalse(ImportJournal.pendingIn(dir))
        ImportJournal.at(dir, AesSealer()).write(entries)
        assertTrue(ImportJournal.pendingIn(dir))
        assertEquals("import.pending", ImportJournal.FILE_NAME)
        assertFalse(Samples.contains(File(dir, "import.pending").readBytes(), Samples.BUS_KEY))
    }

    @Test
    fun `a file that is not a journal is rejected`() {
        val file = File(tmp.root, "import.pending")
        val journal = ImportJournal(file, PlainSealer)
        val good = entries
        journal.write(good)
        val bytes = file.readBytes()
        for (bad in listOf(
            ByteArray(0),
            ByteArray(7),
            bytes.copyOf(bytes.size - 1),
            bytes + byteArrayOf(0),
            bytes.copyOf().also { it[0] = 0 },
            bytes.copyOf().also { it[7] = 5 }, // an entry count that is not the schema's
        )) {
            file.writeBytes(bad)
            try {
                journal.read()
                fail("accepted ${bad.size} bytes")
            } catch (e: IOException) {
                // expected
            }
        }
    }
}
