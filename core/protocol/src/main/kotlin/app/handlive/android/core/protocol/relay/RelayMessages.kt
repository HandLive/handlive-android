package app.handlive.android.core.protocol.relay

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.ProtocolException
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.envelope.Envelope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** `{"to": <device_id>, "env": <envelope>}`: a device sends an envelope to its peer through the relay (0.4.3). */
@Serializable
data class RelayOutbound(
    val to: String,
    val env: Envelope,
)

/** `{"from": <device_id>, "env": <envelope>}`: an envelope the relay delivers from a peer. */
@Serializable
data class RelayInbound(
    val from: String,
    val env: Envelope,
)

/** Control ops of `/v1/relay` (0.7.3): text frames `{"op": <name>, …}`, never E2E encrypted. */
object RelayOp {
    const val PRESENCE = "presence"
    const val ERROR = "error"
    const val RV_JOIN = "rv_join"
    const val RV_JOINED = "rv_joined"
    const val RV_MSG = "rv_msg"
    const val PAIR_REVOKED = "pair_revoked"
}

/** `presence` (CONN-03 API 5): a hint only; the source of truth for a session is the E2E handshake. */
@Serializable
data class RelayPresence(
    val op: String = RelayOp.PRESENCE,
    @SerialName("pair_id") val pairId: String,
    @SerialName("peer_device_id") val peerDeviceId: String,
    val online: Boolean,
)

/** `error` (CONN-03 API 5): `code` ∈ {NOT_PAIRED, NOT_CONNECTED, PAYLOAD_TOO_LARGE, RATE_LIMITED, BAD_REQUEST}. */
@Serializable
data class RelayError(
    val op: String = RelayOp.ERROR,
    val code: String,
    val message: String,
    val to: String? = null,
)

/** `rv_join` (PAIR-01 API 7): join the pairing rendezvous of the scanned code. */
@Serializable
data class RelayRvJoin(
    val op: String = RelayOp.RV_JOIN,
    @SerialName("rv_id") val rvId: String,
)

/** `rv_joined` (PAIR-01 API 7): [peerPresent] once both members are in the rendezvous. */
@Serializable
data class RelayRvJoined(
    val op: String = RelayOp.RV_JOINED,
    @SerialName("rv_id") val rvId: String,
    @SerialName("peer_present") val peerPresent: Boolean,
)

/** `rv_msg` (PAIR-01 API 7): a `pair` envelope through the rendezvous, both ways. */
@Serializable
data class RelayRvMsg(
    val op: String = RelayOp.RV_MSG,
    @SerialName("rv_id") val rvId: String,
    val env: Envelope,
)

/** `pair_revoked` (PAIR-03 API 4): the pair was revoked by [by]; sent at once and on every new connection. */
@Serializable
data class RelayPairRevoked(
    val op: String = RelayOp.PAIR_REVOKED,
    @SerialName("pair_id") val pairId: String,
    val by: String,
)

/** A text frame from the relay, read by its shape: an envelope from a peer, or a control op. */
sealed interface RelayIncoming {
    class Envelope(
        val message: RelayInbound,
    ) : RelayIncoming

    class Presence(
        val message: RelayPresence,
    ) : RelayIncoming

    class Error(
        val message: RelayError,
    ) : RelayIncoming

    class RvJoined(
        val message: RelayRvJoined,
    ) : RelayIncoming

    class RvMsg(
        val message: RelayRvMsg,
    ) : RelayIncoming

    class PairRevoked(
        val message: RelayPairRevoked,
    ) : RelayIncoming

    /** An op this version does not know: ignored (0.5.1 rule 6). */
    class Unknown(
        val op: String,
    ) : RelayIncoming
}

/** Text frames of `/v1/relay`: what a device writes, and what it reads (0.4.3, 0.7.3). */
object RelayWire {
    fun outbound(
        to: String,
        env: Envelope,
    ): String = ProtocolJson.encodeToString(RelayOutbound.serializer(), RelayOutbound(to, env))

    fun <T> control(
        serializer: KSerializer<T>,
        message: T,
    ): String = ControlJson.encodeToString(serializer, message)

    /** A malformed frame → `ProtocolException(BAD_REQUEST)`. */
    fun decode(text: String): RelayIncoming {
        val json = parse(text)
        val op = (json["op"] as? JsonPrimitive)?.contentOrNull
        return when {
            op == null && json.containsKey("from") -> RelayIncoming.Envelope(read(json, RelayInbound.serializer()))
            op == RelayOp.PRESENCE -> RelayIncoming.Presence(read(json, RelayPresence.serializer()))
            op == RelayOp.ERROR -> RelayIncoming.Error(read(json, RelayError.serializer()))
            op == RelayOp.RV_JOINED -> RelayIncoming.RvJoined(read(json, RelayRvJoined.serializer()))
            op == RelayOp.RV_MSG -> RelayIncoming.RvMsg(read(json, RelayRvMsg.serializer()))
            op == RelayOp.PAIR_REVOKED -> RelayIncoming.PairRevoked(read(json, RelayPairRevoked.serializer()))
            op != null -> RelayIncoming.Unknown(op)
            else -> throw ProtocolException(ErrorCode.BAD_REQUEST, "unknown relay frame")
        }
    }

    /** Control messages always write their `op`, which is a default value of their data classes. */
    private val ControlJson = kotlinx.serialization.json.Json(ProtocolJson) { encodeDefaults = true }

    private fun parse(text: String): JsonObject =
        try {
            ProtocolJson.parseToJsonElement(text).jsonObject
        } catch (e: SerializationException) {
            throw ProtocolException(ErrorCode.BAD_REQUEST, "malformed relay frame", e)
        } catch (e: IllegalArgumentException) {
            throw ProtocolException(ErrorCode.BAD_REQUEST, "malformed relay frame", e)
        }

    private fun <T> read(
        json: JsonElement,
        serializer: KSerializer<T>,
    ): T =
        try {
            ProtocolJson.decodeFromJsonElement(serializer, json)
        } catch (e: SerializationException) {
            throw ProtocolException(ErrorCode.BAD_REQUEST, "malformed relay frame", e)
        } catch (e: IllegalArgumentException) {
            throw ProtocolException(ErrorCode.BAD_REQUEST, "malformed relay frame", e)
        }
}
