package app.handlive.android.feature.pairing.exchange

import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.core.data.pairing.NewPair
import app.handlive.android.core.data.pairing.PeerKeys
import app.handlive.android.core.data.pairing.SignedAttestation
import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.pairing.PairErrorData
import app.handlive.android.core.protocol.pairing.PairHelloData
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun pinWindowOpensWithPmOnlyOnceThePinIsConfirmed() =
        runTest {
            val coordinator = coordinator()
            coordinator.startPin()
            assertEquals(PairingState.EnterPin(attemptsLeft = null), coordinator.state.value)
            // A3 → A4: while the PIN is typed nothing advertises `pm = 1` and /v1/pair stays closed.
            assertTrue(adverts.none { it.pinMode })
            val early = FakeTextSocket()
            early.toPhone.send(client.hello(mode = PairHelloData.MODE_PIN))
            coordinator.handle(early)
            assertEquals(
                ErrorCode.PAIRING_CLOSED.name,
                client.read(early.toClient.receive(), PairErrorData.serializer())?.code,
            )
            assertEquals(PairingState.EnterPin(attemptsLeft = null), coordinator.state.value)
            coordinator.submitPin("482915")
            assertEquals(PairingAdvert(pinMode = true), adverts.last())
            assertEquals(PairingState.Waiting(pinMode = true), coordinator.state.value)
        }

    @Test
    fun pinWindowLasts120SecondsFromTheConfirmedPin() =
        runTest {
            val coordinator = coordinator()
            coordinator.startPin()
            advanceTimeBy(PairingWindow.DURATION_MILLIS + 1)
            runCurrent()
            assertEquals(PairingState.EnterPin(attemptsLeft = null), coordinator.state.value)
            coordinator.submitPin("482915")
            advanceTimeBy(PairingWindow.DURATION_MILLIS - 1)
            runCurrent()
            assertEquals(PairingState.Waiting(pinMode = true), coordinator.state.value)
        }

    @Test
    fun aMacThatReconnectsBeforeTheNewPinIsTypedKeepsThePinEntryOpenAndPairs() =
        runBlocking {
            val phone = localPhone()
            val windowScope = CoroutineScope(SupervisorJob())
            val coordinator =
                PairingCoordinator({ phone }, pairs.store, { adverts += it }, windowScope, clock = { NOW })
            coordinator.startPin()
            coordinator.submitPin("111111")
            // The Mac shows 482915: the offer's MAC does not verify and it answers PIN_INVALID (A5, E7).
            val first = FakeTextSocket()
            val firstRun = async { coordinator.handle(first) }
            first.toPhone.send(client.hello(mode = PairHelloData.MODE_PIN))
            first.toClient.receive()
            first.toPhone.send(client.error(ErrorCode.PIN_INVALID.name, attemptsLeft = 2))
            firstRun.await()
            assertEquals(PairingState.EnterPin(attemptsLeft = 2), coordinator.state.value)
            // The Mac retries at once, while the user is still typing the new PIN: the PIN entry stays open.
            val second = FakeTextSocket()
            val secondRun = async { coordinator.handle(second) }
            second.toPhone.send(client.hello(mode = PairHelloData.MODE_PIN))
            awaitConsumed(second)
            assertEquals(PairingState.EnterPin(attemptsLeft = 2), coordinator.state.value)
            // "Pairing…" starts once the PIN is confirmed; the waiting client then gets the offer under K_pin.
            coordinator.submitPin("482915")
            assertEquals(PairingState.Verifying, coordinator.state.value)
            val offerText = second.toClient.receive()
            val offer =
                checkNotNull(client.checkOffer(offerText, client.pinSecret("482915", offerText), phone.tlsSha256))
            val pairId = UUID.randomUUID().toString()
            second.toPhone.send(client.confirm(offer, pairId, CREATED_AT))
            assertTrue(client.checkDone(second.toClient.receive(), offer, pairId, CREATED_AT))
            second.toPhone.close()
            secondRun.await()
            val paired = coordinator.state.value as PairingState.Paired
            assertEquals(client.name, paired.peerName)
            assertTrue("the phone's first pair, made with the PIN", paired.firstPair)
            windowScope.cancel()
        }

    @Test
    fun aSecondClientIsTurnedAwayWithoutClosingTheWindow() =
        runBlocking {
            val phone = localPhone()
            val windowScope = CoroutineScope(SupervisorJob())
            val coordinator =
                PairingCoordinator({ phone }, pairs.store, { adverts += it }, windowScope, clock = { NOW })
            coordinator.startPin()
            coordinator.submitPin("111111")
            val first = FakeTextSocket()
            val firstRun = async { coordinator.handle(first) }
            first.toPhone.send(client.hello(mode = PairHelloData.MODE_PIN))
            first.toClient.receive()
            first.toPhone.send(client.error(ErrorCode.PIN_INVALID.name, attemptsLeft = 2))
            firstRun.await()
            // The Mac reconnects and waits for the new PIN; a second connection arrives meanwhile (API 2 rule 4).
            val waiting = FakeTextSocket()
            val waitingRun = async { coordinator.handle(waiting) }
            waiting.toPhone.send(client.hello(mode = PairHelloData.MODE_PIN))
            awaitConsumed(waiting)
            val second = FakeTextSocket()
            second.toPhone.send(client.hello(mode = PairHelloData.MODE_PIN))
            coordinator.handle(second)
            assertEquals(
                ErrorCode.PAIRING_CLOSED.name,
                client.read(second.toClient.receive(), PairErrorData.serializer())?.code,
            )
            // Only that connection is refused: the PIN entry, the window and pm = 1 stay.
            assertEquals(PairingState.EnterPin(attemptsLeft = 2), coordinator.state.value)
            assertEquals(PairingAdvert(pinMode = true), adverts.last())
            coordinator.submitPin("482915")
            val offerText = waiting.toClient.receive()
            val offer =
                checkNotNull(client.checkOffer(offerText, client.pinSecret("482915", offerText), phone.tlsSha256))
            val pairId = UUID.randomUUID().toString()
            waiting.toPhone.send(client.confirm(offer, pairId, CREATED_AT))
            assertTrue(client.checkDone(waiting.toClient.receive(), offer, pairId, CREATED_AT))
            waiting.toPhone.close()
            waitingRun.await()
            assertEquals(client.name, (coordinator.state.value as PairingState.Paired).peerName)
            windowScope.cancel()
        }

    /** Lets the exchange read pair/hello and reach the point where it waits for the PIN. */
    private suspend fun awaitConsumed(socket: FakeTextSocket) {
        withTimeout(5_000) { while (!socket.toPhone.isEmpty) yield() }
        repeat(10) { yield() }
    }

    @Test
    fun pinWindowThatEndsWithoutAPairSaysThePinExpired() =
        runTest {
            val coordinator = coordinator()
            coordinator.startPin()
            coordinator.submitPin("482915")
            advanceTimeBy(PairingWindow.DURATION_MILLIS + 1)
            runCurrent()
            assertEquals(PairingState.Failed(PairingFailure.PIN_EXPIRED), coordinator.state.value)
            assertEquals(PairingAdvert.NONE, adverts.last())
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
    fun thePhonesFirstPairSaysItIsTheFirst() =
        runBlocking {
            // SET-01 step 8: after the first pairing the app opens the feature list.
            assertTrue(pairOverQr().firstPair)
            assertEquals(1, pairs.store.activeCount())
        }

    @Test
    fun aPairAddedNextToAnotherIsNotTheFirst() =
        runBlocking {
            pairs.store.save(fakePair(), ByteArray(32))
            assertFalse(pairOverQr().firstPair)
            assertEquals(2, pairs.store.activeCount())
        }

    @Test
    fun pairingTheSameClientAgainIsNotTheFirst() =
        runBlocking {
            // The new pair replaces the old one of that client (PAIR-01 API 4 rule 4): still one pair, not the first.
            pairs.store.save(fakePair(peerDeviceId = client.deviceId), ByteArray(32))
            assertFalse(pairOverQr().firstPair)
            assertEquals(1, pairs.store.activeCount())
        }

    /** A whole QR pairing with [client] on a fresh window; returns the result the screen shows. */
    private suspend fun pairOverQr(): PairingState.Paired =
        coroutineScope {
            val phone = localPhone()
            val windowScope = CoroutineScope(SupervisorJob())
            val coordinator =
                PairingCoordinator({ phone }, pairs.store, { adverts += it }, windowScope, clock = { NOW })
            coordinator.onScanned(client.qrCode())
            coordinator.confirm()
            val socket = FakeTextSocket()
            val run = async { coordinator.handle(socket) }
            socket.toPhone.send(client.hello())
            val offer =
                checkNotNull(client.checkOffer(socket.toClient.receive(), client.pairingSecret, phone.tlsSha256))
            val pairId = UUID.randomUUID().toString()
            socket.toPhone.send(client.confirm(offer, pairId, CREATED_AT))
            assertTrue(client.checkDone(socket.toClient.receive(), offer, pairId, CREATED_AT))
            socket.toPhone.close()
            run.await()
            windowScope.cancel()
            coordinator.state.value as PairingState.Paired
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

    private fun fakePair(peerDeviceId: String = UUID.randomUUID().toString()) =
        NewPair(
            pairId = UUID.randomUUID().toString(),
            peer = PeerKeys(peerDeviceId, ByteArray(32), ByteArray(32)),
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
