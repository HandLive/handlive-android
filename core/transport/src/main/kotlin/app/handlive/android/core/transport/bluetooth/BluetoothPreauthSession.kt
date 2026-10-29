package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.crypto.bluetooth.NoiseNNpsk0
import app.handlive.android.core.crypto.primitives.SecureRandomBytes
import app.handlive.android.core.crypto.primitives.X25519Keys
import app.handlive.android.core.protocol.bluetooth.BtPreauthRecord

/**
 * Phía S (A-SVC) của bắt tay bảo mật liên kết cho MỘT kênh (0.4.5 "Link-security handshake" bước 1-4): thử
 * `K_bt_link` của từng cặp chưa thu hồi theo lượt — quảng bá không mang `pair_id` nên S không biết trước cặp
 * nào. Không tự đóng kênh hay cập nhật admission: bên gọi (listener, A7.1b) làm việc đó theo kết quả trả về.
 */
class BluetoothPreauthSession(
    private val pairKeys: BluetoothPairKeys,
    private val nonce: () -> ByteArray = { SecureRandomBytes.next(NONCE_SIZE) },
    private val ephemeralPrivateKey: () -> ByteArray = X25519Keys::generatePrivateKey,
) {
    private lateinit var prologue: ByteArray
    private var opened: NoiseNNpsk0.Message1Result? = null

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

        data object Rejected : Noise1Outcome
    }

    /** Bước 2-3: [message1] = thân `noise1` sau byte kind (0.4.5 bước 3, thử lần lượt). */
    fun receiveNoise1(message1: ByteArray): Noise1Outcome {
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

    /** Bước 4: chỉ gọi sau [receiveNoise1] trả [Noise1Outcome.Verified]. */
    fun buildNoise2(): LinkKeys {
        val verified = checkNotNull(opened) { "message1 chưa xác thực" }
        val result = NoiseNNpsk0.buildMessage2(verified, ephemeralPrivateKey())
        return LinkKeys(BtPreauthRecord.encode(BtPreauthRecord.KIND_NOISE2, result.message2), result.c1, result.c2)
    }

    private companion object {
        const val NONCE_SIZE = 32
    }
}
