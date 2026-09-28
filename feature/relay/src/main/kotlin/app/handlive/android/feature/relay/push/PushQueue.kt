package app.handlive.android.feature.relay.push

import app.handlive.android.core.data.db.PushKind
import app.handlive.android.core.data.db.PushOutboxDao
import app.handlive.android.core.data.db.PushOutboxEntity
import app.handlive.android.core.data.pairing.PushTarget
import app.handlive.android.core.protocol.id.UuidV7Generator
import app.handlive.android.core.protocol.relay.PushRequest
import app.handlive.android.core.protocol.relay.RelayValues
import app.handlive.android.feature.relay.RelayConstants

/**
 * `push_outbox` for the pushes the relay refused for a temporary reason (CONN-04 E2): each waits with its `reason`
 * and is retried 5 s, 15 s, 45 s… at most 5 minutes apart until it expires — 30 s for an incoming call, a day for a
 * new SMS or a missed call (CONN-04 step 5b).
 */
internal class PushQueue(
    private val outbox: PushOutboxDao,
    private val clock: () -> Long,
    private val ids: UuidV7Generator,
) {
    suspend fun add(
        request: PushRequest,
        retryAfterMillis: Long?,
    ) {
        val now = clock()
        outbox.insert(
            PushOutboxEntity(
                id = ids.next(),
                pairId = request.pairId,
                kind = PushKind.ALERT,
                bodyB64 = request.envB64,
                collapseKey = request.collapseKey,
                attempts = 0,
                nextAttemptAt = now + maxOf(delayAfter(0), retryAfterMillis ?: 0),
                expiresAt = now + expiryOf(request.reason),
                reason = request.reason,
            ),
        )
    }

    /** The pushes whose time has come, each sent through [deliver]; expired ones are deleted first. */
    suspend fun retryDue(
        targets: Map<String, PushTarget>,
        deliver: suspend (PushRequest) -> PushDelivery,
    ) {
        val now = clock()
        outbox.deleteExpired(now)
        for (entry in outbox.due(now, RelayConstants.PUSH_OUTBOX_BATCH)) {
            val request = targets[entry.pairId]?.let { requestOf(entry, it) }
            val delivery = request?.let { deliver(it) } ?: PushDelivery(PushResult.DROPPED)
            if (delivery.result == PushResult.RETRY) {
                val attempts = entry.attempts + 1
                outbox.reschedule(entry.id, attempts, now + maxOf(delayAfter(attempts), delivery.retryAfterMillis ?: 0))
            } else {
                outbox.delete(entry.id, now)
            }
        }
    }

    suspend fun nextDue(): Long? = outbox.nextAttemptAt(clock())

    /** A queued push that would announce what is over, such as an incoming call that stopped ringing. */
    suspend fun drop(
        collapseKey: String,
        reason: String,
    ) {
        outbox.deleteQueued(collapseKey, reason)
    }

    /**
     * The request again as the outbox kept it, with the `ttl_s` of its reason: 30 for an incoming call (CALL-01 API 4;
     * the outbox itself gives up 30 s after the first try), 86,400 otherwise (SMS-02 API 2, CALL-04 API 5).
     */
    private fun requestOf(
        entry: PushOutboxEntity,
        target: PushTarget,
    ): PushRequest {
        val ttl =
            if (entry.reason == RelayValues.REASON_CALL_INCOMING) {
                CallPushBuilder.INCOMING_TTL_SECONDS
            } else {
                PushEnvelopeBuilder.SMS_TTL_SECONDS
            }
        return PushRequest(
            pairId = entry.pairId,
            to = target.peerDeviceId,
            kind = entry.kind.wire,
            reason = entry.reason,
            envB64 = entry.bodyB64,
            collapseKey = entry.collapseKey,
            ttlS = ttl,
        )
    }

    private fun expiryOf(reason: String): Long =
        if (reason == RelayValues.REASON_CALL_INCOMING) {
            RelayConstants.CALL_INCOMING_PUSH_EXPIRY_MILLIS
        } else {
            RelayConstants.SMS_PUSH_EXPIRY_MILLIS
        }

    /** 5 s, 15 s, 45 s… at most 5 minutes. */
    private fun delayAfter(attempts: Int): Long {
        var delay = RelayConstants.PUSH_RETRY_FIRST_MILLIS
        repeat(attempts) {
            delay =
                (delay * RelayConstants.PUSH_RETRY_FACTOR).coerceAtMost(RelayConstants.PUSH_RETRY_MAX_MILLIS)
        }
        return delay
    }
}
