package app.handlive.android.feature.call.module

import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.core.protocol.call.CallLogSyncResponse
import app.handlive.android.core.transport.capability.Feature
import app.handlive.android.feature.call.appcall.AppCallServices
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
 * the client retries on its next session. Both requests are refused with `FEATURE_DISABLED` first when calls are not
 * in effect for the session that sent them (group 6 rules: off on either side, or `READ_PHONE_STATE` missing),
 * whatever other sessions allow.
 */
class CallRequests(
    private val services: CallServices,
    private val appCalls: AppCallServices,
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
        val error = guarded({ CallError.internal("The call could not be controlled") }) { route(session, callId, data) }
        reply(session, error?.ack(re) ?: Ack.success(re))
        services.trace.actionAckSent(callId, re, session, error?.code?.name)
        error?.let { suggest(session, it) }
    }

    /**
     * CALL-02 API 1 logic 7: the live app call of [callId] goes to CALL-05 API 2 — refused with `FEATURE_DISABLED`
     * when app calls are not in effect for [session] — every other `call_id` through the telephony checks, except that
     * a session with app calls in effect but calls not gets `CALL_NOT_FOUND` rather than `FEATURE_DISABLED`.
     */
    private fun route(
        session: PeerSession,
        callId: String,
        data: JsonObject,
    ): CallError? {
        val telephony = session.isEffective(Feature.CALL)
        val appCallsInEffect = session.isEffective(Feature.APP_CALLS)
        return when {
            appCalls.tracker.find(callId) != null -> {
                if (appCallsInEffect &&
                    appCalls.access.enabled()
                ) {
                    appCalls.actions.perform(data)
                } else {
                    CallError.notInEffect()
                }
            }

            !telephony && appCallsInEffect -> {
                appCalls.actions.perform(data)
            }

            !telephony -> {
                CallError.notInEffect()
            }

            else -> {
                services.actions.perform(data, mac = session.peerPlatform == PeerPlatform.MACOS)
            }
        }
    }

    suspend fun logSync(
        session: PeerSession,
        re: String,
        data: JsonObject,
    ) {
        // CALL-04 E6: the call log could not be read (SecurityException, SQLiteException…).
        val reply =
            if (!session.isEffective(Feature.CALL)) {
                LogSyncReply.Refused(CallError.notInEffect())
            } else {
                guarded({ LogSyncReply.Refused(CallError.internal()) }) { services.logs.requests.answer(data) }
            }
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
