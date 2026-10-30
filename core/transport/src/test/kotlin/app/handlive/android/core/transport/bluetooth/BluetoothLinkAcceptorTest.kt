package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.crypto.primitives.SecureRandomBytes
import app.handlive.android.core.protocol.bluetooth.BtLinkConstants
import app.handlive.android.core.protocol.bluetooth.BtLinkException
import app.handlive.android.core.protocol.bluetooth.BtPreauthRecord
import app.handlive.android.core.protocol.bluetooth.BtTransportFrame
import app.handlive.android.core.protocol.testing.SharedTestVectors
import app.handlive.android.core.protocol.testing.hex
import app.handlive.android.core.protocol.testing.str
import app.handlive.android.core.transport.bluetooth.testing.InMemoryBluetoothLinkIo
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Một kênh L2CAP vừa accept, phía A-SVC (0.4.5 "Link-security handshake" bước 1-5 và "Admission"): admission theo
 * kênh, `challenge` → `noise1` → `noise2` → bản ghi vận chuyển đầu tiên giải mã được (bước 5), `reject` cho mọi lỗi
 * trước bước 5. Phía client dựng từ bt-link.json (`n_s`, `e` cố định) qua [InMemoryBluetoothLinkIo] — không radio.
 */
class BluetoothLinkAcceptorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val keys = SharedTestVectors.vectors("bt-link.json").filter { it.str("kind") == "key" }
    private val pair1 = keys.first { it.str("name") == "cặp 1" }
    private val pair2 = keys.first { it.str("name") == "cặp 2" }
    private val handshake =
        SharedTestVectors.vectors("bt-link.json").first {
            it.str("kind") == "handshake" && it.str("name").contains("cặp 1")
        }
    private val pairKeys =
        BluetoothPairKeys {
            listOf(pair2.str("pair_id") to pair2.hex("k_bt_link"), pair1.str("pair_id") to pair1.hex("k_bt_link"))
        }
    private val goodNoise1 = handshake.hex("e_pub_c") + handshake.hex("ciphertext_tag_1")

    @After
    fun tearDown() = scope.cancel()

    private class Served(
        val pairId: String,
        val firstText: String,
    )

    private fun acceptor(
        admission: BluetoothLinkAdmission,
        served: CompletableDeferred<Served> = CompletableDeferred(),
        holdLink: CompletableDeferred<Unit> = CompletableDeferred(Unit),
        preauthTimeout: Duration = BluetoothLinkConstants.PREAUTH_TIMEOUT,
    ) = BluetoothLinkAcceptor(
        admission = admission,
        serve = { socket: WebSocketSession, pairId: String ->
            val first = (socket.incoming.receive() as Frame.Text).readText()
            served.complete(Served(pairId, first))
            holdLink.await()
        },
        preauthTimeout = preauthTimeout,
        newSession = {
            BluetoothPreauthSession(
                pairKeys,
                nonce = { handshake.hex("n_s") },
                ephemeralPrivateKey = { handshake.hex("e_priv_s") },
            )
        },
    )

    private fun admission(
        linkLimit: Int = 1,
        authFailBlock: Int = 5,
    ) = BluetoothLinkAdmission(linkLimit = linkLimit, authFailBlock = authFailBlock, authFailWindow = 1.minutes)

    private fun firstTransportRecord(text: String): ByteArray =
        BtTransportCipher(handshake.hex("c1")).seal(
            BtTransportFrame.encode(BtLinkConstants.CHAN_CONTROL, more = false, long = false, text.toByteArray()),
        )

    /** Client side of steps 1-4 with the vector's message 1; returns the record S answered (noise2 or reject). */
    private suspend fun clientNoise(
        client: InMemoryBluetoothLinkIo,
        message1: ByteArray = goodNoise1,
    ): BtPreauthRecord.Decoded {
        val challenge = BtPreauthRecord.decode(client.readPreauthRecord())
        assertEquals(BtPreauthRecord.KIND_CHALLENGE, challenge.kind)
        client.writeRecord(BtPreauthRecord.encode(BtPreauthRecord.KIND_NOISE1, message1))
        return BtPreauthRecord.decode(client.readPreauthRecord())
    }

    @Test
    fun aValidLinkReachesTheSessionBoundToThePairWhosePskVerified() =
        runBlocking<Unit> {
            val admission = admission()
            val served = CompletableDeferred<Served>()
            val (client, server) = InMemoryBluetoothLinkIo.pair()
            val link = scope.async { acceptor(admission, served).accept(server) }
            val noise2 = clientNoise(client)
            assertEquals(BtPreauthRecord.KIND_NOISE2, noise2.kind)
            assertArrayEquals(handshake.hex("message2"), noise2.payload)
            client.writeRecord(firstTransportRecord("hello"))
            val session = withTimeout(WAIT) { served.await() }
            assertEquals(pair1.str("pair_id"), session.pairId)
            assertEquals("hello", session.firstText)
            withTimeout(WAIT) { link.await() }
        }

    @Test
    fun theLinkHoldsALinkSlotButFreesThePreauthSlotOnceConfirmedAndBothWhenItEnds() =
        runBlocking<Unit> {
            val admission = BluetoothLinkAdmission(linkLimit = 2, preauthLimit = 1)
            val served = CompletableDeferred<Served>()
            val hold = CompletableDeferred<Unit>()
            val (client, server) = InMemoryBluetoothLinkIo.pair()
            val link = scope.async { acceptor(admission, served, hold).accept(server) }
            clientNoise(client)
            client.writeRecord(firstTransportRecord("hello"))
            withTimeout(WAIT) { served.await() }
            // Confirmed (step 5): the single pre-auth slot is free again, the link still counts toward the limit.
            assertEquals(BluetoothLinkAdmission.AdmissionResult.Admitted, admission.admitNewChannel())
            assertEquals(BluetoothLinkAdmission.AdmissionResult.Rejected, admission.admitNewChannel())
            admission.closeLink(wasPreauth = true)
            hold.complete(Unit)
            withTimeout(WAIT) { link.await() }
            // The link ended: both of its slots are back, so two links fit again.
            assertEquals(BluetoothLinkAdmission.AdmissionResult.Admitted, admission.admitNewChannel())
            admission.confirmLink()
            assertEquals(BluetoothLinkAdmission.AdmissionResult.Admitted, admission.admitNewChannel())
        }

    @Test
    fun aChannelOverTheCapGetsRejectInsteadOfAChallenge() =
        runBlocking<Unit> {
            val admission = admission()
            assertEquals(BluetoothLinkAdmission.AdmissionResult.Admitted, admission.admitNewChannel())
            val (client, server) = InMemoryBluetoothLinkIo.pair()
            withTimeout(WAIT) { acceptor(admission).accept(server) }
            assertEquals(BtPreauthRecord.KIND_REJECT, BtPreauthRecord.decode(client.readPreauthRecord()).kind)
            assertThrows(BtLinkException::class.java) { runBlocking { client.readPreauthRecord() } }
        }

    @Test
    fun aNoise1NoPairVerifiesGetsRejectAndCountsAsAnAuthFailure() =
        runBlocking<Unit> {
            val admission = admission(authFailBlock = 2)
            val (client, server) = InMemoryBluetoothLinkIo.pair()
            val link = scope.async { acceptor(admission).accept(server) }
            val answer = clientNoise(client, message1 = SecureRandomBytes.next(goodNoise1.size))
            assertEquals(BtPreauthRecord.KIND_REJECT, answer.kind)
            withTimeout(WAIT) { link.await() }
            assertFalse(admission.isBlocked)
            assertEquals(BluetoothLinkAdmission.AdmissionResult.Admitted, admission.admitNewChannel()) // slot freed
            admission.closeLink(wasPreauth = true)
            admission.recordAuthFailure()
            assertTrue(admission.isBlocked)
        }

    @Test
    fun aBlockedListenerRejectsEveryNewChannel() =
        runBlocking<Unit> {
            val admission = admission(authFailBlock = 1)
            admission.recordAuthFailure()
            val (client, server) = InMemoryBluetoothLinkIo.pair()
            withTimeout(WAIT) { acceptor(admission).accept(server) }
            assertEquals(BtPreauthRecord.KIND_REJECT, BtPreauthRecord.decode(client.readPreauthRecord()).kind)
        }

    @Test
    fun aSilentClientGetsRejectAtThePreauthTimeoutWithoutCountingAFailure() =
        runBlocking<Unit> {
            val admission = admission(authFailBlock = 1)
            val (client, server) = InMemoryBluetoothLinkIo.pair()
            val link = scope.async { acceptor(admission, preauthTimeout = SHORT).accept(server) }
            assertEquals(BtPreauthRecord.KIND_CHALLENGE, BtPreauthRecord.decode(client.readPreauthRecord()).kind)
            val answer = withTimeout(WAIT) { BtPreauthRecord.decode(client.readPreauthRecord()) }
            assertEquals(BtPreauthRecord.KIND_REJECT, answer.kind)
            withTimeout(WAIT) { link.await() }
            assertFalse(admission.isBlocked)
            assertEquals(BluetoothLinkAdmission.AdmissionResult.Admitted, admission.admitNewChannel())
        }

    @Test
    fun aFirstTransportRecordThatDoesNotDecryptIsAKeyConfirmationFailure() =
        runBlocking<Unit> {
            val admission = admission(authFailBlock = 1)
            val served = CompletableDeferred<Served>()
            val (client, server) = InMemoryBluetoothLinkIo.pair()
            val link = scope.async { acceptor(admission, served).accept(server) }
            clientNoise(client)
            client.writeRecord(SecureRandomBytes.next(BtLinkConstants.TRANSPORT_RECORD_SIZES.first()))
            withTimeout(WAIT) { link.await() }
            assertFalse(served.isCompleted)
            assertTrue(admission.isBlocked)
        }

    @Test
    fun aLinkThatNeverSendsItsFirstTransportRecordEndsAtThePreauthTimeout() =
        runBlocking<Unit> {
            val admission = admission(authFailBlock = 1)
            val served = CompletableDeferred<Served>()
            val (client, server) = InMemoryBluetoothLinkIo.pair()
            val link = scope.async { acceptor(admission, served, preauthTimeout = SHORT).accept(server) }
            assertEquals(BtPreauthRecord.KIND_NOISE2, clientNoise(client).kind)
            withTimeout(WAIT) { link.await() }
            assertFalse(served.isCompleted)
            assertFalse(admission.isBlocked)
            assertEquals(BluetoothLinkAdmission.AdmissionResult.Admitted, admission.admitNewChannel())
        }

    @Test
    fun cpace1WithoutAPinWindowClosesTheChannelWithoutAnAnswer() =
        runBlocking<Unit> {
            val admission = admission()
            val (client, server) = InMemoryBluetoothLinkIo.pair()
            val link = scope.async { acceptor(admission).accept(server) }
            client.readPreauthRecord() // challenge
            client.writeRecord(BtPreauthRecord.encode(BtPreauthRecord.KIND_CPACE1, ByteArray(32)))
            withTimeout(WAIT) { link.await() }
            assertThrows(BtLinkException::class.java) { runBlocking { client.readPreauthRecord() } }
            assertFalse(admission.isBlocked)
        }

    private companion object {
        val WAIT = 5.seconds
        val SHORT = 300.milliseconds
    }
}
