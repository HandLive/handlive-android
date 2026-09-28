package app.handlive.android.feature.relay

import app.handlive.android.core.crypto.derivation.RevocationStatement
import app.handlive.android.core.data.pairing.RelayPairs
import app.handlive.android.core.protocol.encoding.Base64Codecs
import app.handlive.android.core.protocol.relay.RelayPairRevoked
import app.handlive.android.core.protocol.relay.Revocation
import app.handlive.android.core.transport.relay.RelayApi
import app.handlive.android.core.transport.relay.RelayAuth
import app.handlive.android.core.transport.relay.RelayRequestException

/**
 * Signed revocation on the phone (0.6.2 `HLREVOKE1`, PAIR-03, SET-02): the statements this phone signs, and the check
 * that a revocation the relay forwards was signed by the pair's peer. The relay alone can never unpair the phone.
 */
class RelayRevocations(
    private val auth: RelayAuth,
    private val api: RelayApi,
    private val relayPairs: RelayPairs,
    private val clock: () -> Long,
) {
    /**
     * PAIR-03 API 4 logic 1: a `pair_revoked` counts only when `by` is the pair's peer and the statement verifies with
     * the peer's stored `ik_sig` public key; the relay alone can never unpair the phone.
     */
    suspend fun isSignedByPeer(frame: RelayPairRevoked): Boolean =
        isSignedByPeer(frame.pairId, frame.by, frame.revokedAt, frame.sig)

    suspend fun isSignedByPeer(
        pairId: String,
        by: String?,
        revokedAt: Long?,
        sig: String?,
    ): Boolean {
        val peer = relayPairs.peerOf(pairId) ?: return false
        val statement = RevocationStatement.Received(pairId, by, revokedAt, sig)
        return RevocationStatement.isFromPeer(statement, peer.deviceId, peer.ikSigPub)
    }

    /** `HLREVOKE1` of this phone for [pairId] at [revokedAt] (0.6.2), b64u. */
    fun statement(
        pairId: String,
        revokedAt: Long,
    ): String = Base64Codecs.encodeB64u(auth.sign(RevocationStatement.message(pairId, auth.deviceId, revokedAt)))

    /**
     * SET-02 A4: one fresh statement for every pair the relay may still hold unrevoked — the local pairs, tombstones
     * included, and every unrevoked pair `GET /v1/pairs` lists. A list the relay cannot give leaves the local pairs;
     * a relay that no longer knows the device ends the deletion as done (via [RelayRequestException]).
     */
    suspend fun forDelete(): List<Revocation> {
        val remote =
            try {
                api
                    .pairs(registerIfUnknown = false)
                    .pairs
                    .filter { it.revokedAt == null }
                    .map { it.pairId }
            } catch (e: RelayRequestException) {
                if (e.code in RelayRegistrar.GONE) throw e
                emptyList()
            }
        val now = clock()
        return (relayPairs.pairsToRevokeOnDelete() + remote).distinct().map { Revocation(it, now, statement(it, now)) }
    }
}
