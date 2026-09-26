package app.handlive.android.feature.pairing.exchange

import app.handlive.android.core.crypto.derivation.AttestationFields
import app.handlive.android.core.crypto.derivation.DeviceIdDerivation
import app.handlive.android.core.crypto.derivation.PairingAuthDerivation
import app.handlive.android.core.crypto.derivation.PairingKeyDerivation
import app.handlive.android.core.crypto.derivation.PairingParty
import app.handlive.android.core.crypto.derivation.PeerRole
import app.handlive.android.core.crypto.identity.DeviceIdentity
import app.handlive.android.core.crypto.primitives.Ed25519Keys
import app.handlive.android.core.crypto.primitives.HmacSha256
import app.handlive.android.core.crypto.primitives.SecureRandomBytes
import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.core.data.pairing.NewPair
import app.handlive.android.core.data.pairing.PairStore
import app.handlive.android.core.data.pairing.PeerKeys
import app.handlive.android.core.data.pairing.SignedAttestation
import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.encoding.Base64Codecs
import app.handlive.android.core.protocol.id.UuidBytes
import app.handlive.android.core.protocol.pairing.PairConfirmData
import app.handlive.android.core.protocol.pairing.PairDoneData
import app.handlive.android.core.protocol.pairing.PairErrorData
import app.handlive.android.core.protocol.pairing.PairHelloData
import app.handlive.android.core.protocol.pairing.PairOfferData
import app.handlive.android.core.protocol.pairing.PairOp
import app.handlive.android.core.transport.WsCloseCode
import app.handlive.android.core.transport.server.TextMessageSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** This phone as the offer describes it (PAIR-01 API 3). */
class LocalPairingDevice(
    val identity: DeviceIdentity,
    /** `Settings.Global.DEVICE_NAME`, shown on the Mac once paired. */
    val name: String,
    val model: String,
    val osVersion: String,
    /** SHA-256 of the TLS certificate the client pins (`tls_sha256`). */
    val tlsSha256: ByteArray,
)

/** Why a pairing attempt ended without a pair (PAIR-01 E1–E9). */
enum class PairingFailure {
    QR_INVALID,
    PAIRING_CLOSED,
    AUTH_FAILED,
    PIN_INVALID,
    LIMIT_REACHED,
    CAMERA_DENIED,
    INTERNAL,

    /** The client left before the exchange finished (network, or the user cancelled on the Mac). */
    DISCONNECTED,
}

sealed interface PairingOutcome {
    class Paired(
        val pairId: String,
        val peerName: String,
        /** PAIR-02 field 10: identical on both devices of the pair. */
        val safetyCode: String,
    ) : PairingOutcome

    class Failed(
        val failure: PairingFailure,
        val attemptsLeft: Int? = null,
    ) : PairingOutcome
}

/**
 * Android's side of one `/v1/pair` connection (PAIR-01 steps 9–11, A4–A5; API 2–6): `pair/hello` → checks →
 * `pair/offer` (MAC over `T_offer`, TLS pin inside) → `pair/confirm` (MAC, `prk_check`, client signature) → store the
 * pair → `pair/done`. Nothing is stored unless every check passes; `AUTH_FAILED` never says which check failed
 * (API 3 rule 3, API 6). A failed check aborts the exchange; logs nothing.
 */
