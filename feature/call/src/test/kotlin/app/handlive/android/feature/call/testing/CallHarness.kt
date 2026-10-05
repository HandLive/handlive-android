package app.handlive.android.feature.call.testing

import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.protocol.id.UuidV7Generator
import app.handlive.android.core.transport.server.InboundEnvelope
import app.handlive.android.feature.call.appcall.AppCallActions
import app.handlive.android.feature.call.appcall.AppCallBroadcaster
import app.handlive.android.feature.call.appcall.AppCallFixtures
import app.handlive.android.feature.call.appcall.AppCallServices
import app.handlive.android.feature.call.appcall.AppCallTracker
import app.handlive.android.feature.call.appcall.AppNotification
import app.handlive.android.feature.call.appcall.FakeAppCallAccess
import app.handlive.android.feature.call.appcall.FakeCommunicationMode
import app.handlive.android.feature.call.appcall.FakeExemption
import app.handlive.android.feature.call.appcall.FakeTapNotifier
import app.handlive.android.feature.call.context.BroadcastCopy
import app.handlive.android.feature.call.context.CallTracker
import app.handlive.android.feature.call.context.NameCache
import app.handlive.android.feature.call.context.PhoneState
import app.handlive.android.feature.call.context.SimReport
import app.handlive.android.feature.call.log.CallLogEntries
import app.handlive.android.feature.call.log.CallLogRequests
import app.handlive.android.feature.call.log.CallLogSyncEngine
import app.handlive.android.feature.call.log.CallLogWatcher
import app.handlive.android.feature.call.module.CallActions
import app.handlive.android.feature.call.module.CallBroadcaster
import app.handlive.android.feature.call.module.CallLogServices
import app.handlive.android.feature.call.module.CallModule
import app.handlive.android.feature.call.module.CallPermissionListener
import app.handlive.android.feature.call.module.CallServices
import app.handlive.android.feature.call.module.CallTrace
import app.handlive.android.feature.connection.capability.AndroidPermissions
import app.handlive.android.feature.connection.session.PeerSession
import app.handlive.android.feature.connection.session.SessionRouter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject

/**
 * The call module on the test scheduler with fake Telephony, Telecom, call log, numbers and SIMs, reached through a
 * router like the service does; the clock is the scheduler's virtual time from [BASE_TS].
 */
