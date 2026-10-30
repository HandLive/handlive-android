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
import app.handlive.android.feature.pairing.testing.PairStoreFixture
import app.handlive.android.feature.pairing.testing.PipeBluetoothLinkIo
import app.handlive.android.feature.pairing.testing.localPhone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
 * Đường thật của PAIR-01 luồng C (PIN) qua Bluetooth (0.4.5 "PIN", PAIR-01 C1-C4): bộ accept mà app dựng
 * ([BluetoothAdvertising.acceptor]) + cửa sổ PIN thật của [PairingCoordinator] qua [BtPinPairingKeySource]; một Mac
 * giả ([FakeBtPinPairingClientLink] + [FakePairingClient]) chạy CPace rồi Noise rồi hello → offer → confirm → done
 * trên kênh `0x01` với `K_bt_pin` thay `K_pin`, và cặp được lưu như luồng LAN.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BtPinPairingLinkTest {
    private val pairs = PairStoreFixture()
    private val client = FakePairingClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val phone = localPhone()
    private val adverts = mutableListOf<PairingAdvert>()
    private val coordinator =
        PairingCoordinator(local = { phone }, pairs = pairs.store, advertise = { adverts += it }, scope = scope)
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

    private suspend fun confirmPin(value: String = PIN) {
        coordinator.startPin()
        coordinator.submitPin(value)
    }

    private fun connect(): FakeBtPinPairingClientLink {
        val (mac, phoneEnd) = PipeBluetoothLinkIo.pair()
        scope.async { acceptor.accept(phoneEnd) }
        return FakeBtPinPairingClientLink(mac)
    }

    @Test
    fun aPinPairingLinkRunsPair01ThroughPairingExchangeWithKBtPinAndStoresThePair() =
        runBlocking<Unit> {
            confirmPin()
            val link = connect()
            assertTrue(link.handshake(PIN.toByteArray(Charsets.UTF_8)))
            withTimeout(WAIT) {
                link.sendText(client.hello(mode = PairHelloData.MODE_PIN))
                val offerText = checkNotNull(link.receiveText())
                val offer = checkNotNull(client.checkOffer(offerText, link.kBtPin, phone.tlsSha256))
                val pairId = "0193a0c1-7e5f-7a2b-8c3d-4e5f6a7b8c9d"
                link.sendText(client.confirm(offer, pairId = pairId))
                assertTrue(client.checkDone(checkNotNull(link.receiveText()), offer, pairId, CREATED_AT))
                link.close()
                while (coordinator.state.value !is PairingState.Paired) delay(POLL)
            }
            assertEquals(1, pairs.store.activeCount())
            assertTrue(coordinator.state.value is PairingState.Paired)
        }

    @Test
    fun aQrHelloOnAPinPairingLinkIsRefusedAndStoresNothing() =
        runBlocking<Unit> {
            confirmPin()
            val link = connect()
            assertTrue(link.handshake(PIN.toByteArray(Charsets.UTF_8)))
            val refusal =
                withTimeout(WAIT) {
                    link.sendText(client.hello(mode = PairHelloData.MODE_QR))
                    checkNotNull(link.receiveText())
                }
            assertEquals(PairOp.ERROR, client.opOf(refusal))
            assertEquals(ErrorCode.PAIRING_CLOSED.name, client.read(refusal, PairErrorData.serializer())?.code)
            assertEquals(0, pairs.store.activeCount())
        }

    @Test
    fun withoutAPinWindowEvenTheRightPinGetsNoPairingLink() =
        runBlocking<Unit> {
            val link = connect()
            assertFalse(withTimeout(WAIT) { link.handshake(PIN.toByteArray(Charsets.UTF_8)) })
        }

    @Test
    fun threeWrongPinChannelsCloseTheWindowAndLockOutTheNextOne() =
        runBlocking<Unit> {
            confirmPin()
            repeat(3) {
                val (mac, phoneEnd) = PipeBluetoothLinkIo.pair()
                val accepted = scope.async { acceptor.accept(phoneEnd) }
                val link = FakeBtPinPairingClientLink(mac)
                assertFalse(withTimeout(WAIT) { link.handshake(WRONG_PIN.toByteArray(Charsets.UTF_8)) })
                // The phone counts the failed channel when the acceptor ends it, after the reject reached the Mac.
                withTimeout(WAIT) { accepted.await() }
            }
            assertEquals(PairingState.Failed(PairingFailure.PIN_EXPIRED), coordinator.state.value)
            val advertsBeforeRetry = adverts.size
            // BT_PIN_LOCKOUT (0.4.5 "PIN", E10): một cửa sổ PIN mới không mở, nên không có advert `pinMode = true`
            // nào nữa dù `startPin`/`submitPin` vẫn được gọi (chỉ `closeWindow` của `startPin` phát NONE như mọi lần).
            confirmPin("111111")
            assertTrue(adverts.drop(advertsBeforeRetry).none { it.pinMode })
            assertNull(window.currentPin())
        }

    private companion object {
        val WAIT = 10.seconds
        const val POLL = 20L
        const val PIN = "482915"
        const val WRONG_PIN = "000000"
        const val CREATED_AT = 1_727_150_003_210L
    }
}
