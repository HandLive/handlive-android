package app.handlive.android.feature.call

import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import app.handlive.android.core.data.HandLiveData
import app.handlive.android.core.data.settings.HandLiveSettings
import app.handlive.android.core.protocol.capability.CapabilityData
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.protocol.id.UuidV7Generator
import app.handlive.android.feature.call.context.CallTracker
import app.handlive.android.feature.call.context.NameCache
import app.handlive.android.feature.call.log.CallLogEntries
import app.handlive.android.feature.call.log.CallLogRequests
import app.handlive.android.feature.call.log.CallLogSyncEngine
import app.handlive.android.feature.call.log.CallLogWatcher
import app.handlive.android.feature.call.module.CallActions
import app.handlive.android.feature.call.module.CallBroadcaster
import app.handlive.android.feature.call.module.CallLogServices
import app.handlive.android.feature.call.module.CallModule
import app.handlive.android.feature.call.module.CallServices
import app.handlive.android.feature.call.module.OfflineCallDelivery
import app.handlive.android.feature.call.system.AndroidCallAccess
import app.handlive.android.feature.call.system.AndroidTelecom
import app.handlive.android.feature.call.system.ContentResolverCallLog
import app.handlive.android.feature.call.system.DirectorySimLabels
import app.handlive.android.feature.call.system.PhoneAccountSubIds
import app.handlive.android.feature.call.system.PhoneStateReceiver
import app.handlive.android.feature.call.system.SystemCallNumbers
import app.handlive.android.feature.call.system.TelephonyWatcher
import app.handlive.android.feature.call.system.UriObserver
import app.handlive.android.feature.connection.ConnectionRuntime
import app.handlive.android.feature.connection.capability.AndroidPermissions
import app.handlive.android.feature.connection.capability.SimDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn

/**
 * The calls feature of this process (A-CALL): builds [CallModule] with the Android pieces, registers it for
 * `type = call_event`, and runs the call state listeners, the PHONE_STATE receiver and the call log observer while
 * calls are on with their permissions (CALL-01 API 2, API 3; CALL-04 API 3). Every call event runs on one serial
 * worker, the A-CALL thread; `log_sync` pages read the provider on the IO pool.
 */
