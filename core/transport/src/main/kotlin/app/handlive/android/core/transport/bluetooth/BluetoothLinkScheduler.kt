package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.protocol.bluetooth.BtLinkConstants
import app.handlive.android.core.protocol.bluetooth.BtTransportFrame
import app.handlive.android.core.protocol.envelope.MessageType

/**
 * Bộ lập lịch gửi của một liên kết Bluetooth (0.4.5 "Priority"): ba hàng đợi, ở mỗi ranh giới mảnh [poll] trả
 * mảnh từ hàng đợi không rỗng đầu tiên theo thứ tự ngắn, dài, bulk. Tối đa [bulkInflight] bản ghi dài+bulk đã
 * gửi (đã [poll]) mà chưa xả (gọi [onDrained]) cùng lúc; làn ngắn không bị giới hạn này nên luôn tới ngay sau
 * mảnh đang gửi, không phải chờ thêm mảnh dài/bulk nào khác xếp sau nó.
 *
 * Một phong bì cùng `type` với một tin làn dài còn đang xếp hàng/trên đường gửi phải đi làn dài theo sau nó
 * (0.4.5 "Order"): [typesPendingLong] theo dõi điều này, xoá khi mảnh CUỐI của tin đó được [poll] — thứ tự trên
 * dây đã chốt ngay lúc đó vì liên kết chỉ có một luồng byte nối tiếp.
 */
class BluetoothLinkScheduler(
    private val bulkInflight: Int = BluetoothLinkConstants.BULK_INFLIGHT,
) {
    private class QueuedFrame(
        val bytes: ByteArray,
        val type: String?,
        val isLastOfMessage: Boolean,
    )

    private val short = ArrayDeque<ByteArray>()
    private val long = ArrayDeque<QueuedFrame>()
    private val bulk = ArrayDeque<QueuedFrame>()
    private var longBulkInflight = 0
    private val typesPendingLong = mutableSetOf<String>()

    /** Phong bì JSON (0.5.1) trên kênh điều khiển `0x01`; tự chọn làn ngắn hay dài theo 0.4.5 "Control channel". */
    fun enqueueControlEnvelope(
        type: String,
        envelope: ByteArray,
    ) {
        val mustUseLong =
            envelope.size > BtLinkConstants.FRAGMENT_MAX || type == MessageType.SESSION.wire || type in typesPendingLong
        if (mustUseLong) {
            typesPendingLong += type
            long.addAll(fragment(type, envelope, BtLinkConstants.CHAN_CONTROL, long = true))
        } else {
            val chan = BtLinkConstants.CHAN_CONTROL
            short.addLast(BtTransportFrame.encode(chan, more = false, long = false, data = envelope))
        }
    }

    /** Dữ liệu bulk trên kênh `0x02` (nội dung: 0.4.7, ngoài phạm vi thẻ này). */
    fun enqueueBulk(data: ByteArray) {
        bulk.addAll(fragment(type = null, data, BtLinkConstants.CHAN_BULK, long = false))
    }

    fun hasPending(): Boolean = short.isNotEmpty() || long.isNotEmpty() || bulk.isNotEmpty()

    /** Mảnh plaintext kế tiếp theo ưu tiên 0.4.5 "Priority", hay `null` nếu không còn gì để gửi ngay. */
    fun poll(): ByteArray? =
        short.removeFirstOrNull()
            ?: if (longBulkInflight >= bulkInflight) {
                null
            } else {
                long.removeFirstOrNull()?.let { dispatch(it) }
                    ?: bulk.removeFirstOrNull()?.let { dispatch(it) }
            }

    /** Gọi khi một bản ghi dài/bulk đã [poll] trước đó thật sự xả khỏi bộ đệm ghi (mở lại một chỗ inflight). */
    fun onDrained() {
        if (longBulkInflight > 0) longBulkInflight--
    }

    private fun dispatch(frame: QueuedFrame): ByteArray {
        longBulkInflight++
        if (frame.isLastOfMessage) frame.type?.let { typesPendingLong -= it }
        return frame.bytes
    }

    private fun fragment(
        type: String?,
        data: ByteArray,
        chan: Int,
        long: Boolean,
    ): List<QueuedFrame> {
        val chunks = chunk(data, BtLinkConstants.BULK_FRAGMENT_MAX)
        return chunks.mapIndexed { index, part ->
            val more = index != chunks.lastIndex
            val plaintext = BtTransportFrame.encode(chan, more = more, long = long, data = part)
            QueuedFrame(plaintext, type, isLastOfMessage = !more)
        }
    }

    private fun chunk(
        data: ByteArray,
        maxSize: Int,
    ): List<ByteArray> {
        if (data.isEmpty()) return listOf(ByteArray(0))
        val out = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + maxSize, data.size)
            out += data.copyOfRange(offset, end)
            offset = end
        }
        return out
    }
}
