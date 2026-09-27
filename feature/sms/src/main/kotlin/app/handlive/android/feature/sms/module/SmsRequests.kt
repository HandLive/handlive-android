package app.handlive.android.feature.sms.module

import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.sms.SmsHistoryRequest
import app.handlive.android.core.protocol.sms.SmsSyncRequest
import app.handlive.android.feature.connection.capability.AndroidPermissions
import app.handlive.android.feature.sms.SmsConstants
import app.handlive.android.feature.sms.sync.SyncPageRequest
import app.handlive.android.feature.sms.sync.SyncTokens
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject

/** A request either checked and decoded, or refused with the `ack` error the spec names. */
sealed interface Checked<out T> {
    class Valid<T>(
        val value: T,
    ) : Checked<T>

    class Refused(
        val error: SmsError,
    ) : Checked<Nothing>
}

/** A checked `sms/history` request (SMS-03 API 1). */
class HistoryPageRequest(
    val threadId: Long,
    val beforeTs: Long,
    val limit: Int,
)

/**
 * The checks of the SMS requests in the order the specs give: SMS-01 API 1 logic 1 (`feature.sms` → `READ_SMS` →
 * parameter limits → `cursor`, then `page_token`) and SMS-03 API 1 logic 1 (`feature.sms` → `READ_SMS` →
 * parameters; the conversation's existence is checked by the caller against the provider).
 */
class SmsRequests(
    private val access: SmsAccess,
) {
    fun sync(data: JsonObject): Checked<SyncPageRequest> {
        val request = decode(data, SmsSyncRequest.serializer())
        val cursor = request?.cursor?.let(SyncTokens::decodeCursor)
        val token = request?.pageToken?.let { SyncTokens.decodePageToken(it, catchUp = request.cursor != null) }
        val error =
            readAccess() ?: when {
                request == null -> {
                    SmsError.badRequest("malformed sms/sync")
                }

                request.threadLimit !in 1..SmsConstants.SYNC_THREADS_MAX ||
                    request.perThreadLimit !in 1..SmsConstants.SYNC_PER_THREAD_MAX -> {
                    SmsError.badRequest("sms/sync limit out of range")
                }

                request.cursor != null && cursor == null -> {
                    SmsError.cursorInvalid(SmsError.REASON_CURSOR)
                }

                request.pageToken != null && token == null -> {
                    SmsError.cursorInvalid(SmsError.REASON_PAGE_TOKEN)
                }

                else -> {
                    null
                }
            }
        return if (error != null) {
            Checked.Refused(error)
        } else {
            checkNotNull(
                request,
            ).let { Checked.Valid(SyncPageRequest(it.threadLimit, it.perThreadLimit, cursor, token)) }
        }
    }

    fun history(data: JsonObject): Checked<HistoryPageRequest> {
        val request = decode(data, SmsHistoryRequest.serializer())
        val error =
            readAccess() ?: when {
                request == null -> {
                    SmsError.badRequest("malformed sms/history")
                }

                request.limit !in 1..SmsConstants.HISTORY_LIMIT_MAX || request.beforeTs < 0 -> {
                    SmsError.badRequest("sms/history parameter out of range")
                }

                else -> {
                    null
                }
            }
        return if (error != null) {
            Checked.Refused(error)
        } else {
            checkNotNull(request).let { Checked.Valid(HistoryPageRequest(it.threadId, it.beforeTs, it.limit)) }
        }
    }

    /** E1 then E2 of SMS-01 (and SMS-03 E5). */
    private fun readAccess(): SmsError? =
        when {
            !access.enabled() -> SmsError.featureDisabled()
            !access.granted(AndroidPermissions.READ_SMS) -> SmsError.permissionMissing(AndroidPermissions.READ_SMS)
            else -> null
        }

    private fun <T> decode(
        data: JsonObject,
        serializer: KSerializer<T>,
    ): T? = runCatching { ProtocolJson.decodeFromJsonElement(serializer, data) }.getOrNull()
}
