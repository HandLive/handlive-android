package app.handlive.android.feature.pairing.testing

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.handlive.android.core.crypto.derivation.AttestationFields
import app.handlive.android.core.crypto.derivation.DeviceIdDerivation
import app.handlive.android.core.crypto.derivation.PairingAuthDerivation
import app.handlive.android.core.crypto.derivation.PairingKeyDerivation
import app.handlive.android.core.crypto.derivation.PairingParty
import app.handlive.android.core.crypto.derivation.PeerRole
import app.handlive.android.core.crypto.identity.DeviceIdentity
import app.handlive.android.core.crypto.keystore.AeadSecretSealer
import app.handlive.android.core.crypto.primitives.Ed25519Keys
import app.handlive.android.core.crypto.primitives.HmacSha256
import app.handlive.android.core.crypto.primitives.SecureRandomBytes
import app.handlive.android.core.crypto.primitives.X25519Keys
import app.handlive.android.core.data.db.HandLiveDatabase
import app.handlive.android.core.data.pairing.PairStore
import app.handlive.android.core.protocol.encoding.Base64Codecs
import app.handlive.android.core.protocol.envelope.EnvelopeCodec
import app.handlive.android.core.protocol.envelope.EnvelopeHeader
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.protocol.envelope.UnencryptedEnvelopes
import app.handlive.android.core.protocol.id.UuidV7Generator
import app.handlive.android.core.protocol.pairing.PairConfirmData
import app.handlive.android.core.protocol.pairing.PairDoneData
import app.handlive.android.core.protocol.pairing.PairErrorData
import app.handlive.android.core.protocol.pairing.PairHelloData
import app.handlive.android.core.protocol.pairing.PairOfferData
import app.handlive.android.core.protocol.pairing.PairOp
import app.handlive.android.core.transport.server.TextMessageSocket
import app.handlive.android.feature.pairing.exchange.LocalPairingDevice
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.KSerializer
import java.util.UUID

/** `/v1/pair` as two channels: what the client sends and what the phone answers. */
class FakeTextSocket : TextMessageSocket {
    val toPhone = Channel<String>(Channel.UNLIMITED)
    val toClient = Channel<String>(Channel.UNLIMITED)

    @Volatile
    var closeCode: Short? = null

    override val remoteAddress = "192.168.1.20"

    override suspend fun receiveText(): String? = toPhone.receiveCatching().getOrNull()

    override suspend fun sendText(text: String) {
        toClient.send(text)
    }

    override suspend fun close(
        code: Short,
        message: String,
    ) {
        closeCode = code
        toClient.close()
    }
}

/** An in-memory `handlive.db` with a software AEAD, as on the phone but without the Keystore. */
class PairStoreFixture {
    val database: HandLiveDatabase =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HandLiveDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    val store = PairStore(database.pairedDevices(), { AeadSecretSealer(newAead()) })

    private fun newAead(): Aead {
        AeadConfig.register()
        return KeysetHandle
            .generateNew(PredefinedAeadParameters.AES256_GCM)
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }
}

/** This phone in tests. */
fun localPhone(): LocalPairingDevice =
    LocalPairingDevice(
        identity = DeviceIdentity(Ed25519Keys.generateSeed(), X25519Keys.generatePrivateKey()),
        name = "Pixel của Lan",
        model = "Pixel 8",
        osVersion = "15",
        tlsSha256 = SecureRandomBytes.next(32),
    )

/**
 * The Mac or iPhone side of PAIR-01, written from the spec independently of the phone's code path (it shares only
 * the derivation primitives): builds `hello`, checks `offer` the way M-APP does (MAC, `device_id`, TLS pin), sends
 * `confirm` and checks `done`.
 */
