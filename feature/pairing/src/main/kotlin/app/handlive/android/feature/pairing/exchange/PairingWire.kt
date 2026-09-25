package app.handlive.android.feature.pairing.exchange

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.envelope.EnvelopeCodec
import app.handlive.android.core.protocol.envelope.EnvelopeHeader
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.protocol.envelope.UnencryptedEnvelopes
import app.handlive.android.core.protocol.id.UuidV7Generator
import app.handlive.android.core.protocol.pairing.PairErrorData
import app.handlive.android.core.protocol.pairing.PairOp
import app.handlive.android.core.transport.WsCloseCode
import app.handlive.android.core.transport.server.TextMessageSocket
import kotlinx.serialization.KSerializer

/** `type = pair` envelopes on `/v1/pair`: unencrypted payloads (0.5.1 exception 1), English diagnostics only. */
class PairingWire(
    private val clock: () -> Long,
) {
    private val ids = UuidV7Generator(clock)

    suspend fun <T> send(
        socket: TextMessageSocket,
        op: String,
        serializer: KSerializer<T>,
        data: T,
    ) {
        val envelope =
            UnencryptedEnvelopes.build(
                EnvelopeHeader(MessageType.PAIR.wire, ids.next(), clock()),
                op,
                serializer,
                data,
            )
        socket.sendText(EnvelopeCodec.encode(envelope))
    }

    /** `data` of a `pair/<op>` message, or `null` for anything else. */
    fun <T> readOrNull(
        text: String,
        op: String,
        serializer: KSerializer<T>,
    ): T? =
        runCatching {
            val envelope = EnvelopeCodec.decode(text)
            require(envelope.type == MessageType.PAIR.wire && UnencryptedEnvelopes.op(envelope) == op)
            UnencryptedEnvelopes.read(envelope, serializer).data
        }.getOrNull()

    /** `pair/error` then close (API 6); `AUTH_FAILED` never says which check failed. */
    suspend fun refuse(
        socket: TextMessageSocket,
        code: ErrorCode,
    ) {
        runCatching {
            send(
                socket,
                PairOp.ERROR,
                PairErrorData.serializer(),
                PairErrorData(code.name, diagnostic(code)),
            )
        }
        socket.close(WsCloseCode.NORMAL, code.name)
    }

    private fun diagnostic(code: ErrorCode) =
        when (code) {
            ErrorCode.PAIRING_CLOSED -> "Pairing window is closed"
            ErrorCode.AUTH_FAILED -> "Pairing authentication failed"
            else -> "Pairing failed"
        }
}
