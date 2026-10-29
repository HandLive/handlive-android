package app.handlive.android.core.transport.bluetooth

/**
 * Đọc/viết một bản ghi (`rlen` ‖ thân, 0.4.5 "Link records") trên một kênh BLE L2CAP CoC; [readRecord]/[writeRecord]
 * nhận và trả THÂN bản ghi, phần `rlen` do bên triển khai lo. Bên triển khai kiểm `rlen` TRƯỚC khi cấp phát thân
 * (review #165): ≤ `BT_PREAUTH_RECORD_MAX` cho [readPreauthRecord] (bước 1-4), đúng một trong ba cỡ cố định cho
 * [readRecord] (sau bước 4). Bản thật là [StreamBluetoothLinkIo] trên `BluetoothSocket`; test dùng ống trong bộ nhớ.
 */
interface BluetoothLinkIo {
    suspend fun readPreauthRecord(): ByteArray

    suspend fun readRecord(): ByteArray

    suspend fun writeRecord(body: ByteArray)

    fun close()
}
