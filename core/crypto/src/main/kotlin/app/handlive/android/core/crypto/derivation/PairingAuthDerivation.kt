package app.handlive.android.core.crypto.derivation

import app.handlive.android.core.crypto.primitives.Argon2id
import app.handlive.android.core.crypto.primitives.HkdfSha256
import app.handlive.android.core.crypto.primitives.HmacSha256
import app.handlive.android.core.protocol.id.UuidBytes
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** The fields of the pairing attestation (0.6.2), in its byte order. */
class AttestationFields(
    val pairId: String,
    val androidDeviceId: String,
    val clientDeviceId: String,
    val androidSigningKey: ByteArray,
    val clientSigningKey: ByteArray,
    val createdAt: Long,
)

/** One side of the pairing exchange as `T_offer` binds it (PAIR-01 API 2–3). */
class PairingParty(
    val deviceId: String,
    val nonce: ByteArray,
    val signingPublicKey: ByteArray,
    val dhPublicKey: ByteArray,
    val name: String,
)

/**
 * Byte strings and keys of the pairing exchange (PAIR-01 "Chuỗi xác thực", API 3–5; 0.6.2). No JSON is ever
 * signed or MACed; every field is raw bytes: uuids as 16 bytes, timestamps as int64 BE, `str(x)` = uint16 BE length ‖
 * UTF-8. The labels are ASCII without spaces — `"HL1|confirm|"`: the spaces around `|` in the spec's tables come from
 * Markdown escaping, as for the envelope AAD of 0.5.1, which the shared vectors fix without spaces.
 */
object PairingAuthDerivation {
    const val AUTH_INFO = "handlive/v1/pair-auth"
    const val SECRET_SIZE = 32
    private val OFFER_LABEL = "HL1|offer|".ascii()
    private val CONFIRM_LABEL = "HL1|confirm|".ascii()
    private val DONE_LABEL = "HL1|done|".ascii()
    private val PRK_CHECK_CLIENT = "HL1|prk-check-c|".ascii()
    private val PRK_CHECK_SERVER = "HL1|prk-check-s|".ascii()
    private val ATTESTATION_LABEL = "HLPAIR1".ascii()

    /** `K_pin` = Argon2id(PIN, salt = `nonce_c` ‖ `nonce_s`, t = 3, m = 64 MiB, p = 4, L = 32) (0.6.2). */
    val PIN_PARAMETERS = Argon2id.Parameters(passes = 3, memoryKiB = 64 * 1024, parallelism = 4, tagLength = 32)

    /**
     * `K_pa` = HKDF-SHA256(ikm = `pairing_secret` or `K_pin`, salt = `nonce_c` ‖ `nonce_s`,
     * info "handlive/v1/pair-auth").
     */
    fun authKey(
        secret: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ): ByteArray = HkdfSha256.derive(ikm = secret, salt = clientNonce + serverNonce, info = AUTH_INFO)

    fun pinKey(
        pin: String,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ): ByteArray = Argon2id.hash(pin.toByteArray(Charsets.UTF_8), clientNonce + serverNonce, PIN_PARAMETERS)

    /**
     * `T_offer` = "HL1|offer|" ‖ `device_id` C ‖ `nonce_c` ‖ `ik_sig_pub` C ‖ `ik_dh_pub` C ‖ str(`name` C) ‖
     * `device_id` S ‖ `nonce_s` ‖ `ik_sig_pub` S ‖ `ik_dh_pub` S ‖ `tls_sha256` ‖ str(`name` S).
     */
    fun offerTranscript(
        client: PairingParty,
        server: PairingParty,
        tlsSha256: ByteArray,
    ): ByteArray =
        ByteArrayOutputStream()
            .apply {
                write(OFFER_LABEL)
                writeParty(client)
                write(UuidBytes.toBytes(server.deviceId))
                write(server.nonce)
                write(server.signingPublicKey)
                write(server.dhPublicKey)
                write(tlsSha256)
                writeStr(server.name)
            }.toByteArray()

    fun offerMac(
        authKey: ByteArray,
        offerTranscript: ByteArray,
    ): ByteArray = HmacSha256.mac(authKey, offerTranscript)

    /** `mac` of `pair/confirm`: HMAC(`K_pa`, "HL1|confirm|" ‖ `T_offer` ‖ `pair_id` ‖ `created_at` ‖ `sig`). */
    fun confirmMac(
        authKey: ByteArray,
        offerTranscript: ByteArray,
        pairId: String,
        createdAt: Long,
        clientSignature: ByteArray,
    ): ByteArray =
        HmacSha256.mac(
            authKey,
            CONFIRM_LABEL + offerTranscript + UuidBytes.toBytes(pairId) + int64(createdAt) + clientSignature,
        )

    /** `mac` of `pair/done`: HMAC(`K_pa`, "HL1|done|" ‖ `pair_id` ‖ `sig`). */
    fun doneMac(
        authKey: ByteArray,
        pairId: String,
        serverSignature: ByteArray,
    ): ByteArray = HmacSha256.mac(authKey, DONE_LABEL + UuidBytes.toBytes(pairId) + serverSignature)

    /** `prk_check` of the client (`pair/confirm`) or of Android (`pair/done`): HMAC(`PRK`, label ‖ `pair_id`). */
    fun prkCheck(
        prk: ByteArray,
        pairId: String,
        role: PeerRole,
    ): ByteArray {
        val label = if (role == PeerRole.CLIENT) PRK_CHECK_CLIENT else PRK_CHECK_SERVER
        return HmacSha256.mac(prk, label + UuidBytes.toBytes(pairId))
    }

    /**
     * Pairing attestation (0.6.2): "HLPAIR1" ‖ `pair_id` ‖ `device_id` Android ‖ `device_id` client ‖ `ik_sig_pub`
     * Android ‖ `ik_sig_pub` client ‖ `created_at`. Both sides sign it with Ed25519; the relay checks both.
     */
    fun attestation(fields: AttestationFields): ByteArray =
        ATTESTATION_LABEL + UuidBytes.toBytes(fields.pairId) + UuidBytes.toBytes(fields.androidDeviceId) +
            UuidBytes.toBytes(fields.clientDeviceId) + fields.androidSigningKey + fields.clientSigningKey +
            int64(fields.createdAt)
}

private fun ByteArrayOutputStream.writeParty(party: PairingParty) {
    write(UuidBytes.toBytes(party.deviceId))
    write(party.nonce)
    write(party.signingPublicKey)
    write(party.dhPublicKey)
    writeStr(party.name)
}

private fun ByteArrayOutputStream.writeStr(text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    require(bytes.size <= MAX_STR_BYTES) { "name too long" }
    write(bytes.size ushr Byte.SIZE_BITS)
    write(bytes.size and BYTE_MASK)
    write(bytes)
}

private fun int64(value: Long): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array()

private fun String.ascii() = toByteArray(Charsets.US_ASCII)

private const val MAX_STR_BYTES = 0xffff
private const val BYTE_MASK = 0xff
