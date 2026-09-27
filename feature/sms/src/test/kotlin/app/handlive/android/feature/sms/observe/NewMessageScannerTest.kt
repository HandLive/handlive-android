package app.handlive.android.feature.sms.observe

import app.handlive.android.feature.sms.provider.SmsRow
import app.handlive.android.feature.sms.provider.SmsType
import app.handlive.android.feature.sms.testing.BASE_TS
import app.handlive.android.feature.sms.testing.FakeSmsProvider
import app.handlive.android.feature.sms.testing.MemoryObserverState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** SMS-02 steps 3, 4, 12 and API 3 logic 1–5: `last_sms_id`, `pending_out`, drafts. */
class NewMessageScannerTest {
    private val provider = FakeSmsProvider().apply { conversation(42, "+84900000123") }
    private val state = MemoryObserverState()
    private var now = BASE_TS
    private val scanner = NewMessageScanner(provider, state) { now }

    @Test
    fun theFirstRunStartsFromTheNewestMessageWithoutBroadcastingHistory() =
        runTest {
            (1..3).forEach { provider.add(42, BASE_TS + it) }
            assertEquals(emptyList<Long>(), scanner.scan().map { it.id })
            assertEquals(3L, state.mark)
            provider.add(42, BASE_TS + 4)
            assertEquals(listOf(4L), scanner.scan().map { it.id })
            assertEquals(4L, state.mark)
        }

    @Test
    fun inboxSentAndFailedGoOutInIdOrderDraftsNeverButTheMarkCoversThem() =
        runTest {
            state.mark = 0
            provider.add(42, BASE_TS + 1, type = SmsType.INBOX)
            provider.add(42, BASE_TS + 2, type = SmsType.DRAFT)
            provider.add(42, BASE_TS + 3, type = SmsType.SENT)
            provider.add(42, BASE_TS + 4, type = SmsType.FAILED)
            provider.add(42, BASE_TS + 5, type = SmsType.DRAFT)
            assertEquals(listOf(1L, 3L, 4L), scanner.scan().map { it.id })
            assertEquals(5L, state.mark)
            assertEquals(emptyList<Long>(), scanner.scan().map { it.id })
        }

    @Test
    fun outboxAndQueuedRowsWaitUntilTheyAreSentOrFailed() =
        runTest {
            state.mark = 0
            provider.add(42, BASE_TS + 1, type = SmsType.OUTBOX)
            provider.add(42, BASE_TS + 2, type = SmsType.QUEUED)
            assertEquals(emptyList<Long>(), scanner.scan().map { it.id })
            assertEquals(2L, state.mark)

            provider.update(1) { it.copy(type = SmsType.SENT) }
            provider.add(42, BASE_TS + 3)
            assertEquals(listOf(1L, 3L), scanner.scan().map { it.id })
            provider.update(2) { it.copy(type = SmsType.FAILED) }
            assertEquals(listOf(2L), scanner.scan().map { it.id })
            provider.update(2) { it.copy(type = SmsType.SENT) }
            assertEquals("a row goes out of pending_out once", emptyList<Long>(), scanner.scan().map { it.id })
        }

    @Test
    fun pendingRowsAreDroppedAfterTenMinutesOrWhenDeleted() =
        runTest {
            state.mark = 0
            provider.add(42, BASE_TS + 1, type = SmsType.OUTBOX)
            provider.add(42, BASE_TS + 2, type = SmsType.OUTBOX)
            scanner.scan()
            provider.delete(2)
            scanner.scan()
            now += 10 * 60 * 1000 + 1
            provider.update(1) { it.copy(type = SmsType.SENT) }
            assertEquals(emptyList<Long>(), scanner.scan().map { it.id })
        }

    @Test
    fun deletingTheNewestMessageLowersTheMarkSoAReusedIdIsSeen() =
        runTest {
            (1..3).forEach { provider.add(42, BASE_TS + it) }
            scanner.scan()
            provider.delete(3)
            assertEquals(emptyList<Long>(), scanner.scan().map { it.id })
            assertEquals(2L, state.mark)
            provider.put(SmsRow(3, 42, "+84900000123", "again", SmsType.INBOX, BASE_TS + 9, 0, true, 1))
            assertEquals(listOf(3L), scanner.scan().map { it.id })
        }
}
