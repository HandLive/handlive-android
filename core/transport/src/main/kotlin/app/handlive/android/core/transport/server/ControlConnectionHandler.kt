package app.handlive.android.core.transport.server

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.ProtocolException
import app.handlive.android.core.protocol.envelope.Envelope
import app.handlive.android.core.protocol.envelope.EnvelopeCodec
import app.handlive.android.core.protocol.id.UuidV7Generator
import app.handlive.android.core.transport.WsCloseCode
import app.handlive.android.core.transport.handshake.HandshakeOutcome
import app.handlive.android.core.transport.handshake.ServerHandshake
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Vòng đời một kết nối `/v1/ctl` phía S: kiểm soát nhận kết nối (16 kết nối chưa bắt tay, chặn IP — CONN-01 API 3–4),
 * bắt tay (0.6.3 bước 1–5) trong `HANDSHAKE_TIMEOUT`, rồi vòng nhận envelope mã hóa với bộ canh im lặng 45 s
 * (CONN-02). Không log payload hay plaintext; lỗi chỉ lộ ra qua mã đóng WebSocket (0.8.3).
 */
internal class ControlConnectionHandler(
    private val config: ControlServerConfig,
    private val registry: ActiveSessionRegistry,
) {
    private val clock = config.options.clock
    private val handshake = ServerHandshake(config.localDeviceId, config.pairs, UuidV7Generator(clock), clock)
    val admission = ConnectionAdmission(config.options.limits, clock)

    suspend fun handle(
        socket: WebSocketSession,
        remoteAddress: String,
    ) {
        val ticket = admission.admit(remoteAddress)
        if (ticket == null) {
            socket.close(RATE_LIMITED)
            return
        }
        val frames = InboundFrames(socket, Envelope.MAX_BYTES, clock)
        val result =
            try {
                withTimeoutOrNull(config.options.handshakeTimeout) { establish(socket, frames, remoteAddress) }
            } finally {
                ticket.release()
            }
        when (result) {
            null -> socket.close(CloseReason(WsCloseCode.HANDSHAKE_TIMEOUT, "handshake timeout"))
            is Result.Closed -> result.reason?.let { socket.close(it) }
            is Result.Established -> serve(socket, frames, result.session)
        }
    }

    private suspend fun serve(
        socket: WebSocketSession,
        frames: InboundFrames,
        session: ControlSession,
    ) {
        registry.replace(session)?.replaced()
        config.onSessionEstablished(session)
        val watchdog = socket.launch { closeWhenSilent(socket, frames, session) }
        try {
            var close: CloseReason? = null
            while (close == null) {
                close =
                    when (val message = frames.receive()) {
                        is InboundMessage.Text -> handleText(session, message.text)

                        // Echo the peer's Close (RFC 6455 §5.5.1); a dropped connection has nothing to echo.
                        is InboundMessage.Closed -> message.reason ?: break

                        is InboundMessage.Violation -> message.reason
                    }
            }
            close?.let { session.close(it.code, it.message) }
        } finally {
            watchdog.cancel()
            session.markClosed()
            registry.remove(session)
        }
    }

    /** No frame at all for `idleTimeout` → close 4411, then drop the connection if the peer never answers. */
    private suspend fun closeWhenSilent(
        socket: WebSocketSession,
        frames: InboundFrames,
        session: ControlSession,
    ) {
        val timeoutMillis = config.options.limits.idleTimeout.inWholeMilliseconds
        while (true) {
            val silentFor = clock() - frames.lastFrameAt
            if (silentFor >= timeoutMillis) break
            delay(timeoutMillis - silentFor)
        }
        session.close(WsCloseCode.IDLE_TIMEOUT, "IDLE_TIMEOUT")
        delay(CLOSE_GRACE_MILLIS)
        socket.cancel()
    }

    private sealed interface Result {
        class Established(
            val session: ControlSession,
        ) : Result

        class Closed(
            val reason: CloseReason?,
        ) : Result
    }

    /** `session/hello` → `welcome` + `capability/hello` của S; hoặc `session/error` rồi đóng. */
    private suspend fun establish(
        socket: WebSocketSession,
        frames: InboundFrames,
        remoteAddress: String,
    ): Result {
        val hello = frames.receive()
        if (hello !is InboundMessage.Text) return Result.Closed(hello.handshakeCloseReason())
        return when (val outcome = handshake.respond(hello.text)) {
            is HandshakeOutcome.Rejected -> {
                if (outcome.code == ErrorCode.AUTH_FAILED) admission.recordAuthFailure(remoteAddress)
                outcome.error?.let { socket.send(Frame.Text(EnvelopeCodec.encode(it))) }
                Result.Closed(CloseReason(outcome.closeCode, outcome.code.name))
            }

            is HandshakeOutcome.Accepted -> {
                socket.send(Frame.Text(EnvelopeCodec.encode(outcome.welcome)))
                val session =
                    ControlSession(outcome.pair.pairId, outcome.pair.peerDeviceId, socket, outcome.keys, config)
                session.sendCapabilityHello()
                confirmKeys(frames, session)
            }
        }
    }

    /** Envelope mã hóa đầu tiên của C phải là `capability/hello` giải mã được (0.6.3 bước 4, CONN-01 API 5). */
    private suspend fun confirmKeys(
        frames: InboundFrames,
        session: ControlSession,
    ): Result {
        val first = frames.receive()
        if (first !is InboundMessage.Text) return Result.Closed(first.handshakeCloseReason())
        val close =
            try {
                firstCapabilityClose(session, first.text)
            } catch (e: ProtocolException) {
                // Không giải mã được → khóa không khớp → 4401.
                val code = if (e.code == ErrorCode.DECRYPT_FAILED) WsCloseCode.AUTH_FAILED else WsCloseCode.BAD_REQUEST
                CloseReason(code, e.code.name)
            }
        return close?.let(Result::Closed) ?: Result.Established(session.also { it.markEstablished() })
    }

    private suspend fun firstCapabilityClose(
        session: ControlSession,
        text: String,
    ): CloseReason? {
        val envelope = EnvelopeCodec.decode(text)
        val plaintext = session.channel.open(envelope)
        val (capability, close) =
            if (ControlSessionDispatcher.isCapabilityHello(envelope, plaintext)) {
                ControlSessionDispatcher.capabilityOrClose(plaintext)
            } else {
                null to BAD_REQUEST
            }
        capability?.let(session.capabilities::apply)
        return close ?: BAD_REQUEST.takeIf { capability == null }
    }

    private suspend fun handleText(
        session: ControlSession,
        text: String,
    ): CloseReason? =
        try {
            val envelope = EnvelopeCodec.decode(text)
            val plaintext = session.channel.open(envelope)
            ControlSessionDispatcher.dispatch(session, envelope, plaintext).also {
                if (it == null) session.channel.startRekeyIfDue()
            }
        } catch (e: ProtocolException) {
            // `DECRYPT_FAILED` sau bắt tay → 4400, client kết nối lại (CONN-02 E5).
            CloseReason(WsCloseCode.BAD_REQUEST, e.code.name)
        }

    private companion object {
        val BAD_REQUEST = CloseReason(WsCloseCode.BAD_REQUEST, "BAD_REQUEST")
        val RATE_LIMITED = CloseReason(WsCloseCode.RATE_LIMITED, "RATE_LIMITED")

        /** After closing a silent session, wait this long for the peer's Close before dropping the TCP connection. */
        const val CLOSE_GRACE_MILLIS = 2_000L
    }
}

/**
 * What to send when the handshake ends on a non-text message: the violation's reason, or nothing when the client
 * closed or dropped the connection before finishing the handshake.
 */
private fun InboundMessage.handshakeCloseReason(): CloseReason? = (this as? InboundMessage.Violation)?.reason
