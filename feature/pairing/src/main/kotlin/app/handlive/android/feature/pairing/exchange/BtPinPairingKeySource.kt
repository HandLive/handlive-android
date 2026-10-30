package app.handlive.android.feature.pairing.exchange

import app.handlive.android.core.transport.bluetooth.BluetoothPinPairingWindow

/**
 * Cửa sổ PIN của [coordinator] như listener Bluetooth thấy (0.4.5 "PIN", PAIR-01 C1-C3): PIN để chạy CPace, báo
 * một kênh hỏng sau `cpace1` vào bộ đếm chung với LAN (A4/C3). Không có PIN khi cửa sổ hiện tại là QR hay không
 * có cửa sổ nào. `PairingFeature.install` gắn nó vào `ConnectionRuntime` như [BtQrPairingKeySource].
 */
class BtPinPairingKeySource(
    private val coordinator: PairingCoordinator,
) : BluetoothPinPairingWindow {
    override fun currentPin(): ByteArray? = coordinator.currentPinWindowSecret

    override fun onPinChannelFailedAfterCpace1() {
        coordinator.currentPinWindow?.failBluetoothAttempt()
    }
}
