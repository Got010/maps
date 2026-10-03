package app.southbound.core.userdata

import app.southbound.core.network.MiniJson

/** The on-disk JSON for [UserData], before encryption. Versioned so a later app can migrate it. */
object UserDataCodec {
    const val VERSION = 1

    fun encode(data: UserData): String = MiniJson.stringify(
        mapOf(
            "version" to VERSION,
            "lastId" to data.lastId,
            "lists" to data.lists.map { mapOf("id" to it.id, "name" to it.name, "builtIn" to it.builtIn) },
            "saved" to data.saved.map { mapOf("id" to it.id, "list" to it.listId, "savedAt" to it.savedAt, "place" to place(it.place)) },
            "recents" to data.recents.map { mapOf("seenAt" to it.seenAt, "place" to place(it.place)) },
        ),
    )

    /** @throws IllegalArgumentException when the text isn't a user data document. */
    fun decode(json: String): UserData {
        val root = MiniJson.parse(json) as? Map<*, *> ?: throw IllegalArgumentException("user data: not an object")
        val version = root["version"] as? Long ?: throw IllegalArgumentException("user data: no version")
        if (version > VERSION) throw IllegalArgumentException("user data: version $version is newer than this app")
        val lists = (root["lists"] as? List<*>).orEmpty().map { item ->
            val m = item as Map<*, *>
            PlaceList(m.long("id"), m.string("name"), m["builtIn"] == true)
        }
        val saved = (root["saved"] as? List<*>).orEmpty().map { item ->
            val m = item as Map<*, *>
            SavedPlace(m.long("id"), m.long("list"), placeRef(m["place"]), m.long("savedAt"))
        }
        val recents = (root["recents"] as? List<*>).orEmpty().map { item ->
            val m = item as Map<*, *>
            RecentPlace(placeRef(m["place"]), m.long("seenAt"))
        }
        val lastId = (root["lastId"] as? Long) ?: maxOf(lists.maxOfOrNull { it.id } ?: 0, saved.maxOfOrNull { it.id } ?: 0)
        return UserData(lists.ifEmpty { UserData.DEFAULT_LISTS }, saved, recents, lastId)
    }

    private fun place(p: PlaceRef): Map<String, Any?> = mapOf(
        "name" to p.name, "lat" to p.lat, "lon" to p.lon, "address" to p.address, "category" to p.category, "osmRef" to p.osmRef,
    )

    private fun placeRef(value: Any?): PlaceRef {
        val m = value as? Map<*, *> ?: throw IllegalArgumentException("user data: place is not an object")
        return PlaceRef(
            name = m.string("name"),
            lat = m.double("lat"),
            lon = m.double("lon"),
            address = m["address"] as? String,
            category = m["category"] as? String,
            osmRef = m["osmRef"] as? String,
        )
    }

    private fun Map<*, *>.long(key: String): Long = (this[key] as? Number)?.toLong() ?: throw IllegalArgumentException("user data: no $key")
    private fun Map<*, *>.double(key: String): Double = (this[key] as? Number)?.toDouble() ?: throw IllegalArgumentException("user data: no $key")
    private fun Map<*, *>.string(key: String): String = this[key] as? String ?: throw IllegalArgumentException("user data: no $key")
}
