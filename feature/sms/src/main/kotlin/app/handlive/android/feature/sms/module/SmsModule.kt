package app.handlive.android.feature.sms.module

import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import app.handlive.android.core.protocol.sms.SmsHistoryResponse
import app.handlive.android.core.protocol.sms.SmsOp
import app.handlive.android.core.protocol.sms.SmsSendAckData
import app.handlive.android.core.protocol.sms.SmsStatusData
import app.handlive.android.core.protocol.sms.SmsSyncResponse
import app.handlive.android.core.transport.capability.Feature
import app.handlive.android.core.transport.server.InboundEnvelope
import app.handlive.android.feature.connection.session.EnvelopeHandler
import app.handlive.android.feature.connection.session.PeerSession
import app.handlive.android.feature.sms.send.DeliveryReport
import app.handlive.android.feature.sms.send.SendOutcome
import app.handlive.android.feature.sms.send.SendRegistry
import app.handlive.android.feature.sms.send.SmsSendPipeline
import app.handlive.android.feature.sms.sync.SmsHistoryEngine
import app.handlive.android.feature.sms.sync.SmsSyncEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** What [SmsModule] works with: the request checks, the two page engines, sending and its registry, the events out. */
class SmsServices(
    val requests: SmsRequests,
    val sync: SmsSyncEngine,
    val history: SmsHistoryEngine,
    val sender: SmsSendPipeline,
    val registry: SendRegistry,
    val broadcaster: SmsBroadcaster,
)

/**
 * A-SMS (`SmsModule` of 05-sms): answers `sms/sync`, `sms/history` and `sms/send` from the clients ([handler]),
 * sends `sms/status` for the messages it sends, and re-broadcasts the status of every message a pair sent when that
 * pair gets a new session (SMS-04 API 1 logic 6). Provider reads run on [reads] so one session's sync never delays
 * the other messages of that session; the send state runs on the serial [worker]. SMS errors never close a session
 * and never reach other features: every failure becomes an error `ack` (group 5 general rules).
 */
class SmsModule(
    private val worker: CoroutineDispatcher,
    private val reads: CoroutineDispatcher,
    private val sessions: StateFlow<Map<String, PeerSession>>,
    private val services: SmsServices,
    permissionMissing: PermissionMissingListener = PermissionMissingListener { _, _ -> },
) {
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private val replies = SmsReplies(permissionMissing)
    private val announced = mutableSetOf<PeerSession>()

    val handler =
        EnvelopeHandler { session, envelope ->
            val payload = runCatching { PlaintextCodec.decodePayload(envelope.plaintext) }.getOrNull()
            when (payload?.op) {
                SmsOp.SYNC -> scope.launch(reads) { replies.answer(session, envelope) { syncPage(payload.data) } }

                SmsOp.HISTORY -> scope.launch(reads) { replies.answer(session, envelope) { historyPage(payload.data) } }

                SmsOp.SEND -> scope.launch { send(session, envelope, payload.data) }

                // `new`, `status`, `read_changed` go the other way; an unknown op is treated as an event (0.5.1).
                else -> Unit
            }
        }

    /** Starts re-broadcasting send statuses to sessions on which SMS becomes active. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        sessions
            .flatMapLatest { open ->
                if (open.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(open.values.map { session -> session.effectiveFeatures.map { session to it } }) {
                        it.filter { (_, features) -> Feature.SMS in features }.map { (session, _) -> session }
                    }
                }
            }.onEach { active -> rebroadcast(active) }
            .launchIn(scope)
    }

    /** A "sent" result from the radio (SMS-04 API 3). */
    fun onSent(
        localId: String,
        index: Int,
        resultCode: Int,
    ) {
        scope.launch { services.sender.onSent(localId, index, resultCode)?.let { status(it) } }
    }

    /** A delivery report from the radio (SMS-04 API 3 logic 5). */
    fun onDelivered(
        localId: String,
        index: Int,
        report: DeliveryReport,
    ) {
        scope.launch { services.sender.onDelivered(localId, index, report)?.let { status(it) } }
    }

    private fun syncPage(data: JsonObject): Reply =
        when (val checked = services.requests.sync(data)) {
            is Checked.Refused -> Reply.Error(checked.error)
            is Checked.Valid -> Reply.Data(json(SmsSyncResponse.serializer(), services.sync.page(checked.value)))
        }

    private fun historyPage(data: JsonObject): Reply =
        when (val checked = services.requests.history(data)) {
            is Checked.Refused -> {
                Reply.Error(checked.error)
            }

            is Checked.Valid -> {
                val request = checked.value
                if (services.history.exists(request.threadId)) {
                    val page = services.history.page(request.threadId, request.beforeTs, request.limit)
                    Reply.Data(json(SmsHistoryResponse.serializer(), page))
                } else {
                    Reply.Error(SmsError.threadNotFound())
                }
            }
        }

    /** SMS-04 steps 6–8: the `ack` first, then the radio (API 1 logic 1). */
    private suspend fun send(
        session: PeerSession,
        envelope: InboundEnvelope,
        data: JsonObject,
    ) {
        when (val outcome = services.sender.accept(session.pairId, data)) {
            is SendOutcome.Refused -> {
                replies.refuse(session, envelope.id, outcome.error)
            }

            is SendOutcome.Accepted -> {
                val entry = outcome.entry
                val ack = SmsSendAckData(accepted = true, parts = entry.parts)
                replies.send(session, Ack.success(envelope.id, json(SmsSendAckData.serializer(), ack)))
                if (outcome.duplicate) {
                    // Logic 4: a retry is not sent again; the client gets the current status once more.
                    entry.statusData?.let { services.broadcaster.status(session, it) }
                } else {
                    services.sender.dispatch(entry)?.let { status(it) }
                }
            }
        }
    }

    /** Only to the pair that sent the message; a pair without a session gets it on its next one (logic 6). */
    private suspend fun status(status: SmsStatusData) {
        val pairId = services.registry.find(status.localId)?.pairId ?: return
        services.broadcaster.status(pairId, status)
    }

    /** Every status of a pair's messages, once per session on which SMS is active (SMS-04 API 1 logic 6). */
    private suspend fun rebroadcast(active: List<PeerSession>) {
        announced.retainAll(sessions.value.values.toSet())
        for (session in active.filter { announced.add(it) }) {
            services.registry
                .ofPair(session.pairId)
                .mapNotNull { it.statusData }
                .forEach { services.broadcaster.status(session, it) }
        }
    }

    private companion object {
        fun <T> json(
            serializer: KSerializer<T>,
            value: T,
        ): JsonObject = ProtocolJson.encodeToJsonElement(serializer, value).jsonObject
    }
}
