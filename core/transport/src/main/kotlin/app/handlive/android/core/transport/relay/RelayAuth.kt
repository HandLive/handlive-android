package app.handlive.android.core.transport.relay

import app.handlive.android.core.crypto.derivation.RelaySignatureMessages
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.encoding.Base64Codecs
import app.handlive.android.core.protocol.relay.ChallengeRequest
import app.handlive.android.core.protocol.relay.ChallengeResponse
import app.handlive.android.core.protocol.relay.DeviceRegistrationRequest
import app.handlive.android.core.protocol.relay.RelayErrorCode
import app.handlive.android.core.protocol.relay.RelayValues
import app.handlive.android.core.protocol.relay.TokenRequest
import app.handlive.android.core.protocol.relay.TokenResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer

/** What the relay knows this phone by (0.6.4): its `device_id`, `ik_sig_pub`, and the Ed25519 signer of `ik_sig`. */
class RelayIdentity(
    val deviceId: String,
    val signingPublicKey: ByteArray,
    val appVersion: String,
    val sign: (ByteArray) -> ByteArray,
)

/**
 * Registration and authentication with the relay (CONN-03 API 1–3, 0.6.4): `POST /v1/devices` signed with
 * `"HLREG1"`, then challenge → `"HLAUTH1"` signature → a 15-minute JWT, reused while more than 60 s remain. An unknown
 * device (404 `DEVICE_NOT_FOUND`, 401 `SIGNATURE_INVALID`) registers again and retries once (E2); 410
 * `DEVICE_REVOKED` is final (E3). Thread-safe.
 */
class RelayAuth(
    private val http: RelayHttp,
    private val identity: RelayIdentity,
    private val clock: () -> Long,
) {
    private val lock = Mutex()
    private var token: String? = null
    private var expiresAt = 0L

    /** This phone's `device_id`, the `device_a` of the pairs it registers (PAIR-01 API 8). */
    val deviceId: String get() = identity.deviceId

    /** The device was registered by this process (CONN-03 step 3: once, then only after E2). */
    @Volatile
    var registered = false
        private set

    /** `POST /v1/devices` (upsert, idempotent). */
    suspend fun register() {
        val ts = clock()
        val message =
            RelaySignatureMessages.registration(
                identity.deviceId,
                identity.signingPublicKey,
                RelayValues.PLATFORM_ANDROID,
                ts,
            )
        val request =
            DeviceRegistrationRequest(
                deviceId = identity.deviceId,
                platform = RelayValues.PLATFORM_ANDROID,
                appVersion = identity.appVersion,
                ikSigPub = Base64Codecs.encodeB64u(identity.signingPublicKey),
                ts = ts,
                sig = Base64Codecs.encodeB64u(identity.sign(message)),
            )
        val response = http.send(POST, DEVICES, json(DeviceRegistrationRequest.serializer(), request), null)
        if (!response.ok) throw response.failure()
        registered = true
    }

    /**
     * A JWT for `Authorization: Bearer` (0.6.4 step 3). With [registerIfUnknown] false, an unknown device is reported
     * as 404 `DEVICE_NOT_FOUND` instead of being registered (SET-02 E6: the device already removed itself).
     */
    suspend fun token(registerIfUnknown: Boolean = true): String =
        lock.withLock {
            val current = token?.takeIf { expiresAt - clock() > REUSE_MARGIN_MILLIS }
            current ?: run {
                if (registerIfUnknown && !registered) register()
                fetch(registerIfUnknown)
            }
        }

    /** The relay said the token expired (401 `TOKEN_EXPIRED`): the next [token] asks for a new one. */
    fun forget() {
        token = null
    }

    private suspend fun fetch(registerIfUnknown: Boolean): String {
        val challengeResponse =
            http.send(POST, CHALLENGE, json(ChallengeRequest.serializer(), ChallengeRequest(identity.deviceId)), null)
        val tokenResponse = challengeResponse.takeIf { it.ok }?.let { signedToken(it) }
        return if (tokenResponse?.ok == true) {
            val issued = decode(TokenResponse.serializer(), tokenResponse.body)
            token = issued.accessToken
            expiresAt = clock() + issued.expiresIn * MILLIS_PER_SECOND
            issued.accessToken
        } else {
            retryAfterRegistering(tokenResponse ?: challengeResponse, registerIfUnknown)
        }
    }

    /** `POST /v1/auth/token` with the `"HLAUTH1"` signature over the challenge (0.6.4 step 2). */
    private suspend fun signedToken(challengeResponse: RelayResponse): RelayResponse {
        val challenge = decode(ChallengeResponse.serializer(), challengeResponse.body).challenge
        val signature =
            identity.sign(
                RelaySignatureMessages.token(Base64Codecs.decodeB64u(challenge, CHALLENGE_SIZE), identity.deviceId),
            )
        val request = TokenRequest(identity.deviceId, challenge, Base64Codecs.encodeB64u(signature))
        return http.send(POST, TOKEN, json(TokenRequest.serializer(), request), null)
    }

    /** CONN-03 E2: the relay does not know this device (any more): register, then one more try. */
    private suspend fun retryAfterRegistering(
        response: RelayResponse,
        registerIfUnknown: Boolean,
    ): String {
        val unknown = response.errorCode in UNKNOWN_DEVICE_CODES
        if (!unknown || !registerIfUnknown) throw response.failure()
        register()
        return fetch(registerIfUnknown = false)
    }

    private companion object {
        const val POST = "POST"
        const val DEVICES = "/v1/devices"
        const val CHALLENGE = "/v1/auth/challenge"
        const val TOKEN = "/v1/auth/token"
        const val CHALLENGE_SIZE = 32
        const val MILLIS_PER_SECOND = 1_000L

        /** A token with more than 60 s left is reused (CONN-03 step 4). */
        const val REUSE_MARGIN_MILLIS = 60_000L
        val UNKNOWN_DEVICE_CODES = setOf(RelayErrorCode.DEVICE_NOT_FOUND, RelayErrorCode.SIGNATURE_INVALID)
    }
}

internal fun <T> json(
    serializer: KSerializer<T>,
    value: T,
): String = ProtocolJson.encodeToString(serializer, value)

internal fun <T> decode(
    serializer: KSerializer<T>,
    body: String,
): T = ProtocolJson.decodeFromString(serializer, body)

internal fun RelayResponse.failure() = RelayRequestException(status, errorCode, retryAfterMillis)
