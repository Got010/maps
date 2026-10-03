package app.southbound.core.updates

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The updater's state for this process; the worker writes it, screens read it. */
object UpdateStatus {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun set(state: UpdateState) {
        _state.value = state
    }

    /** Android's "Update Southbound?" screen, for the open app to show; null when none waits. */
    private val _confirm = MutableStateFlow<Intent?>(null)
    val confirm: StateFlow<Intent?> = _confirm.asStateFlow()

    fun requestConfirm(intent: Intent) {
        _confirm.value = intent
    }

    fun confirmShown() {
        _confirm.value = null
    }
}
