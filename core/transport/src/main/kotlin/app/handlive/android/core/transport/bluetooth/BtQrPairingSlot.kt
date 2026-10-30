package app.handlive.android.core.transport.bluetooth

import kotlin.time.Duration

/**
 * Slot ghép đôi QR duy nhất trên toàn listener (0.4.5 "Pairing link"): [tryClaim] chiếm ngay khi `noise1` của một
 * kênh xác thực được bằng `K_bt_plink` ([BluetoothPreauthSession]); [release] nhả khi kênh đó kết thúc hoặc xác
 * thực hỏng. Một kênh khác xác thực đúng cùng psk trong lúc slot còn bị chiếm vẫn bị từ chối ("S stops trying
 * K_bt_plink", một người ghép mỗi cửa sổ) — [BluetoothPreauthSession] không thử `K_bt_plink` với nó, nên nó rơi
 * xuống `K_bt_link` như một kênh thường và thất bại. [holdTimeout] tự nhả nếu kênh giữ slot biến mất mà không gọi
 * [release] (ví dụ tiến trình bị giết giữa chừng).
 */
class BtQrPairingSlot(
    private val holdTimeout: Duration = BluetoothLinkConstants.PREAUTH_TIMEOUT,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var heldUntil: Long = 0

    @Synchronized
    fun tryClaim(): Boolean {
        if (clock() < heldUntil) return false
        heldUntil = clock() + holdTimeout.inWholeMilliseconds
        return true
    }

    @Synchronized
    fun release() {
        heldUntil = 0
    }
}
