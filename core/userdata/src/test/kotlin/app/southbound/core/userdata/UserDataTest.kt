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

    @Test
    fun `wifi marks mark, flip, clear and the newest wins`() {
        var d = UserData()
        assertEquals(null, d.wifiMark("n1"))
        d = d.markWifi("n1", worked = true, now = 100)
        assertEquals(WifiMark("n1", true, 100), d.wifiMark("n1"))
        // Marking the other way replaces it: one mark per spot, the newest wins.
        d = d.markWifi("n1", worked = false, now = 200)
        assertEquals(listOf(WifiMark("n1", false, 200)), d.wifiMarks)
        // The same answer again only moves the time.
        d = d.markWifi("n1", worked = false, now = 300)
        assertEquals(listOf(WifiMark("n1", false, 300)), d.wifiMarks)
        d = d.markWifi("w22", worked = true, now = 400)
        assertEquals(listOf("n1", "w22"), d.wifiMarks.map { it.osmRef })
        // Clearing one leaves the other; clearing a spot with no mark changes nothing.
        d = d.clearWifiMark("n1")
        assertEquals(null, d.wifiMark("n1"))
        assertEquals(listOf(WifiMark("w22", true, 400)), d.wifiMarks)
        assertEquals(d, d.clearWifiMark("n1"))
        // A mark is not a saved place, a recent or a list change.
        assertEquals(UserData().saved, d.saved)
        assertEquals(UserData().recents, d.recents)
    }

    @Test
    fun `wifi marks round-trip through the codec and the store`() {
        val d = UserData().save(cafe, UserData.FAVORITES_ID, 100).markWifi("n1", true, 500).markWifi("r7", false, 600)
        val json = UserDataCodec.encode(d)
        assertTrue(json.contains(""""wifiMarks":[{"osmRef":"n1","worked":true,"markedAt":500},{"osmRef":"r7","worked":false,"markedAt":600}]"""))
        assertEquals(d, UserDataCodec.decode(json))
        // The version stays 1: the field is optional on read, as home and work were.
        assertTrue(json.startsWith("""{"version":1,"""))

        val file = tmp.root.resolve("m/places.sealed")
        var now = 700L
        val store = UserDataStore(file, PlainSealer) { now }
        store.markWifi("n5", true)
        now = 800
        store.markWifi("n5", false)
        store.markWifi("w9", true)
        store.clearWifiMark("w9")
        assertEquals(listOf(WifiMark("n5", false, 800)), UserDataStore(file, PlainSealer).data.value.wifiMarks)
    }

    @Test
    fun `a document without wifiMarks still loads, with none`() {
        // A document as the app wrote it before the marks existed: no wifiMarks field at all.
        val old = """{"version":1,"lastId":3,"lists":[{"id":1,"name":"Favorites","builtIn":true},{"id":2,"name":"Want to go","builtIn":true}],""" +
            """"saved":[{"id":3,"list":1,"savedAt":100,"place":{"name":"Joe's","lat":40.7,"lon":-74.0,"address":null,"category":"cafe","osmRef":"n1"}}],""" +
            """"recents":[]}"""
        val d = UserDataCodec.decode(old)
        assertEquals(emptyList<WifiMark>(), d.wifiMarks)
        assertEquals(1, d.saved.size)
        // And through the store: the file opens (it is not "unreadable") and the first mark is added to what was there.
        val file = tmp.root.resolve("old/places.sealed")
        file.parentFile!!.mkdirs()
        file.writeBytes(PlainSealer.seal(old.toByteArray()))
        val store = UserDataStore(file, PlainSealer)
        assertEquals(LoadState.Loaded, store.loadState.value)
        store.markWifi("n1", true)
        val back = UserDataStore(file, PlainSealer).data.value
        assertEquals(1, back.saved.size)
        assertEquals(listOf("n1"), back.wifiMarks.map { it.osmRef })
    }

    @Test
    fun `two marks for one spot in a document keep the newest`() {
        val json = UserDataCodec.encode(UserData()).replace(
            """"wifiMarks":[]""",
            """"wifiMarks":[{"osmRef":"n1","worked":true,"markedAt":10},{"osmRef":"n1","worked":false,"markedAt":20},{"osmRef":"n2","worked":true,"markedAt":5}]""",
        )
        assertEquals(listOf(WifiMark("n1", false, 20), WifiMark("n2", true, 5)), UserDataCodec.decode(json).wifiMarks)
        for (bad in listOf(
            """"wifiMarks":[{"osmRef":"n1","markedAt":10}]""",
            """"wifiMarks":[{"osmRef":"n1","worked":"yes","markedAt":10}]""",
            """"wifiMarks":[{"worked":true,"markedAt":10}]""",
        )) {
            try {
                UserDataCodec.decode(UserDataCodec.encode(UserData()).replace(""""wifiMarks":[]""", bad))
                fail("accepted $bad")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `legs round-trip through the codec and the store`() {
        val d = UserData().save(cafe, UserData.FAVORITES_ID, 100)
            .addLeg("Fall trip", "2026-10-24", 341_825, LegMode.DRIVE, "Motel A", "Site B", edited = false)
            .addLeg("Fall trip", "2026-10-25", 1_200, LegMode.WALK, "Site B", "Motel A", edited = true)
        val json = UserDataCodec.encode(d)
        assertTrue(
            json.contains(
                """"legs":[{"id":4,"trip":"Fall trip","on":"2026-10-24","meters":341825,"mode":"DRIVE","from":"Motel A","to":"Site B","edited":false},""" +
                    """{"id":5,"trip":"Fall trip","on":"2026-10-25","meters":1200,"mode":"WALK","from":"Site B","to":"Motel A","edited":true}]""",
            ),
        )
        // Right after the marks, written even when empty; the version stays 1.
        assertTrue(json.contains(""""wifiMarks":[],"legs":[{"""))
        assertTrue(UserDataCodec.encode(UserData()).contains(""","wifiMarks":[],"legs":[]"""))
        assertTrue(json.startsWith("""{"version":1,"""))
        assertEquals(d, UserDataCodec.decode(json))

        val file = tmp.root.resolve("legs/places.sealed")
        val store = UserDataStore(file, PlainSealer)
        store.addLeg("Fall trip", "2026-10-24", 1_000, LegMode.DRIVE, "Motel A", "Site B", edited = false)
        store.addLeg("Fall trip", "2026-10-25", 2_000, LegMode.BIKE, "Site B", "Motel A", edited = false)
        val first = store.data.value.legs.first().id
        store.editLeg(first, "Fall trip", "2026-10-23", 1_500, LegMode.DRIVE, "Motel A", "Site C")
        store.renameTrip("Fall trip", "October")
        store.deleteLeg(first + 1)
        val back = UserDataStore(file, PlainSealer).data.value
        assertEquals(listOf(Leg(first, "October", "2026-10-23", 1_500, LegMode.DRIVE, "Motel A", "Site C", edited = true)), back.legs)
        store.deleteTrip("October")
        assertTrue(UserDataStore(file, PlainSealer).data.value.legs.isEmpty())
        // A leg out of bounds is refused before anything is written.
        val before = file.readBytes()
        try {
            store.addLeg("Fall trip", "2026-02-30", 1_000, LegMode.DRIVE, "Motel A", "Site B", edited = false)
            fail("logged a leg on 2026-02-30")
        } catch (expected: LegRefusedException) {
        }
        assertTrue(before.contentEquals(file.readBytes()))
    }

    @Test
    fun `a leg's JSON has exactly the eight fields`() {
        val d = UserData().addLeg("Fall trip", "2026-10-24", 1_000, LegMode.DRIVE, "Motel A", "Site B", edited = false)
        val root = app.southbound.core.network.MiniJson.parse(UserDataCodec.encode(d)) as Map<*, *>
        val leg = (root["legs"] as List<*>).single() as Map<*, *>
        assertEquals(listOf("id", "trip", "on", "meters", "mode", "from", "to", "edited"), leg.keys.toList())
        // And a Leg has no other field that a later writer could add by mistake: no coordinate, place or time can ride along.
        val fields = Leg::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic }
        assertEquals(setOf("id", "trip", "on", "meters", "mode", "from", "to", "edited"), fields.map { it.name }.toSet())
    }

    @Test
    fun `a document without legs still loads, with none, and keeps its places on the first leg`() {
        // A document as the app wrote it before the trip log: wifiMarks, no legs field at all.
        val old = UserDataCodec.encode(UserData().save(cafe, UserData.FAVORITES_ID, 100).markWifi("n1", true, 5)).replace(""","legs":[]""", "")
        assertFalse(old.contains("legs"))
        val d = UserDataCodec.decode(old)
        assertEquals(emptyList<Leg>(), d.legs)
        assertEquals(1, d.saved.size)
        val file = tmp.root.resolve("old-legs/places.sealed")
        file.parentFile!!.mkdirs()
        file.writeBytes(PlainSealer.seal(old.toByteArray()))
        val store = UserDataStore(file, PlainSealer)
        assertEquals(LoadState.Loaded, store.loadState.value)
        store.addLeg("Fall trip", "2026-10-24", 1_000, LegMode.DRIVE, "Motel A", "Site B", edited = false)
        val back = UserDataStore(file, PlainSealer).data.value
        assertEquals(1, back.saved.size)
        assertEquals(1, back.wifiMarks.size)
        // The new leg's id is past the saved place's.
        assertEquals(d.lastId + 1, back.legs.single().id)
        // A document with legs but no lastId takes the highest id of anything in it.
        val noLastId = UserDataCodec.encode(back).replace(""""lastId":${back.lastId},""", "")
        assertEquals(back.lastId, UserDataCodec.decode(noLastId).lastId)
    }

    @Test
    fun `a broken leg makes the document unreadable, never silently dropped`() {
        val good = UserDataCodec.encode(UserData().addLeg("Fall trip", "2026-10-24", 1_000, LegMode.DRIVE, "Motel A", "Site B", edited = false))
        for ((old, new) in listOf(
            "\"mode\":\"DRIVE\"" to "\"mode\":\"TRANSIT\"",
            "\"meters\":1000" to "\"meters\":\"far\"",
            ",\"edited\":false" to "",
            "\"trip\":\"Fall trip\"," to "",
        )) {
            assertTrue(old, good.contains(old))
            try {
                UserDataCodec.decode(good.replace(old, new))
                fail("accepted $new")
            } catch (expected: IllegalArgumentException) {
            }
        }
        val file = tmp.root.resolve("broken/places.sealed")
        file.parentFile!!.mkdirs()
        file.writeBytes(good.replace("\"mode\":\"DRIVE\"", "\"mode\":\"TRANSIT\"").toByteArray())
        val store = UserDataStore(file, PlainSealer)
        assertTrue(store.loadState.value is LoadState.Unreadable)
        // Every leg call is an explicit save: refused and counted, the file untouched.
        try {
            store.addLeg("Fall trip", "2026-10-24", 1_000, LegMode.DRIVE, "Motel A", "Site B", edited = false)
            fail("logged over an unreadable file")
        } catch (expected: UserDataUnreadableException) {
        }
        assertEquals(1, store.refusedSaves.value)
        assertTrue(file.readText().contains("TRANSIT"))
    }

    @Test
    fun `reload after a temporary failure ends loaded with the data`() {
        val file = tmp.root.resolve("places.sealed")
        UserDataStore(file, PlainSealer).save(cafe, UserData.FAVORITES_ID)
        var failuresLeft = 1
        val flaky = object : Sealer {
            override fun seal(plain: ByteArray) = plain
            override fun open(sealed: ByteArray): ByteArray =
                if (failuresLeft-- > 0) throw java.security.GeneralSecurityException("keystore busy") else sealed
        }
        val store = UserDataStore(file, flaky)
        assertTrue(store.loadState.value is LoadState.Unreadable)
        store.reload()
        assertEquals(LoadState.Loaded, store.loadState.value)
        assertEquals(1, store.data.value.saved.size)
        store.recordRecent(pin) // saves work again
        assertEquals(1, UserDataStore(file, PlainSealer).data.value.recents.size)
    }

    @Test
    fun `reload keeps a still-unreadable file as it is`() {
        val file = tmp.root.resolve("places.sealed")
        file.writeBytes("garbage".toByteArray())
        val store = UserDataStore(file, PlainSealer)
        store.reload()
        assertTrue(store.loadState.value is LoadState.Unreadable)
        assertEquals("garbage", file.readText())
        assertEquals(listOf("places.sealed"), tmp.root.list()!!.toList())
    }

    @Test
    fun `refused explicit saves are counted but automatic recents are not`() {
        val file = tmp.root.resolve("places.sealed")
        file.writeBytes("garbage".toByteArray())
        val store = UserDataStore(file, PlainSealer)
        assertEquals(0, store.refusedSaves.value)
        repeat(2) { try { store.save(cafe, UserData.FAVORITES_ID) } catch (expected: UserDataUnreadableException) { } }
        assertEquals(2, store.refusedSaves.value)
        // A recent is recorded automatically on every place viewed: refused, but not counted.
        try {
            store.recordRecent(pin)
            fail("recorded a recent over an unreadable file")
        } catch (expected: UserDataUnreadableException) {
        }
        assertEquals(2, store.refusedSaves.value)
        assertEquals("garbage", file.readText())
    }

    @Test
    fun `a stale temp file from a crash is replaced by the next write`() {
        val file = tmp.root.resolve("places.sealed")
        tmp.root.resolve("places.sealed.part").writeBytes("half a wri".toByteArray())
        val store = UserDataStore(file, PlainSealer)
        assertEquals(LoadState.Loaded, store.loadState.value) // the .part file is ignored on load
        store.save(cafe, UserData.FAVORITES_ID)
        assertEquals(listOf("places.sealed"), tmp.root.list()!!.toList())
        assertEquals(1, UserDataStore(file, PlainSealer).data.value.saved.size)
    }
}
