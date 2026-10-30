package app.handlive.android.feature.pairing.exchange

import app.handlive.android.core.transport.bluetooth.BluetoothQrPairingWindow
import app.handlive.android.core.transport.bluetooth.BtQrPairingKeys

/**
 * Cửa sổ QR của [coordinator] như listener Bluetooth thấy (0.4.5 "Pairing mode", PAIR-01 B1–B3): `K_bt_plink` để
 * thử `noise1`, `pr_bt` để quảng bá, cả hai dẫn từ `pairing_secret` của cửa sổ đang mở; không có ở cửa sổ PIN hay khi
 * không có cửa sổ nào. `PairingFeature.install` gắn nó vào `ConnectionRuntime`.
 */
class BtQrPairingKeySource(
    private val coordinator: PairingCoordinator,
) : BluetoothQrPairingWindow {
    override fun currentPlinkKey(): ByteArray? = coordinator.currentQrPairingSecret?.let(BtQrPairingKeys::kBtPlink)

    override fun currentPrBt(): ByteArray? =
        coordinator.currentQrPairingSecret?.let { BtQrPairingKeys.prBt(BtQrPairingKeys.kBtPdisc(it)) }
}
