package app.handlive.android.feature.pairing.exchange

import app.handlive.android.core.crypto.primitives.SecureRandomBytes
import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.pairing.PairErrorData
import app.handlive.android.core.protocol.pairing.PairHelloData
import app.handlive.android.core.protocol.pairing.PairOp
import app.handlive.android.core.transport.bluetooth.BluetoothLinkAdmission
import app.handlive.android.core.transport.bluetooth.BtQrPairingKeys
import app.handlive.android.feature.connection.bluetooth.BluetoothAdvertising
import app.handlive.android.feature.connection.bluetooth.BluetoothPairingHooks
import app.handlive.android.feature.connection.bluetooth.BluetoothQrPairingHooks
import app.handlive.android.feature.pairing.testing.FakeBtPairingClientLink
import app.handlive.android.feature.pairing.testing.FakePairingClient
import app.handlive.android.feature.pairing.testing.PairStoreFixture
import app.handlive.android.feature.pairing.testing.PipeBluetoothLinkIo
import app.handlive.android.feature.pairing.testing.localPhone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/**
 * Đường thật của PAIR-01 luồng B qua Bluetooth (0.4.5 "Pairing mode", PAIR-01 B3): bộ accept mà app dựng
 * ([BluetoothAdvertising.acceptor], một slot cho cả listener) + cửa sổ QR thật của [PairingCoordinator] qua
 * [BtQrPairingKeySource]; một Mac giả ([FakeBtPairingClientLink] + [FakePairingClient]) bắt tay với `K_bt_plink` từ mã
 * QR rồi chạy hello → offer → confirm → done trên kênh `0x01`, và cặp được lưu như luồng LAN.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BtQrPairingLinkTest {
    private val pairs = PairStoreFixture()
    private val client = FakePairingClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val phone = localPhone()
    private val coordinator =
        PairingCoordinator(local = { phone }, pairs = pairs.store, advertise = {}, scope = scope)
    private val window = BtQrPairingKeySource(coordinator)
    private val acceptor =
        BluetoothAdvertising.acceptor(
            admission = BluetoothLinkAdmission(),
            serve = { _, _ -> error("liên kết ghép đôi không bao giờ vào serve() của cặp đã ghép") },
            pairing = BluetoothPairingHooks(qr = BluetoothQrPairingHooks({ window }, flowOf(Unit), { coordinator })),
            clock = System::currentTimeMillis,
            linkKeys = { emptyList() },
        )

    @After
    fun tearDown() {
        scope.cancel()
        pairs.database.close()
    }

    private suspend fun openQrWindow() {
        coordinator.onScanned(client.qrCode())
        coordinator.confirm()
    }

    private fun connect(): FakeBtPairingClientLink {
        val (mac, phoneEnd) = PipeBluetoothLinkIo.pair()
        scope.async { acceptor.accept(phoneEnd) }
        return FakeBtPairingClientLink(mac)
    }

    @Test
    fun aQrPairingLinkRunsPair01ThroughPairingExchangeAndStoresThePair() =
        runBlocking<Unit> {
            openQrWindow()
            val link = connect()
            assertTrue(link.handshake(BtQrPairingKeys.kBtPlink(client.pairingSecret)))
            withTimeout(WAIT) {
                link.sendText(client.hello())
                val offerText = checkNotNull(link.receiveText())
                val offer = checkNotNull(client.checkOffer(offerText, client.pairingSecret, phone.tlsSha256))
                val pairId = "0193a0c1-7e5f-7a2b-8c3d-4e5f6a7b8c9d"
                link.sendText(client.confirm(offer, pairId = pairId))
                assertTrue(client.checkDone(checkNotNull(link.receiveText()), offer, pairId, CREATED_AT))
                link.close()
                // Cặp được lưu trước `pair/done`; trạng thái "đã ghép" chỉ đổi khi PAIR-01 trên liên kết kết thúc.
                while (coordinator.state.value !is PairingState.Paired) kotlinx.coroutines.delay(POLL)
            }
            assertEquals(1, pairs.store.activeCount())
            assertTrue(coordinator.state.value is PairingState.Paired)
        }

    @Test
    fun aPinHelloOnAQrPairingLinkIsRefusedAndStoresNothing() =
        runBlocking<Unit> {
            openQrWindow()
            val link = connect()
            assertTrue(link.handshake(BtQrPairingKeys.kBtPlink(client.pairingSecret)))
            val refusal =
                withTimeout(WAIT) {
                    link.sendText(client.hello(mode = PairHelloData.MODE_PIN))
                    checkNotNull(link.receiveText())
                }
            assertEquals(PairOp.ERROR, client.opOf(refusal))
            assertEquals(ErrorCode.PAIRING_CLOSED.name, client.read(refusal, PairErrorData.serializer())?.code)
            assertEquals(0, pairs.store.activeCount())
        }

    @Test
    fun aLinkWithoutThePairingSecretIsRejectedBeforeAnyPairEnvelope() =
        runBlocking<Unit> {
            openQrWindow()
            val link = connect()
            assertFalse(withTimeout(WAIT) { link.handshake(SecureRandomBytes.next(KEY_SIZE)) })
            assertEquals(0, pairs.store.activeCount())
            assertTrue(coordinator.state.value is PairingState.Waiting)
        }

    @Test
    fun withoutAQrWindowEvenTheRightKeyGetsNoPairingLink() =
        runBlocking<Unit> {
            val link = connect()
            assertFalse(withTimeout(WAIT) { link.handshake(BtQrPairingKeys.kBtPlink(client.pairingSecret)) })
        }

    private companion object {
        val WAIT = 10.seconds
        const val POLL = 20L
        const val KEY_SIZE = 32
        const val CREATED_AT = 1_727_150_003_210L
    }
}
