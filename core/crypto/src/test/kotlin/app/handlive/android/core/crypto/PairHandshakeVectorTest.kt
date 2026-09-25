package app.handlive.android.core.crypto

import app.handlive.android.core.crypto.derivation.AttestationFields
import app.handlive.android.core.crypto.derivation.DeviceIdDerivation
import app.handlive.android.core.crypto.derivation.PairingAuthDerivation
import app.handlive.android.core.crypto.derivation.PairingKeyDerivation
import app.handlive.android.core.crypto.derivation.PairingParty
import app.handlive.android.core.crypto.derivation.PeerRole
import app.handlive.android.core.crypto.identity.DeviceIdentity
import app.handlive.android.core.crypto.primitives.Argon2id
import app.handlive.android.core.crypto.primitives.Ed25519Keys
import app.handlive.android.core.crypto.primitives.HmacSha256
import app.handlive.android.core.protocol.encoding.Base64Codecs
import app.handlive.android.core.protocol.envelope.EnvelopeCodec
import app.handlive.android.core.protocol.envelope.UnencryptedEnvelopes
import app.handlive.android.core.protocol.pairing.PairConfirmData
import app.handlive.android.core.protocol.pairing.PairDoneData
import app.handlive.android.core.protocol.pairing.PairHelloData
import app.handlive.android.core.protocol.pairing.PairOfferData
import app.handlive.android.core.protocol.testing.Hex
import app.handlive.android.core.protocol.testing.SharedTestVectors
import app.handlive.android.core.protocol.testing.hex
import app.handlive.android.core.protocol.testing.int
import app.handlive.android.core.protocol.testing.long
import app.handlive.android.core.protocol.testing.objects
import app.handlive.android.core.protocol.testing.str
import app.handlive.android.core.protocol.testing.strOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * pair-handshake.json (PAIR-01 API 1–5, 0.6.2): every key, byte string, MAC and signature of the QR and PIN pairings
 * recomputed with the Android derivations, the `pair` messages decoded with the Android codec, and the negative
 * vectors that the client or both sides check (`offer`, `done`, `K_pin`) failing at exactly their stated check. The
 * phone's own side of the exchange runs against the same file in feature/pairing (PairingExchangeVectorTest).
 */
class PairHandshakeVectorTest {
    @Test
    fun identitiesSecretsAndAuthKeyMatch() {
        for (v in vectors) {
            val name = v.str("name")
            for (side in listOf("client", "android")) {
                val identity = identity(v, side)
                assertEquals(name, v.str("${side}_ik_sig_pub"), Hex.encode(identity.signingPublicKey))
                assertEquals(name, v.str("${side}_ik_dh_pub"), Hex.encode(identity.dhPublicKey))
                assertEquals(name, v.str("${side}_device_id"), identity.deviceId)
            }
            assertEquals(name, v.str("secret"), Hex.encode(secret(v)))
            if (v.str("mode") == PairHelloData.MODE_PIN) assertPinParameters(v)
            assertEquals(name, v.str("k_pa_info"), PairingAuthDerivation.AUTH_INFO)
            assertEquals(name, v.str("k_pa"), Hex.encode(authKey(v)))
        }
        assertEquals(setOf(PairHelloData.MODE_QR, PairHelloData.MODE_PIN), vectors.map { it.str("mode") }.toSet())
    }

