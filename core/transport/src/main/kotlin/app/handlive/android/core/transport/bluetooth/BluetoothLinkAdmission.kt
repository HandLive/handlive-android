package app.handlive.android.core.transport.bluetooth

import kotlin.time.Duration

/**
 * Admission của toàn bộ listener Bluetooth (0.4.5 "Admission"): BLE không cho địa chỉ client, nên cận và bộ đếm
 * thất bại tính chung cho mọi kênh — không bao giờ khoá theo địa chỉ, khác `CTL_PREAUTH_LIMIT`/`CTL_IP_BLOCK` của
 * LAN. An toàn đa luồng: mỗi kênh chạy `BluetoothLinkAcceptor` riêng, và `ControlConnectionHandler` đếm thêm
 * `AUTH_FAILED` của `session/hello` từ coroutine khác.
 */
class BluetoothLinkAdmission(
    private val linkLimit: Int = BluetoothLinkConstants.LINK_LIMIT,
    private val preauthLimit: Int = BluetoothLinkConstants.PREAUTH_LIMIT,
    private val preauthTimeout: Duration = BluetoothLinkConstants.PREAUTH_TIMEOUT,
    private val authFailBlock: Int = BluetoothLinkConstants.AUTH_FAIL_BLOCK,
    private val authFailWindow: Duration = BluetoothLinkConstants.AUTH_FAIL_WINDOW,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var openLinks = 0
    private var preauthLinks = 0
    private val authFailureTimestamps = ArrayDeque<Long>()
    private var blockedUntil: Long = 0

    sealed interface AdmissionResult {
        data object Admitted : AdmissionResult

        /** Cận chung hoặc listener đang bị khoá sau `BT_AUTH_FAIL_BLOCK` → `reject`, đóng kênh. */
        data object Rejected : AdmissionResult
    }

    val isBlocked: Boolean
        @Synchronized get() = clock() < blockedUntil

    /** Một kênh L2CAP mới mở: kiểm khoá chung rồi slot pre-auth (0.4.5 "Admission" bước 1). */
    @Synchronized
    fun admitNewChannel(): AdmissionResult {
        if (isBlocked || openLinks >= linkLimit || preauthLinks >= preauthLimit) {
            return AdmissionResult.Rejected
        }
        openLinks++
        preauthLinks++
        return AdmissionResult.Admitted
    }

    /** Bước 5 hoàn tất: không còn tính vào slot pre-auth, vẫn tính vào `BT_LINK_LIMIT`. */
    @Synchronized
    fun confirmLink() {
        preauthLinks--
    }

    /** Kênh đóng ở bất kỳ giai đoạn: trả lại slot. [wasPreauth] = chưa qua bước 5 khi đóng. */
    @Synchronized
    fun closeLink(wasPreauth: Boolean) {
        openLinks--
        if (wasPreauth) preauthLinks--
    }

    /** Một lần xác thực khoá thất bại (bước 3/5) hoặc `session/hello` bị `AUTH_FAILED` — tính cho toàn listener. */
    @Synchronized
    fun recordAuthFailure() {
        pruneAuthFailures()
        authFailureTimestamps.addLast(clock())
        if (authFailureTimestamps.size >= authFailBlock) {
            blockedUntil = clock() + authFailWindow.inWholeMilliseconds
        }
    }

    fun isPreauthTimedOut(channelOpenedAt: Long): Boolean =
        clock() - channelOpenedAt >= preauthTimeout.inWholeMilliseconds

    private fun pruneAuthFailures() {
        val windowStart = clock() - authFailWindow.inWholeMilliseconds
        while (authFailureTimestamps.isNotEmpty() && authFailureTimestamps.first() < windowStart) {
            authFailureTimestamps.removeFirst()
        }
    }
}