class CallFeature private constructor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val data = HandLiveData.get(appContext)
    private val runtime = ConnectionRuntime.get(appContext)
    private val clock: () -> Long = System::currentTimeMillis
    private val worker = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private val logSignals = Channel<Unit>(Channel.CONFLATED)
    private val sims = SimDirectory(appContext)

    private val settings: StateFlow<HandLiveSettings> =
        data.settings.settings.stateIn(scope, SharingStarted.Eagerly, HandLiveSettings())

    private val access = AndroidCallAccess(appContext, settings)
    private val numbers =
        SystemCallNumbers(resolver, sims::countryIso) { access.granted(AndroidPermissions.READ_CONTACTS) }
    private val provider = ContentResolverCallLog(resolver)
    private val subIds = PhoneAccountSubIds(appContext)
    private val tracker =
        CallTracker(UuidV7Generator(clock)::next, numbers, DirectorySimLabels(sims)) {
            access.granted(AndroidPermissions.READ_CALL_LOG)
        }
    private val trace = CallBenchTrace()

    val module =
        CallModule(
            worker = worker,
            reads = Dispatchers.IO,
            sessions = runtime.sessions,
            services =
                CallServices(
                    access = access,
                    tracker = tracker,
                    actions =
                        CallActions(
                            access,
                            AndroidTelecom(appContext),
                            tracker,
                            clock,
                            runtime::refreshEnvironment,
                        ),
                    broadcaster =
                        CallBroadcaster(
                            runtime.sessions,
                            { access.granted(AndroidPermissions.ANSWER_PHONE_CALLS) },
                            trace = trace,
                        ),
                    logs =
                        CallLogServices(
                            CallLogRequests(access, CallLogSyncEngine(provider, ::syncEntries, clock)),
                            CallLogWatcher(provider),
                        ) { CallLogEntries(numbers::normalize, numbers::name, subIds) },
                    trace = trace,
                ),
            clock = clock,
        )

    /** Clients without a session (CONN-03, CONN-04): installed by the relay feature. */
    var offline: OfflineCallDelivery
        get() = module.offline
        set(value) {
            module.offline = value
        }

    private val telephony =
        TelephonyWatcher(appContext, worker.asExecutor(), clock, module::onPhoneState, module::onSimState)
    private val receiver = PhoneStateReceiver(appContext, clock, module::onBroadcast)
    private val logObserver = UriObserver(resolver, CallLog.Calls.CONTENT_URI) { logSignals.trySend(Unit) }
    private val contactsObserver = UriObserver(resolver, ContactsContract.Contacts.CONTENT_URI, numbers::forgetNames)
    private var watching = CallWatch.OFF

    /** CALL-04 API 1 logic 7: names cached per sync, never on disk; without `READ_CONTACTS` only `CACHED_NAME`. */
    private fun syncEntries(): CallLogEntries {
        val names =
            if (access.granted(AndroidPermissions.READ_CONTACTS)) {
                NameCache(CallConstants.NAME_CACHE_SIZE, numbers::lookup)::name
            } else {
                null
            }
        return CallLogEntries(numbers::normalize, names, subIds)
    }

    private fun start() {
        module.start()
        runtime.localCapability
            .map(CallWatch::of)
            .distinctUntilChanged()
            .onEach(::watchSafely)
            .launchIn(scope)
        logSignals
            .receiveAsFlow()
            .onEach {
                // CALL-04 API 3 logic 2: calls within 100 ms are coalesced.
                delay(CallConstants.LOG_OBSERVER_DEBOUNCE_MILLIS)
                logSignals.tryReceive()
                module.onLogChanged()
            }.launchIn(scope)
    }

    /** A system service that refuses in an unexpected way leaves calls off until the next change; A-SVC goes on. */
    private fun watchSafely(next: CallWatch) {
        try {
            watch(next)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            watching = CallWatch.OFF
            runCatching { telephony.stop() }
            runCatching { receiver.unregister() }
        }
    }

    /** Registers and removes the listeners, the receiver and the observers as calls come and go (API 2 logic 4). */
    private fun watch(next: CallWatch) {
        val previous = watching
        watching = next
        when {
            next.listen && !previous.listen -> {
                // The receiver first, so no copy of the first change is missed; the tracker starts over (E8).
                receiver.register()
                module.onListening()
                if (!telephony.start(next.sims)) runtime.refreshEnvironment()
            }

            !next.listen && previous.listen -> {
                telephony.stop()
                receiver.unregister()
                module.onListening()
            }

            next.listen && next.sims != previous.sims -> {
                telephony.updateSims(next.sims)
            }
        }
        if (next.callLog != previous.callLog) {
            if (next.callLog) logObserver.register() else logObserver.unregister()
            module.watchLog(next.callLog)
        }
        if (next.contacts != previous.contacts) {
            if (next.contacts) contactsObserver.register() else contactsObserver.unregister()
            numbers.forgetNames()
        }
        if (next.canControl != previous.canControl) module.onPermissionsChanged()
    }

    /** What the capability says the call group may run now. */
    private data class CallWatch(
        /** Calls on with `READ_PHONE_STATE` while the service runs: listeners and receiver (API 2). */
        val listen: Boolean,
        val sims: List<Int>,
        /** And `READ_CALL_LOG`: the call log observer (CALL-04 API 3). */
        val callLog: Boolean,
        /** And `READ_CONTACTS`: names; the cache is dropped when the contacts change. */
        val contacts: Boolean,
        /** `ANSWER_PHONE_CALLS`: the `controls` of the current call change with it. */
        val canControl: Boolean,
    ) {
        companion object {
            val OFF =
                CallWatch(listen = false, sims = emptyList(), callLog = false, contacts = false, canControl = false)

            fun of(capability: CapabilityData?): CallWatch {
                val call = capability?.features?.call
                val missing = capability?.permissionsMissing.orEmpty()
                val canControl = call?.canAnswer == true
                val listen = call?.enabled == true && AndroidPermissions.READ_PHONE_STATE !in missing
                return if (capability == null || !listen) {
                    OFF.copy(canControl = canControl)
                } else {
                    CallWatch(
                        listen = true,
                        sims =
                            capability.features.sms
                                ?.sims
                                .orEmpty()
                                .map { it.subId },
                        callLog = AndroidPermissions.READ_CALL_LOG !in missing,
                        contacts = AndroidPermissions.READ_CONTACTS !in missing,
                        canControl = canControl,
                    )
                }
            }
        }
    }

    companion object {
        @Volatile
        private var instance: CallFeature? = null

        fun get(context: Context): CallFeature =
            instance ?: synchronized(this) {
                instance ?: CallFeature(context).also { instance = it }
            }

        /** Connects the feature to the connection runtime; runs once at process start. */
        fun install(context: Context) {
            val feature = get(context)
            feature.runtime.router.register(MessageType.CALL_EVENT, feature.module.handler)
            feature.start()
        }
    }
}