    @Test
    fun transcriptAttestationSignaturesAndMacsMatch() {
        for (v in vectors) {
            val name = v.str("name")
            val transcript = transcript(v)
            assertEquals(name, v.str("t_offer"), Hex.encode(transcript))
            assertEquals(name, joinedParts(v, "t_offer_parts"), v.str("t_offer"))
            assertEquals(name, v.str("offer_mac"), Hex.encode(PairingAuthDerivation.offerMac(authKey(v), transcript)))
            val attestation = attestation(v)
            assertEquals(name, v.str("attestation"), Hex.encode(attestation))
            assertEquals(name, joinedParts(v, "attestation_parts"), v.str("attestation"))
            assertEquals(name, v.str("sig_c"), Hex.encode(identity(v, "client").sign(attestation)))
            assertEquals(name, v.str("sig_s"), Hex.encode(identity(v, "android").sign(attestation)))
            assertTrue(name, Ed25519Keys.verify(v.hex("client_ik_sig_pub"), attestation, v.hex("sig_c")))
            val confirmMac =
                PairingAuthDerivation.confirmMac(
                    authKey(v),
                    transcript,
                    v.str("pair_id"),
                    v.long("created_at"),
                    v.hex("sig_c"),
                )
            assertEquals(name, v.str("confirm_mac"), Hex.encode(confirmMac))
            assertEquals(name, joinedParts(v, "confirm_mac_input_parts"), v.str("confirm_mac_input"))
            val doneMac = PairingAuthDerivation.doneMac(authKey(v), v.str("pair_id"), v.hex("sig_s"))
            assertEquals(name, v.str("done_mac"), Hex.encode(doneMac))
            assertEquals(name, joinedParts(v, "done_mac_input_parts"), v.str("done_mac_input"))
        }
    }

    @Test
    fun prkFromBothSidesAndPrkChecksMatch() {
        for (v in vectors) {
            val name = v.str("name")
            val clientId = v.str("client_device_id")
            val androidId = v.str("android_device_id")
            assertArrayEquals(name, v.hex("prk_salt_input"), PairingKeyDerivation.saltInput(androidId, clientId))
            assertArrayEquals(name, v.hex("prk_salt"), PairingKeyDerivation.salt(clientId, androidId))
            assertEquals(name, v.str("prk_info"), PairingKeyDerivation.INFO)
            assertArrayEquals(name, v.hex("prk"), prk(v))
            val fromClient =
                PairingKeyDerivation.prk(
                    v.hex("client_ik_dh_priv"),
                    v.hex("android_ik_dh_pub"),
                    secret(v),
                    clientId,
                    androidId,
                )
            assertArrayEquals(name, v.hex("prk"), fromClient)
            val pairId = v.str("pair_id")
            assertEquals(name, v.str("prk_check_c"), Hex.encode(PairingAuthDerivation.prkCheck(prk(v), pairId, CLIENT)))
            assertEquals(name, v.str("prk_check_s"), Hex.encode(PairingAuthDerivation.prkCheck(prk(v), pairId, SERVER)))
        }
    }

    @Test
    fun pairMessagesDecodeToTheVectorValues() {
        for (v in vectors) {
            val name = v.str("name")
            val hello = read(v.str("hello_envelope"), PairHelloData.serializer())
            assertEquals(
                name,
                listOf(v.str("mode"), v.str("client_device_id"), v.str("client_name")),
                listOf(hello.mode, hello.deviceId, hello.name),
            )
            assertArrayEquals(name, v.hex("nonce_c"), Base64Codecs.decodeB64u(hello.nonce, KEY_SIZE))
            val offer = read(v.str("offer_envelope"), PairOfferData.serializer())
            assertEquals(name, v.str("android_name"), offer.name)
            assertArrayEquals(name, v.hex("tls_sha256"), Base64Codecs.decodeB64u(offer.tlsSha256, KEY_SIZE))
            val confirm = read(v.str("confirm_envelope"), PairConfirmData.serializer())
            assertEquals(name, v.long("created_at"), confirm.createdAt)
            assertArrayEquals(name, v.hex("sig_c"), Base64Codecs.decodeB64u(confirm.sig, SIGNATURE_SIZE))
            val done = read(v.str("done_envelope"), PairDoneData.serializer())
            assertArrayEquals(name, v.hex("done_mac"), Base64Codecs.decodeB64u(done.mac, KEY_SIZE))
            assertNull(name, offerFailure(v, offer))
            assertNull(name, doneFailure(v, done))
        }
    }

