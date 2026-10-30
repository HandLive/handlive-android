package app.handlive.android.feature.pairing.testing

import app.handlive.android.core.crypto.bluetooth.NoiseNNpsk0
import app.handlive.android.core.crypto.primitives.CPaceX25519SHA512
import app.handlive.android.core.crypto.primitives.ChaCha20Poly1305Aead
import app.handlive.android.core.crypto.primitives.HmacSha256
import app.handlive.android.core.crypto.primitives.X25519Keys
import app.handlive.android.core.protocol.bluetooth.BtLinkConstants
import app.handlive.android.core.protocol.bluetooth.BtLinkException
import app.handlive.android.core.protocol.bluetooth.BtPreauthRecord
import app.handlive.android.core.protocol.bluetooth.BtTransportFrame
import app.handlive.android.core.transport.bluetooth.BluetoothLinkIo
import app.handlive.android.core.transport.bluetooth.BtPinPairingKeys
import app.handlive.android.core.transport.bluetooth.BtTransportCipher
import java.security.GeneralSecurityException
import java.security.MessageDigest

/**
 * Phía M-APP (C) của một liên kết ghép đôi PIN qua Bluetooth (0.4.5 "PIN", A7.2-C), như [FakeBtPairingClientLink]
 * (luồng QR) nhưng chạy CPace trước Noise: `cpace1`/`cpace2` dẫn `K_bt_pinlink`/`K_bt_pin`, rồi bắt tay
 * `Noise_NNpsk0_25519_ChaChaPoly_SHA256` dựng độc lập với [NoiseNNpsk0] (phía S, chỉ dùng chung `split`). CPace
 * dùng lại [CPaceX25519SHA512] — đã có vector riêng (`CPaceX25519SHA512VectorTest`), không phải mục tiêu của test
 * dùng lớp này.
 */
