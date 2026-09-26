package app.handlive.android.core.transport.relay

import app.handlive.android.core.protocol.envelope.Envelope
import app.handlive.android.core.protocol.envelope.EnvelopeCodec
import app.handlive.android.core.protocol.relay.RelayOp
import app.handlive.android.core.transport.server.TextMessageSocket
import kotlinx.coroutines.channels.Channel

/**
 * The pairing exchange through a relay rendezvous (PAIR-01 API 7), as the pairing endpoint reads `/v1/pair`: the
 * `pair` envelopes of `rv_msg` in, `rv_msg` out. The relay forwards them without reading them; the PIN never goes
 * this way (only QR windows carry `rv`).
 */
class RelayRendezvous(
    val rvId: String,
    private val send: (String) -> Boolean,
) : TextMessageSocket {
    private val incoming = Channel<String>(Channel.UNLIMITED)

    override val remoteAddress: String = REMOTE_ADDRESS

    fun deliver(env: Envelope): Boolean = incoming.trySend(EnvelopeCodec.encode(env)).isSuccess

    override suspend fun receiveText(): String? = incoming.receiveCatching().getOrNull()

    override suspend fun sendText(text: String) {
        send("""{"op":"${RelayOp.RV_MSG}","rv_id":"$rvId","env":$text}""")
    }

    override suspend fun close(
        code: Short,
        message: String,
    ) = end()

    /** The window closed or the link dropped: the exchange reading this rendezvous sees it end. */
    fun end() {
        incoming.close()
    }

    companion object {
        /** Not an IP address: the pairing checks that need the LAN (the PIN) refuse it. */
        const val REMOTE_ADDRESS = "relay"
    }
}
