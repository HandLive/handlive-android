package app.handlive.android.feature.pairing.exchange

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.pairing.PairErrorData
import app.handlive.android.core.protocol.pairing.PairHelloData
import app.handlive.android.core.protocol.pairing.PairOp
import app.handlive.android.core.transport.bluetooth.BluetoothLinkAdmission
import app.handlive.android.feature.connection.PairingAdvert
import app.handlive.android.feature.connection.bluetooth.BluetoothAdvertising
import app.handlive.android.feature.connection.bluetooth.BluetoothPairingHooks
import app.handlive.android.feature.connection.bluetooth.BluetoothPinPairingHooks
import app.handlive.android.feature.pairing.testing.FakeBtPinPairingClientLink
import app.handlive.android.feature.pairing.testing.FakePairingClient
import app.handlive.android.feature.pairing.testing.FakeTextSocket
import app.handlive.android.feature.pairing.testing.PairStoreFixture
import app.handlive.android.feature.pairing.testing.PipeBluetoothLinkIo
import app.handlive.android.feature.pairing.testing.localPhone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/**
 * `PIN_MAX_ATTEMPTS` đếm MỘT lần cho cả LAN và Bluetooth (00-common-specs 0.10, 0.4.5 "PIN", PAIR-01 A4/C3): một
 * `pair/offer` LAN không tới `pair/done` và một kênh Bluetooth hỏng sau `cpace1` cộng vào cùng bộ đếm của cửa sổ PIN
 * đang mở, theo thứ tự nào cũng vậy. Lần thứ 3 đóng cửa sổ ("PIN expired"), cả hai kênh từ chối lần sau, và
 * `BT_PIN_LOCKOUT` chặn cửa sổ PIN mới.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BtPinPairingSharedCounterTest {
    private val pairs = PairStoreFixture()
    private val client = FakePairingClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val adverts = mutableListOf<PairingAdvert>()
    private val coordinator =
        PairingCoordinator(local = { localPhone() }, pairs = pairs.store, advertise = { adverts += it }, scope = scope)
    private val window = BtPinPairingKeySource(coordinator)
    private val acceptor =
        BluetoothAdvertising.acceptor(
            admission = BluetoothLinkAdmission(),
            serve = { _, _ -> error("liên kết ghép đôi không bao giờ vào serve() của cặp đã ghép") },
            pairing = BluetoothPairingHooks(pin = BluetoothPinPairingHooks({ window }, flowOf(Unit), { coordinator })),
            clock = System::currentTimeMillis,
            linkKeys = { emptyList() },
        )

    @After
    fun tearDown() {
        scope.cancel()
        pairs.database.close()
    }

    @Test
    fun twoWrongPinsOverBluetoothThenOneOverTheLanCloseTheWindowForBothAndLockOut() =
        runBlocking<Unit> {
            confirmPin()
            wrongPinOverBluetooth(offersLeftAfter = 2)
            wrongPinOverBluetooth(offersLeftAfter = 1)
            assertEquals(PairingState.Waiting(pinMode = true), coordinator.state.value)
            wrongPinOverLan()
            assertClosedOnBothTransportsAndLockedOut()
        }

    @Test
    fun oneWrongPinOverTheLanThenTwoOverBluetoothCloseTheWindowForBothAndLockOut() =
        runBlocking<Unit> {
            confirmPin()
            wrongPinOverLan()
            assertEquals(PairingState.EnterPin(attemptsLeft = 2), coordinator.state.value)
            // A4: the LAN's wrong PIN is dropped; the user types it again on the same window.
            coordinator.submitPin(PIN)
            wrongPinOverBluetooth(offersLeftAfter = 1)
            wrongPinOverBluetooth(offersLeftAfter = 0)
            assertClosedOnBothTransportsAndLockedOut()
        }

    private suspend fun confirmPin() {
        coordinator.startPin()
        coordinator.submitPin(PIN)
    }

    /** One Bluetooth channel that fails after `cpace1`; counted when the acceptor ends it, so wait for that. */
    private suspend fun wrongPinOverBluetooth(offersLeftAfter: Int) {
        val (mac, phoneEnd) = PipeBluetoothLinkIo.pair()
        val accepted = scope.async { acceptor.accept(phoneEnd) }
        assertFalse(withTimeout(WAIT) { FakeBtPinPairingClientLink(mac).handshake(WRONG_PIN.toByteArray()) })
        withTimeout(WAIT) { accepted.await() }
        assertEquals(offersLeftAfter, coordinator.currentPinWindow?.offersLeft ?: 0)
    }

    /** One LAN `pair/offer` that the Mac answers with `PIN_INVALID` (A5). */
    private suspend fun wrongPinOverLan() =
        coroutineScope {
            val socket = FakeTextSocket()
            val run = async { coordinator.handle(socket) }
            socket.toPhone.send(client.hello(mode = PairHelloData.MODE_PIN))
            assertEquals(PairOp.OFFER, client.opOf(withTimeout(WAIT) { socket.toClient.receive() }))
            socket.toPhone.send(client.error(ErrorCode.PIN_INVALID.name, attemptsLeft = 2))
            withTimeout(WAIT) { run.await() }
        }

    private suspend fun assertClosedOnBothTransportsAndLockedOut() {
        assertEquals(PairingState.Failed(PairingFailure.PIN_EXPIRED), coordinator.state.value)
        assertNull(coordinator.currentPinWindow)
        // The LAN refuses the next client...
        val lan = FakeTextSocket()
        lan.toPhone.send(client.hello(mode = PairHelloData.MODE_PIN))
        withTimeout(WAIT) { coordinator.handle(lan) }
        val refusal = client.read(lan.toClient.receive(), PairErrorData.serializer())
        assertEquals(ErrorCode.PAIRING_CLOSED.name, refusal?.code)
        // ...and Bluetooth gives no PIN pairing link, even to the right PIN.
        val (mac, phoneEnd) = PipeBluetoothLinkIo.pair()
        scope.async { acceptor.accept(phoneEnd) }
        assertFalse(withTimeout(WAIT) { FakeBtPinPairingClientLink(mac).handshake(PIN.toByteArray()) })
        // BT_PIN_LOCKOUT (E10): confirming a PIN again opens no window, so nothing advertises `pm = 1`/BT_PIN_HINT.
        val advertsBefore = adverts.size
        confirmPin()
        assertTrue(adverts.drop(advertsBefore).none { it.pinMode })
        assertNull(window.currentPin())
        assertEquals(PairingState.Failed(PairingFailure.PIN_EXPIRED), coordinator.state.value)
    }

    private companion object {
        val WAIT = 30.seconds
        const val PIN = "482915"
        const val WRONG_PIN = "000000"
    }
}
