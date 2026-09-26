package app.handlive.android.feature.relay

import app.handlive.android.core.transport.relay.RelayAuth
import app.handlive.android.core.transport.relay.RelayLinkFactory
import app.handlive.android.core.transport.server.PairingEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * The phone's presence on `/v1/relay` (CONN-03). The phone keeps no permanent connection: it connects while there is
 * [demand] — a client may be waiting on the relay (after a lost LAN session, a network change, an SMS for a client
 * without a session, an FCM wake-up) — or a relayed session or a pairing rendezvous is open, and it leaves after
 * 5 idle minutes ([RelayConnection]). Failed connections are retried with `RECONNECT_BACKOFF`. Everything runs on the
 * serial [scope]; the public functions may be called from any thread.
 */
class RelayConnector(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    auth: RelayAuth,
    links: RelayLinkFactory,
    private val owner: RelayOwner,
    private val random: Random = Random.Default,
) {
    private val stateFlow = MutableStateFlow(RelayLinkState.OFF)
    private val rendezvous: RelayRendezvousTable = RelayRendezvousTable(scope) { text -> connection.send(text) }
    private val connection: RelayConnection = RelayConnection(scope, clock, auth, links, owner, rendezvous)
    private var runner: Job? = null
    private var demandUntil = 0L
    private var attempt = 0

    val state: StateFlow<RelayLinkState> = stateFlow.asStateFlow()

    /** A client may be waiting on the relay: connect, and stay at least `RELAY_IDLE_DISCONNECT`. */
    fun demand() {
        scope.launch { demandNow() }
    }

    /** The network changed: a waiting reconnect goes now (CONN-02 backoff rule). */
    fun networkChanged() {
        scope.launch {
            if (stateFlow.value == RelayLinkState.BACKOFF) {
                runner?.cancel()
                runner = null
                attempt = 0
            }
            if (demandUntil > clock()) ensureRunning()
        }
    }

    /** `relay.enabled` off, the service stopping, or the device revoked: leave the relay now. */
    fun stop() {
        scope.launch {
            demandUntil = 0
            rendezvous.endAll()
            runner?.cancel()
            runner = null
            connection.close(OFF_REASON)
            stateFlow.value = RelayLinkState.OFF
        }
    }

    /**
     * PAIR-01 step 6: joins the rendezvous [rvId] of a shown code; the first `pair` envelope of the client starts
     * [endpoint] on it. The phone stays connected until [leaveRendezvous].
     */
    fun joinRendezvous(
        rvId: String,
        endpoint: PairingEndpoint,
    ) {
        scope.launch {
            rendezvous.join(rvId, endpoint, connected = connection.isOpen)
            demandNow()
        }
    }

    fun leaveRendezvous(rvId: String) {
        scope.launch { rendezvous.leave(rvId) }
    }

    private fun demandNow() {
        demandUntil = clock() + RelayConstants.IDLE_DISCONNECT_MILLIS
        connection.touch()
        ensureRunning()
    }

    private fun ensureRunning() {
        if (runner?.isActive == true || !owner.allowed()) return
        runner = scope.launch { run() }
    }

    /** Connects while wanted; waits `RECONNECT_BACKOFF` between failed tries. */
    private suspend fun run() {
        while (wanted()) {
            stateFlow.value = RelayLinkState.CONNECTING
            val outcome =
                connection.connect {
                    stateFlow.value = RelayLinkState.CONNECTED
                    attempt = 0
                    owner.connected()
                }
            if (outcome == RelayOutcome.IDLE) demandUntil = 0
            if (outcome == RelayOutcome.STOP || !wanted()) break
            if (outcome == RelayOutcome.CLOSED) attempt = 0
            stateFlow.value = RelayLinkState.BACKOFF
            delay(RelayBackoff.delayMillis(attempt, random))
            attempt++
        }
        stateFlow.value = RelayLinkState.OFF
    }

    private fun wanted(): Boolean =
        owner.allowed() && (demandUntil > clock() || !rendezvous.isEmpty || connection.peerCount > 0)

    private companion object {
        const val OFF_REASON = "off"
    }
}
