package app.handlive.android.feature.pairing.exchange

import app.handlive.android.core.transport.bluetooth.BtQrPairingKeys
import app.handlive.android.feature.pairing.testing.FakePairingClient
import app.handlive.android.feature.pairing.testing.PairStoreFixture
import app.handlive.android.feature.pairing.testing.localPhone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Điểm nối [PairingCoordinator] với [app.handlive.android.core.transport.bluetooth.BluetoothPairingKeySource]
 * (0.4.5 "Pairing mode", A7.2-B): `K_bt_plink` chỉ khi cửa sổ đang mở là QR, không phải PIN hay không có cửa sổ
 * nào — `BluetoothLinkAcceptor` dùng đúng khoá của cửa sổ đang chạy trên điện thoại, không phải một snapshot cũ.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BtQrPairingKeySourceTest {
    private val pairs = PairStoreFixture()
    private val client = FakePairingClient()

    @After
    fun tearDown() = pairs.database.close()

    private fun kotlinx.coroutines.test.TestScope.coordinator() =
        PairingCoordinator(
            local = { localPhone() },
            pairs = pairs.store,
            advertise = {},
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
        )

    @Test
    fun noKeyWithNoWindowOpen() =
        runTest {
            val source = BtQrPairingKeySource(coordinator())
            assertNull(source.currentPlinkKey())
        }

    @Test
    fun theQrWindowsKBtPlinkIsDerivedFromItsPairingSecret() =
        runTest {
            val coordinator = coordinator()
            val source = BtQrPairingKeySource(coordinator)
            coordinator.onScanned(client.qrCode())
            coordinator.confirm()
            assertArrayEquals(BtQrPairingKeys.kBtPlink(client.pairingSecret), source.currentPlinkKey())
        }

    @Test
    fun noKeyOnceTheWindowClosesAgain() =
        runTest {
            val coordinator = coordinator()
            val source = BtQrPairingKeySource(coordinator)
            coordinator.onScanned(client.qrCode())
            coordinator.confirm()
            coordinator.cancel()
            assertNull(source.currentPlinkKey())
        }

    @Test
    fun noKeyForAPinWindow() =
        runTest {
            val coordinator = coordinator()
            val source = BtQrPairingKeySource(coordinator)
            coordinator.startPin()
            coordinator.submitPin("123456")
            assertNull(source.currentPlinkKey())
        }
}
