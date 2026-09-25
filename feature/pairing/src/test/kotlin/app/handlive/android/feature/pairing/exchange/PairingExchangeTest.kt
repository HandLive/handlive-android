package app.handlive.android.feature.pairing.exchange

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.pairing.PairErrorData
import app.handlive.android.core.protocol.pairing.PairHelloData
import app.handlive.android.core.protocol.pairing.PairOp
import app.handlive.android.feature.pairing.invite.PairingInvite
import app.handlive.android.feature.pairing.testing.FakePairingClient
import app.handlive.android.feature.pairing.testing.FakeTextSocket
import app.handlive.android.feature.pairing.testing.PairStoreFixture
import app.handlive.android.feature.pairing.testing.localPhone
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** PAIR-01 on the phone, against an independent client: the happy paths (QR, PIN) and E2, E4, E7. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PairingExchangeTest {
    private val pairs = PairStoreFixture()
    private val phone = localPhone()
    private val client = FakePairingClient()
    private var now = 1_727_150_000_000L

    @After
    fun tearDown() = pairs.database.close()

    private fun qrWindow(expiresIn: Long = PairingWindow.DURATION_MILLIS) =
        PairingWindow.Qr(checkNotNull(PairingInvite.parse(client.qrCode())), now + expiresIn)

    private fun exchange(window: PairingWindow) = PairingExchange(phone, window, pairs.store, clock = { now })

    @Test
    fun qrPairingStoresThePairAndBothSidesAgreeOnKeysAndSecurityCode() =
        runBlocking {
            val socket = FakeTextSocket()
            val result = async { exchange(qrWindow()).run(socket) }
            socket.toPhone.send(client.hello())
            val offer =
                checkNotNull(
                    client.checkOffer(socket.toClient.receive(), client.pairingSecret, phone.tlsSha256),
                )
            val pairId = UUID.randomUUID().toString()
            socket.toPhone.send(client.confirm(offer, pairId, CREATED_AT))
            assertTrue("done checks", client.checkDone(socket.toClient.receive(), offer, pairId, CREATED_AT))
            socket.toPhone.close()

            val paired = result.await() as PairingOutcome.Paired
            assertEquals(pairId, paired.pairId)
            assertEquals(client.name, paired.peerName)
            // Security Code: the Mac hashes the same attestation (PAIR-02 field 10).
            assertEquals(
                app.handlive.android.core.data.pairing.PairStore.safetyCode(
                    client.attestation(offer, pairId, CREATED_AT),
                ),
                paired.safetyCode,
            )
            val stored = pairs.store.secretBlocking(pairId)!!
            assertArrayEquals(client.prk(offer), stored.prk)
            assertEquals(client.deviceId, stored.peerDeviceId)
        }

    @Test
    fun pinPairingWaitsForThePinAndDerivesArgon2Key() =
        runBlocking {
            val window = PairingWindow.Pin(now + PairingWindow.DURATION_MILLIS)
            val socket = FakeTextSocket()
            val result = async { exchange(window).run(socket) }
            socket.toPhone.send(client.hello(mode = PairHelloData.MODE_PIN))
            window.submit("482915")
            val offerText = socket.toClient.receive()
            val offer =
                checkNotNull(
                    client.checkOffer(offerText, client.pinSecret("482915", offerText), phone.tlsSha256),
                )
            val pairId = UUID.randomUUID().toString()
            socket.toPhone.send(client.confirm(offer, pairId, CREATED_AT))
            assertTrue(client.checkDone(socket.toClient.receive(), offer, pairId, CREATED_AT))
            socket.toPhone.close()
            assertTrue(result.await() is PairingOutcome.Paired)
        }

    @Test
    fun wrongPinReportedByTheClientGivesTheAttemptsLeftAndReopensTheWindow() =
        runBlocking {
            val window = PairingWindow.Pin(now + PairingWindow.DURATION_MILLIS)
            val socket = FakeTextSocket()
            val result = async { exchange(window).run(socket) }
            socket.toPhone.send(client.hello(mode = PairHelloData.MODE_PIN))
            window.submit("111111")
            val offerText = socket.toClient.receive()
            // The Mac's PIN is another one: the MAC does not verify, it answers PIN_INVALID (A5, E7).
            assertNull(client.checkOffer(offerText, client.pinSecret("482915", offerText), phone.tlsSha256))
            socket.toPhone.send(client.error(ErrorCode.PIN_INVALID.name, attemptsLeft = 2))
            val failed = result.await() as PairingOutcome.Failed
            assertEquals(PairingFailure.PIN_INVALID, failed.failure)
            assertEquals(2, failed.attemptsLeft)
            assertTrue("window can be claimed again", window.claim())
            assertEquals(0, pairs.store.activeCount())
        }

    @Test
    fun expiredWindowAnswersPairingClosed() =
        assertRefused(qrWindow(expiresIn = -1), client.hello(), ErrorCode.PAIRING_CLOSED)

    @Test
    fun pinHelloOnAQrWindowAnswersPairingClosed() =
        assertRefused(qrWindow(), client.hello(mode = PairHelloData.MODE_PIN), ErrorCode.PAIRING_CLOSED)

    @Test
    fun keyOtherThanTheScannedOneIsAuthFailed() =
        assertRefused(qrWindow(), client.hello(dhOverride = ByteArray(32) { 9 }), ErrorCode.AUTH_FAILED)

    @Test
    fun deviceIdNotDerivedFromTheSigningKeyIsAuthFailed() =
        assertRefused(qrWindow(), client.hello(deviceIdOverride = UUID.randomUUID().toString()), ErrorCode.AUTH_FAILED)

    @Test
    fun secondClientOfTheSameWindowIsRefused() =
        runBlocking {
            val window = qrWindow()
            assertTrue(window.claim())
            assertRefused(window, client.hello(), ErrorCode.PAIRING_CLOSED)
        }

    @Test
    fun tamperedConfirmMacIsAuthFailedAndNothingIsStored() =
        assertConfirmRefused { offer ->
            client.confirm(offer, tamper = FakePairingClient.Tamper.MAC)
        }

    @Test
    fun wrongPrkCheckIsAuthFailedAndNothingIsStored() =
        assertConfirmRefused { offer ->
            client.confirm(offer, tamper = FakePairingClient.Tamper.PRK_CHECK)
        }

    @Test
    fun badClientSignatureIsAuthFailedAndNothingIsStored() =
        assertConfirmRefused { offer -> client.confirm(offer, tamper = FakePairingClient.Tamper.SIGNATURE) }

    private fun assertRefused(
        window: PairingWindow,
        hello: String,
        code: ErrorCode,
    ) = runBlocking {
        val socket = FakeTextSocket()
        val result = async { exchange(window).run(socket) }
        socket.toPhone.send(hello)
        val reply = socket.toClient.receive()
        assertEquals(PairOp.ERROR, client.opOf(reply))
        assertEquals(code.name, client.read(reply, PairErrorData.serializer())?.code)
        assertTrue(result.await() is PairingOutcome.Failed)
        assertEquals(0, pairs.store.activeCount())
    }

    private fun assertConfirmRefused(confirm: (FakePairingClient.Offer) -> String) =
        runBlocking {
            val socket = FakeTextSocket()
            val result = async { exchange(qrWindow()).run(socket) }
            socket.toPhone.send(client.hello())
            val offer = client.checkOffer(socket.toClient.receive(), client.pairingSecret, phone.tlsSha256)!!
            socket.toPhone.send(confirm(offer))
            val reply = socket.toClient.receive()
            assertEquals(ErrorCode.AUTH_FAILED.name, client.read(reply, PairErrorData.serializer())?.code)
            assertEquals(PairingFailure.AUTH_FAILED, (result.await() as PairingOutcome.Failed).failure)
            assertEquals(0, pairs.store.activeCount())
        }

    private companion object {
        const val CREATED_AT = 1_727_150_003_210L
    }
}
