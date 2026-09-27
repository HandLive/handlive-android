package app.handlive.android.feature.call.module

import app.handlive.android.core.protocol.call.CallEndReason
import app.handlive.android.feature.call.CallConstants
import app.handlive.android.feature.call.context.CallContext
import kotlinx.coroutines.Job

/**
 * What goes to the clients without a session ([OfflineCallDelivery], CONN-03, CONN-04) when the context changes: one
 * `call_incoming` push per ringing incoming call, as soon as the number is known — or 300 ms after `RINGING`, or at
 * once when no number can come (E2) — never for a waiting call (CALL-01 API 4 logic 1–2); the start and the end of
 * the ringing, for the relay; a missed call inferred from the state when there is no call log (flow A). Missed calls
 * of the call log go from [CallEvents.logRound]. Part of [CallEvents], on the A-CALL thread.
 */
internal class CallPushes(
    private val current: () -> CallContext?,
    private val broadcaster: CallBroadcaster,
    private val connected: () -> Set<String>,
    /** Runs a task on the A-CALL thread after a delay; the returned job cancels it. */
    private val later: (Long, suspend () -> Unit) -> Job,
    private val offline: () -> OfflineCallDelivery,
    /** `READ_CALL_LOG` is granted: numbers come from the broadcast, missed calls from the call log. */
    private val callerId: () -> Boolean,
) {
    private var ringingCallId: String? = null
    private var pushedCallId: String? = null
    private var numberWait: Job? = null

    fun changed(context: CallContext) {
        when {
            context.ringingIncoming -> ringing(context)
            context.callId == ringingCallId -> stoppedRinging()
        }
        // Flow A: without the call log the state is the only source of missed calls (CALL-04 A1, API 2 logic 3).
        if (context.ended && context.endReason == CallEndReason.MISSED && !callerId()) {
            offline().missed(MissedCall.Inferred(broadcaster.forPush(context)), connected())
        }
    }

    /** The listeners went away (feature off, permission lost): the call no longer rings as far as HandLive knows. */
    fun reset() {
        if (ringingCallId != null) stoppedRinging()
    }

    private fun ringing(context: CallContext) {
        if (context.callId != ringingCallId) {
            ringingCallId = context.callId
            offline().ringing(context.callId)
            numberWait = later(CallConstants.PUSH_NUMBER_WAIT_MILLIS) { pushIncoming(context.callId) }
        }
        if (context.numberSettled || !callerId()) pushIncoming(context.callId)
    }

    /** At most one `call_incoming` push per `call_id`, only while it rings. */
    private fun pushIncoming(callId: String) {
        val context = current()?.takeIf { it.callId == callId && it.ringingIncoming }
        if (context == null || pushedCallId == callId) return
        pushedCallId = callId
        numberWait?.cancel()
        offline().incoming(broadcaster.forPush(context), connected())
    }

    private fun stoppedRinging() {
        val callId = ringingCallId ?: return
        ringingCallId = null
        numberWait?.cancel()
        offline().ringingEnded(callId)
    }
}
