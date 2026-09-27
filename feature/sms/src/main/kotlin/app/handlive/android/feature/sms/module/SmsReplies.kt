package app.handlive.android.feature.sms.module

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.core.transport.server.InboundEnvelope
import app.handlive.android.feature.connection.session.PeerSession
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A client got `PERMISSION_MISSING` (SET-01 field 17: the phone suggests granting the permission). */
fun interface PermissionMissingListener {
    fun onPermissionMissing(
        session: PeerSession,
        permission: String,
    )
}

/** The data of an `ack`, or the error it carries. */
sealed interface Reply {
    class Data(
        val data: JsonObject,
    ) : Reply

    class Error(
        val error: SmsError,
    ) : Reply
}

/**
 * How the SMS group answers a request: an `ack` built from a [Reply]; a failure of the provider becomes `INTERNAL`
 * (SMS-01 E6); `PERMISSION_MISSING` also asks the phone to suggest the permission. A session that closed meanwhile
 * gets nothing — the client retries on its next session. SMS errors never close a session (group 5 rules).
 */
class SmsReplies(
    private val permissionMissing: PermissionMissingListener,
) {
    suspend fun answer(
        session: PeerSession,
        envelope: InboundEnvelope,
        page: () -> Reply,
    ) {
        val reply =
            try {
                page()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") _: Exception,
            ) {
                // SMS-01 E6: the provider could not be read (SecurityException, SQLiteException…).
                Reply.Error(SmsError.internal())
            }
        when (reply) {
            is Reply.Data -> send(session, Ack.success(envelope.id, reply.data))
            is Reply.Error -> refuse(session, envelope.id, reply.error)
        }
    }

    suspend fun refuse(
        session: PeerSession,
        re: String,
        error: SmsError,
    ) {
        send(session, error.ack(re))
        if (error.code == ErrorCode.PERMISSION_MISSING) {
            (error.details?.get(DETAIL_PERMISSION) as? JsonPrimitive)?.contentOrNull?.let {
                permissionMissing.onPermissionMissing(session, it)
            }
        }
    }

    suspend fun send(
        session: PeerSession,
        ack: Ack,
    ) {
        try {
            session.sendAck(ack)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            // The session closed meanwhile.
        }
    }

    private companion object {
        const val DETAIL_PERMISSION = "permission"
    }
}
