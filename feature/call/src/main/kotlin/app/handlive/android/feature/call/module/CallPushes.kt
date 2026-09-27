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
    private var settledCallId: String? = null
    private var pushedCallId: String? = null
    private var numberWait: Job? = null

    /**
     * [context] settles the caller's number of its ringing call — the copy with the number, the second copy without
     * it that marks a withheld caller (a copy held for `RINGING` counts with it), or `RINGING` itself without
     * `READ_CALL_LOG` — where the incoming push time starts (API 4 logic 2). True for one change per call, before
     * [changed] gets that change; the 300 ms fallback settles nothing.
     */
    fun settles(context: CallContext): Boolean =
        context.ringingIncoming && settled(context) && context.callId != settledCallId

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
        if (settled(context)) {
            settledCallId = context.callId
            pushIncoming(context.callId)
        }
    }

    /** The number is known or withheld, or none can come (E2): the push waits no longer. */
    private fun settled(context: CallContext) = context.numberSettled || !callerId()

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
