package app.handlive.android.feature.call.context

import app.handlive.android.core.protocol.call.CallDirection
import app.handlive.android.core.protocol.call.CallEndReason
import app.handlive.android.feature.call.CallConstants
import kotlin.math.abs

/**
 * The "recently ended" list (CALL-01 postconditions): each ended context stays 60 s, so the call log entry written
 * when it ended can be matched with it (CALL-04 API 2 logic 2) and its `end_reason` corrected once (CALL-01 API 1
 * logic 5). Memory only.
 */
class RecentCalls {
    private val ended = ArrayDeque<Ended>()

    fun add(context: CallContext) {
        forget(context.endedAt ?: return)
        ended.addLast(Ended(context))
    }

    /**
     * The context of a new call log entry — not matched yet, started within 5 s of [date], of [direction] (or
     * `unknown`), with the same number when both have one; the closest in time. `null` when none matches.
     */
    fun match(
        date: Long,
        direction: String,
        number: String?,
        now: Long,
    ): CallContext? {
        forget(now)
        val found =
            ended
                .filter { !it.matched && it.fits(date, direction, number) }
                .minByOrNull { abs(date - it.context.startedAt) } ?: return null
        found.matched = true
        return found.context
    }

    /** A context that ended as `missed` gets [endReason], once; `null` when there is nothing to correct. */
    fun correct(
        callId: String,
        endReason: String,
    ): CallContext? {
        val entry =
            ended.firstOrNull { it.context.callId == callId && it.context.endReason == CallEndReason.MISSED }
                ?: return null
        entry.context = entry.context.copy(endReason = endReason)
        return entry.context
    }

    private fun forget(now: Long) {
        ended.removeAll { now - (it.context.endedAt ?: now) > CallConstants.RECENTLY_ENDED_MILLIS }
    }

    /** An ended context and whether a call log entry already took it. */
    private class Ended(
        var context: CallContext,
        var matched: Boolean = false,
    ) {
        fun fits(
            date: Long,
            direction: String,
            number: String?,
        ): Boolean =
            abs(date - context.startedAt) <= CallConstants.LOG_MATCH_MILLIS &&
                (context.direction == direction || context.direction == CallDirection.UNKNOWN) &&
                (number == null || context.number == null || number == context.number)
    }
}
