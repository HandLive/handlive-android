package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.crypto.primitives.HkdfSha256

/**
 * Khoá CPace của một cửa sổ PIN đang mở qua Bluetooth (0.4.5 "PIN (PAIR-01 luồng C)"): [CPACE_CI] là `CI` cố định
 * của bắt tay, `K_bt_pinlink` thay `K_bt_plink` làm psk của bắt tay Noise, `K_bt_pin` thay `K_pin` ở bước 9-11
 * PAIR-01. Cả hai dẫn từ `ISK` của CPace (`CPACE-X25519-SHA512`, core:crypto), muối bằng `n_s` của chính kênh đó —
 * khác nhau dù cùng PIN, dù cùng cửa sổ. Vector: `shared/test-vectors/cpace.json` (kind `handlive_pin`).
 */
object BtPinPairingKeys {
    const val CPACE_CI = "HLBTPIN1"
    const val PINLINK_INFO = "handlive/v1/bt-pin-link"
    const val PIN_INFO = "handlive/v1/bt-pin-pair"
    private const val KEY_SIZE = 32

    fun kBtPinlink(
        isk: ByteArray,
        nS: ByteArray,
    ): ByteArray = HkdfSha256.derive(ikm = isk, salt = nS, info = PINLINK_INFO, length = KEY_SIZE)

    fun kBtPin(
        isk: ByteArray,
        nS: ByteArray,
    ): ByteArray = HkdfSha256.derive(ikm = isk, salt = nS, info = PIN_INFO, length = KEY_SIZE)
}

/** PIN (UTF-8, PRS của CPace) của cửa sổ PIN đang mở, hoặc `null` khi không có cửa sổ PIN nào hay PIN chưa gõ xong
 * (0.4.5 "PIN"). Cài đặt thật đọc cửa sổ hiện tại của `PairingCoordinator` (feature/pairing, A7.2-C); đây chỉ là
 * điểm nối để [BluetoothPreauthSession] không phụ thuộc feature/pairing — như [BluetoothPairingKeySource] của QR. */
fun interface BluetoothPinPairingKeySource {
    fun currentPin(): ByteArray?
}

/**
 * Cửa sổ ghép đôi PIN như listener Bluetooth thấy (0.4.5 "PIN", PAIR-01 C1-C3): [currentPin] để chạy CPace,
 * [onPinChannelFailedAfterCpace1] báo một kênh đã nhận `cpace1` nhưng không xác nhận được bằng `K_bt_pinlink` —
 * tính một lần sai vào bộ đếm chung với LAN (A4/C3), dù chưa từng chạm tới `pair/hello`.
 */
interface BluetoothPinPairingWindow : BluetoothPinPairingKeySource {
    fun onPinChannelFailedAfterCpace1()

    companion object {
        /** Không có cửa sổ PIN nào: không chạy CPace, không bao giờ báo lỗi (listener không có cửa sổ PIN). */
        val NONE: BluetoothPinPairingWindow =
            object : BluetoothPinPairingWindow {
                override fun currentPin(): ByteArray? = null

                override fun onPinChannelFailedAfterCpace1() = Unit
            }
    }
}
