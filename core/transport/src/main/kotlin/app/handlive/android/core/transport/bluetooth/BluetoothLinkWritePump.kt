package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.protocol.envelope.EnvelopeCodec
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Vòng ghi của một `BluetoothPeerSocket`: lấy frame từ [outgoing], xếp vào [BluetoothLinkScheduler] rồi viết từng
 * mảnh lên dây qua [writePlaintext] (bên gọi niêm phong và ghi bản ghi). Sau MỖI mảnh, vòng này rút ngay các frame
 * đã chờ sẵn trong [outgoing] trước lần `poll()` kế, để envelope điều khiển ngắn gửi giữa lúc bulk đang chạy chen
 * vào ở ranh giới mảnh kế tiếp, không phải đợi cả envelope bulk xả xong (0.4.5 "Priority", 0.4.7).
 */
internal class BluetoothLinkWritePump(
    private val outgoing: ReceiveChannel<Frame>,
    private val writePlaintext: suspend (ByteArray) -> Unit,
    private val onBulkEnvelopeWritten: () -> Unit,
) {
    private val scheduler = BluetoothLinkScheduler()

    /** Đã gặp `Frame.Close`: không nhận thêm frame nào, chỉ xả nốt phần đã xếp rồi dừng. */
    private var closeRequested = false

    /** Chạy tới khi [outgoing] đóng hoặc gặp `Frame.Close`; mọi frame xếp trước `Frame.Close` đều lên dây. */
    suspend fun run() {
        for (frame in outgoing) {
            accept(frame)
            drain()
            if (closeRequested) return
        }
    }

    private fun accept(frame: Frame) {
        when (frame) {
            // Phong bì JSON của làn điều khiển: scheduler tự chọn làn ngắn hay dài theo `type` và cỡ.
            is Frame.Text -> {
                val json = frame.readText()
                scheduler.enqueueControlEnvelope(EnvelopeCodec.decode(json).type, json.toByteArray(Charsets.UTF_8))
            }

            is Frame.Binary -> {
                scheduler.enqueueBulk(frame.data)
            }

            is Frame.Close -> {
                closeRequested = true
            }

            else -> {}
        }
    }

    private suspend fun drain() {
        var plaintext = scheduler.poll()
        while (plaintext != null) {
            writePlaintext(plaintext)
            if (scheduler.onDrained()) onBulkEnvelopeWritten()
            acceptPending()
            plaintext = scheduler.poll()
        }
    }

    /** Ranh giới mảnh: nhận mọi frame đang chờ mà không treo, dừng ở `Frame.Close`. */
    private fun acceptPending() {
        while (!closeRequested) {
            accept(outgoing.tryReceive().getOrNull() ?: return)
        }
    }
}
