package app.handlive.android.feature.connection.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.handlive.android.core.crypto.derivation.BtLinkKeyDerivation
import app.handlive.android.core.data.pairing.PairStore
import app.handlive.android.core.transport.bluetooth.BluetoothLinkAcceptor
import app.handlive.android.core.transport.bluetooth.BluetoothPairKeys
import app.handlive.android.core.transport.server.ControlServer
import app.handlive.android.feature.connection.bench.BenchEvent
import app.handlive.android.feature.connection.bench.BenchLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Kênh Bluetooth của A-SVC (CONN-05, 0.4.5), chạy song song và độc lập với server LAN: quảng bá + server L2CAP
 * ([BluetoothPeripheral] trên [AndroidBluetoothRadio]), mỗi kênh qua [BluetoothLinkAcceptor] rồi vào
 * `ControlServer.serveBluetoothPeer`. [BluetoothLinkLifecycle] dựng lại tất cả khi adapter về `STATE_ON` và dừng khi
 * tắt (điều kiện 2 của spike G7); thiếu "Nearby devices" trên Android 12+ coi như adapter tắt.
 *
 * Quảng bá hint của các cặp chưa thu hồi KHÔNG có session sống trên route nào, `last_seen_at` gần nhất trước; thử
 * `K_bt_link` của mọi cặp chưa thu hồi khi kênh mở (một session mới thay session cũ, 0.4.1).
 */
class BluetoothAdvertising(
    context: Context,
    private val pairs: PairStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext

    @Volatile
    private var linkKeys: List<Pair<String, ByteArray>> = emptyList()
    private var receiver: BroadcastReceiver? = null
    private var lifecycle: BluetoothLinkLifecycle? = null
    private var peripheral: BluetoothPeripheral? = null

    /**
     * [liveSessions]: `pair_id` có session đang mở (mọi route). [environment] phát lại khi quyền có thể đã đổi
     * (`refreshEnvironment`), để quảng bá bật/tắt theo "Nearby devices".
     */
    fun start(
        scope: CoroutineScope,
        server: ControlServer,
        liveSessions: Flow<Set<String>>,
        environment: Flow<*>,
    ) {
        if (appContext.getSystemService(BluetoothManager::class.java)?.adapter == null) return
        val acceptor =
            BluetoothLinkAcceptor(server.bluetoothAdmission, BluetoothPairKeys { linkKeys }, server::serveBluetoothPeer)
        val radioPeripheral = BluetoothPeripheral(AndroidBluetoothRadio(appContext), acceptor::accept, clock)
        peripheral = radioPeripheral
        val discoveryKeys = discoveryKeys(liveSessions)
        val stateMachine =
            BluetoothLinkLifecycle(
                start = { radioPeripheral.start(scope, discoveryKeys) },
                stop = radioPeripheral::stop,
            ).also { lifecycle = it }
        receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    BenchLog.event(BenchEvent.BT_STATE, "state" to state)
                    stateMachine.onAdapterState(gated(adapterStateOf(state)))
                }
            }.also {
                ContextCompat.registerReceiver(
                    appContext,
                    it,
                    IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }
        environment.onEach { stateMachine.onAdapterState(gated(currentAdapterState())) }.launchIn(scope)
    }

    fun stop() {
        receiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        receiver = null
        lifecycle?.onAdapterState(BluetoothAdapterState.OFF)
        lifecycle = null
        peripheral?.stop()
        peripheral = null
        linkKeys = emptyList()
    }

    /** `K_bt_disc` của các cặp cần quảng bá; cập nhật luôn [linkKeys] (mọi cặp chưa thu hồi) cho bước 3. */
    private fun discoveryKeys(liveSessions: Flow<Set<String>>): Flow<List<ByteArray>> =
        combine(pairs.observeActive(), liveSessions) { devices, live -> devices to live }
            .map { (devices, live) ->
                val prks =
                    pairs
                        .activeSecrets()
                        .mapNotNull { secret -> secret.prk?.let { secret.pairId to it } }
                        .toMap()
                linkKeys = prks.map { (pairId, prk) -> pairId to BtLinkKeyDerivation.kBtLink(prk) }
                devices
                    .filter { it.pairId !in live }
                    .sortedByDescending { it.lastSeenAt ?: Long.MIN_VALUE }
                    .mapNotNull { prks[it.pairId] }
                    .map(BtLinkKeyDerivation::kBtDisc)
            }

    private fun currentAdapterState(): BluetoothAdapterState {
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        return adapterStateOf(adapter?.state ?: BluetoothAdapter.STATE_OFF)
    }

    /** Thiếu "Nearby devices" (Android 12+) = không được quảng bá hay nghe: đối xử như adapter tắt. */
    private fun gated(state: BluetoothAdapterState): BluetoothAdapterState =
        if (nearbyDevicesGranted()) state else BluetoothAdapterState.OFF

    private fun nearbyDevicesGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            NEARBY_DEVICES.all {
                ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
            }

    private companion object {
        val NEARBY_DEVICES = listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)

        fun adapterStateOf(state: Int): BluetoothAdapterState =
            when (state) {
                BluetoothAdapter.STATE_ON -> BluetoothAdapterState.ON
                BluetoothAdapter.STATE_TURNING_ON -> BluetoothAdapterState.TURNING_ON
                BluetoothAdapter.STATE_TURNING_OFF -> BluetoothAdapterState.TURNING_OFF
                else -> BluetoothAdapterState.OFF
            }
    }
}
