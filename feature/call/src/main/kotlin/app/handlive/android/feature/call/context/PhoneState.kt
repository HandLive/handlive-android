package app.handlive.android.feature.call.context

import app.handlive.android.core.protocol.call.CallPhase

/** The phone's aggregate call state as the public APIs report it (CALL-01 API 2): no per-call details (C12). */
enum class PhoneState(
    /** `state.state` on the wire. */
    val phase: String,
) {
    IDLE(CallPhase.IDLE),
    RINGING(CallPhase.RINGING),
    OFFHOOK(CallPhase.OFFHOOK),
    ;

    companion object {
        /** `TelephonyManager.CALL_STATE_IDLE` (0), `CALL_STATE_RINGING` (1), `CALL_STATE_OFFHOOK` (2). */
        fun fromCallState(state: Int): PhoneState? = entries.getOrNull(state)

        /** `TelephonyManager.EXTRA_STATE_IDLE` / `_RINGING` / `_OFFHOOK` of the PHONE_STATE broadcast (API 3). */
        fun fromExtra(value: String?): PhoneState? = entries.firstOrNull { it.name == value }
    }
}

/**
 * One copy of the `ACTION_PHONE_STATE_CHANGED` broadcast (CALL-01 API 3). An app holding `READ_PHONE_STATE` and
 * `READ_CALL_LOG` receives two copies per change, in no fixed order; only one may carry the
 * `EXTRA_INCOMING_NUMBER` key ([numberKey]), whose value ([number]) may be empty when the caller withholds it.
 */
data class BroadcastCopy(
    val state: PhoneState,
    val numberKey: Boolean,
    val number: String?,
    val at: Long,
)

/** A per-SIM listener's report (CALL-01 API 2): only used to find the SIM of a new call. */
data class SimReport(
    val subId: Int,
    val state: PhoneState,
    val at: Long,
)
