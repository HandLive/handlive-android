package app.handlive.android.core.crypto

import app.handlive.android.core.crypto.primitives.CPaceX25519SHA512
import app.handlive.android.core.crypto.primitives.HkdfSha256
import app.handlive.android.core.protocol.testing.SharedTestVectors
import app.handlive.android.core.protocol.testing.hex
import app.handlive.android.core.protocol.testing.str
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest

private val EMPTY = ByteArray(0)

/**
 * cpace.json (A7.2a): `calculate_generator`/`scalar_mult_vfy`/`ISK` của [CPaceX25519SHA512] khớp cả vector
 * chính thức của draft-irtf-cfrg-cpace-21 Appendix B.1 (`official_generator`/`official_message`) và vector
 * ghép đôi bằng PIN của HandLive (`handlive_pin`, kèm `K_bt_pinlink`/`K_bt_pin` suy từ ISK); vector âm
 * (`low_order_ya`/`low_order_yb`/`sid_mismatch`/`wrong_pin`) đúng hành vi phải đóng kênh hoặc lệch ISK.
 */
class CPaceX25519SHA512VectorTest {
    private val vectors = SharedTestVectors.vectors("cpace.json")
    private val invalidVectors = SharedTestVectors.invalidVectors("cpace.json")

    @Test
    fun officialGeneratorVectorMatchesDraftAppendixB11() {
        val vector = vectors.single { it.str("kind") == "official_generator" }
        val generatorString = CPaceX25519SHA512.generatorString(vector.hex("PRS"), vector.hex("CI"), vector.hex("sid"))
        assertArrayEquals(vector.hex("generator_string"), generatorString)

        val hash = MessageDigest.getInstance("SHA-512").digest(generatorString)
        assertArrayEquals(vector.hex("hash_generator_string"), hash.copyOf(32))

        val maskedTopBit = hash.copyOf(32).also { it[31] = (it[31].toInt() and 0x7F).toByte() }
        assertArrayEquals(vector.hex("decoded_field_element"), maskedTopBit)

        val g = CPaceX25519SHA512.calculateGenerator(vector.hex("PRS"), vector.hex("CI"), vector.hex("sid"))
        assertArrayEquals(vector.hex("g"), g)
    }

    @Test
    fun officialMessageVectorMatchesDraftAppendixB12To17() {
        val vector = vectors.single { it.str("kind") == "official_message" }
        val g = vector.hex("g")

        val ya = requireNotNull(CPaceX25519SHA512.scalarMultVfy(vector.hex("ya"), g))
        assertArrayEquals(vector.hex("Ya"), ya)
        val yb = requireNotNull(CPaceX25519SHA512.scalarMultVfy(vector.hex("yb"), g))
        assertArrayEquals(vector.hex("Yb"), yb)

        val kFromC = requireNotNull(CPaceX25519SHA512.scalarMultVfy(vector.hex("ya"), yb))
        val kFromS = requireNotNull(CPaceX25519SHA512.scalarMultVfy(vector.hex("yb"), ya))
        assertArrayEquals(vector.hex("K"), kFromC)
        assertArrayEquals(vector.hex("K"), kFromS)

        val isk = CPaceX25519SHA512.isk(vector.hex("sid"), kFromC, ya, vector.hex("ADa"), yb, vector.hex("ADb"))
        assertArrayEquals(vector.hex("ISK_initiator_responder"), isk)
    }

