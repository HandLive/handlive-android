package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.crypto.bluetooth.NoiseNNpsk0
import app.handlive.android.core.protocol.bluetooth.BtPreauthRecord
import app.handlive.android.core.protocol.testing.SharedTestVectors
import app.handlive.android.core.protocol.testing.hex
import app.handlive.android.core.protocol.testing.str
import app.handlive.android.core.protocol.testing.strOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `K_bt_pdisc`/`K_bt_plink`/`pr_bt` của một QR (0.4.5 "Keys", "Pairing mode") và `noise1` "khung đầu" của một liên
 * kết ghép đôi mà `K_bt_plink` trở thành psk — `shared/test-vectors/bt-pairing.json`. Không phải vector công bố
 * của Noise (xem mô tả file); phần hằng thời gian của CPace (A7.2a) không ở đây.
 */
class BtQrPairingKeysTest {
    private val keyVectors = SharedTestVectors.vectors("bt-pairing.json").filter { it.str("kind") == "key" }
    private val pair1 = keyVectors.first { it.str("name") == "cặp 1" }
    private val pair2 = keyVectors.first { it.str("name") == "cặp 2" }
    private val firstFrame = SharedTestVectors.vectors("bt-pairing.json").first { it.str("kind") == "first_frame" }
    private val invalid = SharedTestVectors.invalidVectors("bt-pairing.json")

    @Test
    fun derivesKBtPdiscAndKBtPlinkFromThePairingSecret() {
        for (vector in listOf(pair1, pair2)) {
            val secret = vector.hex("pairing_secret")
            assertArrayEquals(vector.hex("k_bt_pdisc"), BtQrPairingKeys.kBtPdisc(secret))
            assertArrayEquals(vector.hex("k_bt_plink"), BtQrPairingKeys.kBtPlink(secret))
        }
    }

    @Test
    fun prBtIsTheFirstFourBytesOfHmacOfKBtPdisc() {
        for (vector in listOf(pair1, pair2)) {
            val kBtPdisc = BtQrPairingKeys.kBtPdisc(vector.hex("pairing_secret"))
            assertArrayEquals(vector.hex("pr_bt"), BtQrPairingKeys.prBt(kBtPdisc))
        }
    }

    @Test
    fun theDerivedKBtPlinkOpensTheFirstFrameOfAPairingLink() {
        val kBtPlink = BtQrPairingKeys.kBtPlink(pair1.hex("pairing_secret"))
        assertArrayEquals(firstFrame.hex("psk"), kBtPlink)
        val prologue = NoiseNNpsk0.prologue(firstFrame.hex("n_s"))
        val opened = NoiseNNpsk0.tryOpenMessage1(kBtPlink, prologue, firstFrame.hex("message1"))
        assertTrue(opened != null)
        val record = BtPreauthRecord.decode(firstFrame.hex("record").let { it.copyOfRange(2, it.size) })
        assertArrayEquals(firstFrame.hex("message1"), record.payload)
    }

    @Test
    fun theInvalidVectorsNeverOpen() {
        for (vector in invalid) {
            val nS = vector.hex("n_s")
            val ePub = vector.hex("e_pub")
            val tag = vector.hex("ciphertext_tag")
            val psk = if (vector.strOrNull("wrong_psk") != null) vector.hex("wrong_psk") else firstFrame.hex("psk")
            val prologue = NoiseNNpsk0.prologue(nS)
            assertNull(vector.str("name"), NoiseNNpsk0.tryOpenMessage1(psk, prologue, ePub + tag))
        }
    }
}
