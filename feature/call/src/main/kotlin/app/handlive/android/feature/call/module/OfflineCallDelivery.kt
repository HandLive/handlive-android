package app.handlive.android.feature.call.module

import app.handlive.android.core.protocol.call.CallLogNewData
import app.handlive.android.core.protocol.call.CallStateData

/** A missed call for the push (CALL-04 API 5): from the call log, or inferred from the state without it (flow A). */
sealed interface MissedCall {
    /** `call_event/log_new` of a `missed` entry (with `READ_CALL_LOG`); `collapse_key` `call:` or `calllog:`. */
    class Logged(
        val new: CallLogNewData,
    ) : MissedCall

    /** `call_event/state` `idle` with `end_reason = missed` (flow A, no `READ_CALL_LOG`). */
    class Inferred(
        val state: CallStateData,
    ) : MissedCall
}

/**
 * Where calls go for clients without a session (the relay module: CONN-03, CONN-04). Every call returns at once; the
 * relay does its work on its own thread, so the A-CALL thread is never held up by the network.
 */
interface OfflineCallDelivery {
    /** An incoming call rings: clients waiting on the relay need the phone there (CALL-01 step 4). */
    fun ringing(callId: String) = Unit

    /**
     * The one `call_incoming` push of [state] (`ringing`, seen as an iPhone sees it) for iPhone and iPad pairs outside
     * [connected] (CALL-01 step 5); the relay then stays open while the call rings (API 4 logic 4).
     */
    fun incoming(
        state: CallStateData,
        connected: Set<String>,
    ) = Unit

    /** The call stopped ringing: the relay follows `RELAY_IDLE_DISCONNECT` again; a queued incoming push is moot. */
    fun ringingEnded(callId: String) = Unit

    /** A missed call for iPhone and iPad pairs outside [connected] (CALL-04 step 8, flow A). */
    fun missed(
        missed: MissedCall,
        connected: Set<String>,
    ) = Unit

    companion object {
        val NONE = object : OfflineCallDelivery {}
    }
}
