package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.crypto.primitives.CPaceX25519SHA512
import app.handlive.android.core.crypto.primitives.X25519Keys
import app.handlive.android.core.protocol.bluetooth.BtPreauthRecord

/**
 * Phía S (A-SVC) của CPace cho MỘT kênh ghép đôi PIN (0.4.5 "PIN", A7.2-C): `cpace1` (`Ya`) → `cpace2` (`Yb`), dẫn
 * `K_bt_pinlink` (psk của `noise1` kế tiếp) và `K_bt_pin` từ `ISK`. Giữ trạng thái lần thử PIN của kênh để báo
 * [window] đúng một lần khi kênh hỏng sau `cpace1` — bộ đếm thật nằm ở cửa sổ PIN, dùng chung với LAN (C3).
 */
class BtPinPairingResponder(
    private val window: BluetoothPinPairingWindow = BluetoothPinPairingWindow.NONE,
    private val scalar: () -> ByteArray = X25519Keys::generatePrivateKey,
) {
    /** `K_bt_pinlink` sau một `cpace1` hợp lệ, `null` trước đó. */
    var linkKey: ByteArray? = null
        private set
    private var pinKey: ByteArray? = null

    @Volatile
    private var attemptStarted = false

    @Volatile
    private var attemptConfirmed = false

    /**
     * Thân `cpace2` để gửi, hoặc `null` (đóng kênh không trả lời) khi không có cửa sổ PIN, `Ya` sai cỡ hay `K` toàn 0
     * ("an all-zero K → close without answer"). Một `null` không tính vào bộ đếm: chỉ một `noise1` không mở được SAU
     * khi CPace đã chạy mới là một lần đoán PIN. Mỗi lối ra sớm là một bước kiểm của 0.4.5 theo đúng thứ tự.
     */
    @Suppress("ReturnCount")
    fun receiveCpace1(
        ya: ByteArray,
        nS: ByteArray,
    ): ByteArray? {
        val pin = window.currentPin() ?: return null
        if (ya.size != X25519Keys.KEY_SIZE) return null
        val g = CPaceX25519SHA512.calculateGenerator(pin, CI, nS)
        val ybScalar = scalar()
        val yb = CPaceX25519SHA512.scalarMultVfy(ybScalar, g) ?: return null
        val k = CPaceX25519SHA512.scalarMultVfy(ybScalar, ya) ?: return null
        val isk = CPaceX25519SHA512.isk(nS, k, ya, EMPTY, yb, EMPTY)
        linkKey = BtPinPairingKeys.kBtPinlink(isk, nS)
        pinKey = BtPinPairingKeys.kBtPin(isk, nS)
        attemptStarted = true
        return BtPreauthRecord.encode(BtPreauthRecord.KIND_CPACE2, yb)
    }

    /** `noise1` đã mở bằng [linkKey]: lần thử thành công; trả `K_bt_pin` cho bước 9-11 PAIR-01. */
    fun confirm(): ByteArray {
        attemptConfirmed = true
        return checkNotNull(pinKey) { "cpace1 chưa chạy" }
    }

    /** Gọi trong `finally` của listener: lần thử đã qua `cpace1` mà không [confirm] → một lần sai, chỉ một lần. */
    fun recordFailureIfNeeded() {
        if (attemptStarted && !attemptConfirmed) {
            attemptStarted = false
            window.onPinChannelFailedAfterCpace1()
        }
    }

    private companion object {
        val CI = BtPinPairingKeys.CPACE_CI.toByteArray(Charsets.US_ASCII)
        val EMPTY = ByteArray(0)
    }
}
