package app.handlive.android.core.crypto

import app.handlive.android.core.crypto.derivation.DeviceIdDerivation
import app.handlive.android.core.crypto.derivation.RevocationStatement
import app.handlive.android.core.crypto.primitives.Ed25519Keys
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.encoding.Base64Codecs
import app.handlive.android.core.protocol.relay.DevicesDeleteRequest
import app.handlive.android.core.protocol.relay.PairRevokeRequest
import app.handlive.android.core.protocol.relay.RelayIncoming
import app.handlive.android.core.protocol.relay.RelayWire
import app.handlive.android.core.protocol.relay.Revocation
import app.handlive.android.core.protocol.testing.Hex
import app.handlive.android.core.protocol.testing.SharedTestVectors
import app.handlive.android.core.protocol.testing.hex
import app.handlive.android.core.protocol.testing.long
import app.handlive.android.core.protocol.testing.str
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * revoke.json (`HLREVOKE1`, 0.6.2, PAIR-03): the phone builds the same message and signature, writes the same wire
 * bodies, and acts on a relay `pair_revoked` only when `by` is the peer and the signature verifies with the peer's key.
 */
class RevokeStatementVectorTest {
    private val file = "revoke.json"

    @Test
    fun messagesSignaturesAndWireBodiesMatch() {
        val vectors = SharedTestVectors.vectors(file)
        for (v in vectors) {
            val name = v.str("name")
            val seed = v.hex("ik_sig_seed")
            val publicKey = Ed25519Keys.publicFromSeed(seed)
            assertArrayEquals(name, v.hex("ik_sig_pub"), publicKey)
            assertEquals(name, v.str("device_id"), DeviceIdDerivation.deviceId(publicKey))
            val message = RevocationStatement.message(v.str("pair_id"), v.str("device_id"), v.long("revoked_at"))
            assertEquals(name, v.str("message"), Hex.encode(message))
            val signature = Ed25519Keys.sign(seed, message)
            assertEquals(name, v.str("sig"), Hex.encode(signature))
            assertEquals(name, v.str("sig_b64u"), Base64Codecs.encodeB64u(signature))

            val sig = v.str("sig_b64u")
            val revokedAt = v.long("revoked_at")
            assertEquals(
                name,
                v.str("revoke_request"),
                ProtocolJson.encodeToString(PairRevokeRequest.serializer(), PairRevokeRequest(revokedAt, sig)),
            )
            assertEquals(
                name,
                v.str("revocation"),
                ProtocolJson.encodeToString(Revocation.serializer(), Revocation(v.str("pair_id"), revokedAt, sig)),
            )
            // The receiver of the frame is the peer: the vector's device is that peer's peer.
            assertTrue(name, accepts(v, peerDeviceId = v.str("device_id"), peerKey = publicKey))
        }
        assertEquals(3, vectors.size)
    }

    @Test
    fun theDeleteBodyCarriesOneStatementPerPair() {
        val v = SharedTestVectors.vectors(file).first()
        val body = DevicesDeleteRequest(listOf(Revocation(v.str("pair_id"), v.long("revoked_at"), v.str("sig_b64u"))))
        assertEquals(
            "{\"revocations\":[${v.str("revocation")}]}",
            ProtocolJson.encodeToString(DevicesDeleteRequest.serializer(), body),
        )
    }

    @Test
    fun receiverNegativesAreIgnored() {
        val invalid = SharedTestVectors.invalidVectors(file).filter { it.str("check") == "receiver" }
        for (v in invalid) {
            assertFalse(v.str("name"), accepts(v, v.str("peer_device_id"), v.hex("peer_ik_sig_pub")))
        }
        assertEquals(7, invalid.size)
    }

    private fun accepts(
        v: JsonObject,
        peerDeviceId: String,
        peerKey: ByteArray,
    ): Boolean {
        val frame = (RelayWire.decode(v.str("pair_revoked")) as RelayIncoming.PairRevoked).message
        assertEquals(v.str("pair_id"), frame.pairId)
        return RevocationStatement.isFromPeer(
            frame.pairId,
            frame.by,
            frame.revokedAt,
            frame.sig,
            peerDeviceId,
            peerKey,
        )
    }
}
