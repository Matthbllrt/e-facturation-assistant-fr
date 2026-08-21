package app.mosaic.privatevault.domain

import app.mosaic.privatevault.domain.dedup.MessageDeduplicator
import app.mosaic.privatevault.domain.model.DeliveryStatus
import app.mosaic.privatevault.domain.model.MediaKind
import app.mosaic.privatevault.domain.model.MessageDirection
import app.mosaic.privatevault.domain.model.MessageSource
import app.mosaic.privatevault.domain.model.VaultMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDeduplicatorTest {

    private val deduplicator = MessageDeduplicator()
    private val base = 1_700_000_000_000L

    private fun message(
        text: String? = "Salut",
        timestamp: Long = base,
        direction: MessageDirection = MessageDirection.INCOMING,
        remoteId: String? = null,
        source: MessageSource = MessageSource.ANDROID_NOTIFICATION,
        localId: Long = 0L,
        mediaKind: MediaKind = MediaKind.TEXT,
    ) = VaultMessage(
        localId = localId,
        remoteId = remoteId,
        dedupKey = deduplicator.keyFor(remoteId, direction, text, timestamp),
        timestamp = timestamp,
        direction = direction,
        text = text,
        mediaKind = mediaKind,
        status = DeliveryStatus.RECEIVED,
        source = source,
    )

    @Test
    fun `a message with no counterpart is inserted`() {
        val decision = deduplicator.decide(message(), recent = emptyList())
        assertEquals(MessageDeduplicator.Decision.Insert, decision)
    }

    @Test
    fun `the same notification re-posted is dropped`() {
        val existing = message(localId = 7L)
        // Instagram re-posts the notification a few seconds later.
        val repost = message(timestamp = base + 4_000L)

        val decision = deduplicator.decide(repost, listOf(existing))

        assertEquals(MessageDeduplicator.Decision.Skip(7L), decision)
    }

    @Test
    fun `whitespace and case differences do not defeat deduplication`() {
        val existing = message(text = "Salut  ça va", localId = 3L)
        val variant = message(text = "SALUT ça va", timestamp = base + 1_000L)

        assertEquals(MessageDeduplicator.Decision.Skip(3L), deduplicator.decide(variant, listOf(existing)))
    }

    @Test
    fun `the same text much later is a genuinely new message`() {
        val existing = message(localId = 1L)
        val later = message(timestamp = base + 10 * 60_000L)

        assertEquals(MessageDeduplicator.Decision.Insert, deduplicator.decide(later, listOf(existing)))
    }

    @Test
    fun `an API copy of a notification-captured message upgrades the existing row`() {
        val fromNotification = message(localId = 42L, source = MessageSource.ANDROID_NOTIFICATION)
        val fromApi = message(
            timestamp = base + 2_000L,
            remoteId = "ig-999",
            source = MessageSource.OFFICIAL_API,
        )

        val decision = deduplicator.decide(fromApi, listOf(fromNotification))

        // Merge, not Insert: one bubble, and the server id is kept.
        assertEquals(MessageDeduplicator.Decision.Merge(42L), decision)
    }

    @Test
    fun `the same remote id is never stored twice`() {
        val existing = message(localId = 5L, remoteId = "ig-1", source = MessageSource.OFFICIAL_API)
        // A webhook and a poll both deliver it, hours apart on the clock.
        val duplicate = message(
            timestamp = base + 6 * 3_600_000L,
            remoteId = "ig-1",
            source = MessageSource.OFFICIAL_API,
        )

        assertEquals(MessageDeduplicator.Decision.Skip(5L), deduplicator.decide(duplicate, listOf(existing)))
    }

    @Test
    fun `incoming and outgoing with the same text are distinct`() {
        val incoming = message(direction = MessageDirection.INCOMING, localId = 1L)
        val outgoing = message(direction = MessageDirection.OUTGOING, timestamp = base + 500L)

        assertEquals(MessageDeduplicator.Decision.Insert, deduplicator.decide(outgoing, listOf(incoming)))
    }

    @Test
    fun `dedup keys are stable across process restarts`() {
        val first = deduplicator.keyFor(null, MessageDirection.INCOMING, "Bonjour", base)
        val second = deduplicator.keyFor(null, MessageDirection.INCOMING, "Bonjour", base)

        assertEquals(first, second)
    }

    @Test
    fun `dedup keys differ for different content`() {
        val a = deduplicator.keyFor(null, MessageDirection.INCOMING, "Bonjour", base)
        val b = deduplicator.keyFor(null, MessageDirection.INCOMING, "Bonsoir", base)

        assertNotEquals(a, b)
    }

    @Test
    fun `remote ids take priority in the dedup key`() {
        val key = deduplicator.keyFor("ig-77", MessageDirection.INCOMING, "Bonjour", base)
        assertEquals("r:ig-77", key)
    }

    @Test
    fun `dedup keys never contain the message text`() {
        val secret = "rendez-vous à 21h chez moi"
        val key = deduplicator.keyFor(null, MessageDirection.INCOMING, secret, base)

        assertTrue(key.startsWith("n:INCOMING:"))
        assertTrue(!key.contains("rendez-vous"))
        assertTrue(!key.contains("21h"))
    }
}
