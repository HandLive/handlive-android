package app.handlive.android.feature.relay

import app.handlive.android.core.data.pairing.PairStore
import app.handlive.android.core.data.pairing.RelayPairs
import app.handlive.android.core.protocol.encoding.Base64Codecs
import app.handlive.android.core.protocol.relay.PairRegistrationRequest
import app.handlive.android.core.protocol.relay.RelayErrorCode
import app.handlive.android.core.protocol.relay.RelayValues
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
 * revocation, and the push token. A pair refused with 404 (the peer is not registered yet) waits 24 h. Errors bubble
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
                response.status in CLIENT_ERRORS -> {
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
                val response = api.deleteThisDevice(revokePairs)
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

    /** PAIR-03 step 8–9: `true` when the relay no longer knows the pair, and the tombstone is gone. */
    suspend fun revoke(
        pairId: String,
        reason: String,
    ): Boolean {
        val response = api.revokePair(pairId, reason)
        val done =
            response.ok || response.errorCode == RelayErrorCode.DEVICE_NOT_FOUND ||
                response.errorCode == RelayErrorCode.NOT_PAIRED
        if (done) relayPairs.deleteTombstone(pairId)
        return done
    }

    /**
     * PAIR-02 step 4–5 (at most once every 60 s unless [force]): a pair revoked on the relay is cleaned up (E3); a pair
     * the relay forgot while it was registered means the peer removed itself (rule 3) → `relay_registered = 0`.
     */
    suspend fun checkPairs(force: Boolean = false) {
        if (!force && clock() - lastPairsCheck < RelayConstants.PAIRS_CHECK_MIN_INTERVAL_MILLIS) return
        lastPairsCheck = clock()
        val remote = api.pairs().pairs.associateBy { it.pairId }
        for (local in pairs.observeActive().first()) {
            val entry = remote[local.pairId]
            when {
                entry?.revokedAt != null -> verdict.revokedElsewhere(local.pairId)
                entry == null && local.relayRegistered -> relayPairs.markRegistered(local.pairId, registered = false)
            }
        }
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
        val CLIENT_ERRORS = 400..499

        /** The relay no longer has the device: deleted earlier (E6) or refused for good. */
        val GONE = setOf(RelayErrorCode.DEVICE_NOT_FOUND, RelayErrorCode.DEVICE_REVOKED)
    }
}
