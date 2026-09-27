package app.handlive.android.feature.call.context

import app.handlive.android.core.protocol.call.CallDirection
import app.handlive.android.core.protocol.call.CallPresentation

/**
 * A call context of A-CALL (CALL-01 step 3): created on `RINGING` or `OFFHOOK` from `IDLE`, alive until `IDLE`, then
 * kept 60 s in the "recently ended" list for the call log (CALL-04). It lives in memory only and never reaches a log
 * (group 6 rules). The client-independent part of `call_event/state`; `controls`, `hfp_connected` and `audio_on` are
 * added per client ([app.handlive.android.feature.call.module.CallStateView]).
 */
data class CallContext(
    val callId: String,
    val direction: String,
    val phoneState: PhoneState,
    val startedAt: Long,
    val waiting: Boolean = false,
    val number: String? = null,
    val displayName: String? = null,
    val presentation: String = CallPresentation.UNKNOWN,
    val subId: Int? = null,
    val simLabel: String? = null,
    val waitingNumber: String? = null,
    val waitingDisplayName: String? = null,
    val answeredAt: Long? = null,
    val endedAt: Long? = null,
    val endReason: String? = null,
    /** The phone was `OFFHOOK` during the context: it ends as `ended`, never `missed` (API 1 logic 5). */
    val wentOffhook: Boolean = false,
    /**
     * The phone has said what the number is — the number-carrying broadcast arrived, with or without a number — so
     * the `call_incoming` push waits no longer (API 4 logic 2).
     */
    val numberSettled: Boolean = false,
    /** Copies of the ringing broadcast without the number key, to tell a withheld number (API 3 logic 3). */
    val bareRingingCopies: Int = 0,
    /** Where the client that answered wants the call (CALL-02 API 1 logic 5); read by call audio (group 7). */
    val requestedAudio: String? = null,
) {
    val ringing: Boolean get() = phoneState == PhoneState.RINGING

    val ended: Boolean get() = phoneState == PhoneState.IDLE

    /** An incoming call that rings on its own, not a call waiting behind an ongoing one (E9). */
    val ringingIncoming: Boolean get() = ringing && !waiting && direction == CallDirection.INCOMING
}
