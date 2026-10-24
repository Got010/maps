package app.southbound.core.userdata

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
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

    private val _refusedSaves = MutableStateFlow(0)

    /** Counts explicit user saves refused because the file is unreadable, so the UI can say so again. */
    val refusedSaves: StateFlow<Int> = _refusedSaves.asStateFlow()

    val now: Long get() = clock()

    /**
     * Applies [transform] and saves. A write failure keeps the old data and rethrows.
     * Throws [UserDataUnreadableException] without touching the file while it is unreadable.
     * Only an [explicit] user action (the default) counts toward [refusedSaves]; automatic
     * writes such as recents are refused quietly.
     */
    @Synchronized
    fun update(explicit: Boolean = true, transform: (UserData) -> UserData): UserData {
        (_loadState.value as? LoadState.Unreadable)?.let {
            if (explicit) _refusedSaves.value++
            throw UserDataUnreadableException(it.reason)
        }
        val next = transform(_data.value)
        if (next != _data.value) {
            write(next)
            _data.value = next
        }
        return next
    }

    fun save(place: PlaceRef, listId: Long) = update { it.save(place, listId, clock()) }
    fun unsave(place: PlaceRef, listId: Long) = update { it.unsave(place, listId) }
    fun recordRecent(place: PlaceRef) = update(explicit = false) { it.recordRecent(place, clock()) }
    fun setShortcut(kind: Shortcut, place: PlaceRef?) = update { it.withShortcut(kind, place) }
    fun markWifi(osmRef: String, worked: Boolean) = update { it.markWifi(osmRef, worked, clock()) }
    fun clearWifiMark(osmRef: String) = update { it.clearWifiMark(osmRef) }

    // The trip log: each an explicit user action, so a refusal while the file is unreadable is counted and shown. A
    // leg out of bounds throws LegRefusedException before anything is written.
    fun addLeg(trip: String, on: String, meters: Long, mode: LegMode, from: String, to: String, edited: Boolean) =
        update { it.addLeg(trip, on, meters, mode, from, to, edited) }
    fun editLeg(id: Long, trip: String, on: String, meters: Long, mode: LegMode, from: String, to: String) =
        update { it.editLeg(id, trip, on, meters, mode, from, to) }
    fun deleteLeg(id: Long) = update { it.deleteLeg(id) }
    fun renameTrip(trip: String, to: String) = update { it.renameTrip(trip, to) }
    fun deleteTrip(trip: String) = update { it.deleteTrip(trip) }

    /**
     * Reads the file again while it is unreadable, since the failure may have been temporary
     * (a Keystore error). On success the store is loaded and saves work; otherwise it stays
     * unreadable and the file is untouched. Does nothing when the store is readable.
     */
    @Synchronized
    fun reload() {
        if (_loadState.value !is LoadState.Unreadable) return
        when (val result = read()) {
            is Read.Ok -> {
                _data.value = result.data
                _loadState.value = LoadState.Loaded
            }
            is Read.Failed -> _loadState.value = LoadState.Unreadable(result.reason)
        }
    }

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
            syncDirectory(file.parentFile)
        }
        val fresh = UserData()
        write(fresh)
        _data.value = fresh
        _loadState.value = LoadState.Loaded
    }

    private fun load(): UserData {
        if (!file.exists()) return UserData() // first run
        return when (val result = read()) {
            is Read.Ok -> result.data
            is Read.Failed -> {
                _loadState.value = LoadState.Unreadable(result.reason)
                UserData()
            }
        }
    }

    private sealed interface Read {
        class Ok(val data: UserData) : Read
        class Failed(val reason: String) : Read
    }

    /** A file that has gone missing since the failed read counts as empty (nothing to protect). */
    private fun read(): Read {
        if (!file.exists()) return Read.Ok(UserData())
        return try {
            Read.Ok(UserDataCodec.decode(sealer.open(file.readBytes()).decodeToString()))
        } catch (e: Exception) {
            Read.Failed(e.javaClass.simpleName)
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
            syncDirectory(file.parentFile)
        } catch (e: Exception) {
            temp.delete()
            throw if (e is IOException) e else IOException("Couldn't save ${file.name}", e)
        }
    }

    /** Flushes a rename to disk so power loss cannot undo it. Best effort: some systems refuse to open a directory. */
    private fun syncDirectory(dir: File?) {
        if (dir == null) return
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: Exception) {
            // Opening a directory for reading can fail (e.g. on Windows or a restrictive filesystem); the rename itself has already happened.
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
