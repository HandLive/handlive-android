package app.handlive.android.feature.call.module

import app.handlive.android.core.protocol.call.CallAudio
import app.handlive.android.core.protocol.call.CallControls
import app.handlive.android.core.protocol.call.CallStateData
import app.handlive.android.core.protocol.call.HfpControl
import app.handlive.android.feature.call.context.CallContext
import app.handlive.android.feature.call.context.PhoneState

/**
 * What the receiving client adds to a state (CALL-01 API 1 logic 4): whether that Mac has the HFP profile connected
 * to the phone and where the call audio is. Call audio comes with Phase 4 (group 7); until then every client sees
 * [PHONE] — and an iPhone or iPad always does.
 */
data class ClientAudio(
    val hfpConnected: Boolean,
    val audioOn: String,
) {
    companion object {
        val PHONE = ClientAudio(hfpConnected = false, audioOn = CallAudio.PHONE)
    }
}

/** Who receives a state: a Mac may answer (CALL-02), and its HFP link and audio location (group 7). */
data class CallRecipient(
    val mac: Boolean,
    val audio: ClientAudio = ClientAudio.PHONE,
)

/** `call_event/state` for one client (CALL-01 API 1): the context plus `controls`, `hfp_connected`, `audio_on`. */
object CallStateView {
    /** [canControl] = `ANSWER_PHONE_CALLS` is granted (`features.call.can_answer`, `can_end`). */
    fun of(
        context: CallContext,
        recipient: CallRecipient,
        canControl: Boolean,
    ): CallStateData =
        CallStateData(
            callId = context.callId,
            direction = context.direction,
            state = context.phoneState.phase,
            waiting = context.waiting,
            number = context.number,
            displayName = context.displayName,
            presentation = context.presentation,
            subId = context.subId,
            simLabel = context.simLabel,
            waitingNumber = context.waitingNumber,
            waitingDisplayName = context.waitingDisplayName,
            startedAt = context.startedAt,
            answeredAt = context.answeredAt,
            endedAt = context.endedAt,
            endReason = context.endReason,
            controls = controls(context, recipient, canControl),
            hfpConnected = recipient.audio.hfpConnected,
            audioOn = recipient.audio.audioOn,
        )

    /** The `controls` table of CALL-01 API 1. */
    fun controls(
        context: CallContext,
        recipient: CallRecipient,
        canControl: Boolean,
    ): CallControls {
        val ringing = context.phoneState == PhoneState.RINGING && !context.waiting && canControl
        val offhook = context.phoneState == PhoneState.OFFHOOK
        val hfp = offhook && recipient.audio.hfpConnected
        val onMac = recipient.audio.audioOn == CallAudio.MAC
        return CallControls(
            answer = ringing && recipient.mac,
            reject = ringing,
            end = offhook && !context.waiting && canControl,
            hold = hfp.control(),
            dtmf = hfp.control(),
            mute = (hfp && onMac).control(),
        )
    }

    private fun Boolean.control(): String = if (this) HfpControl.HFP else HfpControl.UNAVAILABLE
}