class CallHarness(
    private val scope: TestScope,
    trace: CallTrace = CallTrace.NONE,
) {
    val wall = { BASE_TS + scope.testScheduler.currentTime }
    val access = FakeAccess()
    val telecom = FakeTelecom()
    val numbers = FakeNumbers()
    val sims = FakeSimLabels()
    val callLog = FakeCallLog()
    val offline = FakeOffline(wall)
    val appAccess = FakeAppCallAccess()
    val exemption = FakeExemption()
    val tap = FakeTapNotifier()
    val mode = FakeCommunicationMode()
    val sessions = MutableStateFlow<Map<String, PeerSession>>(emptyMap())
    val permissionsAsked = mutableListOf<Pair<String, String>>()
    var permissionLost = 0
    private val dispatcher = StandardTestDispatcher(scope.testScheduler)
    private val ids = UuidV7Generator(wall)
    val tracker = CallTracker(ids::next, numbers, sims) { access.granted(AndroidPermissions.READ_CALL_LOG) }
    val appTracker = AppCallTracker(ids::next, AppCallFixtures.labels, mode)
    private val entries = {
        CallLogEntries(numbers::normalize, numbers::name.takeIf { numbers.contacts }, FakeCallLog.SUB_IDS)
    }
    private val syncEntries = {
        val names = NameCache(NAME_CACHE, numbers::name).takeIf { access.granted(AndroidPermissions.READ_CONTACTS) }
        CallLogEntries(numbers::normalize, names?.let { it::name }, FakeCallLog.SUB_IDS)
    }
    val module =
        CallModule(
            dispatcher,
            dispatcher,
            sessions,
            CallServices(
                access = access,
                tracker = tracker,
                actions = CallActions(access, telecom, tracker, wall) { permissionLost++ },
                broadcaster =
                    CallBroadcaster(sessions, { access.granted(AndroidPermissions.ANSWER_PHONE_CALLS) }, trace = trace),
                logs =
                    CallLogServices(
                        CallLogRequests(access, CallLogSyncEngine(callLog, syncEntries, wall)),
                        CallLogWatcher(callLog),
                        entries,
                    ),
                trace = trace,
            ),
            AppCallServices(
                access = appAccess,
                tracker = appTracker,
                broadcaster = AppCallBroadcaster(sessions, exemption, trace),
                actions = AppCallActions(appTracker, exemption, tap, trace),
                tap = tap,
                trace = trace,
            ),
            wall,
        ).also { module ->
            module.offline = offline
            module.permissionMissing =
                CallPermissionListener { session, permission -> permissionsAsked += session.pairId to permission }
        }
    private val router = SessionRouter(wall).apply { register(MessageType.CALL_EVENT, module.handler) }

    val mac = FakeClient("MacBook của Lan", "pair-mac", wall)
    val iphone = FakeClient("iPhone của Lan", "pair-iphone", wall, PeerPlatform.IOS)

    init {
        module.start()
        module.onListening()
        run()
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

    /** Moves the virtual clock forward and runs what fell due. */
    fun advance(millis: Long) {
        scope.testScheduler.advanceTimeBy(millis)
        run()
    }

    /** The default listener reports [state] now. */
    fun phone(state: PhoneState) {
        module.onPhoneState(state, wall())
        run()
    }

    fun sim(
        subId: Int,
        state: PhoneState,
    ) {
        module.onSimState(SimReport(subId, state, wall()))
        run()
    }

    /** One PHONE_STATE copy; [number] = `null` without the number key, `""` with an empty one. */
    fun copy(
        state: PhoneState,
        number: String?,
    ) {
        module.onBroadcast(BroadcastCopy(state, number != null, number, wall()))
        run()
    }

    /** An incoming call: both copies (the one with the number first), then the listener. */
    fun ring(number: String? = "0900000123") {
        copy(PhoneState.RINGING, number)
        copy(PhoneState.RINGING, null)
        phone(PhoneState.RINGING)
    }

    /** A `call_event/<op>` request from [client]; returns its envelope `id`. */
    suspend fun request(
        client: FakeClient,
        op: String,
        data: String,
        id: String = ids.next(),
    ): String {
        val plaintext = """{"op":"$op","data":$data}""".toByteArray()
        router.route(client.session, InboundEnvelope(MessageType.CALL_EVENT.wire, id, wall(), plaintext))
        run()
        return id
    }

    suspend fun action(
        client: FakeClient,
        callId: String,
        action: String,
        audio: String? = null,
    ): Ack {
        val extra = audio?.let { ""","audio":"$it"""" }.orEmpty()
        val id = request(client, "action", """{"call_id":"$callId","action":"$action"$extra}""")
        return client.acks().last { it.re == id }
    }

    /** The notification listener delivers [notification] now; its post time is [postTime] (now by default). */
    fun appPost(
        notification: AppNotification,
        postTime: Long = wall(),
    ) {
        module.appCalls.posted(notification, postTime)
        run()
    }

    /** [byApp]: the app removed the notification itself; otherwise the user or the system did. */
    fun appRemove(
        key: String,
        byApp: Boolean = true,
    ) {
        module.appCalls.removed(key, wall(), byApp)
        run()
    }

    fun appListenerLost() {
        module.appCalls.listenerLost(wall())
        run()
    }

    /** The setting, the Notification access or the background-start exemption changed. */
    fun appEnvironmentChanged() {
        module.appCalls.environmentChanged(wall())
        run()
    }

    /** The call log observer fired (after its 100 ms coalescing). */
    fun logChanged() {
        module.onLogChanged()
        run()
    }

    companion object {
        private const val NAME_CACHE = 200

        fun <T> decode(
            serializer: KSerializer<T>,
            data: JsonObject?,
        ): T = ProtocolJson.decodeFromJsonElement(serializer, checkNotNull(data))
    }
}
