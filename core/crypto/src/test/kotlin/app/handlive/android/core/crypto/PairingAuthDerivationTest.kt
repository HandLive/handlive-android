package app.handlive.android.core.crypto

import app.handlive.android.core.crypto.derivation.AttestationFields
import app.handlive.android.core.crypto.derivation.PairingAuthDerivation
import app.handlive.android.core.crypto.derivation.PairingParty
import app.handlive.android.core.crypto.derivation.PeerRole
import app.handlive.android.core.protocol.testing.Hex
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

/**
 * PAIR-01 byte strings and MACs (API 3–5, 0.6.2) on synthetic inputs; the expected values were computed independently
 * with Python `hmac`/`hashlib` from the same inputs (see the report of card A1.2). The shared vectors of
 * pair-handshake.json check the same derivations with real keys (PairHandshakeVectorTest).
 */
class PairingAuthDerivationTest {
    private val client =
        PairingParty(
            deviceId = "5b1f8c2e-9a4d-8e6f-a1b2-c3d4e5f60718",
            nonce = ByteArray(32) { it.toByte() },
            signingPublicKey = ByteArray(32) { 0x11 },
            dhPublicKey = ByteArray(32) { 0x22 },
            name = "MacBook của Lan",
        )
    private val server =
        PairingParty(
            deviceId = "8c7d6e5f-4a3b-8c2d-9e1f-0a1b2c3d4e5f",
            nonce = ByteArray(32) { (it + 32).toByte() },
            signingPublicKey = ByteArray(32) { 0x33 },
            dhPublicKey = ByteArray(32) { 0x44 },
            name = "Pixel của Lan",
        )
    private val tls = ByteArray(32) { 0x55 }
    private val transcript = PairingAuthDerivation.offerTranscript(client, server, tls)
    private val authKey = PairingAuthDerivation.authKey(ByteArray(32) { 0x66 }, client.nonce, server.nonce)

    @Test
    fun offerTranscriptAndAuthKeyMatchTheIndependentValues() {
        assertEquals(302, transcript.size)
        assertEquals("e86712064866f453dab015a4c9fa26db8e90fba670c6da9e56843a66c5f1caa0", sha256(transcript))
        assertEquals("681841cf55cdc0cfe256d293d4a7da6f93d4a6e3b82548dee262da42f4b1e03f", Hex.encode(authKey))
        assertEquals(
            "c85231976b4c876e159d8b9200950ed85b7af2ecb688742617be08b6b3316eb3",
            Hex.encode(PairingAuthDerivation.offerMac(authKey, transcript)),
        )
    }

    @Test
    fun confirmDoneAndPrkChecksMatchTheIndependentValues() {
        assertEquals(
            "a17c1eadf652878f3b9092285bad72a60dccb3e4b917046e9205ddaaf1e13a69",
            Hex.encode(
                PairingAuthDerivation.confirmMac(authKey, transcript, PAIR_ID, CREATED_AT, ByteArray(64) { 0x77 }),
            ),
        )
        assertEquals(
            "1bd5207767db43716e5ce541c2d1d590fd39208b6b50e9f94c825e68b92d443e",
            Hex.encode(PairingAuthDerivation.doneMac(authKey, PAIR_ID, ByteArray(64) { 0x88.toByte() })),
        )
        val prk = ByteArray(32) { 0x99.toByte() }
        assertEquals(
            "5bc47f03f21deff98029ae52055c0be38adf408af776de9d29e9f75cd9ced5e3",
            Hex.encode(PairingAuthDerivation.prkCheck(prk, PAIR_ID, PeerRole.CLIENT)),
        )
        assertEquals(
            "3585200b8cb4d68ce91530d330479a6d6ddc3eafa6c45142e651a7bdbe53e0f7",
            Hex.encode(PairingAuthDerivation.prkCheck(prk, PAIR_ID, PeerRole.SERVER)),
        )
    }

    @Test
    fun attestationLayoutAndSafetyCode() {
        val attestation =
            PairingAuthDerivation.attestation(
                AttestationFields(
                    pairId = PAIR_ID,
                    androidDeviceId = server.deviceId,
                    clientDeviceId = client.deviceId,
                    androidSigningKey = server.signingPublicKey,
                    clientSigningKey = client.signingPublicKey,
                    createdAt = CREATED_AT,
                ),
            )
        assertEquals(
            "484c50414952313f2b1c4d5e6f4a7b8c9d0e1f2a3b4c5d8c7d6e5f4a3b8c2d9e1f0a1b2c3d4e5f5b1f8c2e9a4d8e6f" +
                "a1b2c3d4e5f60718" + "33".repeat(32) + "11".repeat(32) + "000001922229940a",
            Hex.encode(attestation),
        )
        assertEquals("fc647e0b", sha256(attestation).take(8))
    }

    private fun sha256(bytes: ByteArray) = Hex.encode(MessageDigest.getInstance("SHA-256").digest(bytes))

    private companion object {
        const val PAIR_ID = "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
        const val CREATED_AT = 1_727_150_003_210L
    }
}
