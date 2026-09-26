package app.handlive.android.core.crypto

import app.handlive.android.core.crypto.derivation.PushKeyDerivation
import app.handlive.android.core.crypto.message.EnvelopeCipher
import app.handlive.android.core.crypto.message.PushEnvelopes
import app.handlive.android.core.protocol.ProtocolException
import app.handlive.android.core.protocol.encoding.Base64Codecs
import app.handlive.android.core.protocol.envelope.EnvelopeCodec
import app.handlive.android.core.protocol.envelope.EnvelopeHeader
import app.handlive.android.core.protocol.testing.SharedTestVectors
import app.handlive.android.core.protocol.testing.hex
import app.handlive.android.core.protocol.testing.long
import app.handlive.android.core.protocol.testing.str
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** push-envelope.json: `K_push`, envelopes sealed with it, `env_b64`, and what a receiver must refuse (CONN-04). */
class PushEnvelopeVectorTest {
    private val vectors = SharedTestVectors.vectors(FILE)

    @Test
    fun kPushOfBothPairs() {
        val keys = vectors.filter { it.str("kind") == "key" }
        keys.forEach { v ->
            assertEquals(PushKeyDerivation.INFO, v.str("info"))
            assertArrayEquals(v.str("name"), v.hex("k_push"), PushKeyDerivation.kPush(v.hex("prk")))
        }
        assertEquals(2, keys.size)
    }

    @Test
    fun envelopesSealedWithKPushMatchByteForByte() {
        val prks = vectors.filter { it.str("kind") == "key" }.associate { it.str("pair_id") to it.hex("prk") }
        val envelopes = vectors.filter { it.str("kind") == "envelope" }
        envelopes.forEach { v ->
            val name = v.str("name")
            val header = EnvelopeHeader(v.str("type"), v.str("id"), v.long("ts"))
            val sealed =
                PushEnvelopes.seal(
                    prks.getValue(v.str("pair_id")),
                    header,
                    v.str("plaintext").toByteArray(),
                    v.hex("nonce"),
                )
            assertEquals(name, v.str("payload_b64"), sealed.payload)
            assertEquals(name, v.str("envelope"), EnvelopeCodec.encode(sealed))
            assertEquals(name, v.str("env_b64"), PushEnvelopes.envB64(sealed))
            assertTrue(name, PushEnvelopes.envB64(sealed).length <= ENV_B64_MAX)
        }
        assertEquals(3, envelopes.size)
    }

    @Test
    fun invalidPushEnvelopesDoNotOpen() {
        val invalid = SharedTestVectors.invalidVectors(FILE)
        invalid.filter { it.str("reason") != "stale" }.forEach { v ->
            val opened =
                runCatching {
                    val json = Base64Codecs.decodeB64(v.str("env_b64")).toString(Charsets.UTF_8)
                    EnvelopeCipher.open(v.hex("key"), EnvelopeCodec.decode(json))
                }
            assertTrue(v.str("name"), opened.exceptionOrNull() is ProtocolException)
        }
        // "stale" opens: its age is the receiver's check (CONN-04 E7), not a cryptographic failure.
        val stale = invalid.single { it.str("reason") == "stale" }
        val json = Base64Codecs.decodeB64(stale.str("env_b64")).toString(Charsets.UTF_8)
        assertNotEquals(0, EnvelopeCipher.open(stale.hex("key"), EnvelopeCodec.decode(json)).size)
        assertEquals(9, invalid.size)
    }

    private companion object {
        const val FILE = "push-envelope.json"

        /** `env_b64` ≤ 3,000 characters (CONN-04 API 2). */
        const val ENV_B64_MAX = 3_000
    }
}
