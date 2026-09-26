package app.handlive.android.core.transport.server

import app.handlive.android.core.crypto.derivation.PeerRole
import app.handlive.android.core.crypto.derivation.SessionKeys
import app.handlive.android.core.protocol.capability.CapabilityData
import app.handlive.android.core.protocol.capability.CapabilityOp
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import app.handlive.android.core.protocol.session.SessionByeData
import app.handlive.android.core.protocol.session.SessionOp
import app.handlive.android.core.transport.WsCloseCode
import app.handlive.android.core.transport.capability.Feature
import app.handlive.android.core.transport.session.SessionCipher
import app.handlive.android.core.transport.session.SessionRekeyCoordinator
import io.ktor.websocket.CloseReason
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** Envelope ứng dụng đã giải mã, giao cho mô-đun tính năng (clipboard, SMS…). Không log [plaintext]. */
class InboundEnvelope(
    val type: String,
    val id: String,
    val ts: Long,
    val plaintext: ByteArray,
)

/** How a `/v1/ctl` session reaches the client (0.11 channel, PAIR-02 field 5). */
enum class SessionTransport {
    /** TLS WebSocket on the LAN (CONN-01). */
    LAN,

    /** Envelopes wrapped `{to, env}` / `{from, env}` through `/v1/relay` (CONN-03). */
    RELAY,
}

/**
 * Một phiên `/v1/ctl` với một client đã ghép nối. Mọi envelope mã hóa bằng khóa theo chiều gửi; `session` và
 * `capability` xử lý trong tầng này, các `type` khác chuyển qua [inbound]. Rekey tự khởi tạo khi đạt `REKEY_AFTER`.
 */
class ControlSession internal constructor(
    val pairId: String,
    val peerDeviceId: String,
    private val socket: WebSocketSession,
    keys: SessionKeys,
    private val config: ControlServerConfig,
    /** Over the LAN or through the relay: the same handshake, keys and envelopes either way (CONN-03 step 9). */
    val transport: SessionTransport = SessionTransport.LAN,
) {
    internal val channel: EncryptedEnvelopeChannel =
        SessionCipher(keys, PeerRole.SERVER, config.options.clock, config.options.rekeyAfterEnvelopes).let { cipher ->
            EncryptedEnvelopeChannel(
                socket,
                cipher,
                SessionRekeyCoordinator(config.localDeviceId, peerDeviceId, cipher),
                config.options.clock,
            ) { close(WsCloseCode.REKEY_FAILED, "REKEY_FAILED") }
        }
    private val inboundChannel = Channel<InboundEnvelope>(Channel.BUFFERED)
    private val stateFlow = MutableStateFlow(ControlConnectionState.AWAITING_CAPABILITY)
    internal val capabilities = PeerCapabilityState(config.localCapability)
    private val byeReasonFlow = MutableStateFlow<String?>(null)
    private val closing = AtomicBoolean(false)

    val state: StateFlow<ControlConnectionState> = stateFlow.asStateFlow()
    val peerCapability: StateFlow<CapabilityData?> = capabilities.peer

    /** Tính năng hiệu lực hiện tại (0.7.2); mô-đun tính năng chỉ chạy khi có tên mình trong tập này. */
    val effectiveFeatures: StateFlow<Set<Feature>> = capabilities.effective
    val inbound: ReceiveChannel<InboundEnvelope> = inboundChannel

    /** `reason` of the peer's `session/bye` (`shutdown`, `revoked`, `update`…), once one arrived (CONN-02 API 4). */
    val byeReason: StateFlow<String?> = byeReasonFlow.asStateFlow()

    /**
     * Gửi một envelope ứng dụng đã mã hóa; trả `id` của envelope. A caller-chosen UUIDv7 [id] lets a request's `ack`
     * waiter exist before the envelope leaves.
     */
    suspend fun send(
        type: MessageType,
        plaintext: ByteArray,
        id: String? = null,
    ): String = if (id == null) channel.send(type.wire, plaintext) else channel.send(type.wire, plaintext, id)

    /** Gửi `capability/update` (ảnh chụp đầy đủ) khi cấu hình Android đổi, và tính lại tính năng hiệu lực. */
    suspend fun sendCapabilityUpdate() {
        val local = config.localCapability()
        channel.send(MessageType.CAPABILITY.wire, capabilityPlaintext(CapabilityOp.UPDATE, local))
        capabilities.recompute(local)
    }

    /** `session/bye` rồi đóng 1000 (CONN-02 API 4). */
    suspend fun bye(reason: String) {
        channel.send(MessageType.SESSION.wire, byePlaintext(reason))
        close(WsCloseCode.NORMAL, reason)
    }

    suspend fun close(
        code: Short,
        message: String,
    ) {
        if (!closing.compareAndSet(false, true)) return
        markClosed()
        socket.close(CloseReason(code, message))
    }

    /** Phiên mới của cùng cặp đã bắt tay xong: `session/bye {reason: replaced}` rồi đóng 4409 (CONN-01 bước 7). */
    internal suspend fun replaced() {
        runCatching { channel.send(MessageType.SESSION.wire, byePlaintext(BYE_REPLACED)) }
        close(WsCloseCode.REPLACED, BYE_REPLACED)
    }

    internal suspend fun sendCapabilityHello() {
        channel.send(MessageType.CAPABILITY.wire, capabilityPlaintext(CapabilityOp.HELLO, config.localCapability()))
    }

    internal fun recordBye(reason: String) {
        byeReasonFlow.value = reason
    }

    internal fun markEstablished() {
        stateFlow.value = ControlConnectionState.ESTABLISHED
    }

    internal fun markClosed() {
        stateFlow.value = ControlConnectionState.CLOSED
        inboundChannel.close()
    }

    internal suspend fun deliver(message: InboundEnvelope) = inboundChannel.send(message)

    private companion object {
        const val BYE_REPLACED = "replaced"
    }
}

private fun capabilityPlaintext(
    op: String,
    data: CapabilityData,
) = PlaintextCodec.encodeOp(op, CapabilityData.serializer(), data)

private fun byePlaintext(reason: String) =
    PlaintextCodec.encodeOp(SessionOp.BYE, SessionByeData.serializer(), SessionByeData(reason))
