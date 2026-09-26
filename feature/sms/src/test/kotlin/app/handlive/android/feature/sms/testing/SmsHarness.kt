package app.handlive.android.feature.sms.testing

import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.core.protocol.capability.CapabilityData
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import app.handlive.android.core.protocol.id.UuidV7Generator
import app.handlive.android.core.protocol.sms.SmsNewData
import app.handlive.android.core.protocol.sms.SmsOp
import app.handlive.android.core.protocol.sms.SmsReadChangedData
import app.handlive.android.core.protocol.sms.SmsStatusData
import app.handlive.android.core.transport.capability.Feature
import app.handlive.android.core.transport.server.InboundEnvelope
import app.handlive.android.feature.connection.capability.SimCard
import app.handlive.android.feature.connection.session.PeerSession
import app.handlive.android.feature.connection.session.SessionRouter
import app.handlive.android.feature.sms.module.FakeAccess
import app.handlive.android.feature.sms.module.OfflineSmsDelivery
import app.handlive.android.feature.sms.module.SmsBroadcaster
import app.handlive.android.feature.sms.module.SmsEvents
import app.handlive.android.feature.sms.module.SmsModule
import app.handlive.android.feature.sms.module.SmsRequests
import app.handlive.android.feature.sms.module.SmsServices
import app.handlive.android.feature.sms.observe.NewMessageScanner
import app.handlive.android.feature.sms.observe.ObserverState
import app.handlive.android.feature.sms.observe.ReadStateTracker
import app.handlive.android.feature.sms.send.SendRegistry
import app.handlive.android.feature.sms.send.SimChoices
import app.handlive.android.feature.sms.send.SmsRadio
import app.handlive.android.feature.sms.send.SmsSendPipeline
import app.handlive.android.feature.sms.sync.SmsHistoryEngine
import app.handlive.android.feature.sms.sync.SmsSyncEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** `SmsManager` that records every send; [divide] splits like GSM-7 (153) or UCS-2 (67) multipart messages. */
class FakeRadio : SmsRadio {
    class Sent(
        val subId: Int?,
        val destination: String,
        val parts: List<String>,
        val localId: String,
    )

    val sent = mutableListOf<Sent>()
    var failing = false

    override fun divide(
        subId: Int?,
        body: String,
    ): List<String> {
        val single = if (body.all { it.code < ASCII }) GSM_SINGLE else UCS2_SINGLE
        val part = if (body.all { it.code < ASCII }) GSM_PART else UCS2_PART
        return if (body.length <= single) listOf(body) else body.chunked(part)
    }

    override fun send(
        subId: Int?,
        destination: String,
        parts: List<String>,
        localId: String,
    ) {
        check(!failing) { "radio refused" }
        sent += Sent(subId, destination, parts, localId)
    }

    private companion object {
        const val ASCII = 128
        const val GSM_SINGLE = 160
        const val GSM_PART = 153
        const val UCS2_SINGLE = 70
        const val UCS2_PART = 67
    }
}

/** Two Vietnamese SIMs by default, the first one the default SMS SIM; `active = null` = no `READ_PHONE_STATE`. */
class FakeSims : SimChoices {
    var active: List<SimCard>? = listOf(SimCard(1, 0, "SIM 1", "VN"), SimCard(2, 1, "SIM 2", "VN"))
    var default: Int? = 1

    override fun active() = active

    override fun defaultSmsSubId() = default

    override fun countryIso(subId: Int?) = "VN"
}

class MemoryObserverState : ObserverState {
    var mark: Long? = null
    var writes = 0

    override suspend fun lastSmsId() = mark

    override suspend fun write(lastSmsId: Long) {
        mark = lastSmsId
        writes++
    }
}

