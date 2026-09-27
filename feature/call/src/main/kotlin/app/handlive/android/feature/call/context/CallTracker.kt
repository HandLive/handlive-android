package app.handlive.android.feature.call.context

import app.handlive.android.core.protocol.call.CallDirection
import app.handlive.android.core.protocol.call.CallEndReason
import app.handlive.android.feature.call.CallConstants

/**
 * A-CALL's call context state machine (CALL-01 API 1 logic 2, API 2, API 3), fed one event at a time on the A-CALL
 * thread: the default listener's aggregate state (the only source of the state), the per-SIM reports (only for
 * `sub_id`) and the PHONE_STATE broadcast copies (only for numbers). Every event returns the contexts that changed —
 * the current one, or the one that just ended — for the broadcaster. Nothing here is logged or written to disk.
 */
class CallTracker(
    private val ids: () -> String,
    numbers: CallNumbers,
    sims: SimLabels,
    /** `READ_CALL_LOG` is granted, so a withheld number shows as two ringing copies without the number key. */
    callerId: () -> Boolean,
) {
    private var lastState: PhoneState? = null
    private var declinedAt: Long? = null
    private val callers = CallerNumbers(numbers, callerId)
    private val simFinder = SimFinder(sims)

    /** The contexts that ended in the last 60 s, for the call log (CALL-04). */
    val recent = RecentCalls()

    /** The call in progress; `null` while the phone is idle. */
    var current: CallContext? = null
        private set

    /** Listeners (re)registered or removed: the next report builds the context from the current state (E8). */
    fun reset() {
        lastState = null
        current = null
        declinedAt = null
        callers.clear()
        simFinder.clear()
    }

    /** The default listener (API 2): `IDLE → RINGING`, `IDLE → OFFHOOK`, `RINGING ↔ OFFHOOK`, `→ IDLE`. */
    fun onPhoneState(
        state: PhoneState,
        at: Long,
    ): List<CallContext> {
        val previous = lastState
        lastState = state
        val context = current
        val changed =
            when {
                previous == state -> null

                state == PhoneState.IDLE -> context?.let { end(it, at) }

                context == null -> create(state, at, registration = previous == null)

                state == PhoneState.OFFHOOK -> offhook(context, at)

                // OFFHOOK → RINGING: a call waiting behind the ongoing one (E9); the context keeps its call_id.
                else -> callers.release(context.copy(phoneState = PhoneState.RINGING, waiting = true), at)
            }
        if (changed != null && !changed.ended) current = changed
        return listOfNotNull(changed)
    }

    /** API 2 logic 3: a per-SIM report may name the SIM of the context just created. */
    fun onSimState(report: SimReport): List<CallContext> {
        val context = current
        val relevant = simFinder.record(report, context?.callId)
        return if (context != null && relevant) changed(context, simFinder.label(context)) else emptyList()
    }

    /** API 3: a copy sets numbers on the context in its state; a copy for another state waits for it. */
    fun onBroadcast(copy: BroadcastCopy): List<CallContext> {
        val context = current
        return when {
            copy.state == PhoneState.IDLE -> emptyList()
            context == null || context.phoneState != copy.state -> emptyList<CallContext>().also { callers.hold(copy) }
            else -> changed(context, callers.apply(context, copy))
        }
    }

    /**
     * CALL-02 API 1 logic 4: `reject` is about to call `endCall()` at [at] (`null`: it failed); an `IDLE` within
     * 3 s ends the call as `rejected`.
     */
    fun declined(at: Long?) {
        declinedAt = at
    }

    /** CALL-02 API 1 logic 5: the answering client's `audio`, kept for call audio (AUDIO-03). */
    fun requestAudio(audio: String) {
        current = current?.copy(requestedAudio = audio)
    }

    private fun create(
        state: PhoneState,
        at: Long,
        registration: Boolean,
    ): CallContext {
        val direction =
            when {
                state == PhoneState.RINGING -> CallDirection.INCOMING
                registration -> CallDirection.UNKNOWN
                else -> CallDirection.OUTGOING
            }
        val context = CallContext(ids(), direction, state, startedAt = at, wentOffhook = state == PhoneState.OFFHOOK)
        simFinder.created(context.callId, state, at)
        return callers.release(simFinder.label(context), at)
    }

    /** `RINGING → OFFHOOK`: answered, or the waiting call is over (accepted or not — C12 cannot tell). */
    private fun offhook(
        context: CallContext,
        at: Long,
    ): CallContext {
        val next =
            if (context.waiting) {
                context.copy(
                    phoneState = PhoneState.OFFHOOK,
                    waiting = false,
                    waitingNumber = null,
                    waitingDisplayName = null,
                )
            } else {
                val answered = if (context.direction == CallDirection.INCOMING) at else null
                context.copy(phoneState = PhoneState.OFFHOOK, answeredAt = answered, wentOffhook = true)
            }
        return callers.release(next, at)
    }

    private fun end(
        context: CallContext,
        at: Long,
    ): CallContext {
        val reason =
            when {
                context.wentOffhook -> CallEndReason.ENDED
                declinedAt?.let { at - it <= CallConstants.ACTION_LOCK_MILLIS } == true -> CallEndReason.REJECTED
                else -> CallEndReason.MISSED
            }
        val ended =
            context.copy(
                phoneState = PhoneState.IDLE,
                waiting = false,
                waitingNumber = null,
                waitingDisplayName = null,
                endedAt = at,
                endReason = reason,
            )
        current = null
        declinedAt = null
        recent.add(ended)
        return ended
    }

    private fun changed(
        old: CallContext,
        new: CallContext,
    ): List<CallContext> {
        current = new
        return if (new.sameAs(old)) emptyList() else listOf(new)
    }
}
