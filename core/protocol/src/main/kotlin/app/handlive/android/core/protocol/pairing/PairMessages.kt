package app.handlive.android.core.protocol.pairing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `op` names of `type = pair` (0.7.1). */
object PairOp {
    const val HELLO = "hello"
    const val OFFER = "offer"
    const val CONFIRM = "confirm"
    const val DONE = "done"
    const val ERROR = "error"
    const val REVOKE = "revoke"
}

/** `pair/hello` C→S (PAIR-01 API 2); unencrypted payload. `mode` ∈ {qr, pin}; keys and nonce are b64u 32 bytes. */
@Serializable
data class PairHelloData(
    val mode: String,
    @SerialName("device_id") val deviceId: String,
    val nonce: String,
    val name: String,
    val platform: String,
    val model: String? = null,
    @SerialName("ik_sig_pub") val ikSigPub: String,
    @SerialName("ik_dh_pub") val ikDhPub: String,
) {
    companion object {
        const val MODE_QR = "qr"
        const val MODE_PIN = "pin"
    }
}

/** `pair/offer` S→C (PAIR-01 API 3); unencrypted payload, integrity by `mac` = HMAC(`K_pa`, `T_offer`). */
@Serializable
data class PairOfferData(
    @SerialName("device_id") val deviceId: String,
    val nonce: String,
    val name: String,
    val model: String,
    @SerialName("os_version") val osVersion: String,
    @SerialName("ik_sig_pub") val ikSigPub: String,
    @SerialName("ik_dh_pub") val ikDhPub: String,
    @SerialName("tls_sha256") val tlsSha256: String,
    val mac: String,
)

/** `pair/confirm` C→S (PAIR-01 API 4): `sig` b64u 64 bytes, `prk_check` and `mac` b64u 32 bytes. */
@Serializable
data class PairConfirmData(
    @SerialName("pair_id") val pairId: String,
    @SerialName("created_at") val createdAt: Long,
    val sig: String,
    @SerialName("prk_check") val prkCheck: String,
    val mac: String,
)

/** `pair/done` S→C (PAIR-01 API 5). */
@Serializable
data class PairDoneData(
    val sig: String,
    @SerialName("prk_check") val prkCheck: String,
    val mac: String,
)

/**
 * `pair/error` both ways (PAIR-01 API 6): `code` ∈ {QR_INVALID, PAIRING_CLOSED, PIN_INVALID, AUTH_FAILED, INTERNAL};
 * `message` is an English diagnostic (0.12.4); `attempts_left` only with `PIN_INVALID`.
 */
@Serializable
data class PairErrorData(
    val code: String,
    val message: String,
    @SerialName("attempts_left") val attemptsLeft: Int? = null,
)

/** `pair/revoke` both ways on `/v1/ctl` (PAIR-03 API 1); `reason` ∈ {user, reinstall, limit}. Encrypted, with ack. */
@Serializable
data class PairRevokeData(
    @SerialName("pair_id") val pairId: String,
    val reason: String,
) {
    companion object {
        const val REASON_USER = "user"
    }
}