    @Test
    fun handlivePinVectorsDeriveMatchingIskAndBluetoothKeys() {
        val pinVectors = vectors.filter { it.str("kind") == "handlive_pin" }
        for (vector in pinVectors) {
            val prs = vector.str("pin").toByteArray(Charsets.UTF_8)
            val ci = vector.str("ci").toByteArray(Charsets.US_ASCII)
            val sid = vector.hex("n_s")

            val g = CPaceX25519SHA512.calculateGenerator(prs, ci, sid)
            assertArrayEquals(vector.str("name"), vector.hex("g"), g)

            val ya = requireNotNull(CPaceX25519SHA512.scalarMultVfy(vector.hex("ya"), g))
            assertArrayEquals(vector.str("name"), vector.hex("Ya"), ya)
            val yb = requireNotNull(CPaceX25519SHA512.scalarMultVfy(vector.hex("yb"), g))
            assertArrayEquals(vector.str("name"), vector.hex("Yb"), yb)

            val k = requireNotNull(CPaceX25519SHA512.scalarMultVfy(vector.hex("ya"), yb))
            assertArrayEquals(vector.str("name"), vector.hex("K"), k)

            val isk = CPaceX25519SHA512.isk(sid, k, ya, EMPTY, yb, EMPTY)
            assertArrayEquals(vector.str("name"), vector.hex("ISK"), isk)

            val kBtPinlink = HkdfSha256.derive(isk, salt = sid, info = vector.str("k_bt_pinlink_info"), length = 32)
            assertArrayEquals(vector.str("name"), vector.hex("k_bt_pinlink"), kBtPinlink)
            val kBtPin = HkdfSha256.derive(isk, salt = sid, info = vector.str("k_bt_pin_info"), length = 32)
            assertArrayEquals(vector.str("name"), vector.hex("k_bt_pin"), kBtPin)
        }
        assertEquals(2, pinVectors.size)
    }

    @Test
    fun lowOrderYaMakesTheResponderCloseWithoutAnswering() {
        val vector = invalidVectors.single { it.str("reason") == "low_order_ya" }
        val k = CPaceX25519SHA512.scalarMultVfy(vector.hex("yb"), vector.hex("Ya_attacker"))
        assertNull(vector.str("name"), k)
    }

    @Test
    fun lowOrderYbMakesTheInitiatorCloseWithoutAnswering() {
        val vector = invalidVectors.single { it.str("reason") == "low_order_yb" }
        val k = CPaceX25519SHA512.scalarMultVfy(vector.hex("ya"), vector.hex("Yb_attacker"))
        assertNull(vector.str("name"), k)
    }

    @Test
    fun sidMismatchYieldsDifferentIskOnEachSide() {
        val vector = invalidVectors.single { it.str("reason") == "sid_mismatch" }
        val ya = vector.hex("Ya")
        val yb = vector.hex("Yb")

        val kC = requireNotNull(CPaceX25519SHA512.scalarMultVfy(vector.hex("ya"), yb))
        val iskC = CPaceX25519SHA512.isk(vector.hex("n_s_c"), kC, ya, EMPTY, yb, EMPTY)
        assertArrayEquals(vector.str("name"), vector.hex("isk_c"), iskC)

        val kS = requireNotNull(CPaceX25519SHA512.scalarMultVfy(vector.hex("yb"), ya))
        val iskS = CPaceX25519SHA512.isk(vector.hex("n_s_s"), kS, ya, EMPTY, yb, EMPTY)
        assertArrayEquals(vector.str("name"), vector.hex("isk_s"), iskS)

        assertFalse(vector.str("name"), iskC.contentEquals(iskS))
    }

    @Test
    fun wrongPinYieldsDifferentIskOnEachSide() {
        val vector = invalidVectors.single { it.str("reason") == "wrong_pin" }
        val ya = vector.hex("Ya")
        val yb = vector.hex("Yb")
        val sid = vector.hex("n_s")

        val kC = requireNotNull(CPaceX25519SHA512.scalarMultVfy(vector.hex("ya"), yb))
        val iskC = CPaceX25519SHA512.isk(sid, kC, ya, EMPTY, yb, EMPTY)
        assertArrayEquals(vector.str("name"), vector.hex("isk_c"), iskC)

        val kS = requireNotNull(CPaceX25519SHA512.scalarMultVfy(vector.hex("yb"), ya))
        val iskS = CPaceX25519SHA512.isk(sid, kS, ya, EMPTY, yb, EMPTY)
        assertArrayEquals(vector.str("name"), vector.hex("isk_s"), iskS)

        assertFalse(vector.str("name"), iskC.contentEquals(iskS))
    }

    @Test
    fun vectorFileHasExactlyTheExpectedShape() {
        assertEquals(4, vectors.size)
        assertEquals(4, invalidVectors.size)
        assertNotNull(invalidVectors.singleOrNull { it.str("reason") == "low_order_ya" })
        assertNotNull(invalidVectors.singleOrNull { it.str("reason") == "low_order_yb" })
    }
}
