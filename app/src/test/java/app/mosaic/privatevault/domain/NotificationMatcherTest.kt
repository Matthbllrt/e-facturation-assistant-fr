package app.mosaic.privatevault.domain

import app.mosaic.privatevault.domain.model.TargetIdentity
import app.mosaic.privatevault.domain.notify.NotificationCandidate
import app.mosaic.privatevault.domain.notify.NotificationMatcher
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationMatcherTest {

    private val matcher = NotificationMatcher()
    private val target = TargetIdentity(
        handle = "camille_r",
        displayName = "Camille R",
        threadKey = "thread-abc",
    )

    private fun candidate(
        packageName: String = "com.instagram.android",
        title: String? = "Camille R",
        text: String? = "à ce soir",
        conversationTitle: String? = null,
        shortcutId: String? = "thread-abc",
        isGroupSummary: Boolean = false,
        isOngoing: Boolean = false,
        looksLikeMessage: Boolean = true,
        hasRemoteInput: Boolean = true,
    ) = NotificationCandidate(
        packageName = packageName,
        title = title,
        text = text,
        conversationTitle = conversationTitle,
        shortcutId = shortcutId,
        isGroupSummary = isGroupSummary,
        isOngoing = isOngoing,
        looksLikeMessage = looksLikeMessage,
        hasRemoteInput = hasRemoteInput,
        postTime = 1_700_000_000_000L,
    )

    @Test
    fun `the target conversation matches on its conversation key`() {
        assertEquals(NotificationMatcher.Result.Matched, matcher.match(candidate(), target))
    }

    @Test
    fun `notifications from other apps are ignored`() {
        val result = matcher.match(candidate(packageName = "com.whatsapp"), target)
        assertEquals(NotificationMatcher.Result.Ignored(NotificationMatcher.Reason.NOT_INSTAGRAM), result)
    }

    @Test
    fun `a third-party Instagram client is not trusted`() {
        val result = matcher.match(candidate(packageName = "com.example.instaclient"), target)
        assertEquals(NotificationMatcher.Result.Ignored(NotificationMatcher.Reason.NOT_INSTAGRAM), result)
    }

    @Test
    fun `the grouped summary notification is ignored`() {
        val result = matcher.match(candidate(isGroupSummary = true), target)
        assertEquals(NotificationMatcher.Result.Ignored(NotificationMatcher.Reason.GROUP_SUMMARY), result)
    }

    @Test
    fun `upload progress notifications are ignored`() {
        val result = matcher.match(candidate(isOngoing = true), target)
        assertEquals(NotificationMatcher.Result.Ignored(NotificationMatcher.Reason.ONGOING), result)
    }

    @Test
    fun `likes and follows are ignored because they are not messages`() {
        val result = matcher.match(
            candidate(
                title = "camille_r",
                text = "a aimé votre publication",
                looksLikeMessage = false,
                hasRemoteInput = false,
            ),
            target,
        )
        assertEquals(NotificationMatcher.Result.Ignored(NotificationMatcher.Reason.NOT_A_MESSAGE), result)
    }

    @Test
    fun `another conversation with a different key is ignored`() {
        val result = matcher.match(candidate(title = "Alex", shortcutId = "thread-zzz"), target)
        assertEquals(
            NotificationMatcher.Result.Ignored(NotificationMatcher.Reason.OTHER_CONVERSATION),
            result,
        )
    }

    @Test
    fun `a namesake with a different conversation key is rejected`() {
        // The whole point of pinning the conversation key: same displayed name,
        // different person.
        val result = matcher.match(candidate(title = "Camille R", shortcutId = "thread-other"), target)
        assertEquals(
            NotificationMatcher.Result.Ignored(NotificationMatcher.Reason.OTHER_CONVERSATION),
            result,
        )
    }

    @Test
    fun `without a conversation key the name match is accepted but flagged`() {
        val nameOnlyTarget = target.copy(threadKey = null)
        val result = matcher.match(candidate(shortcutId = null), nameOnlyTarget)
        assertEquals(NotificationMatcher.Result.MatchedByNameOnly, result)
    }

    @Test
    fun `the unread counter suffix does not break name matching`() {
        val nameOnlyTarget = target.copy(threadKey = null)
        val result = matcher.match(candidate(title = "Camille R (3)", shortcutId = null), nameOnlyTarget)
        assertEquals(NotificationMatcher.Result.MatchedByNameOnly, result)
    }

    @Test
    fun `accents and case do not break name matching`() {
        val accented = TargetIdentity(handle = null, displayName = "Éloïse Ferré", threadKey = null)
        val result = matcher.match(candidate(title = "eloise ferre", shortcutId = null), accented)
        assertEquals(NotificationMatcher.Result.MatchedByNameOnly, result)
    }

    @Test
    fun `the handle also identifies the person when the title uses it`() {
        val nameOnlyTarget = target.copy(threadKey = null, displayName = "")
        val result = matcher.match(candidate(title = "camille_r", shortcutId = null), nameOnlyTarget)
        assertEquals(NotificationMatcher.Result.MatchedByNameOnly, result)
    }

    @Test
    fun `an empty notification body is ignored`() {
        val result = matcher.match(candidate(text = "  "), target)
        assertEquals(NotificationMatcher.Result.Ignored(NotificationMatcher.Reason.EMPTY), result)
    }

    @Test
    fun `nothing is captured before a target is chosen`() {
        val result = matcher.match(candidate(), target = null)
        assertEquals(NotificationMatcher.Result.Ignored(NotificationMatcher.Reason.NO_TARGET), result)
    }
}
