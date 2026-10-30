package app.handlive.android.core.crypto.bluetooth

import app.handlive.android.core.crypto.primitives.ChaCha20Poly1305Aead
import app.handlive.android.core.crypto.primitives.HmacSha256
import app.handlive.android.core.crypto.primitives.X25519Keys
import java.security.GeneralSecurityException
import java.security.MessageDigest

/**
 * `Noise_NNpsk0_25519_ChaChaPoly_SHA256` (Noise Protocol Framework rev. 34), phía S (A-SVC, responder) — bắt tay
 * bảo mật liên kết của 0.4.5 bước 1-5. Rev. 34 §9.2 (chế độ psk): mỗi token "e" làm MixHash(e.pub) RỒI
 * MixKey(e.pub), khác pattern không-psk nơi "e" chỉ MixHash — cùng thuật toán của `noise_nnpsk0.py`
 * (`handlive-shared/tools/vectors`), đã rà soát ở #3418/#3431 và kiểm chéo bằng thư viện `noiseprotocol` thật.
 * Android luôn là S: không cần phía khởi tạo (dựng `noise1`, mở `noise2`) — đó là M7.1b (Mac).
 */
object NoiseNNpsk0 {
    val PROTOCOL_NAME: ByteArray = "Noise_NNpsk0_25519_ChaChaPoly_SHA256".toByteArray(Charsets.US_ASCII)
    const val PROLOGUE_LABEL = "HLBT1"

    private const val HASH_SIZE = 32
    private val ZERO_NONCE = ByteArray(ChaCha20Poly1305Aead.NONCE_SIZE)
    private val EMPTY = ByteArray(0)

    /** prologue = `"HLBT1"` ‖ `n_s` (0.4.5 "Link-security handshake"). */
    fun prologue(nS: ByteArray): ByteArray = PROLOGUE_LABEL.toByteArray(Charsets.US_ASCII) + nS

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun mixHash(
        h: ByteArray,
        data: ByteArray,
    ): ByteArray = sha256(h + data)

    /** `h0 = ck0` = tên giao thức, hoặc SHA-256 của nó nếu dài hơn `HASHLEN` (ở đây 37 byte > 32). */
    private fun initializeSymmetric(): Pair<ByteArray, ByteArray> {
        val h = if (PROTOCOL_NAME.size <= HASH_SIZE) PROTOCOL_NAME else sha256(PROTOCOL_NAME)
        return h to h
    }

    private class MixKeyResult(
        val ck: ByteArray,
        val tempK: ByteArray,
    )

    /** Token DH ("ee"…) hoặc phần MixKey(e.pub) thêm của chế độ psk: 2 ngõ ra qua HMAC, KHÔNG MixHash. */
    private fun mixKey(
        ck: ByteArray,
        ikm: ByteArray,
    ): MixKeyResult {
        val tempKey = HmacSha256.mac(ck, ikm)
        val out1 = HmacSha256.mac(tempKey, byteArrayOf(0x01))
        val out2 = HmacSha256.mac(tempKey, out1 + byteArrayOf(0x02))
        return MixKeyResult(ck = out1, tempK = out2)
    }

    private class PskMixResult(
        val ck: ByteArray,
        val h: ByteArray,
    )

    /** Token "psk": MixKeyAndHash — 3 ngõ ra qua HMAC rồi MixHash(temp_h). */
    private fun mixKeyAndHashPsk(
        ck: ByteArray,
        h: ByteArray,
        psk: ByteArray,
    ): PskMixResult {
        val tempKey = HmacSha256.mac(ck, psk)
        val out1 = HmacSha256.mac(tempKey, byteArrayOf(0x01))
        val out2 = HmacSha256.mac(tempKey, out1 + byteArrayOf(0x02))
        return PskMixResult(ck = out1, h = mixHash(h, out2))
    }

    /** Kết quả xác thực "khung đầu" (message 1) của một psk — đủ để dựng message 2 (0.4.5 bước 3-4). */
    class Message1Result internal constructor(
        internal val ck1: ByteArray,
        internal val h4: ByteArray,
        val ePubC: ByteArray,
    )

