package app.handlive.android.feature.connection.bluetooth

/** Trạng thái adapter Bluetooth tối giản mà vòng đời này cần biết (ánh xạ từ `BluetoothAdapter.STATE_*`). */
enum class BluetoothAdapterState {
    OFF,
    TURNING_ON,
    ON,
    TURNING_OFF,
}

/**
 * Dựng lại advertiser + server L2CAP khi Bluetooth chuyển sang [BluetoothAdapterState.ON], độc lập với
 * `ControlServer` (điều kiện 2 của spike G7: tắt/mở lại Bluetooth phải tự phục hồi, không cần khởi động lại
 * A-SVC). Chỉ quyết định NÊN gọi [start]/[stop] lúc nào; [BluetoothAdvertising] nối [start]/[stop] vào
 * [BluetoothPeripheral] (radio thật: [AndroidBluetoothRadio]) và báo trạng thái từ `ACTION_STATE_CHANGED`.
 */
class BluetoothLinkLifecycle(
    private val start: () -> Unit,
    private val stop: () -> Unit,
) {
    var isRunning: Boolean = false
        private set

    fun onAdapterState(state: BluetoothAdapterState) {
        when (state) {
            BluetoothAdapterState.ON -> {
                if (!isRunning) {
                    isRunning = true
                    start()
                }
            }

            BluetoothAdapterState.OFF, BluetoothAdapterState.TURNING_OFF -> {
                if (isRunning) {
                    isRunning = false
                    stop()
                }
            }

            BluetoothAdapterState.TURNING_ON -> {
                // Chờ STATE_ON: chưa mở được server hay quảng bá.
            }
        }
    }
}
