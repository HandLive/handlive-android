package app.handlive.android.feature.call.module

import app.handlive.android.core.protocol.call.CallEndReason
import app.handlive.android.core.protocol.call.CallLogNewData
import app.handlive.android.core.protocol.call.CallLogType
import app.handlive.android.feature.call.context.BroadcastCopy
import app.handlive.android.feature.call.context.CallContext
import app.handlive.android.feature.call.context.PhoneState
import app.handlive.android.feature.call.context.SimReport
import app.handlive.android.feature.call.log.CallLogEntries
import app.handlive.android.feature.connection.capability.AndroidPermissions
import kotlinx.coroutines.Job

/**
 * The events of the call group, one at a time on the A-CALL thread (CALL-01 API 1 logic 1): a change of the context
 * goes to the sessions ([CallBroadcaster]) and to the clients without one ([CallPushes]). The call log rounds send
 * `log_new`, push missed calls, match each entry with its context and correct the context's `end_reason`
 * (CALL-04 API 2, API 3).
 */
class CallEvents(
    private val services: CallServices,
    /** Pairs with a session now (LAN or relay): they need no push. */
    private val connected: () -> Set<String>,
    later: (Long, suspend () -> Unit) -> Job,
    private val offline: () -> OfflineCallDelivery,
    private val clock: () -> Long,
) {
    private val tracker = services.tracker
    private val broadcaster = services.broadcaster
    private val pushes =
        CallPushes({ tracker.current }, broadcaster, connected, later, offline) {
            services.access.granted(AndroidPermissions.READ_CALL_LOG)
        }

    suspend fun phoneState(
        state: PhoneState,
        at: Long,
    ) {
        val changes = tracker.onPhoneState(state, at)
        changes.forEach { services.trace.phoneState(it.callId, it.phoneState.phase, at) }
        changed(changes)
    }

    suspend fun simState(report: SimReport) = changed(tracker.onSimState(report))

    suspend fun broadcast(copy: BroadcastCopy) = changed(tracker.onBroadcast(copy))

    /** The listeners were registered again or removed: the next report builds a new context (E8). */
    fun reset() {
        tracker.reset()
        pushes.reset()
    }

    /** A session got calls in effect, or `ANSWER_PHONE_CALLS` changed the controls: the call where it differs. */
    suspend fun republish() = broadcaster.publish(tracker.current)

    /** CALL-04 API 3: the new call log rows as `log_new`, in ascending `_ID` order. */
    suspend fun logRound() {
        val rows = services.logs.watcher.newRows()
        if (rows.isEmpty()) return
        val entries = services.logs.entries()
        val now = clock()
        for (row in rows) {
            val entry = entries.entry(row) ?: continue
            val direction = CallLogEntries.directionOf(row.type)
            val context = direction?.let { tracker.recent.match(row.date, it, entry.number, now) }
            val new = CallLogNewData(entry, context?.callId)
            broadcaster.logNew(new)
            if (entry.type == CallLogType.MISSED) offline().missed(MissedCall.Logged(new), connected())
            context?.let { correction(it, row.type) }?.let { broadcaster.publish(it) }
        }
    }

    private suspend fun changed(contexts: List<CallContext>) {
        for (context in contexts) {
            broadcaster.publish(context)
            pushes.changed(context)
        }
    }

    /** CALL-01 API 1 logic 5: declined or blocked on the phone, or answered on another device sharing the number. */
    private fun correction(
        context: CallContext,
        type: Int,
    ): CallContext? {
        val reason =
            when (type) {
                CallLogEntries.TYPE_REJECTED, CallLogEntries.TYPE_BLOCKED -> CallEndReason.REJECTED
                CallLogEntries.TYPE_ANSWERED_EXTERNALLY -> CallEndReason.ANSWERED_ELSEWHERE
                else -> null
            }
        return reason?.let { tracker.recent.correct(context.callId, it) }
    }
}
