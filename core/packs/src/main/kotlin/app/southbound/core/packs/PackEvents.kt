package app.southbound.core.packs

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Packs installed or removed while the app runs, by import, by the updater or
 * from Settings, so open screens can switch to the new data (e.g. reload the
 * map).
 */
object PackEvents {
    private val _installed = MutableSharedFlow<ImportedPack>(extraBufferCapacity = 16)
    val installed: SharedFlow<ImportedPack> = _installed.asSharedFlow()

    private val _removed = MutableSharedFlow<Pair<PackKind, String>>(extraBufferCapacity = 16)

    /** A pack deleted from Settings, as (kind, region). */
    val removed: SharedFlow<Pair<PackKind, String>> = _removed.asSharedFlow()

    fun emit(pack: ImportedPack) {
        _installed.tryEmit(pack)
    }

    fun emitRemoved(kind: PackKind, region: String) {
        _removed.tryEmit(kind to region)
    }
}
