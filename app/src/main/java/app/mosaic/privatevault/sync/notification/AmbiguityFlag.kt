package app.mosaic.privatevault.sync.notification

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Raised when the target could only be identified by display name.
 *
 * Two different people can call themselves the same thing; without a
 * conversation key Mosaic cannot tell them apart, and the honest response is to
 * say so in the header rather than to quietly file a stranger's messages under
 * the alias.
 */
object AmbiguityFlag {
    private val state = MutableStateFlow(false)
    val nameOnlyMatching: StateFlow<Boolean> = state.asStateFlow()

    fun raise() {
        state.value = true
    }

    fun clear() {
        state.value = false
    }
}
