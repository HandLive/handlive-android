package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.crypto.primitives.HmacSha256
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Nội dung quảng bá BLE của A-SVC (0.4.5 "Advertisement"): advertisement chỉ mang [SERVICE_UUID], scan response
 * mang service data `ver` ‖ `psm` (uint16 LE) ‖ `hints` (4 × n, n ≤ [MAX_HINTS]). Hint của một cặp = 4 byte đầu
 * HMAC-SHA256(`K_bt_disc`, "HLBTDISC1" ‖ int64 BE epoch), epoch = floor(now_ms / [HINT_PERIOD_MILLIS]).
 */
object BluetoothAdvertisementData {
    /** `BT_SERVICE_UUID` (0.10); `0xFEED` của spike G7 không bao giờ dùng ở bản sản phẩm. */
    val SERVICE_UUID: UUID = UUID.fromString("3b3a01f9-ab27-44a8-bb68-b9b11a488bdb")

    /** `BT_HINT_PERIOD`: hint, PSM và bộ quảng bá xoay cùng lúc ở mỗi ranh giới. */
    const val HINT_PERIOD_MILLIS = 900_000L

    /** `BT_ADV_MAX_HINTS`: giữ scan response ≤ 31 byte. */
    const val MAX_HINTS = 2

    const val HINT_SIZE = 4

    /** `BT_PIN_HINT`: đánh dấu cửa sổ ghép đôi PIN đang mở, giống nhau cho mọi điện thoại (AC9). */
    val PIN_HINT: ByteArray get() = ByteArray(HINT_SIZE)

    private const val VERSION: Byte = 0x01
    private const val HEADER_SIZE = 3
    private const val PSM_MAX = 0xFFFF
    private const val BYTE_MASK = 0xFF
    private val HINT_LABEL = "HLBTDISC1".toByteArray(Charsets.US_ASCII)

    fun epoch(nowMillis: Long): Long = Math.floorDiv(nowMillis, HINT_PERIOD_MILLIS)

    fun millisUntilNextEpoch(nowMillis: Long): Long = (epoch(nowMillis) + 1) * HINT_PERIOD_MILLIS - nowMillis

    fun hint(
        discoveryKey: ByteArray,
        epoch: Long,
    ): ByteArray {
        val message = HINT_LABEL + ByteBuffer.allocate(Long.SIZE_BYTES).putLong(epoch).array()
        return HmacSha256.mac(discoveryKey, message).copyOf(HINT_SIZE)
    }

    fun serviceData(
        psm: Int,
        hints: List<ByteArray>,
    ): ByteArray {
        require(psm in 0..PSM_MAX) { "psm out of range: $psm" }
        require(hints.size <= MAX_HINTS) { "at most $MAX_HINTS hints" }
        require(hints.all { it.size == HINT_SIZE }) { "a hint is $HINT_SIZE bytes" }
        val out = ByteArray(HEADER_SIZE + HINT_SIZE * hints.size)
        out[0] = VERSION
        out[1] = (psm and BYTE_MASK).toByte()
        out[2] = (psm ushr Byte.SIZE_BITS and BYTE_MASK).toByte()
        hints.forEachIndexed { index, hint -> hint.copyInto(out, HEADER_SIZE + HINT_SIZE * index) }
        return out
    }
}
