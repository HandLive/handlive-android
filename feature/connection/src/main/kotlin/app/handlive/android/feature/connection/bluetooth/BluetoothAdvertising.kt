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
import app.handlive.android.core.transport.bluetooth.BluetoothAdvertisementData
import app.handlive.android.core.transport.bluetooth.BluetoothLinkAcceptor
import app.handlive.android.core.transport.bluetooth.BluetoothLinkAdmission
import app.handlive.android.core.transport.bluetooth.BluetoothPairKeys
import app.handlive.android.core.transport.bluetooth.BluetoothPairingKeySource
import app.handlive.android.core.transport.bluetooth.BluetoothPinPairingWindow
import app.handlive.android.core.transport.bluetooth.BluetoothPreauthSession
import app.handlive.android.core.transport.bluetooth.BluetoothQrPairingWindow
import app.handlive.android.core.transport.bluetooth.BtPinPairingLink
import app.handlive.android.core.transport.bluetooth.BtPinPairingResponder
import app.handlive.android.core.transport.bluetooth.BtQrPairingLink
import app.handlive.android.core.transport.bluetooth.BtQrPairingSlot
import app.handlive.android.core.transport.server.ControlServer
import app.handlive.android.core.transport.server.PairingEndpoint
import app.handlive.android.feature.connection.bench.BenchEvent
import app.handlive.android.feature.connection.bench.BenchLog
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
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
 * `K_bt_link` của mọi cặp chưa thu hồi khi kênh mở (một session mới thay session cũ, 0.4.1). Cửa sổ ghép đôi QR
 * ([BluetoothQrPairingHooks], 0.4.5 "Pairing mode"): `pr_bt` đứng đầu quảng bá, `K_bt_plink` được thử trước mọi cặp
 * qua MỘT [BtQrPairingSlot] cho cả listener, và liên kết ghép đôi chạy PAIR-01 ở `PairingCoordinator`.
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
        pairing: BluetoothPairingHooks = BluetoothPairingHooks(),
    ) {
        if (appContext.getSystemService(BluetoothManager::class.java)?.adapter == null) return
        val acceptor =
            acceptor(server.bluetoothAdmission, server::serveBluetoothPeer, pairing, clock) { linkKeys }
        val radioPeripheral = BluetoothPeripheral(AndroidBluetoothRadio(appContext), acceptor::accept, clock)
        peripheral = radioPeripheral
        val discoveryKeys = discoveryKeys(liveSessions)
        // `pr_bt` (QR) và `BT_PIN_HINT` không bao giờ cùng mở (một cửa sổ ghép đôi một lúc, PairingCoordinator).
        val pairingHints = combine(pairing.qr.pairingHints(), pairing.pin.pinHints()) { pr, pin -> pr ?: pin }
        val stateMachine =
            BluetoothLinkLifecycle(
                start = { radioPeripheral.start(scope, discoveryKeys, pairingHints) },
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

    companion object {
        private val NEARBY_DEVICES =
            listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)

        /**
         * Bộ accept của listener: thử `K_bt_plink` (qua MỘT slot cho mọi kênh của listener này) rồi `K_bt_link` của
         * từng cặp; liên kết cặp vào [serve] (`ControlServer.serveBluetoothPeer`), liên kết ghép đôi QR hay PIN (CPace,
         * A7.2-C) vào endpoint của [pairing]. Tách ra để test dựng đúng bộ accept của app mà không cần radio thật.
         */
        fun acceptor(
            admission: BluetoothLinkAdmission,
            serve: suspend (WebSocketSession, String) -> Unit,
            pairing: BluetoothPairingHooks,
            clock: () -> Long,
            linkKeys: () -> List<Pair<String, ByteArray>>,
        ): BluetoothLinkAcceptor {
            val slot = BtQrPairingSlot()
            val qrPairing = pairing.qr
            val pinPairing = pairing.pin
            val pairingKeys = BluetoothPairingKeySource { qrPairing.window()?.currentPlinkKey() }
            val pinWindow =
                object : BluetoothPinPairingWindow {
                    override fun currentPin(): ByteArray? = pinPairing.window()?.currentPin()

                    override fun onPinChannelFailedAfterCpace1() {
                        pinPairing.window()?.onPinChannelFailedAfterCpace1()
                    }
                }
            return BluetoothLinkAcceptor(
                admission = admission,
                serve = serve,
                newSession = {
                    val pinResponder = BtPinPairingResponder(pinWindow)
                    BluetoothPreauthSession(BluetoothPairKeys(linkKeys), pairingKeys, slot, pinResponder)
                },
                servePairing = { socket ->
                    qrPairing.endpoint()?.let { BtQrPairingLink.serve(socket, it, clock) }
                },
                servePinPairing = { socket, kBtPin ->
                    pinPairing.endpoint()?.let { BtPinPairingLink.serve(socket, kBtPin, it, clock) }
                },
            )
        }

        private fun adapterStateOf(state: Int): BluetoothAdapterState =
            when (state) {
                BluetoothAdapter.STATE_ON -> BluetoothAdapterState.ON
                BluetoothAdapter.STATE_TURNING_ON -> BluetoothAdapterState.TURNING_ON
                BluetoothAdapter.STATE_TURNING_OFF -> BluetoothAdapterState.TURNING_OFF
                else -> BluetoothAdapterState.OFF
            }
    }
}

