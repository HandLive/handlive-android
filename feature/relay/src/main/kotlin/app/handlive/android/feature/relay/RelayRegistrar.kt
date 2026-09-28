package app.handlive.android.feature.relay

import app.handlive.android.core.crypto.derivation.RevocationStatement
import app.handlive.android.core.data.pairing.PairStore
import app.handlive.android.core.data.pairing.RelayPairs
import app.handlive.android.core.protocol.encoding.Base64Codecs
import app.handlive.android.core.protocol.relay.DevicesDeleteRequest
import app.handlive.android.core.protocol.relay.PairRegistrationRequest
import app.handlive.android.core.protocol.relay.PairRevokeRequest
import app.handlive.android.core.protocol.relay.RelayErrorCode
import app.handlive.android.core.protocol.relay.RelayPairRevoked
import app.handlive.android.core.protocol.relay.RelayValues
import app.handlive.android.core.protocol.relay.Revocation
import app.handlive.android.core.transport.relay.RelayApi
import app.handlive.android.core.transport.relay.RelayAuth
import app.handlive.android.core.transport.relay.RelayRequestException
import app.handlive.android.core.transport.relay.RelayUnreachableException
import kotlinx.coroutines.flow.first

/** What the relay's list of pairs says about a pair of this phone (PAIR-02 API 1). */
fun interface PairsVerdict {
    /** The pair is revoked on the relay (PAIR-02 E3): clean it up and say "<name> was unpaired from another device". */
    suspend fun revokedElsewhere(pairId: String)
}

/**
 * What the relay must know about this phone (CONN-03 step 3, PAIR-01 API 8, PAIR-02 API 1, PAIR-03 E3, CONN-04
 * API 1): the device (once per process), every pair still at `relay_registered = 0`, the tombstones waiting for their
 * revocation, and the push token. A pair refused with 404 (the peer is not registered yet) or another 4xx waits 24 h —
 * but not after a 401, a token the relay refused even once renewed, which the next occasion tries again. Errors bubble
 * up to the caller, which tries again at the next occasion (network, service start, relay switched on).
 */
