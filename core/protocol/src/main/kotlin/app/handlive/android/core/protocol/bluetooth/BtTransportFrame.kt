package app.handlive.android.core.protocol.bluetooth

import java.nio.ByteBuffer

/**
 * Plaintext của một khung vận chuyển (0.4.5 "Transport records and channels"), trước khi mã hoá / sau khi giải
 * mã: `chan`(1) ‖ `flags`(1, bit0 `more`, bit1 `long`) ‖ `dlen`(2 BE) ‖ `data` ‖ đệm 0 tới cỡ nhỏ nhất trong
 * [BtLinkConstants.PADDED_PLAINTEXT_SIZES] chứa đủ khung.
 */
class BtTransportFrame(
    val chan: Int,
    val more: Boolean,
    val long: Boolean,
    val data: ByteArray,
) {
    companion object {
        private const val FLAG_MORE = 0x01
        private const val FLAG_LONG = 0x02
        private const val BYTE_MASK = 0xFF
        private const val BITS_PER_BYTE = 8
        private const val DLEN_LOW_BYTE_INDEX = 3

        /**
         * Cận `dlen`: làn dài (control, `long`) hoặc bulk (`chan` = 0x02) → `BULK_FRAGMENT_MAX`; làn ngắn →
         * `FRAGMENT_MAX`.
         */
        fun maxDlenFor(
            chan: Int,
            long: Boolean,
        ): Int =
            if (chan == BtLinkConstants.CHAN_BULK || long) {
                BtLinkConstants.BULK_FRAGMENT_MAX
            } else {
                BtLinkConstants.FRAGMENT_MAX
            }

        fun paddedSizeFor(unpaddedSize: Int): Int =
            BtLinkConstants.PADDED_PLAINTEXT_SIZES.firstOrNull { it >= unpaddedSize }
                ?: throw IllegalArgumentException("no padded tier holds $unpaddedSize bytes")

        /** Dựng plaintext đã đệm sẵn để mã hoá; bên gọi (bộ lập lịch/mux) lo việc cắt mảnh đúng cận trước. */
        fun encode(
            chan: Int,
            more: Boolean,
            long: Boolean,
            data: ByteArray,
        ): ByteArray {
            require(data.size <= maxDlenFor(chan, long)) { "dlen ${data.size} exceeds bound for chan=$chan long=$long" }
            val padded = paddedSizeFor(BtLinkConstants.FRAME_HEADER_SIZE + data.size)
            var flags = 0
            if (more) flags = flags or FLAG_MORE
            if (long) flags = flags or FLAG_LONG
            return ByteBuffer
                .allocate(padded)
                .put(chan.toByte())
                .put(flags.toByte())
                .putShort(data.size.toShort())
                .put(data)
                .array()
        }

        private fun fail(message: String): Nothing = throw BtLinkException(message)

        /**
         * [plaintext] đã qua xác thực tag; kiểm mọi luật của 0.4.5 trước khi trả khung — không trả lời, đóng kênh
         * khi sai.
         */
        fun decode(plaintext: ByteArray): BtTransportFrame {
            if (plaintext.size !in BtLinkConstants.PADDED_PLAINTEXT_SIZES) {
                fail("unexpected plaintext size ${plaintext.size}")
            }
            val chan = plaintext[0].toInt() and BYTE_MASK
            if (chan != BtLinkConstants.CHAN_CONTROL && chan != BtLinkConstants.CHAN_BULK) {
                fail("unknown chan $chan")
            }
            val flags = plaintext[1].toInt() and BYTE_MASK
            val more = flags and FLAG_MORE != 0
            val long = flags and FLAG_LONG != 0
            // Luật "more=1 với long=0 → đóng kênh" chỉ áp dụng cho làn ngắn của control (chan=0x01); bulk
            // (chan=0x02) không dùng bit `long` nhưng vẫn hợp lệ cắt mảnh nhiều `more=1` (0.4.7).
            if (chan == BtLinkConstants.CHAN_CONTROL && more && !long) fail("more without long")
            val dlen =
                ((plaintext[2].toInt() and BYTE_MASK) shl BITS_PER_BYTE) or
                    (plaintext[DLEN_LOW_BYTE_INDEX].toInt() and BYTE_MASK)
            val maxDlen = maxDlenFor(chan, long)
            if (dlen > maxDlen) fail("dlen $dlen exceeds bound $maxDlen for chan=$chan long=$long")
            val dataEnd = BtLinkConstants.FRAME_HEADER_SIZE + dlen
            if (dataEnd > plaintext.size) fail("dlen $dlen overflows frame")
            val data = plaintext.copyOfRange(BtLinkConstants.FRAME_HEADER_SIZE, dataEnd)
            return BtTransportFrame(chan, more, long, data)
        }
    }
}