    @Test
    fun negativeVectorsCheckedByTheClientFailAtTheirCheck() {
        val checked = invalid.filter { it.strOrNull("message") in setOf("offer", "done") }
        for (n in checked) {
            val v = vectorOf(n)
            val failure =
                when (n.str("message")) {
                    "offer" -> offerFailure(v, read(n.str("envelope"), PairOfferData.serializer()))
                    else -> doneFailure(v, read(n.str("envelope"), PairDoneData.serializer()))
                }
            assertEquals(n.str("name"), n.str("check"), failure)
        }
        assertEquals(setOf("offer_mac", "done_mac", "prk_check_s", "sig_s"), checked.map { it.str("check") }.toSet())
    }

    @Test
    fun wrongPinKeysDifferAndAreReproducedByTheAndroidArgon2() {
        val pinKeys = invalid.filter { it.str("check") == "k_pin" }
        for (n in pinKeys) {
            val v = vectorOf(n)
            assertNotEquals(n.str("name"), n.str("k_pin_used"), Hex.encode(secret(v)))
            val parameters = (n["argon2_used"] ?: v.getValue("argon2")).jsonObject
            val used =
                Argon2id.hash(
                    n.str("pin_used").toByteArray(Charsets.UTF_8),
                    v.hex("nonce_c") + v.hex("nonce_s"),
                    Argon2id.Parameters(
                        passes = parameters.int("t"),
                        memoryKiB = parameters.int("m_kib"),
                        parallelism = parameters.int("p"),
                        tagLength = parameters.int("length"),
                    ),
                )
            assertEquals(n.str("name"), n.str("k_pin_used"), Hex.encode(used))
        }
        assertEquals(setOf("wrong_parameters", "wrong_encoding"), pinKeys.map { it.str("reason") }.toSet())
    }

    /** The client's checks of `pair/offer` in the order of API 3 rule 3: MAC, `device_id` of the key, TLS pin. */
    private fun offerFailure(
        v: JsonObject,
        offer: PairOfferData,
    ): String? {
        val signingKey = Base64Codecs.decodeB64u(offer.ikSigPub, KEY_SIZE)
        val tls = Base64Codecs.decodeB64u(offer.tlsSha256, KEY_SIZE)
        val server =
            PairingParty(
                offer.deviceId,
                Base64Codecs.decodeB64u(offer.nonce, KEY_SIZE),
                signingKey,
                Base64Codecs.decodeB64u(offer.ikDhPub, KEY_SIZE),
                offer.name,
            )
        val transcript = PairingAuthDerivation.offerTranscript(clientParty(v), server, tls)
        val expectedMac = PairingAuthDerivation.offerMac(authKey(v), transcript)
        return when {
            !HmacSha256.constantTimeEquals(expectedMac, Base64Codecs.decodeB64u(offer.mac)) -> "offer_mac"
            !DeviceIdDerivation.matches(offer.deviceId, signingKey) -> "offer_device_id"
            !tls.contentEquals(v.hex("tls_sha256")) -> "offer_tls"
            else -> null
        }
    }

    /** The client's checks of `pair/done` (API 5 rule 1): MAC, Android's `prk_check`, Android's signature. */
    private fun doneFailure(
        v: JsonObject,
        done: PairDoneData,
    ): String? {
        val pairId = v.str("pair_id")
        val signature = Base64Codecs.decodeB64u(done.sig)
        val expectedMac = PairingAuthDerivation.doneMac(authKey(v), pairId, signature)
        val expectedCheck = PairingAuthDerivation.prkCheck(prk(v), pairId, SERVER)
        return when {
            !HmacSha256.constantTimeEquals(expectedMac, Base64Codecs.decodeB64u(done.mac)) -> "done_mac"
            !HmacSha256.constantTimeEquals(expectedCheck, Base64Codecs.decodeB64u(done.prkCheck)) -> "prk_check_s"
            !Ed25519Keys.verify(v.hex("android_ik_sig_pub"), attestation(v), signature) -> "sig_s"
            else -> null
        }
    }

