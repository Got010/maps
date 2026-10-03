package app.southbound.core.userdata

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Saved places, lists and recents, held in memory and written whole to one
 * encrypted file on every change (HANDOFF: history and saved places stay
 * on-device, encrypted at rest). The file is excluded from backups by living
 * under the app's no-backup directory, which the caller passes in.
 *
 * A missing file is a first run and loads empty. A file that exists but can't be
 * opened (wrong key, damaged, written by a newer version) loads as
 * [LoadState.Unreadable]: it is left exactly where it is and every save is refused
 * until [startFresh] sets it aside under a new name. Nothing here ever deletes it.
 *
 * Reads are instant; [update] writes on the calling thread, so call it off
 * the main thread or from a coroutine on IO.
 */
class UserDataStore(private val file: File, private val sealer: Sealer, private val clock: () -> Long = System::currentTimeMillis) {

    private val _loadState = MutableStateFlow<LoadState>(LoadState.Loaded)
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    // Empty while unreadable; the file on disk is what matters then.
    private val _data = MutableStateFlow(load())
    val data: StateFlow<UserData> = _data.asStateFlow()

    val now: Long get() = clock()

    /**
     * Applies [transform] and saves. A write failure keeps the old data and rethrows.
     * Throws [UserDataUnreadableException] without touching the file while it is unreadable.
     */
    @Synchronized
    fun update(transform: (UserData) -> UserData): UserData {
        (_loadState.value as? LoadState.Unreadable)?.let { throw UserDataUnreadableException(it.reason) }
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

    /**
     * The user's choice after an unreadable file: renames it to `<name>.unreadable-<timestamp>`
     * (never deletes it), then creates an empty store. Throws and stays unreadable if the rename
     * fails. Does nothing when the store is readable.
     */
    @Synchronized
    fun startFresh() {
        if (_loadState.value !is LoadState.Unreadable) return
        if (file.exists()) {
            var target = File(file.parentFile, "${file.name}.unreadable-${clock()}")
            var n = 1
            while (target.exists()) target = File(file.parentFile, "${file.name}.unreadable-${clock()}-${n++}")
            Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        val fresh = UserData()
        write(fresh)
        _data.value = fresh
        _loadState.value = LoadState.Loaded
    }

    private fun load(): UserData {
        if (!file.exists()) return UserData() // first run
        return try {
            UserDataCodec.decode(sealer.open(file.readBytes()).decodeToString())
        } catch (e: Exception) {
            _loadState.value = LoadState.Unreadable(e.javaClass.simpleName)
            UserData()
        }
    }

    /** Temp file in the same directory, fsynced, then renamed over the original. */
    private fun write(data: UserData) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.part")
        try {
            FileOutputStream(temp).use { out ->
                out.write(sealer.seal(UserDataCodec.encode(data).toByteArray()))
                out.fd.sync()
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            temp.delete()
            throw if (e is IOException) e else IOException("Couldn't save ${file.name}", e)
        }
    }

    companion object {
        const val FILE_NAME = "places.sealed"
    }
}

/** Whether the saved file was opened. */
sealed interface LoadState {
    data object Loaded : LoadState

    /** The file exists but could not be read; it has been left untouched. [reason] is for diagnosis, not for show. */
    data class Unreadable(val reason: String) : LoadState
}

/** A save was refused because the existing file could not be read and has not been set aside yet. */
class UserDataUnreadableException(reason: String) : IOException("Saved places file is unreadable ($reason); not overwriting it")
