package app.handlive.android.feature.call.context

import app.handlive.android.core.protocol.call.CallDirection
import app.handlive.android.core.protocol.call.CallPresentation
import app.handlive.android.feature.call.CallConstants

/**
 * The numbers of CALL-01 API 3: what a PHONE_STATE copy sets on the context in its state — the number of a ringing
 * (or answered) incoming call, the number of a waiting call, a withheld number — and the copies that arrived before
 * the listener reported their state, held for 2 s (logic 4). Part of [CallTracker], on the A-CALL thread.
 */
internal class CallerNumbers(
    private val numbers: CallNumbers,
    /** `READ_CALL_LOG` is granted, so a withheld number shows as two ringing copies without the number key. */
    private val callerId: () -> Boolean,
) {
    private val held = mutableListOf<BroadcastCopy>()

    fun clear() = held.clear()

    /** A copy for a state the context is not in yet. */
    fun hold(copy: BroadcastCopy) {
        forget(copy.at)
        held += copy
    }

    /** The held copies of the state [context] has just entered, applied in their order of arrival. */
    fun release(
        context: CallContext,
        at: Long,
    ): CallContext {
        forget(at)
        val matching = held.filter { it.state == context.phoneState }
        held.removeAll { it.state == context.phoneState }
        return matching.fold(context, ::apply)
    }

    fun apply(
        context: CallContext,
        copy: BroadcastCopy,
    ): CallContext {
        val raw = copy.number?.trim().orEmpty()
        return when {
            context.waiting -> if (copy.numberKey && raw.isNotEmpty()) waitingCaller(context, raw) else context
            copy.state == PhoneState.OFFHOOK -> answeredCaller(context, copy, raw)
            !copy.numberKey -> bareRingingCopy(context)
            raw.isEmpty() -> if (context.number == null) context.withheld() else context
            else -> caller(context, raw)
        }
    }

    /** Two ringing copies without the number key while `READ_CALL_LOG` is granted: the caller withholds it. */
    private fun bareRingingCopy(context: CallContext): CallContext {
        val bare = context.copy(bareRingingCopies = context.bareRingingCopies + 1)
        val withheld = bare.bareRingingCopies >= 2 && !bare.numberSettled && callerId()
        return if (withheld) bare.withheld() else bare
    }

    /** An `OFFHOOK` copy fills in the number of an answered incoming call whose ringing copy never came. */
    private fun answeredCaller(
        context: CallContext,
        copy: BroadcastCopy,
        raw: String,
    ): CallContext {
        val missing = context.direction == CallDirection.INCOMING && !context.numberSettled
        return if (missing && copy.numberKey && raw.isNotEmpty()) caller(context, raw) else context
    }

    private fun caller(
        context: CallContext,
        raw: String,
    ): CallContext {
        val number = numbers.normalize(raw, context.subId)
        if (context.numberSettled && context.number == number) return context
        return context.copy(
            number = number,
            displayName = numbers.name(number),
            presentation = CallPresentation.ALLOWED,
            numberSettled = true,
        )
    }

    private fun waitingCaller(
        context: CallContext,
        raw: String,
    ): CallContext {
        val number = numbers.normalize(raw, context.subId)
        if (context.waitingNumber == number) return context
        return context.copy(waitingNumber = number, waitingDisplayName = numbers.name(number))
    }

    private fun forget(now: Long) {
        held.removeAll { now - it.at > CallConstants.BROADCAST_HOLD_MILLIS }
    }
}

private fun CallContext.withheld() =
    copy(number = null, displayName = null, presentation = CallPresentation.RESTRICTED, numberSettled = true)
