package app.handlive.android.feature.call.log

import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.call.CallLogSyncRequest
import app.handlive.android.core.protocol.call.CallLogSyncResponse
import app.handlive.android.feature.call.CallConstants
import app.handlive.android.feature.call.module.CallAccess
import app.handlive.android.feature.call.module.CallError
import app.handlive.android.feature.connection.capability.AndroidPermissions
import kotlinx.serialization.json.JsonObject

/** The page of a `log_sync`, or the error it is refused with. */
sealed interface LogSyncReply {
    class Page(
        val page: CallLogSyncResponse,
    ) : LogSyncReply

    class Refused(
        val error: CallError,
    ) : LogSyncReply
}

/**
 * `call_event/log_sync` (CALL-04 API 1 logic 1): checked in the order `feature.call` → `READ_CALL_LOG` → `limit` →
 * the cursor (a cursor that cannot be used is no error but a first sync with `reset`, E5). A provider failure is
 * thrown to the caller, which answers `INTERNAL` (E6).
 */
class CallLogRequests(
    private val access: CallAccess,
    private val engine: CallLogSyncEngine,
) {
    fun answer(data: JsonObject): LogSyncReply {
        val request =
            runCatching { ProtocolJson.decodeFromJsonElement(CallLogSyncRequest.serializer(), data) }.getOrNull()
        val refusal =
            when {
                !access.enabled() -> {
                    CallError.featureDisabled()
                }

                !access.granted(AndroidPermissions.READ_CALL_LOG) -> {
                    CallError.permissionMissing(AndroidPermissions.READ_CALL_LOG)
                }

                request == null || request.limit !in 1..CallConstants.SYNC_LIMIT_MAX -> {
                    CallError.badRequest("log_sync limit out of range")
                }

                else -> {
                    null
                }
            }
        return refusal?.let(LogSyncReply::Refused)
            ?: LogSyncReply.Page(engine.page(checkNotNull(request).cursor, request.limit))
    }
}
