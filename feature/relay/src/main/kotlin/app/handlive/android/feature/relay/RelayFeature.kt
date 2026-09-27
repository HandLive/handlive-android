package app.handlive.android.feature.relay

import android.content.Context
import app.handlive.android.core.data.HandLiveData
import app.handlive.android.core.data.settings.HandLiveSettings
import app.handlive.android.core.data.settings.SettingsKeys
import app.handlive.android.core.protocol.sms.SmsNewData
import app.handlive.android.core.transport.relay.RelayApi
import app.handlive.android.core.transport.relay.RelayAuth
import app.handlive.android.core.transport.relay.RelayConfig
import app.handlive.android.core.transport.relay.RelayIdentity
import app.handlive.android.core.transport.relay.RelayPeerLink
import app.handlive.android.core.transport.relay.RelayTransport
import app.handlive.android.core.transport.server.PairingEndpoint
import app.handlive.android.feature.connection.ConnectionRuntime
import app.handlive.android.feature.connection.ServiceLauncher
import app.handlive.android.feature.connection.ServiceState
import app.handlive.android.feature.connection.SessionEnded
import app.handlive.android.feature.connection.bench.BenchLog
import app.handlive.android.feature.connection.session.PeerSession
import app.handlive.android.feature.pairing.PairingFeature
import app.handlive.android.feature.pairing.exchange.RendezvousConnector
import app.handlive.android.feature.pairing.revoke.RemoteRevoker
import app.handlive.android.feature.relay.push.PushOutboxRunner
import app.handlive.android.feature.relay.push.PushSender
import app.handlive.android.feature.sms.SmsBenchEvent
import app.handlive.android.feature.sms.SmsFeature
import app.handlive.android.feature.sms.module.OfflineSmsDelivery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The relay on the phone (CONN-03, CONN-04, PAIR-01 over the relay, PAIR-03 flow B): wires the relay client into the
 * connection service, the pairing and SMS features, and the network. Everything that touches the Keystore or the
 * network runs on one serial worker, so the relay's state needs no locks.
 */
