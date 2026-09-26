package app.handlive.android.core.transport.relay

import app.handlive.android.core.protocol.envelope.Envelope
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * A peer's relayed session as the modules outside the transport pass it along: they hand it to
 * `ControlServer.serveRelayPeer` without touching the WebSocket underneath.
 */
class RelayPeerLink internal constructor(
    internal val socket: RelayPeerSocket,
) {
    val peerDeviceId: String get() = socket.peerDeviceId
}

/**
 * One peer's `/v1/ctl` session as the control server sees it, carried by the relay (CONN-03 step 9): envelopes the
 * relay delivers `{from: peer, env}` arrive as text frames, and every text frame the session sends leaves as
 * `{to: peer, env}` — byte for byte the envelope JSON of the LAN. There is no per-peer close on the relay: a close
 * only ends the virtual session here (the client notices through its E2E pings, CONN-02).
 */
internal class RelayPeerSocket(
    val peerDeviceId: String,
    parent: CoroutineScope,
    private val sendWrapped: (String) -> Boolean,
    private val onClosed: (RelayPeerSocket) -> Unit,
) : WebSocketSession {
    override val coroutineContext: CoroutineContext =
        parent.coroutineContext + SupervisorJob(parent.coroutineContext.job)

    private val incomingFrames = Channel<Frame>(Channel.UNLIMITED)
    private val outgoingFrames = Channel<Frame>(Channel.UNLIMITED)

    override val incoming: ReceiveChannel<Frame> = incomingFrames
    override val outgoing: SendChannel<Frame> = outgoingFrames
    override val extensions: List<WebSocketExtension<*>> = emptyList()
    override var masking: Boolean = false
    override var maxFrameSize: Long = Envelope.MAX_BYTES.toLong()

    init {
        // Cancelled by the session (idle close drops the connection): the socket ends with it.
        coroutineContext.job.invokeOnCompletion { end() }
        launch {
            for (frame in outgoingFrames) {
                when (frame) {
                    is Frame.Text -> sendWrapped("""{"to":"$peerDeviceId","env":${frame.readText()}}""")

                    is Frame.Close -> break

                    // Pongs and pings have no meaning on the relay (the link has its own), binary never occurs.
                    else -> Unit
                }
            }
            end()
        }
    }

    /** An envelope from the peer, as the JSON text the session reads. */
    fun deliver(envelopeJson: String): Boolean = incomingFrames.trySend(Frame.Text(envelopeJson)).isSuccess

    /** The peer left the relay or the link dropped: the session sees its connection end. */
    fun end() {
        incomingFrames.close()
        outgoingFrames.close()
        onClosed(this)
    }

    override suspend fun flush() = Unit

    @Deprecated("Use cancel() instead.", ReplaceWith("cancel()", "kotlinx.coroutines.cancel"))
    override fun terminate() {
        end()
        cancel()
    }
}
