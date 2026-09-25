package app.handlive.android.feature.pairing.exchange

import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.core.data.pairing.NewPair
import app.handlive.android.core.data.pairing.PeerKeys
import app.handlive.android.core.data.pairing.SignedAttestation
import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.pairing.PairErrorData
import app.handlive.android.feature.connection.PairingAdvert
import app.handlive.android.feature.pairing.testing.FakePairingClient
import app.handlive.android.feature.pairing.testing.FakeTextSocket
import app.handlive.android.feature.pairing.testing.PairStoreFixture
import app.handlive.android.feature.pairing.testing.localPhone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
}
