package app.handlive.android.feature.pairing.revoke

import app.handlive.android.core.data.pairing.PairStore
import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import app.handlive.android.core.protocol.pairing.PairOp
import app.handlive.android.core.protocol.pairing.PairRevokeData
import app.handlive.android.core.transport.server.InboundEnvelope
import app.handlive.android.feature.connection.SessionEnded
import app.handlive.android.feature.connection.session.AckTimeoutException
import app.handlive.android.feature.connection.session.EnvelopeHandler
import app.handlive.android.feature.connection.session.PeerSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** PAIR-03 field 4: both devices cleaned up, or the other one will when it reconnects. */
enum class UnpairResult { DONE, DONE_PENDING_REMOTE }

/**
 * A pair ended from the other side: "<name> unpaired this device" (PAIR-03 field 5), or, when the relay reported it
 * revoked ([elsewhere], PAIR-02 E3), "<name> was unpaired from another device".
 */
data class UnpairedByPeer(
    val peerName: String,
    val elsewhere: Boolean = false,
)

/** Revokes a pair on the relay (PAIR-03 step 8); the relay feature's. */
fun interface RemoteRevoker {
    /** [reason] ∈ {`user`, `lost_device`}; runs in the background, the tombstone goes when the relay confirms. */
    fun revoke(
        pairId: String,
        reason: String,
    )
}

/** Closes the `/v1/ctl` session of a pair with `session/bye {reason}` (the connection runtime). */
fun interface SessionCloser {
    suspend fun close(
        pairId: String,
        reason: String,
    )
}

/**
 * PAIR-03 on the phone. Initiator (flow A): `pair/revoke` on the open session, `ack` within 10 s, then the key is
 * wiped and the session closes — without a session or an `ack` (E2, flow B) the phone cleans up alone, revokes the
 * pair on the relay ([remote]) and the client cleans up when it next connects. Receiver: `ack` first (the keys are
 * still needed to encrypt it), then clean up, `session/bye {reason: revoked}` and close 1000 (API 1); a revocation
 * the relay reports ([onRevokedByRelay]) is cleaned up the same way.
 */
class UnpairController(
    private val pairs: PairStore,
    private val sessions: () -> Map<String, PeerSession>,
    private val closer: SessionCloser,
) {
    private val notice = MutableStateFlow<UnpairedByPeer?>(null)

    /** PAIR-03 step 8 through the relay; `null` while the relay is not available. */
    @Volatile
    var remote: RemoteRevoker? = null

    /** The latest "<name> unpaired this device" not yet shown; the UI calls [noticeShown] after showing it. */
    val unpairedByPeer: StateFlow<UnpairedByPeer?> = notice.asStateFlow()

    fun noticeShown() {
        notice.value = null
    }

    suspend fun unpair(pairId: String): UnpairResult {
        val session = sessions()[pairId]
        val confirmed =
            session != null &&
                try {
                    session.request(MessageType.PAIR, revokePlaintext(pairId)).ok
                } catch (_: AckTimeoutException) {
                    false
                }
        // The PRK is wiped before the result is reported (PAIR-03 special requirements).
        pairs.revoke(pairId)
        closer.close(pairId, BYE_REVOKED)
        // Step 8: the relay stops forwarding and pushing; `lost_device` when the peer could not be told (flow B).
        remote?.revoke(pairId, if (confirmed) REASON_USER else REASON_LOST_DEVICE)
        return if (confirmed) UnpairResult.DONE else UnpairResult.DONE_PENDING_REMOTE
    }

    /**
     * The relay says the pair is revoked: `pair_revoked` (PAIR-03 API 4, [elsewhere] false) or a revoked pair in
     * `GET /v1/pairs` (PAIR-02 E3, [elsewhere] true). Cleaned up as a receiver; repeats are ignored.
     */
    suspend fun onRevokedByRelay(
        pairId: String,
        elsewhere: Boolean,
    ) {
        val device = pairs.find(pairId) ?: return
        if (pairs.revoke(pairId)) notice.value = UnpairedByPeer(device.peerName, elsewhere)
        closer.close(pairId, BYE_REVOKED)
    }

    /** `pair/revoke` from the client (API 1, receiver side). */
    val handler =
        EnvelopeHandler { session, envelope ->
            val request = revokeRequest(envelope)
            when {
                request == null -> {
                    session.sendError(envelope.id, ErrorCode.UNSUPPORTED_TYPE, "unsupported pair op")
                }

                request.pairId != session.pairId -> {
                    session.sendError(envelope.id, ErrorCode.BAD_REQUEST, "pair_id does not match the session")
                }

                else -> {
                    session.sendAck(Ack.success(envelope.id))
                    cleanUpRevoked(session.pairId, session.peerName)
                }
            }
        }

    /** API 2: a `session/bye {reason: revoked}` also cleans up, in case `pair/revoke` got lost. */
    suspend fun onSessionEnded(ended: SessionEnded) {
        if (ended.byeReason != BYE_REVOKED) return
        val device = pairs.find(ended.pairId) ?: return
        cleanUpRevoked(ended.pairId, device.peerName)
    }

    private suspend fun cleanUpRevoked(
        pairId: String,
        peerName: String,
    ) {
        if (pairs.revoke(pairId)) notice.value = UnpairedByPeer(peerName)
        closer.close(pairId, BYE_REVOKED)
    }

    private fun revokeRequest(envelope: InboundEnvelope): PairRevokeData? =
        runCatching {
            val payload = PlaintextCodec.decodePayload(envelope.plaintext)
            require(payload.op == PairOp.REVOKE)
            ProtocolJson.decodeFromJsonElement(PairRevokeData.serializer(), payload.data)
        }.getOrNull()

    private fun revokePlaintext(pairId: String) =
        PlaintextCodec.encodeOp(
            PairOp.REVOKE,
            PairRevokeData.serializer(),
            PairRevokeData(pairId, PairRevokeData.REASON_USER),
        )

    private companion object {
        const val BYE_REVOKED = "revoked"
        const val REASON_USER = "user"
        const val REASON_LOST_DEVICE = "lost_device"
    }
}
