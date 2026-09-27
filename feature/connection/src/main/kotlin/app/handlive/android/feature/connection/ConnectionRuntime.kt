package app.handlive.android.feature.connection

import android.content.Context
import app.handlive.android.core.data.HandLiveData
import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.core.data.pairing.PairStore
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.capability.CapabilityData
import app.handlive.android.core.transport.handshake.PairRecord
import app.handlive.android.core.transport.relay.RelayPeerLink
import app.handlive.android.core.transport.server.ControlServer
import app.handlive.android.core.transport.server.ControlServerConfig
import app.handlive.android.core.transport.server.ControlSession
import app.handlive.android.core.transport.server.PairingEndpoint
import app.handlive.android.core.transport.server.SessionTransport
import app.handlive.android.core.transport.tls.AndroidTlsIdentityStorage
import app.handlive.android.core.transport.tls.TlsIdentityProvider
import app.handlive.android.feature.connection.bench.BenchLog
import app.handlive.android.feature.connection.capability.CapabilityPublisher
import app.handlive.android.feature.connection.capability.LocalEnvironmentReader
import app.handlive.android.feature.connection.capability.SimChangeWatcher
import app.handlive.android.feature.connection.discovery.DiscoveryAdvertising
import app.handlive.android.feature.connection.session.PeerSession
import app.handlive.android.feature.connection.session.SessionRouter
import app.handlive.android.feature.connection.session.SessionTable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A-SVC's engine, one per process (CONN-01, CONN-02): runs the TLS server on 47800–47809, advertises
 * `_handlive._tcp` with hourly hints, keeps this phone's capability current on every session, and hands decrypted
 * envelopes to the feature modules through [router]. [HandLiveService] starts and stops it; the UI and the feature
 * modules read [connectedPeers], [sessions] and [state].
 */
