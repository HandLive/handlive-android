package app.handlive.android.core.protocol.bluetooth

/** Hằng số khung liên kết Bluetooth (0.4.5 "Link records", "Transport records and channels"; 0.10). */
object BtLinkConstants {
    /** Cận trên của một bản ghi pre-auth, kiểm trước khi cấp phát (0.4.5 "Link records"). */
    const val PREAUTH_RECORD_MAX = 64

    private const val PADDED_PLAINTEXT_SMALL = 256
    private const val PADDED_PLAINTEXT_MEDIUM = 1024
    private const val PADDED_PLAINTEXT_LARGE = 4096

    /** Kích cỡ plaintext đã đệm trước khi mã hoá (0.4.5 "Transport records and channels"). */
    val PADDED_PLAINTEXT_SIZES = intArrayOf(PADDED_PLAINTEXT_SMALL, PADDED_PLAINTEXT_MEDIUM, PADDED_PLAINTEXT_LARGE)

    /** `rlen` hợp lệ duy nhất của một bản ghi vận chuyển sau bước 4: mỗi [PADDED_PLAINTEXT_SIZES] cộng [TAG_SIZE]. */
    val TRANSPORT_RECORD_SIZES =
        intArrayOf(
            PADDED_PLAINTEXT_SMALL + TAG_SIZE,
            PADDED_PLAINTEXT_MEDIUM + TAG_SIZE,
            PADDED_PLAINTEXT_LARGE + TAG_SIZE,
        )

    /** `dlen` của làn ngắn (control, `long` = 0); cũng là cận chung của trường `dlen`. */
    const val FRAGMENT_MAX = 4092

    /** `dlen` của một mảnh làn dài (control, `long` = 1) hoặc một mảnh bulk (`chan` = 0x02). */
    const val BULK_FRAGMENT_MAX = 1020

    /** Tổng byte lớn nhất của một tin đã ghép lại trên làn dài. */
    const val MESSAGE_MAX = 256 * 1024

    const val CHAN_CONTROL = 0x01
    const val CHAN_BULK = 0x02

    /** `chan`(1) ‖ `flags`(1) ‖ `dlen`(2 BE), trước `data`. */
    const val FRAME_HEADER_SIZE = 4

    const val TAG_SIZE = 16
}
