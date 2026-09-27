package app.handlive.android.feature.relay.push

import app.handlive.android.core.data.db.PushOutboxDao
import app.handlive.android.core.data.pairing.PushTarget
import app.handlive.android.core.data.pairing.RelayPairs
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.call.CallStateData
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
import app.handlive.android.feature.call.module.MissedCall
import kotlinx.coroutines.CancellationException

/** What happened to one push (CONN-04 E1–E4), for the outbox and the bench log. */
enum class PushResult { SENT, DROPPED, RETRY }

/** A push's result and, for a 429, how long the relay asked to wait (`Retry-After`, CONN-04 E4). */
class PushDelivery(
    val result: PushResult,
    val retryAfterMillis: Long? = null,
)

/**
 * Alert pushes to an iPhone or iPad without a session (CONN-04), only for a pair registered with the relay whose
 * latest capability wants them and has not refused the relay: a new inbox SMS with `sms.notify` (SMS-02 step 10), an
 * incoming or missed call with `call.notify` (CALL-01 step 5, CALL-04 API 5). A temporary refusal (network, 429, 5xx,
 * 502 `PUSH_PROVIDER_ERROR`) waits in `push_outbox` ([PushQueue], E2); 409 `PUSH_TOKEN_MISSING` and 403 `NOT_PAIRED`
 * are dropped (E1, PAIR-03).
 */
class PushSender(
    private val api: RelayApi,
    private val relayPairs: RelayPairs,
    outbox: PushOutboxDao,
    clock: () -> Long,
    /** Every push the relay answered, with the HTTP status (the bench's `sms_push_sent`, `call_push_sent`). */
    private val answered: (PushRequest, Int) -> Unit = { _, _ -> },
    ids: UuidV7Generator = UuidV7Generator(clock),
) {
    private val builder = PushEnvelopeBuilder(clock, ids)
    private val calls = CallPushBuilder(clock, ids::next)
    private val queue = PushQueue(outbox, clock, ids)

    /** [connected] = pairs with a session now (LAN or relay): they got `sms/new` and need no push. */
    suspend fun smsNew(
        new: SmsNewData,
        connected: Set<String>,
    ) {
        if (new.message.box != SmsBox.INBOX) return
        push(connected, ::wantsSms) { builder.smsNew(it.pairId, it.peerDeviceId, it.prk, new) }
    }

    /** CALL-01 step 5: the one `call_incoming` push of a ringing call, [state] as an iPhone sees it. */
    suspend fun callIncoming(
        state: CallStateData,
        connected: Set<String>,
    ) = push(connected, ::wantsCalls) { calls.incoming(it.callTarget(), state) }

    /** CALL-04 step 8 and flow A: a missed call. */
    suspend fun callMissed(
        missed: MissedCall,
        connected: Set<String>,
    ) = push(connected, ::wantsCalls) { calls.missed(it.callTarget(), missed) }

    /** The call stopped ringing: an incoming-call push still waiting would announce a call that is over. */
    suspend fun dropIncoming(callId: String) =
        queue.drop(CallPushBuilder.CALL_KEY + callId, RelayValues.REASON_CALL_INCOMING)

    /** The pushes waiting in `push_outbox` whose time has come; expired ones are deleted. */
    suspend fun retryDue() = queue.retryDue(relayPairs.pushTargets().associateBy { it.pairId }, ::deliver)

    /** When the next waiting push is due, to wake the retry loop; `null` when the outbox is empty. */
    suspend fun nextDue(): Long? = queue.nextDue()

    /** Builds and sends one push per wanting target outside [connected]; a temporary failure is queued. */
    private suspend fun push(
        connected: Set<String>,
        feature: (CapabilityData) -> Boolean,
        build: (PushTarget) -> PushRequest?,
    ) {
        val targets = relayPairs.pushTargets().filter { it.pairId !in connected && it.wants(feature) }
        for (target in targets) {
            val request = build(target) ?: continue
            val delivery = deliver(request)
            if (delivery.result == PushResult.RETRY) queue.add(request, delivery.retryAfterMillis)
        }
    }

    private suspend fun deliver(request: PushRequest): PushDelivery =
        try {
            val response = api.push(request)
            answered(request, response.status)
            deliveryOf(response)
        } catch (e: CancellationException) {
            throw e
        } catch (_: RelayUnreachableException) {
            PushDelivery(PushResult.RETRY)
        } catch (e: RelayRequestException) {
            // Authentication failed on the way (5xx, 429 of the auth endpoints, or a revoked device).
            val temporary = e.status >= HTTP_SERVER_ERROR || e.code == RelayErrorCode.RATE_LIMITED
            PushDelivery(if (temporary) PushResult.RETRY else PushResult.DROPPED, e.retryAfterMillis)
        }

    private companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500

        fun deliveryOf(response: RelayResponse): PushDelivery =
            when {
                response.ok -> PushDelivery(PushResult.SENT)

                response.status == HTTP_TOO_MANY_REQUESTS -> PushDelivery(PushResult.RETRY, response.retryAfterMillis)

                response.status >= HTTP_SERVER_ERROR -> PushDelivery(PushResult.RETRY)

                // 409 PUSH_TOKEN_MISSING (E1), 403 NOT_PAIRED, 413 PAYLOAD_TOO_LARGE, 400: no retry changes that.
                else -> PushDelivery(PushResult.DROPPED)
            }

        /** SMS-02 API 2 logic 1 on the stored capability (`features_json`). */
        fun wantsSms(capability: CapabilityData): Boolean =
            capability.features.sms?.let { it.enabled && it.notify == true } == true

        /** CALL-01 step 5, CALL-04 API 5 logic 1: calls on and `call.notify` on the iPhone or iPad. */
        fun wantsCalls(capability: CapabilityData): Boolean =
            capability.features.call?.let { it.enabled && it.notify == true } == true

        /** [feature] on the target's latest capability, and the relay not refused; an unreadable one sends nothing. */
        fun PushTarget.wants(feature: (CapabilityData) -> Boolean): Boolean {
            val capability =
                runCatching { ProtocolJson.decodeFromString(CapabilityData.serializer(), featuresJson) }.getOrNull()
            return capability != null && feature(capability) && capability.features.relay?.enabled != false
        }

        fun PushTarget.callTarget() = CallPushBuilder.Target(pairId, peerDeviceId, prk)
    }
}
