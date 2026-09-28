package app.handlive.android.feature.sms.module

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.feature.connection.capability.AndroidPermissions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * An error `ack` of the SMS group (0.8.1): the code, an English diagnostic for logs (never shown, 0.12.4) and the
 * `details` the spec asks for. Carries no message content or phone number.
 */
class SmsError(
    val code: ErrorCode,
    val message: String,
    val details: JsonObject? = null,
) {
    fun ack(re: String): Ack = Ack.failure(re, code, message, details)

    companion object {
        fun featureDisabled() = SmsError(ErrorCode.FEATURE_DISABLED, "SMS is turned off on the phone")

        /** SMS is off on the client that asked (its latest `capability`), group 5 rules. */
        fun notInEffect() = SmsError(ErrorCode.FEATURE_DISABLED, "SMS is not in effect for this session")

        /** SMS-01 E2, SMS-04 E3: `details.permission` is the full name, `android.permission.READ_SMS`. */
        fun permissionMissing(shortName: String) =
            SmsError(
                ErrorCode.PERMISSION_MISSING,
                "Missing Android permission",
                buildJsonObject { put("permission", AndroidPermissions.fullName(shortName)) },
            )

        fun badRequest(message: String) = SmsError(ErrorCode.BAD_REQUEST, message)

        /** SMS-01 E5: `details.reason` ∈ {`cursor`, `page_token`}. */
        fun cursorInvalid(reason: String) =
            SmsError(
                ErrorCode.SMS_CURSOR_INVALID,
                "Sync position cannot be read",
                buildJsonObject { put("reason", reason) },
            )

        fun threadNotFound() = SmsError(ErrorCode.SMS_THREAD_NOT_FOUND, "Conversation no longer exists on the phone")

        fun tooLarge() = SmsError(ErrorCode.PAYLOAD_TOO_LARGE, "Message is longer than 1600 characters")

        fun invalidAddress() = SmsError(ErrorCode.SMS_INVALID_ADDRESS, "Recipient is not a valid number")

        /** SMS-04 E6: `details.sims` = the `sub_id` values that are valid. */
        fun simUnavailable(validSims: List<Int>) =
            SmsError(
                ErrorCode.SMS_SIM_UNAVAILABLE,
                "Selected SIM is not active",
                buildJsonObject { putJsonArray("sims") { validSims.forEach { add(JsonPrimitive(it)) } } },
            )

        fun internal() = SmsError(ErrorCode.INTERNAL, "Could not read the SMS provider")

        /** SMS-04 E11: over `SMS_SEND_LIMIT`; `details.retry_after_ms` = the wait until a send is allowed again. */
        fun rateLimited(retryAfterMillis: Long) =
            SmsError(
                ErrorCode.RATE_LIMITED,
                "Sending limit reached",
                buildJsonObject { put("retry_after_ms", retryAfterMillis) },
            )

        const val REASON_CURSOR = "cursor"
        const val REASON_PAGE_TOKEN = "page_token"
    }
}
