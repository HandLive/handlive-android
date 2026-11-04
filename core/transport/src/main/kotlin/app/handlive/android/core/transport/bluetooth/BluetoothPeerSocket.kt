package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.protocol.bluetooth.BtLinkConstants
import app.handlive.android.core.protocol.bluetooth.BtLinkException
import app.handlive.android.core.protocol.bluetooth.BtTransportFrame
import app.handlive.android.core.protocol.envelope.Envelope
import app.handlive.android.core.transport.BulkLaneSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.security.GeneralSecurityException
import kotlin.coroutines.CoroutineContext

/**
 * Một phiên `/v1/ctl` qua liên kết Bluetooth đã qua bắt tay bảo mật liên kết (0.4.5 bước 4-5, #191): như
 * `RelayPeerSocket` của relay, `ControlServer.serveBluetoothPeer` nhận socket ảo này mà không đụng tới dây L2CAP
 * bên dưới — E2E `session/hello` (0.6.3) chạy y nguyên bên trong (0.4.5 bước 6). [sendCipher]/[receiveCipher] là
 * hai nửa của `Split()`; việc cắt mảnh/ưu tiên là [BluetoothLinkScheduler] (vòng ghi: [BluetoothLinkWritePump]),
 * ghép lại làn dài là [BluetoothLongLaneReassembler], làn bulk (`chan` = 0x02, 0.4.7) là [BluetoothBulkLane] và
 * [sendBulk] (cả ba của #191/A7.4). [onFirstRecordOpened]/[onFirstRecordRejected] báo bước 5 cho
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
) : WebSocketSession,
    BulkLaneSocket {
    override val coroutineContext: CoroutineContext =
        parent.coroutineContext + SupervisorJob(parent.coroutineContext.job)

    private val incomingFrames = Channel<Frame>(Channel.UNLIMITED)
    private val outgoingFrames = Channel<Frame>(Channel.UNLIMITED)
    private val reassembler = BluetoothLongLaneReassembler()
    private val bulkLane = BluetoothBulkLane()
    private val writePump =
        BluetoothLinkWritePump(
            outgoing = outgoingFrames,
            writePlaintext = { io.writeRecord(sendCipher.seal(it)) },
            onBulkEnvelopeWritten = bulkLane::onEnvelopeWritten,
        )
    private var anyRecordOpened = false

    /** Vòng ghi đã dừng: mọi frame xếp trước một `Frame.Close` đã lên dây (hoặc dây đã đứt). */
    private val writesFinished = CompletableDeferred<Unit>()

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
                val frame = BtTransportFrame.decode(openRecord(io.readRecord()))
                // Làn bulk có đệm riêng (0.4.7 bulk rule 5-6); tin đủ của cả hai kênh lên cùng pipeline `/v1/ctl`.
                val message =
                    when {
                        frame.chan != BtLinkConstants.CHAN_CONTROL -> bulkLane.accept(frame)
                        frame.long -> reassembler.accept(frame)
                        else -> frame.data
                    }
                message?.let { incomingFrames.trySend(Frame.Text(it.toString(Charsets.UTF_8))) }
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

    /** Làn bulk (0.4.7): [BulkLaneSocket.sendBulk] gọi trực tiếp, không qua [outgoingFrames] như làn điều khiển. */
    override suspend fun sendBulk(envelope: String) {
        bulkLane.awaitQueueSlot()
        outgoingFrames.send(Frame.Binary(true, envelope.toByteArray(Charsets.UTF_8)))
    }

    private suspend fun writeLoop() {
        try {
            writePump.run()
        } catch (e: CancellationException) {
            throw e
        } catch (_: BtLinkException) {
            // The link died mid-write (`more`/`dlen` only this side can produce correctly) — nothing to retry.
        } finally {
            writesFinished.complete(Unit)
        }
        end()
    }

    /**
     * Chờ vòng ghi xả xong (sau một `Frame.Close`), tối đa [timeoutMillis]: bên gọi sắp huỷ socket mà vừa gửi một
     * envelope cuối (như `pair/error`) thì không để lệnh huỷ nuốt mất nó.
     */
    suspend fun awaitWritesFinished(timeoutMillis: Long) {
        withTimeoutOrNull(timeoutMillis) { writesFinished.await() }
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
