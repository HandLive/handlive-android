package app.handlive.android.feature.relay.push

import app.handlive.android.core.data.db.PushKind
import app.handlive.android.core.data.db.PushOutboxDao
import app.handlive.android.core.data.db.PushOutboxEntity
import app.handlive.android.core.data.pairing.PushTarget
import app.handlive.android.core.data.pairing.RelayPairs
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.capability.CapabilityData
import app.handlive.android.core.protocol.id.UuidV7Generator
import app.handlive.android.core.protocol.relay.PushRequest
import app.handlive.android.core.protocol.relay.RelayErrorCode
import app.handlive.android.core.protocol.relay.RelayValues
import app.handlive.android.core.protocol.sms.SmsBox
import app.handlive.android.core.protocol.sms.SmsNewData
import app.handlive.android.core.transport.relay.RelayApi
import app.handlive.android.core.transport.relay.RelayRequestException
import app.handlive.android.core.transport.relay.RelayResponse
import app.handlive.android.core.transport.relay.RelayUnreachableException
import app.handlive.android.feature.relay.RelayConstants
import kotlinx.coroutines.CancellationException

/** What happened to one push (CONN-04 E1–E4), for the outbox and the bench log. */
enum class PushResult { SENT, DROPPED, RETRY }

/** A push's result and, for a 429, how long the relay asked to wait (`Retry-After`, CONN-04 E4). */
class PushDelivery(
    val result: PushResult,
    val retryAfterMillis: Long? = null,
)

/**
 * Alert pushes to an iPhone or iPad without a session (SMS-02 step 10, CONN-04): only for an inbox message, a pair
 * registered with the relay, whose latest capability has SMS on, `sms.notify` on and the relay not refused. A
 * temporary refusal (network, 429, 5xx, 502 `PUSH_PROVIDER_ERROR`) waits in `push_outbox` and is retried with a
 * growing delay until it expires (E2); 409 `PUSH_TOKEN_MISSING` and 403 `NOT_PAIRED` are dropped (E1, PAIR-03).
 */
class PushSender(
    private val api: RelayApi,
    private val relayPairs: RelayPairs,
    private val outbox: PushOutboxDao,
    private val clock: () -> Long,
    private val sent: (messageKey: String, peerDeviceId: String) -> Unit = { _, _ -> },
    private val ids: UuidV7Generator = UuidV7Generator(clock),
) {
    private val builder = PushEnvelopeBuilder(clock, ids)

    /** [connected] = pairs with a session now (LAN or relay): they got `sms/new` and need no push. */
    suspend fun smsNew(
        new: SmsNewData,
        connected: Set<String>,
    ) {
        if (new.message.box != SmsBox.INBOX) return
        val targets = relayPairs.pushTargets().filter { it.pairId !in connected && wantsSmsPush(it) }
        for (target in targets) {
            val request = builder.smsNew(target.pairId, target.peerDeviceId, target.prk, new) ?: continue
            val delivery = deliver(request)
            when (delivery.result) {
                PushResult.SENT -> sent(new.message.messageKey, target.peerDeviceId)
                PushResult.RETRY -> queue(request, delivery.retryAfterMillis)
                PushResult.DROPPED -> Unit
            }
        }
    }

    /** The pushes waiting in `push_outbox` whose time has come; expired ones are deleted. */
    suspend fun retryDue() {
        val now = clock()
        outbox.deleteExpired(now)
        val targets = relayPairs.pushTargets().associateBy { it.pairId }
        for (entry in outbox.due(now, RelayConstants.PUSH_OUTBOX_BATCH)) {
            val target = targets[entry.pairId]
            val request = target?.let { requestOf(entry, it) }
            val delivery = request?.let { deliver(it) } ?: PushDelivery(PushResult.DROPPED)
            if (delivery.result == PushResult.RETRY) {
                val attempts = entry.attempts + 1
                outbox.reschedule(entry.id, attempts, now + maxOf(delayAfter(attempts), delivery.retryAfterMillis ?: 0))
            } else {
                outbox.delete(entry.id, now)
            }
        }
    }

    /** When the next waiting push is due, to wake the retry loop; `null` when the outbox is empty. */
    suspend fun nextDue(): Long? = outbox.nextAttemptAt(clock())

    private suspend fun deliver(request: PushRequest): PushDelivery =
        try {
            deliveryOf(api.push(request))
        } catch (e: CancellationException) {
            throw e
        } catch (_: RelayUnreachableException) {
            PushDelivery(PushResult.RETRY)
        } catch (e: RelayRequestException) {
            // Authentication failed on the way (5xx, 429 of the auth endpoints, or a revoked device).
            val temporary = e.status >= HTTP_SERVER_ERROR || e.code == RelayErrorCode.RATE_LIMITED
            PushDelivery(if (temporary) PushResult.RETRY else PushResult.DROPPED, e.retryAfterMillis)
        }

    private fun deliveryOf(response: RelayResponse): PushDelivery =
        when {
            response.ok -> PushDelivery(PushResult.SENT)

            response.status == HTTP_TOO_MANY_REQUESTS -> PushDelivery(PushResult.RETRY, response.retryAfterMillis)

            response.status >= HTTP_SERVER_ERROR -> PushDelivery(PushResult.RETRY)

            // 409 PUSH_TOKEN_MISSING (E1), 403 NOT_PAIRED, 413 PAYLOAD_TOO_LARGE, 400: sending again changes nothing.
            else -> PushDelivery(PushResult.DROPPED)
        }

    private suspend fun queue(
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
                expiresAt = now + RelayConstants.SMS_PUSH_EXPIRY_MILLIS,
            ),
        )
    }

    /** The request again as the outbox kept it; `ttl_s` stays 86,400 for `sms_new` (SMS-02 API 2). */
    private fun requestOf(
        entry: PushOutboxEntity,
        target: PushTarget,
    ): PushRequest =
        PushRequest(
            pairId = entry.pairId,
            to = target.peerDeviceId,
            kind = entry.kind.wire,
            reason = RelayValues.REASON_SMS_NEW,
            envB64 = entry.bodyB64,
            collapseKey = entry.collapseKey,
            ttlS = PushEnvelopeBuilder.SMS_TTL_SECONDS,
        )

    /** 5 s, 15 s, 45 s… at most 5 minutes. */
    private fun delayAfter(attempts: Int): Long {
        var delay = RelayConstants.PUSH_RETRY_FIRST_MILLIS
        repeat(attempts) {
            delay =
                (delay * RelayConstants.PUSH_RETRY_FACTOR).coerceAtMost(RelayConstants.PUSH_RETRY_MAX_MILLIS)
        }
        return delay
    }

    /** SMS-02 API 2 logic 1 on the stored capability (`features_json`); an unreadable one sends nothing. */
    private fun wantsSmsPush(target: PushTarget): Boolean {
        val capability =
            runCatching { ProtocolJson.decodeFromString(CapabilityData.serializer(), target.featuresJson) }.getOrNull()
        val sms = capability?.features?.sms
        return sms?.enabled == true && sms.notify == true && capability.features.relay?.enabled != false
    }

    private companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
    }
}