class PairingExchange(
    private val local: LocalPairingDevice,
    private val window: PairingWindow,
    private val pairs: PairStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nonce: () -> ByteArray = { SecureRandomBytes.next(NONCE_SIZE) },
) {
    private val wire = PairingWire(clock)

    /** This connection took the window's claim (API 2 rule 4); only then may it give the claim back. */
    private var holdsClaim = false

    /** Ends the exchange: [refusal] goes to the client as `pair/error`; [outcome] is what the phone reports. */
    private class Abort(
        val outcome: PairingOutcome.Failed,
        val refusal: ErrorCode? = null,
    ) : Exception()

    suspend fun run(socket: TextMessageSocket): PairingOutcome {
        val outcome =
            try {
                exchange(socket)
            } catch (abort: Abort) {
                abort.refusal?.let { wire.refuse(socket, it) }
                abort.outcome
            }
        // The window goes on after a lost connection (QR and PIN) or a wrong PIN: the next connection may claim it.
        // Any other failure closes the window (the coordinator), so the claim is kept until then.
        if (holdsClaim && outcome is PairingOutcome.Failed && outcome.failure in WINDOW_GOES_ON) window.release()
        return outcome
    }

    private suspend fun exchange(socket: TextMessageSocket): PairingOutcome.Paired {
        val hello = receiveHello(socket)
        val handshake = sendOffer(socket, hello)
        return finish(socket, handshake, receiveConfirm(socket))
    }

    /** Reads `pair/hello` even when the window is over, so the client learns why (PAIRING_CLOSED, API 2 rule 1). */
    private suspend fun receiveHello(socket: TextMessageSocket): PairHelloData {
        val text = withTimeoutOrNull(HELLO_TIMEOUT_MILLIS) { socket.receiveText() }
        val hello = text?.let { wire.readOrNull(it, PairOp.HELLO, PairHelloData.serializer()) }
        if (hello == null) {
            if (text != null) socket.close(WsCloseCode.BAD_REQUEST, "BAD_REQUEST")
            throw disconnected()
        }
        val refusal = checkHello(hello) ?: claimOrClosed()
        refusal?.let { throw refused(it) }
        return hello
    }

    /** One client per window (API 2 rule 4): a second connection gets `PAIRING_CLOSED`. */
    private fun claimOrClosed(): ErrorCode? {
        holdsClaim = window.claim()
        return if (holdsClaim) null else ErrorCode.PAIRING_CLOSED
    }

    /** `pair/offer` with the MAC over `T_offer` under `K_pa` (API 3). */
    private suspend fun sendOffer(
        socket: TextMessageSocket,
        hello: PairHelloData,
    ): Handshake {
        val clientNonce = Base64Codecs.decodeB64u(hello.nonce, NONCE_SIZE)
        val serverNonce = nonce()
        val secret = secretFor(clientNonce, serverNonce)
        val transcript =
            PairingAuthDerivation.offerTranscript(
                clientParty(hello, clientNonce),
                local.party(serverNonce),
                local.tlsSha256,
            )
        val authKey = PairingAuthDerivation.authKey(secret, clientNonce, serverNonce)
        wire.send(socket, PairOp.OFFER, PairOfferData.serializer(), local.offer(serverNonce, authKey, transcript))
        return Handshake(hello, secret, authKey, transcript)
    }

    /** `pair/confirm`, or the client's `pair/error` (a wrong PIN, A5; a failed check, E4). */
    private suspend fun receiveConfirm(socket: TextMessageSocket): PairConfirmData {
        val reply = withTimeoutOrNull(REPLY_TIMEOUT_MILLIS) { socket.receiveText() }
        val confirm = reply?.let { wire.readOrNull(it, PairOp.CONFIRM, PairConfirmData.serializer()) }
        if (confirm != null) return confirm
        val clientRefusal = reply?.let { wire.readOrNull(it, PairOp.ERROR, PairErrorData.serializer()) }
        throw when {
            reply == null -> disconnected()
            clientRefusal != null -> Abort(clientError(clientRefusal))
            else -> refused(ErrorCode.AUTH_FAILED)
        }
    }

    /** API 2 rules 1–3: open window of the right mode, the scanned key, a self-certifying `device_id`. */
    private fun checkHello(hello: PairHelloData): ErrorCode? {
        val keysValid =
            runCatching {
                val sigKey = Base64Codecs.decodeB64u(hello.ikSigPub, KEY_SIZE)
                Base64Codecs.decodeB64u(hello.ikDhPub, KEY_SIZE)
                Base64Codecs.decodeB64u(hello.nonce, NONCE_SIZE)
                DeviceIdDerivation.matches(hello.deviceId, sigKey) && PeerPlatform.fromWire(hello.platform) != null &&
                    hello.name.codePointCount(0, hello.name.length) <= MAX_NAME
            }.getOrDefault(false)
        val scannedKey = (window as? PairingWindow.Qr)?.invite?.clientDhPublicKey
        val qrKeyMatches =
            scannedKey == null ||
                runCatching {
                    HmacSha256.constantTimeEquals(scannedKey, Base64Codecs.decodeB64u(hello.ikDhPub, KEY_SIZE))
                }.getOrDefault(false)
        val modeMatches =
            hello.mode == if (window is PairingWindow.Qr) PairHelloData.MODE_QR else PairHelloData.MODE_PIN
        return when {
            !window.isOpen(clock()) || !modeMatches -> ErrorCode.PAIRING_CLOSED
            !keysValid || !qrKeyMatches -> ErrorCode.AUTH_FAILED
            else -> null
        }
    }

    /** `pairing_secret` from the QR code, or `K_pin` from the PIN the user typed (A4), awaited within the window. */
    private suspend fun secretFor(
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ): ByteArray =
        when (window) {
            is PairingWindow.Qr -> {
                window.invite.pairingSecret
            }

            is PairingWindow.Pin -> {
                val pin =
                    withTimeoutOrNull(window.expiresAt - clock()) { window.awaitPin() }
                        ?: throw refused(ErrorCode.PAIRING_CLOSED)
                withContext(Dispatchers.Default) { PairingAuthDerivation.pinKey(pin, clientNonce, serverNonce) }
            }
        }

    private class Handshake(
        val hello: PairHelloData,
        val secret: ByteArray,
        val authKey: ByteArray,
        val transcript: ByteArray,
    )

    /** API 4: MAC first, then `prk_check`, then the client signature over the attestation; store; `pair/done`. */
    private suspend fun finish(
        socket: TextMessageSocket,
        handshake: Handshake,
        confirm: PairConfirmData,
    ): PairingOutcome.Paired {
        val hello = handshake.hello
        val checked = checkConfirm(handshake, confirm) ?: throw refused(ErrorCode.AUTH_FAILED)
        val attestation =
            PairingAuthDerivation.attestation(
                AttestationFields(
                    pairId = confirm.pairId,
                    androidDeviceId = local.identity.deviceId,
                    clientDeviceId = hello.deviceId,
                    androidSigningKey = local.identity.signingPublicKey,
                    clientSigningKey = checked.clientSigningKey,
                    createdAt = confirm.createdAt,
                ),
            )
        if (!Ed25519Keys.verify(checked.clientSigningKey, attestation, checked.clientSignature)) {
            throw refused(ErrorCode.AUTH_FAILED)
        }
        val signature = local.identity.sign(attestation)
        pairs.save(
            NewPair(
                pairId = confirm.pairId,
                peer =
                    PeerKeys(
                        hello.deviceId,
                        checked.clientSigningKey,
                        Base64Codecs.decodeB64u(hello.ikDhPub, KEY_SIZE),
                    ),
                peerName = hello.name,
                peerPlatform = checkNotNull(PeerPlatform.fromWire(hello.platform)),
                peerModel = hello.model,
                attestation = SignedAttestation(attestation, signature, checked.clientSignature, confirm.createdAt),
            ),
            checked.prk,
        )
        wire.send(
            socket,
            PairOp.DONE,
            PairDoneData.serializer(),
            done(handshake, confirm.pairId, signature, checked.prk),
        )
        // The client closes after `pair/done` (API 5); wait for it so `done` is flushed before the connection ends.
        withTimeoutOrNull(CLOSE_WAIT_MILLIS) { socket.receiveText() }
        return PairingOutcome.Paired(confirm.pairId, hello.name, PairStore.safetyCode(attestation))
    }

    private class CheckedConfirm(
        val prk: ByteArray,
        val clientSigningKey: ByteArray,
        val clientSignature: ByteArray,
    )

    /** `mac` (constant time) → `PRK` and `prk_check`; `null` means `AUTH_FAILED` without saying why. */
    private fun checkConfirm(
        handshake: Handshake,
        confirm: PairConfirmData,
    ): CheckedConfirm? =
        runCatching {
            require(UuidBytes.isCanonical(confirm.pairId))
            val signature = Base64Codecs.decodeB64u(confirm.sig, SIGNATURE_SIZE)
            val expectedMac =
                PairingAuthDerivation.confirmMac(
                    handshake.authKey,
                    handshake.transcript,
                    confirm.pairId,
                    confirm.createdAt,
                    signature,
                )
            require(HmacSha256.constantTimeEquals(expectedMac, Base64Codecs.decodeB64u(confirm.mac, MAC_SIZE)))
            val hello = handshake.hello
            val prk =
                PairingKeyDerivation.prkFromShared(
                    local.identity.dhSharedSecret(Base64Codecs.decodeB64u(hello.ikDhPub, KEY_SIZE)),
                    handshake.secret,
                    local.identity.deviceId,
                    hello.deviceId,
                )
            val expectedCheck = PairingAuthDerivation.prkCheck(prk, confirm.pairId, PeerRole.CLIENT)
            require(HmacSha256.constantTimeEquals(expectedCheck, Base64Codecs.decodeB64u(confirm.prkCheck, MAC_SIZE)))
            CheckedConfirm(prk, Base64Codecs.decodeB64u(hello.ikSigPub, KEY_SIZE), signature)
        }.getOrNull()

    private companion object {
        const val KEY_SIZE = 32
        const val NONCE_SIZE = 32
        const val MAC_SIZE = 32
        const val SIGNATURE_SIZE = 64
        const val MAX_NAME = 64

        /** The client sends `pair/hello` right after connecting (API 2). */
        const val HELLO_TIMEOUT_MILLIS = 10_000L

        /** The client answers the offer at once (its checks are fast); the user is not in this loop. */
        const val REPLY_TIMEOUT_MILLIS = 30_000L
        const val CLOSE_WAIT_MILLIS = 2_000L

        /** Failures after which the coordinator keeps the window open (`Waiting`, `EnterPin`). */
        val WINDOW_GOES_ON = setOf(PairingFailure.DISCONNECTED, PairingFailure.PIN_INVALID)

        fun disconnected() = Abort(PairingOutcome.Failed(PairingFailure.DISCONNECTED))

        fun refused(code: ErrorCode): Abort {
            val closed = code == ErrorCode.PAIRING_CLOSED
            return Abort(
                PairingOutcome.Failed(if (closed) PairingFailure.PAIRING_CLOSED else PairingFailure.AUTH_FAILED),
                code,
            )
        }

        fun clientParty(
            hello: PairHelloData,
            clientNonce: ByteArray,
        ) = PairingParty(
            hello.deviceId,
            clientNonce,
            Base64Codecs.decodeB64u(hello.ikSigPub, KEY_SIZE),
            Base64Codecs.decodeB64u(hello.ikDhPub, KEY_SIZE),
            hello.name,
        )

        /** `pair/done`: Android's signature, its `prk_check` and the MAC over both (API 5). */
        fun done(
            handshake: Handshake,
            pairId: String,
            signature: ByteArray,
            prk: ByteArray,
        ) = PairDoneData(
            sig = Base64Codecs.encodeB64u(signature),
            prkCheck = Base64Codecs.encodeB64u(PairingAuthDerivation.prkCheck(prk, pairId, PeerRole.SERVER)),
            mac = Base64Codecs.encodeB64u(PairingAuthDerivation.doneMac(handshake.authKey, pairId, signature)),
        )

        /** The client refused our offer: a wrong PIN (A5, with the attempts left) or a failed check (E4). */
        fun clientError(error: PairErrorData): PairingOutcome.Failed =
            when (ErrorCode.fromWire(error.code)) {
                ErrorCode.PIN_INVALID -> PairingOutcome.Failed(PairingFailure.PIN_INVALID, error.attemptsLeft)
                ErrorCode.PAIRING_CLOSED -> PairingOutcome.Failed(PairingFailure.PAIRING_CLOSED)
                ErrorCode.QR_INVALID -> PairingOutcome.Failed(PairingFailure.QR_INVALID)
                ErrorCode.AUTH_FAILED -> PairingOutcome.Failed(PairingFailure.AUTH_FAILED)
                else -> PairingOutcome.Failed(PairingFailure.INTERNAL)
            }
    }
}

/** `pair/offer` of this phone (API 3): its keys, name and TLS pin, with the MAC over `T_offer`. */
private fun LocalPairingDevice.offer(
    serverNonce: ByteArray,
    authKey: ByteArray,
    transcript: ByteArray,
) = PairOfferData(
    deviceId = identity.deviceId,
    nonce = Base64Codecs.encodeB64u(serverNonce),
    name = name,
    model = model,
    osVersion = osVersion,
    ikSigPub = Base64Codecs.encodeB64u(identity.signingPublicKey),
    ikDhPub = Base64Codecs.encodeB64u(identity.dhPublicKey),
    tlsSha256 = Base64Codecs.encodeB64u(tlsSha256),
    mac = Base64Codecs.encodeB64u(PairingAuthDerivation.offerMac(authKey, transcript)),
)

/** This phone as the S side of `T_offer`. */
private fun LocalPairingDevice.party(serverNonce: ByteArray) =
    PairingParty(identity.deviceId, serverNonce, identity.signingPublicKey, identity.dhPublicKey, name)
