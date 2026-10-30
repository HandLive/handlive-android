package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.crypto.bluetooth.NoiseNNpsk0
import app.handlive.android.core.crypto.primitives.ChaCha20Poly1305Aead
import app.handlive.android.core.crypto.primitives.SecureRandomBytes
import app.handlive.android.core.crypto.primitives.X25519Keys
import app.handlive.android.core.protocol.bluetooth.BtPreauthRecord

/**
 * Phía S (A-SVC) của bắt tay bảo mật liên kết cho MỘT kênh (0.4.5 "Link-security handshake" bước 1-4): thử
 * `K_bt_plink` của cửa sổ ghép đôi QR đang mở (nếu còn slot, 0.4.5 "Pairing mode", A7.2-B) rồi `K_bt_link` của
 * từng cặp chưa thu hồi theo lượt — quảng bá không mang `pair_id` nên S không biết trước cặp nào. [receiveCpace1]/
 * [receivePinNoise1] là nhánh PIN (A7.2-C): một round trip CPace thêm trước `noise1`, khoá bằng `K_bt_pinlink` vừa
 * dẫn từ `ISK`, không rơi xuống `K_bt_plink` hay `K_bt_link` nào. Không tự đóng kênh hay cập nhật admission: bên
 * gọi (listener, A7.1b) làm việc đó theo kết quả trả về.
 */
class BluetoothPreauthSession(
    private val pairKeys: BluetoothPairKeys,
    private val pairingKeySource: BluetoothPairingKeySource = BluetoothPairingKeySource { null },
    private val pairingSlot: BtQrPairingSlot = BtQrPairingSlot(),
    private val pinPairing: BtPinPairingResponder = BtPinPairingResponder(),
    private val nonce: () -> ByteArray = { SecureRandomBytes.next(NONCE_SIZE) },
    private val ephemeralPrivateKey: () -> ByteArray = X25519Keys::generatePrivateKey,
) {
    private lateinit var prologue: ByteArray
    private lateinit var nS: ByteArray
    private var opened: NoiseNNpsk0.Message1Result? = null

    @Volatile
    private var pairingSlotHeld = false

    /** Bước 1: `n_s` mới cho kênh này; trói buộc vào prologue nên `noise1` ghi lại từ kênh khác không bao giờ khớp;
     * cũng là `sid` của CPace (nhánh PIN). */
    fun challenge(): ByteArray {
        val n = nonce()
        nS = n
        prologue = NoiseNNpsk0.prologue(n)
        return BtPreauthRecord.encode(BtPreauthRecord.KIND_CHALLENGE, n)
    }

    sealed interface Noise1Outcome {
        class Verified(
            val pairId: String,
        ) : Noise1Outcome

        /** `noise1` xác thực bằng `K_bt_plink`: liên kết ghép đôi QR, chưa có `pair_id` (0.4.5 "Pairing link"). */
        data object PairingVerified : Noise1Outcome

        /** `noise1` xác thực bằng `K_bt_pinlink` vừa dẫn qua CPace: liên kết ghép đôi PIN, chưa có `pair_id`
         * (0.4.5 "PIN"); [kBtPin] thay `K_pin` ở bước 9-11 PAIR-01. */
        class PinPairingVerified(
            val kBtPin: ByteArray,
        ) : Noise1Outcome

        data object Rejected : Noise1Outcome
    }

    sealed interface Cpace1Outcome {
        /** `cpace2` (`Yb`) để gửi tiếp; `K_bt_pinlink`/`K_bt_pin` đã dẫn xong, chờ `noise1` kế tiếp
         * ([receivePinNoise1]). */
        class Ready(
            val cpace2: ByteArray,
        ) : Cpace1Outcome

        /** Không có cửa sổ PIN đang mở, `Ya` sai cỡ, hoặc `Ya`/sinh `g` bậc thấp (`K` toàn 0, G.I abort): đóng kênh
         * không trả lời, như mọi kind lạc chỗ (0.4.5 "PIN" — "an all-zero K → close without answer"). */
        data object Closed : Cpace1Outcome
    }

    /** Bước CPace của 0.4.5 "PIN" (trước `noise1`), sid = `n_s` của kênh này ([BtPinPairingResponder]). */
    fun receiveCpace1(ya: ByteArray): Cpace1Outcome =
        pinPairing.receiveCpace1(ya, nS)?.let { Cpace1Outcome.Ready(it) } ?: Cpace1Outcome.Closed

    /** `noise1` của một kênh ghép đôi PIN, sau [receiveCpace1]: chỉ thử `K_bt_pinlink` vừa dẫn — một PIN sai ở
     * một phía làm `ISK` (nên `K_bt_pinlink`) khác nhau, nên tag không mở được, như mọi khoá sai (0.4.5 "The link
     * that passes noise1 with K_bt_pinlink is a PIN pairing link"). */
    fun receivePinNoise1(message1: ByteArray): Noise1Outcome {
        val psk = pinPairing.linkKey
        val result =
            psk
                ?.takeIf { message1.size == MESSAGE1_SIZE }
                ?.let { NoiseNNpsk0.tryOpenMessage1(it, prologue, message1) }
                ?: return Noise1Outcome.Rejected
        opened = result
        return Noise1Outcome.PinPairingVerified(pinPairing.confirm())
    }

    /** Lần thử PIN của kênh hỏng sau `cpace1` → một lần sai ở bộ đếm chung với LAN (C3); gọi trong `finally`. */
    fun recordPinFailureIfNeeded() = pinPairing.recordFailureIfNeeded()

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

    /** Bước 4: chỉ gọi sau [receiveNoise1]/[receivePinNoise1] trả về một kết quả đã xác thực (không phải
     * [Noise1Outcome.Rejected]). */
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
