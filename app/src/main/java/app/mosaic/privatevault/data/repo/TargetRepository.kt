package app.mosaic.privatevault.data.repo

import app.mosaic.privatevault.data.secure.SecureStore
import app.mosaic.privatevault.domain.model.TargetIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Holds the one real identity Mosaic tracks, and nothing else.
 *
 * Reads go through here so there is a single place to audit: no other layer is
 * allowed to touch [SecureStore.KEY_TARGET_HANDLE].
 */
class TargetRepository(private val store: SecureStore) {

    private val state = MutableStateFlow(read())

    /**
     * Only *whether* a target exists is observable. The identity itself is
     * pull-only, so no Compose recomposition can accidentally surface it.
     */
    val isConfigured: Flow<Boolean> = state.map { it?.isConfigured == true }

    fun current(): TargetIdentity? = state.value

    fun save(identity: TargetIdentity) {
        identity.handle?.takeIf { it.isNotBlank() }
            ?.let { store.putString(SecureStore.KEY_TARGET_HANDLE, it.removePrefix("@")) }
            ?: store.remove(SecureStore.KEY_TARGET_HANDLE)
        store.putString(SecureStore.KEY_TARGET_DISPLAY_NAME, identity.displayName)
        identity.threadKey?.takeIf { it.isNotBlank() }
            ?.let { store.putString(SecureStore.KEY_TARGET_THREAD_ID, it) }
            ?: store.remove(SecureStore.KEY_TARGET_THREAD_ID)
        state.value = identity
    }

    /**
     * The person changed their @. The thread key and the vault stay as they are —
     * it is the same conversation — only the handle is refreshed.
     */
    fun updateHandle(handle: String?) {
        val existing = current() ?: return
        save(existing.copy(handle = handle))
    }

    fun clear() {
        store.remove(SecureStore.KEY_TARGET_HANDLE)
        store.remove(SecureStore.KEY_TARGET_DISPLAY_NAME)
        store.remove(SecureStore.KEY_TARGET_THREAD_ID)
        state.value = null
    }

    private fun read(): TargetIdentity? {
        val displayName = store.getString(SecureStore.KEY_TARGET_DISPLAY_NAME)
        val handle = store.getString(SecureStore.KEY_TARGET_HANDLE)
        if (displayName.isNullOrBlank() && handle.isNullOrBlank()) return null
        return TargetIdentity(
            handle = handle,
            displayName = displayName ?: handle.orEmpty(),
            threadKey = store.getString(SecureStore.KEY_TARGET_THREAD_ID),
        )
    }
}
