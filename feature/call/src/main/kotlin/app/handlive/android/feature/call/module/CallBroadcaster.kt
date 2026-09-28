package app.handlive.android.feature.call.module

import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.core.protocol.call.CallLogNewData
import app.handlive.android.core.protocol.call.CallOp
import app.handlive.android.core.protocol.call.CallStateData
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import app.handlive.android.core.transport.capability.Feature
import app.handlive.android.feature.call.context.CallContext
import app.handlive.android.feature.connection.session.PeerSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/**
 * Sends the call events to the sessions with calls in effect (E1: no other session gets anything).
 * `call_event/state` (CALL-01 API 1 logic 3 and 4): each session gets its own envelope — `controls`,
 * `hfp_connected` and `audio_on` are computed for that client — whenever a field differs from the version it last
 * got; never an identical one. A new session gets the current call once calls are in effect on it (E8); an ended call
 * only goes to the sessions that saw it, and its correction (logic 5) once more. `call_event/log_new` goes to every
 * such session (CALL-04 API 2). A failed send (the session is closing) is dropped: events have no `ack`, and the
 * client catches up on its next session. Runs on the A-CALL thread.
 */
class CallBroadcaster(
    private val sessions: StateFlow<Map<String, PeerSession>>,
    /** `ANSWER_PHONE_CALLS` is granted right now. */
    private val canControl: () -> Boolean,
    private val audioOf: (PeerSession) -> ClientAudio = { ClientAudio.PHONE },
    private val trace: CallTrace = CallTrace.NONE,
) {
    private val lastSent = HashMap<PeerSession, CallStateData>()

    /** Sessions with calls in effect, by `pair_id`. */
    fun active(): Map<String, PeerSession> = sessions.value.filterValues { it.isEffective(Feature.CALL) }

    /**
     * [context] to every active session whose last version differs; an ended one only where it was seen. [change] =
     * the context itself changed; otherwise a session got calls in effect or the controls changed, and a session
     * without any version of the call gets the current one (E8).
     */
    suspend fun publish(
        context: CallContext?,
        change: Boolean,
    ) {
        val active = active().values.toSet()
        lastSent.keys.retainAll(active)
        if (context == null) return
        for (session in active) {
            val last = lastSent[session]
            if (context.ended && last?.callId != context.callId) continue
            val data = CallStateView.of(context, recipientOf(session), canControl())
            val newSession = !change && last?.callId != data.callId
            if (data != last && send(session, data, newSession)) lastSent[session] = data
        }
    }

    /** The state [context] would have for an iPhone or iPad (the `call_incoming` and flow A pushes, CONN-04). */
    fun forPush(context: CallContext): CallStateData =
        CallStateView.of(context, CallRecipient(mac = false), canControl())

    /** CALL-04 API 2: a new call log entry to every active session. */
    suspend fun logNew(new: CallLogNewData) {
        val plaintext = PlaintextCodec.encodeOp(CallOp.LOG_NEW, CallLogNewData.serializer(), new)
        active().values.forEach { send(it, plaintext) }
    }

    private fun recipientOf(session: PeerSession) =
        CallRecipient(mac = session.peerPlatform == PeerPlatform.MACOS, audio = audioOf(session))

    private suspend fun send(
        session: PeerSession,
        data: CallStateData,
        newSession: Boolean,
    ): Boolean {
        val id = send(session, PlaintextCodec.encodeOp(CallOp.STATE, CallStateData.serializer(), data))
        id?.let { trace.stateSent(data, it, session, newSession) }
        return id != null
    }

    /** The envelope `id`, or `null` when the session was closing. */
    private suspend fun send(
        session: PeerSession,
        plaintext: ByteArray,
    ): String? =
        try {
            session.send(MessageType.CALL_EVENT, plaintext)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            null
        }
}
