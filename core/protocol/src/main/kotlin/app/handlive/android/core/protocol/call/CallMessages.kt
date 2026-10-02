package app.handlive.android.core.protocol.call

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `op` names of `type = call_event` (0.7.1). */
object CallOp {
    const val STATE = "state"
    const val ACTION = "action"
    const val HFP_STATUS = "hfp_status"
    const val LOG_SYNC = "log_sync"
    const val LOG_NEW = "log_new"
    const val APP_CALL = "app_call"
}

/** `state.direction` (CALL-01 API 1): `unknown` when the context was built while the phone was already `OFFHOOK`. */
object CallDirection {
    const val INCOMING = "incoming"
    const val OUTGOING = "outgoing"
    const val UNKNOWN = "unknown"
}

/** `state.state`: the phone's aggregate state (CALL-01 API 1). */
object CallPhase {
    const val RINGING = "ringing"
    const val OFFHOOK = "offhook"
    const val IDLE = "idle"
}

/** `state.presentation` (CALL-01 API 1). */
object CallPresentation {
    const val ALLOWED = "allowed"
    const val RESTRICTED = "restricted"
    const val UNKNOWN = "unknown"
}

/** `state.end_reason`, only when `state = idle` (CALL-01 API 1 logic 5). */
object CallEndReason {
    const val MISSED = "missed"
    const val REJECTED = "rejected"
    const val ENDED = "ended"
    const val ANSWERED_ELSEWHERE = "answered_elsewhere"
}

/** `action.action` (CALL-02 API 1): over WebSocket only [ANSWER], [REJECT] and [END] are carried out. */
object CallAction {
    const val ANSWER = "answer"
    const val REJECT = "reject"
    const val END = "end"
    const val HOLD = "hold"
    const val UNHOLD = "unhold"
    const val DTMF = "dtmf"
    const val MUTE = "mute"

    /** Actions that exist only as HFP commands (C12): sent over WebSocket they get `CALL_HFP_REQUIRED`. */
    val HFP_ONLY = setOf(HOLD, UNHOLD, DTMF, MUTE)
    val ALL = setOf(ANSWER, REJECT, END) + HFP_ONLY
}

/** `action.audio` and `state.audio_on` (CALL-01 API 1, CALL-02 API 1). */
object CallAudio {
    const val PHONE = "phone"
    const val MAC = "mac"
    val ALL = setOf(PHONE, MAC)
}

/** `controls.hold`, `dtmf`, `mute` (CALL-01 API 1). */
object HfpControl {
    const val HFP = "hfp"
    const val UNAVAILABLE = "unavailable"
}

/** `details.reason` of `CALL_ACTION_NOT_ALLOWED` (0.8.1, CALL-02 API 1). */
object CallRefusal {
    const val STATE = "state"
    const val WAITING = "waiting"
    const val PLATFORM = "platform"
    const val SYSTEM = "system"
}

/** `entry.type` of the call log (CALL-04 shared object). */
object CallLogType {
    const val INCOMING = "incoming"
    const val OUTGOING = "outgoing"
    const val MISSED = "missed"
    const val REJECTED = "rejected"
    const val BLOCKED = "blocked"
    const val VOICEMAIL = "voicemail"
}

/** The actions a client may perform on the call (CALL-01 API 1, `controls` table). */
@Serializable
data class CallControls(
    val answer: Boolean,
    val reject: Boolean,
    val end: Boolean,
    val hold: String,
    val dtmf: String,
    val mute: String,
) {
    companion object {
        /** Nothing is possible (an ended call, a phone without `ANSWER_PHONE_CALLS` and without HFP). */
        val NONE =
            CallControls(false, false, false, HfpControl.UNAVAILABLE, HfpControl.UNAVAILABLE, HfpControl.UNAVAILABLE)
    }
}

/**
 * `call_event/state` (CALL-01 API 1): every field is required, the nullable ones are written as `null`, so none has
 * a default. The phone builds one per session, since `controls`, `hfp_connected` and `audio_on` depend on the client.
 */