/** Cửa sổ ghép đôi của listener Bluetooth (0.4.5 "Pairing mode"): QR (luồng B) và PIN (luồng C), mỗi lúc chỉ một mở. */
class BluetoothPairingHooks(
    val qr: BluetoothQrPairingHooks = BluetoothQrPairingHooks.NONE,
    val pin: BluetoothPinPairingHooks = BluetoothPinPairingHooks.NONE,
)

/**
 * Cửa sổ ghép đôi QR cho listener Bluetooth (0.4.5 "Pairing mode"), do `ConnectionRuntime` cấp: [window] đọc cửa sổ
 * đang mở ngay lúc cần (khoá `noise1`, `pr_bt`), [windowChanges] phát lại mỗi khi cửa sổ mở hay đóng để quảng bá
 * dựng lại (phải phát ngay một lần lúc đầu, như một `StateFlow`), [endpoint] chạy PAIR-01 (`PairingCoordinator`,
 * như `/v1/pair`).
 */
class BluetoothQrPairingHooks(
    val window: () -> BluetoothQrPairingWindow?,
    val windowChanges: Flow<*>,
    val endpoint: () -> PairingEndpoint?,
) {
    fun pairingHints(): Flow<ByteArray?> = windowChanges.map { window()?.currentPrBt() }

    companion object {
        /** Không có cửa sổ QR nào: phát một lần để quảng bá các cặp vẫn chạy (combine cần một giá trị). */
        val NONE = BluetoothQrPairingHooks({ null }, flowOf(Unit), { null })
    }
}

/**
 * Cửa sổ ghép đôi PIN cho listener Bluetooth (0.4.5 "PIN", A7.2-C), do `ConnectionRuntime` cấp — cùng hình dạng
 * [BluetoothQrPairingHooks]: [window] đọc cửa sổ đang mở ngay lúc cần (PIN cho CPace, bộ đếm sai), [windowChanges]
 * phát lại mỗi khi cửa sổ mở hay đóng để quảng bá `BT_PIN_HINT` dựng lại, [endpoint] chạy PAIR-01 trên liên kết đã
 * xác nhận bằng `K_bt_pinlink`.
 */
class BluetoothPinPairingHooks(
    val window: () -> BluetoothPinPairingWindow?,
    val windowChanges: Flow<*>,
    val endpoint: () -> PairingEndpoint?,
) {
    /** `BT_PIN_HINT` khi có cửa sổ PIN đang mở; marker giống nhau cho mọi điện thoại, không suy ra từ PIN (AC9). */
    fun pinHints(): Flow<ByteArray?> =
        windowChanges.map { if (window()?.currentPin() != null) BluetoothAdvertisementData.PIN_HINT else null }

    companion object {
        /** Không có cửa sổ PIN nào: phát một lần để `combine` ở [BluetoothAdvertising.start] có giá trị. */
        val NONE = BluetoothPinPairingHooks({ null }, flowOf(Unit), { null })
    }
}
