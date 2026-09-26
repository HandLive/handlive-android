package app.handlive.android.feature.sms

import android.content.Context
import app.handlive.android.core.data.HandLiveData
import app.handlive.android.core.data.settings.HandLiveSettings
import app.handlive.android.core.protocol.capability.CapabilityData
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.feature.connection.ConnectionRuntime
import app.handlive.android.feature.connection.capability.AndroidPermissions
import app.handlive.android.feature.connection.capability.SimDirectory
import app.handlive.android.feature.sms.module.OfflineSmsDelivery
import app.handlive.android.feature.sms.module.PermissionMissingListener
import app.handlive.android.feature.sms.module.SmsBroadcaster
import app.handlive.android.feature.sms.module.SmsEvents
import app.handlive.android.feature.sms.module.SmsModule
import app.handlive.android.feature.sms.module.SmsRequests
import app.handlive.android.feature.sms.module.SmsServices
import app.handlive.android.feature.sms.observe.NewMessageScanner
import app.handlive.android.feature.sms.observe.ReadStateTracker
import app.handlive.android.feature.sms.provider.SmsObjects
import app.handlive.android.feature.sms.send.SendRegistry
import app.handlive.android.feature.sms.send.SmsSendPipeline
import app.handlive.android.feature.sms.sync.SmsHistoryEngine
import app.handlive.android.feature.sms.sync.SmsSyncEngine
import app.handlive.android.feature.sms.system.AndroidSmsAccess
import app.handlive.android.feature.sms.system.AndroidSmsRadio
import app.handlive.android.feature.sms.system.ContentResolverSmsProvider
import app.handlive.android.feature.sms.system.DirectorySimChoices
import app.handlive.android.feature.sms.system.PhoneLookupContactNames
import app.handlive.android.feature.sms.system.PhoneNumberNormalizer
import app.handlive.android.feature.sms.system.RoomObserverState
import app.handlive.android.feature.sms.system.SmsContentObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import java.util.concurrent.atomic.AtomicLong

/**
 * The SMS feature of this process (A-SMS): builds [SmsModule] with the Android pieces, registers it for
 * `type = sms`, and runs the provider observer while `features.sms` is on with `READ_SMS` (SMS-02 API 3). Observer
 * rounds, sending and its results share one serial worker; sync and history pages read the provider on the IO pool.
 */
class SmsFeature private constructor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val data = HandLiveData.get(appContext)
    private val runtime = ConnectionRuntime.get(appContext)
    private val clock: () -> Long = System::currentTimeMillis
    private val worker = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val sims = SimDirectory(appContext)

    val settings: StateFlow<HandLiveSettings> =
        data.settings.settings.stateIn(scope, SharingStarted.Eagerly, HandLiveSettings())

    private val access = AndroidSmsAccess(appContext, settings)
    private val provider = ContentResolverSmsProvider(appContext.contentResolver)
    private val numbers = PhoneNumberNormalizer()
    private val contacts = PhoneLookupContactNames(appContext.contentResolver)
    private val registry = SendRegistry(clock)
    private val sender =
        SmsSendPipeline(
            access,
            DirectorySimChoices(appContext, sims),
            numbers,
            AndroidSmsRadio(appContext),
            registry,
            clock,
        )
    private val trace = SmsBenchTrace()
    private val broadcaster = SmsBroadcaster(runtime.sessions, trace)

    /** Wall clock of the first `onChange` of the batch not yet processed; 0 = none. */
    private val firstChangeAt = AtomicLong()
    private val observer =
        SmsContentObserver(appContext.contentResolver) {
            firstChangeAt.compareAndSet(0, clock())
            signals.trySend(Unit)
        }

    /** Clients without a session (CONN-03, CONN-04): installed by the relay feature. */
    @Volatile
    var offline: OfflineSmsDelivery = OfflineSmsDelivery { _, _ -> }

    /** SET-01 field 17: installed by the app, which owns the permission notification. */
    @Volatile
    var permissionMissing: PermissionMissingListener = PermissionMissingListener { _, _ -> }

    private val services =
        SmsServices(
            SmsRequests(access),
            SmsSyncEngine(provider, ::objects),
            SmsHistoryEngine(provider, ::objects),
            sender,
            broadcaster,
            trace,
        )

    val module =
        SmsModule(
            worker = worker,
            reads = Dispatchers.IO,
            sessions = runtime.sessions,
            services = services,
            permissionMissing = { session, permission -> permissionMissing.onPermissionMissing(session, permission) },
        )

    private val events =
        SmsEvents(
            NewMessageScanner(provider, RoomObserverState(data.smsObserverState, clock), clock),
            ReadStateTracker(clock),
            ::objects,
            services,
        ) { message, reached -> offline.newMessage(message, reached) }

    @Volatile
    private var observing = false

    /** One scope per page or round: names are looked up only with `READ_CONTACTS` (SMS-01 E3). */
    private fun objects(): SmsObjects =
        SmsObjects.of(
            provider,
            numbers,
            contacts.takeIf { access.granted(AndroidPermissions.READ_CONTACTS) },
            sims.countryIso(sims.defaultSmsSubId()),
        )

    private fun start() {
        module.start()
        runtime.localCapability
            .map { it.observesSms() }
            .distinctUntilChanged()
            .onEach { on -> if (on) startObserving() else stopObserving() }
            .launchIn(scope)
        signals
            .receiveAsFlow()
            .onEach {
                // SMS-02 API 3 logic 2: calls within 100 ms are coalesced and processed one round at a time.
                delay(SmsConstants.OBSERVER_DEBOUNCE_MILLIS)
                signals.tryReceive()
                val changedAt = firstChangeAt.getAndSet(0)
                if (observing) safely { events.round(changedAt) }
            }.launchIn(scope)
    }

    private suspend fun startObserving() {
        observing = observer.register()
        if (observing) safely { events.start() }
    }

    /** SET-02 field 7 off or `READ_SMS` lost (SMS-02 E6): no more rounds until it comes back. */
    private fun stopObserving() {
        observing = false
        observer.unregister()
        events.stop()
    }

    /** API 3 logic 7: every failure of a round is caught; the observer never stops the service. */
    private suspend fun safely(round: suspend () -> Unit) {
        try {
            round()
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            // Provider or database error: the next onChange tries again from the persisted mark.
        }
    }

    companion object {
        @Volatile
        private var instance: SmsFeature? = null

        fun get(context: Context): SmsFeature =
            instance ?: synchronized(this) {
                instance ?: SmsFeature(context).also { instance = it }
            }

        /** Connects the feature to the connection runtime; runs once at process start. */
        fun install(context: Context) {
            val feature = get(context)
            feature.runtime.router.register(MessageType.SMS, feature.module.handler)
            feature.start()
        }

        /** The observer runs while the service does, SMS is on and `READ_SMS` is granted. */
        private fun CapabilityData?.observesSms(): Boolean =
            this != null && features.sms?.enabled == true &&
                permissionsMissing.orEmpty().none { it == AndroidPermissions.READ_SMS }
    }
}
