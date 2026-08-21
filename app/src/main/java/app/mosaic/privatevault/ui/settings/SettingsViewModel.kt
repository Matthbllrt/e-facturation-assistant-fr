package app.mosaic.privatevault.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mosaic.privatevault.MosaicGraph
import app.mosaic.privatevault.core.log.SafeLog
import app.mosaic.privatevault.data.export.VaultExporter
import app.mosaic.privatevault.domain.alias.Alias
import app.mosaic.privatevault.domain.model.AutoLockDelay
import app.mosaic.privatevault.domain.model.MosaicSettings
import app.mosaic.privatevault.domain.model.NotificationPolicy
import app.mosaic.privatevault.domain.model.SyncMode
import app.mosaic.privatevault.domain.model.TargetIdentity
import app.mosaic.privatevault.sync.work.ApiSyncWorker
import app.mosaic.privatevault.sync.work.CooldownScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    val settings: StateFlow<MosaicSettings> = MosaicGraph.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MosaicSettings())

    /**
     * The real Instagram identity, revealed only after a fresh authentication
     * and cleared as soon as the user leaves the screen. It is never part of the
     * persistent UI state, so it cannot end up in a saved-state bundle.
     */
    private val revealed = MutableStateFlow<TargetIdentity?>(null)
    val revealedIdentity: StateFlow<TargetIdentity?> = revealed.asStateFlow()

    private val messageEvents = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = messageEvents.asStateFlow()

    fun consumeMessage() {
        messageEvents.value = null
    }

    fun reveal() {
        revealed.value = MosaicGraph.target.current()
    }

    fun hideIdentity() {
        revealed.value = null
    }

    fun setAlias(alias: String) = viewModelScope.launch {
        val clean = Alias.sanitize(alias)
        MosaicGraph.settings.setAlias(clean)
        MosaicGraph.settings.setAvatarSeed(Alias.avatarSeed(clean))
    }

    fun setCooldownSeconds(seconds: Long) = viewModelScope.launch {
        MosaicGraph.settings.setCooldownSeconds(seconds)
        if (seconds <= 0L) {
            CooldownScheduler.cancel(getApplication())
            MosaicGraph.settings.clearCleanupReady()
        }
    }

    fun setNotificationPolicy(policy: NotificationPolicy) = viewModelScope.launch {
        MosaicGraph.settings.setNotificationPolicy(policy)
    }

    fun setAutoLock(delay: AutoLockDelay) = viewModelScope.launch {
        MosaicGraph.settings.setAutoLock(delay)
    }

    fun setScreenshotProtection(enabled: Boolean) = viewModelScope.launch {
        MosaicGraph.settings.setScreenshotProtection(enabled)
    }

    fun setSyncMode(mode: SyncMode) = viewModelScope.launch {
        MosaicGraph.settings.setSyncMode(mode)
        if (mode == SyncMode.OFFICIAL_API && MosaicGraph.api.hasToken()) {
            ApiSyncWorker.enable(getApplication())
        } else {
            ApiSyncWorker.disable(getApplication())
        }
    }

    fun setPin(pin: CharArray): Boolean = MosaicGraph.pinManager.setPin(pin)

    /** Erases the messages, keeps the setup. */
    fun eraseConversation(onDone: () -> Unit) = viewModelScope.launch {
        MosaicGraph.eraseConversationOnly()
        MosaicGraph.settings.clearCleanupReady()
        CooldownScheduler.cancel(getApplication())
        onDone()
    }

    /** Full reset: data, keys, preferences, schedules. */
    fun resetEverything(onDone: () -> Unit) = viewModelScope.launch {
        ApiSyncWorker.disable(getApplication())
        CooldownScheduler.cancel(getApplication())
        MosaicGraph.eraseEverything()
        onDone()
    }

    fun export(uri: Uri, passphrase: CharArray, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val repository = MosaicGraph.conversations() ?: return@withContext false
                val messages = repository.observeMessages().first()
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                        VaultExporter.export(messages, passphrase, it)
                    } ?: false
                }.onFailure { SafeLog.e("export.stream_failed", it) }.getOrDefault(false)
            }
            passphrase.fill(' ')
            onResult(ok)
        }
    }
}
