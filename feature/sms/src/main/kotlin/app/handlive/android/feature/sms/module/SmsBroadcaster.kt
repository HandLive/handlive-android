package app.handlive.android.feature.sms.module

import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import app.handlive.android.core.protocol.sms.SmsNewData
import app.handlive.android.core.protocol.sms.SmsOp
import app.handlive.android.core.protocol.sms.SmsReadChangedData
import app.handlive.android.core.protocol.sms.SmsStatusData
import app.handlive.android.core.transport.capability.Feature
import app.handlive.android.feature.connection.session.PeerSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/**
 * Sends the SMS events (`sms/new`, `sms/status`, `sms/read_changed`) to the clients whose session has SMS active.
 * Each session gets its own envelope, encrypted with its keys (SMS-02 API 1 logic 1). A failed send (the session is
 * closing) is dropped: events have no `ack`, and a client that missed one catches up through SMS-01.
 */
class SmsBroadcaster(
    private val sessions: StateFlow<Map<String, PeerSession>>,
) {
    /** Sessions with SMS active, by `pair_id`. */
    fun active(): Map<String, PeerSession> = sessions.value.filterValues { it.isEffective(Feature.SMS) }

    /**
     * `sms/new` to every active session; the one of [creatorPairId] gets [withLocalId] (the message carries its
     * `local_id`, SMS-04 API 4), the others [message] without it. Returns the pairs it reached.
     */
    suspend fun newMessage(
        message: SmsNewData,
        creatorPairId: String? = null,
        withLocalId: SmsNewData? = null,
    ): Set<String> {
        val reached = mutableSetOf<String>()
        for ((pairId, session) in active()) {
            val data = if (pairId == creatorPairId && withLocalId != null) withLocalId else message
            if (send(session, PlaintextCodec.encodeOp(SmsOp.NEW, SmsNewData.serializer(), data))) {
                reached += pairId
            }
        }
        return reached
    }

    suspend fun readChanged(change: SmsReadChangedData) {
        val plaintext = PlaintextCodec.encodeOp(SmsOp.READ_CHANGED, SmsReadChangedData.serializer(), change)
        active().values.forEach { send(it, plaintext) }
    }

    /** `sms/status` only to the pair that sent the message (SMS-04 API 2 logic 3); `false` when it is not connected. */
    suspend fun status(
        pairId: String,
        status: SmsStatusData,
    ): Boolean {
        val session = active()[pairId] ?: return false
        return send(session, PlaintextCodec.encodeOp(SmsOp.STATUS, SmsStatusData.serializer(), status))
    }

    suspend fun status(
        session: PeerSession,
        status: SmsStatusData,
    ): Boolean = send(session, PlaintextCodec.encodeOp(SmsOp.STATUS, SmsStatusData.serializer(), status))

    private suspend fun send(
        session: PeerSession,
        plaintext: ByteArray,
    ): Boolean =
        try {
            session.send(MessageType.SMS, plaintext)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            false
        }
}
