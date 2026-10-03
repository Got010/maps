package app.southbound.core.packs

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Packs installed while the app runs, by import or by the updater, so open
 * screens can switch to the new data (e.g. reload the map).
 */
object PackEvents {
    private val _installed = MutableSharedFlow<ImportedPack>(extraBufferCapacity = 16)
    val installed: SharedFlow<ImportedPack> = _installed.asSharedFlow()

    fun emit(pack: ImportedPack) {
        _installed.tryEmit(pack)
    }
}
