package app.southbound.core.userdata

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

/**
 * Saved places, lists and recents, held in memory and written whole to one
 * encrypted file on every change (HANDOFF: history and saved places stay
 * on-device, encrypted at rest). The file is excluded from backups by living
 * under the app's no-backup directory, which the caller passes in.
 *
 * Reads are instant; [update] writes on the calling thread, so call it off
 * the main thread or from a coroutine on IO.
 */
class UserDataStore(private val file: File, private val sealer: Sealer, private val clock: () -> Long = System::currentTimeMillis) {

    private val _data = MutableStateFlow(load())
    val data: StateFlow<UserData> = _data.asStateFlow()

    val now: Long get() = clock()

    /** Applies [transform] and saves. A write failure keeps the old data and rethrows. */
    @Synchronized
    fun update(transform: (UserData) -> UserData): UserData {
        val next = transform(_data.value)
        if (next != _data.value) {
            write(next)
            _data.value = next
        }
        return next
    }

    fun save(place: PlaceRef, listId: Long) = update { it.save(place, listId, clock()) }
    fun unsave(place: PlaceRef, listId: Long) = update { it.unsave(place, listId) }
    fun recordRecent(place: PlaceRef) = update { it.recordRecent(place, clock()) }
    fun setShortcut(kind: Shortcut, place: PlaceRef?) = update { it.withShortcut(kind, place) }

    private fun load(): UserData {
        if (!file.isFile) return UserData()
        return try {
            UserDataCodec.decode(sealer.open(file.readBytes()).decodeToString())
        } catch (e: Exception) {
            // Unreadable (a key lost with a factory reset, or a damaged file): start over
            // rather than refuse to open; the file is rewritten on the next change.
            UserData()
        }
    }

    private fun write(data: UserData) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.part")
        temp.writeBytes(sealer.seal(UserDataCodec.encode(data).toByteArray()))
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("Couldn't save ${file.name}")
        }
    }

    companion object {
        const val FILE_NAME = "places.sealed"
    }
}
