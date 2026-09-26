package app.handlive.android.feature.connection.session

import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.core.data.pairing.PairStore
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.capability.CapabilityData
import app.handlive.android.core.transport.server.ControlSession
import app.handlive.android.core.transport.server.SessionTransport
import app.handlive.android.feature.connection.SessionEnded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * The open sessions as the feature modules see them (CONN-01 steps 9–10): one [PeerSession] per pair, over the LAN
 * or the relay, fed to the [router] until it closes. The ledger of processed `id`s outlives the session, so a switch
 * between the LAN and the relay loses no `ack`.
 */
class SessionTable(
    private val pairs: PairStore,
    private val router: SessionRouter,
    private val clock: () -> Long,
) {
    private val sessionsFlow = MutableStateFlow<Map<String, PeerSession>>(emptyMap())
    private val endedFlow = MutableSharedFlow<SessionEnded>(extraBufferCapacity = ENDED_BUFFER)
    private val ledgers = ConcurrentHashMap<String, EnvelopeLedger>()

    val sessions: StateFlow<Map<String, PeerSession>> = sessionsFlow.asStateFlow()
    val ended: SharedFlow<SessionEnded> = endedFlow.asSharedFlow()

    /** Runs until [control] closes; records `last_seen_at` and every capability of the peer (CONN-01 step 10). */
    suspend fun attach(
        control: ControlSession,
        scope: CoroutineScope,
    ) {
        val device = pairs.find(control.pairId)
        val channel =
            if (control.transport ==
                SessionTransport.RELAY
            ) {
                PeerSession.Channel.RELAY
            } else {
                PeerSession.Channel.LAN
            }
        val peer =
            PeerSession(
                peer =
                    PeerSession.PeerInfo(
                        pairId = control.pairId,
                        peerDeviceId = control.peerDeviceId,
                        peerName = device?.peerName.orEmpty(),
                        peerPlatform = device?.peerPlatform ?: PeerPlatform.MACOS,
                    ),
                channel = channel,
                effectiveFeatures = control.effectiveFeatures,
                peerCapability = control.peerCapability,
                sender = { type, plaintext, id -> control.send(type, plaintext, id) },
                clock = clock,
            ).also { it.ledger = ledgers.getOrPut(control.pairId) { EnvelopeLedger(clock) } }
        sessionsFlow.update { it + (control.pairId to peer) }
        val recorder =
            control.peerCapability
                .filterNotNull()
                .onEach {
                    pairs.recordSeen(
                        control.pairId,
                        ProtocolJson.encodeToString(CapabilityData.serializer(), it),
                    )
                }.launchIn(scope)
        try {
            for (message in control.inbound) router.route(peer, message)
        } finally {
            recorder.cancel()
            sessionsFlow.update { open -> if (open[control.pairId] === peer) open - control.pairId else open }
            endedFlow.tryEmit(SessionEnded(control.pairId, control.byeReason.value, channel))
        }
    }

    /** The service stopped: no session remains. */
    fun clear() {
        sessionsFlow.value = emptyMap()
    }

    private companion object {
        const val ENDED_BUFFER = 16
    }
}
