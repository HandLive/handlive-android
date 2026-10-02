package app.handlive.android.feature.call.appcall

import app.handlive.android.core.protocol.call.AppCallApp
import app.handlive.android.core.protocol.call.AppCallAudio
import app.handlive.android.core.protocol.call.AppCallControls
import app.handlive.android.core.protocol.call.AppCallData
import app.handlive.android.core.protocol.call.AppCallState

/** `call_event/app_call` for one client (CALL-05): the context, the `controls` its intents allow, the `answer_mode`. */
object AppCallView {
    fun of(
        context: AppCallContext,
        answerMode: String,
    ): AppCallData =
        AppCallData(
            callId = context.callId,
            app = AppCallApp(context.packageName, context.label),
            caller = context.caller,
            state = context.state,
            controls = controls(context),
            answerMode = answerMode,
            audio = AppCallAudio.PHONE,
            startedAt = context.startedAt,
            answeredAt = context.answeredAt,
            endedAt = context.endedAt,
            endReason = context.endReason,
        )

    /** Answer and decline: ringing and the notification has the intent; end: ongoing and an end action exists. */
    private fun controls(context: AppCallContext): AppCallControls {
        val ringing = context.state == AppCallState.RINGING
        return AppCallControls(
            answer = ringing && context.answer != null,
            decline = ringing && context.decline != null,
            end = context.state == AppCallState.ONGOING && context.end != null,
        )
    }
}
