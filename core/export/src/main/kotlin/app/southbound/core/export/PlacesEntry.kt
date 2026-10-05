package app.southbound.core.export

import app.southbound.core.userdata.PlaceRef
import app.southbound.core.userdata.UserData
import app.southbound.core.userdata.UserDataCodec

/**
 * `places.json`: the saved places file's own JSON ([UserDataCodec], version 1), checked far more strictly than
 * the app's own reader is, because here the text comes from outside. Export runs the same check before a byte is
 * written, so it refuses what import would.
 */
internal object PlacesEntry {
    const val MAX_LISTS = 500
    const val MAX_SAVED = 40_000
    const val MAX_LIST_NAME = 1_024
    const val MAX_NAME = 2_048
    const val MAX_ADDRESS = 4_096
    const val MAX_CATEGORY = 256
    const val MAX_OSM_REF = 64

    fun encode(data: UserData): ByteArray {
        check(data)
        return UserDataCodec.encode(data).toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): UserData {
        val text = utf8(bytes)
        val data = try {
            UserDataCodec.decode(text)
        } catch (e: Exception) {
            // IllegalArgumentException from the codec, or a wrong type inside the document.
            invalid("places.json can't be read")
        }
        check(data)
        return data
    }

    /** The rules of schema 1. Notes name the field, never its contents. */
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
