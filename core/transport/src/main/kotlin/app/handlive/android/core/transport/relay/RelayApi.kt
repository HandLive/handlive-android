package app.handlive.android.core.transport.relay

import app.handlive.android.core.protocol.relay.PairRegistrationRequest
import app.handlive.android.core.protocol.relay.PairRevokeRequest
import app.handlive.android.core.protocol.relay.PairsListResponse
import app.handlive.android.core.protocol.relay.PushRequest
import app.handlive.android.core.protocol.relay.PushTokenRequest
import app.handlive.android.core.protocol.relay.RelayErrorCode
import app.handlive.android.core.protocol.relay.RelayValues

/**
 * The relay endpoints that need the device JWT (0.7.4). An expired token (401 `TOKEN_EXPIRED`) is renewed and the
 * call retried once (0.6.4 step 3); an unknown device (404 `DEVICE_NOT_FOUND`) registers again and retries once
 * (CONN-03 E2) — except for `DELETE /v1/devices/me`, where it already means "done" (SET-02 E6). Callers read the
 * status and `error.code` of the returned [RelayResponse].
 */
class RelayApi(
    private val http: RelayHttp,
    private val auth: RelayAuth,
) {
    /** PAIR-01 API 8: idempotent; 200 or 201 → `relay_registered = 1`. */
    suspend fun registerPair(request: PairRegistrationRequest): RelayResponse =
        authorized("POST", "/v1/pairs", json(PairRegistrationRequest.serializer(), request))

    /** PAIR-02 API 1. */
    suspend fun pairs(): PairsListResponse {
        val response = authorized("GET", "/v1/pairs", null)
        if (!response.ok) throw response.failure()
        return decode(PairsListResponse.serializer(), response.body)
    }

    /** PAIR-03 API 3: 204, or 404 when the pair never reached the relay — both mean revoked. */
    suspend fun revokePair(
        pairId: String,
        reason: String,
    ): RelayResponse =
        authorized("POST", "/v1/pairs/$pairId/revoke", json(PairRevokeRequest.serializer(), PairRevokeRequest(reason)))

    /** CONN-04 API 1: the FCM registration token of this phone. */
    suspend fun putPushToken(token: String): RelayResponse =
        authorized(
            "PUT",
            "/v1/devices/me/push-token",
            json(PushTokenRequest.serializer(), PushTokenRequest(RelayValues.PROVIDER_FCM, token)),
        )

    /** CONN-04 API 2. */
    suspend fun push(request: PushRequest): RelayResponse =
        authorized("POST", "/v1/push", json(PushRequest.serializer(), request))

    /** SET-02 API 2: `revoke_pairs=false` (Remove Device from Server) or `true` (Delete All HandLive Data). */
    suspend fun deleteThisDevice(revokePairs: Boolean): RelayResponse =
        authorized("DELETE", "/v1/devices/me?revoke_pairs=$revokePairs", null, registerIfUnknown = false)

    private suspend fun authorized(
        method: String,
        path: String,
        body: String?,
        registerIfUnknown: Boolean = true,
    ): RelayResponse {
        val first = http.send(method, path, body, auth.token(registerIfUnknown))
        return when {
            first.status == HTTP_UNAUTHORIZED && first.errorCode == RelayErrorCode.TOKEN_EXPIRED -> {
                auth.forget()
                http.send(method, path, body, auth.token(registerIfUnknown))
            }

            registerIfUnknown && first.isUnknownDevice() -> {
                auth.register()
                auth.forget()
                http.send(method, path, body, auth.token())
            }

            else -> {
                first
            }
        }
    }

    private fun RelayResponse.isUnknownDevice() =
        status == HTTP_NOT_FOUND && errorCode == RelayErrorCode.DEVICE_NOT_FOUND

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
    }
}