/** A connected client as the phone sees it: everything Android sends it, decoded. */
class FakeClient(
    val name: String,
    val pairId: String,
    clock: () -> Long,
    platform: PeerPlatform = PeerPlatform.MACOS,
) {
    class Sent(
        val type: MessageType,
        val plaintext: ByteArray,
    )

    val sent = mutableListOf<Sent>()
    val effective = MutableStateFlow(setOf(Feature.SMS))
    var session = newSession(clock, platform)
        private set

    private fun newSession(
        clock: () -> Long,
        platform: PeerPlatform,
    ) = PeerSession(
        PeerSession.PeerInfo(pairId, "$pairId-device", name, platform),
        PeerSession.Channel.LAN,
        effective,
        MutableStateFlow<CapabilityData?>(null),
        { type, plaintext, _ -> sent += Sent(type, plaintext) },
        clock,
    )

    /** A new `/v1/ctl` session of the same pair (CONN-02 reconnect, CONN-03 switch). */
    fun reconnect(clock: () -> Long) {
        session = newSession(clock, session.peerPlatform)
    }

    fun acks(): List<Ack> = sent.filter { it.type == MessageType.ACK }.map { PlaintextCodec.decodeAck(it.plaintext) }

    fun statuses(): List<SmsStatusData> = ops(SmsOp.STATUS, SmsStatusData.serializer())

    fun news(): List<SmsNewData> = ops(SmsOp.NEW, SmsNewData.serializer())

    fun readChanges(): List<SmsReadChangedData> = ops(SmsOp.READ_CHANGED, SmsReadChangedData.serializer())

    private fun <T> ops(
        op: String,
        serializer: KSerializer<T>,
    ): List<T> =
        sent
            .filter { it.type == MessageType.SMS && PlaintextCodec.decodePayload(it.plaintext).op == op }
            .map { PlaintextCodec.decodeOp(it.plaintext, serializer).data }
}

/** The SMS module on the test scheduler with the fake provider, radio and SIMs, reached through a router. */
class SmsHarness(
    private val scope: TestScope,
) {
    val wall = { BASE_TS + scope.testScheduler.currentTime }
    val provider = FakeSmsProvider()
    val access = FakeAccess()
    val sims = FakeSims()
    val radio = FakeRadio()
    val state = MemoryObserverState()
    val sessions = MutableStateFlow<Map<String, PeerSession>>(emptyMap())
    val offline = mutableListOf<Pair<SmsNewData, Set<String>>>()
    val permissionsAsked = mutableListOf<Pair<String, String>>()
    private val dispatcher = StandardTestDispatcher(scope.testScheduler)
    private val ids = UuidV7Generator(wall)
    private val objects = objectsOf(provider)
    val registry = SendRegistry(wall)
    val sender = SmsSendPipeline(access, sims, FakeNumbers(), radio, registry, wall)
    private val broadcaster = SmsBroadcaster(sessions)
    private val services =
        SmsServices(
            SmsRequests(access),
            SmsSyncEngine(provider, objects),
            SmsHistoryEngine(provider, objects),
            sender,
            registry,
            broadcaster,
        )
    val module =
        SmsModule(dispatcher, dispatcher, sessions, services) { session, permission ->
            permissionsAsked += session.pairId to permission
        }
    val events =
        SmsEvents(
            NewMessageScanner(provider, state, wall),
            ReadStateTracker(wall),
            objects,
            registry,
            broadcaster,
            OfflineSmsDelivery { message, reached -> offline += message to reached },
        )
    private val router = SessionRouter(wall).apply { register(MessageType.SMS, module.handler) }

    val mac = FakeClient("MacBook của Lan", "pair-mac", wall)
    val iphone = FakeClient("iPhone của Lan", "pair-iphone", wall, PeerPlatform.IOS)

    init {
        module.start()
    }

    fun connect(vararg clients: FakeClient) {
        sessions.value = sessions.value + clients.associate { it.pairId to it.session }
        run()
    }

    fun disconnect(client: FakeClient) {
        sessions.value = sessions.value - client.pairId
        run()
    }

    fun run() = scope.testScheduler.runCurrent()

    /** An `sms/<op>` request from [client]; returns its envelope `id`. */
    suspend fun request(
        client: FakeClient,
        op: String,
        data: String,
        id: String = ids.next(),
    ): String {
        val plaintext = """{"op":"$op","data":$data}""".toByteArray()
        router.route(client.session, InboundEnvelope(MessageType.SMS.wire, id, wall(), plaintext))
        run()
        return id
    }

    fun newLocalId(): String = ids.next()

    suspend fun round() {
        events.round()
        run()
    }

    companion object {
        fun data(ack: Ack): JsonObject = checkNotNull(ack.data)

        fun <T> decode(
            serializer: KSerializer<T>,
            data: JsonObject,
        ): T = ProtocolJson.decodeFromJsonElement(serializer, data)

        fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    }
}
