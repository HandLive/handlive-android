package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.crypto.bluetooth.NoiseNNpsk0
import app.handlive.android.core.crypto.primitives.ChaCha20Poly1305Aead
import app.handlive.android.core.crypto.primitives.SecureRandomBytes
import app.handlive.android.core.crypto.primitives.X25519Keys
import app.handlive.android.core.protocol.bluetooth.BtPreauthRecord

/**
 * Phía S (A-SVC) của bắt tay bảo mật liên kết cho MỘT kênh (0.4.5 "Link-security handshake" bước 1-4): thử
 * `K_bt_plink` của cửa sổ ghép đôi QR đang mở (nếu còn slot, 0.4.5 "Pairing mode", A7.2-B) rồi `K_bt_link` của
 * từng cặp chưa thu hồi theo lượt — quảng bá không mang `pair_id` nên S không biết trước cặp nào. Không tự đóng
 * kênh hay cập nhật admission: bên gọi (listener, A7.1b) làm việc đó theo kết quả trả về.
 */
class BluetoothPreauthSession(
    private val pairKeys: BluetoothPairKeys,
    private val pairingKeySource: BluetoothPairingKeySource = BluetoothPairingKeySource { null },
    private val pairingSlot: BtQrPairingSlot = BtQrPairingSlot(),
    private val nonce: () -> ByteArray = { SecureRandomBytes.next(NONCE_SIZE) },
    private val ephemeralPrivateKey: () -> ByteArray = X25519Keys::generatePrivateKey,
) {
    private lateinit var prologue: ByteArray
    private var opened: NoiseNNpsk0.Message1Result? = null

    @Volatile
    private var pairingSlotHeld = false

    /** Bước 1: `n_s` mới cho kênh này; trói buộc vào prologue nên `noise1` ghi lại từ kênh khác không bao giờ khớp. */
    fun challenge(): ByteArray {
        val nS = nonce()
        prologue = NoiseNNpsk0.prologue(nS)
        return BtPreauthRecord.encode(BtPreauthRecord.KIND_CHALLENGE, nS)
    }

    sealed interface Noise1Outcome {
        class Verified(
            val pairId: String,
        ) : Noise1Outcome

        /** `noise1` xác thực bằng `K_bt_plink`: liên kết ghép đôi QR, chưa có `pair_id` (0.4.5 "Pairing link"). */
        data object PairingVerified : Noise1Outcome

        data object Rejected : Noise1Outcome
    }

    /** Bước 2-3: [message1] = thân `noise1` sau byte kind (0.4.5 bước 3, thử lần lượt, QR trước cặp đã ghép). */
    fun receiveNoise1(message1: ByteArray): Noise1Outcome {
        // `tryOpenMessage1` giả định đúng cỡ (`require`); thiếu MAC (cỡ sai) là `reject` như một tag không khớp,
        // không phải lỗi chưa bắt — không có tag nào để thử dù còn `K_bt_plink` hay cặp nào chưa thu hồi.
        if (message1.size != MESSAGE1_SIZE) return Noise1Outcome.Rejected
        return tryPairingPsk(message1) ?: tryPairKeys(message1)
    }

    /** `null` khi không có cửa sổ QR mở hoặc slot đã bị một kênh khác chiếm — rơi xuống `K_bt_link` như thường. */
    private fun tryPairingPsk(message1: ByteArray): Noise1Outcome? {
        val psk = pairingKeySource.currentPlinkKey()
        if (psk == null || !pairingSlot.tryClaim()) return null
        val result = NoiseNNpsk0.tryOpenMessage1(psk, prologue, message1)
        if (result == null) {
            pairingSlot.release()
        } else {
            opened = result
            pairingSlotHeld = true
        }
        return result?.let { Noise1Outcome.PairingVerified }
    }

    private fun tryPairKeys(message1: ByteArray): Noise1Outcome {
        for ((pairId, psk) in pairKeys.unrevokedLinkKeys()) {
            val result = NoiseNNpsk0.tryOpenMessage1(psk, prologue, message1)
            if (result != null) {
                opened = result
                return Noise1Outcome.Verified(pairId)
            }
        }
        return Noise1Outcome.Rejected
    }

    class LinkKeys(
        val noise2: ByteArray,
        val c1: ByteArray,
        val c2: ByteArray,
    )

    /** Bước 4: chỉ gọi sau [receiveNoise1] trả [Noise1Outcome.Verified] hoặc [Noise1Outcome.PairingVerified]. */
    fun buildNoise2(): LinkKeys {
        val verified = checkNotNull(opened) { "message1 chưa xác thực" }
        val result = NoiseNNpsk0.buildMessage2(verified, ephemeralPrivateKey())
        return LinkKeys(BtPreauthRecord.encode(BtPreauthRecord.KIND_NOISE2, result.message2), result.c1, result.c2)
    }

    /** Nhả slot ghép đôi QR nếu kênh này đang giữ, dù kết cục là gì — bên gọi gọi trong `finally`. */
    fun releasePairingSlotIfHeld() {
        if (pairingSlotHeld) {
            pairingSlotHeld = false
            pairingSlot.release()
        }
    }

    private companion object {
        const val NONCE_SIZE = 32
        const val MESSAGE1_SIZE = X25519Keys.KEY_SIZE + ChaCha20Poly1305Aead.TAG_SIZE
    }
}
