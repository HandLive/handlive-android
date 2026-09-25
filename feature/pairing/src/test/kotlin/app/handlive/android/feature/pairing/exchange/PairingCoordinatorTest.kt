package app.handlive.android.feature.pairing.exchange

import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.core.data.pairing.NewPair
import app.handlive.android.core.data.pairing.PeerKeys
import app.handlive.android.core.data.pairing.SignedAttestation
import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.pairing.PairErrorData
import app.handlive.android.core.protocol.pairing.PairOp
import app.handlive.android.feature.connection.PairingAdvert
import app.handlive.android.feature.pairing.testing.FakePairingClient
import app.handlive.android.feature.pairing.testing.FakeTextSocket
import app.handlive.android.feature.pairing.testing.PairStoreFixture
import app.handlive.android.feature.pairing.testing.localPhone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** PAIR-01 steps 4–6 and E1, E2, E5, E6, E9 on the phone's state machine. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PairingCoordinatorTest {
    private val pairs = PairStoreFixture()
    private val client = FakePairingClient()
    private val adverts = mutableListOf<PairingAdvert>()

    @After
    fun tearDown() = pairs.database.close()

    private fun kotlinx.coroutines.test.TestScope.coordinator() =
        PairingCoordinator(
            local = { localPhone() },
            pairs = pairs.store,
            advertise = { adverts += it },
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
        )

    @Test
    fun codeThatIsNotHandLivesIsQrInvalid() =
        runTest {
            val coordinator = coordinator()
            coordinator.onScanned("https://example.com/pair?v=1")
            assertEquals(PairingState.Failed(PairingFailure.QR_INVALID), coordinator.state.value)
        }

    @Test
    fun validCodeAsksForConfirmationAndConfirmOpensTheWindowWithPr() =
        runTest {
            val coordinator = coordinator()
            coordinator.onScanned(client.qrCode())
            assertEquals(PairingState.Confirm(client.name), coordinator.state.value)
            coordinator.confirm()
            assertEquals(PairingState.Waiting(pinMode = false), coordinator.state.value)
            assertEquals(8, adverts.last().pairingRequest?.length)
        }

    @Test
    fun cancelClosesTheWindowAndDropsTheCode() =
        runTest {
            val coordinator = coordinator()
            coordinator.onScanned(client.qrCode())
            coordinator.confirm()
            coordinator.cancel()
            assertEquals(PairingState.Idle, coordinator.state.value)
            assertEquals(PairingAdvert.NONE, adverts.last())
            coordinator.confirm() // nothing left to confirm
            assertEquals(PairingState.Idle, coordinator.state.value)
        }

    @Test
    fun windowExpiresAfter120SecondsAsPairingClosed() =
        runTest {
            val coordinator = coordinator()
            coordinator.onScanned(client.qrCode())
            coordinator.confirm()
            advanceTimeBy(PairingWindow.DURATION_MILLIS - 1)
            runCurrent()
            assertEquals(PairingState.Waiting(pinMode = false), coordinator.state.value)
            advanceTimeBy(2)
            runCurrent()
            assertEquals(PairingState.Failed(PairingFailure.PAIRING_CLOSED), coordinator.state.value)
            assertEquals(PairingAdvert.NONE, adverts.last())
        }

    @Test
    fun eightPairsAlreadyIsLimitReached() =
        runTest {
            repeat(8) { pairs.store.save(fakePair(), ByteArray(32)) }
            val coordinator = coordinator()
            coordinator.onScanned(client.qrCode())
            assertEquals(PairingState.Failed(PairingFailure.LIMIT_REACHED), coordinator.state.value)
            coordinator.startPin()
            assertEquals(PairingState.Failed(PairingFailure.LIMIT_REACHED), coordinator.state.value)
        }

    @Test
    fun pinWindowAdvertisesPmAndWaitsForThePin() =
        runTest {
            val coordinator = coordinator()
            coordinator.startPin()
            assertEquals(PairingAdvert(pinMode = true), adverts.last())
            assertEquals(PairingState.EnterPin(attemptsLeft = null), coordinator.state.value)
            coordinator.submitPin("482915")
            assertEquals(PairingState.Waiting(pinMode = true), coordinator.state.value)
        }

    @Test
    fun deniedCameraOffersThePin() =
        runTest {
            val coordinator = coordinator()
            coordinator.onCameraDenied()
            assertEquals(PairingState.Failed(PairingFailure.CAMERA_DENIED), coordinator.state.value)
        }

    @Test
    fun aMacThatDropsMidExchangeFinishesPairingOnANewConnectionWithinTheWindow() =
        runBlocking {
            val phone = localPhone()
            val windowScope = CoroutineScope(SupervisorJob())
            val coordinator =
                PairingCoordinator({ phone }, pairs.store, { adverts += it }, windowScope, clock = { NOW })
            coordinator.onScanned(client.qrCode())
            coordinator.confirm()
            // The Mac's network drops after pair/hello and the offer.
            val first = FakeTextSocket()
            val firstRun = async { coordinator.handle(first) }
            first.toPhone.send(client.hello())
            assertEquals(PairOp.OFFER, client.opOf(first.toClient.receive()))
            first.toPhone.close()
            firstRun.await()
            assertEquals(PairingState.Waiting(pinMode = false), coordinator.state.value)
            // It reconnects within the same 120 s window and pairing completes.
            val second = FakeTextSocket()
            val secondRun = async { coordinator.handle(second) }
            second.toPhone.send(client.hello())
            val offer =
                checkNotNull(client.checkOffer(second.toClient.receive(), client.pairingSecret, phone.tlsSha256))
            val pairId = UUID.randomUUID().toString()
            second.toPhone.send(client.confirm(offer, pairId, CREATED_AT))
            assertTrue(client.checkDone(second.toClient.receive(), offer, pairId, CREATED_AT))
            second.toPhone.close()
            secondRun.await()
            val paired = coordinator.state.value as PairingState.Paired
            assertEquals(client.name, paired.peerName)
            assertEquals(1, pairs.store.activeCount())
            assertEquals(PairingAdvert.NONE, adverts.last())
            windowScope.cancel()
        }

    @Test
    fun aReconnectAfterTheWindowExpiredIsStillPairingClosed() =
        runTest {
            val coordinator = coordinator()
            coordinator.onScanned(client.qrCode())
            coordinator.confirm()
            val first = FakeTextSocket()
            val firstRun = async { coordinator.handle(first) }
            first.toPhone.send(client.hello())
            assertEquals(PairOp.OFFER, client.opOf(first.toClient.receive()))
            first.toPhone.close()
            firstRun.await()
            assertEquals(PairingState.Waiting(pinMode = false), coordinator.state.value)
            advanceTimeBy(PairingWindow.DURATION_MILLIS + 1)
            runCurrent()
            assertEquals(PairingState.Failed(PairingFailure.PAIRING_CLOSED), coordinator.state.value)
            val late = FakeTextSocket()
            late.toPhone.send(client.hello())
            coordinator.handle(late)
            assertEquals(
                ErrorCode.PAIRING_CLOSED.name,
                client.read(late.toClient.receive(), PairErrorData.serializer())?.code,
            )
        }

    @Test
    fun connectionWithoutAnOpenWindowGetsPairingClosed() =
        runTest {
            val socket = FakeTextSocket()
            coordinator().handle(socket)
            assertEquals(
                ErrorCode.PAIRING_CLOSED.name,
                client.read(socket.toClient.receive(), PairErrorData.serializer())?.code,
            )
        }

    private fun fakePair() =
        NewPair(
            pairId = UUID.randomUUID().toString(),
            peer = PeerKeys(UUID.randomUUID().toString(), ByteArray(32), ByteArray(32)),
            peerName = "Mac",
            peerPlatform = PeerPlatform.MACOS,
            peerModel = null,
            attestation = SignedAttestation(ByteArray(8), ByteArray(64), ByteArray(64), 0),
        )

    private companion object {
        const val NOW = 1_727_150_000_000L
        const val CREATED_AT = 1_727_150_003_210L
    }
}
