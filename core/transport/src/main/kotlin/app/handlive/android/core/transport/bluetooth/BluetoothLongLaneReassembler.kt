package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.protocol.bluetooth.BtLinkConstants
import app.handlive.android.core.protocol.bluetooth.BtLinkException
import app.handlive.android.core.protocol.bluetooth.BtTransportFrame
import java.io.ByteArrayOutputStream

/**
 * Ghép lại làn dài của kênh điều khiển (0.4.5): một tin một lúc mỗi hướng. Đóng liên kết khi tổng byte tích luỹ
 * vượt `BT_MESSAGE_MAX` — kiểm ở MỌI mảnh, không chỉ mảnh cuối (`more` = 0): một mảnh giữa đường đã đẩy tổng
 * vượt cận phải đóng ngay, không chờ mảnh cuối đọc hết mới biết tin quá lớn.
 */
class BluetoothLongLaneReassembler(
    private val messageMax: Int = BtLinkConstants.MESSAGE_MAX,
) {
    private val buffer = ByteArrayOutputStream()

    /** Trả tin hoàn chỉnh khi [frame] là mảnh cuối (`more` = false), `null` nếu còn chờ mảnh tiếp. */
    fun accept(frame: BtTransportFrame): ByteArray? {
        require(frame.long) { "not a long-lane frame" }
        val total = buffer.size() + frame.data.size
        if (total > messageMax) throw BtLinkException("long-lane message exceeds BT_MESSAGE_MAX")
        buffer.write(frame.data)
        if (frame.more) return null
        val result = buffer.toByteArray()
        buffer.reset()
        return result
    }
}
