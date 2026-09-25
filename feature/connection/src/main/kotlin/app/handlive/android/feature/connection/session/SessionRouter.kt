package app.handlive.android.feature.connection.session

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.ProtocolException
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import app.handlive.android.core.transport.server.InboundEnvelope
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/** Handles every envelope of one `type` (clipboard, pair…) for a feature module; runs on the session's receive loop. */
fun interface EnvelopeHandler {
    suspend fun handle(
        session: PeerSession,
        envelope: InboundEnvelope,
    )
}

/**
 * Routes decrypted envelopes of a session (0.5.1): `ack`s complete the matching request; a repeated `id` gets its
 * old `ack` back (rule 2); other types go to the handler registered for them. A type without a handler — a feature
 * Android does not implement in this version — gets `UNSUPPORTED_TYPE` when the message is a request and is
 * ignored when it is an event (rule 3). `ping/ping` (CONN-02 API 2) is answered here.
 */
class SessionRouter(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val handlers = ConcurrentHashMap<String, EnvelopeHandler>()

    fun register(
        type: MessageType,
        handler: EnvelopeHandler,
    ) {
        handlers[type.wire] = handler
    }

    suspend fun route(
        session: PeerSession,
        envelope: InboundEnvelope,
    ) {
        if (envelope.type == MessageType.ACK.wire) {
            decodeAck(envelope.plaintext)?.let(session::completeAck)
            return
        }
        if (!session.firstDelivery(envelope.id)) return
        val handler = handlers[envelope.type]
        when {
            handler != null -> handler.handle(session, envelope)
            envelope.type == MessageType.PING.wire -> answerPing(session, envelope)
            isRequest(envelope) -> session.sendError(envelope.id, ErrorCode.UNSUPPORTED_TYPE, "unsupported type")
        }
    }

    /** CONN-02 API 2: `ack` `{seq, server_ts}`; any other `ping` op is an unknown event and ignored. */
    private suspend fun answerPing(
        session: PeerSession,
        envelope: InboundEnvelope,
    ) {
        val payload = decodePayloadOrNull(envelope.plaintext)?.takeIf { it.op == PING_OP } ?: return
        val seq = (payload.data["seq"] as? JsonPrimitive)?.longOrNull
        val ack =
            if (seq == null) {
                Ack.failure(envelope.id, ErrorCode.BAD_REQUEST, "missing seq")
            } else {
                Ack.success(
                    envelope.id,
                    buildJsonObject {
                        put("seq", seq)
                        put("server_ts", clock())
                    },
                )
            }
        session.sendAck(ack)
    }

    private fun isRequest(envelope: InboundEnvelope): Boolean {
        val op = decodePayloadOrNull(envelope.plaintext)?.op ?: return false
        return REQUEST_OPS[envelope.type]?.contains(op) == true
    }

    private fun decodeAck(plaintext: ByteArray): Ack? =
        try {
            PlaintextCodec.decodeAck(plaintext)
        } catch (_: ProtocolException) {
            null
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun decodePayloadOrNull(plaintext: ByteArray) =
        try {
            PlaintextCodec.decodePayload(plaintext)
        } catch (_: ProtocolException) {
            null
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    companion object {
        const val PING_OP = "ping"

        /** Operations that expect an `ack` (0.7.1 column "Ack"); everything else is an event. */
        val REQUEST_OPS: Map<String, Set<String>> =
            mapOf(
                "pair" to setOf("revoke"),
                "ping" to setOf("ping"),
                "clipboard" to setOf("push"),
                "sms" to setOf("sync", "history", "send"),
                "call_event" to setOf("action", "log_sync"),
                "call_audio" to setOf("open", "close"),
                "camera" to setOf("start", "stop", "config"),
            )

        /** Serializer shared by feature handlers that decode `data` objects. */
        val json = ProtocolJson
    }
}