class FakeBtPinPairingClientLink(
    private val io: BluetoothLinkIo,
) {
    private var send: BtTransportCipher? = null
    private var receive: BtTransportCipher? = null

    /** `K_bt_pin` của lần ghép đôi vừa xác nhận, thay `K_pin` ở bước 9-11 PAIR-01 (chỉ hợp lệ sau [handshake] trả
     * `true`). */
    lateinit var kBtPin: ByteArray
        private set

    /**
     * `challenge` → `cpace1`/`cpace2` (PRS = [pin]) → `noise1`/`noise2` keyed bằng `K_bt_pinlink` vừa dẫn; `true`
     * khi S trả `noise2` hợp lệ, `false` khi S trả `reject`, `noise2` sai hay đóng kênh không trả lời (PIN sai,
     * cửa sổ PIN không mở, điểm bậc thấp).
     */
    suspend fun handshake(
        pin: ByteArray,
        yaScalar: ByteArray = X25519Keys.generatePrivateKey(),
    ): Boolean {
        val challenge = BtPreauthRecord.decode(io.readPreauthRecord())
        check(challenge.kind == BtPreauthRecord.KIND_CHALLENGE)
        val nS = challenge.payload
        val kBtPinlink = runCpace(pin, yaScalar, nS) ?: return false
        val initiator = Initiator(kBtPinlink, NoiseNNpsk0.prologue(nS), X25519Keys.generatePrivateKey())
        io.writeRecord(BtPreauthRecord.encode(BtPreauthRecord.KIND_NOISE1, initiator.message1()))
        val answer = BtPreauthRecord.decode(io.readPreauthRecord())
        val keys = if (answer.kind == BtPreauthRecord.KIND_NOISE2) initiator.openMessage2(answer.payload) else null
        keys?.let { (c1, c2) ->
            send = BtTransportCipher(c1)
            receive = BtTransportCipher(c2)
        }
        return keys != null
    }

    /** `cpace1` → `cpace2`; `K_bt_pinlink`, hoặc `null` khi S đóng kênh không trả lời (không cửa sổ PIN...). */
    private suspend fun runCpace(
        pin: ByteArray,
        yaScalar: ByteArray,
        nS: ByteArray,
    ): ByteArray? {
        val ci = BtPinPairingKeys.CPACE_CI.toByteArray(Charsets.US_ASCII)
        val g = CPaceX25519SHA512.calculateGenerator(pin, ci, nS)
        val ya = checkNotNull(CPaceX25519SHA512.scalarMultVfy(yaScalar, g)) { "ya bậc thấp, đổi vector" }
        io.writeRecord(BtPreauthRecord.encode(BtPreauthRecord.KIND_CPACE1, ya))
        val cpace2 =
            try {
                BtPreauthRecord.decode(io.readPreauthRecord())
            } catch (_: BtLinkException) {
                null
            }
        val yb = cpace2?.takeIf { it.kind == BtPreauthRecord.KIND_CPACE2 }?.payload
        val k = yb?.let { CPaceX25519SHA512.scalarMultVfy(yaScalar, it) } ?: return null
        val isk = CPaceX25519SHA512.isk(nS, k, ya, EMPTY, checkNotNull(yb), EMPTY)
        kBtPin = BtPinPairingKeys.kBtPin(isk, nS)
        return BtPinPairingKeys.kBtPinlink(isk, nS)
    }

    suspend fun sendText(text: String) {
        val frame =
            BtTransportFrame.encode(BtLinkConstants.CHAN_CONTROL, more = false, long = false, text.toByteArray())
        io.writeRecord(checkNotNull(send).seal(frame))
    }

    /** Envelope kế tiếp từ S, hoặc `null` khi S đã đóng liên kết. */
    suspend fun receiveText(): String? =
        try {
            val frame = BtTransportFrame.decode(checkNotNull(receive).open(io.readRecord()))
            frame.data.toString(Charsets.UTF_8)
        } catch (_: BtLinkException) {
            null
        }

    fun close() = io.close()

    private class Initiator(
        private val psk: ByteArray,
        private val prologue: ByteArray,
        private val ePrivC: ByteArray,
    ) {
        private lateinit var ck1: ByteArray
        private lateinit var h4: ByteArray

        fun message1(): ByteArray {
            val (h0, ck0) = initializeSymmetric()
            val h1 = mixHash(h0, prologue)
            val pskStep = mixKey(ck0, psk)
            val h2 = mixHash(h1, pskStep.second)
            val ePubC = X25519Keys.publicFromPrivate(ePrivC)
            val h3 = mixHash(h2, ePubC)
            val eStep = mixKey(pskStep.first, ePubC)
            val tag = ChaCha20Poly1305Aead.seal(eStep.second, ZERO_NONCE, ByteArray(0), h3)
            ck1 = eStep.first
            h4 = mixHash(h3, tag)
            return ePubC + tag
        }

        fun openMessage2(message2: ByteArray): Pair<ByteArray, ByteArray>? {
            val ePubS = message2.copyOfRange(0, X25519Keys.KEY_SIZE)
            val tag = message2.copyOfRange(X25519Keys.KEY_SIZE, message2.size)
            val h5 = mixHash(h4, ePubS)
            val eStep = mixKey(ck1, ePubS)
            val eeStep = mixKey(eStep.first, X25519Keys.sharedSecret(ePrivC, ePubS))
            return try {
                ChaCha20Poly1305Aead.open(eeStep.second, ZERO_NONCE, tag, h5)
                NoiseNNpsk0.split(eeStep.first)
            } catch (_: GeneralSecurityException) {
                null
            }
        }

        private fun initializeSymmetric(): Pair<ByteArray, ByteArray> {
            val name = NoiseNNpsk0.PROTOCOL_NAME
            val h = if (name.size <= HASH_SIZE) name else sha256(name)
            return h to h
        }

        private fun mixKey(
            ck: ByteArray,
            ikm: ByteArray,
        ): Pair<ByteArray, ByteArray> {
            val tempKey = HmacSha256.mac(ck, ikm)
            val out1 = HmacSha256.mac(tempKey, byteArrayOf(1))
            val out2 = HmacSha256.mac(tempKey, out1 + byteArrayOf(2))
            return out1 to out2
        }

        private fun mixHash(
            h: ByteArray,
            data: ByteArray,
        ): ByteArray = sha256(h + data)

        private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

        private companion object {
            const val HASH_SIZE = 32
            val ZERO_NONCE = ByteArray(ChaCha20Poly1305Aead.NONCE_SIZE)
        }
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
