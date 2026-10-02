package app.handlive.android.feature.call.module

import app.handlive.android.core.protocol.call.AppCallData
import app.handlive.android.core.protocol.call.CallStateData
import app.handlive.android.feature.call.appcall.AppCallContext
import app.handlive.android.feature.call.context.CallContext
import app.handlive.android.feature.connection.session.PeerSession

/**
 * The `HLBENCH/1` events of the call group that the phone writes (shared/tools/bench/README.md): call ids, envelope
 * ids, states, `sub_id`s and error codes only — never a number, a contact name, a SIM label, a DTMF digit, an app
 * call's caller or app label (group 6 rules, 0.6.5). A no-op outside debug builds.
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

    /**
     * A-CALL applied a notification listener event that changed the app call [context] (`app_call_changed`, CALL-05):
     * [os] = the notification's `postTime` for a post, the wall clock of the listener callback for a removal or a lost
     * listener, when A-CALL decided otherwise (the end of the link window, the setting turned off).
     */
    fun appCallChanged(
        context: AppCallContext,
        os: Long,
    ) = Unit

    /** `call_event/app_call` [data] handed to one client's session as envelope [envId] (`app_call_sent`). */
    fun appCallSent(
        data: AppCallData,
        envId: String,
        session: PeerSession,
        newSession: Boolean,
    ) = Unit

    /**
     * HandLive sent the calling app's intent for [action] (`answer`, `reject`, `end`) of the app call [callId], or
     * posted the tap-to-answer notification (`app_call_intent_sent`); [mode] = [DIRECT], [TAP] or [PLAIN].
     */
    fun appCallIntentSent(
        callId: String,
        action: String,
        mode: String,
    ) = Unit

    companion object {
        /**
         * `mode` of `app_call_intent_sent`: the answer intent sent with the background-start option, the
         * tap-to-answer notification posted, an intent sent without the option (decline, end).
         */
        const val DIRECT = "direct"
        const val TAP = "tap"
        const val PLAIN = "plain"

        /** `trigger` of `call_changed`: the state listeners (API 2), a PHONE_STATE copy (API 3), the call log. */
        const val LISTENER = "listener"
        const val BROADCAST = "broadcast"
        const val CALLLOG = "calllog"

        val NONE = object : CallTrace {}
    }
}