class FakePairingClient(
    val name: String = "MacBook của Lan",
    val platform: String = "macos",
) {
    private val seed = Ed25519Keys.generateSeed()
    val signingPublicKey: ByteArray = Ed25519Keys.publicFromSeed(seed)
    private val dhPrivate = X25519Keys.generatePrivateKey()
    val dhPublicKey: ByteArray = X25519Keys.publicFromPrivate(dhPrivate)
    val deviceId: String = DeviceIdDerivation.deviceId(signingPublicKey)
    val pairingSecret: ByteArray = SecureRandomBytes.next(32)
    private val ids = UuidV7Generator()
    private val nonce = SecureRandomBytes.next(32)

    /** Result of a checked offer, kept for `confirm` and `done`. */
    class Offer(
        val data: PairOfferData,
        val authKey: ByteArray,
        val transcript: ByteArray,
        val secret: ByteArray,
    )

    fun qrCode(
        secret: ByteArray = pairingSecret,
        publicKey: ByteArray = dhPublicKey,
    ): String =
        "handlive://pair?v=1&pk=${Base64Codecs.encodeB64u(publicKey)}&ps=${Base64Codecs.encodeB64u(secret)}" +
            "&d=${Uri.encode(name)}"

    fun hello(
        mode: String = PairHelloData.MODE_QR,
        deviceIdOverride: String = deviceId,
        dhOverride: ByteArray = dhPublicKey,
    ): String =
        envelope(
            PairOp.HELLO,
            PairHelloData.serializer(),
            PairHelloData(
                mode = mode,
                deviceId = deviceIdOverride,
                nonce = Base64Codecs.encodeB64u(nonce),
                name = name,
                platform = platform,
                model = "Mac15,3",
                ikSigPub = Base64Codecs.encodeB64u(signingPublicKey),
                ikDhPub = Base64Codecs.encodeB64u(dhOverride),
            ),
        )

    /** M-APP checks (API 3 rule 3): MAC → `device_id` of `ik_sig_pub` → TLS pin. `null` means `AUTH_FAILED`. */
    fun checkOffer(
        text: String,
        secret: ByteArray,
        expectedTls: ByteArray,
    ): Offer? {
        val offer = read(text, PairOfferData.serializer()) ?: return null
        val serverNonce = Base64Codecs.decodeB64u(offer.nonce, 32)
        val server =
            PairingParty(
                offer.deviceId,
                serverNonce,
                Base64Codecs.decodeB64u(offer.ikSigPub, 32),
                Base64Codecs.decodeB64u(offer.ikDhPub, 32),
                offer.name,
            )
        val tls = Base64Codecs.decodeB64u(offer.tlsSha256, 32)
        val transcript =
            PairingAuthDerivation.offerTranscript(
                PairingParty(deviceId, nonce, signingPublicKey, dhPublicKey, name),
                server,
                tls,
            )
        val authKey = PairingAuthDerivation.authKey(secret, nonce, serverNonce)
        val macOk =
            HmacSha256.constantTimeEquals(
                PairingAuthDerivation.offerMac(authKey, transcript),
                Base64Codecs.decodeB64u(offer.mac, 32),
            )
        val valid =
            macOk && DeviceIdDerivation.matches(offer.deviceId, server.signingPublicKey) &&
                tls.contentEquals(expectedTls)
        return if (valid) Offer(offer, authKey, transcript, secret) else null
    }

    fun pinSecret(
        pin: String,
        offerText: String,
    ): ByteArray {
        val offer = checkNotNull(read(offerText, PairOfferData.serializer()))
        return PairingAuthDerivation.pinKey(pin, nonce, Base64Codecs.decodeB64u(offer.nonce, 32))
    }

    fun prk(offer: Offer): ByteArray =
        PairingKeyDerivation.prk(
            dhPrivate,
            Base64Codecs.decodeB64u(offer.data.ikDhPub, 32),
            offer.secret,
            deviceId,
            offer.data.deviceId,
        )

    fun attestation(
        offer: Offer,
        pairId: String,
        createdAt: Long,
    ): ByteArray =
        PairingAuthDerivation.attestation(
            AttestationFields(
                pairId,
                offer.data.deviceId,
                deviceId,
                Base64Codecs.decodeB64u(offer.data.ikSigPub, 32),
                signingPublicKey,
                createdAt,
            ),
        )

    /** Which field of `pair/confirm` to corrupt for the negative cases of API 4. */
    enum class Tamper { NONE, MAC, PRK_CHECK, SIGNATURE }

    fun confirm(
        offer: Offer,
        pairId: String = UUID.randomUUID().toString(),
        createdAt: Long = 1_727_150_003_210,
        tamper: Tamper = Tamper.NONE,
    ): String {
        val tamperMac = tamper == Tamper.MAC
        val tamperPrkCheck = tamper == Tamper.PRK_CHECK
        val tamperSignature = tamper == Tamper.SIGNATURE
        val signature =
            Ed25519Keys.sign(seed, attestation(offer, pairId, createdAt)).also {
                if (tamperSignature) {
                    it[0] =
                        (it[0] + 1).toByte()
                }
            }
        val prkCheck =
            PairingAuthDerivation.prkCheck(prk(offer), pairId, PeerRole.CLIENT).also {
                if (tamperPrkCheck) {
                    it[0] =
                        (it[0] + 1).toByte()
                }
            }
        val mac =
            PairingAuthDerivation
                .confirmMac(offer.authKey, offer.transcript, pairId, createdAt, signature)
                .also { if (tamperMac) it[0] = (it[0] + 1).toByte() }
        return envelope(
            PairOp.CONFIRM,
            PairConfirmData.serializer(),
            PairConfirmData(
                pairId,
                createdAt,
                Base64Codecs.encodeB64u(signature),
                Base64Codecs.encodeB64u(prkCheck),
                Base64Codecs.encodeB64u(mac),
            ),
        )
    }

    /** API 5 rule 1: `mac`, `prk_check` and the phone's signature must all be right. */
    fun checkDone(
        text: String,
        offer: Offer,
        pairId: String,
        createdAt: Long,
    ): Boolean {
        val done = read(text, PairDoneData.serializer()) ?: return false
        val signature = Base64Codecs.decodeB64u(done.sig, 64)
        val prkCheck = PairingAuthDerivation.prkCheck(prk(offer), pairId, PeerRole.SERVER)
        return Ed25519Keys.verify(
            Base64Codecs.decodeB64u(offer.data.ikSigPub, 32),
            attestation(offer, pairId, createdAt),
            signature,
        ) &&
            prkCheck.contentEquals(Base64Codecs.decodeB64u(done.prkCheck, 32)) &&
            PairingAuthDerivation
                .doneMac(
                    offer.authKey,
                    pairId,
                    signature,
                ).contentEquals(Base64Codecs.decodeB64u(done.mac, 32))
    }

    fun error(
        code: String,
        attemptsLeft: Int? = null,
    ): String = envelope(PairOp.ERROR, PairErrorData.serializer(), PairErrorData(code, "test", attemptsLeft))

    fun <T> read(
        text: String,
        serializer: KSerializer<T>,
    ): T? = runCatching { UnencryptedEnvelopes.read(EnvelopeCodec.decode(text), serializer).data }.getOrNull()

    fun opOf(text: String): String = UnencryptedEnvelopes.op(EnvelopeCodec.decode(text))

    private fun <T> envelope(
        op: String,
        serializer: KSerializer<T>,
        data: T,
    ): String =
        EnvelopeCodec.encode(
            UnencryptedEnvelopes.build(
                EnvelopeHeader(MessageType.PAIR.wire, ids.next(), System.currentTimeMillis()),
                op,
                serializer,
                data,
            ),
        )
}