class ConnectionRuntime private constructor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val data = HandLiveData.get(appContext)
    private val clock: () -> Long = System::currentTimeMillis
    private val lock = Mutex()
    private val stateFlow = MutableStateFlow(ServiceState.STOPPED)
    private val accessibilityRunning = MutableStateFlow(false)
    private val environmentVersion = MutableStateFlow(0)
    private val pairingAdvert = MutableStateFlow(PairingAdvert.NONE)
    private val capability = CapabilityPublisher(LocalEnvironmentReader(appContext))
    private val localCapabilityFlow = MutableStateFlow<CapabilityData?>(null)
    private val simWatcher = SimChangeWatcher(appContext, ::refreshEnvironment)
    private val discovery = DiscoveryAdvertising(appContext, data.pairs, clock)
    private var scope: CoroutineScope? = null
    private var server: ControlServer? = null

    /** Routes envelopes to feature handlers; register handlers before the service starts. */
    val router = SessionRouter(clock)
    private val table = SessionTable(data.pairs, router, clock)

    /** Open sessions by `pair_id`, over the LAN or the relay. */
    val sessions: StateFlow<Map<String, PeerSession>> = table.sessions

    val connectedPeers: StateFlow<List<ConnectedPeer>> =
        table.sessions
            .map { open -> open.values.map { ConnectedPeer(it.pairId, it.peerName, it.peerPlatform, it.channel) } }
            .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, emptyList())

    val state: StateFlow<ServiceState> = stateFlow.asStateFlow()

    /**
     * This phone's current capability (0.7.2) while the service runs, `null` otherwise: feature modules start and
     * stop their work from it (SMS registers its observer only while `features.sms` is on with `READ_SMS`).
     */
    val localCapability: StateFlow<CapabilityData?> = localCapabilityFlow.asStateFlow()

    /** Sessions that closed, with the peer's `session/bye` reason if it sent one (PAIR-03 API 2 needs `revoked`). */
    val sessionEnded: SharedFlow<SessionEnded> = table.ended

    /** Installed by the pairing feature; `/v1/pair` delegates to it (PAIR-01). */
    @Volatile
    var pairingEndpoint: PairingEndpoint? = null

    /** SHA-256 of the TLS certificate (`tls_sha256` of PAIR-01 API 3), known once the server runs. */
    @Volatile
    var certificateSha256: ByteArray? = null
        private set

    /** Starts the server and the advertising; idempotent. Blocking Keystore work runs on [Dispatchers.IO]. */
    suspend fun start() =
        lock.withLock {
            if (server != null) return@withLock
            stateFlow.value = ServiceState.STARTING
            val failure = runCatching { startLocked() }.exceptionOrNull()
            if (failure is CancellationException) throw failure
            stateFlow.value =
                if (failure == null) {
                    ServiceState.RUNNING
                } else {
                    // Keystore, port or NSD failure: the UI offers "Try Again" (SET-01 E2).
                    stopLocked()
                    ServiceState.FAILED
                }
        }

    suspend fun stop() =
        lock.withLock {
            stopLocked()
            stateFlow.value = ServiceState.STOPPED
        }

    /**
     * A start of the foreground service was requested ([accepted]: a previous failure no longer stands, so the UI can
     * wait for the outcome of the retry) or refused by Android (SET-01 E2).
     */
    fun markLaunch(accepted: Boolean) {
        stateFlow.update { current ->
            when {
                !accepted -> ServiceState.FAILED
                current == ServiceState.FAILED || current == ServiceState.STOPPED -> ServiceState.STARTING
                else -> current
            }
        }
    }

    /** CLIP-01 A3: the Accessibility service connected or disconnected; `auto_send` follows. */
    fun setAccessibilityRunning(running: Boolean) {
        accessibilityRunning.value = running
    }

    /** Re-reads permissions (UI `onResume`, SET-01 step 14); a change goes out as `capability/update`. */
    fun refreshEnvironment() {
        environmentVersion.update { it + 1 }
    }

    /** Pairing window opened or closed: TXT gains or loses `pr` / `pm`. */
    fun setPairingAdvert(advert: PairingAdvert) {
        pairingAdvert.value = advert
    }

    /** The relay's way into the control server (CONN-03): relayed sessions in, and out again when it is turned off. */
    val relayGate = RelayGate { server }

    /** Closes the session of [pairId] with `session/bye {reason}` (PAIR-03 step 6, CONN-02 API 4). */
    suspend fun closeSession(
        pairId: String,
        reason: String,
    ) {
        server?.sessions?.get(pairId)?.let { runCatching { it.bye(reason) } }
    }

    private suspend fun startLocked() {
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        val (identity, tls) =
            withContext(Dispatchers.IO) {
                data.identity to TlsIdentityProvider.loadOrCreate(AndroidTlsIdentityStorage.create(appContext))
            }
        certificateSha256 = tls.certificateSha256()
        BenchLog.setDevice(identity.deviceId)
        val capabilityState =
            capability.state(
                runtimeScope,
                data.settings.current(),
                data.settings.settings,
                accessibilityRunning,
                environmentVersion,
            )
        val controlServer =
            ControlServer(
                ControlServerConfig(
                    tls = tls,
                    localDeviceId = identity.deviceId,
                    pairs = { pairId -> data.pairs.pairRecord(pairId) },
                    localCapability = { capabilityState.value },
                    onSessionEstablished = { session -> runtimeScope.launch { table.attach(session, runtimeScope) } },
                    pairingEndpoint = { socket -> pairingEndpoint?.handle(socket) },
                ),
            )
        val port = controlServer.start()
        server = controlServer
        capabilityState.onEach { localCapabilityFlow.value = it }.launchIn(runtimeScope)
        capability.publishUpdates(runtimeScope, capabilityState) { controlServer.sessions.all() }
        discovery.start(runtimeScope, port, pairingAdvert)
        simWatcher.start()
    }

    private suspend fun stopLocked() {
        simWatcher.stop()
        localCapabilityFlow.value = null
        server?.sessions?.all()?.forEach { runCatching { it.bye(BYE_SHUTDOWN) } }
        server?.stop()
        server = null
        discovery.stop()
        scope?.cancel()
        scope = null
        table.clear()
    }

    companion object {
        private const val BYE_SHUTDOWN = "shutdown"

        @Volatile
        private var instance: ConnectionRuntime? = null

        fun get(context: Context): ConnectionRuntime =
            instance ?: synchronized(this) {
                instance ?: ConnectionRuntime(context).also { instance = it }
            }
    }
}

/** CONN-01 step 7: the pair of `session/hello`, read on the TLS server thread; a tombstone has no `PRK`. */
private fun PairStore.pairRecord(pairId: String): PairRecord? =
    secretBlocking(pairId)?.let { secret ->
        PairRecord(secret.pairId, secret.peerDeviceId, secret.prk ?: ByteArray(0), secret.revoked)
    }
