package app.handlive.android.feature.call.module

import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.core.protocol.call.CallLogSyncResponse
import app.handlive.android.core.protocol.call.CallOp
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import app.handlive.android.core.transport.capability.Feature
import app.handlive.android.feature.call.context.BroadcastCopy
import app.handlive.android.feature.call.context.PhoneState
import app.handlive.android.feature.call.context.SimReport
import app.handlive.android.feature.call.log.LogSyncReply
import app.handlive.android.feature.connection.session.EnvelopeHandler
import app.handlive.android.feature.connection.session.PeerSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * A-CALL (`CallModule` of 06-call-control): answers `call_event/action` and `call_event/log_sync` from the clients
 * ([handler]) and runs the call events. Everything that touches the call context — the listeners' reports, the
 * broadcast copies, the actions, the call log rounds — goes through one queue on the A-CALL thread and is processed
 * to its end before the next (API 1 logic 1), so the clients never get an older state after a newer one. `log_sync`
 * pages read the provider on [reads]. Call errors never close a session, never stop A-SVC and never reach other
 * features: every failure becomes an error `ack` or is dropped (group 6 rules).
 */
class CallModule(
    worker: CoroutineDispatcher,
    private val reads: CoroutineDispatcher,
    private val sessions: StateFlow<Map<String, PeerSession>>,
    private val services: CallServices,
    clock: () -> Long,
) {
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /** Clients without a session (CONN-03, CONN-04): installed by the relay feature. */
    @Volatile
    var offline: OfflineCallDelivery = OfflineCallDelivery.NONE

    /** SET-01 field 17: the suggestion when a client needs a permission this phone lacks. */
    @Volatile
    var permissionMissing: CallPermissionListener = CallPermissionListener { _, _ -> }

    private val events = CallEvents(services, { sessions.value.keys }, ::later, { offline }, clock)
    private val requests = CallRequests(services) { permissionMissing }

    val handler =
        EnvelopeHandler { session, envelope ->
            val payload = runCatching { PlaintextCodec.decodePayload(envelope.plaintext) }.getOrNull()
            when (payload?.op) {
                CallOp.ACTION -> post { requests.action(session, envelope.id, payload.data) }

                CallOp.LOG_SYNC -> scope.launch(reads) { requests.logSync(session, envelope.id, payload.data) }

                // `state`, `log_new`, `hfp_status` go the other way; an unknown op is treated as an event (0.5.1).
                else -> Unit
            }
        }

    /** Starts the queue, and sends the current call to every session on which calls become effective (E8). */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        scope.launch { for (task in queue) runSafely(task) }
        sessions
            .flatMapLatest { open ->
                if (open.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(open.values.map { session -> session.effectiveFeatures.map { session to it } }) { all ->
                        all.filter { (_, features) -> Feature.CALL in features }.map { (session, _) -> session }
                    }
                }
            }.distinctUntilChanged()
            .onEach { post { events.republish() } }
            .launchIn(scope)
    }

    /** The default listener (CALL-01 API 2); [at] is the wall clock of the callback. */
    fun onPhoneState(
        state: PhoneState,
        at: Long,
    ) = post { events.phoneState(state, at) }

    fun onSimState(report: SimReport) = post { events.simState(report) }

    fun onBroadcast(copy: BroadcastCopy) = post { events.broadcast(copy) }

    /** The listeners were registered (the first report is the current state, E8) or removed. */
    fun onListening() = post { events.reset() }

    /** The call log observer was registered or removed (CALL-04 API 3). */
    fun watchLog(on: Boolean) =
        post {
            if (on) services.logs.watcher.start() else services.logs.watcher.stop()
        }

    /** A coalesced `onChange` of the call log (API 3 logic 2). */
    fun onLogChanged() = post { events.logRound() }

    /** The permissions changed: `controls` follow `ANSWER_PHONE_CALLS` (SET-01 step 14). */
    fun onPermissionsChanged() = post { events.republish() }

    private fun post(task: suspend () -> Unit) {
        queue.trySend(task)
    }

    private fun later(
        delayMillis: Long,
        task: suspend () -> Unit,
    ): Job =
        scope.launch {
            delay(delayMillis)
            post(task)
        }

    private companion object {
        /** Every failure of a task is caught: the next event starts from a consistent context (group 6 rules). */
        suspend fun runSafely(block: suspend () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") _: Exception,
            ) {
                // Provider, Telecom or session error: dropped, the call group goes on.
            }
        }
    }
}
