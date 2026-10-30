package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.crypto.bluetooth.NoiseNNpsk0
import app.handlive.android.core.crypto.primitives.ChaCha20Poly1305Aead
import app.handlive.android.core.protocol.testing.SharedTestVectors
import app.handlive.android.core.protocol.testing.hex
import app.handlive.android.core.protocol.testing.int
import app.handlive.android.core.protocol.testing.str
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * bt-frame.json kind "transport_frame": nonce = 4 byte 0 ‖ bộ đếm 64-bit LE, khoá = `c1` của bt-link.json
 * (0.4.5 "Transport records and channels"). `Rekey()` không có vector riêng (NoiseNNpsk0.rekey) — kiểm bằng
 * cách tính thủ công khoá kỳ vọng sau rekey và so khớp với đầu ra của [BtTransportCipher], không chỉ "khác nhau".
 */
class BtTransportCipherTest {
    private val c1 =
        SharedTestVectors
            .vectors("bt-link.json")
            .first { it.str("kind") == "handshake" && it.str("name").contains("cặp 1") }
            .hex("c1")

    @Test
    fun sealMatchesVectorsAtTheirCounter() {
        for (v in SharedTestVectors.vectors("bt-frame.json").filter { it.str("kind") == "transport_frame" }) {
            val cipher = cipherAtVectorCounter(v)
            assertArrayEquals(v.str("name"), v.hex("ciphertext_tag"), cipher.seal(v.hex("plaintext")))
        }
    }

    @Test
    fun openMatchesVectorsAtTheirCounter() {
        for (v in SharedTestVectors.vectors("bt-frame.json").filter { it.str("kind") == "transport_frame" }) {
            val cipher = cipherAtVectorCounter(v)
            assertArrayEquals(v.str("name"), v.hex("plaintext"), cipher.open(v.hex("ciphertext_tag")))
        }
    }

    private fun cipherAtVectorCounter(v: JsonObject) =
        BtTransportCipher(c1, rekeyAfterRecords = Long.MAX_VALUE, initialCounter = v.int("counter").toLong())

    @Test
    fun rekeysAfterTheConfiguredRecordCountWithoutResettingTheCounter() {
        val key0 = ByteArray(32) { it.toByte() }
        val plaintext = ByteArray(256)
        val cipher = BtTransportCipher(key0, rekeyAfterRecords = 2)
        cipher.seal(plaintext) // counter 0, key0
        cipher.seal(plaintext) // counter 1, key0 -> rekey triggers after this record
        val key1 = NoiseNNpsk0.rekey(key0)
        val nonceAtCounter2 = ByteArray(12).also { it[4] = 2 }
        val expected = ChaCha20Poly1305Aead.seal(key1, nonceAtCounter2, plaintext, ByteArray(0))
        assertArrayEquals(expected, cipher.seal(plaintext))
    }
}
