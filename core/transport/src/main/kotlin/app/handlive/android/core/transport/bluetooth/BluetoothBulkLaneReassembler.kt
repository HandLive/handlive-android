package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.protocol.bluetooth.BtLinkConstants
import app.handlive.android.core.protocol.bluetooth.BtLinkException
import app.handlive.android.core.protocol.bluetooth.BtTransportFrame
import java.io.ByteArrayOutputStream

/**
 * Ghép lại làn bulk của kênh `0x02` (0.4.7 bulk rule 5): một đệm riêng, khác đệm làn dài của kênh điều khiển
 * `0x01` — một tin một lúc mỗi hướng (bulk rule 3). Đóng liên kết khi tổng byte tích luỹ vượt `BT_MESSAGE_MAX`,
 * kiểm ở MỌI mảnh như `BluetoothLongLaneReassembler`. Bulk không dùng bit `long`; `BtTransportFrame.decode` đã
 * đóng liên kết khi nó bằng 1 trên kênh này (bulk rule 5), nên [accept] chỉ còn ghép theo `more`.
 */
class BluetoothBulkLaneReassembler(
    private val messageMax: Int = BtLinkConstants.MESSAGE_MAX,
) {
    private val buffer = ByteArrayOutputStream()

    /** Trả tin hoàn chỉnh khi [frame] là mảnh cuối (`more` = false), `null` nếu còn chờ mảnh tiếp. */
    fun accept(frame: BtTransportFrame): ByteArray? {
        require(frame.chan == BtLinkConstants.CHAN_BULK) { "not a bulk-lane frame" }
        val total = buffer.size() + frame.data.size
        if (total > messageMax) throw BtLinkException("bulk message exceeds BT_MESSAGE_MAX")
        buffer.write(frame.data)
        if (frame.more) return null
        val result = buffer.toByteArray()
        buffer.reset()
        return result
    }
}
