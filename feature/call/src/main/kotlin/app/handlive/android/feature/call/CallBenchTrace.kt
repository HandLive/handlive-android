package app.handlive.android.feature.call

import app.handlive.android.core.protocol.call.CallStateData
import app.handlive.android.feature.call.context.CallContext
import app.handlive.android.feature.call.module.CallTrace
import app.handlive.android.feature.connection.bench.BenchLog
import app.handlive.android.feature.connection.session.PeerSession

/**
 * [CallTrace] written as `HLBENCH/1` lines (debuggable builds only, [BenchLog]): call ids, envelope ids, the first
 * 8 hex digits of the peer's `device_id`, states, `sub_id`s and error codes — never a number, a contact name or a SIM
 * label (the `number` field only says whether one is known).
 */
internal class CallBenchTrace(
    private val log: (event: String, fields: List<Pair<String, Any>>) -> Unit = BenchLog::event,
) : CallTrace {
    override fun changed(
        context: CallContext,
        trigger: String,
        os: Long,
        settled: Boolean,
    ) = log(
        CallBenchEvent.CALL_CHANGED,
        buildList {
            add("call" to context.callId)
            add("state" to context.phoneState.phase)
            add("waiting" to context.waiting)
            add("trigger" to trigger)
            add("os" to os)
            add("number" to if (context.number != null) "known" else "none")
            if (settled) add("settled" to true)
            context.subId?.let { add("sub" to it) }
            context.endReason?.let { add("end" to it) }
        },
    )

    override fun stateSent(
        data: CallStateData,
        envId: String,
        session: PeerSession,
        newSession: Boolean,
    ) = log(
        CallBenchEvent.CALL_STATE_SENT,
        listOf(
            "call" to data.callId,
            "env" to envId,
            "peer" to session.peerDeviceId.take(PEER_ID),
            "via" to if (session.channel == PeerSession.Channel.RELAY) "relay" else "lan",
            "state" to data.state,
            "reason" to if (newSession) "session" else "change",
        ),
    )

    override fun actionReceived(
        callId: String,
        envId: String,
        session: PeerSession,
        action: String,
    ) = log(
        CallBenchEvent.CALL_ACTION_RECEIVED,
        listOf(
            "call" to callId.ifEmpty { NONE },
            "env" to envId,
            "peer" to session.peerDeviceId.take(PEER_ID),
            "action" to action.ifEmpty { NONE },
        ),
    )

    override fun actionAckSent(
        callId: String,
        envId: String,
        session: PeerSession,
        code: String?,
    ) = log(
        CallBenchEvent.CALL_ACTION_ACK_SENT,
        listOf(
            "call" to callId.ifEmpty { NONE },
            "env" to envId,
            "peer" to session.peerDeviceId.take(PEER_ID),
            "ok" to (code == null),
        ) +
            listOfNotNull(code?.let { "code" to it }),
    )

    private companion object {
        const val PEER_ID = 8
        const val NONE = "none"
    }
}

/** The call rows of the `HLBENCH/1` table that the phone writes (`shared/tools/bench/README.md`). */
object CallBenchEvent {
    const val CALL_CHANGED = "call_changed"
    const val CALL_STATE_SENT = "call_state_sent"
    const val CALL_PUSH_SENT = "call_push_sent"
    const val CALL_ACTION_RECEIVED = "call_action_received"
    const val CALL_ACTION_ACK_SENT = "call_action_ack_sent"
}
