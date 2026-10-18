package app.southbound.core.export

import app.southbound.core.userdata.PlaceList
import app.southbound.core.userdata.PlaceRef
import app.southbound.core.userdata.RecentPlace
import app.southbound.core.userdata.SavedPlace
import app.southbound.core.userdata.UserData
import app.southbound.core.userdata.UserDataCodec
import app.southbound.core.userdata.WifiMark

/**
 * `places.json`: the saved places file's own JSON ([UserDataCodec] writes it, version 1), read back strictly (see
 * [decode]) and checked far more tightly than the app's own reader does, because here the text comes from outside.
 * Export runs the same check before a byte is written, so it refuses what import would. Schema 9 added `wifiMarks`
 * (the user's "Wi-Fi worked / Didn't work" marks); a schema 1 to 8 file has no such field and reads with none.
 */
internal object PlacesEntry {
    const val MAX_LISTS = 500
    const val MAX_SAVED = 40_000
    const val MAX_LIST_NAME = 1_024
    const val MAX_NAME = 2_048
    const val MAX_ADDRESS = 4_096
    const val MAX_CATEGORY = 256
    const val MAX_OSM_REF = 64
    const val MAX_WIFI_MARKS = 20_000

    fun encode(data: UserData): ByteArray {
        check(data)
        return UserDataCodec.encode(data).toByteArray(Charsets.UTF_8)
    }

    /**
     * The text earlier versions wrote for [data]: the same without the `wifiMarks` field, which only exists to be
     * empty here, or null when there are marks (an earlier version had none to write). Only the import journal's
     * "are these the same places" check uses it. A name can't contain the field's text, as quotes in strings are escaped.
     */
    fun encodeBeforeSchema9(data: UserData): ByteArray? {
        if (data.wifiMarks.isNotEmpty()) return null
        check(data)
        return UserDataCodec.encode(data).replaceFirst(EMPTY_MARKS, "").toByteArray(Charsets.UTF_8)
    }

    private const val EMPTY_MARKS = ",\"wifiMarks\":[]"

    private val ROOT_1 = setOf("version", "lastId", "lists", "saved", "recents")

    /** Schema 9: the Wi-Fi marks, always present (an empty list when there are none). */
    private val ROOT = ROOT_1 + "wifiMarks"
    private val ROOT_OPTIONAL = setOf("home", "work")
    private val LIST = setOf("id", "name", "builtIn")
    private val SAVED = setOf("id", "list", "savedAt", "place")
    private val RECENT = setOf("seenAt", "place")
    private val PLACE = setOf("name", "lat", "lon", "address", "category", "osmRef")
    private val WIFI_MARK = setOf("osmRef", "worked", "markedAt")

    /**
     * Read strictly, not with the app's own lenient reader: exactly the fields and types [UserDataCodec] writes
     * (an unknown or missing field, a value of the wrong type or a fractional id is refused, never defaulted), and
     * `version` must be 1. Duplicate keys were already refused by the JSON reader. [schema] is the bundle's: from 9 on
     * `wifiMarks` is required, before that it is not allowed and the marks read as none.
     */
    fun decode(bytes: ByteArray, schema: Long): UserData {
        val root = Obj.of(parseJson(bytes, "places.json"), "places", if (schema >= 9) ROOT else ROOT_1, ROOT_OPTIONAL)
        if (root.long("version", 1, 1) != 1L) invalid("places.json is not version 1")
        val data = UserData(
            lists = root.objects("lists", MAX_LISTS, LIST).map {
                PlaceList(it.long("id", 1, Long.MAX_VALUE), it.string("name", MAX_LIST_NAME), it.bool("builtIn"))
            },
            saved = root.objects("saved", MAX_SAVED, SAVED).map {
                SavedPlace(
                    it.long("id", 1, Long.MAX_VALUE), it.long("list", 1, Long.MAX_VALUE),
                    place(it.obj("place", PLACE)), it.long("savedAt", 0, Long.MAX_VALUE),
                )
            },
            recents = root.objects("recents", UserData.MAX_RECENTS, RECENT).map {
                RecentPlace(place(it.obj("place", PLACE)), it.long("seenAt", 0, Long.MAX_VALUE))
            },
            lastId = root.long("lastId", 1, Long.MAX_VALUE),
            home = if (root.has("home")) place(root.obj("home", PLACE)) else null,
            work = if (root.has("work")) place(root.obj("work", PLACE)) else null,
            wifiMarks = if (schema >= 9) {
                root.objects("wifiMarks", MAX_WIFI_MARKS, WIFI_MARK).map {
                    WifiMark(it.string("osmRef", MAX_OSM_REF), it.bool("worked"), it.long("markedAt", 0, Long.MAX_VALUE))
                }
            } else {
                emptyList()
            },
        )
        check(data)
        return data
    }

