package app.mosaic.privatevault.ui.conversation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mosaic.privatevault.MosaicGraph
import app.mosaic.privatevault.core.log.SafeLog
import app.mosaic.privatevault.data.repo.ConversationRepository
import app.mosaic.privatevault.data.repo.MessageCapture
import app.mosaic.privatevault.domain.alias.Alias
import app.mosaic.privatevault.domain.model.DeliveryStatus
import app.mosaic.privatevault.domain.model.MessageDirection
import app.mosaic.privatevault.domain.model.MessageSource
import app.mosaic.privatevault.domain.model.MosaicSettings
import app.mosaic.privatevault.domain.model.SyncMode
import app.mosaic.privatevault.domain.model.VaultMessage
import app.mosaic.privatevault.sync.api.InstagramSync
import app.mosaic.privatevault.sync.notification.AmbiguityFlag
import app.mosaic.privatevault.sync.notification.MosaicNotificationListener
import app.mosaic.privatevault.sync.notification.ReplyDispatcher
import app.mosaic.privatevault.ui.common.MosaicIntents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How a reply can be delivered right now. Drives which button the user sees. */
enum class ReplyCapability {
    /** Official Send API is configured and usable. */
    OFFICIAL_API,

    /** Instagram's own notification reply action is live. */
    REMOTE_INPUT,

    /** Neither: the only honest option is to hand off to Instagram. */
    OPEN_INSTAGRAM_ONLY,
}

data class ConversationUiState(
    val messages: List<VaultMessage> = emptyList(),
    val alias: String = MosaicSettings.DEFAULT_ALIAS,
    val avatarSeed: Int = 0,
    val syncMode: SyncMode = SyncMode.UNSET,
    val replyCapability: ReplyCapability = ReplyCapability.OPEN_INSTAGRAM_ONLY,
    val cleanupReady: Boolean = false,
    val captureHealthy: Boolean = true,
    val nameOnlyMatching: Boolean = false,
    val vaultUnreadable: Boolean = false,
    val notice: String? = null,
)

class ConversationViewModel(application: Application) : AndroidViewModel(application) {

    private val notices = MutableStateFlow<String?>(null)

