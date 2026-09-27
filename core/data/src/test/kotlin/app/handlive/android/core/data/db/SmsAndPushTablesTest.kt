package app.handlive.android.core.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `sms_observer_state` (SMS-02) and `push_outbox` (CONN-04) with the queries of 0.9.1. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmsAndPushTablesTest {
    private val database =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HandLiveDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    @After
    fun tearDown() = database.close()

    @Test
    fun theObserverMarkIsCreatedOnFirstRunThenUpdated() =
        runTest {
            val dao = database.smsObserverState()
            assertNull(dao.lastSmsId())
            dao.write(SmsObserverStateEntity(lastSmsId = 12846, updatedAt = 1))
            dao.write(SmsObserverStateEntity(lastSmsId = 12847, updatedAt = 2))
            assertEquals(12847L, dao.lastSmsId())
        }

    @Test
    fun duePushesComeOldestFirstUntilTheyExpire() =
        runTest {
            insertPair("pair-a")
            val outbox = database.pushOutbox()
            outbox.insert(push("p2", "pair-a", nextAttemptAt = 200, expiresAt = 10_000))
            outbox.insert(push("p1", "pair-a", nextAttemptAt = 100, expiresAt = 10_000))
            outbox.insert(push("late", "pair-a", nextAttemptAt = 5_000, expiresAt = 10_000))
            outbox.insert(push("old", "pair-a", nextAttemptAt = 50, expiresAt = 300))

            assertEquals(listOf("p1", "p2"), outbox.due(now = 300, limit = 20).map { it.id })
            assertEquals(100L, outbox.nextAttemptAt(now = 300))

            outbox.reschedule("p1", attempts = 1, nextAttemptAt = 2_000)
            outbox.delete("p2", now = 300)
            assertEquals(listOf("p1", "late"), outbox.due(now = 5_000, limit = 20).map { it.id })
            assertEquals(1, outbox.due(now = 5_000, limit = 20).first().attempts)
        }

    @Test
    fun unpairingDeletesThePairsPushes() =
        runTest {
            insertPair("pair-a")
            insertPair("pair-b")
            database.pushOutbox().insert(push("a", "pair-a", nextAttemptAt = 0, expiresAt = 10))
            database.pushOutbox().insert(push("b", "pair-b", nextAttemptAt = 0, expiresAt = 10))
            database.pairedDevices().tombstone("pair-a", now = 1)
            database.pairedDevices().deleteTombstone("pair-a")
            assertEquals(listOf("b"), database.pushOutbox().due(now = 5, limit = 20).map { it.id })
        }

    private suspend fun insertPair(pairId: String) =
        database.pairedDevices().insert(
            PairedDeviceEntity(
                pairId = pairId,
                peerDeviceId = "$pairId-device",
                peerName = pairId,
                peerPlatform = PeerPlatform.IOS,
                peerModel = null,
                peerIkSigPub = ByteArray(KEY_SIZE),
                peerIkDhPub = ByteArray(KEY_SIZE),
                prkEnc = ByteArray(KEY_SIZE),
                attestation = ByteArray(KEY_SIZE),
                sigSelf = ByteArray(KEY_SIZE),
                sigPeer = ByteArray(KEY_SIZE),
                createdAt = 0,
            ),
        )

    private fun push(
        id: String,
        pairId: String,
        nextAttemptAt: Long,
        expiresAt: Long,
    ) = PushOutboxEntity(id, pairId, PushKind.ALERT, "e30=", "sms:42", 0, nextAttemptAt, expiresAt)

    private companion object {
        const val KEY_SIZE = 32
    }
}
