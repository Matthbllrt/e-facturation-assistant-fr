package app.mosaic.privatevault.domain.notify

import app.mosaic.privatevault.domain.model.TargetIdentity
import java.text.Normalizer

/**
 * Decides whether a notification belongs to the one person Mosaic is watching.
 *
 * Everything else — other conversations, likes, follows, story pings, upload
 * progress — is discarded before it is ever written down. That is the whole
 * point: Mosaic is a vault for one thread, not a notification log.
 */
class NotificationMatcher {

    sealed interface Result {
        /** Belongs to the target and was identified by its conversation key. */
        data object Matched : Result

        /**
         * Belongs to the target by display name only, because Android gave no
         * conversation key. Accepted, but the caller surfaces a warning: a
         * different person with the same display name would land here too.
         */
        data object MatchedByNameOnly : Result

        data class Ignored(val reason: Reason) : Result
    }

    enum class Reason {
        NOT_INSTAGRAM,
        GROUP_SUMMARY,
        ONGOING,
        NOT_A_MESSAGE,
        EMPTY,
        OTHER_CONVERSATION,
        NO_TARGET,
    }

    fun match(candidate: NotificationCandidate, target: TargetIdentity?): Result {
        if (candidate.packageName !in INSTAGRAM_PACKAGES) {
            return Result.Ignored(Reason.NOT_INSTAGRAM)
        }
        // The bundled "3 nouveaux messages" parent carries no usable content and
        // would otherwise be stored as a bogus message.
        if (candidate.isGroupSummary) return Result.Ignored(Reason.GROUP_SUMMARY)
        // Upload / live progress notifications.
        if (candidate.isOngoing) return Result.Ignored(Reason.ONGOING)
        if (!candidate.looksLikeMessage && !candidate.hasRemoteInput) {
            return Result.Ignored(Reason.NOT_A_MESSAGE)
        }
        if (candidate.text.isNullOrBlank()) return Result.Ignored(Reason.EMPTY)
        if (target == null || !target.isConfigured) return Result.Ignored(Reason.NO_TARGET)

        val targetKey = target.threadKey
        val candidateKey = candidate.shortcutId
        if (!targetKey.isNullOrBlank() && !candidateKey.isNullOrBlank()) {
            // Both sides know the conversation key: it is authoritative, and it
            // is the only thing that separates two people with the same name.
            return if (targetKey == candidateKey) Result.Matched
            else Result.Ignored(Reason.OTHER_CONVERSATION)
        }

        return if (matchesByName(candidate, target)) Result.MatchedByNameOnly
        else Result.Ignored(Reason.OTHER_CONVERSATION)
    }

    private fun matchesByName(candidate: NotificationCandidate, target: TargetIdentity): Boolean {
        val names = listOfNotNull(candidate.conversationTitle, candidate.title)
            .map(::normalizeName)
            .filter { it.isNotEmpty() }
        if (names.isEmpty()) return false

        val expected = normalizeName(target.displayName)
        if (expected.isNotEmpty() && names.any { it == expected }) return true

        val handle = target.handle?.removePrefix("@")?.let(::normalizeName).orEmpty()
        return handle.isNotEmpty() && names.any { it == handle }
    }

    /**
     * Lowercases, drops diacritics and strips the unread counter Instagram
     * appends to a conversation title ("Louis (3)"), so that the same person
     * matches whether or not there are pending messages.
     */
    fun normalizeName(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val withoutCounter = UNREAD_COUNTER.replace(raw, "")
        val decomposed = Normalizer.normalize(withoutCounter, Normalizer.Form.NFD)
        return decomposed
            .replace(DIACRITICS, "")
            .replace(WHITESPACE, " ")
            .trim()
            .lowercase()
    }

    companion object {
        /** Only Meta's own Instagram builds. No third-party client is trusted. */
        val INSTAGRAM_PACKAGES = setOf(
            "com.instagram.android",
            "com.instagram.lite",
        )

        private val UNREAD_COUNTER = Regex("\\s*\\(\\d+\\)\\s*$")
        private val DIACRITICS = Regex("\\p{Mn}+")
        private val WHITESPACE = Regex("\\s+")
    }
}
