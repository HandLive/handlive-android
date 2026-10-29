package app.handlive.android.feature.connection.bluetooth

import app.handlive.android.core.transport.bluetooth.BluetoothAdvertisementData
import app.handlive.android.core.transport.bluetooth.BluetoothLinkIo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Quảng bá + server L2CAP của A-SVC (0.4.5 "Advertisement"), độc lập với server LAN và với mạng. Mỗi lần nội dung đổi
 * — sang epoch `BT_HINT_PERIOD` mới, hoặc danh sách cặp cần quảng bá đổi — nó dừng bộ quảng bá, đóng server socket
 * (kênh đã mở vẫn sống), mở server mới (PSM mới) và quảng bá lại: hint, PSM và bộ quảng bá đổi cùng nhau ("Rotation";
 * dựng lại bộ quảng bá là cách duy nhất app đổi được địa chỉ radio). Không cặp nào cần quảng bá → không server, không
 * quảng bá. [BluetoothLinkLifecycle] gọi [start]/[stop] theo trạng thái adapter.
 */
class BluetoothPeripheral(
    private val radio: BluetoothRadio,
    private val accept: suspend (BluetoothLinkIo) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var job: Job? = null

    /**
     * [discoveryKeys]: `K_bt_disc` của các cặp cần quảng bá, cặp `last_seen_at` gần nhất trước; chỉ
     * `BT_ADV_MAX_HINTS` cặp đầu lên sóng. [pairingHints]: `pr_bt` của cửa sổ ghép đôi QR đang mở (`null` khi không
     * có), luôn đứng đầu và chiếm một chỗ của cặp ("Pairing mode"); cửa sổ mở hay đóng đều dựng lại bộ quảng bá. Kênh
     * accept được chạy trong [scope] nên sống qua các lần xoay và [stop].
     */
    @Synchronized
    fun start(
        scope: CoroutineScope,
        discoveryKeys: Flow<List<ByteArray>>,
        pairingHints: Flow<ByteArray?> = flowOf(null),
    ) {
        if (job?.isActive == true) return
        job =
            scope.launch {
                val advertised =
                    combine(discoveryKeys, pairingHints) { keys, pairing -> Advertised(pairing, keys) }
                        .distinctUntilChanged { old, new -> old.sameAs(new) }
                combine(advertised, epochs()) { content, epoch -> content to epoch }
                    .collectLatest { (content, epoch) ->
                        if (content.isNotEmpty()) serve(scope, content.hints(epoch))
                    }
            }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Một bộ quảng bá + server cho tới khi bị huỷ; server hỏng thì mở lại sau [RETRY_DELAY_MILLIS]. */
    private suspend fun serve(
        linkScope: CoroutineScope,
        hints: List<ByteArray>,
    ) {
        while (true) {
            // Stack Bluetooth bỏ socket (đang tắt radio, lỗi tạm): advertiseAndAccept đã dừng sạch, thử lại.
            val failure = runCatching { advertiseAndAccept(linkScope, hints) }.exceptionOrNull()
            if (failure is CancellationException) throw failure
            delay(RETRY_DELAY_MILLIS)
        }
    }

    private suspend fun advertiseAndAccept(
        linkScope: CoroutineScope,
        hints: List<ByteArray>,
    ) {
        val server = radio.listen()
        try {
            radio.startAdvertising(BluetoothAdvertisementData.serviceData(server.psm, hints))
            try {
                while (true) {
                    val io = server.accept()
                    linkScope.launch {
                        // One broken link must not take A-SVC down: the acceptor already closed it.
                        val failure = runCatching { accept(io) }.exceptionOrNull()
                        if (failure is CancellationException) throw failure
                    }
                }
            } finally {
                radio.stopAdvertising()
            }
        } finally {
            server.close()
        }
    }

    /** Epoch hiện tại, rồi lại ngay sau mỗi ranh giới `BT_HINT_PERIOD`. */
    private fun epochs(): Flow<Long> =
        flow {
            while (true) {
                emit(BluetoothAdvertisementData.epoch(clock()))
                delay(BluetoothAdvertisementData.millisUntilNextEpoch(clock()))
            }
        }

    /** Nội dung cần quảng bá: `pr_bt` (nếu có cửa sổ QR) rồi `K_bt_disc` của các cặp, tối đa `BT_ADV_MAX_HINTS`. */
    private class Advertised(
        val pairing: ByteArray?,
        allKeys: List<ByteArray>,
    ) {
        val keys: List<ByteArray> =
            allKeys.take(BluetoothAdvertisementData.MAX_HINTS - if (pairing == null) 0 else 1)

        fun isNotEmpty(): Boolean = pairing != null || keys.isNotEmpty()

        fun hints(epoch: Long): List<ByteArray> =
            listOfNotNull(pairing) + keys.map { BluetoothAdvertisementData.hint(it, epoch) }

        fun sameAs(other: Advertised): Boolean =
            pairing.sameBytes(other.pairing) && keys.size == other.keys.size &&
                keys.zip(other.keys).all { (a, b) -> a.contentEquals(b) }
    }

    companion object {
        const val RETRY_DELAY_MILLIS = 2_000L

        private fun ByteArray?.sameBytes(other: ByteArray?): Boolean =
            if (this == null) other == null else other != null && contentEquals(other)
    }
}
