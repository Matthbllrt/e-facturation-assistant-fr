package app.mosaic.privatevault.sync.notification

import app.mosaic.privatevault.domain.notify.NotificationCandidate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The setup-time list of Instagram conversations Mosaic has seen a notification
 * from, so the user can pick a person without typing a handle.
 *
 * Held in memory only, active only while the picker screen is open, and cleared
 * on exit: this is a chooser, not a second log of everyone who writes to you.
 */
object DiscoveryFeed {

    data class Candidate(
        val displayName: String,
        val conversationKey: String?,
        val lastSeen: Long,
    )

    private val entries = MutableStateFlow<List<Candidate>>(emptyList())
    val observed: StateFlow<List<Candidate>> = entries.asStateFlow()

    @Volatile
    var isActive: Boolean = false
        private set

    fun start() {
        isActive = true
    }

    fun stop() {
        isActive = false
        entries.value = emptyList()
    }

    fun offer(candidate: NotificationCandidate) {
        if (candidate.isGroupSummary || candidate.isOngoing) return
        if (!candidate.looksLikeMessage && !candidate.hasRemoteInput) return
        val name = candidate.conversationTitle?.takeIf { it.isNotBlank() }
            ?: candidate.title?.takeIf { it.isNotBlank() }
            ?: return

        val key = candidate.shortcutId
        entries.value = buildList {
            addAll(entries.value.filterNot { it.displayName == name && it.conversationKey == key })
            add(Candidate(displayName = name, conversationKey = key, lastSeen = candidate.postTime))
        }.sortedByDescending { it.lastSeen }.take(MAX_ENTRIES)
    }

    private const val MAX_ENTRIES = 20
}