@Serializable
data class CallStateData(
    @SerialName("call_id") val callId: String,
    val direction: String,
    val state: String,
    val waiting: Boolean,
    val number: String?,
    @SerialName("display_name") val displayName: String?,
    val presentation: String,
    @SerialName("sub_id") val subId: Int?,
    @SerialName("sim_label") val simLabel: String?,
    @SerialName("waiting_number") val waitingNumber: String?,
    @SerialName("waiting_display_name") val waitingDisplayName: String?,
    @SerialName("started_at") val startedAt: Long,
    @SerialName("answered_at") val answeredAt: Long?,
    @SerialName("ended_at") val endedAt: Long?,
    @SerialName("end_reason") val endReason: String?,
    val controls: CallControls,
    @SerialName("hfp_connected") val hfpConnected: Boolean,
    @SerialName("audio_on") val audioOn: String,
)

/** `call_event/action` (CALL-02 API 1, CALL-03 API 1); absent [audio] means `phone`. */
@Serializable
data class CallActionRequest(
    @SerialName("call_id") val callId: String,
    val action: String,
    val audio: String? = null,
)

/** `call_event/log_sync` (CALL-04 API 1): absent [cursor] = first sync; Android accepts a [limit] of 1–500. */
@Serializable
data class CallLogSyncRequest(
    val cursor: String? = null,
    val limit: Int,
)

/** The shared `entry` object of CALL-04: `number`, `display_name` and `sub_id` are written even when `null`. */
@Serializable
data class CallLogEntryData(
    @SerialName("entry_id") val entryId: Long,
    val number: String?,
    @SerialName("display_name") val displayName: String?,
    val type: String,
    val ts: Long,
    @SerialName("duration_s") val durationS: Int,
    @SerialName("sub_id") val subId: Int?,
)

/** `ack.data` of `call_event/log_sync` (CALL-04 API 1). */
@Serializable
data class CallLogSyncResponse(
    val entries: List<CallLogEntryData>,
    val cursor: String,
    @SerialName("has_more") val hasMore: Boolean,
    val reset: Boolean,
)

/** `call_event/log_new` (CALL-04 API 2): [callId] is `null` when no call context matched the entry. */
@Serializable
data class CallLogNewData(
    val entry: CallLogEntryData,
    @SerialName("call_id") val callId: String?,
)

/** `app_call.state` (CALL-05): a call of another app, as its notification tells it. */
object AppCallState {
    const val RINGING = "ringing"
    const val ONGOING = "ongoing"
    const val ENDED = "ended"
}

/** `app_call.end_reason`, only when `state = ended`. */
object AppCallEndReason {
    const val DECLINED = "declined"
    const val ENDED = "ended"
    const val MISSED = "missed"
    const val UNKNOWN = "unknown"
}

/** `app_call.answer_mode`: `direct` while HandLive may start the app's answer screen from the background. */
object AppCallAnswerMode {
    const val DIRECT = "direct"
    const val TAP = "tap"
}

/** `app_call.audio`: v1 keeps the audio on the phone; `mac` is reserved and refused with `CALL_ROUTE_FAILED`. */
object AppCallAudio {
    const val PHONE = "phone"
}

/** `app_call.app`: the calling app. */
@Serializable
data class AppCallApp(
    @SerialName("package") val packageName: String,
    val label: String,
)

/** What the client may do to the app call: each is a button of the Mac panel. */
@Serializable
data class AppCallControls(
    val answer: Boolean,
    val decline: Boolean,
    val end: Boolean,
)

/**
 * `call_event/app_call` (CALL-05, S→C): every field is required and the nullable ones are written as `null`, so none
 * has a default. `caller` is personal data: it travels end-to-end encrypted only and is never logged or stored.
 */
@Serializable
data class AppCallData(
    @SerialName("call_id") val callId: String,
    val app: AppCallApp,
    val caller: String?,
    val state: String,
    val controls: AppCallControls,
    @SerialName("answer_mode") val answerMode: String,
    val audio: String,
    @SerialName("started_at") val startedAt: Long,
    @SerialName("answered_at") val answeredAt: Long?,
    @SerialName("ended_at") val endedAt: Long?,
    @SerialName("end_reason") val endReason: String?,
)
