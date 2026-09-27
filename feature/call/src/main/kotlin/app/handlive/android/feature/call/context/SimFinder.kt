package app.handlive.android.feature.call.context

import app.handlive.android.feature.call.CallConstants
import kotlin.math.abs

/**
 * The SIM of a new call (CALL-01 API 2 logic 3): exactly one per-SIM listener reporting the state that created the
 * context within 500 ms around the default callback gives `sub_id`; several SIMs, or none, give `null`. Part of
 * [CallTracker], on the A-CALL thread.
 */
internal class SimFinder(
    private val labels: SimLabels,
) {
    private val reports = mutableListOf<SimReport>()
    private var creation: Creation? = null

    fun clear() {
        reports.clear()
        creation = null
    }

    /** The context [callId] was created by [state] at [at]: only reports around that count. */
    fun created(
        callId: String,
        state: PhoneState,
        at: Long,
    ) {
        creation = Creation(callId, state, at)
    }

    /** Keeps [report]; `true` when it may change the SIM of the context [callId]. */
    fun record(
        report: SimReport,
        callId: String?,
    ): Boolean {
        reports.removeAll { report.at - it.at > 2 * CallConstants.SIM_WINDOW_MILLIS }
        reports += report
        return creation?.let { it.callId == callId && it.covers(report) } == true
    }

    /** [context] with the `sub_id` and `sim_label` the reports give it now. */
    fun label(context: CallContext): CallContext {
        val window = creation?.takeIf { it.callId == context.callId } ?: return context
        val subId =
            reports
                .filter(window::covers)
                .map { it.subId }
                .distinct()
                .singleOrNull()
        return context.copy(subId = subId, simLabel = subId?.let(labels::label))
    }

    private class Creation(
        val callId: String,
        val state: PhoneState,
        val at: Long,
    ) {
        fun covers(report: SimReport): Boolean =
            report.state == state && abs(report.at - at) <= CallConstants.SIM_WINDOW_MILLIS
    }
}
