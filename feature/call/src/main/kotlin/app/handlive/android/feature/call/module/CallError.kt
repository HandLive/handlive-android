package app.handlive.android.feature.call.module

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.feature.connection.capability.AndroidPermissions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * An error `ack` of the call group (0.8.1): the code, an English diagnostic for logs (never shown, 0.12.4) and the
 * `details` the spec asks for. Carries no number, name or DTMF key.
 */
class CallError(
    val code: ErrorCode,
    val message: String,
    val details: JsonObject? = null,
) {
    fun ack(re: String): Ack = Ack.failure(re, code, message, details)

    /** `details.permission` of a `PERMISSION_MISSING` (full name), for the suggestion of SET-01 field 17. */
    val permission: String?
        get() = (details?.get(DETAIL_PERMISSION) as? JsonPrimitive)?.contentOrNull

    companion object {
        private const val DETAIL_PERMISSION = "permission"

        fun featureDisabled() = CallError(ErrorCode.FEATURE_DISABLED, "Calls are turned off on the phone")

        /** Calls are not in effect for the session that asked (off on either side, or `READ_PHONE_STATE` missing). */
        fun notInEffect() = CallError(ErrorCode.FEATURE_DISABLED, "Calls are not in effect for this session")

        fun badRequest(message: String) = CallError(ErrorCode.BAD_REQUEST, message)

        /** CALL-03 E2: `details.action` = the action sent. */
        fun hfpRequired(action: String) =
            CallError(
                ErrorCode.CALL_HFP_REQUIRED,
                "This action requires Bluetooth HFP",
                buildJsonObject { put("action", action) },
            )

        fun notFound() = CallError(ErrorCode.CALL_NOT_FOUND, "No such call on the phone")

        /** CALL-02 E3, CALL-04 E2: `details.permission` is the full name, `android.permission.ANSWER_PHONE_CALLS`. */
        fun permissionMissing(shortName: String) =
            CallError(
                ErrorCode.PERMISSION_MISSING,
                "Missing Android permission",
                buildJsonObject { put(DETAIL_PERMISSION, AndroidPermissions.fullName(shortName)) },
            )

        /** CALL-02 E2: `details.state` = the current state, `details.reason` ∈ {state, waiting, platform, system}. */
        fun notAllowed(
            state: String,
            reason: String,
        ) = CallError(
            ErrorCode.CALL_ACTION_NOT_ALLOWED,
            "The call does not allow this action now",
            buildJsonObject {
                put("state", state)
                put("reason", reason)
            },
        )

        /** CALL-04 E6: the call log could not be read; or Telecom failed in an unexpected way. */
        fun internal(message: String = "Could not read the call log") = CallError(ErrorCode.INTERNAL, message)
    }
}
