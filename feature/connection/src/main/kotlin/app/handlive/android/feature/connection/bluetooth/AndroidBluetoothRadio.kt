package app.handlive.android.feature.connection.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.Context
import android.os.ParcelUuid
import app.handlive.android.core.protocol.bluetooth.BtLinkException
import app.handlive.android.core.transport.bluetooth.BluetoothAdvertisementData
import app.handlive.android.core.transport.bluetooth.BluetoothLinkIo
import app.handlive.android.core.transport.bluetooth.StreamBluetoothLinkIo
import app.handlive.android.core.transport.bluetooth.cancellableBlocking
import app.handlive.android.feature.connection.bench.BenchEvent
import app.handlive.android.feature.connection.bench.BenchLog

/**
 * Radio BLE thật của A-SVC (0.4.5 "Roles", "Advertisement"): `listenUsingInsecureL2capChannel()` (PSM động, không
 * bond, không GATT) và một bộ quảng bá legacy kết nối được, quét được, `INTERVAL_LOW`, `TX_POWER_HIGH` (cấu hình đo
 * của G7), không tên thiết bị, không trường TX power; advertisement chỉ mang `BT_SERVICE_UUID` (21 byte), scan
 * response mang service data (≤ 29 byte).
 *
 * CHƯA ĐO TRÊN MÁY, chờ S25 + Mac: scan response với service data UUID 128-bit (spike G7 chưa thử bố cục này qua
 * `startAdvertisingSet`), địa chỉ radio đổi khi dựng lại bộ quảng bá (AC9), và accept/kết nối thật. Lớp này không có
 * test đơn vị — mọi logic quanh nó ([BluetoothPeripheral], `BluetoothLinkAcceptor`) test qua radio giả.
 *
 * `MissingPermission`: [BluetoothAdvertising] chỉ dựng lớp này khi "Nearby devices" (`BLUETOOTH_ADVERTISE`,
 * `BLUETOOTH_CONNECT`) đã được cấp trên Android 12+; quyền bị thu hồi giữa chừng thì lệnh gọi ném `SecurityException`
 * và [BluetoothPeripheral] thử lại sau.
 */
@SuppressLint("MissingPermission")
class AndroidBluetoothRadio(
    context: Context,
) : BluetoothRadio {
    private val manager = context.getSystemService(BluetoothManager::class.java)

    @Volatile
    private var callback: AdvertisingSetCallback? = null

    override suspend fun listen(): BluetoothServerChannel {
        val adapter = manager?.adapter ?: throw BtLinkException("no Bluetooth adapter")
        return AndroidServerChannel(cancellableBlocking({}) { adapter.listenUsingInsecureL2capChannel() })
    }

    override fun startAdvertising(serviceData: ByteArray) {
        val advertiser = manager?.adapter?.bluetoothLeAdvertiser ?: throw BtLinkException("no LE advertiser")
        val uuid = ParcelUuid(BluetoothAdvertisementData.SERVICE_UUID)
        val parameters =
            AdvertisingSetParameters
                .Builder()
                .setLegacyMode(true)
                .setConnectable(true)
                .setScannable(true)
                .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                .build()
        val advertisement =
            AdvertiseData
                .Builder()
                .addServiceUuid(uuid)
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build()
        val scanResponse =
            AdvertiseData
                .Builder()
                .addServiceData(uuid, serviceData)
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build()
        val psm = (serviceData[1].toInt() and BYTE_MASK) or ((serviceData[2].toInt() and BYTE_MASK) shl Byte.SIZE_BITS)
        val hints = (serviceData.size - SERVICE_DATA_HEADER) / BluetoothAdvertisementData.HINT_SIZE
        BenchLog.event(BenchEvent.BT_ADVERTISE, "psm" to psm, "hints" to hints)
        val started =
            object : AdvertisingSetCallback() {
                override fun onAdvertisingSetStarted(
                    advertisingSet: AdvertisingSet?,
                    txPower: Int,
                    status: Int,
                ) {
                    BenchLog.event(BenchEvent.BT_ADVERTISE, "psm" to psm, "status" to status)
                }
            }
        callback = started
        advertiser.startAdvertisingSet(parameters, advertisement, scanResponse, null, null, started)
    }

    override fun stopAdvertising() {
        val started = callback ?: return
        callback = null
        runCatching { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(started) }
    }

    private class AndroidServerChannel(
        private val socket: BluetoothServerSocket,
    ) : BluetoothServerChannel {
        override val psm: Int = socket.psm

        override suspend fun accept(): BluetoothLinkIo {
            val client = cancellableBlocking(::close) { socket.accept() }
            BenchLog.event(BenchEvent.BT_ACCEPT, "psm" to psm)
            return StreamBluetoothLinkIo(client.inputStream, client.outputStream, client::close)
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    private companion object {
        const val BYTE_MASK = 0xFF
        const val SERVICE_DATA_HEADER = 3
    }
}
