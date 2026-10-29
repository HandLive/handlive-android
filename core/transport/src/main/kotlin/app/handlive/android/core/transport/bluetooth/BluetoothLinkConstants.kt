package app.handlive.android.core.transport.bluetooth

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Hằng số phiên liên kết Bluetooth (0.10): admission, rekey, lập lịch — khác hằng số khung của `core:protocol`. */
object BluetoothLinkConstants {
    const val LINK_LIMIT = 4
    const val PREAUTH_LIMIT = 1
    val PREAUTH_TIMEOUT: Duration = 7.seconds

    const val AUTH_FAIL_BLOCK = 5
    val AUTH_FAIL_WINDOW: Duration = 1.minutes

    /** Noise `Rekey()` trên CipherState gửi sau mỗi bao nhiêu bản ghi một chiều. */
    const val LINK_REKEY_RECORDS = 65_536L

    /** Số bản ghi làn dài + bulk cùng lúc đã gửi mà chưa xả (0.4.5 "Priority"). */
    const val BULK_INFLIGHT = 2

    /** `BT_ENABLE_PROMPT_INTERVAL` (SET-01 API 10): tối đa một lần xin bật Bluetooth tự động trong khoảng này. */
    val ENABLE_PROMPT_INTERVAL: Duration = 24.hours
}