    private fun place(o: Obj) = PlaceRef(
        name = o.string("name", MAX_NAME),
        lat = o.double("lat", -90.0, 90.0),
        lon = o.double("lon", -180.0, 180.0),
        address = o.stringOrNull("address", MAX_ADDRESS),
        category = o.stringOrNull("category", MAX_CATEGORY),
        osmRef = o.stringOrNull("osmRef", MAX_OSM_REF),
    )

    /** The rules of schemas 1 to 9. Notes name the field, never its contents. */
    fun check(data: UserData) {
        if (data.lists.size !in 2..MAX_LISTS) invalid("the number of lists is outside 2..$MAX_LISTS")
        if (data.saved.size > MAX_SAVED) invalid("more than $MAX_SAVED saved places")
        if (data.recents.size > UserData.MAX_RECENTS) invalid("more than ${UserData.MAX_RECENTS} recents")

        val listIds = HashSet<Long>()
        var highest = 0L
        for (list in data.lists) {
            if (list.id < 1 || !listIds.add(list.id)) invalid("a list id is not positive and unique")
            if (list.name.length > MAX_LIST_NAME || !list.name.isWellFormed()) invalid("a list name is too long or invalid")
            // Only the two built-in lists are built in; they can't be deleted, so they are always there.
            val builtIn = list.id == UserData.FAVORITES_ID || list.id == UserData.WANT_TO_GO_ID
            if (list.builtIn != builtIn) invalid("the built-in lists are not the two schema 1 names")
            highest = maxOf(highest, list.id)
        }
        if (UserData.FAVORITES_ID !in listIds || UserData.WANT_TO_GO_ID !in listIds) invalid("a built-in list is missing")

        val savedIds = HashSet<Long>()
        for (saved in data.saved) {
            if (saved.id < 1 || !savedIds.add(saved.id)) invalid("a saved place id is not positive and unique")
            if (saved.listId !in listIds) invalid("a saved place is in a list that does not exist")
            if (saved.savedAt < 0) invalid("a saved time is negative")
            place(saved.place, "a saved place")
            highest = maxOf(highest, saved.id)
        }
        for (recent in data.recents) {
            if (recent.seenAt < 0) invalid("a recent time is negative")
            place(recent.place, "a recent place")
        }
        data.home?.let { place(it, "Home") }
        data.work?.let { place(it, "Work") }
        if (data.wifiMarks.size > MAX_WIFI_MARKS) invalid("more than $MAX_WIFI_MARKS Wi-Fi marks")
        val marked = HashSet<String>()
        for (mark in data.wifiMarks) {
            if (mark.osmRef.isEmpty()) invalid("a Wi-Fi mark has no OSM reference")
            text(mark.osmRef, MAX_OSM_REF, "a Wi-Fi mark's OSM reference")
            if (!marked.add(mark.osmRef)) invalid("two Wi-Fi marks are for the same spot")
            if (mark.markedAt < 0) invalid("a Wi-Fi mark's time is negative")
        }
        // The next id is lastId + 1, so it must be past every id in use.
        if (data.lastId < highest) invalid("lastId is below an id in use")
    }

    private fun place(p: PlaceRef, what: String) {
        if (!p.lat.isFinite() || p.lat < -90.0 || p.lat > 90.0) invalid("$what has a latitude outside -90..90")
        if (!p.lon.isFinite() || p.lon < -180.0 || p.lon > 180.0) invalid("$what has a longitude outside -180..180")
        text(p.name, MAX_NAME, "$what's name")
        p.address?.let { text(it, MAX_ADDRESS, "$what's address") }
        p.category?.let { text(it, MAX_CATEGORY, "$what's category") }
        p.osmRef?.let { text(it, MAX_OSM_REF, "$what's OSM reference") }
    }

    private fun text(s: String, max: Int, what: String) {
        if (s.length > max) invalid("$what is longer than $max characters")
        if (!s.isWellFormed()) invalid("$what is not valid text")
    }
}
