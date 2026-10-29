package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.protocol.bluetooth.BtLinkConstants
import app.handlive.android.core.protocol.bluetooth.BtLinkException
import app.handlive.android.core.protocol.bluetooth.BtTransportFrame
import app.handlive.android.core.protocol.envelope.Envelope
import app.handlive.android.core.protocol.envelope.EnvelopeCodec
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.security.GeneralSecurityException
import kotlin.coroutines.CoroutineContext

/**
 * Một phiên `/v1/ctl` qua liên kết Bluetooth đã qua bắt tay bảo mật liên kết (0.4.5 bước 4-5, #191): như
 * `RelayPeerSocket` của relay, `ControlServer.serveBluetoothPeer` nhận socket ảo này mà không đụng tới dây L2CAP
 * bên dưới — E2E `session/hello` (0.6.3) chạy y nguyên bên trong (0.4.5 bước 6). [sendCipher]/[receiveCipher] là
 * hai nửa của `Split()`; việc cắt mảnh/ưu tiên là [BluetoothLinkScheduler], ghép lại làn dài là
 * [BluetoothLongLaneReassembler] (cả hai của #191). Kênh bulk (`chan` = 0x02) bỏ qua ở đây — nội dung của nó
 * thuộc 0.4.7 (A7.4), ngoài phạm vi thẻ A7.1b. [onFirstRecordOpened]/[onFirstRecordRejected] báo bước 5 cho
 * `BluetoothLinkAcceptor` (admission, `BT_PREAUTH_TIMEOUT`).
 */
internal data class BluetoothPeerSocketCallbacks(
    val onClosed: (BluetoothPeerSocket) -> Unit = {},
    val onFirstRecordOpened: () -> Unit = {},
    val onFirstRecordRejected: () -> Unit = {},
)

internal class BluetoothPeerSocket(
    private val io: BluetoothLinkIo,
    private val sendCipher: BtTransportCipher,
    private val receiveCipher: BtTransportCipher,
    parent: CoroutineScope,
    private val callbacks: BluetoothPeerSocketCallbacks = BluetoothPeerSocketCallbacks(),
) : WebSocketSession {
    override val coroutineContext: CoroutineContext =
        parent.coroutineContext + SupervisorJob(parent.coroutineContext.job)

    private val incomingFrames = Channel<Frame>(Channel.UNLIMITED)
    private val outgoingFrames = Channel<Frame>(Channel.UNLIMITED)
    private val scheduler = BluetoothLinkScheduler()
    private val reassembler = BluetoothLongLaneReassembler()
    private var anyRecordOpened = false

    override val incoming: ReceiveChannel<Frame> = incomingFrames
    override val outgoing: SendChannel<Frame> = outgoingFrames
    override val extensions: List<WebSocketExtension<*>> = emptyList()
    override var masking: Boolean = false
    override var maxFrameSize: Long = Envelope.MAX_BYTES.toLong()

    init {
        coroutineContext.job.invokeOnCompletion { end() }
        launch { readLoop() }
        launch { writeLoop() }
    }

    private suspend fun readLoop() {
        try {
            while (true) {
                val body = io.readRecord()
                val plaintext = openRecord(body)
                val frame = BtTransportFrame.decode(plaintext)
                if (frame.chan == BtLinkConstants.CHAN_CONTROL) deliverControlFrame(frame)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: BtLinkException) {
            end()
        } catch (_: GeneralSecurityException) {
            end()
        }
    }

    /** Bản ghi đầu tiên giải mã được là bước 5 của 0.4.5 (liên kết được xác nhận); hỏng ở đó là lỗi xác nhận khoá. */
    private fun openRecord(body: ByteArray): ByteArray {
        val plaintext =
            try {
                receiveCipher.open(body)
            } catch (e: GeneralSecurityException) {
                if (!anyRecordOpened) callbacks.onFirstRecordRejected()
                throw e
            }
        if (!anyRecordOpened) {
            anyRecordOpened = true
            callbacks.onFirstRecordOpened()
        }
        return plaintext
    }

    private fun deliverControlFrame(frame: BtTransportFrame) {
        val message = if (frame.long) reassembler.accept(frame) else frame.data
        message?.let { incomingFrames.trySend(Frame.Text(it.toString(Charsets.UTF_8))) }
    }

    private suspend fun writeLoop() {
        try {
            for (frame in outgoingFrames) {
                when (frame) {
                    is Frame.Text -> enqueueEnvelope(frame.readText())
                    is Frame.Close -> break
                    else -> Unit
                }
                drainScheduler()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: BtLinkException) {
            // The link died mid-write (`more`/`dlen` only this side can produce correctly) — nothing to retry.
        }
        end()
    }

    private fun enqueueEnvelope(json: String) {
        val type = EnvelopeCodec.decode(json).type
        scheduler.enqueueControlEnvelope(type, json.toByteArray(Charsets.UTF_8))
    }

    private suspend fun drainScheduler() {
        var plaintext = scheduler.poll()
        while (plaintext != null) {
            io.writeRecord(sendCipher.seal(plaintext))
            scheduler.onDrained()
            plaintext = scheduler.poll()
        }
    }

    /** Dây L2CAP đứt hoặc phiên đóng: đóng cả hai hàng đợi và dây vật lý bên dưới. */
    fun end() {
        incomingFrames.close()
        outgoingFrames.close()
        io.close()
        callbacks.onClosed(this)
    }

    override suspend fun flush() = Unit

    @Deprecated("Use cancel() instead.", ReplaceWith("cancel()", "kotlinx.coroutines.cancel"))
    override fun terminate() {
        end()
        cancel()
    }
}