    private fun assertPinParameters(v: JsonObject) {
        val argon2 = v.getValue("argon2").jsonObject
        val spec = PairingAuthDerivation.PIN_PARAMETERS
        assertEquals(
            listOf(Argon2id.VERSION, spec.passes, spec.memoryKiB, spec.parallelism, spec.tagLength),
            listOf("version", "t", "m_kib", "p", "length").map { argon2.int(it) },
        )
        assertEquals(v.str("k_pin"), Hex.encode(secret(v)))
    }

    private companion object {
        const val FILE = "pair-handshake.json"
        const val KEY_SIZE = 32
        const val SIGNATURE_SIZE = 64
        val CLIENT = PeerRole.CLIENT
        val SERVER = PeerRole.SERVER

        val vectors: List<JsonObject> by lazy { SharedTestVectors.vectors(FILE) }
        val invalid: List<JsonObject> by lazy { SharedTestVectors.invalidVectors(FILE) }

        /** `pairing_secret`, or `K_pin` from the PIN: derived once per vector, Argon2id at 64 MiB is slow. */
        private val secrets = mutableMapOf<String, ByteArray>()

        fun vectorOf(negative: JsonObject): JsonObject = vectors.single { it.str("name") == negative.str("vector") }

        fun secret(v: JsonObject): ByteArray =
            secrets.getOrPut(v.str("name")) {
                if (v.str("mode") == PairHelloData.MODE_QR) {
                    v.hex("pairing_secret")
                } else {
                    PairingAuthDerivation.pinKey(v.str("pin"), v.hex("nonce_c"), v.hex("nonce_s"))
                }
            }

        fun identity(
            v: JsonObject,
            side: String,
        ) = DeviceIdentity(v.hex("${side}_ik_sig_seed"), v.hex("${side}_ik_dh_priv"))

        fun authKey(v: JsonObject): ByteArray =
            PairingAuthDerivation.authKey(secret(v), v.hex("nonce_c"), v.hex("nonce_s"))

        fun clientParty(v: JsonObject) =
            PairingParty(
                v.str("client_device_id"),
                v.hex("nonce_c"),
                v.hex("client_ik_sig_pub"),
                v.hex("client_ik_dh_pub"),
                v.str("client_name"),
            )

        fun transcript(v: JsonObject): ByteArray {
            val server =
                PairingParty(
                    v.str("android_device_id"),
                    v.hex("nonce_s"),
                    v.hex("android_ik_sig_pub"),
                    v.hex("android_ik_dh_pub"),
                    v.str("android_name"),
                )
            return PairingAuthDerivation.offerTranscript(clientParty(v), server, v.hex("tls_sha256"))
        }

        fun attestation(v: JsonObject): ByteArray =
            PairingAuthDerivation.attestation(
                AttestationFields(
                    pairId = v.str("pair_id"),
                    androidDeviceId = v.str("android_device_id"),
                    clientDeviceId = v.str("client_device_id"),
                    androidSigningKey = v.hex("android_ik_sig_pub"),
                    clientSigningKey = v.hex("client_ik_sig_pub"),
                    createdAt = v.long("created_at"),
                ),
            )

        /** `PRK` as Android derives it: its own `ik_dh`, the client's public key and `device_id`s. */
        fun prk(v: JsonObject): ByteArray =
            PairingKeyDerivation.prk(
                v.hex("android_ik_dh_priv"),
                v.hex("client_ik_dh_pub"),
                secret(v),
                v.str("android_device_id"),
                v.str("client_device_id"),
            )

        /** The `[{field, hex}]` parts of a byte string joined back: they must give the whole string. */
        fun joinedParts(
            v: JsonObject,
            key: String,
        ): String = v.objects(key).joinToString("") { it.str("hex") }

        fun <T> read(
            envelope: String,
            serializer: KSerializer<T>,
        ): T = UnencryptedEnvelopes.read(EnvelopeCodec.decode(envelope), serializer).data
    }
}