    private val messages: StateFlow<List<VaultMessage>> =
        (MosaicGraph.conversations()?.observeMessages() ?: emptyFlow())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val state: StateFlow<ConversationUiState> = combine(
        messages,
        MosaicGraph.settings.settings,
        MosaicGraph.settings.cleanupReadyAt,
        ReplyDispatcher.available,
        AmbiguityFlag.nameOnlyMatching,
    ) { messages, settings, cleanupReadyAt, replyHandle, nameOnly ->
        val apiReady = settings.syncMode == SyncMode.OFFICIAL_API && MosaicGraph.api.hasToken()
        ConversationUiState(
            messages = messages,
            alias = Alias.displayName(settings),
            avatarSeed = settings.avatarSeed.takeIf { it != 0 }
                ?: Alias.avatarSeed(Alias.displayName(settings)),
            syncMode = settings.syncMode,
            replyCapability = when {
                apiReady -> ReplyCapability.OFFICIAL_API
                replyHandle != null -> ReplyCapability.REMOTE_INPUT
                else -> ReplyCapability.OPEN_INSTAGRAM_ONLY
            },
            cleanupReady = cleanupReadyAt != null,
            captureHealthy = when (settings.syncMode) {
                SyncMode.ANDROID_NOTIFICATIONS ->
                    MosaicNotificationListener.isPermissionGranted(getApplication())

                SyncMode.OFFICIAL_API -> MosaicGraph.api.hasToken()
                SyncMode.UNSET -> false
            },
            nameOnlyMatching = nameOnly,
            vaultUnreadable = MosaicGraph.vaultState.value is MosaicGraph.VaultState.Unreadable,
            notice = notices.value,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationUiState())

    val notice: StateFlow<String?> = notices.asStateFlow()

    fun dismissNotice() {
        notices.value = null
    }

    /**
     * Sends through whichever channel is genuinely available, and records the
     * outgoing message locally either way — including when the user is handed
     * off to Instagram, because the local vault is the point of the app.
     */
    fun send(text: String, onOpenInstagram: () -> Unit) {
        val body = text.trim()
        if (body.isEmpty()) return
        val repository = MosaicGraph.conversations()
        if (repository == null) {
            notices.value = getApplication<Application>()
                .getString(app.mosaic.privatevault.R.string.error_vault_unavailable)
            return
        }

        viewModelScope.launch {
            when (state.value.replyCapability) {
                ReplyCapability.OFFICIAL_API -> {
                    val localId = store(repository, body, DeliveryStatus.PENDING)
                    val sync = InstagramSync(MosaicGraph.api, MosaicGraph.target)
                    when (val result = sync.send(body)) {
                        is InstagramSync.SendResult.Sent ->
                            repository.markStatus(localId, DeliveryStatus.SENT)

                        InstagramSync.SendResult.OutsideMessagingWindow -> {
                            repository.markStatus(localId, DeliveryStatus.FAILED)
                            notices.value = getApplication<Application>()
                                .getString(app.mosaic.privatevault.R.string.error_messaging_window)
                        }

                        is InstagramSync.SendResult.Failed -> {
                            repository.markStatus(localId, DeliveryStatus.FAILED)
                            notices.value = getApplication<Application>()
                                .getString(app.mosaic.privatevault.R.string.error_send_failed)
                        }
                    }
                }

                ReplyCapability.REMOTE_INPUT -> {
                    when (ReplyDispatcher.send(getApplication(), body)) {
                        ReplyDispatcher.Result.Sent -> store(repository, body, DeliveryStatus.SENT)
                        ReplyDispatcher.Result.Unavailable -> {
                            // The notification vanished between render and tap;
                            // ReplyDispatcher has already dropped the handle, so
                            // the UI falls back on the next recomposition.
                            notices.value = getApplication<Application>()
                                .getString(app.mosaic.privatevault.R.string.error_reply_expired)
                        }

                        is ReplyDispatcher.Result.Failed -> {
                            store(repository, body, DeliveryStatus.FAILED)
                            notices.value = getApplication<Application>()
                                .getString(app.mosaic.privatevault.R.string.error_send_failed)
                        }
                    }
                }

                ReplyCapability.OPEN_INSTAGRAM_ONLY -> onOpenInstagram()
            }
        }
    }

    private suspend fun store(
        repository: ConversationRepository,
        body: String,
        status: DeliveryStatus,
    ): Long {
        val outcome = repository.record(
            MessageCapture(
                timestamp = System.currentTimeMillis(),
                direction = MessageDirection.OUTGOING,
                text = body,
                status = status,
                source = MessageSource.LOCAL_ECHO,
            ),
        )
        return when (outcome) {
            is ConversationRepository.Outcome.Stored -> outcome.localId
            is ConversationRepository.Outcome.Duplicate -> outcome.localId
            is ConversationRepository.Outcome.Upgraded -> outcome.localId
            is ConversationRepository.Outcome.Failed -> {
                SafeLog.e("conversation.echo_store_failed")
                -1L
            }
        }
    }

    fun openInstagram() {
        val handle = MosaicGraph.target.current()?.handle
        val result = MosaicIntents.openInstagramConversation(getApplication(), handle)
        if (result == MosaicIntents.OpenResult.NOT_INSTALLED) {
            notices.value = getApplication<Application>()
                .getString(app.mosaic.privatevault.R.string.error_instagram_missing)
        }
    }

    /** The user did the Instagram-side cleanup (or chose to skip it). */
    fun dismissCleanup() {
        viewModelScope.launch { MosaicGraph.settings.clearCleanupReady() }
    }

    fun lockNow() = MosaicGraph.appLock.lock()
}
