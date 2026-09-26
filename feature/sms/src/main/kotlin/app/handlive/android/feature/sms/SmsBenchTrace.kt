package app.handlive.android.feature.sms

import app.handlive.android.feature.connection.bench.BenchLog
import app.handlive.android.feature.sms.module.SmsTrace

/**
 * [SmsTrace] written as `HLBENCH/1` lines (debuggable builds only, [BenchLog]): message keys, `local_id`s, the first
 * 8 hex digits of the peer's `device_id`, boxes, states and codes — never text, numbers or names.
 */
internal class SmsBenchTrace : SmsTrace {
    override fun detected(
        messageKey: String,
        box: String,
        onChangeAt: Long,
        providerDate: Long,
    ) = BenchLog.event(
        SmsBenchEvent.SMS_DETECTED,
        "msg" to messageKey,
        "box" to box,
        "onchange" to onChangeAt,
        "provider" to providerDate,
    )

    override fun newSent(
        messageKey: String,
        peerDeviceId: String,
        viaRelay: Boolean,
    ) = BenchLog.event(
        SmsBenchEvent.SMS_NEW_SENT,
        "msg" to messageKey,
        "peer" to peerDeviceId.take(PEER_ID),
        "via" to if (viaRelay) "relay" else "lan",
    )

    override fun sendReceived(
        localId: String,
        peerDeviceId: String,
    ) = BenchLog.event(SmsBenchEvent.SMS_SEND_RECEIVED, "local" to localId, "peer" to peerDeviceId.take(PEER_ID))

    override fun sendAckSent(
        localId: String,
        peerDeviceId: String,
        ok: Boolean,
        code: String?,
    ) = BenchLog.event(
        SmsBenchEvent.SMS_SEND_ACK_SENT,
        withCode(code, "local" to localId, "peer" to peerDeviceId.take(PEER_ID), "ok" to ok),
    )

    override fun radioDone(
        localId: String,
        failed: Boolean,
        code: String?,
    ) = BenchLog.event(
        SmsBenchEvent.SMS_RADIO_DONE,
        withCode(code, "local" to localId, "result" to if (failed) "failed" else "sent"),
    )

    override fun statusSent(
        localId: String,
        peerDeviceId: String,
        status: String,
        code: String?,
    ) = BenchLog.event(
        SmsBenchEvent.SMS_STATUS_SENT,
        withCode(code, "local" to localId, "peer" to peerDeviceId.take(PEER_ID), "status" to status),
    )

    /** The fields, then the optional `code`. */
    private fun withCode(
        code: String?,
        vararg fields: Pair<String, Any>,
    ): List<Pair<String, Any>> = if (code == null) fields.asList() else fields.asList() + ("code" to code)

    private companion object {
        const val PEER_ID = 8
    }
}

/** The SMS rows of the `HLBENCH/1` table that the phone writes (`shared/tools/bench/README.md`). */
object SmsBenchEvent {
    const val SMS_DETECTED = "sms_detected"
    const val SMS_NEW_SENT = "sms_new_sent"
    const val SMS_PUSH_SENT = "sms_push_sent"
    const val SMS_SEND_RECEIVED = "sms_send_received"
    const val SMS_SEND_ACK_SENT = "sms_send_ack_sent"
    const val SMS_RADIO_DONE = "sms_radio_done"
    const val SMS_STATUS_SENT = "sms_status_sent"
}
