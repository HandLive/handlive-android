package app.handlive.android.core.transport.handshake

import app.handlive.android.core.protocol.envelope.Envelope
import app.handlive.android.core.protocol.envelope.EnvelopeHeader
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.protocol.envelope.OpPayload
import app.handlive.android.core.protocol.envelope.UnencryptedEnvelopes
import kotlinx.serialization.KSerializer

/** Envelope bắt tay `session/hello|welcome|error`: payload = b64 của JSON chưa mã hóa (0.5.1 ngoại lệ 1). */
internal object HandshakeEnvelopes {
    fun <T> build(
        id: String,
        ts: Long,
        op: String,
        serializer: KSerializer<T>,
        data: T,
    ): Envelope = UnencryptedEnvelopes.build(EnvelopeHeader(MessageType.SESSION.wire, id, ts), op, serializer, data)

    /** Payload sai b64 hoặc sai cấu trúc → `ProtocolException(BAD_REQUEST)`. */
    fun <T> read(
        envelope: Envelope,
        serializer: KSerializer<T>,
    ): OpPayload<T> = UnencryptedEnvelopes.read(envelope, serializer)
}
