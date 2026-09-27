package app.handlive.android.feature.call.module

import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.core.protocol.call.CallLogSyncResponse
import app.handlive.android.feature.call.log.LogSyncReply
import app.handlive.android.feature.connection.session.PeerSession
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** A client got `PERMISSION_MISSING` (SET-01 field 17: the phone suggests granting the permission). */
fun interface CallPermissionListener {
    fun onPermissionMissing(
        session: PeerSession,
        permission: String,
    )
}

/**
 * The `ack`s of the call requests: `call_event/action` (CALL-02 API 1, on the A-CALL thread) and
 * `call_event/log_sync` (CALL-04 API 1, on the IO pool). An unexpected failure becomes `INTERNAL`; a
 * `PERMISSION_MISSING` also asks the phone to suggest the permission. A session that closed meanwhile gets nothing —
 * the client retries on its next session.
 */
class CallRequests(
    private val services: CallServices,
    private val permissionMissing: () -> CallPermissionListener,
) {
    suspend fun action(
        session: PeerSession,
        re: String,
        data: JsonObject,
    ) {
        val callId = (data[CALL_ID] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val action = (data[ACTION] as? JsonPrimitive)?.contentOrNull.orEmpty()
        services.trace.actionReceived(callId, re, session, action)
        val error =
            guarded({ CallError.internal("The call could not be controlled") }) {
                services.actions.perform(data, mac = session.peerPlatform == PeerPlatform.MACOS)
            }
        reply(session, error?.ack(re) ?: Ack.success(re))
        services.trace.actionAckSent(callId, re, session, error?.code?.name)
        error?.let { suggest(session, it) }
    }

    suspend fun logSync(
        session: PeerSession,
        re: String,
        data: JsonObject,
    ) {
        // CALL-04 E6: the call log could not be read (SecurityException, SQLiteException…).
        val reply = guarded({ LogSyncReply.Refused(CallError.internal()) }) { services.logs.requests.answer(data) }
        when (reply) {
            is LogSyncReply.Page -> {
                reply(session, Ack.success(re, json(reply.page)))
            }

            is LogSyncReply.Refused -> {
                reply(session, reply.error.ack(re))
                suggest(session, reply.error)
            }
        }
    }

    private fun suggest(
        session: PeerSession,
        error: CallError,
    ) {
        if (error.code != ErrorCode.PERMISSION_MISSING) return
        error.permission?.let { permissionMissing().onPermissionMissing(session, it) }
    }

    private suspend fun reply(
        session: PeerSession,
        ack: Ack,
    ) {
        guarded({ }) { session.sendAck(ack) }
    }

    private companion object {
        const val CALL_ID = "call_id"
        const val ACTION = "action"

        fun json(page: CallLogSyncResponse): JsonObject =
            ProtocolJson.encodeToJsonElement(CallLogSyncResponse.serializer(), page).jsonObject

        /** [block], or [fallback] when it fails in any way but cancellation. */
        inline fun <T> guarded(
            fallback: () -> T,
            block: () -> T,
        ): T =
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") _: Exception,
            ) {
                fallback()
            }
    }
}
