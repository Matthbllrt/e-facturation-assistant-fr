package app.mosaic.privatevault.data

import app.mosaic.privatevault.core.time.Clock
import app.mosaic.privatevault.data.db.MessageDao
import app.mosaic.privatevault.data.db.MessageEntity
import app.mosaic.privatevault.data.repo.ConversationRepository
import app.mosaic.privatevault.data.repo.MessageCapture
import app.mosaic.privatevault.domain.model.DeliveryStatus
import app.mosaic.privatevault.domain.model.MediaKind
import app.mosaic.privatevault.domain.model.MessageDirection
import app.mosaic.privatevault.domain.model.MessageSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vault's write contract, exercised against an in-memory DAO that mimics
 * Room's unique-index behaviour.
 */
class ConversationRepositoryTest {

    private class FakeMessageDao(
        /** Set to make every write fail, standing in for a corrupt database. */
        var failWrites: Boolean = false,
    ) : MessageDao {

        private val rows = MutableStateFlow<List<MessageEntity>>(emptyList())
        private var nextId = 1L

        override fun observeAll(): Flow<List<MessageEntity>> = rows

        override suspend fun recent(limit: Int): List<MessageEntity> =
            rows.value.sortedByDescending { it.timestamp }.take(limit)

        override suspend fun since(since: Long): List<MessageEntity> =
            rows.value.filter { it.timestamp >= since }

        override suspend fun latestTimestamp(): Long? = rows.value.maxOfOrNull { it.timestamp }

        override suspend fun count(): Int = rows.value.size

        override suspend fun insertIgnoring(entity: MessageEntity): Long {
            if (failWrites) throw IllegalStateException("database unavailable")
            // Mirrors the unique indices on dedup_key and remote_id.
            val clash = rows.value.any {
                it.dedupKey == entity.dedupKey ||
                    (entity.remoteId != null && it.remoteId == entity.remoteId)
            }
            if (clash) return -1L
            val id = nextId++
            rows.value = rows.value + entity.copy(localId = id)
            return id
        }

        override suspend fun upgrade(
            localId: Long,
            remoteId: String?,
            source: MessageSource,
            text: String?,
            mediaKind: MediaKind,
            status: DeliveryStatus,
        ) {
            rows.value = rows.value.map { row ->
                if (row.localId != localId) {
                    row
                } else {
                    row.copy(
                        remoteId = remoteId,
                        source = source,
                        text = text ?: row.text,
                        mediaKind = mediaKind,
                        status = status,
                    )
                }
            }
        }

        override suspend fun updateStatus(localId: Long, status: DeliveryStatus) {
            rows.value = rows.value.map { if (it.localId == localId) it.copy(status = status) else it }
        }

        override suspend fun findByDedupKey(dedupKey: String): MessageEntity? =
            rows.value.firstOrNull { it.dedupKey == dedupKey }

        override suspend fun deleteAll() {
            rows.value = emptyList()
        }
    }

    private val now = 1_700_000_000_000L
    private val clock = Clock { now }

    private fun capture(
        text: String? = "Salut",
        timestamp: Long = now,
        direction: MessageDirection = MessageDirection.INCOMING,
        remoteId: String? = null,
        source: MessageSource = MessageSource.ANDROID_NOTIFICATION,
    ) = MessageCapture(
        remoteId = remoteId,
        timestamp = timestamp,
        direction = direction,
        text = text,
        source = source,
    )

    @Test
    fun `a captured message is stored`() = runTest {
        val dao = FakeMessageDao()
        val repository = ConversationRepository(dao, clock)

        val outcome = repository.record(capture())

        assertTrue(outcome is ConversationRepository.Outcome.Stored)
        assertEquals(1, dao.count())
    }

    @Test
    fun `a re-posted notification does not create a second bubble`() = runTest {
        val dao = FakeMessageDao()
        val repository = ConversationRepository(dao, clock)

        repository.record(capture())
        val second = repository.record(capture(timestamp = now + 3_000L))

        assertTrue(second is ConversationRepository.Outcome.Duplicate)
        assertEquals(1, dao.count())
    }

    @Test
    fun `the API copy upgrades the notification row instead of duplicating it`() = runTest {
        val dao = FakeMessageDao()
        val repository = ConversationRepository(dao, clock)

        repository.record(capture())
        val outcome = repository.record(
            capture(timestamp = now + 5_000L, remoteId = "ig-1", source = MessageSource.OFFICIAL_API),
        )

        assertTrue(outcome is ConversationRepository.Outcome.Upgraded)
        assertEquals(1, dao.count())
        val stored = dao.observeAll().first().single()
        assertEquals("ig-1", stored.remoteId)
        assertEquals(MessageSource.OFFICIAL_API, stored.source)
    }

    @Test
    fun `distinct messages are all kept`() = runTest {
        val dao = FakeMessageDao()
        val repository = ConversationRepository(dao, clock)

        repository.record(capture(text = "un"))
        repository.record(capture(text = "deux", timestamp = now + 1_000L))
        repository.record(capture(text = "trois", timestamp = now + 2_000L))

        assertEquals(3, dao.count())
    }

    @Test
    fun `a write failure is reported and never silently swallowed`() = runTest {
        val dao = FakeMessageDao(failWrites = true)
        val repository = ConversationRepository(dao, clock)

        val outcome = repository.record(capture())

        assertTrue(outcome is ConversationRepository.Outcome.Failed)
    }

    @Test
    fun `stored messages survive a failed later capture`() = runTest {
        val dao = FakeMessageDao()
        val repository = ConversationRepository(dao, clock)
        repository.record(capture(text = "important"))

        dao.failWrites = true
        repository.record(capture(text = "perdu", timestamp = now + 60_000L))

        assertEquals(1, dao.count())
        assertEquals("important", dao.observeAll().first().single().text)
    }

    @Test
    fun `an outgoing echo can be marked sent afterwards`() = runTest {
        val dao = FakeMessageDao()
        val repository = ConversationRepository(dao, clock)
        val outcome = repository.record(
            capture(direction = MessageDirection.OUTGOING, source = MessageSource.LOCAL_ECHO)
                .copy(status = DeliveryStatus.PENDING),
        ) as ConversationRepository.Outcome.Stored

        repository.markStatus(outcome.localId, DeliveryStatus.SENT)

        assertEquals(DeliveryStatus.SENT, dao.observeAll().first().single().status)
    }

    @Test
    fun `the last activity timestamp drives the cooldown`() = runTest {
        val dao = FakeMessageDao()
        val repository = ConversationRepository(dao, clock)
        assertNull(repository.lastActivityMillis())

        repository.record(capture(timestamp = now))
        repository.record(capture(text = "plus tard", timestamp = now + 120_000L))

        assertEquals(now + 120_000L, repository.lastActivityMillis())
    }

    @Test
    fun `messages are only removed by an explicit erase`() = runTest {
        val dao = FakeMessageDao()
        val repository = ConversationRepository(dao, clock)
        repository.record(capture())
        assertNotNull(dao.observeAll().first().firstOrNull())

        repository.eraseAll()

        assertEquals(0, dao.count())
    }

    @Test
    fun `the same remote id delivered twice is stored once`() = runTest {
        val dao = FakeMessageDao()
        val repository = ConversationRepository(dao, clock)

        // A webhook and a poll, hours apart, carrying the same message.
        repository.record(capture(remoteId = "ig-9", source = MessageSource.OFFICIAL_API))
        repository.record(
            capture(
                remoteId = "ig-9",
                timestamp = now + 6 * 3_600_000L,
                source = MessageSource.OFFICIAL_API,
            ),
        )

        assertEquals(1, dao.count())
    }
}
