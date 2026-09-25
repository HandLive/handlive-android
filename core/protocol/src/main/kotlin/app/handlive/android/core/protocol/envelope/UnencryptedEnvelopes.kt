package app.handlive.android.core.protocol.envelope

import kotlinx.serialization.KSerializer

/**
 * Handshake envelopes whose payload is the b64 of plain JSON because no key exists yet (0.5.1 exception 1):
 * `pair/hello|offer|confirm|done|error`, `session/hello|welcome|error`, and `stream_hello|stream_welcome`.
 */
object UnencryptedEnvelopes {
    fun <T> build(
        header: EnvelopeHeader,
        op: String,
        serializer: KSerializer<T>,
        data: T,
    ): Envelope {
        val json = PlaintextCodec.encodeOp(op, serializer, data).toString(Charsets.UTF_8)
        return Envelope(header.v, header.type, header.id, header.ts, EnvelopeCodec.unencryptedPayload(json))
    }

    /** Bad b64 or a malformed payload → `ProtocolException(BAD_REQUEST)`. */
    fun <T> read(
        envelope: Envelope,
        serializer: KSerializer<T>,
    ): OpPayload<T> =
        PlaintextCodec.decodeOp(EnvelopeCodec.readUnencryptedPayload(envelope).toByteArray(Charsets.UTF_8), serializer)

    /** The `op` of an unencrypted payload, without decoding `data`. */
    fun op(envelope: Envelope): String =
        PlaintextCodec.decodePayload(EnvelopeCodec.readUnencryptedPayload(envelope).toByteArray(Charsets.UTF_8)).op
}
