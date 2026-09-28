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
 * call retried once (0.6.4 step 3); a token the relay no longer accepts (401 `SIGNATURE_INVALID`, such as one signed
 * before the relay's key changed) and an unknown device (404 `DEVICE_NOT_FOUND`) register the device again first
 * (CONN-03 E2). Where 404 already means "done" — `DELETE /v1/devices/me` (SET-02 E6) and a pair's revocation (PAIR-03
 * API 3) — nothing registers again: a refused token is only renewed. Callers read the status and `error.code` of the
 * returned [RelayResponse].
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

    /**
     * PAIR-03 API 3: 204, or 404 `DEVICE_NOT_FOUND` when the relay does not know the pair — both mean revoked. That
     * 404 names the unknown pair, so the device is not registered again for it.
     */
    suspend fun revokePair(
        pairId: String,
        reason: String,
    ): RelayResponse =
        authorized(
            "POST",
            "/v1/pairs/$pairId/revoke",
            json(PairRevokeRequest.serializer(), PairRevokeRequest(reason)),
            registerIfUnknown = false,
        )

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
        val refusedToken = first.status == HTTP_UNAUTHORIZED && first.errorCode in REFUSED_TOKEN
        if (!refusedToken && !(registerIfUnknown && first.isUnknownDevice())) return first
        // CONN-03 E2 registers again, except for a token that only expired (0.6.4 step 3).
        if (registerIfUnknown && first.errorCode != RelayErrorCode.TOKEN_EXPIRED) auth.register()
        auth.forget()
        return http.send(method, path, body, auth.token(registerIfUnknown))
    }

    private fun RelayResponse.isUnknownDevice() =
        status == HTTP_NOT_FOUND && errorCode == RelayErrorCode.DEVICE_NOT_FOUND

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404

        /** A 401 that a new token may cure: expired, or signed with a key the relay no longer uses. */
        val REFUSED_TOKEN = setOf(RelayErrorCode.TOKEN_EXPIRED, RelayErrorCode.SIGNATURE_INVALID)
    }
}
