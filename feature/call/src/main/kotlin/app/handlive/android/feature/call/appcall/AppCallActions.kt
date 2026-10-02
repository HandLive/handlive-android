package app.handlive.android.feature.call.appcall

import app.handlive.android.core.protocol.call.AppCallState
import app.handlive.android.core.protocol.call.CallAction
import app.handlive.android.core.protocol.call.CallAudio
import app.handlive.android.feature.call.module.CallError
import app.handlive.android.feature.call.module.CallTrace
import app.handlive.android.feature.call.module.decodeCallActionRequest
import kotlinx.serialization.json.JsonObject

/**
 * `call_event/action` for an app call (CALL-05 API 2) with the errors in their order: `BAD_REQUEST`,
 * `CALL_HFP_REQUIRED` (hold, unhold, dtmf, mute), `CALL_NOT_FOUND` (a `call_id` that is no live app call, reached only
 * by a session that has app calls in effect but telephony calls not), `CALL_ROUTE_FAILED` (`answer` with `audio = mac`:
 * the audio of an app call stays on the phone) and `CALL_APP_ACTION_UNAVAILABLE` (the notification no longer offers
 * the action, or the app canceled its intent). The checks before — `FEATURE_DISABLED` per session — belong to the
 * caller. No Telecom permission is involved and no action lock applies. Only the answer intent is sent with the
 * background-activity-start option (it starts the app's call screen); decline and end go without it. Runs on the
 * A-CALL thread; an intent that throws is the caller's `INTERNAL`.
 */
class AppCallActions(
    private val tracker: AppCallTracker,
    private val exemption: BackgroundStartExemption,
    private val tap: TapToAnswerNotifier,
    private val trace: CallTrace = CallTrace.NONE,
) {
    /** Carries out [data]; `null` means done (`ack {}`), otherwise the refusal. */
    fun perform(data: JsonObject): CallError? {
        val request = decodeCallActionRequest(data)
        val context = request?.let { tracker.find(it.callId) }
        return when {
            request == null -> CallError.badRequest("malformed call_event/action")
            request.action in CallAction.HFP_ONLY -> CallError.hfpRequired(request.action)
            context == null -> CallError.notFound()
            request.action == CallAction.ANSWER && request.audio == CallAudio.MAC -> CallError.routeFailed()
            request.action == CallAction.ANSWER -> answer(context)
            request.action == CallAction.REJECT -> decline(context)
            else -> end(context)
        }
    }

    /** Direct: the app's answer intent now. Tap: the user's tap on a HandLive notification sends it. */
    private fun answer(context: AppCallContext): CallError? {
        val intent = context.answer?.takeIf { context.state == AppCallState.RINGING } ?: return unavailable()
        return if (exemption.held()) {
            send(context, intent, CallAction.ANSWER) { tracker.markSent(context.callId, AppCallAction.ANSWER) }
        } else if (tap.post(context.callId, context.label, context.caller, intent)) {
            // The user's tap may still not come: an answer nothing follows ends as unknown, not missed.
            tracker.markSent(context.callId, AppCallAction.ANSWER)
            trace.appCallIntentSent(context.callId, CallAction.ANSWER, CallTrace.TAP)
            null
        } else {
            unavailable()
        }
    }

    private fun decline(context: AppCallContext): CallError? {
        val intent = context.decline?.takeIf { context.state == AppCallState.RINGING } ?: return unavailable()
        return send(context, intent, CallAction.REJECT) { tracker.markSent(context.callId, AppCallAction.DECLINE) }
    }

    private fun end(context: AppCallContext): CallError? {
        val intent = context.end?.takeIf { context.state == AppCallState.ONGOING } ?: return unavailable()
        return send(context, intent, CallAction.END) {}
    }

    /**
     * Sends [intent] for [action] — with the background-start option only for `answer`. [sent] runs after the app
     * accepted the intent, before the notification's removal can reach the tracker.
     */
    private fun send(
        context: AppCallContext,
        intent: AppIntent,
        action: String,
        sent: () -> Unit,
    ): CallError? {
        val answer = action == CallAction.ANSWER
        if (!intent.send(backgroundStart = answer)) return unavailable()
        sent()
        trace.appCallIntentSent(context.callId, action, if (answer) CallTrace.DIRECT else CallTrace.PLAIN)
        return null
    }

    private fun unavailable() = CallError.appActionUnavailable()
}