class RelayFeature private constructor(
    context: Context,
    private val config: RelayConfig,
) {
    private val appContext = context.applicationContext
    private val data = HandLiveData.get(appContext)
    private val runtime = ConnectionRuntime.get(appContext)
    private val clock: () -> Long = System::currentTimeMillis
    private val worker = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private val deviceRevoked = MutableStateFlow(false)
    private val certificateRejected = MutableStateFlow(false)
    private val owner = Owner()

    private val settings: StateFlow<HandLiveSettings> =
        data.settings.settings.stateIn(scope, SharingStarted.Eagerly, HandLiveSettings())

    // Created on the worker: the identity keys come from the Keystore.
    private val transport by lazy { RelayTransport.create(config) }
    private val auth by lazy {
        val identity = data.identity
        RelayAuth(
            transport.http,
            RelayIdentity(identity.deviceId, identity.signingPublicKey, appContext.appVersion(), identity::sign),
            clock,
        )
    }
    private val api by lazy { RelayApi(transport.http, auth) }
    private val registrar by lazy {
        RelayRegistrar(auth, api, data.pairs, data.relayPairs, clock) { pairId ->
            PairingFeature.get(appContext).unpair.onRevokedByRelay(pairId, elsewhere = true)
        }
    }
    private val push by lazy {
        PushSender(api, data.relayPairs, data.pushOutbox, clock, sent = { messageKey, peer ->
            BenchLog.event(SmsBenchEvent.SMS_PUSH_SENT, "msg" to messageKey, "peer" to peer.take(PEER_ID))
        })
    }
    private val outbox = PushOutboxRunner(scope, clock, { push }, owner::allowed)
    private val connectorHolder = lazy { RelayConnector(scope, clock, auth, transport.links, owner) }
    private val connector by connectorHolder

    /** SET-02 field 21 and CONN-03 E3 for Settings; a build without a relay stays `available = false`. */
    val status: StateFlow<RelayStatus> by lazy {
        MutableStateFlow(RelayStatus(available = config.available)).also { status ->
            if (config.available) {
                deviceRevoked.onEach { revoked -> status.update { it.copy(deviceRevoked = revoked) } }.launchIn(scope)
                certificateRejected
                    .onEach { rejected -> status.update { it.copy(pinMismatch = rejected) } }
                    .launchIn(scope)
                scope.launch { connector.state.collect { link -> status.update { it.copy(link = link) } } }
            }
        }
    }

    /** FCM `t = wake` (CONN-04 step 9a, `gms` flavor): run the service and let the waiting client in. */
    fun onWake() {
        ServiceLauncher.start(appContext)
        onWorker { connector.demand() }
    }

    /**
     * SET-02 A2–A4 on the relay's side: `DELETE /v1/devices/me` with [revokePairs] (`false` = "Remove Device from
     * Server", `true` = "Delete All HandLive Data", whose local part the caller runs). Removing only keeps every pair
     * but forgets its registration and writes `relay.enabled = false`, which sends `capability/update` and closes the
     * relayed sessions and the link. A build without a relay has nothing to delete there.
     */
    suspend fun deleteFromServer(revokePairs: Boolean): ServerDeletion =
        withContext(worker) {
            val outcome = if (config.available) registrar.deleteDevice(revokePairs) else ServerDeletion.DONE
            if (outcome == ServerDeletion.DONE && !revokePairs) {
                data.relayPairs.forgetRegistrations()
                data.settings.set(SettingsKeys.RELAY_ENABLED, false)
            }
            outcome
        }

    /** FCM `onNewToken`, or the token read at start (CONN-04 step 2, `gms` flavor). */
    fun onPushToken(token: String) {
        onWorker { registrar.registerPushToken(token) }
    }

    private fun start() {
        runtime.state
            .map { it == ServiceState.RUNNING }
            .distinctUntilChanged()
            .onEach { running ->
                if (running) {
                    onServiceRunning()
                } else if (connectorHolder.isInitialized()) {
                    connector.stop()
                }
            }.launchIn(scope)
        settings
            .map { it.relayEnabled }
            .distinctUntilChanged()
            .drop(1)
            .onEach(::onRelaySwitched)
            .launchIn(scope)
        runtime.sessionEnded.onEach(::onSessionEnded).launchIn(scope)
        appContext.watchDefaultNetwork {
            onWorker {
                connector.networkChanged()
                registrar.registerEverything()
            }
            outbox.wake()
        }
    }

    private fun onServiceRunning() {
        onWorker {
            registrar.registerEverything()
            // Clients outside the LAN may be waiting for the phone since before the service started.
            if (hasRelayPairWithoutSession()) connector.demand()
        }
        outbox.start()
    }

    /**
     * SET-02 field 21. On: a new registration (also after E3) and the waiting clients. Off (SET-02 API 1 step 6): the
     * capability goes out first, then `session/bye` on the relayed sessions, and the link closes.
     */
    private fun onRelaySwitched(enabled: Boolean) {
        if (enabled) {
            deviceRevoked.value = false
            certificateRejected.value = false
            onServiceRunning()
        } else {
            scope.launch {
                attempt { runtime.relayGate.closeSessions() }
                connector.stop()
            }
        }
    }

    /** A LAN session that dropped (no `bye`): the client will look for the phone on the relay (CONN-03 step 2). */
    private fun onSessionEnded(ended: SessionEnded) {
        if (ended.channel == PeerSession.Channel.LAN && ended.byeReason == null) onWorker { connector.demand() }
    }

    /** SMS-02 steps 5 and 10: iPhone and iPad without a session get a push; waiting clients get the relay. */
    private fun onSmsForOfflineClients(
        new: SmsNewData,
        reached: Set<String>,
    ) {
        onWorker {
            val connected = runtime.sessions.value.keys + reached
            push.smsNew(new, connected)
            if (hasRelayPairWithoutSession()) connector.demand()
            outbox.wake()
        }
    }

    private suspend fun hasRelayPairWithoutSession(): Boolean {
        val open = runtime.sessions.value.keys
        return data.pairs
            .observeActive()
            .first()
            .any { it.relayRegistered && it.pairId !in open }
    }

    /** Runs [block] on the worker once the relay may be used; failures wait for the next occasion. */
    private fun onWorker(block: suspend () -> Unit) {
        scope.launch { if (owner.allowed()) attempt(block) }
    }

    /** What the relay connection asks of the phone and tells it about its pairs and itself. */
    private inner class Owner : RelayOwner {
        override fun allowed(): Boolean =
            config.available && settings.value.relayEnabled && !deviceRevoked.value &&
                runtime.state.value == ServiceState.RUNNING

        override suspend fun serve(
            link: RelayPeerLink,
            peerDeviceId: String,
        ) = runtime.relayGate.serve(link, peerDeviceId)

        override suspend fun pairRevoked(
            pairId: String,
            by: String,
        ) = PairingFeature.get(appContext).unpair.onRevokedByRelay(pairId, elsewhere = false)

        override fun notPaired() {
            onWorker { registrar.checkPairs(force = true) }
        }

        /** CONN-03 E3: the relay is off until the user turns it back on (a new registration). */
        override fun deviceRevoked() {
            deviceRevoked.value = true
            scope.launch { attempt { data.settings.set(SettingsKeys.RELAY_ENABLED, false) } }
            connector.stop()
        }

        override fun pinMismatch() {
            certificateRejected.value = true
        }

        override fun connected() {
            certificateRejected.value = false
            onWorker {
                registrar.registerEverything()
                attempt { registrar.checkPairs() }
            }
            outbox.wake()
        }
    }

    companion object {
        private const val PEER_ID = 8

        @Volatile
        private var instance: RelayFeature? = null

        /** The feature [install] created at process start. */
        fun get(): RelayFeature = checkNotNull(instance) { "RelayFeature.install() was not called" }

        /** Connects the relay to the service and the features; runs once at process start, with this build's relay. */
        fun install(
            context: Context,
            config: RelayConfig,
        ) {
            val feature =
                synchronized(this) {
                    instance ?: RelayFeature(context, config).also { instance = it }
                }
            if (!config.available) return
            SmsFeature.get(context).offline =
                OfflineSmsDelivery { new, reached -> feature.onSmsForOfflineClients(new, reached) }
            val pairing = PairingFeature.get(context)
            pairing.coordinator.rendezvous =
                object : RendezvousConnector {
                    override fun join(
                        rvId: String,
                        endpoint: PairingEndpoint,
                    ) = feature.onWorker { feature.connector.joinRendezvous(rvId, endpoint) }

                    override fun leave(rvId: String) = feature.onWorker { feature.connector.leaveRendezvous(rvId) }
                }
            pairing.coordinator.onPaired = { feature.onWorker { feature.registrar.registerAll() } }
            pairing.unpair.remote =
                RemoteRevoker { pairId, reason -> feature.onWorker { feature.registrar.revoke(pairId, reason) } }
            feature.start()
        }
    }
}
