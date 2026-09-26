package app.handlive.android.core.protocol.relay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `POST /v1/devices` (CONN-03 API 1): self-certified by `sig` over `"HLREG1"` ‖ … (0.6.4). */
@Serializable
data class DeviceRegistrationRequest(
    @SerialName("device_id") val deviceId: String,
    val platform: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("ik_sig_pub") val ikSigPub: String,
    val ts: Long,
    val sig: String,
)

@Serializable
data class DeviceRegistrationResponse(
    @SerialName("device_id") val deviceId: String,
    @SerialName("created_at") val createdAt: Long,
)

/** `POST /v1/auth/challenge` (CONN-03 API 2). */
@Serializable
data class ChallengeRequest(
    @SerialName("device_id") val deviceId: String,
)

@Serializable
data class ChallengeResponse(
    val challenge: String,
    @SerialName("expires_at") val expiresAt: Long,
)

/** `POST /v1/auth/token` (CONN-03 API 3): `sig` over `"HLAUTH1"` ‖ challenge ‖ `device_id`. */
@Serializable
data class TokenRequest(
    @SerialName("device_id") val deviceId: String,
    val challenge: String,
    val sig: String,
)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Int,
)

/** `PUT /v1/devices/me/push-token` (CONN-04 API 1): Android sends `fcm` without `topic`. */
@Serializable
data class PushTokenRequest(
    val provider: String,
    val token: String,
    val topic: String? = null,
)

/** `POST /v1/pairs` (PAIR-01 API 8): this phone is `device_a`, the client `device_b`; binary fields are b64u. */
@Serializable
data class PairRegistrationRequest(
    @SerialName("pair_id") val pairId: String,
    @SerialName("device_a") val deviceA: String,
    @SerialName("device_b") val deviceB: String,
    @SerialName("created_at") val createdAt: Long,
    val attestation: String,
    @SerialName("sig_a") val sigA: String,
    @SerialName("sig_b") val sigB: String,
)

/** `GET /v1/pairs` (PAIR-02 API 1). */
@Serializable
data class PairsListResponse(
    val pairs: List<RelayPairEntry>,
)

@Serializable
data class RelayPairEntry(
    @SerialName("pair_id") val pairId: String,
    @SerialName("peer_device_id") val peerDeviceId: String,
    @SerialName("peer_platform") val peerPlatform: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("revoked_at") val revokedAt: Long? = null,
    @SerialName("peer_online") val peerOnline: Boolean = false,
)

/** `POST /v1/pairs/{pair_id}/revoke` (PAIR-03 API 3): `reason` ∈ {user, reinstall, lost_device}. */
@Serializable
data class PairRevokeRequest(
    val reason: String,
)

/**
 * `POST /v1/push` (CONN-04 API 2). `kind = alert` (to iPhone/iPad) carries [envB64], the envelope sealed with
 * `K_push`; the field order is the one of `shared/test-vectors/push-envelope.json` (`push_request`).
 */
@Serializable
data class PushRequest(
    @SerialName("pair_id") val pairId: String,
    val to: String,
    val kind: String,
    val reason: String,
    @SerialName("env_b64") val envB64: String? = null,
    @SerialName("collapse_key") val collapseKey: String? = null,
    @SerialName("ttl_s") val ttlS: Int? = null,
)

/** The error body of every relay REST call (0.4.3, 0.8.2). */
@Serializable
data class RelayErrorResponse(
    val error: RelayErrorBody,
)

@Serializable
data class RelayErrorBody(
    val code: String,
    val message: String = "",
)

/** Values of the relay REST bodies (0.7.4, CONN-04). */
object RelayValues {
    const val PLATFORM_ANDROID = "android"
    const val PROVIDER_FCM = "fcm"
    const val KIND_WAKE = "wake"
    const val KIND_ALERT = "alert"
    const val REASON_SMS_NEW = "sms_new"
    const val REVOKE_USER = "user"
    const val REVOKE_REINSTALL = "reinstall"
    const val REVOKE_LOST_DEVICE = "lost_device"
}

/** Relay error codes of 0.8.2 (HTTP) and of the `error` op (CONN-03 API 5), as the Android client reacts to them. */
object RelayErrorCode {
    const val BAD_REQUEST = "BAD_REQUEST"
    const val CHALLENGE_EXPIRED = "CHALLENGE_EXPIRED"
    const val SIGNATURE_INVALID = "SIGNATURE_INVALID"
    const val TOKEN_EXPIRED = "TOKEN_EXPIRED"
    const val NOT_PAIRED = "NOT_PAIRED"
    const val DEVICE_NOT_FOUND = "DEVICE_NOT_FOUND"
    const val PAIR_EXISTS = "PAIR_EXISTS"
    const val PUSH_TOKEN_MISSING = "PUSH_TOKEN_MISSING"
    const val DEVICE_REVOKED = "DEVICE_REVOKED"
    const val PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val INTERNAL = "INTERNAL"
    const val PUSH_PROVIDER_ERROR = "PUSH_PROVIDER_ERROR"
    const val NOT_CONNECTED = "NOT_CONNECTED"
}
