package app.handlive.android.feature.call.appcall

import app.handlive.android.core.protocol.call.AppCallAnswerMode
import app.handlive.android.core.protocol.call.AppCallData
import app.handlive.android.core.protocol.call.CallOp
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import app.handlive.android.core.transport.capability.Feature
import app.handlive.android.feature.call.module.CallTrace
import app.handlive.android.feature.connection.session.PeerSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/**
 * Sends `call_event/app_call` (CALL-05, S→C, no `ack`) to the sessions with app calls in effect and to no other: on
 * every change of a call a session has not got yet in that form — never an identical message twice —, `ended` once and
 * only to the sessions that saw the call. A session that gets app calls in effect gets the current calls through
 * [publish] with all of them. A failed send (the session is closing) is dropped: the client catches up on its next
 * session. Runs on the A-CALL thread.
 */
class AppCallBroadcaster(
    private val sessions: StateFlow<Map<String, PeerSession>>,
    private val exemption: BackgroundStartExemption,
    private val trace: CallTrace = CallTrace.NONE,
) {
    private val lastSent = HashMap<PeerSession, HashMap<String, AppCallData>>()

    fun active(): List<PeerSession> = sessions.value.values.filter { it.isEffective(Feature.APP_CALLS) }

    /**
     * [contexts] to every active session where they differ from what it last got. [change] = the contexts themselves
     * changed; otherwise a session got app calls in effect or the answer mode changed, and a call a session never got
     * goes to it as the current one (`reason=session` in the bench trace).
     */
    suspend fun publish(
        contexts: Collection<AppCallContext>,
        change: Boolean,
    ) {
        val active = active()
        lastSent.keys.retainAll(active.toSet())
        val mode = if (exemption.held()) AppCallAnswerMode.DIRECT else AppCallAnswerMode.TAP
        for (session in active) publishTo(session, contexts, mode, change)
    }

    private suspend fun publishTo(
        session: PeerSession,
        contexts: Collection<AppCallContext>,
        mode: String,
        change: Boolean,
    ) {
        val seen = lastSent.getOrPut(session) { HashMap() }
        // An ended call goes only to a session that saw it; never the version a session already has.
        val due =
            contexts
                .filter { !it.ended || it.callId in seen }
                .map { it to AppCallView.of(it, mode) }
                .filter { (context, data) -> seen[context.callId] != data }
        for ((context, data) in due) {
            val envId = send(session, data) ?: continue
            trace.appCallSent(data, envId, session, newSession = !change && context.callId !in seen)
            if (context.ended) seen.remove(context.callId) else seen[context.callId] = data
        }
    }

    /** The envelope `id`, or `null` when the session was closing. */
    private suspend fun send(
        session: PeerSession,
        data: AppCallData,
    ): String? =
        try {
            session.send(
                MessageType.CALL_EVENT,
                PlaintextCodec.encodeOp(CallOp.APP_CALL, AppCallData.serializer(), data),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            null
        }
}
