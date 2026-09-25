package app.handlive.android.core.transport.server

import app.handlive.android.core.protocol.envelope.Envelope
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close

/** A WebSocket that carries only text messages, as the pairing exchange `/v1/pair` sees it (PAIR-01 API 2–6). */
interface TextMessageSocket {
    /** IP address of the client, for logs-free checks such as "PIN only from the LAN". */
    val remoteAddress: String

    /** The next text message; `null` once the client closed, dropped or broke the protocol (binary, oversized). */
    suspend fun receiveText(): String?

    suspend fun sendText(text: String)

    suspend fun close(
        code: Short,
        message: String,
    )
}

/** Handles one `/v1/pair` connection; installed by the pairing feature (PAIR-01), which owns the pairing window. */
fun interface PairingEndpoint {
    suspend fun handle(socket: TextMessageSocket)
}

/** [TextMessageSocket] over a raw Ktor session, reusing the `/v1/ctl` frame reader (pings answered, 256 KiB cap). */
internal class RawTextMessageSocket(
    private val socket: WebSocketSession,
    override val remoteAddress: String,
    clock: () -> Long,
) : TextMessageSocket {
    private val frames = InboundFrames(socket, Envelope.MAX_BYTES, clock)

    override suspend fun receiveText(): String? = (frames.receive() as? InboundMessage.Text)?.text

    override suspend fun sendText(text: String) = socket.send(Frame.Text(text))

    override suspend fun close(
        code: Short,
        message: String,
    ) = socket.close(CloseReason(code, message))
}
