package app.southbound.core.export

import app.southbound.core.export.fwx.FwxError
import app.southbound.core.export.fwx.FwxException
import app.southbound.core.export.fwx.FwxWriter
import app.southbound.core.network.SecretUnreadableException
import app.southbound.core.userdata.PlaceRef
import app.southbound.core.userdata.PlainSealer
import app.southbound.core.userdata.Sealer
import app.southbound.core.userdata.UserData
import app.southbound.core.userdata.UserDataStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class BackupServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pass get() = Samples.PASSPHRASE.toCharArray()

    private class Device(dir: File, sealer: Sealer = PlainSealer) {
        val stores = FakeStores(dir)
        val journal = ImportJournal(File(dir, "import.pending"), sealer)
        val service = BackupService(stores, journal, "0.1.14") { 1_791_028_800_000L }
    }

    private fun device(name: String, sealer: Sealer = PlainSealer) = Device(tmp.newFolder(name), sealer)

    private fun filled(name: String = "a"): Device = device(name).also {
        it.stores.places.update { Samples.userData() }
        it.stores.keys = Samples.keys()
        it.stores.settings = Samples.settings()
    }

    /** The bytes an export writes, with the output and read-back streams of a document in memory. */
    private fun export(from: Device, passphrase: CharArray = pass): ByteArray {
        val out = ByteArrayOutputStream()
        runBlocking { from.service.export({ out }, { ByteArrayInputStream(out.toByteArray()) }, passphrase) }
        return out.toByteArray()
    }

    private fun import(into: Device, file: ByteArray, passphrase: CharArray = pass) =
        runBlocking { into.service.import({ ByteArrayInputStream(file) }, passphrase) }

    private fun failure(block: () -> Unit): Throwable {
        try {
            block()
        } catch (e: Throwable) {
            return e
        }
        fail("accepted")
        error("unreachable")
    }

    // ---- The round trip ----------------------------------------------------------------------------

    @Test
    fun `export then import into an empty store restores everything`() {
        val a = filled()
        val file = export(a)
        val b = device("b")
        assertEquals(UserData(), b.stores.places.data.value)

        val outcome = import(b, file)

        assertTrue(outcome is ImportOutcome.Done)
        assertEquals(a.stores.snapshot(), b.stores.snapshot())
        assertEquals(3, (outcome as ImportOutcome.Done).counts.apiKeys)
        assertFalse(b.journal.exists())
        // And the restored phone writes the same bundle: nothing was lost or changed on the way.
        assertEquals(
            SouthboundBundle.encode(a.stores.let { BackupData(it.places.data.value, it.keys, it.settings) }, "v").mapValues { it.value.toList() },
            SouthboundBundle.encode(b.stores.let { BackupData(it.places.data.value, it.keys, it.settings) }, "v").mapValues { it.value.toList() },
        )
        // The restored places are in the store's own file too.
        assertEquals(a.stores.places.data.value, UserDataStore(File(tmp.root, "b/places.sealed"), PlainSealer).data.value)
    }

    @Test
    fun `the file holds nothing readable and the summary only counts`() {
        val file = export(filled())
        assertFalse(Samples.contains(file, Samples.CAFE_NAME))
        assertFalse(Samples.contains(file, Samples.BUS_KEY))
        assertFalse(Samples.contains(file, Samples.GH_TOKEN))
        val counts = runBlocking {
            val out = ByteArrayOutputStream()
            filled("c").service.export({ out }, { ByteArrayInputStream(out.toByteArray()) }, pass)
        }
        assertEquals(BackupCounts(lists = 3, savedPlaces = 3, recents = 2, home = 1, work = 1, apiKeys = 3, settings = 56), counts)
        assertFalse(counts.toString().contains(Samples.BUS_KEY))
    }

    @Test
    fun `import replaces, it does not merge`() {
        val file = export(filled())
        val b = device("b")
        b.stores.places.update { UserData().save(PlaceRef("Only on b", 41.0, -73.0), UserData.FAVORITES_ID, 5) }
        b.stores.keys = mapOf(app.southbound.core.network.ApiKey.EIA to "old-eia")
        import(b, file)
        assertEquals(Samples.userData(), b.stores.places.data.value)
        assertEquals(Samples.keys(), b.stores.keys)
        assertFalse(b.stores.places.data.value.saved.any { it.place.name == "Only on b" })
    }

    @Test
    fun `an empty phone exports and imports too`() {
        val file = export(device("a"))
        val b = filled("b")
        import(b, file)
        assertEquals(UserData(), b.stores.places.data.value)
        assertTrue(b.stores.keys.isEmpty())
        assertEquals(Samples.defaultSettings(), b.stores.settings)
    }

    // ---- Export: refuses what import would refuse, and never half-writes ------------------------------

    @Test
    fun `export with over-long fields fails before the document is opened`() {
        val a = filled()
        a.stores.places.update { it.save(PlaceRef("n".repeat(PlacesEntry.MAX_NAME + 1), 40.0, -74.0), UserData.FAVORITES_ID, 9) }
        var opened = false
        val e = failure {
            runBlocking { a.service.export({ opened = true; ByteArrayOutputStream() }, { ByteArrayInputStream(ByteArray(0)) }, pass) }
        }
        assertEquals(FwxError.MALFORMED_PAYLOAD, (e as FwxException).code)
        assertFalse(opened)
        // The note names the rule, not the place.
        assertFalse(e.message!!.contains("nnnn"))
    }

    @Test
    fun `export with a short passphrase or a lone surrogate fails before the document is opened`() {
        val a = filled()
        for (bad in listOf("eleven char".toCharArray(), "abcdefghij\ud800klm".toCharArray())) {
            var opened = false
            val e = failure {
                runBlocking { a.service.export({ opened = true; ByteArrayOutputStream() }, { ByteArrayInputStream(ByteArray(0)) }, bad) }
            }
            assertEquals(FwxError.BAD_PASSPHRASE, (e as FwxException).code)
            assertFalse(opened)
        }
    }

    @Test
    fun `export checks the written file by reading it back`() {
        val a = filled()
        val out = ByteArrayOutputStream()
        // The document reads back damaged.
        val damaged = failure {
            runBlocking {
                a.service.export({ out }, { ByteArrayInputStream(out.toByteArray().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }) }, pass)
            }
        }
        assertEquals(FwxError.DAMAGED, (damaged as FwxException).code)
        // The document reads back as a different, valid file.
        val other = export(device("empty"))
        val differs = failure {
            runBlocking { a.service.export({ ByteArrayOutputStream() }, { ByteArrayInputStream(other) }, pass) }
        }
        assertTrue(differs is ReadBackFailedException)
    }

    @Test
    fun `export passes a destination error through unchanged`() {
        val a = filled()
        val e = failure {
            runBlocking {
                a.service.export(
                    { object : java.io.OutputStream() { override fun write(b: Int) = throw IOException("no space") } },
                    { ByteArrayInputStream(ByteArray(0)) },
                    pass,
                )
            }
        }
        assertEquals("no space", e.message)
    }

    @Test
    fun `export and import stop while the saved places are unreadable`() {
        val file = export(filled())
        val dir = tmp.newFolder("x")
        File(dir, "places.sealed").writeText("not a places file")
        val stores = FakeStores(dir)
        val journal = ImportJournal(File(dir, "import.pending"), PlainSealer)
        val service = BackupService(stores, journal, "v")
        assertNotNull(stores.blocker())
        val before = File(dir, "places.sealed").readBytes().toList()

        val export = failure { runBlocking { service.export({ ByteArrayOutputStream() }, { ByteArrayInputStream(file) }, pass) } }
        val import = failure { runBlocking { service.import({ ByteArrayInputStream(file) }, pass) } }

        assertTrue(export is BackupRefusedException)
        assertTrue(import is BackupRefusedException)
        // The unreadable file is left exactly as it was, and nothing was applied or kept.
        assertEquals(before, File(dir, "places.sealed").readBytes().toList())
        assertEquals(0, stores.applyCalls)
        assertFalse(journal.exists())
    }

    @Test
    fun `an unreadable saved key stops export and import`() {
        val file = export(filled())
        val b = filled("b")
        b.stores.keysUnreadable = true
        val before = b.stores.snapshot()
        assertTrue(failure { runBlocking { b.service.export({ ByteArrayOutputStream() }, { ByteArrayInputStream(file) }, pass) } } is SecretUnreadableException)
        assertTrue(failure { import(b, file) } is BackupRefusedException)
        assertEquals(before, b.stores.snapshot())
        assertEquals(0, b.stores.applyCalls)
        assertFalse(b.journal.exists())
    }

    @Test
    fun `the header's time is the clock at the start of the export`() {
        val a = filled()
        var now = 1_791_028_800_000L
        val service = BackupService(a.stores, a.journal, "v") { now.also { now += 5_000 } }
        val out = ByteArrayOutputStream()
        runBlocking { service.export({ out }, { ByteArrayInputStream(out.toByteArray()) }, pass) }
        val header = SouthboundBundle.readHeader(ByteArrayInputStream(out.toByteArray()))
        assertEquals(1_791_028_800_000L, header.createdMs)
    }

    // ---- Import: wrong app, wrong passphrase, damage, bad contents change nothing -------------------------

    private fun assertUntouched(d: Device, code: FwxError, file: ByteArray, passphrase: CharArray = pass) {
        val before = d.stores.snapshot()
        val e = failure { import(d, file, passphrase) }
        assertEquals(code, (e as FwxException).code)
        assertEquals(before, d.stores.snapshot())
        assertEquals(0, d.stores.applyCalls)
        assertFalse(d.journal.exists())
    }

    @Test
    fun `a wrong passphrase changes nothing`() {
        val file = export(filled())
        assertUntouched(filled("b"), FwxError.WRONG_PASSPHRASE, file, "correct horse battery stapl".toCharArray())
    }

    @Test
    fun `another app's bundle changes nothing`() {
        val out = ByteArrayOutputStream()
        FwxWriter(out, "prikey", 1, 1L, pass).also { it.entry("manifest.json", "{}".toByteArray()); it.finish() }
        assertUntouched(filled("b"), FwxError.WRONG_APP, out.toByteArray())
    }

    @Test
    fun `a damaged or cut file changes nothing`() {
        val file = export(filled())
        val flipped = file.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x10).toByte() }
        assertUntouched(filled("b"), FwxError.DAMAGED, flipped)
        assertUntouched(filled("c"), FwxError.DAMAGED, file.copyOf(file.size - 1))
        assertUntouched(filled("d"), FwxError.NOT_AN_EXPORT, "hello".toByteArray())
    }

    @Test
    fun `a bundle with bad contents changes nothing`() {
        val good = SouthboundBundle.encode(Samples.data(), "v")
        val bad = LinkedHashMap(good)
        bad["settings.json"] = String(good.getValue("settings.json")).replace("\"mpg\":31.5", "\"mpg\":0").toByteArray()
        val out = ByteArrayOutputStream()
        FwxWriter(out, "southbound", 1, 1L, pass).also { w -> bad.forEach { (n, b) -> w.entry(n, b) }; w.finish() }
        assertUntouched(filled("b"), FwxError.MALFORMED_PAYLOAD, out.toByteArray())
    }

    // ---- The commit point and a stop in the middle ---------------------------------------------------------

    @Test
    fun `an import that stops while applying is kept and finished later`() {
        val file = export(filled())
        val b = device("b")
        b.stores.failApply = true

        val outcome = import(b, file)

        assertTrue(outcome is ImportOutcome.Pending)
        assertEquals("disk full", (outcome as ImportOutcome.Pending).reason)
        assertTrue(b.journal.exists())
        assertTrue(b.service.hasPending())
        // New exports and imports wait for it.
        assertNotNull(b.service.blocker())
        assertTrue(failure { import(b, file) } is BackupRefusedException)

        b.stores.failApply = false
        val resumed = runBlocking { b.service.resume() }
        assertTrue(resumed.toString(), resumed is ResumeOutcome.Applied)
        assertEquals(filled("a2").stores.snapshot(), b.stores.snapshot())
        assertFalse(b.journal.exists())
        assertEquals(ResumeOutcome.NothingPending, runBlocking { b.service.resume() })
        assertNull(b.service.blocker())
    }

    @Test
    fun `a preference write that fails keeps the journal until it lands`() {
        val file = export(filled())
        val b = device("b")
        b.stores.failSettings = true

        val outcome = import(b, file)

        assertTrue(outcome is ImportOutcome.Pending)
        assertEquals("commit failed", (outcome as ImportOutcome.Pending).reason)
        // The places and keys landed but the preferences did not: the journal is still there, not forgotten.
        assertTrue(b.journal.exists())
        assertEquals(Samples.userData(), b.stores.places.data.value)
        assertEquals(Samples.defaultSettings(), b.stores.settings)
        // A resume that fails again keeps it too.
        assertTrue(runBlocking { b.service.resume() } is ResumeOutcome.Failed)
        assertTrue(b.journal.exists())
        b.stores.failSettings = false
        assertTrue(runBlocking { b.service.resume() } is ResumeOutcome.Applied)
        assertFalse(b.journal.exists())
        assertEquals(Samples.settings(), b.stores.settings)
    }

    @Test
    fun `the summary names every network feature the import turns on`() {
        val a = filled() // every toggle away from its default
        val file = export(a)
        val b = device("b") // defaults: only PACK_DOWNLOADS on
        val outcome = import(b, file) as ImportOutcome.Done
        assertEquals(
            listOf(
                "APP_UPDATES", "TRAFFIC_511NY", "TRAFFIC_NYC_DOT", "TRAFFIC_TOMTOM", "TRANSIT_REALTIME", "TRANSIT_BUS_TIME", "GAS_PRICES",
                "WEATHER",
            ),
            outcome.turnedOn.map { it.name },
        )
        // Into a phone that already has them on, or from a bundle that turns none on: none.
        assertEquals(emptyList<Any>(), (import(b, file) as ImportOutcome.Done).turnedOn)
        val plain = export(device("plain"))
        assertEquals(emptyList<Any>(), (import(device("d"), plain) as ImportOutcome.Done).turnedOn)
        // Only what goes from off to on is named: this bundle turns PACK_DOWNLOADS back on.
        assertEquals(listOf("PACK_DOWNLOADS"), (import(b, plain) as ImportOutcome.Done).turnedOn.map { it.name })
        // A kept import says the same before it lands.
        val c = device("c")
        c.stores.failApply = true
        assertEquals(8, (import(c, file) as ImportOutcome.Pending).turnedOn.size)
        c.stores.failApply = false
        assertEquals(8, (runBlocking { c.service.resume() } as ResumeOutcome.Applied).turnedOn.size)
    }

    @Test
    fun `resume checks again that nothing unreadable would be overwritten`() {
        val file = export(filled())
        val b = device("b")
        b.stores.failApply = true
        import(b, file)
        b.stores.failApply = false
        b.stores.keysUnreadable = true
        val applied = b.stores.applyCalls
        val outcome = runBlocking { b.service.resume() }
        assertTrue(outcome is ResumeOutcome.Failed && !outcome.unreadable)
        assertEquals(applied, b.stores.applyCalls)
        assertTrue(b.journal.exists())
    }

    @Test
    fun `a stopped process finishes the import at the next start`() {
        val file = export(filled())
        val dir = tmp.newFolder("phone")
        val first = Device(dir)
        first.stores.failApply = true
        import(first, file)
        assertTrue(first.journal.exists())

        // A new process: new objects over the same files.
        val second = Device(dir)
        val resumed = runBlocking { second.service.resume() }
        assertTrue(resumed.toString(), resumed is ResumeOutcome.Applied)
        assertEquals(Samples.userData(), second.stores.places.data.value)
        assertFalse(second.journal.exists())
    }

    @Test
    fun `applying twice leaves the same state`() {
        val file = export(filled())
        val b = device("b")
        import(b, file)
        val once = b.stores.snapshot()
        val data = SouthboundBundle.decode(SouthboundBundle.readEntries(ByteArrayInputStream(file), pass))
        runBlocking { b.stores.apply(data) }
        assertEquals(once, b.stores.snapshot())
    }

    @Test
    fun `when the commit itself fails nothing has changed`() {
        val file = export(filled())
        val dir = tmp.newFolder("b")
        val stores = FakeStores(dir)
        // The journal's directory is a regular file, so it can't be written.
        File(dir, "blocked").writeText("x")
        val journal = ImportJournal(File(dir, "blocked/import.pending"), PlainSealer)
        val service = BackupService(stores, journal, "v")
        val before = stores.snapshot()
        val e = failure { runBlocking { service.import({ ByteArrayInputStream(file) }, pass) } }
        assertTrue(e is IOException)
        assertEquals(before, stores.snapshot())
        assertEquals(0, stores.applyCalls)
        assertFalse(journal.exists())
    }

    @Test
    fun `a pending import can be discarded and an unreadable one is reported`() {
        val file = export(filled())
        val dir = tmp.newFolder("p")
        val d = Device(dir, AesSealer())
        d.stores.failApply = true
        import(d, file)
        assertTrue(d.journal.exists())
        runBlocking { d.service.discardPending() }
        assertFalse(d.service.hasPending())

        // A journal written under a key this phone no longer has can't be opened.
        d.stores.failApply = true
        import(d, file)
        val other = Device(dir, AesSealer())
        val outcome = runBlocking { other.service.resume() }
        assertTrue(outcome is ResumeOutcome.Failed && outcome.unreadable)
        assertTrue(other.journal.exists())
        assertEquals(0, other.stores.applyCalls)
    }

    @Test
    fun `the kept import is sealed and holds no readable key or place`() {
        val file = export(filled())
        val dir = tmp.newFolder("s")
        val d = Device(dir, AesSealer())
        d.stores.failApply = true
        import(d, file)
        val kept = File(dir, "import.pending").readBytes()
        assertTrue(kept.isNotEmpty())
        assertFalse(Samples.contains(kept, Samples.CAFE_NAME))
        assertFalse(Samples.contains(kept, Samples.BUS_KEY))
        assertFalse(Samples.contains(kept, "southbound"))
        assertEquals(listOf("import.pending"), File(dir, "").list()!!.filter { it.startsWith("import") })
        // The saved places file is the store's own plain test file here; only the journal is under test.
        assertTrue(File(dir, "places.sealed").exists())
    }

    @Test
    fun `a journal that is not ours is rejected`() {
        val dir = tmp.newFolder("j")
        val journal = ImportJournal(File(dir, "import.pending"), PlainSealer)
        File(dir, "import.pending").writeBytes(ByteArray(40) { 7 })
        assertTrue(failure { journal.read() } is IOException)
        journal.clear()
        assertNull(journal.read())
        assertFalse(journal.exists())
    }
}
