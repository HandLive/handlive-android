package app.handlive.android.feature.call.module

/**
 * The `HLBENCH/1` events of the call group that the phone writes (shared/tools/bench/README.md): identifiers, states,
 * actions and error codes only — never numbers, names or DTMF keys (group 6 rules, 0.6.5). A no-op outside debug
 * builds.
 */
interface CallTrace {
    /** The default listener reported a change of the aggregate state (the start of the latency budgets). */
    fun phoneState(
        callId: String,
        state: String,
        at: Long,
    ) = Unit

    /** `call_event/state` handed to one client's session; [viaRelay] = through the relay. */
    fun stateSent(
        callId: String,
        state: String,
        peerDeviceId: String,
        viaRelay: Boolean,
    ) = Unit

    fun actionReceived(
        callId: String,
        action: String,
        peerDeviceId: String,
    ) = Unit

    /** The `ack` of an action, right after the Telecom method returned (CALL-02 API 1 logic 2). */
    fun actionAckSent(
        callId: String,
        action: String,
        peerDeviceId: String,
        code: String?,
    ) = Unit

    companion object {
        val NONE = object : CallTrace {}
    }
}
