package app.handlive.android.feature.connection.session

import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.core.protocol.capability.CapabilityData
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import app.handlive.android.core.protocol.id.UuidV7Generator
import app.handlive.android.core.transport.TransportConstants
import app.handlive.android.core.transport.capability.Feature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/** Sends an encrypted envelope with a given UUIDv7 `id` on one `/v1/ctl` session (the transport's `ControlSession`). */
fun interface EnvelopeSender {
    suspend fun send(
        type: MessageType,
        plaintext: ByteArray,
        id: String,
    )
}

/** A request whose `ack` did not arrive within `REQUEST_TIMEOUT` (0.5.1 rule 1) — the caller treats it as `TIMEOUT`. */
class AckTimeoutException : Exception("TIMEOUT")

/**
 * A connected client as the feature modules see it: who it is, what is effective between the two devices, and
 * how to talk to it. Requests are correlated with their `ack` by `id`; incoming requests are de-duplicated.
 */
class PeerSession(
    val peer: PeerInfo,
    val channel: Channel,
    val effectiveFeatures: StateFlow<Set<Feature>>,
    val peerCapability: StateFlow<CapabilityData?>,
    private val sender: EnvelopeSender,
    clock: () -> Long,
) {
    /** Who the client is (the pair row of `paired_device`). */
    data class PeerInfo(
        val pairId: String,
        val peerDeviceId: String,
        val peerName: String,
        val peerPlatform: PeerPlatform,
    )

    /** How the session reaches the phone (0.11, PAIR-02 field 5); Phase 1 serves the LAN only. */
    enum class Channel { LAN, RELAY, USB }

    val pairId: String get() = peer.pairId
    val peerDeviceId: String get() = peer.peerDeviceId
    val peerName: String get() = peer.peerName
    val peerPlatform: PeerPlatform get() = peer.peerPlatform

    private val ids = UuidV7Generator(clock)
    private val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<Ack>>()
    private val processedLock = Mutex()
    internal val processed = ProcessedEnvelopeCache(clock)

    fun isEffective(feature: Feature): Boolean = feature in effectiveFeatures.value

    /** Sends an event (no `ack`); returns its `id`. */
    suspend fun send(
        type: MessageType,
        plaintext: ByteArray,
    ): String = ids.next().also { sender.send(type, plaintext, it) }

    /**
     * Sends a request and waits for its `ack` (0.5.1 rule 1); throws [AckTimeoutException] after [timeout], counted
     * from the end of [afterSend] — a chunked clip sends its chunks there and the 10 s start after the last one
     * (CLIP-03 API 3 rule 3). [afterSend] gets the request's `id`.
     */
    suspend fun request(
        type: MessageType,
        plaintext: ByteArray,
        timeout: Duration = TransportConstants.REQUEST_TIMEOUT,
        afterSend: suspend (String) -> Unit = {},
    ): Ack {
        val id = ids.next()
        val waiter = CompletableDeferred<Ack>()
        pendingAcks[id] = waiter
        return try {
            sender.send(type, plaintext, id)
            afterSend(id)
            withTimeoutOrNull(timeout) { waiter.await() } ?: throw AckTimeoutException()
        } finally {
            pendingAcks.remove(id)
        }
    }

    /** Sends the `ack` of request [re] and keeps it for duplicates. */
    suspend fun sendAck(ack: Ack) {
        processedLock.withLock { processed.recordAck(ack) }
        send(MessageType.ACK, PlaintextCodec.encodeAck(ack))
    }

    suspend fun sendError(
        re: String,
        code: ErrorCode,
        message: String,
    ) = sendAck(Ack.failure(re, code, message))

    /** An `ack` for no waiter (late, after a timeout) is dropped. */
    internal fun completeAck(ack: Ack) {
        pendingAcks[ack.re]?.complete(ack)
    }

    /** `true` when [id] is new (and is now remembered); a duplicate gets its old `ack` again, if one was sent. */
    internal suspend fun firstDelivery(id: String): Boolean {
        val duplicateAck =
            processedLock.withLock {
                val entry = processed.find(id)
                if (entry == null) {
                    processed.remember(id)
                    return true
                }
                entry.ack
            }
        duplicateAck?.let { send(MessageType.ACK, PlaintextCodec.encodeAck(it)) }
        return false
    }
}
