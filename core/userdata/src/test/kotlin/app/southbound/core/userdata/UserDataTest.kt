package app.southbound.core.userdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UserDataTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val cafe = PlaceRef("Joe's", 40.7, -74.0, "1 Main St", "cafe", "n1")
    private val sameCafeMoved = PlaceRef("Joe's Coffee", 40.71, -74.01, osmRef = "n1")
    private val pin = PlaceRef("Dropped pin", 40.75, -73.98)

    @Test
    fun `saving, lists and recents`() {
        var d = UserData()
        d = d.save(cafe, UserData.FAVORITES_ID, 100)
        d = d.save(cafe, UserData.FAVORITES_ID, 101) // twice is once
        assertEquals(1, d.saved.size)
        assertTrue(d.isSaved(sameCafeMoved)) // same OSM object
        assertFalse(d.isSaved(pin))
        d = d.createList("Pizza").also { assertEquals(3, it.lists.size) }
        val pizza = d.lists.last()
        d = d.save(pin, pizza.id, 102)
        assertEquals(listOf(UserData.FAVORITES_ID), d.listsContaining(cafe).map { it.id })
        assertEquals(listOf(pin), d.placesIn(pizza.id).map { it.place })
        d = d.deleteList(UserData.FAVORITES_ID) // built in: stays
        assertEquals(3, d.lists.size)
        d = d.deleteList(pizza.id)
        assertEquals(2, d.lists.size)
        assertFalse(d.isSaved(pin))
        d = d.unsave(cafe, UserData.FAVORITES_ID)
        assertTrue(d.saved.isEmpty())
        // Ids never come back.
        assertTrue(d.createList("Again").lists.last().id > pizza.id)

        for (i in 0 until 60) d = d.recordRecent(PlaceRef("p$i", 40.0 + i * 0.001, -74.0), i.toLong())
        assertEquals(UserData.MAX_RECENTS, d.recents.size)
        assertEquals("p59", d.recents.first().place.name)
        d = d.recordRecent(PlaceRef("p58", 40.058, -74.0), 999)
        assertEquals("p58", d.recents.first().place.name)
        assertEquals(UserData.MAX_RECENTS, d.recents.size)
        assertEquals(1, d.recents.count { it.place.name == "p58" })
        assertTrue(d.clearRecents().recents.isEmpty())
    }

    @Test
    fun `codec round-trips and rejects junk`() {
        val d = UserData().save(cafe, UserData.FAVORITES_ID, 100).createList("Pizza").recordRecent(pin, 200)
        val json = UserDataCodec.encode(d)
        assertEquals(d, UserDataCodec.decode(json))
        assertTrue(json.startsWith("""{"version":1,"""))
        for (bad in listOf("[]", "{}", """{"version":99}""", """{"version":1,"saved":[{"id":1}]}""")) {
            try {
                UserDataCodec.decode(bad)
                fail("accepted $bad")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `store writes on change and reloads`() {
        val file = tmp.root.resolve("data/places.sealed")
        var now = 1_000L
        val store = UserDataStore(file, PlainSealer) { now }
        assertEquals(UserData(), store.data.value)
        assertFalse(file.exists())
        store.save(cafe, UserData.WANT_TO_GO_ID)
        now = 2_000
        store.recordRecent(pin)
        assertTrue(file.isFile)
        assertEquals(listOf("places.sealed"), file.parentFile!!.list()!!.toList())

        val reopened = UserDataStore(file, PlainSealer)
        assertEquals(store.data.value, reopened.data.value)
        assertEquals(1_000L, reopened.data.value.saved.single().savedAt)
        assertEquals(2_000L, reopened.data.value.recents.single().seenAt)
    }

    @Test
    fun `missing file loads empty and readable`() {
        val store = UserDataStore(tmp.root.resolve("none/places.sealed"), PlainSealer)
        assertEquals(UserData(), store.data.value)
        assertEquals(LoadState.Loaded, store.loadState.value)
        store.save(cafe, UserData.FAVORITES_ID) // first run can save
        assertEquals(1, store.data.value.saved.size)
    }

    @Test
    fun `unreadable file is kept and saves are refused`() {
        val file = tmp.root.resolve("places.sealed")
        val junk = "garbage".toByteArray()
        file.writeBytes(junk)
        val store = UserDataStore(file, PlainSealer)
        assertTrue(store.loadState.value is LoadState.Unreadable)
        assertEquals(UserData(), store.data.value)
        try {
            store.save(cafe, UserData.FAVORITES_ID)
            fail("saved over an unreadable file")
        } catch (expected: UserDataUnreadableException) {
        }
        assertTrue(junk.contentEquals(file.readBytes()))
        assertEquals(listOf("places.sealed"), tmp.root.list()!!.toList())
        assertTrue(store.loadState.value is LoadState.Unreadable)
        // A reopen sees the same file and the same state.
        assertTrue(UserDataStore(file, PlainSealer).loadState.value is LoadState.Unreadable)
    }

    @Test
    fun `a file the key cannot open is unreadable, not empty`() {
        val file = tmp.root.resolve("places.sealed")
        UserDataStore(file, PlainSealer).save(cafe, UserData.FAVORITES_ID)
        val before = file.readBytes()
        val wrongKey = object : Sealer {
            override fun seal(plain: ByteArray) = plain
            override fun open(sealed: ByteArray): ByteArray = throw java.security.GeneralSecurityException("tag mismatch")
        }
        val store = UserDataStore(file, wrongKey)
        assertTrue(store.loadState.value is LoadState.Unreadable)
        try {
            store.recordRecent(pin)
            fail("saved over an unreadable file")
        } catch (expected: UserDataUnreadableException) {
        }
        assertTrue(before.contentEquals(file.readBytes()))
    }

    @Test
    fun `start fresh renames the old file then saves`() {
        val file = tmp.root.resolve("places.sealed")
        val junk = "garbage".toByteArray()
        file.writeBytes(junk)
        val store = UserDataStore(file, PlainSealer) { 1234L }
        store.startFresh()
        assertEquals(LoadState.Loaded, store.loadState.value)
        assertEquals(UserData(), store.data.value)
        val kept = tmp.root.resolve("places.sealed.unreadable-1234")
        assertTrue(junk.contentEquals(kept.readBytes())) // never deleted
        assertEquals(UserData(), UserDataStore(file, PlainSealer).data.value) // empty store exists
        store.save(cafe, UserData.FAVORITES_ID)
        assertEquals(1, UserDataStore(file, PlainSealer).data.value.saved.size)
        assertTrue(junk.contentEquals(kept.readBytes()))
        assertEquals(setOf("places.sealed", "places.sealed.unreadable-1234"), tmp.root.list()!!.toSet())
    }

    @Test
    fun `start fresh twice in the same millisecond keeps both old files`() {
        val file = tmp.root.resolve("places.sealed")
        file.writeBytes("one".toByteArray())
        UserDataStore(file, PlainSealer) { 5L }.startFresh()
        file.writeBytes("two".toByteArray())
        UserDataStore(file, PlainSealer) { 5L }.startFresh()
        val kept = tmp.root.list()!!.filter { it.startsWith("places.sealed.unreadable-") }
        assertEquals(2, kept.size)
        assertEquals(setOf("one", "two"), kept.map { tmp.root.resolve(it).readText() }.toSet())
    }

    @Test
    fun `start fresh on a readable store changes nothing`() {
        val file = tmp.root.resolve("places.sealed")
        val store = UserDataStore(file, PlainSealer)
        store.save(cafe, UserData.FAVORITES_ID)
        store.startFresh()
        assertEquals(1, store.data.value.saved.size)
        assertEquals(listOf("places.sealed"), tmp.root.list()!!.toList())
    }

    @Test
    fun `atomic write leaves no temp file and replaces the old one whole`() {
        val file = tmp.root.resolve("d/places.sealed")
        val store = UserDataStore(file, PlainSealer)
        repeat(5) { store.recordRecent(PlaceRef("p$it", 40.0 + it, -74.0)) }
        assertEquals(listOf("places.sealed"), file.parentFile!!.list()!!.toList())
        assertEquals(5, UserDataStore(file, PlainSealer).data.value.recents.size)
    }

    @Test
    fun `failed write cleans up its temp file and keeps the old data`() {
        val file = tmp.root.resolve("places.sealed")
        var failing = false
        val sealer = object : Sealer {
            override fun seal(plain: ByteArray): ByteArray = if (failing) throw IllegalStateException("keystore") else plain
            override fun open(sealed: ByteArray) = sealed
        }
        val store = UserDataStore(file, sealer)
        store.save(cafe, UserData.FAVORITES_ID)
        val before = file.readBytes()
        failing = true
        try {
            store.recordRecent(pin)
            fail("write should have failed")
        } catch (expected: java.io.IOException) {
        }
        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals(listOf("places.sealed"), tmp.root.list()!!.toList())
        assertEquals(1, store.data.value.saved.size)
        assertTrue(store.data.value.recents.isEmpty())
    }

    @Test
    fun `home and work shortcuts set, clear and round-trip`() {
        var d = UserData()
        assertEquals(null, d.shortcut(Shortcut.HOME))
        d = d.withShortcut(Shortcut.HOME, cafe).withShortcut(Shortcut.WORK, pin)
        assertEquals(cafe, d.shortcut(Shortcut.HOME))
        assertEquals(setOf(Shortcut.HOME), d.shortcutsOf(sameCafeMoved))
        assertEquals(setOf(Shortcut.WORK), d.shortcutsOf(pin))
        assertEquals(UserData(), UserData().withShortcut(Shortcut.WORK, null))
        assertEquals(d, UserDataCodec.decode(UserDataCodec.encode(d)))
        d = d.withShortcut(Shortcut.HOME, null)
        assertEquals(null, UserDataCodec.decode(UserDataCodec.encode(d)).home)
        // A file written before shortcuts existed still opens.
        assertEquals(UserData(), UserDataCodec.decode(UserDataCodec.encode(UserData()).replace(""","recents":[]""", ""","recents":[]""")))
        val file = tmp.root.resolve("s/places.sealed")
        val store = UserDataStore(file, PlainSealer)
        store.setShortcut(Shortcut.HOME, cafe)
        assertEquals(cafe, UserDataStore(file, PlainSealer).data.value.home)
    }
}
