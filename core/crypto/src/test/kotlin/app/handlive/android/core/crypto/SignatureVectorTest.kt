package app.handlive.android.core.crypto

import app.handlive.android.core.crypto.derivation.DeviceIdDerivation
import app.handlive.android.core.crypto.derivation.RelaySignatureMessages
import app.handlive.android.core.crypto.primitives.Ed25519Keys
import app.handlive.android.core.protocol.encoding.Base64Codecs
import app.handlive.android.core.protocol.testing.Hex
import app.handlive.android.core.protocol.testing.SharedTestVectors
import app.handlive.android.core.protocol.testing.hex
import app.handlive.android.core.protocol.testing.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ed25519.json (the algorithm of the PAIR-01 attestation signatures) and relay-auth.json (HLREG1/HLAUTH1, 0.6.4).
 * Ed25519 is deterministic: Android re-signs and must produce the same bytes; every negative vector must fail a
 * strict verifier (Tink rejects S ≥ L and signatures that are not 64 bytes).
 */
class SignatureVectorTest {
    @Test
    fun ed25519SignaturesAreReproducedAndVerified() {
        val vectors = SharedTestVectors.vectors("ed25519.json")
        for (v in vectors) {
            val name = v.str("name")
            val seed = v.hex("seed")
            assertArrayEquals(name, v.hex("public_key"), Ed25519Keys.publicFromSeed(seed))
            assertEquals(name, v.str("signature"), Hex.encode(Ed25519Keys.sign(seed, v.hex("message"))))
            assertTrue(name, Ed25519Keys.verify(v.hex("public_key"), v.hex("message"), v.hex("signature")))
        }
        assertEquals(3, vectors.size)
    }

    @Test
    fun ed25519NegativeVectorsAreRejected() {
        val invalid = SharedTestVectors.invalidVectors("ed25519.json")
        for (v in invalid) {
            assertFalse(v.str("name"), Ed25519Keys.verify(v.hex("public_key"), v.hex("message"), v.hex("signature")))
        }
        assertEquals(6, invalid.size)
    }

    @Test
    fun relayRegistrationAndTokenMessagesAndSignaturesMatch() {
        val vectors = SharedTestVectors.vectors("relay-auth.json")
        for (v in vectors) {
            val name = v.str("name")
            val seed = v.hex("ik_sig_seed")
            val publicKey = Ed25519Keys.publicFromSeed(seed)
            assertArrayEquals(name, v.hex("ik_sig_pub"), publicKey)
            assertEquals(name, v.str("device_id"), DeviceIdDerivation.deviceId(publicKey))
            val message = expectedMessage(v)
            assertEquals(name, v.str("message"), Hex.encode(message))
            val signature = Ed25519Keys.sign(seed, message)
            assertEquals(name, v.str("sig"), Hex.encode(signature))
            // The wire request carries the same signature, b64u without padding.
            val request = Json.parseToJsonElement(v.str("request")).jsonObject
            assertEquals(name, Base64Codecs.encodeB64u(signature), request.text("sig"))
        }
        assertEquals(6, vectors.size)
    }

    @Test
    fun relayNegativeVectorsFailTheRelayChecks() {
        val invalid = SharedTestVectors.invalidVectors("relay-auth.json")
        for (v in invalid) {
            assertFalse(v.str("name"), acceptedByRelay(v))
        }
        assertEquals(10, invalid.size)
    }

    /** The message rebuilt from the vector's own fields (the builder under test), not copied from the file. */
    private fun expectedMessage(v: JsonObject): ByteArray =
        if (v.str("kind") == "register") {
            RelaySignatureMessages.registration(
                v.str("device_id"),
                v.hex("ik_sig_pub"),
                v.str("platform"),
                v.getValue("ts").jsonPrimitive.long,
            )
        } else {
            RelaySignatureMessages.token(v.hex("challenge"), v.str("device_id"))
        }

    /** What the relay checks (CONN-03 API 1, API 3): the device_id of the key, a 64-byte signature, the message. */
    private fun acceptedByRelay(v: JsonObject): Boolean {
        val request = Json.parseToJsonElement(v.str("request")).jsonObject
        val deviceId = request.text("device_id")
        val publicKey = v.hex("ik_sig_pub")
        val signature = runCatching { Base64Codecs.decodeB64u(request.text("sig"), SIGNATURE_SIZE) }.getOrNull()
        val message =
            if (v.str("kind") == "register") {
                RelaySignatureMessages.registration(
                    deviceId,
                    Base64Codecs.decodeB64u(request.text("ik_sig_pub"), PUBLIC_KEY_SIZE),
                    request.text("platform"),
                    request.getValue("ts").jsonPrimitive.long,
                )
            } else {
                RelaySignatureMessages.token(Hex.decode(v.str("challenge")), deviceId)
            }
        return signature != null &&
            DeviceIdDerivation.matches(deviceId, publicKey) &&
            Ed25519Keys.verify(publicKey, message, signature)
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private companion object {
        const val SIGNATURE_SIZE = 64
        const val PUBLIC_KEY_SIZE = 32
    }
}
