package app.handlive.android.feature.call.module

import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.call.CallAction
import app.handlive.android.core.protocol.call.CallActionRequest
import app.handlive.android.core.protocol.call.CallAudio
import app.handlive.android.core.protocol.call.CallRefusal
import app.handlive.android.feature.call.CallConstants
import app.handlive.android.feature.call.context.CallContext
import app.handlive.android.feature.call.context.CallTracker
import app.handlive.android.feature.call.context.PhoneState
import app.handlive.android.feature.connection.capability.AndroidPermissions
import kotlinx.serialization.json.JsonObject

/**
 * `call_event/action` over WebSocket (CALL-02 API 1, CALL-03 API 1): the checks in the order of the error table —
 * `FEATURE_DISABLED`, `BAD_REQUEST`, `CALL_HFP_REQUIRED`, `CALL_NOT_FOUND`, `PERMISSION_MISSING`,
 * `CALL_ACTION_NOT_ALLOWED` — then `acceptRingingCall()` or `endCall()`. The first valid command for a `call_id`
 * holds a 3 s lock; `reject` sets the "declined by HandLive" flag first. Runs on the A-CALL thread.
 */
class CallActions(
    private val access: CallAccess,
    private val telecom: CallTelecom,
    private val tracker: CallTracker,
    private val clock: () -> Long,
    /** `SecurityException` from Telecom: the capability goes out again with `can_answer = false` (API 2 logic 1). */
    private val onPermissionLost: () -> Unit = {},
) {
    private val locks = HashMap<String, Long>()

    /** Carries out [data] from a client ([mac] = a Mac); `null` means done (`ack {}`), otherwise the refusal. */
    fun perform(
        data: JsonObject,
        mac: Boolean,
    ): CallError? {
        val now = clock()
        locks.values.removeAll { now - it >= CallConstants.ACTION_LOCK_MILLIS }
        val request = decode(data)
        val context = tracker.current
        val refusal =
            when {
                !access.enabled() -> {
                    CallError.featureDisabled()
                }

                request == null -> {
                    CallError.badRequest("malformed call_event/action")
                }

                request.action in CallAction.HFP_ONLY -> {
                    CallError.hfpRequired(request.action)
                }

                context == null || context.callId != request.callId -> {
                    CallError.notFound()
                }

                !access.granted(AndroidPermissions.ANSWER_PHONE_CALLS) -> {
                    CallError.permissionMissing(AndroidPermissions.ANSWER_PHONE_CALLS)
                }

                else -> {
                    refusal(context, request.action, mac)
                }
            }
        return refusal ?: carryOut(checkNotNull(context), checkNotNull(request), now)
    }

    /** `CALL_ACTION_NOT_ALLOWED`: a waiting call, the wrong state, the 3 s lock, `answer` from iPhone or iPad. */
    private fun refusal(
        context: CallContext,
        action: String,
        mac: Boolean,
    ): CallError? {
        val phase = context.phoneState.phase
        val expected = if (action == CallAction.END) PhoneState.OFFHOOK else PhoneState.RINGING
        return when {
            context.waiting -> CallError.notAllowed(phase, CallRefusal.WAITING)
            context.phoneState != expected -> CallError.notAllowed(phase, CallRefusal.STATE)
            context.callId in locks -> CallError.notAllowed(phase, CallRefusal.STATE)
            action == CallAction.ANSWER && !mac -> CallError.notAllowed(phase, CallRefusal.PLATFORM)
            else -> null
        }
    }

    private fun carryOut(
        context: CallContext,
        request: CallActionRequest,
        now: Long,
    ): CallError? {
        locks[context.callId] = now
        return try {
            when (request.action) {
                CallAction.ANSWER -> {
                    tracker.requestAudio(request.audio ?: CallAudio.PHONE)
                    telecom.acceptRingingCall()
                    null
                }

                CallAction.REJECT -> {
                    tracker.declined(now)
                    endCall(context).also { refused -> if (refused != null) tracker.declined(null) }
                }

                else -> {
                    endCall(context)
                }
            }
        } catch (_: SecurityException) {
            tracker.declined(null)
            onPermissionLost()
            CallError.permissionMissing(AndroidPermissions.ANSWER_PHONE_CALLS)
        }
    }

    /** CALL-02 API 3 logic 2: `false` on an idle phone is `CALL_NOT_FOUND`, otherwise Telecom refused (E9). */
    private fun endCall(context: CallContext): CallError? =
        when {
            telecom.endCall() -> null
            telecom.phoneState() == PhoneState.IDLE -> CallError.notFound()
            else -> CallError.notAllowed(context.phoneState.phase, CallRefusal.SYSTEM)
        }

    private fun decode(data: JsonObject): CallActionRequest? =
        runCatching { ProtocolJson.decodeFromJsonElement(CallActionRequest.serializer(), data) }
            .getOrNull()
            ?.takeIf { it.callId.isNotBlank() && it.action in CallAction.ALL }
            ?.takeIf { it.audio == null || it.audio in CallAudio.ALL }
}
