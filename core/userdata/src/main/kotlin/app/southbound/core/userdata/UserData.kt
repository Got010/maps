package app.southbound.core.userdata

/** A place as the user keeps it: enough to show a card and route to it without the search pack. */
data class PlaceRef(
    val name: String,
    val lat: Double,
    val lon: Double,
    val address: String? = null,
    /** OSM value such as "cafe"; drives the card's icon and subtitle. */
    val category: String? = null,
    /** "n123", "w456" or "r789" when the place came from the search pack. */
    val osmRef: String? = null,
) {
    /** Two refs are the same place when they share an OSM object, or sit at the same spot with the same name. */
    fun sameAs(other: PlaceRef): Boolean =
        if (osmRef != null && other.osmRef != null) osmRef == other.osmRef else name == other.name && key() == other.key()

    internal fun key(): String = String.format(java.util.Locale.ROOT, "%.5f,%.5f", lat, lon)
}

/** The two special places the search box offers in one tap. */
enum class Shortcut { HOME, WORK }

/** A named list of saved places. [builtIn] lists can't be deleted. */
data class PlaceList(val id: Long, val name: String, val builtIn: Boolean = false)

data class SavedPlace(val id: Long, val listId: Long, val place: PlaceRef, val savedAt: Long)

/** A place the user looked at, newest first in [UserData.recents]. */
data class RecentPlace(val place: PlaceRef, val seenAt: Long)

/**
 * Everything the user has kept: lists, the places in them and recents. Small,
 * so it lives whole in memory and is written whole, encrypted, on every change.
 */
data class UserData(
    val lists: List<PlaceList> = DEFAULT_LISTS,
    val saved: List<SavedPlace> = emptyList(),
    val recents: List<RecentPlace> = emptyList(),
    /** Ids handed out so far; never reused, so a deleted list's id can't come back. */
    val lastId: Long = DEFAULT_LISTS.maxOf { it.id },
    // Phase 6: Home and Work shortcuts.
    val home: PlaceRef? = null,
    val work: PlaceRef? = null,
) {
    fun shortcut(kind: Shortcut): PlaceRef? = if (kind == Shortcut.HOME) home else work

    /** Which shortcut [place] is, if any; Home wins when both point at the same place. */
    fun shortcutsOf(place: PlaceRef): Set<Shortcut> =
        listOfNotNull(Shortcut.HOME.takeIf { home?.sameAs(place) == true }, Shortcut.WORK.takeIf { work?.sameAs(place) == true }).toSet()

    /** Sets or, with null, clears a shortcut. */
    fun withShortcut(kind: Shortcut, place: PlaceRef?): UserData =
        if (kind == Shortcut.HOME) copy(home = place) else copy(work = place)

    fun listsContaining(place: PlaceRef): List<PlaceList> {
        val ids = saved.filter { it.place.sameAs(place) }.map { it.listId }.toSet()
        return lists.filter { it.id in ids }
    }

    fun isSaved(place: PlaceRef): Boolean = saved.any { it.place.sameAs(place) }

    fun placesIn(listId: Long): List<SavedPlace> = saved.filter { it.listId == listId }.sortedByDescending { it.savedAt }

    fun save(place: PlaceRef, listId: Long, now: Long): UserData {
        require(lists.any { it.id == listId }) { "No list $listId" }
        if (saved.any { it.listId == listId && it.place.sameAs(place) }) return this
        val id = lastId + 1
        return copy(saved = saved + SavedPlace(id, listId, place, now), lastId = id)
    }

    fun unsave(place: PlaceRef, listId: Long): UserData =
        copy(saved = saved.filterNot { it.listId == listId && it.place.sameAs(place) })

    fun createList(name: String): UserData {
        val id = lastId + 1
        return copy(lists = lists + PlaceList(id, name.trim()), lastId = id)
    }

    fun renameList(listId: Long, name: String): UserData =
        copy(lists = lists.map { if (it.id == listId) it.copy(name = name.trim()) else it })

    /** Removes the list and what's in it; built-in lists stay. */
    fun deleteList(listId: Long): UserData {
        if (lists.firstOrNull { it.id == listId }?.builtIn != false) return this
        return copy(lists = lists.filterNot { it.id == listId }, saved = saved.filterNot { it.listId == listId })
    }

    /** Puts [place] at the top of recents, dropping an older entry for it and anything past [MAX_RECENTS]. */
    fun recordRecent(place: PlaceRef, now: Long): UserData =
        copy(recents = (listOf(RecentPlace(place, now)) + recents.filterNot { it.place.sameAs(place) }).take(MAX_RECENTS))

    fun forgetRecent(place: PlaceRef): UserData = copy(recents = recents.filterNot { it.place.sameAs(place) })

    fun clearRecents(): UserData = copy(recents = emptyList())

    companion object {
        const val FAVORITES_ID = 1L
        const val WANT_TO_GO_ID = 2L
        const val MAX_RECENTS = 50

        val DEFAULT_LISTS: List<PlaceList> = listOf(
            PlaceList(FAVORITES_ID, "Favorites", builtIn = true),
            PlaceList(WANT_TO_GO_ID, "Want to go", builtIn = true),
        )
    }
}