class RelayRegistrar(
    private val auth: RelayAuth,
    private val api: RelayApi,
    private val pairs: PairStore,
    private val relayPairs: RelayPairs,
    private val clock: () -> Long,
    private val verdict: PairsVerdict,
) {
    private val retryPairAfter = HashMap<String, Long>()
    private var lastPairsCheck = 0L
    private var pushToken: Pair<String, Long>? = null

    /** The device, then its unregistered pairs (SET-02 API 6 when `relay.enabled` turns on). */
    suspend fun registerAll() {
        if (!auth.registered) auth.register()
        for (pair in relayPairs.unregistered()) {
            if ((retryPairAfter[pair.pairId] ?: 0L) > clock()) continue
            val request =
                PairRegistrationRequest(
                    pairId = pair.pairId,
                    deviceA = auth.deviceId,
                    deviceB = pair.peerDeviceId,
                    createdAt = pair.createdAt,
                    attestation = Base64Codecs.encodeB64u(pair.attestation),
                    sigA = Base64Codecs.encodeB64u(pair.sigSelf),
                    sigB = Base64Codecs.encodeB64u(pair.sigPeer),
                )
            val response = api.registerPair(request)
            when {
                // 201 created, 200 already there with the same data (API 8 rule 4) → rule 5.
                response.ok -> {
                    relayPairs.markRegistered(pair.pairId, registered = true)
                }

                // PAIR-02 API 1 rule 3: the peer has not registered (again) yet; other refusals won't change soon.
                // A 401 is about the token, which the next occasion renews, not about the pair.
                response.status in CLIENT_ERRORS && response.status != HTTP_UNAUTHORIZED -> {
                    retryPairAfter[pair.pairId] =
                        clock() + RelayConstants.PAIR_RETRY_AFTER_404_MILLIS
                }
            }
        }
    }

    /** Both at every occasion (service start, network, new link); each runs even when the other failed. */
    suspend fun registerEverything() {
        attempt { registerAll() }
        attempt { revokeTombstones() }
    }

    /**
     * SET-02 A2: `DELETE /v1/devices/me` (the JWT's own device). Done also when the relay no longer has the device
     * (404 `DEVICE_NOT_FOUND` while authenticating, E6, or 410); afterwards any use registers it again.
     */
    suspend fun deleteDevice(revokePairs: Boolean): ServerDeletion {
        val outcome =
            try {
                val body = if (revokePairs) DevicesDeleteRequest(revocationsForDelete()) else null
                val response = api.deleteThisDevice(revokePairs, body)
                if (response.ok || response.errorCode in GONE) ServerDeletion.DONE else ServerDeletion.UNREACHABLE
            } catch (e: RelayRequestException) {
                if (e.code in GONE) ServerDeletion.DONE else ServerDeletion.UNREACHABLE
            } catch (_: RelayUnreachableException) {
                ServerDeletion.UNREACHABLE
            }
        if (outcome == ServerDeletion.DONE) {
            auth.reset()
            pushToken = null
        }
        return outcome
    }

    /** PAIR-03 step 8 again for every tombstone (E3); 204 or 404 (never registered) both mean done. */
    suspend fun revokeTombstones() {
        relayPairs.tombstonesToRevoke().forEach { pairId -> revoke(pairId, RelayValues.REVOKE_USER) }
    }

    /**
     * PAIR-03 step 8–9: `true` when the relay no longer knows the pair, and the tombstone is gone. Each attempt signs a
     * new `HLREVOKE1` statement with the current time, so a retry after E3 is never outside the relay's ±10 minutes. A relay that does
     * not know this device either (404 while authenticating) holds none of its pairs: done as well, and the device is
     * not registered again for it.
     */
    suspend fun revoke(
        pairId: String,
        reason: String,
    ): Boolean {
        val done =
            try {
                val revokedAt = clock()
                val request = PairRevokeRequest(revokedAt, statement(pairId, revokedAt), reason)
                val response = api.revokePair(pairId, request)
                response.ok || response.errorCode == RelayErrorCode.DEVICE_NOT_FOUND ||
                    response.errorCode == RelayErrorCode.NOT_PAIRED
            } catch (e: RelayRequestException) {
                if (e.code != RelayErrorCode.DEVICE_NOT_FOUND) throw e
                true
            }
        if (done) relayPairs.deleteTombstone(pairId)
        return done
    }

    /**
     * PAIR-02 step 4–5 and API 1 logic 3 (at most once every 60 s unless [force]): a pair revoked on the relay is
     * cleaned up (E3); a pair the relay forgot while it was registered means the peer removed itself →
     * `relay_registered = 0`; a pair the relay lists without `revoked_at` while it is still at 0 was completed by the
     * peer → `relay_registered = 1`, which also ends its 24 h wait.
     */
    suspend fun checkPairs(force: Boolean = false) {
        if (!force && clock() - lastPairsCheck < RelayConstants.PAIRS_CHECK_MIN_INTERVAL_MILLIS) return
        lastPairsCheck = clock()
        val remote = api.pairs().pairs.associateBy { it.pairId }
        for (local in pairs.observeActive().first()) {
            val entry = remote[local.pairId]
            when {
                // Logic 4: only a statement the peer signed counts; any other revoked row is ignored.
                entry?.revokedAt != null -> {
                    if (isSignedByPeer(local.pairId, entry.revokedBy, entry.revokedAt, entry.revokeSig)) {
                        verdict.revokedElsewhere(local.pairId)
                    }
                }

                entry == null && local.relayRegistered -> {
                    relayPairs.markRegistered(local.pairId, registered = false)
                }

                entry != null && !local.relayRegistered -> {
                    relayPairs.markRegistered(local.pairId, registered = true)
                    retryPairAfter.remove(local.pairId)
                }
            }
        }
    }

    /**
     * PAIR-03 API 4 logic 1: a `pair_revoked` counts only when `by` is the pair's peer and the statement verifies with
     * the peer's stored `ik_sig` public key; the relay alone can never unpair the phone.
     */
    suspend fun isSignedByPeer(frame: RelayPairRevoked): Boolean =
        isSignedByPeer(frame.pairId, frame.by, frame.revokedAt, frame.sig)

    private suspend fun isSignedByPeer(
        pairId: String,
        by: String?,
        revokedAt: Long?,
        sig: String?,
    ): Boolean {
        val peer = relayPairs.peerOf(pairId) ?: return false
        return RevocationStatement.isFromPeer(pairId, by, revokedAt, sig, peer.deviceId, peer.ikSigPub)
    }

    /** `HLREVOKE1` of this phone for [pairId] at [revokedAt] (0.6.2), b64u. */
    private fun statement(
        pairId: String,
        revokedAt: Long,
    ): String = Base64Codecs.encodeB64u(auth.sign(RevocationStatement.message(pairId, auth.deviceId, revokedAt)))

    /**
     * SET-02 A4: one fresh statement for every pair the relay may still hold unrevoked — the local pairs, tombstones
     * included, and every unrevoked pair `GET /v1/pairs` lists. A list the relay cannot give leaves the local pairs;
     * a relay that no longer knows the device ends the deletion as done (via [RelayRequestException]).
     */
    private suspend fun revocationsForDelete(): List<Revocation> {
        val remote =
            try {
                api
                    .pairs(registerIfUnknown = false)
                    .pairs
                    .filter { it.revokedAt == null }
                    .map { it.pairId }
            } catch (e: RelayRequestException) {
                if (e.code in GONE) throw e
                emptyList()
            }
        val now = clock()
        return (relayPairs.pairsToRevokeOnDelete() + remote).distinct().map { Revocation(it, now, statement(it, now)) }
    }

    /** CONN-04 step 2: a new token, or the same one every 7 days. */
    suspend fun registerPushToken(token: String) {
        val known = pushToken
        if (known != null && known.first == token &&
            clock() - known.second < RelayConstants.PUSH_TOKEN_REFRESH_MILLIS
        ) {
            return
        }
        if (api.putPushToken(token).ok) pushToken = token to clock()
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        val CLIENT_ERRORS = 400..499

        /** The relay no longer has the device: deleted earlier (E6) or refused for good. */
        val GONE = setOf(RelayErrorCode.DEVICE_NOT_FOUND, RelayErrorCode.DEVICE_REVOKED)
    }
}
