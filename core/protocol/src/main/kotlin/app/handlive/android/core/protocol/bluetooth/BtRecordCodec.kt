package app.handlive.android.core.protocol.bluetooth

import java.nio.ByteBuffer

/**
 * Bản ghi liên kết: `rlen` (uint16 BE) ‖ `rlen` byte (0.4.5 "Link records"). Người đọc phải kiểm `rlen` **trước
 * khi cấp phát** thân bản ghi (finding của pentester, review #165): [requireWithinBound]/[requireValidTransportRlen]
 * ném ngay khi vượt cận, trước khi gọi mã nào đọc thêm byte theo `rlen`.
 */
object BtRecordCodec {
    const val LENGTH_PREFIX_SIZE = 2
    private const val UINT16_MAX = 0xFFFF
    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8

    fun encode(body: ByteArray): ByteArray {
        require(body.size <= UINT16_MAX) { "record body too large: ${body.size}" }
        return ByteBuffer
            .allocate(LENGTH_PREFIX_SIZE + body.size)
            .putShort(body.size.toShort())
            .put(body)
            .array()
    }

    fun decodeRlen(prefix: ByteArray): Int {
        require(prefix.size == LENGTH_PREFIX_SIZE) { "rlen prefix must be $LENGTH_PREFIX_SIZE bytes" }
        return ((prefix[0].toInt() and BYTE_MASK) shl BITS_PER_BYTE) or (prefix[1].toInt() and BYTE_MASK)
    }

    /** Bản ghi pre-auth: `rlen` vượt [maxBodySize] (`BT_PREAUTH_RECORD_MAX`) → đóng kênh, không đọc thêm. */
    fun requireWithinBound(
        rlen: Int,
        maxBodySize: Int,
    ) {
        if (rlen > maxBodySize) throw BtLinkException("rlen $rlen exceeds bound $maxBodySize")
    }

    /** Bản ghi vận chuyển: `rlen` phải đúng một trong ba cỡ cố định, không chỉ ≤ cận trên (0.4.5). */
    fun requireValidTransportRlen(rlen: Int) {
        if (rlen !in BtLinkConstants.TRANSPORT_RECORD_SIZES) {
            throw BtLinkException("rlen $rlen is not a valid transport record size")
        }
    }
}
