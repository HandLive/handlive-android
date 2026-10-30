package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.crypto.bluetooth.NoiseNNpsk0
import app.handlive.android.core.crypto.primitives.ChaCha20Poly1305Aead

/**
 * Trạng thái khoá một chiều sau `Split()` (0.4.5 bước 4): nonce = 4 byte 0 ‖ bộ đếm 64-bit LE của chiều đó, AAD
 * rỗng; `Rekey()` tự động sau mỗi [rekeyAfterRecords] bản ghi — bộ đếm KHÔNG reset theo (Noise rev. 34 §4.2:
 * `Rekey()` chỉ đổi khoá, không đổi `n`). Không an toàn đa luồng: bên gọi tự tuần tự hoá, như
 * [app.handlive.android.core.transport.session.SessionCipher] của phiên LAN.
 */
class BtTransportCipher(
    initialKey: ByteArray,
    private val rekeyAfterRecords: Long = BluetoothLinkConstants.LINK_REKEY_RECORDS,
    initialCounter: Long = 0,
) {
    private var key = initialKey
    private var counter: Long = initialCounter
    private var recordsSinceRekey: Long = 0

    val recordCount: Long get() = counter

    fun seal(paddedPlaintext: ByteArray): ByteArray {
        val out = ChaCha20Poly1305Aead.seal(key, nonce(counter), paddedPlaintext, EMPTY)
        advance()
        return out
    }

    fun open(ciphertextTag: ByteArray): ByteArray {
        val out = ChaCha20Poly1305Aead.open(key, nonce(counter), ciphertextTag, EMPTY)
        advance()
        return out
    }

    private fun advance() {
        counter++
        recordsSinceRekey++
        if (recordsSinceRekey >= rekeyAfterRecords) {
            key = NoiseNNpsk0.rekey(key)
            recordsSinceRekey = 0
        }
    }

    private companion object {
        val EMPTY = ByteArray(0)
        const val NONCE_COUNTER_OFFSET = 4
        const val COUNTER_BYTES = 8
        const val BITS_PER_BYTE = 8
        const val BYTE_MASK = 0xFFL

        fun nonce(counter: Long): ByteArray {
            val out = ByteArray(ChaCha20Poly1305Aead.NONCE_SIZE)
            for (i in 0 until COUNTER_BYTES) {
                out[NONCE_COUNTER_OFFSET + i] = ((counter ushr (BITS_PER_BYTE * i)) and BYTE_MASK).toByte()
            }
            return out
        }
    }
}
