package app.handlive.android.feature.connection.bluetooth

import app.handlive.android.core.transport.bluetooth.BluetoothLinkIo

/**
 * Phần radio BLE mà A-SVC cần (0.4.5 "Roles", "Advertisement"): server L2CAP CoC không bond và một bộ quảng bá kết
 * nối được. Tách khỏi `android.bluetooth.*` để [BluetoothPeripheral] test được bằng radio giả; bản thật là
 * [AndroidBluetoothRadio].
 */
interface BluetoothRadio {
    /** `listenUsingInsecureL2capChannel()`: PSM LE động, không bond, không GATT. */
    suspend fun listen(): BluetoothServerChannel

    /** Advertisement mang `BT_SERVICE_UUID`, scan response mang [serviceData] (đã gồm `ver` ‖ `psm` ‖ `hints`). */
    fun startAdvertising(serviceData: ByteArray)

    fun stopAdvertising()
}

/** Một server socket L2CAP đang nghe; đóng nó không đóng các kênh đã accept. */
interface BluetoothServerChannel {
    val psm: Int

    /** Chờ kênh kế tiếp; ném khi server đóng hoặc stack Bluetooth bỏ socket. */
    suspend fun accept(): BluetoothLinkIo

    fun close()
}
