package app.handlive.android.feature.pairing.testing

import app.handlive.android.core.crypto.bluetooth.NoiseNNpsk0
import app.handlive.android.core.crypto.primitives.ChaCha20Poly1305Aead
import app.handlive.android.core.crypto.primitives.HmacSha256
import app.handlive.android.core.crypto.primitives.X25519Keys
import app.handlive.android.core.protocol.bluetooth.BtLinkConstants
import app.handlive.android.core.protocol.bluetooth.BtLinkException
import app.handlive.android.core.protocol.bluetooth.BtPreauthRecord
import app.handlive.android.core.protocol.bluetooth.BtTransportFrame
import app.handlive.android.core.transport.bluetooth.BluetoothLinkIo
import app.handlive.android.core.transport.bluetooth.BtTransportCipher
import kotlinx.coroutines.channels.Channel
import java.security.GeneralSecurityException
import java.security.MessageDigest

/** Hai đầu một liên kết L2CAP giả trong bộ nhớ: đầu này đọc cái đầu kia viết (không radio). */
class PipeBluetoothLinkIo private constructor(
    private val readQueue: Channel<ByteArray>,
    private val writeQueue: Channel<ByteArray>,
) : BluetoothLinkIo {
    override suspend fun readPreauthRecord(): ByteArray = readRecord()

    override suspend fun readRecord(): ByteArray =
        readQueue.receiveCatching().getOrNull() ?: throw BtLinkException("pipe closed")

    override suspend fun writeRecord(body: ByteArray) {
        writeQueue.send(body)
    }

    override fun close() {
        readQueue.close()
        writeQueue.close()
    }

    companion object {
        fun pair(): Pair<PipeBluetoothLinkIo, PipeBluetoothLinkIo> {
            val aToB = Channel<ByteArray>(Channel.UNLIMITED)
            val bToA = Channel<ByteArray>(Channel.UNLIMITED)
            return PipeBluetoothLinkIo(bToA, aToB) to PipeBluetoothLinkIo(aToB, bToA)
        }
    }
}

/**
 * Phía M-APP (C) của một liên kết Bluetooth: bắt tay `Noise_NNpsk0_25519_ChaChaPoly_SHA256` (rev. 34) dựng độc lập với
 * [NoiseNNpsk0] (phía S, chỉ dùng chung `split`), rồi gửi/nhận envelope trên kênh điều khiển `0x01` — đủ để lái
 * PAIR-01 qua liên kết ghép đôi QR như Mac sẽ làm (0.4.5 "Pairing link").
 */
class FakeBtPairingClientLink(
    private val io: BluetoothLinkIo,
) {
    private var send: BtTransportCipher? = null
    private var receive: BtTransportCipher? = null

    /** Bước 1-4 với [psk]; `true` khi S trả `noise2` hợp lệ, `false` khi S trả `reject` hoặc `noise2` sai. */
    suspend fun handshake(psk: ByteArray): Boolean {
        val challenge = BtPreauthRecord.decode(io.readPreauthRecord())
        check(challenge.kind == BtPreauthRecord.KIND_CHALLENGE)
        val initiator = Initiator(psk, NoiseNNpsk0.prologue(challenge.payload), X25519Keys.generatePrivateKey())
        io.writeRecord(BtPreauthRecord.encode(BtPreauthRecord.KIND_NOISE1, initiator.message1()))
        val answer = BtPreauthRecord.decode(io.readPreauthRecord())
        val keys = if (answer.kind == BtPreauthRecord.KIND_NOISE2) initiator.openMessage2(answer.payload) else null
        keys?.let { (c1, c2) ->
            send = BtTransportCipher(c1)
            receive = BtTransportCipher(c2)
        }
        return keys != null
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
            // MixKeyAndHash(psk): ck, temp_h, temp_k = HKDF(ck, psk, 3); MixHash(temp_h) — temp_h là ngõ ra thứ hai.
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

        /** HKDF của Noise, hai ngõ ra đầu: (`ck` mới, ngõ ra thứ hai — `temp_k`, hoặc `temp_h` của token "psk"). */
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
}
