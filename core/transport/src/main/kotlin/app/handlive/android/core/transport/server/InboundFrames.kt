package app.handlive.android.core.transport.server

import app.handlive.android.core.transport.WsCloseCode
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readReason
import java.io.ByteArrayOutputStream

/** One message read from a raw `/v1/ctl` WebSocket. */
internal sealed interface InboundMessage {
    class Text(
        val text: String,
    ) : InboundMessage

    /** The peer sent a Close frame ([reason]) or the connection dropped (`null`). */
    class Closed(
        val reason: CloseReason?,
    ) : InboundMessage

    /** Protocol violation on `/v1/ctl` (binary frame, oversized message): close with [reason]. */
    class Violation(
        val reason: CloseReason,
    ) : InboundMessage
}

/**
 * Reads a raw (`webSocketRaw`) session so that every frame — including the client's WebSocket pings, which a
 * default Ktor session answers internally and never shows — refreshes [lastFrameAt]. This is what lets A-SVC close
 * sessions that stay silent for 45 s (CONN-02) without pinging clients itself. Pings are answered with the same
 * payload, fragmented text messages are reassembled up to [maxMessageBytes].
 */
internal class InboundFrames(
    private val socket: WebSocketSession,
    private val maxMessageBytes: Int,
    private val clock: () -> Long,
) {
    @Volatile
    var lastFrameAt: Long = clock()
        private set

    suspend fun receive(): InboundMessage {
        val message = ByteArrayOutputStream()
        var result: InboundMessage? = null
        while (result == null) {
            val frame = socket.incoming.receiveCatching().getOrNull()
            result = if (frame == null) InboundMessage.Closed(null) else accept(frame, message)
        }
        return result
    }

    /** The message this frame completes, or `null` to keep reading. */
    private suspend fun accept(
        frame: Frame,
        message: ByteArrayOutputStream,
    ): InboundMessage? {
        lastFrameAt = clock()
        return when (frame) {
            is Frame.Ping -> {
                runCatching { socket.send(Frame.Pong(frame.data)) }
                null
            }

            is Frame.Pong -> {
                null
            }

            is Frame.Close -> {
                InboundMessage.Closed(frame.readReason())
            }

            is Frame.Binary -> {
                InboundMessage.Violation(BINARY_NOT_ALLOWED)
            }

            is Frame.Text -> {
                appendText(frame, message)
            }
        }
    }

    private fun appendText(
        frame: Frame.Text,
        message: ByteArrayOutputStream,
    ): InboundMessage? {
        if (message.size() + frame.data.size > maxMessageBytes) return InboundMessage.Violation(TOO_BIG)
        message.write(frame.data)
        return if (frame.fin) InboundMessage.Text(String(message.toByteArray(), Charsets.UTF_8)) else null
    }

    private companion object {
        val BINARY_NOT_ALLOWED = CloseReason(WsCloseCode.BAD_REQUEST, "BAD_REQUEST")
        val TOO_BIG = CloseReason(WsCloseCode.MESSAGE_TOO_BIG, "MESSAGE_TOO_BIG")
    }
}
