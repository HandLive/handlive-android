package app.handlive.android.feature.call.module

import app.handlive.android.core.protocol.call.CallStateData
import app.handlive.android.feature.call.context.CallContext
import app.handlive.android.feature.connection.session.PeerSession

/**
 * The `HLBENCH/1` events of the call group that the phone writes (shared/tools/bench/README.md): call ids, envelope
 * ids, states, `sub_id`s and error codes only — never a number, a contact name, a SIM label or a DTMF digit (group 6
 * rules, 0.6.5). A no-op outside debug builds.
 */
interface CallTrace {
    /**
     * A-CALL applied an OS event that changed [context]; [os] = when the OS delivered it (`call_changed`). [settled]:
     * this event settled the caller's number of the ringing call, where the incoming push time starts (CALL-01 API 4
     * logic 2); true for one change per call at most.
     */
    fun changed(
        context: CallContext,
        trigger: String,
        os: Long,
        settled: Boolean,
    ) = Unit

    /** `call_event/state` [data] handed to one client's session as envelope [envId] (`call_state_sent`). */
    fun stateSent(
        data: CallStateData,
        envId: String,
        session: PeerSession,
        newSession: Boolean,
    ) = Unit

    /** `call_event/action` [envId] decrypted (`call_action_received`). */
    fun actionReceived(
        callId: String,
        envId: String,
        session: PeerSession,
        action: String,
    ) = Unit

    /** Its `ack`, with the error [code] or none, handed to the WebSocket (`call_action_ack_sent`). */
    fun actionAckSent(
        callId: String,
        envId: String,
        session: PeerSession,
        code: String?,
    ) = Unit

    companion object {
        /** `trigger` of `call_changed`: the state listeners (API 2), a PHONE_STATE copy (API 3), the call log. */
        const val LISTENER = "listener"
        const val BROADCAST = "broadcast"
        const val CALLLOG = "calllog"

        val NONE = object : CallTrace {}
    }
}
