package app.handlive.android.core.crypto

import app.handlive.android.core.crypto.bluetooth.NoiseNNpsk0
import app.handlive.android.core.protocol.testing.SharedTestVectors
import app.handlive.android.core.protocol.testing.hex
import app.handlive.android.core.protocol.testing.objects
import app.handlive.android.core.protocol.testing.str
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * bt-link.json kind "handshake": `Noise_NNpsk0_25519_ChaChaPoly_SHA256` phía S (0.4.5 bước 1-4). Vector không
 * phải vector công bố của tài liệu Noise (xem mô tả file) — rà soát riêng ở #3418/#3431/#3432 (Trang, Huy) bằng
 * thư viện `noiseprotocol` thật, nên đáng tin làm điểm neo cho cổng vào của HandLive.
 */
class NoiseNNpsk0Test {
    private val handshakeVectors =
        SharedTestVectors.file("bt-link.json").objects("vectors").filter { it.str("kind") == "handshake" }

    @Test
    fun message1AndMessage2MatchVectors() {
        for (v in handshakeVectors) {
            val name = v.str("name")
            val prologue = NoiseNNpsk0.prologue(v.hex("n_s"))
            assertArrayEquals(name, v.hex("prologue"), prologue)
            val opened = NoiseNNpsk0.tryOpenMessage1(v.hex("psk"), prologue, v.hex("message1"))
            assertNotNull(name, opened)
            assertArrayEquals(name, v.hex("e_pub_c"), opened!!.ePubC)
            val built = NoiseNNpsk0.buildMessage2(opened, v.hex("e_priv_s"))
            assertArrayEquals(name, v.hex("message2"), built.message2)
            assertArrayEquals(name, v.hex("c1"), built.c1)
            assertArrayEquals(name, v.hex("c2"), built.c2)
        }
        assertEquals(1, handshakeVectors.size)
    }

    @Test
    fun wrongPskNeverVerifies() {
        val invalid = vectorByReason("wrong_key")
        val prologue = NoiseNNpsk0.prologue(invalid.hex("n_s"))
        val message1 = invalid.hex("e_pub_c") + invalid.hex("ciphertext_tag_1")
        assertNull(NoiseNNpsk0.tryOpenMessage1(invalid.hex("wrong_psk"), prologue, message1))
    }

    @Test
    fun tamperedNoise1TagNeverVerifies() {
        val invalid = vectorByReason("tag_mismatch_noise1")
        val pair1Psk = handshakeVectors.first { it.str("name").contains("cặp 1") }.hex("psk")
        val prologue = NoiseNNpsk0.prologue(invalid.hex("n_s"))
        val message1 = invalid.hex("e_pub_c") + invalid.hex("ciphertext_tag_1")
        assertNull(NoiseNNpsk0.tryOpenMessage1(pair1Psk, prologue, message1))
    }

    @Test
    fun replayedNoise1OnANewChannelNeverVerifies() {
        val invalid = vectorByReason("replayed_channel")
        val pair1Psk = handshakeVectors.first { it.str("name").contains("cặp 1") }.hex("psk")
        // n_s của kênh mới (challenge khác) — message1 ghi lại từ kênh cũ vẫn mang e_pub/ciphertext cũ.
        val prologue = NoiseNNpsk0.prologue(invalid.hex("n_s"))
        val message1 = invalid.hex("e_pub_c") + invalid.hex("ciphertext_tag_1")
        assertNull(NoiseNNpsk0.tryOpenMessage1(pair1Psk, prologue, message1))
    }

    private fun vectorByReason(reason: String) =
        SharedTestVectors.invalidVectors("bt-link.json").single { it.str("reason") == reason }
}
