package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.protocol.bluetooth.BtPreauthRecord
import app.handlive.android.core.protocol.testing.SharedTestVectors
import app.handlive.android.core.protocol.testing.hex
import app.handlive.android.core.protocol.testing.str
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phía S của 0.4.5 bước 1-4 qua "ống giả": một kênh giả lập chỉ truyền byte đã dựng sẵn từ bt-link.json (không
 * đụng radio, A7.1b lo việc đó) — đúng tinh thần "fake pipe" của thẻ A7.1a.
 */
class BluetoothPreauthSessionTest {
    private val keyVectors = SharedTestVectors.vectors("bt-link.json").filter { it.str("kind") == "key" }
    private val pair1 = keyVectors.first { it.str("name").contains("1") }
    private val pair2 = keyVectors.first { it.str("name").contains("2") }
    private val handshake =
        SharedTestVectors.vectors("bt-link.json").first {
            it.str("kind") == "handshake" && it.str("name").contains("cặp 1")
        }

    private fun pairKeysInOrder(
        firstPairId: String,
        secondPairId: String,
    ) = BluetoothPairKeys { listOf(firstPairId to pairFor(firstPairId), secondPairId to pairFor(secondPairId)) }

    private fun pairFor(pairId: String): ByteArray =
        if (pairId == pair1.str("pair_id")) pair1.hex("k_bt_link") else pair2.hex("k_bt_link")

    @Test
    fun verifiesTheCorrectPairEvenWhenItIsNotTriedFirst() {
        val pairKeys = pairKeysInOrder(pair2.str("pair_id"), pair1.str("pair_id")) // cặp 2 (sai) thử trước
        val session =
            BluetoothPreauthSession(
                pairKeys,
                nonce = { handshake.hex("n_s") },
                ephemeralPrivateKey = { handshake.hex("e_priv_s") },
            )
        session.challenge()
        val message1 = handshake.hex("e_pub_c") + handshake.hex("ciphertext_tag_1")
        val outcome = session.receiveNoise1(message1)
        assertTrue(outcome is BluetoothPreauthSession.Noise1Outcome.Verified)
        assertEquals(pair1.str("pair_id"), (outcome as BluetoothPreauthSession.Noise1Outcome.Verified).pairId)
        val keys = session.buildNoise2()
        assertArrayEquals(handshake.hex("c1"), keys.c1)
        assertArrayEquals(handshake.hex("c2"), keys.c2)
        assertArrayEquals(
            BtPreauthRecord.encode(BtPreauthRecord.KIND_NOISE2, handshake.hex("message2")),
            keys.noise2,
        )
    }

    @Test
    fun challengeCarriesTheKindByte() {
        val pairKeys = pairKeysInOrder(pair1.str("pair_id"), pair2.str("pair_id"))
        val session = BluetoothPreauthSession(pairKeys, nonce = { handshake.hex("n_s") })
        val challenge = session.challenge()
        assertEquals(BtPreauthRecord.KIND_CHALLENGE, BtPreauthRecord.decode(challenge).kind)
        assertArrayEquals(handshake.hex("n_s"), BtPreauthRecord.decode(challenge).payload)
    }

    @Test
    fun noPairMatchingRejectsTheChannel() {
        val session = BluetoothPreauthSession(BluetoothPairKeys { emptyList() }, nonce = { handshake.hex("n_s") })
        session.challenge()
        val message1 = handshake.hex("e_pub_c") + handshake.hex("ciphertext_tag_1")
        assertEquals(BluetoothPreauthSession.Noise1Outcome.Rejected, session.receiveNoise1(message1))
    }

    @Test
    fun replayedNoise1OnANewChannelIsRejected() {
        // Kênh mới -> n_s mới (nonce() mặc định sinh ngẫu nhiên), nhưng message1 là bản ghi lại từ kênh cũ.
        val session = BluetoothPreauthSession(pairKeysInOrder(pair1.str("pair_id"), pair2.str("pair_id")))
        session.challenge()
        val recordedMessage1 = handshake.hex("e_pub_c") + handshake.hex("ciphertext_tag_1")
        assertEquals(BluetoothPreauthSession.Noise1Outcome.Rejected, session.receiveNoise1(recordedMessage1))
    }
}