    /**
     * Phía S thử một psk ứng viên (0.4.5 bước 3): `message1` = `e.pub` (32) ‖ ciphertext+tag (16). Trả `null`
     * nếu tag không khớp psk này — như `reject`, không lộ lý do; gọi lại với psk kế tiếp.
     */
    fun tryOpenMessage1(
        psk: ByteArray,
        prologue: ByteArray,
        message1: ByteArray,
    ): Message1Result? {
        require(message1.size == X25519Keys.KEY_SIZE + ChaCha20Poly1305Aead.TAG_SIZE) { "bad message1 size" }
        val ePubC = message1.copyOfRange(0, X25519Keys.KEY_SIZE)
        val ciphertextTag = message1.copyOfRange(X25519Keys.KEY_SIZE, message1.size)
        val (h0, ck0) = initializeSymmetric()
        val h1 = mixHash(h0, prologue)
        val pskStep = mixKeyAndHashPsk(ck0, h1, psk)
        val h3 = mixHash(pskStep.h, ePubC)
        val eStep = mixKey(pskStep.ck, ePubC)
        return try {
            ChaCha20Poly1305Aead.open(eStep.tempK, ZERO_NONCE, ciphertextTag, h3)
            Message1Result(ck1 = eStep.ck, h4 = mixHash(h3, ciphertextTag), ePubC = ePubC)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    /** `message2` + cả hai khoá `Split()`: `c1` giải mã lượt S nhận, `c2` mã hoá lượt S gửi (0.4.5 bước 4-5). */
    class Message2Result internal constructor(
        val message2: ByteArray,
        val c1: ByteArray,
        val c2: ByteArray,
    )

    /**
     * Dựng message 2 ("e, ee") sau khi [tryOpenMessage1] đã xác thực [opened]; có thể ném
     * [GeneralSecurityException] nếu `e.pub` của C là điểm bậc thấp (bí mật chung toàn 0, [X25519Keys]) — bên gọi
     * coi như thất bại xác thực khoá (0.4.5 bước 4), gửi `reject` và đóng kênh, như `ServerHandshake` ở LAN.
     */
    fun buildMessage2(
        opened: Message1Result,
        ePrivS: ByteArray,
    ): Message2Result {
        val ePubS = X25519Keys.publicFromPrivate(ePrivS)
        val h5 = mixHash(opened.h4, ePubS)
        val eStep = mixKey(opened.ck1, ePubS)
        val dh = X25519Keys.sharedSecret(ePrivS, opened.ePubC)
        val eeStep = mixKey(eStep.ck, dh)
        val ciphertextTag = ChaCha20Poly1305Aead.seal(eeStep.tempK, ZERO_NONCE, EMPTY, h5)
        val (c1, c2) = split(eeStep.ck)
        return Message2Result(message2 = ePubS + ciphertextTag, c1 = c1, c2 = c2)
    }

    /** `Split()` (0.4.5 bước 4): `c1` dùng để C mã hoá/S giải mã, `c2` ngược lại. */
    fun split(ck2: ByteArray): Pair<ByteArray, ByteArray> {
        val tempKey = HmacSha256.mac(ck2, EMPTY)
        val c1 = HmacSha256.mac(tempKey, byteArrayOf(0x01))
        val c2 = HmacSha256.mac(tempKey, c1 + byteArrayOf(0x02))
        return c1 to c2
    }

    private const val NONCE_COUNTER_PREFIX_SIZE = 4
    private const val NONCE_MAX_COUNTER_SIZE = 8
    private const val NONCE_MAX_COUNTER_BYTE = 0xFF
    private const val REKEY_OUTPUT_SIZE = 32

    /**
     * `Rekey()` mặc định của Noise rev. 34 §4.2 (không có vector riêng của HandLive, khác mọi hàm trên — rà soát
     * kỹ trước khi khoá theo): khoá mới = 32 byte đầu của `ENCRYPT(k, maxnonce, "", 32 byte 0)`, `maxnonce` =
     * 2^64-1 (8 byte LE toàn `0xFF`, 4 byte đầu vẫn 0 theo cấu trúc nonce vận chuyển của 0.4.5).
     */
    fun rekey(key: ByteArray): ByteArray {
        val maxNonce =
            ByteArray(NONCE_COUNTER_PREFIX_SIZE) +
                ByteArray(NONCE_MAX_COUNTER_SIZE) { NONCE_MAX_COUNTER_BYTE.toByte() }
        val out = ChaCha20Poly1305Aead.seal(key, maxNonce, ByteArray(REKEY_OUTPUT_SIZE), EMPTY)
        return out.copyOfRange(0, REKEY_OUTPUT_SIZE)
    }
}
