package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.protocol.bluetooth.BtTransportFrame
import kotlinx.coroutines.sync.Semaphore

/**
 * Trạng thái làn bulk (`chan` = 0x02, 0.4.7) của một `BluetoothPeerSocket`: cổng hàng đợi `BT_BULK_QUEUED` (bulk
 * rule 4) cho chiều gửi và đệm ráp mảnh riêng (bulk rule 5-6) cho chiều nhận. Việc cắt mảnh và xếp sau làn điều
 * khiển vẫn là của [BluetoothLinkScheduler]; lớp này không đụng dây.
 */
internal class BluetoothBulkLane {
    private val reassembler = BluetoothBulkLaneReassembler()

    /** Đủ `BT_BULK_QUEUED` envelope bulk chưa ghi xong thì [awaitQueueSlot] chờ tới khi một envelope xả hết. */
    private val queueGate = Semaphore(BluetoothLinkConstants.BULK_QUEUED)

    /** Giữ một chỗ trong hàng đợi bulk trước khi xếp một envelope; trả lại bằng [onEnvelopeWritten]. */
    suspend fun awaitQueueSlot() = queueGate.acquire()

    /** Mảnh cuối của một envelope bulk đã lên dây (`BluetoothLinkScheduler.onDrained` trả `true`). */
    fun onEnvelopeWritten() = queueGate.release()

    /** Một mảnh kênh 0x02; trả envelope hoàn chỉnh khi đó là mảnh cuối, vượt `BT_MESSAGE_MAX` thì ném lỗi liên kết. */
    fun accept(frame: BtTransportFrame): ByteArray? = reassembler.accept(frame)
}
