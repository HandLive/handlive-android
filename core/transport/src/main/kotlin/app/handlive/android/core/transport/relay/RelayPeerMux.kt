package app.handlive.android.core.transport.relay

import app.handlive.android.core.protocol.envelope.Envelope
import app.handlive.android.core.protocol.envelope.EnvelopeCodec
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.protocol.envelope.UnencryptedEnvelopes
import app.handlive.android.core.protocol.session.SessionOp
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the `/v1/ctl` sessions of the peers that reach this phone through the relay (CONN-03 step 9). A
 * `session/hello` from a peer starts a new virtual session with [serve] (the control server's handshake, exactly as
 * on the LAN); later envelopes go to that peer's current session. Envelopes of a peer without a session are dropped:
 * the client sees no answer and starts over with a new handshake.
 */
class RelayPeerMux(
    private val scope: CoroutineScope,
    private val send: (String) -> Boolean,
    private val serve: suspend (WebSocketSession, String) -> Unit,
) {
    private val peers = ConcurrentHashMap<String, RelayPeerSocket>()

    /** Peers with a virtual session open (handshaking or established). */
    val peerCount: Int get() = peers.size

    fun onEnvelope(
        from: String,
        env: Envelope,
    ) {
        val text = EnvelopeCodec.encode(env)
        if (isSessionHello(env)) {
            val socket = RelayPeerSocket(from, scope, send) { peers.remove(it.peerDeviceId, it) }
            peers[from] = socket
            socket.deliver(text)
            scope.launch {
                try {
                    serve(socket, from)
                } finally {
                    socket.end()
                }
            }
        } else {
            peers[from]?.deliver(text)
        }
    }

    /** The peer is offline (`presence`) or cannot be reached (`error NOT_CONNECTED`): end its session. */
    fun onPeerGone(deviceId: String) {
        peers[deviceId]?.end()
    }

    /** The relay link is closed: every session through it ends. */
    fun endAll() {
        peers.values.toList().forEach { it.end() }
    }

    private fun isSessionHello(env: Envelope): Boolean =
        env.type == MessageType.SESSION.wire &&
            runCatching { UnencryptedEnvelopes.op(env) }.getOrNull() == SessionOp.HELLO
}
